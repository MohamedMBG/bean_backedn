package com.beanLoyal.backend.admin;

import com.beanLoyal.backend.audit.AuditService;
import com.beanLoyal.backend.common.ApiException;
import com.beanLoyal.backend.common.ApiResponse;
import com.beanLoyal.backend.common.IdempotencyException;
import com.beanLoyal.backend.common.IdempotencyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Provisions Firebase cashier identities as a recoverable, idempotent cross-system workflow.
 * <p>
 * Firebase Auth and Firestore cannot commit in one transaction, so the workflow deliberately grants
 * privileges last: reserve the idempotency key, ensure an unprivileged deterministic Auth account,
 * atomically write the profile plus audit entry, grant the cashier claim, then mark the operation
 * complete. A retry with the same key resumes from the last durable stage instead of failing with
 * {@code CASHIER_EMAIL_EXISTS}; a replay after completion returns the original cashier identity.
 */
@Service
public class CashierProvisioningService {

    static final String STATUS_RESERVED = "reserved";
    static final String STATUS_PROFILE_READY = "profile_ready";
    static final String STATUS_COMPLETED = "completed";
    private static final String PATH = "/api/v1/admin/cashiers";

    private final ProvisioningLedger ledger;
    private final CashierAccounts accounts;
    private final ObjectMapper objectMapper;

    @Autowired
    public CashierProvisioningService(Firestore firestore, FirebaseAuth firebaseAuth,
                                      AuditService auditService, ObjectMapper objectMapper, Clock clock) {
        this(new FirestoreProvisioningLedger(firestore, auditService, clock),
                new FirebaseCashierAccounts(firebaseAuth), objectMapper);
    }

