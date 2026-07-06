package com.beanLoyal.backend.admin;

/**
 * Request body for {@code POST /api/v1/admin/users/{uid}/points-adjustment}.
 *
 * @param delta  signed point adjustment (client-provided, must be non-zero); re-validated server-side
 *               ({@code INVALID_ADJUSTMENT}).
 * @param reason required human note stored on the activity + audit entry ({@code ADJUSTMENT_REASON_REQUIRED}
 *               if blank).
 *               <p>The target uid is a path variable, never in the body (never trust a client-supplied uid).
 */
public record PointsAdjustmentRequest(Integer delta, String reason) {
}
