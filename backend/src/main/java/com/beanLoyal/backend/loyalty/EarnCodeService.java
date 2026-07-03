package com.beanLoyal.backend.loyalty;

import com.beanLoyal.backend.common.ApiException;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ExecutionException;

/**
 * Reads, validates, and burns QR earn codes per {@code docs/BUSINESS_RULES.md §2}.
 * <p>
 * Invoked from {@link LoyaltyService#earn} inside the caller-supplied Firestore
 * {@link Transaction} shared with {@link com.beanLoyal.backend.common.IdempotencyService} — every
 * read/write here MUST use that transaction, never a nested {@code runTransaction}, so a retried
 * scan can never burn a code twice even before the idempotency record is consulted.
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

    private final Firestore firestore;

    public EarnCodeService(Firestore firestore) {
        this.firestore = firestore;
    }

    /** Firestore reference + granted points for a code that passed all validity checks. */
    record ValidatedCode(DocumentReference ref, long points) {}

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

        Timestamp expiresAt = snap.getTimestamp("expiresAt");
        // Missing expiresAt is a malformed record — treat as expired rather than trust it (same
        // fail-safe default IdempotencyService uses for its own expiresAt field).
        if (expiresAt == null || !now.isBefore(expiresAt.toDate().toInstant())) {
            throw new ApiException(HttpStatus.GONE, "EARN_CODE_EXPIRED", "Earn code has expired");
        }

        String status = snap.getString("status");
        if (!"active".equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "EARN_CODE_ALREADY_USED", "Earn code already used");
        }

        Long points = snap.getLong("points");
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
        transaction.update(ref, "status", "used");
    }
}
