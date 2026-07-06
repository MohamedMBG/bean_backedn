package com.beanLoyal.backend.loyalty;

import com.beanLoyal.backend.common.ApiException;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Reads, validates, burns, creates, and revokes QR earn codes per {@code docs/BUSINESS_RULES.md §2}.
 * <p>
 * The scan-time paths ({@link #readValid}, {@link #burn}) run inside the earn transaction shared with
 * {@link com.beanLoyal.backend.common.IdempotencyService}. The admin lifecycle paths
 * ({@link #create}, {@link #revoke}) run inside the admin endpoint's idempotency transaction
 * (Phase 10). Every read/write MUST use the caller-supplied transaction, never a nested one.
 * <p>
 * Firestore collection: {@code earn_codes/{code}} — the document id IS the code value (§2.5), so
 * lookup is a direct {@code document(code)} reference, no query needed.
 */
@Service
public class EarnCodeService {

    /**
     * Earn code alphabet — uppercase letters + digits, excluding visually ambiguous characters
     * ({@code 0/O/1/I/L}) so a cashier can type a code read off a receipt without transcription
     * errors (§2.5).
     */
    static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** Fixed earn code length (§2.5). */
    static final int CODE_LENGTH = 10;

    /** Earn code lifetime from creation (§2.2). */
    static final Duration CODE_TTL = Duration.ofHours(24);

    static final String STATUS = "status";
    static final String POINTS = "points";
    static final String CREATED_AT = "createdAt";
    static final String EXPIRES_AT = "expiresAt";
    static final String CREATED_BY = "createdBy";
    static final String STATUS_ACTIVE = "active";
    static final String STATUS_USED = "used";
    public static final String STATUS_REVOKED = "revoked";

    private final SecureRandom random = new SecureRandom();
    private final Firestore firestore;

    public EarnCodeService(Firestore firestore) {
        this.firestore = firestore;
    }

    /** Firestore reference + granted points for a code that passed all validity checks. */
    record ValidatedCode(DocumentReference ref, long points) {}

    /** A freshly created earn code + its computed expiry, returned to the admin endpoint. */
    public record CreatedCode(String code, Instant expiresAt) {}

    /**
     * Validate a code's shape against the §2.5 alphabet/length rule. Pure function, no Firestore
     * access — called before any transaction read so a malformed code is rejected at zero cost
     * (§2.5: "Validated against alphabet on inbound before Firestore lookup").
     *
     * @param code raw code value from the client request body; {@code null} fails the check.
     * @throws ApiException 400 {@code EARN_CODE_INVALID_FORMAT} if the length or character set is wrong.
     */
    static void validateFormat(String code) {
        if (code == null || code.length() != CODE_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EARN_CODE_INVALID_FORMAT",
                    "Earn code must be " + CODE_LENGTH + " characters");
        }
        for (int i = 0; i < code.length(); i++) {
            if (CODE_ALPHABET.indexOf(code.charAt(i)) < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "EARN_CODE_INVALID_FORMAT",
                        "Earn code contains invalid characters");
            }
        }
    }

    /**
     * Read {@code earn_codes/{code}} inside {@code transaction} and enforce the §2.8 error table:
     * missing doc, expired, or already-used all reject before any write is staged. Does NOT burn
     * the code — callers must finish all their own transaction reads first and call {@link #burn}
     * afterward, honoring Firestore's read-before-write rule for the shared transaction.
     *
     * @param transaction live Firestore transaction from the caller (shared with idempotency + user writes).
     * @param code        validated code value (call {@link #validateFormat} first).
     * @param now         current instant, injected so callers can share one clock reading per request.
     * @return the code's document reference and its stored {@code points} value.
     * @throws ApiException         404 {@code EARN_CODE_NOT_FOUND}, 410 {@code EARN_CODE_EXPIRED},
     *                              or 409 {@code EARN_CODE_ALREADY_USED} per §2.8.
     * @throws IllegalStateException if the doc exists but is missing a valid {@code points} field —
     *                              a data-integrity bug, not a client error; surfaces as 500.
     * @throws ExecutionException   propagated from the underlying Firestore {@code get()}.
     * @throws InterruptedException propagated from the underlying Firestore {@code get()}.
     */
    ValidatedCode readValid(Transaction transaction, String code, Instant now)
            throws ExecutionException, InterruptedException {
        DocumentReference ref = firestore.collection("earn_codes").document(code);
        DocumentSnapshot snap = transaction.get(ref).get();

        if (!snap.exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EARN_CODE_NOT_FOUND", "Earn code not found");
        }

        Timestamp expiresAt = snap.getTimestamp(EXPIRES_AT);
        // Missing expiresAt is a malformed record — treat as expired rather than trust it (same
        // fail-safe default IdempotencyService uses for its own expiresAt field).
        if (expiresAt == null || !now.isBefore(expiresAt.toDate().toInstant())) {
            throw new ApiException(HttpStatus.GONE, "EARN_CODE_EXPIRED", "Earn code has expired");
        }

        String status = snap.getString(STATUS);
        if (!STATUS_ACTIVE.equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "EARN_CODE_ALREADY_USED", "Earn code already used");
        }

        Long points = snap.getLong(POINTS);
        if (points == null || points <= 0) {
            throw new IllegalStateException("earn_codes/" + code + " missing a valid points field");
        }

        return new ValidatedCode(ref, points);
    }

    /**
     * Flip an already-validated code from {@code active} to {@code used} (§2.3). Must be called
     * only after every transaction read is complete — Firestore transactions reject a read issued
     * after any write.
     *
     * @param transaction live Firestore transaction from the caller.
     * @param ref         reference returned by a prior {@link #readValid} call in the same transaction.
     */
    void burn(Transaction transaction, DocumentReference ref) {
        transaction.update(ref, STATUS, STATUS_USED);
    }

    /**
     * Create a new {@code active} earn code worth {@code points}, inside the admin endpoint's
     * transaction (§2.1/§2.2). The generated code is the document id.
     * <p>
     * ponytail: no collision-retry loop — at 32^10 the birthday-bound collision probability is
     * negligible for MVP volumes, and a (practically impossible) collision would overwrite one stale
     * doc via {@code set}. Add a read-then-retry loop only if code volume ever approaches that keyspace.
     *
     * @param transaction live Firestore transaction from the admin idempotency wrapper.
     * @param points      positive point value stored on the code (validated by the caller).
     * @param actorUid    admin uid, recorded as {@code createdBy}.
     * @param now         current instant; {@code expiresAt = now + 24h}.
     * @return the created code and its expiry.
     */
    public CreatedCode create(Transaction transaction, long points, String actorUid, Instant now) {
        String code = generateCode();
        Instant expiresAt = now.plus(CODE_TTL);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(POINTS, points);
        doc.put(STATUS, STATUS_ACTIVE);
        doc.put(CREATED_AT, toTimestamp(now));
        doc.put(EXPIRES_AT, toTimestamp(expiresAt));
        doc.put(CREATED_BY, actorUid);
        transaction.set(firestore.collection("earn_codes").document(code), doc);
        return new CreatedCode(code, expiresAt);
    }

    /**
     * Revoke an {@code active} earn code (admin, §2). A code that is already used/revoked/expired
     * cannot be revoked.
     *
     * @param transaction live Firestore transaction from the admin idempotency wrapper.
     * @param code        the earn code to revoke.
     * @throws ApiException         404 {@code EARN_CODE_NOT_FOUND} if no doc; 409
     *                              {@code EARN_CODE_NOT_ACTIVE} if the code is not currently active.
     * @throws ExecutionException   propagated from the Firestore {@code get()}.
     * @throws InterruptedException propagated from the Firestore {@code get()}.
     */
    public void revoke(Transaction transaction, String code) throws ExecutionException, InterruptedException {
        DocumentReference ref = firestore.collection("earn_codes").document(code);
        DocumentSnapshot snap = transaction.get(ref).get();
        if (!snap.exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EARN_CODE_NOT_FOUND", "Earn code not found");
        }
        if (!STATUS_ACTIVE.equals(snap.getString(STATUS))) {
            throw new ApiException(HttpStatus.CONFLICT, "EARN_CODE_NOT_ACTIVE",
                    "Only an active earn code can be revoked");
        }
        transaction.update(ref, STATUS, STATUS_REVOKED);
    }

    /** Generate a fresh {@value #CODE_LENGTH}-character code from {@link #CODE_ALPHABET} (§2.5). */
    String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
    }
}
