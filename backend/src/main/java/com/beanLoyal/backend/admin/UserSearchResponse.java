package com.beanLoyal.backend.admin;

import java.util.List;

/**
 * Response for {@code GET /api/v1/admin/users/search}: matched user summaries.
 * Wrapped in {@link com.beanLoyal.backend.common.ApiResponse}.
 *
 * @param users matched users (capped in the service). All values Firestore-derived.
 */
public record UserSearchResponse(List<UserSummary> users) {

    /**
     * @param uid    user document id.
     * @param email  {@code users/{uid}.email} (may be {@code null}).
     * @param phone  {@code users/{uid}.phone} (may be {@code null}).
     * @param points current balance.
     * @param visits visit counter.
     */
    public record UserSummary(String uid, String email, String phone, long points, long visits) {
    }
}
