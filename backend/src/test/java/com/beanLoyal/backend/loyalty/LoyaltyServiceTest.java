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
}
