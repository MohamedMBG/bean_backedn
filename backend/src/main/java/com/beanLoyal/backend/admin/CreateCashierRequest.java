package com.beanLoyal.backend.admin;

/**
 * Request body for {@code POST /api/v1/admin/cashiers} — provision a cashier account.
 * All client-provided; re-validated server-side. The {@code password} is used once to create the
 * Firebase Auth account and is never stored or logged.
 *
 * @param email    login email (required, must look like an email).
 * @param password initial password (required, min 6 chars — Firebase's floor).
 * @param name     display name (optional, may be {@code null}).
 */
public record CreateCashierRequest(String email, String password, String name) {
}
