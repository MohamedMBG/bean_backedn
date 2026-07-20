package com.beanLoyal.backend.audit;

import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Append-only writer for the {@code audit/{autoId}} collection
 * ({@code docs/BACKEND_IMPLEMENTATION_PLAN.md §11}, §13 "Audit log every admin/cashier action").
 * <p>
 * Records privileged actions (cashier redeem-complete now; admin actions in Phase 10) so the owner
 * has a tamper-evident trail of who did what. Append-only by construction: this service only ever
 * {@code set}s a new auto-id document — never updates or deletes an existing one.
 *
 * <h2>Transaction contract</h2>
 * {@link #record} performs a single write and issues no reads, so it MUST be called at the END of
 * the caller's Firestore {@link Transaction} (after all reads/updates). It commits atomically with
 * the action it audits and is covered by the idempotency replay guard, so a retried request never
 * writes a duplicate audit entry.
 */
@Service
public class AuditService {

    static final String COLLECTION = "audit";
    static final String ACTOR_UID = "actorUid";
    static final String ACTION = "action";
    static final String TARGET_ID = "targetId";
    static final String TARGET_UID = "targetUid";
    static final String DETAILS = "details";
    static final String CREATED_AT = "createdAt";

    private final Firestore firestore;

    public AuditService(Firestore firestore) {
        this.firestore = firestore;
    }

    /**
     * Append one audit entry inside the caller's transaction.
     *
     * @param transaction live Firestore transaction shared with the audited action; write-only, so
     *                    call it after all reads/updates in the transaction.
     * @param actorUid    verified Firebase UID of the privileged caller performing the action.
     * @param action      dotted action name, e.g. {@code "redeem.complete"}.
     * @param targetId    id of the primary object acted on (e.g. redeem code), or {@code null}.
     * @param targetUid   uid of the affected user (e.g. the redeem code owner), or {@code null}.
     */
    public void record(Transaction transaction, String actorUid, String action,
                       String targetId, String targetUid) {
        record(transaction, actorUid, action, targetId, targetUid, null);
    }

    /**
     * Append one audit entry with structured {@code details} (e.g. the created code, adjustment
     * delta + reason) inside the caller's transaction.
     *
     * @param transaction live Firestore transaction; write-only, call after all reads/updates.
     * @param actorUid    verified Firebase UID of the privileged caller.
     * @param action      dotted action name, e.g. {@code "points.adjust"}.
     * @param targetId    id of the primary object acted on, or {@code null}.
     * @param targetUid   uid of the affected user, or {@code null}.
     * @param details     action-specific fields to store under {@code details}, or {@code null}.
     */
    public void record(Transaction transaction, String actorUid, String action,
                       String targetId, String targetUid, Map<String, Object> details) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(ACTOR_UID, actorUid);
        entry.put(ACTION, action);
        entry.put(TARGET_ID, targetId);
        entry.put(TARGET_UID, targetUid);
        entry.put(DETAILS, details);
        entry.put(CREATED_AT, FieldValue.serverTimestamp());
        transaction.set(firestore.collection(COLLECTION).document(), entry);
    }

    /**
     * Append one audit entry OUTSIDE any transaction — for privileged actions that aren't a Firestore
     * transaction (e.g. a completed external push delivery). Blocks on the write.
     * Still append-only: only ever {@code set}s a fresh auto-id document.
     *
     * @param actorUid  verified Firebase UID of the privileged caller.
     * @param action    dotted action name, e.g. {@code "cashier.create"}.
     * @param targetId  id of the primary object acted on, or {@code null}.
     * @param targetUid uid of the affected user, or {@code null}.
     * @param details   action-specific fields (never secrets), or {@code null}.
     */
    public void record(String actorUid, String action, String targetId, String targetUid,
                       Map<String, Object> details) throws ExecutionException, InterruptedException {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(ACTOR_UID, actorUid);
        entry.put(ACTION, action);
        entry.put(TARGET_ID, targetId);
        entry.put(TARGET_UID, targetUid);
        entry.put(DETAILS, details);
        entry.put(CREATED_AT, FieldValue.serverTimestamp());
        firestore.collection(COLLECTION).document().set(entry).get();
    }
}
