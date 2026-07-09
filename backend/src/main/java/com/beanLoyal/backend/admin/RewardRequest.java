package com.beanLoyal.backend.admin;

/**
 * Request body for {@code POST /api/v1/admin/rewards} and {@code PUT /api/v1/admin/rewards/{id}} —
 * the catalog fields an admin controls. All client-provided; re-validated server-side.
 *
 * @param name     display name (required, non-blank).
 * @param cost     points cost (required, non-negative integer).
 * @param category grouping label (optional, may be {@code null}).
 * @param imageUrl catalog image (optional, may be {@code null}).
 * @param active   whether the reward is redeemable; defaults to {@code true} when {@code null}.
 */
public record RewardRequest(String name, Integer cost, String category, String imageUrl, Boolean active) {
}
