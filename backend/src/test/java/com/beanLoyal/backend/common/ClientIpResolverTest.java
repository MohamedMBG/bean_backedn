package com.beanLoyal.backend.common;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link ClientIpResolver#resolve}, the per-IP rate-limit key derivation. The security-relevant
 * case is {@link #multipleHopsReturnsLastHopNotSpoofableFirst()}: a client can prepend a forged
 * {@code X-Forwarded-For} value, so only Render's own appended LAST hop may be trusted for a rate-limit
 * decision (BUSINESS_RULES §4, {@code ClientIpResolver} class Javadoc). The remaining cases pin the
 * fallback behavior so a malformed header can never collapse every caller into one shared bucket.
 */
class ClientIpResolverTest {

    private static HttpServletRequest requestWith(String forwardedFor, String remoteAddr) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        return request;
    }

    @Test
    void absentHeaderFallsBackToRemoteAddr() {
        assertEquals("10.0.0.1", ClientIpResolver.resolve(requestWith(null, "10.0.0.1")));
    }

    @Test
    void blankHeaderFallsBackToRemoteAddr() {
        assertEquals("10.0.0.1", ClientIpResolver.resolve(requestWith("   ", "10.0.0.1")));
    }

    @Test
    void singleHopIsReturned() {
        assertEquals("203.0.113.7", ClientIpResolver.resolve(requestWith("203.0.113.7", "10.0.0.1")));
    }

    /**
     * The anti-spoof core: with one trusted proxy (Render), the last hop is the peer IP Render
     * observed and appended; every earlier entry is attacker-supplied. A naive first-entry read would
     * return the forged {@code 1.1.1.1} and let a client dodge its rate limit by rotating that value.
     */
    @Test
    void multipleHopsReturnsLastHopNotSpoofableFirst() {
        assertEquals("203.0.113.7",
                ClientIpResolver.resolve(requestWith("1.1.1.1, 2.2.2.2, 203.0.113.7", "10.0.0.1")));
    }

    @Test
    void trailingEmptySegmentSkippedToLastNonBlankHop() {
        // A stray trailing comma must not yield an empty key; the last non-blank hop is used.
        assertEquals("203.0.113.7",
                ClientIpResolver.resolve(requestWith("1.1.1.1, 203.0.113.7, ", "10.0.0.1")));
    }

    @Test
    void allBlankSegmentsFallBackToRemoteAddr() {
        // Header present but no usable hop (e.g. " , ,") must never return "" — that would merge every
        // such caller into a single shared rate-limit bucket. Fall back to the socket peer instead.
        assertEquals("10.0.0.1", ClientIpResolver.resolve(requestWith(" , , ", "10.0.0.1")));
    }
}
