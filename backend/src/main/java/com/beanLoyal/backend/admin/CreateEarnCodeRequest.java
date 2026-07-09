package com.beanLoyal.backend.admin;

/**
 * Request body for {@code POST /api/v1/admin/earn-codes}.
 *
 * @param amountMad the purchase amount in MAD the cashier rings up (§2.1); required positive number,
 *                  re-validated server-side ({@code INVALID_AMOUNT}). The backend derives the point
 *                  value from it at the fixed {@code POINTS_PER_MAD} ratio — pricing is backend-owned,
 *                  never trusted from the client. Validation lives in the service, after the
 *                  rate-limit check, matching the {@code EarnRequest}/{@code RedeemRequest} convention.
 */
public record CreateEarnCodeRequest(Double amountMad) {
}
