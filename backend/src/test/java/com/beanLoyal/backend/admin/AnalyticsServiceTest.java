package com.beanLoyal.backend.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers {@link AnalyticsService}'s pure UTC-day flooring. The Firestore aggregation path needs the
 * emulator and is deferred to integration tests, same note the other {@code *ServiceTest}s carry.
 */
class AnalyticsServiceTest {

    @Test
    void floorToUtcDayFloorsToMidnight() {
        // 2026-07-09T13:45:00Z = 1783000000000-ish; use exact known values instead.
        long day = 86_400_000L;
        // Midnight stays put.
        assertEquals(0L, AnalyticsService.floorToUtcDay(0L));
        assertEquals(day, AnalyticsService.floorToUtcDay(day));
        // Any time within a day floors to that day's midnight.
        assertEquals(day, AnalyticsService.floorToUtcDay(day + 1));
        assertEquals(day, AnalyticsService.floorToUtcDay(2 * day - 1));
        assertEquals(2 * day, AnalyticsService.floorToUtcDay(2 * day));
    }

    @Test
    void validateRange_acceptsAtMostThirtyOneDays() {
        long day = 86_400_000L;

        AnalyticsService.validateRange(0L, AnalyticsService.MAX_WINDOW_DAYS * day);
        assertThatThrownBy(() -> AnalyticsService.validateRange(0L,
                (AnalyticsService.MAX_WINDOW_DAYS * day) + 1))
                .isInstanceOf(com.beanLoyal.backend.common.ApiException.class);
    }

    @Test
    void validateRange_rejectsReversedOrOverflowingRanges() {
        assertThatThrownBy(() -> AnalyticsService.validateRange(10L, 10L))
                .isInstanceOf(com.beanLoyal.backend.common.ApiException.class);
        assertThatThrownBy(() -> AnalyticsService.validateRange(Long.MIN_VALUE, Long.MAX_VALUE))
                .isInstanceOf(com.beanLoyal.backend.common.ApiException.class);
    }
}
