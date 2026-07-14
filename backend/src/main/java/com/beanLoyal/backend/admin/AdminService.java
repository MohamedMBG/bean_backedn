package com.beanLoyal.backend.admin;

import com.beanLoyal.backend.activity.ActivityService;
import com.beanLoyal.backend.audit.AuditService;
import com.beanLoyal.backend.common.ApiException;
import com.beanLoyal.backend.common.ApiResponse;
import com.beanLoyal.backend.common.IdempotencyService;
import com.beanLoyal.backend.loyalty.EarnCodeService;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Admin operations behind {@code /api/v1/admin} ({@code docs/BACKEND_IMPLEMENTATION_PLAN.md §10},
 * BUSINESS_RULES §2/§5b). Three transactional writes (create/revoke earn code, points adjustment)
 * returning {@link IdempotencyService.BusinessOutcome}; three non-transactional reads (user search,
 * user activity, audit list) returning DTOs.
 * <p>
 * Every mutation writes an atomic {@code audit/{id}} entry inside the same idempotency transaction.
 * The {@code actorUid} always comes from the verified admin token, never the request body.
 */
@Service
public class AdminService {

    /** Default page size for read endpoints when the caller omits/zeroes {@code limit}. */
    static final int DEFAULT_LIMIT = 50;
    /** Hard cap so a caller cannot request an unbounded read. */
    static final int MAX_LIMIT = 100;
    /** Cap on user-search matches. */
    static final int SEARCH_LIMIT = 20;

    private final Firestore firestore;
    private final FirebaseAuth firebaseAuth;
    private final EarnCodeService earnCodeService;
    private final AuditService auditService;
    private final ActivityService activityService;
    private final Clock clock;

    public AdminService(Firestore firestore, FirebaseAuth firebaseAuth, EarnCodeService earnCodeService,
                        AuditService auditService, ActivityService activityService, Clock clock) {
        this.firestore = firestore;
        this.firebaseAuth = firebaseAuth;
        this.earnCodeService = earnCodeService;
        this.auditService = auditService;
        this.activityService = activityService;
        this.clock = clock;
    }

    // ---- writes (transactional) -------------------------------------------------------------

    /**
     * Create a new active earn code for a {@code amountMad} purchase (§2.1/§2.2) and audit it. The
     * point value is derived server-side at the fixed {@code POINTS_PER_MAD} ratio; the money amount
     * is stored on the code so the dashboard can sum revenue.
     *
     * @throws ApiException 400 {@code INVALID_AMOUNT} if {@code amountMad} is null or not positive.
     */
    public IdempotencyService.BusinessOutcome createEarnCode(com.google.cloud.firestore.Transaction tx,
                                                             String actorUid, Double amountMad) {
        validateAmount(amountMad);
        long points = EarnCodeService.pointsForAmount(amountMad);
        EarnCodeService.CreatedCode created = earnCodeService.create(tx, amountMad, points, actorUid, Instant.now(clock));
        auditService.record(tx, actorUid, "earn_code.create", created.code(), null,
                Map.of("code", created.code(), "amountMad", amountMad, "points", points));
        return new IdempotencyService.BusinessOutcome(HttpStatus.OK, ApiResponse.of(
                new CreateEarnCodeResponse(created.code(), amountMad, points, created.expiresAt().toEpochMilli())));
    }

    /**
     * Revoke an active earn code (§2) and audit it.
     *
     * @throws ApiException 404 {@code EARN_CODE_NOT_FOUND} / 409 {@code EARN_CODE_NOT_ACTIVE}.
     */
    public IdempotencyService.BusinessOutcome revokeEarnCode(com.google.cloud.firestore.Transaction tx,
                                                             String actorUid, String codeId)
            throws ExecutionException, InterruptedException {
        earnCodeService.revoke(tx, codeId);
        auditService.record(tx, actorUid, "earn_code.revoke", codeId, null, Map.of("code", codeId));
        return new IdempotencyService.BusinessOutcome(HttpStatus.OK,
                ApiResponse.of(new RevokeEarnCodeResponse(codeId, EarnCodeService.STATUS_REVOKED)));
    }

