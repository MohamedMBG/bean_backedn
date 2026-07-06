package com.beanLoyal.backend.rewards;

/**
 * Response payload for a successful {@code POST /api/v1/rewards/redeem}.
 * Wrapped in {@link com.beanLoyal.backend.common.ApiResponse} by the controller.
 *
 * @param code             the backend-generated pending redeem code — the {@code redeem_codes/{code}}
 *                         document id the customer shows the cashier (§3.1; backend-generated).
 * @param rewardId         echoed target reward id (client-provided, re-emitted for the client's UI).
 * @param cost             points deducted for this redemption, read from
 *                         {@code rewards_catalog/{rewardId}.cost} at redeem time (backend-generated).
 * @param totalPoints      the user's {@code users/{uid}.points} balance after the deduction (backend-generated).
 * @param expiresAtEpochMs pending-code expiry as epoch milliseconds UTC ({@code createdAt + 15min},
 *                         §3.1; backend-generated) so the client can show a countdown without a
 *                         date-format contract.
 */
public record RedeemResponse(String code, String rewardId, long cost, long totalPoints, long expiresAtEpochMs) {
}
