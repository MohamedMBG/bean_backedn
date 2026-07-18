package com.beanLoyal.backend.push;

/**
 * Request body for {@code POST /api/v1/push/unregisterDevice}.
 *
 * @param deviceId stable per-install id previously used for registration.
 */
public record UnregisterDeviceRequest(String deviceId) {
}
