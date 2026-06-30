package com.beanLoyal.backend.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Per-request observability filter.
 * <p>
 * Generates a UUID request id and:
 * <ul>
 *   <li>Stores it in SLF4J MDC under key {@code requestId} so every log line emitted while handling
 *       the request carries the same correlation id (logback pattern includes {@code %X{requestId}}).</li>
 *   <li>Echoes it back as {@code X-Request-ID} response header so clients can quote it when reporting
 *       issues, and so on-call can correlate client-side and server-side traces.</li>
 *   <li>Logs one INFO line per request: method, path, status, duration. No headers, no body —
 *       this is how we satisfy the "never log Authorization / Firebase tokens" rule without
 *       maintaining a header-redaction allow/deny list.</li>
 * </ul>
 * <p>
 * Ordered HIGHEST_PRECEDENCE so the request id is available in MDC for every downstream filter
 * (including FirebaseAuthFilter) and every handler.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final String MDC_KEY = "requestId";
    private static final String HEADER = "X-Request-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Honor a client-supplied request id if present, otherwise mint one.
        // Reusing the client id lets a mobile crash report tie back to the server log.
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Always log + clear, even if downstream threw — partial requests still need traces.
            long duration = System.currentTimeMillis() - start;
            log.info("{} {} -> {} ({} ms)",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    duration);
            MDC.remove(MDC_KEY);
        }
    }
}
