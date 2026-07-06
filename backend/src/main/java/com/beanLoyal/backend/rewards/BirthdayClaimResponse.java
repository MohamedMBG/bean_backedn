package com.beanLoyal.backend.rewards;

/**
 * Response payload for a successful {@code POST /api/v1/rewards/birthday} claim.
 * Wrapped in {@link com.beanLoyal.backend.common.ApiResponse} by the controller.
 *
 * @param pointsGranted points added by this claim (fixed per {@code BUSINESS_RULES.md §3.7}; backend-generated, not client-provided).
 * @param totalPoints   the user's {@code users/{uid}.points} balance after the grant (backend-generated).
 * @param year          calendar year (UTC) the claim was recorded against, matching the
 *                      {@code birthday_claims/{uid}_{year}} document id (backend-generated).
 */
public record BirthdayClaimResponse(long pointsGranted, long totalPoints, int year) {
}
