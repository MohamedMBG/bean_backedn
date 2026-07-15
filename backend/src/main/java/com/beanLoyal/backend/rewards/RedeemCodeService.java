package com.beanLoyal.backend.rewards;

import com.beanLoyal.backend.activity.ActivityService;
import com.beanLoyal.backend.audit.AuditService;
import com.beanLoyal.backend.cashier.CashierCompleteResponse;
import com.beanLoyal.backend.common.ApiException;
import com.beanLoyal.backend.common.ApiResponse;
import com.beanLoyal.backend.common.IdempotencyService;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.Transaction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Generates pending redeem codes and drives their lifecycle transitions per
 * {@code docs/BUSINESS_RULES.md §3}.
 * <p>
 * A redeem code is the {@code redeem_codes/{code}} document id the customer shows the cashier
 * (§3.1); it uses a human-readable alphabet so a cashier can type it off a phone screen when the QR
 * is unscannable.
 * <p>
 * Three terminal paths converge on one shared {@link #refund} helper (status flip +
 * {@code users/{uid}.points += cost} + activity entry, all in one transaction):
 * <ul>
 *   <li>{@link #cancel} — user cancels their own pending code, refunded (§3.2).</li>
 *   <li>{@link #expireCode} — the scheduled sweep expires a past-TTL pending code, refunded (§3.3).</li>
 * </ul>
 * {@link #complete} is the cashier fulfilment path: it flips {@code pending → completed} with no
 * refund (the customer got their reward) and writes an audit entry.
 */
@Service
public class RedeemCodeService {

    /** Maximum expired codes claimed by one five-minute scheduler run. */
    static final int EXPIRATION_BATCH_SIZE = 100;

    /**
     * Redeem code alphabet — uppercase letters + digits, excluding visually ambiguous characters
     * ({@code 0/O/1/I/L}) so a cashier can type a code read off a screen without transcription
     * errors. Kept independent of the earn-code alphabet (a one-line duplicate) so the two code
     * types can diverge without coupling {@code loyalty} and {@code rewards} packages.
     */
    static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** Fixed redeem code length. 32^10 keyspace → collisions negligible at MVP scale. */
    static final int CODE_LENGTH = 10;

    private final SecureRandom random = new SecureRandom();

    private final Firestore firestore;
    private final ActivityService activityService;
    private final AuditService auditService;
    private final Clock clock;

    public RedeemCodeService(Firestore firestore, ActivityService activityService,
                             AuditService auditService, Clock clock) {
        this.firestore = firestore;
        this.activityService = activityService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Generate a fresh {@value #CODE_LENGTH}-character redeem code from {@link #CODE_ALPHABET}.
     * <p>
     * ponytail: no Firestore existence check / collision-retry loop — at 32^10 the birthday-bound
     * collision probability is negligible for MVP volumes, and the caller writes with
     * {@code transaction.set}, so a (practically impossible) collision would overwrite one stale
     * doc rather than corrupt balances. Add a read-then-retry loop only if code volume ever
     * approaches that keyspace.
     *
     * @return an uppercase alphanumeric code with no ambiguous characters.
     */
    String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * Cancel the caller's own pending redeem code and refund its points (§3.2), inside
     * {@code transaction}.
     * <p>
     * Check order (§3.9): not-found (404) → not-owned (403) → not-pending (409). Ownership is checked
     * before the pending check so a caller can never learn the status of someone else's code. On
     * success the code flips {@code pending → cancelled}, the cost is credited back, and a
     * {@code cancel} activity entry is written — all atomically via {@link #refund}.
     *
     * @param transaction live Firestore transaction from {@code IdempotencyService}; every access uses it.
     * @param uid         verified Firebase UID of the caller (never a client-supplied id).
     * @param code        the redeem code to cancel.
     * @return {@link IdempotencyService.BusinessOutcome} wrapping 200 + {@link CancelResponse}.
     * @throws ApiException         400 {@code BAD_REQUEST} (blank code), 404 {@code REDEEM_NOT_FOUND},
     *                              403 {@code REDEEM_NOT_OWNED}, 409 {@code REDEEM_NOT_PENDING}.
     * @throws ExecutionException   propagated from Firestore {@code get()}.
     * @throws InterruptedException propagated from Firestore {@code get()}.
     */
    public IdempotencyService.BusinessOutcome cancel(Transaction transaction, String uid, String code)
            throws ExecutionException, InterruptedException {
        requireCode(code);
        DocumentReference ref = firestore.collection(RedeemCode.COLLECTION).document(code);
        DocumentSnapshot snap = transaction.get(ref).get();
        if (!snap.exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REDEEM_NOT_FOUND", "Redeem code not found");
        }
        if (!uid.equals(snap.getString(RedeemCode.UID))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REDEEM_NOT_OWNED", "Redeem code belongs to another user");
        }
        if (!RedeemCode.STATUS_PENDING.equals(snap.getString(RedeemCode.STATUS))) {
            throw new ApiException(HttpStatus.CONFLICT, "REDEEM_NOT_PENDING", "Redeem code is not pending");
        }

        long cost = orZero(snap.getLong(RedeemCode.COST));
        long newBalance = refund(transaction, ref, uid, cost, RedeemCode.STATUS_CANCELLED, ActivityService.TYPE_CANCEL, code);
        return new IdempotencyService.BusinessOutcome(HttpStatus.OK, ApiResponse.of(new CancelResponse(code, cost, newBalance)));
    }

    /**
     * Mark a pending redeem code completed on behalf of a cashier (§3.9), inside {@code transaction}.
     * No refund and no balance change — the customer received their reward. Writes an audit entry.
     * <p>
     * Check order: not-found (404) → not-pending (409) → expiry re-check. The expiry re-check
     * ({@link #isExpired}) rejects an expired-but-not-yet-swept code with {@code REDEEM_NOT_PENDING}
     * (§3.1 "expired code seen by cashier → REDEEM_NOT_PENDING"), so a cashier can never fulfil a
     * stale code the 5-minute {@link com.beanLoyal.backend.jobs.ExpiredRedemptionJob} has not
     * processed yet.
     *
     * @param transaction live Firestore transaction from {@code IdempotencyService}.
     * @param cashierUid  verified Firebase UID of the cashier (audit actor).
     * @param code        the redeem code presented by the customer.
     * @return {@link IdempotencyService.BusinessOutcome} wrapping 200 + {@link CashierCompleteResponse}.
     * @throws ApiException         400 {@code BAD_REQUEST}, 404 {@code REDEEM_NOT_FOUND},
     *                              409 {@code REDEEM_NOT_PENDING}.
     * @throws ExecutionException   propagated from Firestore {@code get()}.
     * @throws InterruptedException propagated from Firestore {@code get()}.
     */
    public IdempotencyService.BusinessOutcome complete(Transaction transaction, String cashierUid, String code)
            throws ExecutionException, InterruptedException {
        requireCode(code);
        DocumentReference ref = firestore.collection(RedeemCode.COLLECTION).document(code);
        DocumentSnapshot snap = transaction.get(ref).get();
        if (!snap.exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REDEEM_NOT_FOUND", "Redeem code not found");
        }
        if (!RedeemCode.STATUS_PENDING.equals(snap.getString(RedeemCode.STATUS))) {
            throw new ApiException(HttpStatus.CONFLICT, "REDEEM_NOT_PENDING", "Redeem code is not pending");
        }
        // §3.1: a code still literally 'pending' but past its TTL (sweep hasn't run) must not be
        // fulfilled — reject as if already expired.
        Timestamp expiresAt = snap.getTimestamp(RedeemCode.EXPIRES_AT);
        if (isExpired(expiresAt == null ? null : expiresAt.toDate().toInstant(), Instant.now(clock))) {
            throw new ApiException(HttpStatus.CONFLICT, "REDEEM_NOT_PENDING", "Redeem code has expired");
        }

        String ownerUid = snap.getString(RedeemCode.UID);
        String rewardName = snap.getString(RedeemCode.REWARD_NAME);
        transaction.update(ref, Map.of(
                RedeemCode.STATUS, RedeemCode.STATUS_COMPLETED,
                RedeemCode.TERMINAL_AT, toTimestamp(Instant.now(clock)),
                RedeemCode.COMPLETED_BY, cashierUid));
        auditService.record(transaction, cashierUid, "redeem.complete", code, ownerUid);
        return new IdempotencyService.BusinessOutcome(HttpStatus.OK,
                ApiResponse.of(new CashierCompleteResponse(code, rewardName, RedeemCode.STATUS_COMPLETED)));
    }

    /**
     * Query for pending redeem codes past their TTL (§3.1), used by the scheduled expiration sweep.
     * <p>
     * Requires a Firestore composite index on {@code redeem_codes(status, expiresAt)} — distinct
     * from the Phase 6 {@code (uid, status)} index. Without it the query throws
     * {@code FAILED_PRECONDITION}, which the job catches and logs (it never crashes the scheduler).
     *
     * @param now current instant.
     * @return document ids (codes) of pending, past-TTL redeem codes.
     * @throws ExecutionException   propagated from the Firestore query.
     * @throws InterruptedException propagated from the Firestore query.
     */
    public List<String> findExpiredPendingCodeIds(Instant now) throws ExecutionException, InterruptedException {
        Query query = firestore.collection(RedeemCode.COLLECTION)
                .whereEqualTo(RedeemCode.STATUS, RedeemCode.STATUS_PENDING)
                .whereLessThan(RedeemCode.EXPIRES_AT, toTimestamp(now))
                .orderBy(RedeemCode.EXPIRES_AT)
                .limit(EXPIRATION_BATCH_SIZE);
        List<String> ids = new ArrayList<>();
        for (QueryDocumentSnapshot doc : query.get().get().getDocuments()) {
            ids.add(doc.getId());
        }
        return ids;
    }

    /**
     * Expire and refund a single redeem code in its OWN Firestore transaction (§3.3), for the
     * scheduled sweep. Opens a fresh transaction rather than joining an idempotency-guarded one
     * because there is no client request or idempotency key here.
     *
     * @param code the redeem code to expire.
     * @return {@code true} if this call flipped a still-pending code to expired; {@code false} if the
     *         code was already terminal (a concurrent cancel/complete or a stale query result) — the
     *         in-transaction status re-read guarantees a refund happens at most once.
     * @throws ExecutionException   propagated from the transaction.
     * @throws InterruptedException propagated from the transaction.
     */
    public boolean expireCode(String code) throws ExecutionException, InterruptedException {
        return firestore.runTransaction(transaction -> expireIfPending(transaction, code)).get();
    }

    /**
     * Re-read the code inside {@code transaction} and, only if it is still pending, expire + refund
     * it. The re-read is the concurrency guard: if a cancel/complete committed first (or the code was
     * a stale entry from the sweep query), this sees a non-pending status and returns {@code false}
     * without refunding, so points are never credited twice.
     */
    private boolean expireIfPending(Transaction transaction, String code)
            throws ExecutionException, InterruptedException {
        DocumentReference ref = firestore.collection(RedeemCode.COLLECTION).document(code);
        DocumentSnapshot snap = transaction.get(ref).get();
        if (!snap.exists() || !RedeemCode.STATUS_PENDING.equals(snap.getString(RedeemCode.STATUS))) {
            return false;
        }
        String uid = snap.getString(RedeemCode.UID);
        long cost = orZero(snap.getLong(RedeemCode.COST));
        refund(transaction, ref, uid, cost, RedeemCode.STATUS_EXPIRED, ActivityService.TYPE_EXPIRE, code);
        return true;
    }

    /**
     * Shared refund transition for cancel (§3.2) and expire (§3.3): flip the code to
     * {@code terminalStatus}, credit {@code cost} back to {@code users/{uid}.points}, and append an
     * activity entry — all in the caller's transaction.
     * <p>
     * Reads the user doc BEFORE staging any write, honoring Firestore's read-before-write rule; the
     * caller must not have staged writes yet. Uses an absolute-value balance write (read-modify-write)
     * matching {@code LoyaltyService}/{@code RewardRedemptionService}, which also yields
     * {@code balanceAfter} for the activity entry for free.
     *
     * @return the user's balance after the refund.
     */
    private long refund(Transaction transaction, DocumentReference codeRef, String uid, long cost,
                        String terminalStatus, String activityType, String code)
            throws ExecutionException, InterruptedException {
        DocumentReference userRef = firestore.collection("users").document(uid);
        DocumentSnapshot userSnap = transaction.get(userRef).get();
        long newBalance = orZero(userSnap.getLong("points")) + cost;

        transaction.update(codeRef, Map.of(
                RedeemCode.STATUS, terminalStatus,
                RedeemCode.TERMINAL_AT, toTimestamp(Instant.now(clock))));
        transaction.update(userRef, "points", newBalance);
        activityService.record(transaction, uid, activityType, cost, code, newBalance);
        return newBalance;
    }

    /**
     * True if {@code now} is at or past {@code expiresAt} (§3.1). A {@code null} {@code expiresAt} is
     * treated as expired — the fail-safe default matching {@code EarnCodeService}/
     * {@code IdempotencyService}. The boundary is inclusive ({@code now == expiresAt} → expired).
     * Package-private + static: pure function, unit-tested without a Firestore mock.
     */
    static boolean isExpired(Instant expiresAt, Instant now) {
        return expiresAt == null || !now.isBefore(expiresAt);
    }

    private static void requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
    }
}
