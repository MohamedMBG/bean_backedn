package com.beanLoyal.backend.common;

import io.github.bucket4j.Bandwidth;

import java.time.Duration;

/**
 * Rate-limit policy for a class of routes. Each policy carries two independent {@link Bandwidth}
 * definitions: one applied per client IP, one per authenticated Firebase UID.
 * <p>
 * Values mirror {@code docs/BUSINESS_RULES.md §4}. Change here and there together.
 * <p>
 * Both bandwidths are consumed on every request so the effective cap is the stricter of the two.
 * A route hit by an anonymous client (no UID yet in {@code SecurityContext}) skips the UID side
 * and only pays the IP side — pre-auth surface stays bounded without falsely rejecting first-time
 * traffic.
 *
 * @param ipBandwidth  per-source-IP bucket definition
 * @param uidBandwidth per-Firebase-UID bucket definition
 */
public record RateLimitPolicy(Bandwidth ipBandwidth, Bandwidth uidBandwidth) {

    /** {@code POST /api/v1/loyalty/earn} — 30/min per IP, 10/min per UID. */
    public static final RateLimitPolicy EARN = new RateLimitPolicy(
            perMinute(30),
            perMinute(10)
    );

    /** {@code POST /api/v1/rewards/redeem} — 20/min per IP, 5/min per UID. */
    public static final RateLimitPolicy REDEEM = new RateLimitPolicy(
            perMinute(20),
            perMinute(5)
    );

    /**
     * {@code POST /api/v1/rewards/birthday} — 20/min per IP, 3/day per UID.
     * Daily UID quota mirrors the once-per-year business rule with a generous retry envelope
     * for network flakes; the idempotency layer still guarantees single grant per calendar year.
     */
    public static final RateLimitPolicy BIRTHDAY = new RateLimitPolicy(
            perMinute(20),
            Bandwidth.builder().capacity(3).refillGreedy(3, Duration.ofDays(1)).build()
    );

    /** {@code POST /api/v1/cashier/**} and {@code /api/v1/admin/**} — 60/min per IP and per UID. */
    public static final RateLimitPolicy CASHIER_ADMIN = new RateLimitPolicy(
            perMinute(60),
            perMinute(60)
    );

    /**
     * {@code POST /api/v1/push/registerDevice} — 30/min per IP, 10/min per UID.
     * A distinct constant (not reusing {@link #EARN}) because {@code RateLimitService} keys buckets by
     * policy reference identity — sharing {@code EARN} would let earn bursts drain the registration
     * bucket. The 30/min IP envelope matches {@code EARN} so shared coffee-shop NAT wifi is not
     * falsely throttled.
     */
    public static final RateLimitPolicy REGISTER_DEVICE = new RateLimitPolicy(
            perMinute(30),
            perMinute(10)
    );

    /** Customer menu-interest events: 120/min per IP, 30/min per UID. */
    public static final RateLimitPolicy INTEREST_EVENT = new RateLimitPolicy(
            perMinute(120),
            perMinute(30)
    );

    /** Admin push delivery: 10/min per IP and per admin UID. Preview uses CASHIER_ADMIN. */
    public static final RateLimitPolicy PUSH_SEND = new RateLimitPolicy(
            perMinute(10),
            perMinute(10)
    );

    private static Bandwidth perMinute(long count) {
        return Bandwidth.builder().capacity(count).refillGreedy(count, Duration.ofMinutes(1)).build();
    }
}
