package com.beanLoyal.backend.push;

/**
 * Logout unregistration result.
 *
 * @param deviceId disabled device document id.
 * @param disabled true when the device can no longer receive campaigns for the previous user.
 */
public record UnregisterDeviceResponse(String deviceId, boolean disabled) {
}
