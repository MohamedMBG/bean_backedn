package com.beanLoyal.backend.admin;

/**
 * Response for {@code POST /api/v1/admin/cashiers}: the provisioned cashier.
 * Wrapped in {@link com.beanLoyal.backend.common.ApiResponse}. Never echoes the password.
 *
 * @param uid   the new Firebase Auth uid (also the {@code users/{uid}} document id).
 * @param email the cashier's login email.
 */
public record CreateCashierResponse(String uid, String email) {
}