    CashierProvisioningService(ProvisioningLedger ledger, CashierAccounts accounts,
                               ObjectMapper objectMapper) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.objectMapper = objectMapper;
    }

    /**
     * Provision or resume one cashier creation request.
     *
     * @param actorUid       verified admin UID.
     * @param idempotencyKey client-generated key reused for retries of this exact request.
     * @param request        cashier email, initial password, and optional display name.
     * @return cashier identity plus whether the result came from a completed replay.
     * @throws IdempotencyException when the key is missing or reused with changed input.
     * @throws ApiException on invalid input, email conflicts, or Firebase provisioning failures.
     */
    public ProvisioningResult provision(String actorUid, String idempotencyKey,
                                        CreateCashierRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw IdempotencyException.keyRequired();
        }
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CASHIER", "Request body is required");
        }

        AdminService.validateCashier(request.email(), request.password());
        String email = request.email().trim();
        String name = normalizeName(request.name());
        String operationId = IdempotencyService.serverKey(actorUid, idempotencyKey, PATH);
        String targetUid = "cashier-" + operationId;
        String requestHash = requestHash(new CreateCashierRequest(email, request.password(), name));

        Reservation reservation = ledger.reserve(operationId, actorUid, targetUid, requestHash, email);
        CreateCashierResponse response = new CreateCashierResponse(targetUid, email);
        if (reservation.completed()) {
            return new ProvisioningResult(response, true);
        }

        // The deterministic UID closes the crash window between Auth creation and the next
        // Firestore write: retrying can find the exact account created by this operation.
        accounts.ensureAccount(targetUid, email, request.password(), name);

        // Profile + audit are durable before the privileged claim is granted. If this step fails,
        // the partial Firebase account is still an ordinary, unprivileged user and a retry resumes.
        ledger.prepareProfile(operationId, requestHash, actorUid, targetUid, email, name);
        accounts.grantCashierRole(targetUid);
        ledger.complete(operationId, requestHash, responseJson(response));
        return new ProvisioningResult(response, false);
    }

    static String normalizeName(String name) {
        return name == null || name.isBlank() ? null : name.trim();
    }

    private String requestHash(CreateCashierRequest request) {
        try {
            // Only the SHA-256 result is persisted. The initial password never enters Firestore or logs.
            return IdempotencyService.sha256Hex(objectMapper.writeValueAsString(request));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to hash cashier provisioning request", e);
        }
    }

    private String responseJson(CreateCashierResponse response) {
        try {
            return objectMapper.writeValueAsString(ApiResponse.of(response));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize cashier provisioning response", e);
        }
    }

    /** Result returned to the controller so completed replays can be identified by a response header. */
    public record ProvisioningResult(CreateCashierResponse response, boolean replayed) {
    }

    record Reservation(boolean completed) {
    }

    interface ProvisioningLedger {
        Reservation reserve(String operationId, String actorUid, String targetUid,
                            String requestHash, String email);

        void prepareProfile(String operationId, String requestHash, String actorUid,
                            String targetUid, String email, String name);

        void complete(String operationId, String requestHash, String responseBody);
    }

    interface CashierAccounts {
        void ensureAccount(String uid, String email, String password, String name);

        void grantCashierRole(String uid);
    }

    private static final class FirestoreProvisioningLedger implements ProvisioningLedger {
        private static final Duration TTL = Duration.ofHours(24);
        private final Firestore firestore;
        private final AuditService auditService;
        private final Clock clock;

        private FirestoreProvisioningLedger(Firestore firestore, AuditService auditService, Clock clock) {
            this.firestore = firestore;
            this.auditService = auditService;
            this.clock = clock;
        }

        @Override
        public Reservation reserve(String operationId, String actorUid, String targetUid,
                                   String requestHash, String email) {
            DocumentReference ref = operationRef(operationId);
            Instant now = Instant.now(clock);
            return await(firestore.runTransaction(transaction -> {
                DocumentSnapshot snapshot = transaction.get(ref).get();
                if (snapshot.exists()) {
                    requireSameRequest(snapshot, requestHash);
                    requireTarget(snapshot, targetUid);
                    return new Reservation(STATUS_COMPLETED.equals(snapshot.getString("operationStatus")));
                }

                Map<String, Object> record = new LinkedHashMap<>();
                record.put("uid", actorUid);
                record.put("path", PATH);
                record.put("requestHash", requestHash);
                record.put("targetUid", targetUid);
                record.put("email", email);
                record.put("operationStatus", STATUS_RESERVED);
                record.put("createdAt", timestamp(now));
                record.put("expiresAt", timestamp(now.plus(TTL)));
                transaction.set(ref, record);
                return new Reservation(false);
            }), "Cashier provisioning reservation failed");
        }

        @Override
        public void prepareProfile(String operationId, String requestHash, String actorUid,
                                   String targetUid, String email, String name) {
            DocumentReference operationRef = operationRef(operationId);
            DocumentReference userRef = firestore.collection("users").document(targetUid);
            await(firestore.runTransaction(transaction -> {
                DocumentSnapshot snapshot = transaction.get(operationRef).get();
                requireOperation(snapshot, requestHash, targetUid);
                String status = snapshot.getString("operationStatus");
                if (STATUS_PROFILE_READY.equals(status) || STATUS_COMPLETED.equals(status)) {
                    return null;
                }
                if (!STATUS_RESERVED.equals(status)) {
                    throw new IllegalStateException("Unknown cashier provisioning state: " + status);
                }

                Map<String, Object> profile = new HashMap<>();
                profile.put("uid", targetUid);
                profile.put("name", name);
                profile.put("email", email);
                profile.put("role", "cashier");
                profile.put("isActive", true);
                profile.put("createdAt", FieldValue.serverTimestamp());
                transaction.set(userRef, profile);
                auditService.record(transaction, actorUid, "cashier.create", targetUid, targetUid,
                        Map.of("email", email));
                transaction.update(operationRef, Map.of(
                        "operationStatus", STATUS_PROFILE_READY,
                        "updatedAt", FieldValue.serverTimestamp()));
                return null;
            }), "Cashier profile preparation failed");
        }

        @Override
        public void complete(String operationId, String requestHash, String responseBody) {
            DocumentReference ref = operationRef(operationId);
            await(firestore.runTransaction(transaction -> {
                DocumentSnapshot snapshot = transaction.get(ref).get();
                if (!snapshot.exists()) {
                    throw new IllegalStateException("Cashier provisioning reservation is missing");
                }
                requireSameRequest(snapshot, requestHash);
                String status = snapshot.getString("operationStatus");
                if (STATUS_COMPLETED.equals(status)) {
                    return null;
                }
                if (!STATUS_PROFILE_READY.equals(status)) {
                    throw new IllegalStateException("Cashier profile was not prepared before role assignment");
                }
                Map<String, Object> completed = new LinkedHashMap<>();
                completed.put("operationStatus", STATUS_COMPLETED);
                completed.put("status", HttpStatus.OK.value());
                completed.put("responseBody", responseBody);
                completed.put("responseHash", IdempotencyService.sha256Hex(responseBody));
                completed.put("updatedAt", FieldValue.serverTimestamp());
                transaction.update(ref, completed);
                return null;
            }), "Cashier provisioning completion failed");
        }

        private DocumentReference operationRef(String operationId) {
            return firestore.collection("idempotency").document(operationId);
        }

        private static void requireOperation(DocumentSnapshot snapshot, String requestHash,
                                             String targetUid) {
            if (!snapshot.exists()) {
                throw new IllegalStateException("Cashier provisioning reservation is missing");
            }
            requireSameRequest(snapshot, requestHash);
            requireTarget(snapshot, targetUid);
        }

        private static void requireSameRequest(DocumentSnapshot snapshot, String requestHash) {
            if (!requestHash.equals(snapshot.getString("requestHash"))) {
                throw IdempotencyException.keyReused();
            }
        }

        private static void requireTarget(DocumentSnapshot snapshot, String targetUid) {
            if (!targetUid.equals(snapshot.getString("targetUid"))) {
                throw new IllegalStateException("Cashier provisioning target does not match reservation");
            }
        }

        private static Timestamp timestamp(Instant instant) {
            return Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
        }

        private static <T> T await(com.google.api.core.ApiFuture<T> future, String message) {
            try {
                return future.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(message, e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(message + " (interrupted)", e);
            }
        }
    }

    private static final class FirebaseCashierAccounts implements CashierAccounts {
        private static final Logger log = LoggerFactory.getLogger(FirebaseCashierAccounts.class);
        private final FirebaseAuth firebaseAuth;

        private FirebaseCashierAccounts(FirebaseAuth firebaseAuth) {
            this.firebaseAuth = firebaseAuth;
        }

        @Override
        public void ensureAccount(String uid, String email, String password, String name) {
            UserRecord.CreateRequest create = new UserRecord.CreateRequest()
                    .setUid(uid)
                    .setEmail(email)
                    .setPassword(password);
            if (name != null) {
                create.setDisplayName(name);
            }
            try {
                firebaseAuth.createUser(create);
                return;
            } catch (FirebaseAuthException e) {
                if (e.getAuthErrorCode() != AuthErrorCode.UID_ALREADY_EXISTS
                        && e.getAuthErrorCode() != AuthErrorCode.EMAIL_ALREADY_EXISTS) {
                    throw authFailure("CASHIER_AUTH_FAILED", "Could not create the cashier account", e);
                }
            }

            // A retry after a crash sees the deterministic UID created by the first attempt. It is
            // safe to resume only when that exact account owns the requested email.
            try {
                UserRecord existing = firebaseAuth.getUser(uid);
                if (!email.equalsIgnoreCase(existing.getEmail())) {
                    throw new ApiException(HttpStatus.CONFLICT, "CASHIER_ID_CONFLICT",
                            "Cashier provisioning identity conflicts with an existing account");
                }
            } catch (FirebaseAuthException e) {
                if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                    throw new ApiException(HttpStatus.CONFLICT, "CASHIER_EMAIL_EXISTS",
                            "That email is already registered");
                }
                throw authFailure("CASHIER_AUTH_FAILED", "Could not recover the cashier account", e);
            }
        }

        @Override
        public void grantCashierRole(String uid) {
            try {
                UserRecord user = firebaseAuth.getUser(uid);
                Map<String, Object> claims = new HashMap<>(user.getCustomClaims());
                claims.put("role", "cashier");
                firebaseAuth.setCustomUserClaims(uid, claims);
            } catch (FirebaseAuthException e) {
                throw authFailure("CASHIER_CLAIM_FAILED",
                        "Cashier profile is ready but role assignment failed; retry with the same key", e);
            }
        }

        private static ApiException authFailure(String code, String message, FirebaseAuthException cause) {
            log.warn("Firebase cashier provisioning failed: code={} authCode={}", code, cause.getAuthErrorCode());
            return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, code, message);
        }
    }
}
