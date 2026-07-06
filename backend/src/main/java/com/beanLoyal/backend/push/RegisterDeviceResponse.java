package com.beanLoyal.backend.push;

/**
 * Response payload for a successful {@code POST /api/v1/push/registerDevice}.
 * Wrapped in {@link com.beanLoyal.backend.common.ApiResponse} by the controller.
 *
 * @param deviceId   echoed back so the client confirms which {@code devices/{deviceId}} doc was
 *                   written (backend-echoed).
 * @param lastSeenAt epoch millis of this registration, from the injected {@code Clock}
 *                   (backend-generated); matches the stored {@code devices/{deviceId}.lastSeenAt}.
 */
public record RegisterDeviceResponse(String deviceId, long lastSeenAt) {
}
