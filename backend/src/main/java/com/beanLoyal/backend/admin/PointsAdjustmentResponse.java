package com.beanLoyal.backend.admin;

/**
 * Response for a manual points adjustment, wrapped in {@link com.beanLoyal.backend.common.ApiResponse}.
 *
 * @param delta       the applied signed adjustment (echoed).
 * @param totalPoints {@code users/{uid}.points} after the adjustment (backend-computed).
 * @param reason      echoed reason (also stored on the activity + audit entry).
 */
public record PointsAdjustmentResponse(long delta, long totalPoints, String reason) {
}
