package com.beanLoyal.backend.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Implements the {@code Idempotency-Key} contract locked in {@code docs/BUSINESS_RULES.md §1}.
 * <p>
 * Every state-mutating economy endpoint (earn, redeem, cancel, birthday, cashier complete, admin
 * writes) wraps its business logic in {@link #execute}. The service guarantees that a retried
 * request — the norm on flaky mobile networks — grants points, deducts balance, or creates a
 * redeem code at most once.
 *
 * <h2>Firestore collection</h2>
 * {@code idempotency/{key}} where {@code key = sha256(uid + ":" + idempotencyKey + ":" + path)}.
 * The uid is part of the key so one caller's records can never collide with, or be read by, another.
 * Each doc stores {@code uid, path, requestHash, responseHash, responseBody, status, createdAt,
 * expiresAt}. A 24h {@code expiresAt} drives a Firestore TTL policy for cleanup; the service also
 * re-checks {@code expiresAt} on read because that policy deletes lazily (can lag hours).
 *
 * <h2>Atomicity</h2>
 * The idempotency record is written inside the SAME Firestore transaction as the business write
 * (step 3 of §1). If the transaction commits, both the economy change and its dedup marker land
 * together; if it aborts, neither does. There is no window where points are granted without a
 * marker to stop the next retry.
 *
 * <h2>Caller contract for {@link TransactionalWork}</h2>
 * The supplied work runs inside a live Firestore {@link Transaction} and:
 * <ul>
 *   <li>MUST perform all its Firestore reads before its writes (Firestore transaction rule);
 *       the service reads the idempotency doc first and writes it last, so it never violates this
 *       on the work's behalf.</li>
 *   <li>MUST use the provided {@code transaction} for Firestore access — never open a nested
 *       {@code runTransaction}.</li>
 *   <li>MUST be free of non-Firestore side effects (e.g. FCM sends, emails): the transaction can
 *       retry on contention and would repeat them. Defer such effects to after {@code execute}
 *       returns.</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    // Server key logged (it is already a sha256, safe); raw client Idempotency-Key is never logged.
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** Firestore collection holding one dedup record per {@code (uid, key, path)} tuple. */
    static final String COLLECTION = "idempotency";

    /** Record lifetime — long enough to cover an offline-then-recovered phone, short enough to bound growth. */
    private static final Duration TTL = Duration.ofHours(24);

    /** Response header set on a replayed (deduplicated) response so clients and logs can tell it apart. */
    public static final String REPLAY_HEADER = "Idempotency-Replayed";

    private final Firestore firestore;
    private final ObjectMapper objectMapper;

    public IdempotencyService(Firestore firestore, ObjectMapper objectMapper) {
        this.firestore = firestore;
        this.objectMapper = objectMapper;
    }

    /**
     * Business logic to run inside the idempotency-guarded Firestore transaction.
     * Declares {@code throws Exception} so implementations can call
     * {@code transaction.get(ref).get()} and other checked-throwing Firestore APIs directly.
     */
    @FunctionalInterface
    public interface TransactionalWork {
        BusinessOutcome apply(Transaction transaction) throws Exception;
    }

    /**
     * Result of a first (non-replayed) execution.
     *
     * @param status HTTP status the endpoint wants to return; persisted and replayed verbatim.
     * @param body   response payload; serialized to JSON, hashed, stored, and returned to the caller.
     *               May be {@code null} for an empty body.
     */
    public record BusinessOutcome(HttpStatus status, Object body) {}

    /** Internal carrier for the resolved response — either freshly produced or replayed from storage. */
    private record StoredResponse(int status, String body, boolean replayed) {}

    /**
     * Run {@code work} at most once for the given {@code (uid, idempotencyKey, path)} tuple.
     *
     * @param uid            authenticated Firebase UID (from the verified token — never client body).
     * @param path           full request path used in the server key, e.g. {@code /api/v1/rewards/birthday}.
     * @param idempotencyKey raw {@code Idempotency-Key} header value; {@code null}/blank → 400.
     * @param requestBody    parsed request payload, hashed to detect key reuse with a changed body;
     *                       {@code null} is allowed for no-body requests.
     * @param work           business logic executed only on the first call; skipped on replay.
     * @return the JSON response body with its original status; carries the {@code Idempotency-Replayed}
     *         header when served from a stored record.
     * @throws IdempotencyException 400 if the header is missing, 409 if the key is reused with a
     *                              different body.
     */
    public ResponseEntity<String> execute(String uid,
                                          String path,
                                          String idempotencyKey,
                                          Object requestBody,
                                          TransactionalWork work) {
        // Step 6: missing/blank header on a required route is rejected before any work runs.
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw IdempotencyException.keyRequired();
        }

        String key = serverKey(uid, idempotencyKey, path);
        String requestHash = sha256Hex(canonicalJson(requestBody));
        DocumentReference ref = firestore.collection(COLLECTION).document(key);
        Instant now = Instant.now();

        StoredResponse outcome = runGuarded(ref, uid, path, requestHash, now, work);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(outcome.status())
                .contentType(MediaType.APPLICATION_JSON);
        if (outcome.replayed()) {
            // Logged here (not in the transaction lambda) so it fires exactly once even if the
            // transaction retried on contention. requestId in MDC correlates it to the request line.
            log.debug("Idempotency replay served: key={} path={} status={}", key, path, outcome.status());
            builder.header(REPLAY_HEADER, "true");
        }
        return builder.body(outcome.body());
    }

    private StoredResponse runGuarded(DocumentReference ref,
                                      String uid,
                                      String path,
                                      String requestHash,
                                      Instant now,
                                      TransactionalWork work) {
        try {
            return firestore.runTransaction(transaction -> {
                DocumentSnapshot snap = transaction.get(ref).get();

                // Replay path (§1 step 4): a live record exists → return the stored response,
                // do NOT re-run business logic. expiresAt is re-checked here because Firestore's
                // TTL sweep is lazy and an expired-but-not-yet-deleted record must be re-run.
                if (snap.exists()) {
                    if (!isExpired(snap, now)) {
                        String storedHash = snap.getString("requestHash");
                        // §1 step 5: same key, different body → 409. Aborts the transaction (no writes yet).
                        if (storedHash == null || !storedHash.equals(requestHash)) {
                            throw IdempotencyException.keyReused();
                        }
                        Long status = snap.getLong("status");
                        return new StoredResponse(status == null ? 200 : status.intValue(),
                                snap.getString("responseBody"), true);
                    }
                    // Record exists but is past its TTL (or has a missing/malformed expiresAt): the
                    // Firestore TTL sweep hasn't deleted it yet. Fall through to re-run and overwrite.
                    // DEBUG only; may repeat if the transaction retries on contention (rare, harmless).
                    log.debug("Idempotency record expired, re-running: key={} path={}", ref.getId(), path);
                }

                // Fresh path (§1 step 3): run business logic in THIS transaction, then persist the
                // idempotency record in the same commit so the economy write and its dedup marker
                // are atomic. Reads (idempotency doc + work's reads) all precede writes.
                BusinessOutcome result = work.apply(transaction);
                String responseBody = canonicalJson(result.body());

                Map<String, Object> record = new LinkedHashMap<>();
                record.put("uid", uid);
                record.put("path", path);
                record.put("requestHash", requestHash);
                record.put("responseHash", sha256Hex(responseBody));
                record.put("responseBody", responseBody);
                record.put("status", result.status().value());
                record.put("createdAt", toTimestamp(now));
                record.put("expiresAt", toTimestamp(now.plus(TTL)));
                transaction.set(ref, record);

                return new StoredResponse(result.status().value(), responseBody, false);
            }).get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            // Preserve IdempotencyException (and any business RuntimeException) so the
            // GlobalExceptionHandler maps it to its proper status instead of a blanket 500.
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Idempotency transaction failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Idempotency transaction interrupted", e);
        }
    }

    private static boolean isExpired(DocumentSnapshot snap, Instant now) {
        Timestamp expiresAt = snap.getTimestamp("expiresAt");
        // Missing expiresAt = malformed/legacy record; treat as expired so we re-run rather than trust it.
        return expiresAt == null || expiresAt.toDate().toInstant().isBefore(now);
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
    }

    /**
     * Serialize a payload to JSON for hashing/storage.
     * <p>
     * ponytail: no key-sorting canonicalizer — a given DTO/record class serializes deterministically,
     * so the same client sending the same payload always produces the same hash, which is all the
     * reuse check needs. Add canonicalization only if a body with unordered {@code Map} fields ever
     * flows through here.
     */
    private String canonicalJson(Object body) {
        if (body == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            // A payload we control failing to serialize is a server bug, not a client error.
            throw new IllegalStateException("Failed to serialize idempotency payload", e);
        }
    }

    /** Server-side dedup key: {@code sha256(uid + ":" + idempotencyKey + ":" + path)} (§1 step 1). */
    public static String serverKey(String uid, String idempotencyKey, String path) {
        return sha256Hex(uid + ":" + idempotencyKey + ":" + path);
    }

    /** Returns the lowercase SHA-256 hex digest used for request and response fingerprints. */
    public static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JCA algorithm on every conformant JVM; absence = broken runtime.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
