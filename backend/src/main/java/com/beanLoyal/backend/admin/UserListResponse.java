package com.beanLoyal.backend.admin;

import java.util.List;

/**
 * Response for {@code GET /api/v1/admin/users}: the admin client roster.
 * Wrapped in {@link com.beanLoyal.backend.common.ApiResponse}. Admins cannot read the {@code users}
 * collection directly (Firestore rules), so the Clients tab reads it through here.
 *
 * @param users capped list of user summaries (see {@link AdminService#MAX_LIMIT}).
 */
public record UserListResponse(List<UserListItem> users) {

    /**
     * @param uid       user document id.
     * @param fullName  {@code users/{uid}.fullName} (may be {@code null}/empty for incomplete profiles).
     * @param email     {@code users/{uid}.email} (may be {@code null}).
     * @param phone     {@code users/{uid}.phone} (may be {@code null}).
     * @param points    current balance.
     * @param visits    visit counter.
     * @param createdAt signup epoch millis, or {@code 0} if unset.
     */
    public record UserListItem(String uid, String fullName, String email, String phone,
                               long points, long visits, long createdAt) {
    }
}
