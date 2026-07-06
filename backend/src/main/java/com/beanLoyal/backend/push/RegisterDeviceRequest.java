package com.beanLoyal.backend.push;

/**
 * Request body for {@code POST /api/v1/push/registerDevice}.
 *
 * @param deviceId client-provided stable per-install id; becomes the {@code devices/{deviceId}}
 *                 document id. Validated by {@link Device#validateDeviceId} (charset/length/
 *                 Firestore-id safety) rather than bean validation, so every format fault maps to the
 *                 single {@code DEVICE_ID_INVALID} code (same rationale {@code EarnRequest} gives).
 * @param fcmToken client-provided opaque FCM registration token; validated for length only and
 *                 NEVER logged.
 * @param platform client-provided device platform; normalized/validated to {@code android|ios|web}.
 */
public record RegisterDeviceRequest(String deviceId, String fcmToken, String platform) {
}
