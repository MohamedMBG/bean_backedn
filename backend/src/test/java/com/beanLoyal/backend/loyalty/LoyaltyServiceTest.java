package com.beanLoyal.backend.loyalty;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link LoyaltyService#isCoolingDown}, the pure cooldown-window check BUSINESS_RULES §2.4
 * depends on. The Firestore transaction path (earn) needs the emulator and is exercised by
 * integration tests later.
 */
class LoyaltyServiceTest {

    @Test
    void withinCooldownWindowBlocks() {
        Instant lastEarnAt = Instant.parse("2026-07-03T10:00:00Z");
        Instant now = lastEarnAt.plusSeconds(60);
        assertTrue(LoyaltyService.isCoolingDown(lastEarnAt, now));
    }

    /**
     * Boundary case: {@code now == lastEarnAt.plus(VISIT_COOLDOWN)} exactly. Documents that the
     * window is exclusive of its end — {@code isCoolingDown} uses {@code now.isBefore(...)}, so
     * the instant the cooldown elapses, the next earn is already allowed rather than blocked for
     * one more instant. Change deliberately (e.g. {@code isBefore} → {@code !isAfter}) if inclusive
     * boundary behavior is ever wanted instead.
     */
    @Test
    void exactlyThirtyMinutesLaterAllows() {
        Instant lastEarnAt = Instant.parse("2026-07-03T10:00:00Z");
        Instant now = lastEarnAt.plus(LoyaltyService.VISIT_COOLDOWN);
        assertFalse(LoyaltyService.isCoolingDown(lastEarnAt, now));
    }

    @Test
    void afterCooldownWindowAllows() {
        Instant lastEarnAt = Instant.parse("2026-07-03T10:00:00Z");
        Instant now = lastEarnAt.plusSeconds(31 * 60);
        assertFalse(LoyaltyService.isCoolingDown(lastEarnAt, now));
    }

    /**
     * Clock skew: {@code lastEarnAt} lies in the future relative to {@code now} (server clock
     * adjusted backwards, or a stale read racing a concurrent earn). Documents current, intended
     * behavior — {@code isCoolingDown} has no special case for this: a future {@code lastEarnAt}
     * is treated as "still cooling down" because {@code now} is always before
     * {@code lastEarnAt.plus(VISIT_COOLDOWN)} in that case. This is the fail-safe direction (blocks
     * an earn rather than allowing an extra one); change deliberately if that stops being desired.
     */
    @Test
    void futureLastEarnAtFromClockSkewIsTreatedAsCoolingDown() {
        Instant now = Instant.parse("2026-07-03T10:00:00Z");
        Instant lastEarnAt = now.plusSeconds(60);
        assertTrue(LoyaltyService.isCoolingDown(lastEarnAt, now));
    }
}
