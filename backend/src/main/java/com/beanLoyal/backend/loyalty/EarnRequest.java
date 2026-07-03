package com.beanLoyal.backend.loyalty;

/**
 * Request body for {@code POST /api/v1/loyalty/earn}.
 *
 * @param code raw earn code value as scanned/typed by the client (client-provided, untrusted).
 *             Format is checked by {@link EarnCodeService#validateFormat} before any Firestore
 *             access — no bean-validation annotation here so every format failure (null, blank,
 *             wrong length, bad character) maps to the single {@code EARN_CODE_INVALID_FORMAT}
 *             domain error code from {@code docs/BUSINESS_RULES.md §2.8} instead of being split
 *             between generic {@code VALIDATION_FAILED} and the domain code.
 */
public record EarnRequest(String code) {
}
