package com.beanLoyal.backend.cashier;

/**
 * Request body for {@code POST /api/v1/cashier/redeem/complete}.
 *
 * @param code the pending redeem code the customer presented (client-provided, untrusted;
 *             re-validated and expiry-checked server-side in {@code RedeemCodeService.complete}).
 *             A null/blank value maps to 400 {@code BAD_REQUEST}.
 */
public record CashierCompleteRequest(String code) {
}
