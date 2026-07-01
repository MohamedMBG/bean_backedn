package com.beanLoyal.backend.common;

/**
 * Thrown by {@link RateLimitService} when a caller has exhausted their bucket for a policy.
 * <p>
 * Handled by {@code GlobalExceptionHandler} which returns HTTP 429 with the
 * {@code Retry-After} header set from {@link #getRetryAfterSeconds()} and an
 * {@link ApiError} body using code {@code RATE_LIMITED} (per {@code BUSINESS_RULES.md §4}).
 * <p>
 * Runtime exception on purpose: this signals a control-flow decision inside a request, not a
 * recoverable business error the caller should try to catch.
 */
public class RateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    /**
     * @param retryAfterSeconds seconds until the smallest offending bucket refills at least one
     *                          token, rounded up to a whole second (never zero — RFC 9110 requires
     *                          a non-zero value for the {@code Retry-After} header).
     */
    public RateLimitException(long retryAfterSeconds) {
        super("rate limit exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
