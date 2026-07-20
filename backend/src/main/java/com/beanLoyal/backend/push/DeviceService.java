package com.beanLoyal.backend.push;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.SetOptions;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ExecutionException;

/**
 * Registers/updates an FCM device token as {@code devices/{deviceId}} ({@code BUSINESS_RULES.md §8}).
 * <p>
 * A single-document upsert via {@code set(SetOptions.merge())} — no transaction and no
 * {@code IdempotencyService}: the write is naturally idempotent (replaying the same request yields
 * the same terminal doc; only {@code lastSeenAt} advances), and there is no economy invariant a
 * duplicate could corrupt, so the §1 idempotency ledger is intentionally skipped.
 * <p>
 * Ownership is reassign / last-writer-wins: {@code uid} is overwritten to the caller on every call,
 * which is the legitimate "log out user A, log in user B on the same device" flow. This never reads
 * {@code users/{uid}} (devices is its own collection), so there is no {@code USER_NOT_FOUND} path.
 * The {@code fcmToken} is never logged.
 */
@Service
public class DeviceService {

    private final Firestore firestore;
    private final Clock clock;

    public DeviceService(Firestore firestore, Clock clock) {
        this.firestore = firestore;
        this.clock = clock;
    }

    /**
     * Validate the request and upsert {@code devices/{deviceId}} for {@code uid}.
     * <p>
     * {@code .get()} blocks until the write commits, so a 200 response means the doc was persisted.
     *
     * @param uid verified Firebase UID of the caller (never a client-supplied id).
     * @param req client-supplied device payload.
     * @return the written device id + registration timestamp.
     * @throws com.beanLoyal.backend.common.ApiException 400 {@code DEVICE_ID_INVALID} /
     *         {@code FCM_TOKEN_INVALID} / {@code INVALID_PLATFORM} on validation failure.
     * @throws ExecutionException   propagated from the Firestore write.
     * @throws InterruptedException propagated from the Firestore write.
     */
    public RegisterDeviceResponse register(String uid, RegisterDeviceRequest req)
            throws ExecutionException, InterruptedException {
        Device.validateDeviceId(req.deviceId());
        Device.validateToken(req.fcmToken());
        String platform = Device.normalizePlatform(req.platform());

        Instant now = Instant.now(clock);
        firestore.collection(Device.COLLECTION)
                .document(req.deviceId())
                .set(Device.doc(uid, req.fcmToken(), platform, now), SetOptions.merge())
                .get();
        return new RegisterDeviceResponse(req.deviceId(), now.toEpochMilli());
    }

    /**
     * Disable the caller's device on logout and delete its FCM token. The ownership check prevents
     * one authenticated user from disabling another user's installation even if a device id leaks.
     * Missing devices are treated as already disabled, making logout retries naturally idempotent.
     *
     * @param uid verified caller UID.
     * @param req client stable device id.
     * @return disabled state for the device.
     */
    public UnregisterDeviceResponse unregister(String uid, UnregisterDeviceRequest req) {
        Device.validateDeviceId(req == null ? null : req.deviceId());
        var ref = firestore.collection(Device.COLLECTION).document(req.deviceId());
        try {
            return firestore.runTransaction(transaction -> {
                var snapshot = transaction.get(ref).get();
                if (!snapshot.exists()) return new UnregisterDeviceResponse(req.deviceId(), true);
                String owner = snapshot.getString(Device.UID);
                if (owner != null && !owner.equals(uid)) {
                    throw new com.beanLoyal.backend.common.ApiException(
                            org.springframework.http.HttpStatus.FORBIDDEN,
                            "DEVICE_NOT_OWNED", "Device does not belong to the authenticated user");
                }
                java.util.Map<String, Object> updates = new java.util.LinkedHashMap<>();
                updates.put(Device.DISABLED, true);
                updates.put(Device.FCM_TOKEN, FieldValue.delete());
                updates.put(Device.LAST_SEEN_AT, FieldValue.serverTimestamp());
                transaction.update(ref, updates);
                return new UnregisterDeviceResponse(req.deviceId(), true);
            }).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Device unregistration failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Device unregistration interrupted", e);
        }
    }
}
