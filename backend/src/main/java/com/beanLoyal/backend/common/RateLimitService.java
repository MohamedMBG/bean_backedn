package com.beanLoyal.backend.common;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * In-memory token-bucket rate limiter backed by Bucket4j.
 * <p>
 * Buckets are keyed by {@code policy identity + subject (IP or UID)}. Each request that must
 * respect a policy calls {@link #check(RateLimitPolicy, String, String)} once — the service
 * consumes one token from the IP bucket AND one from the UID bucket (when a UID is available)
 * and throws {@link RateLimitException} if either is empty.
 * <p>
 * Storage is a {@link ConcurrentHashMap} — fine for a single Render instance (the current
 * deployment target per {@code BACKEND_IMPLEMENTATION_PLAN.md §12}). Distributed enforcement
 * would require moving buckets to Redis; deferred until Render scales beyond one instance.
 * <p>
 * The map grows unbounded in the pathological case of a very large distinct-IP or distinct-UID
 * set; each entry is a few hundred bytes and Bucket4j itself has no per-bucket timer, so idle
 * entries are cheap. Eviction can be layered on later if RSS becomes a concern — no need now.
 */
@Service
public class RateLimitService {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Consume one token from the policy's IP bucket and, if {@code uid} is non-null,
     * one token from the policy's UID bucket.
     *
     * @param policy    rate-limit policy for the route class
     * @param clientIp  remote address of the caller — never null; use {@code "unknown"} if the
     *                  transport truly cannot provide one (rare)
     * @param uid       Firebase UID of the authenticated caller, or {@code null} for anonymous
     *                  requests (pre-auth or public endpoints that still want IP throttling)
     * @throws RateLimitException if either the IP-side or UID-side bucket is empty; the
     *                            {@code retryAfterSeconds} value is the larger of the two
     *                            wait times so the client's next attempt clears both sides
     */
    public void check(RateLimitPolicy policy, String clientIp, String uid) {
        long retryAfterSec = 0L;

        ConsumptionProbe ipProbe = probe(policy, policy.ipBandwidth(), "ip", clientIp);
        if (!ipProbe.isConsumed()) {
            retryAfterSec = secondsUntilRefill(ipProbe);
        }

        if (uid != null) {
            ConsumptionProbe uidProbe = probe(policy, policy.uidBandwidth(), "uid", uid);
            if (!uidProbe.isConsumed()) {
                retryAfterSec = Math.max(retryAfterSec, secondsUntilRefill(uidProbe));
            }
        }

        if (retryAfterSec > 0) {
            throw new RateLimitException(retryAfterSec);
        }
    }

    private ConsumptionProbe probe(RateLimitPolicy policy, Bandwidth bandwidth, String side, String subject) {
        // Key by policy reference identity so two policies with numerically identical bandwidths
        // still receive separate buckets. Reference identity is safe: RateLimitPolicy instances
        // are shared (static constants or singletons), so the same policy always hashes the same.
        String key = side + ":" + System.identityHashCode(policy) + ":" + subject;
        Bucket bucket = buckets.computeIfAbsent(key, k -> Bucket.builder().addLimit(bandwidth).build());
        return bucket.tryConsumeAndReturnRemaining(1);
    }

    private static long secondsUntilRefill(ConsumptionProbe probe) {
        // Round up so Retry-After never asks the client to retry before a token exists.
        long seconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
        return Math.max(1L, seconds);
    }
}
