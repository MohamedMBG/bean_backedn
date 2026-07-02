package com.beanLoyal.backend.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * Centralized HTTP error mapping for the whole API.
 * <p>
 * Every exception thrown out of a controller (or escaping a service into the MVC layer) is translated
 * here into a uniform {@link ApiError} JSON response. The goal is a single response shape across the
 * entire API so the mobile client only has to parse one format for both success and failure.
 * <p>
 * Security discipline:
 * <ul>
 *   <li>Internal exception messages are never echoed to the client on 500 responses — they may contain
 *       stack traces, SQL fragments, file paths, or other internals that aid an attacker.</li>
 *   <li>Validation messages ARE echoed because they are author-controlled (bean validation annotations).</li>
 *   <li>{@code IllegalArgumentException} messages are echoed because services throw them deliberately
 *       as user-facing business validation.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Bean validation failure on a {@code @Valid @RequestBody} payload.
     * Aggregates every field error into a single human-readable message and returns 400.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.debug("Validation failed: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("VALIDATION_FAILED", message));
    }

    /**
     * Bean validation failure on a {@code @Validated} parameter — e.g. {@code @RequestParam}
     * or {@code @PathVariable} constraint violation. Same shape as field-level validation errors.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.debug("Constraint violation: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("VALIDATION_FAILED", message));
    }

    /**
     * Request body could not be parsed as JSON (malformed, wrong type, etc.).
     * We do NOT forward Jackson's message because it leaks internal class names and field info.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleBadJson(HttpMessageNotReadableException ex) {
        log.debug("Malformed JSON body");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("MALFORMED_JSON", "Request body is not valid JSON"));
    }

    /**
     * Business validation thrown by service layer (e.g. "code already used", "amount must be positive").
     * Message is safe to echo because it originated from our own service code, not user input or framework.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArg(IllegalArgumentException ex) {
        log.debug("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("BAD_REQUEST", ex.getMessage()));
    }

    /**
     * Authenticated user lacks required role/authority for the requested resource.
     * Generic 403 — do not reveal which role would have been sufficient (information leak).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "Access denied"));
    }

    /**
     * No controller mapped to the requested path. Requires
     * {@code spring.mvc.throw-exception-if-no-handler-found: true} in application.yaml,
     * otherwise Spring serves its default whitelabel page instead.
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Resource not found"));
    }

    /**
     * HTTP method not allowed for the matched route (e.g. POST against a GET endpoint).
     * Includes the supported methods in the {@code Allow} response header per RFC 9110.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null) {
            builder.allow(ex.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]));
        }
        return builder.body(ApiError.of("METHOD_NOT_ALLOWED", ex.getMessage()));
    }

    /**
     * Caller exhausted a rate-limit bucket (per {@code BUSINESS_RULES.md §4}).
     * Sets {@code Retry-After} per RFC 9110 so well-behaved clients back off deterministically.
     */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ApiError> handleRateLimit(RateLimitException ex) {
        log.warn("Rate limit exceeded; retryAfter={}s", ex.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(ex.getRetryAfterSeconds()))
                .body(ApiError.of("RATE_LIMITED", "Too many requests"));
    }

    /**
     * Caller violated the {@code Idempotency-Key} contract (per {@code BUSINESS_RULES.md §1}).
     * The exception carries its own status + code: 400 {@code IDEMPOTENCY_KEY_REQUIRED} (missing
     * header) or 409 {@code IDEMPOTENCY_KEY_REUSED} (same key, different body).
     */
    @ExceptionHandler(IdempotencyException.class)
    public ResponseEntity<ApiError> handleIdempotency(IdempotencyException ex) {
        // Key reused with a changed body is a client bug or replay attack worth a WARN;
        // a missing header is a routine client mistake, so keep it at DEBUG.
        if (ex.getStatus() == HttpStatus.CONFLICT) {
            log.warn("Idempotency conflict: {}", ex.getCode());
        } else {
            log.debug("Idempotency error: {}", ex.getCode());
        }
        return ResponseEntity.status(ex.getStatus())
                .body(ApiError.of(ex.getCode(), ex.getMessage()));
    }

    /**
     * Catch-all for any exception not handled above. The exception message MUST NOT be exposed to
     * the client — it may carry stack details, internal identifiers, or third-party error text.
     * The full stack is logged server-side so on-call can correlate via the request id.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleFallthrough(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "Internal error"));
    }
}
