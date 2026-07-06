package com.beanLoyal.backend.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the caller's IP address for per-IP rate limiting ({@link RateLimitService}).
 * <p>
 * Render terminates TLS at its own reverse proxy, so {@code request.getRemoteAddr()} is always
 * that proxy, never the client — the client address must be read from {@code X-Forwarded-For}
 * instead.
 * <p>
 * {@code X-Forwarded-For} accumulates left-to-right as a request crosses proxies: each hop
 * appends the IP of whoever connected to it directly, then forwards the header onward. With
 * exactly one trusted proxy in front of this backend (Render — see
 * {@code BACKEND_IMPLEMENTATION_PLAN.md §12}, single instance, no additional CDN/WAF), the LAST
 * entry is the one Render itself appended — the real peer IP it observed on the socket — and a
 * client cannot forge it. Every earlier entry is attacker-controlled: a client can set its own
 * fake {@code X-Forwarded-For} value before the request ever reaches Render, which is why the
 * naive "first entry" reading is spoofable and must not be used for a security-relevant decision
 * like a rate limit.
 * <p>
 * If this backend is ever placed behind an additional CDN/WAF layer in front of Render, this
 * resolver MUST be revisited — trusting the last hop only holds for exactly one trusted proxy.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    /**
     * @param request current HTTP request.
     * @return the client IP for rate-limiting purposes; falls back to {@code getRemoteAddr()}
     *         when {@code X-Forwarded-For} is absent, blank, or has no non-blank hop (e.g. a
     *         trailing comma or a stray empty segment) — never returns an empty string, which
     *         would otherwise collapse every such caller into one shared rate-limit bucket.
     */
    public static String resolve(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] hops = forwardedFor.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = hops[i].trim();
                if (!hop.isEmpty()) {
                    return hop;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
