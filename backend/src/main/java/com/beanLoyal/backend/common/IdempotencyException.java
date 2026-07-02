package com.beanLoyal.backend.common;

import org.springframework.http.HttpStatus;

/**
 * Raised by {@link IdempotencyService} when the {@code Idempotency-Key} contract
 * (see {@code docs/BUSINESS_RULES.md §1}) is violated by the caller.
 * <p>
 * Carries its own {@link HttpStatus} and {@link ApiError} code so a single
 * {@code GlobalExceptionHandler} mapping can translate either failure case without
 * a separate exception class per status:
 * <ul>
 *   <li>{@link #keyRequired()} → 400 {@code IDEMPOTENCY_KEY_REQUIRED} — header missing on a required route.</li>
 *   <li>{@link #keyReused()} → 409 {@code IDEMPOTENCY_KEY_REUSED} — same key replayed with a different body.</li>
 * </ul>
 * Runtime exception on purpose: this is a control-flow decision inside a request, not a
 * recoverable error the caller catches.
 */
public class IdempotencyException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private IdempotencyException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * A state-mutating route required an {@code Idempotency-Key} header but none (or a blank one)
     * was supplied. Maps to 400 so the client fixes its request before any business logic runs.
     */
    public static IdempotencyException keyRequired() {
        return new IdempotencyException(HttpStatus.BAD_REQUEST,
                "IDEMPOTENCY_KEY_REQUIRED",
                "Idempotency-Key header is required");
    }

    /**
     * The same {@code Idempotency-Key} was reused with a different request body. Treated as an
     * error (409), never a silent overwrite — a reused key with a changed payload is a client bug
     * or replay attack and is the worst kind of duplicate-write to debug after the fact.
     */
    public static IdempotencyException keyReused() {
        return new IdempotencyException(HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key already used with a different request");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
