package com.beanLoyal.backend.loyalty;

/**
 * Response payload for a successful {@code POST /api/v1/loyalty/earn} scan.
 * Wrapped in {@link com.beanLoyal.backend.common.ApiResponse} by the controller.
 *
 * @param pointsGranted points added by this scan; read from {@code earn_codes/{code}.points}
 *                      at scan time (backend-generated, §2.1).
 * @param totalPoints   the user's {@code users/{uid}.points} balance after the grant (backend-generated).
 * @param totalVisits   the user's {@code users/{uid}.visits} counter after this scan (backend-generated, §2.7).
 */
public record EarnResponse(long pointsGranted, long totalPoints, long totalVisits) {
}
