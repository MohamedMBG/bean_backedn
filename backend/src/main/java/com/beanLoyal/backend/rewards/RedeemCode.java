package com.beanLoyal.backend.rewards;

import com.google.cloud.Timestamp;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single schema definition for the {@code redeem_codes/{code}} Firestore collection
 * ({@code docs/BUSINESS_RULES.md §3}). Field names and status values live here once so the Phase 6
 * redeem flow and the Phase 7 cancel/complete/expire flows share one definition and cannot drift
 * or typo a key against each other.
 * <p>
 * The document id is the redeem code itself (see {@link RedeemCodeService}), so no {@code code}
 * field is stored on the doc.
 */
final class RedeemCode {

    private RedeemCode() {
    }

    /** Collection name; the document id IS the redeem code value. */
    static final String COLLECTION = "redeem_codes";

    static final String UID = "uid";
    static final String REWARD_ID = "rewardId";
    static final String REWARD_NAME = "rewardName";
    /** Points cost deducted at redeem time; stored so the Phase 7 refund reads it from the code doc. */
    static final String COST = "cost";
    static final String STATUS = "status";
    static final String CREATED_AT = "createdAt";
    static final String EXPIRES_AT = "expiresAt";
    /**
     * Instant the code left {@code pending} (set on complete/cancel/expire), so an admin/support
     * lookup on the code doc itself is self-describing without joining the activity/audit logs.
     */
    static final String TERMINAL_AT = "terminalAt";

    /** The full lifecycle of a redeem code (§3): created {@code pending}, then one terminal state. */
    static final String STATUS_PENDING = "pending";
    /** Set by the Phase 7 cashier-complete flow. */
    static final String STATUS_COMPLETED = "completed";
    /** Set by the Phase 7 user-cancel flow (refunds points). */
    static final String STATUS_CANCELLED = "cancelled";
    /** Set by the Phase 7 expiration job (refunds points). */
    static final String STATUS_EXPIRED = "expired";

    /**
     * Build the Firestore payload for a freshly created {@code pending} redeem code (§3.1).
     *
     * @param uid        owning caller's verified Firebase UID.
     * @param rewardId   redeemed catalog reward id.
     * @param rewardName reward display name copied for the cashier's screen (may be {@code null}).
     * @param cost       points deducted for this redemption.
     * @param createdAt  creation instant.
     * @param expiresAt  {@code createdAt + 15min} (§3.1), after which the Phase 7 job expires + refunds.
     * @return a mutable map ready for {@code transaction.set}.
     */
    static Map<String, Object> pendingDoc(String uid, String rewardId, String rewardName, long cost,
                                          Instant createdAt, Instant expiresAt) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(UID, uid);
        doc.put(REWARD_ID, rewardId);
        doc.put(REWARD_NAME, rewardName);
        doc.put(COST, cost);
        doc.put(STATUS, STATUS_PENDING);
        doc.put(CREATED_AT, toTimestamp(createdAt));
        doc.put(EXPIRES_AT, toTimestamp(expiresAt));
        return doc;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
    }
}
