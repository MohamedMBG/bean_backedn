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
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
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
    private final EarnCodeService earnCodeService;
    private final AuditService auditService;
    private final ActivityService activityService;
    private final Clock clock;

    public AdminService(Firestore firestore, EarnCodeService earnCodeService, AuditService auditService,
                        ActivityService activityService, Clock clock) {
        this.firestore = firestore;
        this.earnCodeService = earnCodeService;
        this.auditService = auditService;
        this.activityService = activityService;
        this.clock = clock;
    }

    // ---- writes (transactional) -------------------------------------------------------------

    /**
     * Create a new active earn code worth {@code points} (§2.1/§2.2) and audit it.
     *
     * @throws ApiException 400 {@code INVALID_POINTS} if not a positive integer.
     */
    public IdempotencyService.BusinessOutcome createEarnCode(com.google.cloud.firestore.Transaction tx,
                                                             String actorUid, Integer points) {
        validatePoints(points);
        EarnCodeService.CreatedCode created = earnCodeService.create(tx, points, actorUid, Instant.now(clock));
        auditService.record(tx, actorUid, "earn_code.create", created.code(), null,
                Map.of("code", created.code(), "points", points));
        return new IdempotencyService.BusinessOutcome(HttpStatus.OK,
                ApiResponse.of(new CreateEarnCodeResponse(created.code(), points, created.expiresAt().toEpochMilli())));
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
     * List a user's most-recent activity entries (§10).
     *
     * @throws ApiException 404 {@code USER_NOT_FOUND} if the user doc does not exist.
     */
    public UserActivityResponse getUserActivity(String uid, int limit)
            throws ExecutionException, InterruptedException {
        if (!firestore.collection("users").document(uid).get().get().exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User profile not found");
        }
        Query query = firestore.collection("users").document(uid).collection("activities")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(cap(limit));
        List<UserActivityResponse.ActivityEntry> entries = new ArrayList<>();
        for (QueryDocumentSnapshot doc : query.get().get().getDocuments()) {
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

    static void validatePoints(Integer points) {
        if (points == null || points <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_POINTS", "points must be a positive integer");
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
