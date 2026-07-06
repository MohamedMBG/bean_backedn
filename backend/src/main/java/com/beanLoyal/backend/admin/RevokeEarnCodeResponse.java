package com.beanLoyal.backend.admin;

/**
 * Response for a revoked earn code, wrapped in {@link com.beanLoyal.backend.common.ApiResponse}.
 *
 * @param code   the revoked earn code (echoed).
 * @param status always {@code "revoked"} on success (backend-set).
 */
public record RevokeEarnCodeResponse(String code, String status) {
}
