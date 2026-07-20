package com.beanLoyal.backend.push;

/**
 * Preview result for an audience selection.
 *
 * @param count            unique matching users who have at least one active registered device;
 *                         retained as {@code count} for the existing admin UI contract.
 * @param matchedUsers     profiles matching the filters, including users without a device.
 * @param reachableDevices active device tokens that would receive the notification.
 */
public record PushPreviewResponse(int count, int matchedUsers, int reachableDevices) {
}