    /**
     * Apply a manual signed points adjustment to {@code targetUid} (§10), writing a canonical
     * {@code adjust} activity entry and an audit entry atomically. Reads the user doc before any
     * write (read-before-write).
     *
     * @throws ApiException 400 {@code INVALID_ADJUSTMENT}/{@code ADJUSTMENT_REASON_REQUIRED},
     *                      404 {@code USER_NOT_FOUND}, 422 {@code ADJUSTMENT_NEGATIVE_BALANCE}.
     */
    public IdempotencyService.BusinessOutcome adjustPoints(com.google.cloud.firestore.Transaction tx,
                                                           String actorUid, String targetUid,
                                                           Integer delta, String reason)
            throws ExecutionException, InterruptedException {
        validateAdjustment(delta, reason);
        DocumentReference userRef = firestore.collection("users").document(targetUid);
        DocumentSnapshot userSnap = tx.get(userRef).get();
        if (!userSnap.exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User profile not found");
        }
        long newBalance = adjustedBalance(orZero(userSnap.getLong("points")), delta);

        tx.update(userRef, "points", newBalance);
        // refId null: an adjustment has no source code/claim. The reason lives on the audit entry.
        activityService.record(tx, targetUid, ActivityService.TYPE_ADJUST, delta, null, newBalance);
        auditService.record(tx, actorUid, "points.adjust", null, targetUid,
                Map.of("delta", delta, "reason", reason, "balanceAfter", newBalance));
        return new IdempotencyService.BusinessOutcome(HttpStatus.OK,
                ApiResponse.of(new PointsAdjustmentResponse(delta, newBalance, reason)));
    }

    /**
     * Create a {@code rewards_catalog} entry (§10) and audit it. Generates the document id.
     * {@code active} defaults to {@code true} when the request omits it.
     *
     * @throws ApiException 400 {@code INVALID_REWARD} if name is blank or cost is null/negative.
     */
    public IdempotencyService.BusinessOutcome createReward(com.google.cloud.firestore.Transaction tx,
                                                           String actorUid, RewardRequest request) {
        validateReward(request);
        boolean active = request.active() == null || request.active();
        DocumentReference ref = firestore.collection("rewards_catalog").document();
        Map<String, Object> doc = rewardDoc(request, active);
        tx.set(ref, doc);
        auditService.record(tx, actorUid, "reward.create", ref.getId(), null, doc);
        return new IdempotencyService.BusinessOutcome(HttpStatus.OK, ApiResponse.of(new RewardResponse(
                ref.getId(), request.name(), request.cost(), request.category(), request.imageUrl(), active)));
    }

    /**
     * Overwrite an existing {@code rewards_catalog} entry (§10) and audit it. Full replace, matching
     * the admin editor's semantics. Reads the doc before writing (read-before-write).
     *
     * @throws ApiException 400 {@code INVALID_REWARD}, 404 {@code REWARD_NOT_FOUND}.
     */
    public IdempotencyService.BusinessOutcome updateReward(com.google.cloud.firestore.Transaction tx,
                                                           String actorUid, String rewardId,
                                                           RewardRequest request)
            throws ExecutionException, InterruptedException {
        validateReward(request);
        DocumentReference ref = firestore.collection("rewards_catalog").document(rewardId);
        if (!tx.get(ref).get().exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REWARD_NOT_FOUND", "Reward not found");
        }
        boolean active = request.active() == null || request.active();
        Map<String, Object> doc = rewardDoc(request, active);
        tx.set(ref, doc);
        auditService.record(tx, actorUid, "reward.update", rewardId, null, doc);
        return new IdempotencyService.BusinessOutcome(HttpStatus.OK, ApiResponse.of(new RewardResponse(
                rewardId, request.name(), request.cost(), request.category(), request.imageUrl(), active)));
    }

    /**
     * Hard-delete a {@code rewards_catalog} entry (§10) and audit it. Reads the doc before deleting.
     * Existing pending redeem codes are unaffected — they carry their own cost snapshot.
     *
     * @throws ApiException 404 {@code REWARD_NOT_FOUND}.
     */
    public IdempotencyService.BusinessOutcome deleteReward(com.google.cloud.firestore.Transaction tx,
                                                           String actorUid, String rewardId)
            throws ExecutionException, InterruptedException {
        DocumentReference ref = firestore.collection("rewards_catalog").document(rewardId);
        if (!tx.get(ref).get().exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REWARD_NOT_FOUND", "Reward not found");
        }
        tx.delete(ref);
        auditService.record(tx, actorUid, "reward.delete", rewardId, null, null);
        return new IdempotencyService.BusinessOutcome(HttpStatus.OK,
                ApiResponse.of(new RewardDeletedResponse(rewardId, true)));
    }

