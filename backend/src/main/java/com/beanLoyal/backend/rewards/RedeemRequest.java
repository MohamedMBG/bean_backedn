package com.beanLoyal.backend.rewards;

/**
 * Request body for {@code POST /api/v1/rewards/redeem}.
 *
 * @param rewardId document id of the target {@code rewards_catalog/{rewardId}} entry
 *                 (client-provided, untrusted — existence/active/cost are all re-checked
 *                 server-side inside the redemption transaction, never trusted from the client).
 */
public record RedeemRequest(String rewardId) {
}
