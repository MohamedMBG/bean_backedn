package com.beanLoyal.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Exposes a single {@link Clock} bean so services that need "now" for cooldown/expiry checks
 * (e.g. {@link com.beanLoyal.backend.loyalty.LoyaltyService}) inject it instead of calling
 * {@code Instant.now()} directly. Keeps time-dependent business rules deterministic and lets
 * tests substitute {@code Clock.fixed(...)} without touching the system clock.
 */
@Configuration
public class ClockConfig {

    /**
     * @return a UTC system clock; every timestamp stored in Firestore by this backend is already
     *         UTC-based (see {@code IdempotencyService}, {@code EarnCodeService}).
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
