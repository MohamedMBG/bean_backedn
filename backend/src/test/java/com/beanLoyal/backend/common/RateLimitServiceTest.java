package com.beanLoyal.backend.common;

import io.github.bucket4j.Bandwidth;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Unit tests for {@link RateLimitService}. Uses a purpose-built policy with tiny capacity
 * (2 tokens, 2/min refill) so the third call must throw without needing real-time waits.
 */
class RateLimitServiceTest {

    private static final RateLimitPolicy TINY = new RateLimitPolicy(
            Bandwidth.builder().capacity(2).refillGreedy(2, Duration.ofMinutes(1)).build(),
            Bandwidth.builder().capacity(2).refillGreedy(2, Duration.ofMinutes(1)).build()
    );

    @Test
    void ipBucket_exhaustsAfterCapacity() {
        RateLimitService svc = new RateLimitService();

        svc.check(TINY, "1.1.1.1", null);
        svc.check(TINY, "1.1.1.1", null);

        RateLimitException ex = catchThrowableOfType(
                () -> svc.check(TINY, "1.1.1.1", null),
                RateLimitException.class
        );
        assertThat(ex).isNotNull();
        assertThat(ex.getRetryAfterSeconds()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void distinctIps_haveIndependentBuckets() {
        RateLimitService svc = new RateLimitService();

        svc.check(TINY, "1.1.1.1", null);
        svc.check(TINY, "1.1.1.1", null);
        // 2.2.2.2 has its own bucket — should still have 2 tokens
        svc.check(TINY, "2.2.2.2", null);
        svc.check(TINY, "2.2.2.2", null);

        assertThatThrownBy(() -> svc.check(TINY, "1.1.1.1", null))
                .isInstanceOf(RateLimitException.class);
        assertThatThrownBy(() -> svc.check(TINY, "2.2.2.2", null))
                .isInstanceOf(RateLimitException.class);
    }

    @Test
    void uidBucket_exhaustsIndependentlyFromIp() {
        RateLimitService svc = new RateLimitService();

        // Same UID on two different IPs → UID bucket burns down first.
        svc.check(TINY, "1.1.1.1", "user-a");
        svc.check(TINY, "2.2.2.2", "user-a");

        assertThatThrownBy(() -> svc.check(TINY, "3.3.3.3", "user-a"))
                .isInstanceOf(RateLimitException.class);
    }

    @Test
    void differentPolicies_haveIndependentBucketsForSameSubject() {
        RateLimitService svc = new RateLimitService();
        RateLimitPolicy other = new RateLimitPolicy(
                Bandwidth.builder().capacity(2).refillGreedy(2, Duration.ofMinutes(1)).build(),
                Bandwidth.builder().capacity(2).refillGreedy(2, Duration.ofMinutes(1)).build()
        );

        svc.check(TINY, "1.1.1.1", "user-a");
        svc.check(TINY, "1.1.1.1", "user-a");
        // Policy `other` has distinct Bandwidth instances → distinct bucket key → still has tokens
        svc.check(other, "1.1.1.1", "user-a");
        svc.check(other, "1.1.1.1", "user-a");

        assertThatThrownBy(() -> svc.check(TINY, "1.1.1.1", "user-a"))
                .isInstanceOf(RateLimitException.class);
        assertThatThrownBy(() -> svc.check(other, "1.1.1.1", "user-a"))
                .isInstanceOf(RateLimitException.class);
    }
}
