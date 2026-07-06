package com.beanLoyal.backend.rewards;

import com.beanLoyal.backend.activity.ActivityService;
import com.beanLoyal.backend.common.ApiException;
import com.beanLoyal.backend.common.ApiResponse;
import com.beanLoyal.backend.common.IdempotencyService;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.Transaction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;

/**
 * Creates a pending redeem code, deducting the reward's points cost, per
 * {@code docs/BUSINESS_RULES.md §3} (Phase 6 {@code POST /api/v1/rewards/redeem}).
 * <p>
 * Invoked as an {@link IdempotencyService.TransactionalWork} from {@link RewardsController} —
 * every read and write here runs inside the caller-supplied Firestore {@link Transaction}, sharing
 * the same commit as the idempotency record write, so a retried redeem can never deduct points or
 * create a redeem code twice.
 *
 * <h2>Schema assumptions</h2>
 * <ul>
 *   <li>{@code rewards_catalog/{rewardId}} — {@code name} (string), {@code cost} (non-negative
 *       integer points), {@code active} (boolean) per §3.8.</li>
 *   <li>{@code users/{uid}.points} — integer balance, backend-owned (§2.1); missing doc fails fast
 *       with {@code USER_NOT_FOUND} rather than a generic 500 at commit, matching
 *       {@link com.beanLoyal.backend.loyalty.LoyaltyService}.</li>
 *   <li>{@code redeem_codes/{code}} — created here with {@code status: "pending"} and a 15-minute
 *       {@code expiresAt} (§3.1). {@code cost} is stored on the doc so the Phase 7 cancel/expire
 *       refund reads it from the code itself, not from an activity log.</li>
 * </ul>
 *
 * <h2>Required Firestore index</h2>
 * The §3.4 "at most one pending code per user" check queries {@code redeem_codes} filtered by
 * {@code uid == caller AND status == "pending"} — two equality filters, so Firestore needs a
 * composite index on {@code (uid, status)}. It must be added with the Phase 1 Firestore rules/index
 * deploy or the first redeem will fail with {@code FAILED_PRECONDITION}. The query is the source of
 * truth (over a pointer field on the user doc) so a Phase 7 cancel/complete/expire path that forgets
 * to clear a pointer cannot permanently lock a user out of redeeming.
 *
 * <h2>No activity log yet</h2>
 * ponytail: the canonical activity schema is Phase 8 (not started). The {@code redeem_codes} doc
 * fully records this redemption for now; the user-facing activity entry (and the matching
 * cancel/expire entries §3.2/§3.3 reference) land together in Phase 8 rather than guessing a schema
 * here that would then be rewritten.
 */
@Service
public class RewardRedemptionService {

    /** Pending redeem code lifetime before the Phase 7 expiration job refunds and expires it (§3.1). */
    static final Duration PENDING_TTL = Duration.ofMinutes(15);

    private final Firestore firestore;
    private final RedeemCodeService redeemCodeService;
    private final ActivityService activityService;
    private final Clock clock;

    public RewardRedemptionService(Firestore firestore, RedeemCodeService redeemCodeService,
                                   ActivityService activityService, Clock clock) {
        this.firestore = firestore;
        this.redeemCodeService = redeemCodeService;
        this.activityService = activityService;
        this.clock = clock;
    }

    /**
     * Validate and create a pending redeem code for {@code uid} against {@code rewardId} inside
     * {@code transaction}.
     * <p>
     * Order of checks matches the §3.9 error table: reward exists ({@code REWARD_NOT_FOUND} 404) →
     * reward active ({@code REWARD_INACTIVE} 410) → user exists ({@code USER_NOT_FOUND} 404) → no
     * existing pending code ({@code REDEEM_PENDING_LIMIT} 409, §3.4) → enough points
     * ({@code INSUFFICIENT_POINTS} 422, §3.5). All three reads (reward doc, user doc, pending query)
     * happen before any write, satisfying Firestore's read-before-write transaction rule; the
     * balance is deducted and the code created only after every check passes.
     *
     * @param transaction live Firestore transaction supplied by {@code IdempotencyService}; every
     *                    Firestore access in this method MUST use it, never a nested transaction.
     * @param uid         verified Firebase UID of the caller (never trust a client-supplied id).
     * @param rewardId    client-supplied target reward id (re-validated here, never trusted).
     * @return {@link IdempotencyService.BusinessOutcome} wrapping {@code 200} + {@link ApiResponse}
     *         of {@link RedeemResponse} on success.
     * @throws ApiException          on any §3.9 business-rule rejection; aborts the transaction
     *                               before any write is staged.
     * @throws IllegalStateException if the reward doc exists but is missing a valid {@code cost} —
     *                               a data-integrity bug, not a client error; surfaces as 500.
     * @throws ExecutionException    propagated from the underlying Firestore {@code get()} calls.
     * @throws InterruptedException  propagated from the underlying Firestore {@code get()} calls.
     */
    public IdempotencyService.BusinessOutcome redeem(Transaction transaction, String uid, String rewardId)
            throws ExecutionException, InterruptedException {

        DocumentReference rewardRef = firestore.collection("rewards_catalog").document(rewardId);
        DocumentSnapshot rewardSnap = transaction.get(rewardRef).get();
        if (!rewardSnap.exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REWARD_NOT_FOUND", "Reward not found");
        }
        // §3.6: a new redemption against an inactive reward is rejected; existing pending codes are
        // unaffected (they were created while the reward was active and stay completable/cancellable).
        if (!Boolean.TRUE.equals(rewardSnap.getBoolean("active"))) {
            throw new ApiException(HttpStatus.GONE, "REWARD_INACTIVE", "Reward is no longer available");
        }
        Long cost = rewardSnap.getLong("cost");
        if (cost == null || cost < 0) {
            throw new IllegalStateException("rewards_catalog/" + rewardId + " missing a valid cost field");
        }

        DocumentReference userRef = firestore.collection("users").document(uid);
        DocumentSnapshot userSnap = transaction.get(userRef).get();
        if (!userSnap.exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User profile not found");
        }
        long balance = orZero(userSnap.getLong("points"));

        // §3.4: at most one pending redeem code per user. Composite index on (uid, status) required —
        // see class Javadoc. Read must precede the writes below.
        Query pendingQuery = firestore.collection(RedeemCode.COLLECTION)
                .whereEqualTo(RedeemCode.UID, uid)
                .whereEqualTo(RedeemCode.STATUS, RedeemCode.STATUS_PENDING);
        QuerySnapshot pendingSnap = transaction.get(pendingQuery).get();
        if (!pendingSnap.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "REDEEM_PENDING_LIMIT",
                    "You already have a pending redeem code");
        }

        // §3.5: insufficient balance leaves the balance untouched, no partial redeem.
        if (balance < cost) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_POINTS",
                    "Not enough points for this reward");
        }

        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(PENDING_TTL);
        String code = redeemCodeService.generateCode();
        long newBalance = balance - cost;

        transaction.set(
                firestore.collection(RedeemCode.COLLECTION).document(code),
                RedeemCode.pendingDoc(uid, rewardId, rewardSnap.getString("name"), cost, now, expiresAt));
        transaction.update(userRef, "points", newBalance);
        // Canonical activity feed entry (§11): a redemption debits the balance, so pointsDelta is negative.
        activityService.record(transaction, uid, ActivityService.TYPE_REDEEM, -cost, code, newBalance);

        RedeemResponse body = new RedeemResponse(code, rewardId, cost, newBalance, expiresAt.toEpochMilli());
        return new IdempotencyService.BusinessOutcome(HttpStatus.OK, ApiResponse.of(body));
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
