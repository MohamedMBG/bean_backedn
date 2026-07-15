package com.beanLoyal.backend.common;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * In-memory token-bucket rate limiter backed by Bucket4j.
 * <p>
 * Buckets are keyed by policy identity and subject (IP or UID). Each protected request consumes a
 * token from the IP bucket and, when a Firebase UID is available, its UID bucket. The service
 * throws {@link RateLimitException} when either bucket is exhausted.
 * <p>
 * Storage is local to one backend instance. This is suitable for the current single-instance
 * deployment but must move to a shared store such as Redis before horizontal scaling. An hourly
 * sweep evicts buckets unused for two days, preventing high-cardinality traffic from growing the
 * map forever. Two days exceeds the one-day birthday-policy refill interval, so cleanup cannot
 * reset an active quota.
 */
@Service
public class RateLimitService {

    /** Idle retention exceeds the longest configured Bucket4j refill interval. */
    static final long ENTRY_IDLE_TTL_NANOS = TimeUnit.DAYS.toNanos(2);

    private final ConcurrentMap<String, BucketEntry> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    /** Creates the production limiter using the JVM's monotonic clock for eviction timing. */
    public RateLimitService() {
        this(System::nanoTime);
    }

    /** Package-private deterministic-time constructor used by unit tests. */
    RateLimitService(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /**
     * Consumes one token from the IP bucket and, when present, one from the UID bucket.
     *
     * @param policy rate-limit policy for the route class.
     * @param clientIp caller address, or {@code "unknown"} when unavailable.
     * @param uid verified Firebase UID, or {@code null} for an anonymous caller.
     * @throws RateLimitException when either bucket has no token; the retry value waits for both.
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
        String key = side + ":" + System.identityHashCode(policy) + ":" + subject;
        long now = nanoTime.getAsLong();
        BucketEntry entry = buckets.compute(key, (ignored, existing) -> {
            if (existing == null) {
                return new BucketEntry(Bucket.builder().addLimit(bandwidth).build(), now);
            }
            existing.lastAccessNanos = now;
            return existing;
        });
        return entry.bucket.tryConsumeAndReturnRemaining(1);
    }

    /** Removes local buckets untouched for two days, keeping high-cardinality traffic bounded. */
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void evictIdleBuckets() {
        long now = nanoTime.getAsLong();
        buckets.forEach((key, entry) -> buckets.computeIfPresent(key, (ignored, current) ->
                isIdle(current, now) ? null : current));
    }

    /** Visible to package tests to verify the bounded-memory invariant without reflection. */
    int bucketCount() {
        return buckets.size();
    }

    private static boolean isIdle(BucketEntry entry, long now) {
        return now - entry.lastAccessNanos >= ENTRY_IDLE_TTL_NANOS;
    }

    private static long secondsUntilRefill(ConsumptionProbe probe) {
        long seconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
        return Math.max(1L, seconds);
    }

    /** Bucket paired with its last monotonic access time. */
    private static final class BucketEntry {
        private final Bucket bucket;
        private volatile long lastAccessNanos;

        private BucketEntry(Bucket bucket, long lastAccessNanos) {
            this.bucket = bucket;
            this.lastAccessNanos = lastAccessNanos;
        }
    }
}
