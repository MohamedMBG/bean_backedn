package com.beanLoyal.backend.admin;

/**
 * Response for {@code POST /api/v1/admin/rewards} and {@code PUT /api/v1/admin/rewards/{id}}: the
 * persisted catalog entry. Wrapped in {@link com.beanLoyal.backend.common.ApiResponse}.
 *
 * @param id       {@code rewards_catalog} document id (generated on create).
 * @param name     display name.
 * @param cost     points cost.
 * @param category grouping label, or {@code null}.
 * @param imageUrl catalog image, or {@code null}.
 * @param active   whether the reward is redeemable.
 */
public record RewardResponse(String id, String name, int cost, String category, String imageUrl,
                             boolean active) {
}
