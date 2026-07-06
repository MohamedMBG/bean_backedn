package com.beanLoyal.backend.cashier;

/**
 * Response payload for a successful {@code POST /api/v1/cashier/redeem/complete}.
 * Wrapped in {@link com.beanLoyal.backend.common.ApiResponse} by the controller.
 *
 * @param code       the completed redeem code (echoed).
 * @param rewardName reward display name copied from {@code redeem_codes/{code}.rewardName} so the
 *                   cashier screen confirms what to hand over (Firestore-derived; may be {@code null}
 *                   if the code was created without a name).
 * @param status     always {@code "completed"} on success (backend-set).
 */
public record CashierCompleteResponse(String code, String rewardName, String status) {
}
