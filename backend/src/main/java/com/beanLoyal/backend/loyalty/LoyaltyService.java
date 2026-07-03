package com.beanLoyal.backend.loyalty;

import com.beanLoyal.backend.common.ApiException;
import com.beanLoyal.backend.common.ApiResponse;
import com.beanLoyal.backend.common.IdempotencyService;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Grants points for a QR earn-code scan per {@code docs/BUSINESS_RULES.md §2}.
 * <p>
 * Invoked as an {@link IdempotencyService.TransactionalWork} from {@link LoyaltyController} —
 * every read and write here runs inside the caller-supplied Firestore {@link Transaction},
 * sharing the same commit as the idempotency record write, so a retried scan can never double-grant
 * points or double-burn a code even before the earn code's own {@code active/used} status is checked.
 *
 * <h2>Schema assumptions</h2>
 * <ul>
 *   <li>{@code users/{uid}.points} — integer balance, backend-owned (matches §2.1).</li>
 *   <li>{@code users/{uid}.visits} — integer counter, incremented by 1 on every successful earn,
 *       no per-day branching (§2.7).</li>
 *   <li>{@code users/{uid}.lastEarnAt} — Firestore timestamp, read for the §2.4 cooldown check and
 *       overwritten with {@code FieldValue.serverTimestamp()} on every successful earn.</li>
 *   <li>{@code users/{uid}} is assumed to already exist for any authenticated caller (created at
 *       account signup, out of this backend's scope) — same assumption as
 *       {@link com.beanLoyal.backend.rewards.BirthdayRewardService}. If it does not, the
 *       transaction's {@code update} call fails at commit and surfaces as a generic 500.</li>
 * </ul>
 */
@Service
public class LoyaltyService {

    /** Minimum gap between two successful earns for the same uid, across all codes (§2.4). */
    static final Duration VISIT_COOLDOWN = Duration.ofMinutes(30);

    private final Firestore firestore;
    private final EarnCodeService earnCodeService;

    public LoyaltyService(Firestore firestore, EarnCodeService earnCodeService) {
        this.firestore = firestore;
        this.earnCodeService = earnCodeService;
    }

    /**
     * Validate and process a QR earn-code scan for {@code uid} inside {@code transaction}.
     * <p>
     * Order of checks: code format (§2.5, no Firestore access) → code exists/not-expired/not-used
     * (§2.2/§2.3, {@link EarnCodeService#readValid}) → visit cooldown (§2.4). All reads (earn code
     * doc, user doc) happen before any write, satisfying Firestore's read-before-write transaction
     * rule; the code is burned and the user doc updated only after every check passes.
     *
     * @param transaction live Firestore transaction supplied by {@code IdempotencyService}; every
     *                    Firestore access in this method MUST use it, never a nested transaction.
     * @param uid         verified Firebase UID of the caller (never trust a client-supplied id).
     * @param code        raw earn code from the request body.
     * @return {@link IdempotencyService.BusinessOutcome} wrapping {@code 200} + {@link ApiResponse}
     *         of {@link EarnResponse} on success.
     * @throws ApiException         on any business-rule rejection (§2.8: {@code EARN_CODE_INVALID_FORMAT},
     *                              {@code EARN_CODE_NOT_FOUND}, {@code EARN_CODE_EXPIRED},
     *                              {@code EARN_CODE_ALREADY_USED}, {@code VISIT_COOLDOWN}); aborts the
     *                              transaction before any write is staged.
     * @throws ExecutionException   propagated from the underlying Firestore {@code get()} calls.
     * @throws InterruptedException propagated from the underlying Firestore {@code get()} calls.
     */
    public IdempotencyService.BusinessOutcome earn(Transaction transaction, String uid, String code)
            throws ExecutionException, InterruptedException {

        EarnCodeService.validateFormat(code);

        Instant now = Instant.now();
        EarnCodeService.ValidatedCode validCode = earnCodeService.readValid(transaction, code, now);

        DocumentReference userRef = firestore.collection("users").document(uid);
        DocumentSnapshot userSnap = transaction.get(userRef).get();

        checkCooldown(userSnap, now);

        long totalPoints = orZero(userSnap.getLong("points")) + validCode.points();
        long totalVisits = orZero(userSnap.getLong("visits")) + 1;

        earnCodeService.burn(transaction, validCode.ref());

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("points", totalPoints);
        updates.put("visits", totalVisits);
        updates.put("lastEarnAt", FieldValue.serverTimestamp());
        transaction.update(userRef, updates);

        EarnResponse body = new EarnResponse(validCode.points(), totalPoints, totalVisits);
        return new IdempotencyService.BusinessOutcome(HttpStatus.OK, ApiResponse.of(body));
    }

    /**
     * Reject the scan if the caller earned within the last {@link #VISIT_COOLDOWN} (§2.4). A user
     * doc with no prior {@code lastEarnAt} (new user, or first-ever earn) always passes.
     *
     * @throws ApiException 429 {@code VISIT_COOLDOWN} if the cooldown window has not elapsed.
     */
    private void checkCooldown(DocumentSnapshot userSnap, Instant now) {
        if (!userSnap.exists()) {
            return;
        }
        Timestamp lastEarnAt = userSnap.getTimestamp("lastEarnAt");
        if (lastEarnAt == null) {
            return;
        }
        if (isCoolingDown(lastEarnAt.toDate().toInstant(), now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "VISIT_COOLDOWN",
                    "Please wait before earning again");
        }
    }

    /**
     * True if {@code now} falls inside the {@link #VISIT_COOLDOWN} window started by {@code lastEarnAt}
     * (§2.4). Package-private + static: pure function, unit-tested directly without a Firestore mock.
     */
    static boolean isCoolingDown(Instant lastEarnAt, Instant now) {
        return now.isBefore(lastEarnAt.plus(VISIT_COOLDOWN));
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