    /**
     * Provision a cashier account (§5b/§10): create the Firebase Auth user, grant the
     * {@code role: cashier} custom claim (mapped to {@code ROLE_CASHIER} by {@code FirebaseAuthFilter}),
     * and write the {@code users/{uid}} profile doc via the Admin SDK. NOT a Firestore transaction —
     * the auth-account creation is its own idempotency guard (a duplicate email fails). Audited via
     * the non-transactional {@link AuditService#record(String, String, String, String, java.util.Map)}.
     * The password is used once to create the account and is never stored or logged.
     *
     * @throws ApiException 400 {@code INVALID_CASHIER} (bad email / short password), 409
     *                      {@code CASHIER_EMAIL_EXISTS}, 500 {@code CASHIER_CLAIM_FAILED}.
     */
    public CreateCashierResponse createCashier(String actorUid, String email, String password, String name)
            throws ExecutionException, InterruptedException {
        validateCashier(email, password);
        String cleanEmail = email.trim();

        UserRecord.CreateRequest req = new UserRecord.CreateRequest().setEmail(cleanEmail).setPassword(password);
        if (name != null && !name.isBlank()) req.setDisplayName(name.trim());

        UserRecord user;
        try {
            user = firebaseAuth.createUser(req);
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.EMAIL_ALREADY_EXISTS) {
                throw new ApiException(HttpStatus.CONFLICT, "CASHIER_EMAIL_EXISTS",
                        "That email is already registered");
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CASHIER",
                    "Could not create the cashier account");
        }

