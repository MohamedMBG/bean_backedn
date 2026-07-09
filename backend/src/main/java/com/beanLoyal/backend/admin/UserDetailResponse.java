package com.beanLoyal.backend.admin;

/**
 * Response for {@code GET /api/v1/admin/users/{uid}}: one user's full profile for the admin
 * client-details header. Wrapped in {@link com.beanLoyal.backend.common.ApiResponse}. Admins
 * cannot read another user's {@code users/{uid}} doc directly (Firestore rules), so the header
 * reads it through here.
 *
 * @param uid       user document id.
 * @param fullName  {@code users/{uid}.fullName} (may be {@code null}/empty).
 * @param email     {@code users/{uid}.email} (may be {@code null}).
 * @param phone     {@code users/{uid}.phone} (may be {@code null}).
 * @param gender    {@code users/{uid}.gender} (may be {@code null}).
 * @param address   {@code users/{uid}.address} (may be {@code null}).
 * @param birthday  {@code users/{uid}.birthday} as {@code yyyy-MM-dd} (may be {@code null}).
 * @param points    current balance.
 * @param visits    visit counter.
 * @param createdAt signup epoch millis, or {@code 0} if unset.
 * @param lastEarnAt epoch millis of the last earn (the closest thing to "last visit"), or {@code 0}.
 */
public record UserDetailResponse(String uid, String fullName, String email, String phone,
                                 String gender, String address, String birthday,
                                 long points, long visits, long createdAt, long lastEarnAt) {
}
