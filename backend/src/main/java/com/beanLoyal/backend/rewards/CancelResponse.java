package com.beanLoyal.backend.rewards;

/**
 * Response payload for a successful {@code POST /api/v1/rewards/redeem/cancel}.
 * Wrapped in {@link com.beanLoyal.backend.common.ApiResponse} by the controller.
 *
 * @param code        the cancelled redeem code (echoed).
 * @param refunded    points credited back to the caller — the code's stored {@code cost} (§3.2; backend-generated).
 * @param totalPoints the caller's {@code users/{uid}.points} balance after the refund (backend-generated).
 */
public record CancelResponse(String code, long refunded, long totalPoints) {
}
