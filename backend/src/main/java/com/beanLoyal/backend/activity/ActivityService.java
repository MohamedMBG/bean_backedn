package com.beanLoyal.backend.activity;

import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical writer for the per-user activity feed {@code users/{uid}/activities/{autoId}}
 * ({@code docs/BACKEND_IMPLEMENTATION_PLAN.md §11}). This is the single LOCKED activity document
 * shape; every economy operation that moves a user's balance appends one entry through here so the
 * mobile client can render one uniform feed.
 * <p>
 * Introduced in Phase 7 because {@code docs/BUSINESS_RULES.md §3.2/§3.3} require a cancel/expire
 * activity entry; Phase 8 adopts this same writer for earn (§2), redeem (§3), and birthday (§3.7).
 *
 * <h2>Transaction contract</h2>
 * {@link #record} performs a single write ({@code set} with an auto-id) and issues no reads, so it
 * MUST be called at the END of the caller's Firestore {@link Transaction}, after every read and
 * balance write — Firestore rejects a read issued after any write, and this method only writes. It
 * commits atomically with the balance change and is therefore covered by the idempotency replay
 * guard (a retried request never double-appends).
 *
 * <h2>Schema</h2>
 * <ul>
 *   <li>{@code type} — one of {@code earn|redeem|cancel|expire|birthday} (see the {@code TYPE_*} constants).</li>
 *   <li>{@code pointsDelta} — signed balance change: {@code +} for a credit (earn/refund/birthday),
 *       {@code -} for a debit (redeem).</li>
 *   <li>{@code refId} — id of the source document that caused this entry (earn/redeem code, claim id);
 *       nullable.</li>
 *   <li>{@code balanceAfter} — {@code users/{uid}.points} after the event, so the feed can show a
 *       running balance without recomputation.</li>
 *   <li>{@code createdAt} — server timestamp.</li>
 * </ul>
 */
@Service
public class ActivityService {

    static final String TYPE = "type";
    static final String POINTS_DELTA = "pointsDelta";
    static final String REF_ID = "refId";
    static final String BALANCE_AFTER = "balanceAfter";
    static final String CREATED_AT = "createdAt";

    public static final String TYPE_EARN = "earn";
    public static final String TYPE_REDEEM = "redeem";
    public static final String TYPE_CANCEL = "cancel";
    public static final String TYPE_EXPIRE = "expire";
    public static final String TYPE_BIRTHDAY = "birthday";

    private final Firestore firestore;

    public ActivityService(Firestore firestore) {
        this.firestore = firestore;
    }

    /**
     * Append one activity entry for {@code uid} inside the caller's transaction.
     *
     * @param transaction  live Firestore transaction shared with the balance write; this method only
     *                     writes, so call it after all reads/updates in the transaction.
     * @param uid          owning user's verified Firebase UID.
     * @param type         one of the {@code TYPE_*} constants.
     * @param pointsDelta  signed balance change ({@code +} credit / {@code -} debit).
     * @param refId        source document id (earn/redeem code, claim id), or {@code null}.
     * @param balanceAfter {@code users/{uid}.points} after the event.
     */
    public void record(Transaction transaction, String uid, String type, long pointsDelta,
                       String refId, long balanceAfter) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(TYPE, type);
        entry.put(POINTS_DELTA, pointsDelta);
        entry.put(REF_ID, refId);
        entry.put(BALANCE_AFTER, balanceAfter);
        entry.put(CREATED_AT, FieldValue.serverTimestamp());
        transaction.set(firestore.collection("users").document(uid).collection("activities").document(), entry);
    }
}
