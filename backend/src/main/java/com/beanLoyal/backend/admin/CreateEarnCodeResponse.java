package com.beanLoyal.backend.admin;

/**
 * Response for a created earn code, wrapped in {@link com.beanLoyal.backend.common.ApiResponse}.
 *
 * @param code      the generated 10-char §2.5 code value (backend-generated; IS the {@code earn_codes} doc id).
 * @param points    echoed point value stored on the code.
 * @param expiresAt expiry as epoch millis ({@code createdAt + 24h}, §2.2; backend-generated).
 */
public record CreateEarnCodeResponse(String code, long points, long expiresAt) {
}
