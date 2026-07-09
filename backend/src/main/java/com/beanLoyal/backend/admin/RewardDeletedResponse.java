package com.beanLoyal.backend.admin;

/**
 * Response for {@code DELETE /api/v1/admin/rewards/{id}}: confirmation of a hard delete.
 * Wrapped in {@link com.beanLoyal.backend.common.ApiResponse}.
 *
 * @param id      the removed {@code rewards_catalog} document id.
 * @param deleted always {@code true} on success (the endpoint 404s if the reward was absent).
 */
public record RewardDeletedResponse(String id, boolean deleted) {
}
