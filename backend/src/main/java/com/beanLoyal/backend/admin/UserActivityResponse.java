package com.beanLoyal.backend.admin;

import java.util.List;

/**
 * Response for {@code GET /api/v1/admin/users/{uid}/activity}: a user's recent activity feed.
 * Wrapped in {@link com.beanLoyal.backend.common.ApiResponse}. Reads the canonical
 * {@code users/{uid}/activities} schema written by {@code ActivityService}.
 *
 * @param uid        the queried user.
 * @param activities most-recent-first activity entries (capped in the service).
 */
public record UserActivityResponse(String uid, List<ActivityEntry> activities) {

    /**
     * @param type        one of {@code earn|redeem|cancel|expire|birthday|adjust}.
     * @param pointsDelta signed balance change ({@code +} credit / {@code -} debit).
     * @param refId       source doc id (code/claim), or {@code null}.
     * @param balanceAfter balance after the event.
     * @param createdAt   epoch millis, or {@code 0} if the server timestamp has not resolved yet.
     */
    public record ActivityEntry(String type, long pointsDelta, String refId, long balanceAfter, long createdAt) {
    }
}