        try {
            firebaseAuth.setCustomUserClaims(user.getUid(), Map.of("role", "cashier"));
        } catch (FirebaseAuthException e) {
            // Roll back the orphaned auth account so a retry with the same email can succeed rather
            // than dead-end on EMAIL_ALREADY_EXISTS.
            try {
                firebaseAuth.deleteUser(user.getUid());
            } catch (FirebaseAuthException ignored) {
                // Best-effort cleanup; surface the original failure regardless.
            }
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "CASHIER_CLAIM_FAILED",
                    "Account created but role assignment failed — please retry");
        }

        Map<String, Object> doc = new java.util.HashMap<>();
        doc.put("uid", user.getUid());
        doc.put("name", name);
        doc.put("email", cleanEmail);
        doc.put("role", "cashier");
        doc.put("isActive", true);
        doc.put("createdAt", FieldValue.serverTimestamp());
        firestore.collection("users").document(user.getUid()).set(doc).get();

        auditService.record(actorUid, "cashier.create", user.getUid(), user.getUid(),
                Map.of("email", cleanEmail));
        return new CreateCashierResponse(user.getUid(), cleanEmail);
    }

    // ---- reads (non-transactional) ----------------------------------------------------------

    /**
     * Find users by exact email or phone (§10). Exactly one criterion is used; email takes
     * precedence if both are given.
     *
     * @throws ApiException         400 {@code SEARCH_CRITERIA_REQUIRED} if both are blank.
     * @throws ExecutionException   propagated from the Firestore query.
     * @throws InterruptedException propagated from the Firestore query.
     */
    public UserSearchResponse searchUsers(String email, String phone)
            throws ExecutionException, InterruptedException {
        boolean hasEmail = email != null && !email.isBlank();
        boolean hasPhone = phone != null && !phone.isBlank();
        if (!hasEmail && !hasPhone) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SEARCH_CRITERIA_REQUIRED", "Provide email or phone");
        }
        Query query = hasEmail
                ? firestore.collection("users").whereEqualTo("email", email.trim())
                : firestore.collection("users").whereEqualTo("phone", phone.trim());
        List<UserSearchResponse.UserSummary> users = new ArrayList<>();
        for (QueryDocumentSnapshot doc : query.limit(SEARCH_LIMIT).get().get().getDocuments()) {
            users.add(new UserSearchResponse.UserSummary(doc.getId(), doc.getString("email"),
                    doc.getString("phone"), orZero(doc.getLong("points")), orZero(doc.getLong("visits"))));
        }
        return new UserSearchResponse(users);
    }

    /**
     * List the admin client roster (§10). Capped, unordered — a flat page of up to
     * {@link #MAX_LIMIT} users. The client sorts/displays; {@code createdAt} is returned so it can.
     *
     * @throws ExecutionException   propagated from the Firestore query.
     * @throws InterruptedException propagated from the Firestore query.
     */
    public UserListResponse listUsers(int limit) throws ExecutionException, InterruptedException {
        // ponytail: no pagination cursor — a single capped page. Add startAfter(...) paging when
        // the user base outgrows one page. Unordered so users missing createdAt aren't dropped.
        Query query = firestore.collection("users").limit(cap(limit));
        List<UserListResponse.UserListItem> users = new ArrayList<>();
        for (QueryDocumentSnapshot doc : query.get().get().getDocuments()) {
            users.add(new UserListResponse.UserListItem(doc.getId(), doc.getString("fullName"),
                    doc.getString("email"), doc.getString("phone"), orZero(doc.getLong("points")),
                    orZero(doc.getLong("visits")), epochMillis(doc.getTimestamp("createdAt"))));
        }
        return new UserListResponse(users);
    }

    /**
     * A single user's full profile for the admin client-details header (§10).
     *
     * @throws ApiException 404 {@code USER_NOT_FOUND} if the user doc does not exist.
     */
    public UserDetailResponse getUser(String uid) throws ExecutionException, InterruptedException {
        DocumentSnapshot doc = firestore.collection("users").document(uid).get().get();
        if (!doc.exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User profile not found");
        }
        return new UserDetailResponse(doc.getId(), doc.getString("fullName"), doc.getString("email"),
                doc.getString("phone"), doc.getString("gender"), doc.getString("address"),
                doc.getString("birthday"), orZero(doc.getLong("points")), orZero(doc.getLong("visits")),
                epochMillis(doc.getTimestamp("createdAt")), epochMillis(doc.getTimestamp("lastEarnAt")));
    }

    /**
     * List a user's most-recent activity entries (§10).
     *
     * @throws ApiException 404 {@code USER_NOT_FOUND} if the user doc does not exist.
     */
    public UserActivityResponse getUserActivity(String uid, int limit)
            throws ExecutionException, InterruptedException {
        // The existence check and the activities read are independent, so overlap their round-trips:
        // dispatch both, then validate existence before consuming the activities result.
        var userFuture = firestore.collection("users").document(uid).get();
        var activitiesFuture = firestore.collection("users").document(uid).collection("activities")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(cap(limit))
                .get();
        if (!userFuture.get().exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User profile not found");
        }
        List<UserActivityResponse.ActivityEntry> entries = new ArrayList<>();
        for (QueryDocumentSnapshot doc : activitiesFuture.get().getDocuments()) {
            entries.add(new UserActivityResponse.ActivityEntry(
                    doc.getString("type"), orZero(doc.getLong("pointsDelta")), doc.getString("refId"),
                    orZero(doc.getLong("balanceAfter")), epochMillis(doc.getTimestamp("createdAt"))));
        }
        return new UserActivityResponse(uid, entries);
    }

    /**
     * List the most-recent audit entries (§10).
     */
    @SuppressWarnings("unchecked")
    public AuditListResponse listAudit(int limit) throws ExecutionException, InterruptedException {
        Query query = firestore.collection("audit")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(cap(limit));
        List<AuditListResponse.AuditEntry> entries = new ArrayList<>();
        for (QueryDocumentSnapshot doc : query.get().get().getDocuments()) {
            Object details = doc.get("details");
            entries.add(new AuditListResponse.AuditEntry(
                    doc.getString("actorUid"), doc.getString("action"), doc.getString("targetId"),
                    doc.getString("targetUid"),
                    details instanceof Map ? (Map<String, Object>) details : null,
                    epochMillis(doc.getTimestamp("createdAt"))));
        }
        return new AuditListResponse(entries);
    }

    // ---- pure helpers (unit-tested) ---------------------------------------------------------

    static void validateAmount(Double amountMad) {
        if (amountMad == null || amountMad <= 0 || amountMad.isNaN() || amountMad.isInfinite()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "amountMad must be a positive number");
        }
    }

    static void validateReward(RewardRequest r) {
        if (r == null || r.name() == null || r.name().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REWARD", "name is required");
        }
        if (r.cost() == null || r.cost() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REWARD", "cost must be a non-negative integer");
        }
    }

    /** Build the {@code rewards_catalog} document. Optional fields are written only when present. */
    private static Map<String, Object> rewardDoc(RewardRequest r, boolean active) {
        Map<String, Object> doc = new java.util.HashMap<>();
        doc.put("name", r.name().trim());
        doc.put("cost", r.cost());
        doc.put("active", active);
        if (r.category() != null) doc.put("category", r.category());
        if (r.imageUrl() != null) doc.put("imageUrl", r.imageUrl());
        return doc;
    }

    static void validateCashier(String email, String password) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CASHIER", "A valid email is required");
        }
        if (password == null || password.length() < 6) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CASHIER",
                    "Password must be at least 6 characters");
        }
    }

    static void validateAdjustment(Integer delta, String reason) {
        if (delta == null || delta == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ADJUSTMENT", "delta must be a non-zero integer");
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADJUSTMENT_REASON_REQUIRED", "reason is required");
        }
    }

    static long adjustedBalance(long current, long delta) {
        long next = current + delta;
        if (next < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ADJUSTMENT_NEGATIVE_BALANCE",
                    "Adjustment would drive balance below zero");
        }
        return next;
    }

    static int cap(int limit) {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private static long epochMillis(Timestamp timestamp) {
        return timestamp == null ? 0L : timestamp.toDate().getTime();
    }
}
