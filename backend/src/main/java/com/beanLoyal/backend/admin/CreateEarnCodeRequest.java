package com.beanLoyal.backend.admin;

/**
 * Request body for {@code POST /api/v1/admin/earn-codes}.
 *
 * @param points client-provided point value stored on the earn code (§2.1); required positive
 *               integer, re-validated server-side ({@code INVALID_POINTS}) — no bean-validation
 *               annotation, matching the {@code EarnRequest}/{@code RedeemRequest} convention of
 *               keeping validation in the service, after the rate-limit check.
 */
public record CreateEarnCodeRequest(Integer points) {
}
