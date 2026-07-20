package com.beanLoyal.backend.push;

/**
 * Final delivery summary for an admin push campaign.
 *
 * @param campaignId     backend-generated id used for audit/support correlation.
 * @param matchedUsers   profiles matching the audience filters.
 * @param reachableUsers unique matching users with at least one active device.
 * @param targetedDevices number of active device tokens submitted to FCM.
 * @param successCount   device sends accepted successfully by FCM.
 * @param failureCount   device sends rejected by FCM.
 */
public record PushSendResponse(String campaignId,
                               int matchedUsers,
                               int reachableUsers,
                               int targetedDevices,
                               int successCount,
                               int failureCount) {
}
