package com.beanLoyal.backend.rewards;

/**
 * Request body for {@code POST /api/v1/rewards/redeem/cancel}.
 *
 * @param code the pending redeem code to cancel (client-provided, untrusted). No bean-validation
 *             annotation — a null/blank value is guarded inside {@link RedeemCodeService#cancel} and
 *             mapped to 400 {@code BAD_REQUEST}, matching the {@code EarnRequest}/{@code RedeemRequest}
 *             convention of keeping validation in the service so it runs after the rate-limit check.
 *             Ownership is enforced server-side against {@code redeem_codes/{code}.uid}.
 */
public record CancelRequest(String code) {
}
