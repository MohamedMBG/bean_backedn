package com.beanLoyal.backend.push;

import com.beanLoyal.backend.audit.AuditService;
import com.beanLoyal.backend.common.ApiException;
import com.beanLoyal.backend.common.IdempotencyException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Selects push audiences, previews reachable customers, sends through FCM and records delivery
 * results. Reads are bounded and batched: users and devices are each loaded once, then joined by
 * UID in memory; FCM delivery uses batches of 500.
 * <p>
 * Send idempotency uses {@code push_campaigns/{sha256(uid:key:path)}}. The reservation is committed
 * before the external FCM side effect. A replay returns the stored final result and never sends
 * again; a request interrupted while status is {@code sending} is deliberately not retried
 * automatically because doing so could duplicate a notification already accepted by FCM.
 */
@Service
public class PushCampaignService {

    static final int MAX_USERS = 5_000;
    static final int MAX_DEVICES = 10_000;
    private static final int FCM_BATCH_SIZE = 500;
    private static final String CAMPAIGNS = "push_campaigns";
    private static final String SEND_PATH = "/api/v1/admin/push/send";

    private final Firestore firestore;
    private final PushGateway gateway;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PushCampaignService(Firestore firestore, PushGateway gateway, AuditService auditService,
                               ObjectMapper objectMapper, Clock clock) {
        this.firestore = firestore;
        this.gateway = gateway;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Preview the exact audience used by {@link #send}; no writes or FCM calls occur.
     *
     * @param filter admin-selected audience criteria.
     * @return matched profile count plus reachable user/device counts.
     */
    public PushPreviewResponse preview(PushAudienceFilter filter) {
        Selection selection = select(filter);
        return new PushPreviewResponse(selection.reachableUsers(), selection.matchedUsers(),
                selection.devices().size());
    }

    /**
     * Deliver one idempotent admin campaign and append a content-free audit summary.
     *
     * @param actorUid verified admin UID.
     * @param idempotencyKey required client-generated key reused only for retries of this campaign.
     * @param request validated title, message and audience filters.
     * @return stored or freshly produced delivery summary.
     */
    public PushSendResponse send(String actorUid, String idempotencyKey, PushSendRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw IdempotencyException.keyRequired();
        AudienceMatcher.validate(request.filters());

        String requestHash = sha256(json(request));
        String campaignId = sha256(actorUid + ":" + idempotencyKey + ":" + SEND_PATH);
        DocumentReference campaignRef = firestore.collection(CAMPAIGNS).document(campaignId);
        Reservation reservation = reserve(campaignRef, actorUid, requestHash);
        if (reservation.replayed()) return reservation.response();

        try {
            Selection selection = select(request.filters());
            int success = 0;
            int failure = 0;
            List<DeviceTarget> permanentlyInvalid = new ArrayList<>();
            for (int start = 0; start < selection.devices().size(); start += FCM_BATCH_SIZE) {
                List<DeviceTarget> batch = selection.devices().subList(start,
                        Math.min(start + FCM_BATCH_SIZE, selection.devices().size()));
                List<String> tokens = batch.stream().map(DeviceTarget::token).toList();
                PushGateway.DeliveryResult result = gateway.send(request.title(), request.message(), tokens);
                success += result.successCount();
                failure += result.failureCount();
                for (Integer index : result.unregisteredIndexes()) {
                    if (index != null && index >= 0 && index < batch.size()) permanentlyInvalid.add(batch.get(index));
                }
            }
            disableInvalidDevices(permanentlyInvalid);

            PushSendResponse response = new PushSendResponse(campaignId, selection.matchedUsers(),
                    selection.reachableUsers(), selection.devices().size(), success, failure);
            completeCampaign(campaignRef, response);

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("matchedUsers", response.matchedUsers());
            details.put("reachableUsers", response.reachableUsers());
            details.put("targetedDevices", response.targetedDevices());
            details.put("successCount", response.successCount());
            details.put("failureCount", response.failureCount());
            auditService.record(actorUid, "push.send", campaignId, null, details);
            return response;
        } catch (RuntimeException e) {
            markFailed(campaignRef);
            throw e;
        } catch (Exception e) {
            markFailed(campaignRef);
            throw new IllegalStateException("Push delivery failed", e);
        }
    }

    private Selection select(PushAudienceFilter filter) {
        AudienceMatcher.validate(filter);
        Instant now = Instant.now(clock);
        try {
            List<QueryDocumentSnapshot> userDocs = firestore.collection("users")
                    .limit(MAX_USERS + 1).get().get().getDocuments();
            if (userDocs.size() > MAX_USERS) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PUSH_AUDIENCE_TOO_LARGE",
                        "Audience exceeds " + MAX_USERS + " users; pagination/rollups are required");
            }
            Set<String> matchingUids = new HashSet<>();
            for (QueryDocumentSnapshot doc : userDocs) {
                AudienceProfile profile = profile(doc);
                if (AudienceMatcher.matches(profile, filter, now)) matchingUids.add(profile.uid());
            }

            List<QueryDocumentSnapshot> deviceDocs = firestore.collection(Device.COLLECTION)
                    .limit(MAX_DEVICES + 1).get().get().getDocuments();
            if (deviceDocs.size() > MAX_DEVICES) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PUSH_DEVICE_SET_TOO_LARGE",
                        "Device set exceeds " + MAX_DEVICES + "; pagination is required");
            }
            List<DeviceTarget> targets = new ArrayList<>();
            Set<String> reachableUids = new HashSet<>();
            Set<String> seenTokens = new HashSet<>();
            for (QueryDocumentSnapshot doc : deviceDocs) {
                String uid = doc.getString(Device.UID);
                String token = doc.getString(Device.FCM_TOKEN);
                Boolean disabled = doc.getBoolean(Device.DISABLED);
                if (uid == null || token == null || token.isBlank() || Boolean.TRUE.equals(disabled)
                        || !matchingUids.contains(uid) || !seenTokens.add(token)) {
                    continue;
                }
                targets.add(new DeviceTarget(doc.getId(), uid, token));
                reachableUids.add(uid);
            }
            return new Selection(matchingUids.size(), reachableUids.size(), targets);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Push audience query failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Push audience query interrupted", e);
        }
    }

    private AudienceProfile profile(DocumentSnapshot doc) {
        Timestamp lastEarn = doc.getTimestamp("lastEarnAt");
        return new AudienceProfile(doc.getId(), doc.getString("gender"), doc.getString("birthday"),
                doc.getString("address"), lastEarn == null ? null : lastEarn.toDate().toInstant(),
                doc.getString("topInterest"));
    }

    private Reservation reserve(DocumentReference ref, String actorUid, String requestHash) {
        try {
            return firestore.runTransaction(transaction -> {
                DocumentSnapshot snap = transaction.get(ref).get();
                if (snap.exists()) {
                    if (!requestHash.equals(snap.getString("requestHash"))) throw IdempotencyException.keyReused();
                    if ("completed".equals(snap.getString("status"))) {
                        return new Reservation(true, responseFrom(snap, ref.getId()));
                    }
                    throw new ApiException(HttpStatus.CONFLICT, "PUSH_SEND_IN_PROGRESS",
                            "This campaign was already reserved; use a new key only after checking its status");
                }
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("actorUid", actorUid);
                record.put("requestHash", requestHash);
                record.put("status", "sending");
                record.put("createdAt", FieldValue.serverTimestamp());
                transaction.set(ref, record);
                return new Reservation(false, null);
            }).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Push campaign reservation failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Push campaign reservation interrupted", e);
        }
    }

    private void completeCampaign(DocumentReference ref, PushSendResponse response)
            throws ExecutionException, InterruptedException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "completed");
        data.put("matchedUsers", response.matchedUsers());
        data.put("reachableUsers", response.reachableUsers());
        data.put("targetedDevices", response.targetedDevices());
        data.put("successCount", response.successCount());
        data.put("failureCount", response.failureCount());
        data.put("completedAt", FieldValue.serverTimestamp());
        ref.update(data).get();
    }

    private PushSendResponse responseFrom(DocumentSnapshot snap, String campaignId) {
        return new PushSendResponse(campaignId, number(snap, "matchedUsers"),
                number(snap, "reachableUsers"), number(snap, "targetedDevices"),
                number(snap, "successCount"), number(snap, "failureCount"));
    }

    private static int number(DocumentSnapshot snap, String field) {
        Long value = snap.getLong(field);
        return value == null ? 0 : value.intValue();
    }

    private void disableInvalidDevices(List<DeviceTarget> invalid) {
        if (invalid.isEmpty()) return;
        WriteBatch batch = firestore.batch();
        for (DeviceTarget target : invalid) {
            Map<String, Object> updates = new HashMap<>();
            updates.put(Device.DISABLED, true);
            updates.put(Device.FCM_TOKEN, FieldValue.delete());
            batch.update(firestore.collection(Device.COLLECTION).document(target.documentId()), updates);
        }
        try {
            batch.commit().get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to disable unregistered devices", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Device cleanup interrupted", e);
        }
    }

    private void markFailed(DocumentReference ref) {
        try {
            ref.update(Map.of("status", "failed", "failedAt", FieldValue.serverTimestamp())).get();
        } catch (Exception ignored) {
            // Preserve the original delivery error; campaign status can be repaired operationally.
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize push request", e);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record DeviceTarget(String documentId, String uid, String token) {
    }

    private record Selection(int matchedUsers, int reachableUsers, List<DeviceTarget> devices) {
    }

    private record Reservation(boolean replayed, PushSendResponse response) {
    }
}
