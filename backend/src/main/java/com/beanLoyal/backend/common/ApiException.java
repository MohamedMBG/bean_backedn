package com.beanLoyal.backend.common;

import org.springframework.http.HttpStatus;

/**
 * Generic carrier for a business-rule failure that must reach the client as a specific HTTP
 * status and {@link ApiError} code — e.g. the domain error tables in
 * {@code docs/BUSINESS_RULES.md} (§2.8 QR earn, §3.9 redemption, birthday claim).
 * <p>
 * Service methods throw this directly with the status/code/message the relevant business-rules
 * table specifies, instead of a new exception subclass per error code. A single
 * {@code GlobalExceptionHandler} mapping translates any instance to {@code ApiError} JSON.
 * <p>
 * Runtime exception on purpose: thrown from inside a Firestore transaction lambda
 * ({@code IdempotencyService.TransactionalWork}) to abort the transaction and propagate the
 * business failure past the {@code ExecutionException} wrapper — see
 * {@code IdempotencyService.runGuarded}, which rethrows any {@link RuntimeException} cause as-is.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    /**
     * @param status  HTTP status to return (e.g. {@code 422} for a semantically invalid request,
     *                {@code 409} for a state conflict, {@code 404}/{@code 410} for missing/expired).
     * @param code    machine-readable {@link ApiError} code from the relevant business-rules error table.
     * @param message human-readable message; safe to echo to the client (service-thrown, author-controlled,
     *                same trust level as the existing {@code IllegalArgumentException} → {@code BAD_REQUEST} path).
     */
    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
