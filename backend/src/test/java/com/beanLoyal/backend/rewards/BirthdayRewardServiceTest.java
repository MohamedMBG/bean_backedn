package com.beanLoyal.backend.rewards;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link BirthdayRewardService#isBirthdayMatch}, the pure month/day comparison BUSINESS_RULES
 * §3.7 depends on. The Firestore transaction path (claim) needs the emulator and is exercised by
 * integration tests later.
 */
class BirthdayRewardServiceTest {

    @Test
    void matchesOnExactMonthAndDay() {
        assertTrue(BirthdayRewardService.isBirthdayMatch(
                LocalDate.of(2026, 7, 2), LocalDate.of(1995, 7, 2)));
    }

    @Test
    void rejectsDifferentDay() {
        assertFalse(BirthdayRewardService.isBirthdayMatch(
                LocalDate.of(2026, 7, 3), LocalDate.of(1995, 7, 2)));
    }

    @Test
    void feb29BirthdayMatchesFeb28InNonLeapYear() {
        // §3.7 accepted edge case: Feb-29 birthday, non-leap year → Feb 28 counts as the birthday.
        assertTrue(BirthdayRewardService.isBirthdayMatch(
                LocalDate.of(2027, 2, 28), LocalDate.of(2000, 2, 29)));
    }

    @Test
    void feb29BirthdayMatchesFeb29InLeapYear() {
        assertTrue(BirthdayRewardService.isBirthdayMatch(
                LocalDate.of(2028, 2, 29), LocalDate.of(2000, 2, 29)));
    }

    @Test
    void feb29BirthdayDoesNotMatchFeb27InNonLeapYear() {
        assertFalse(BirthdayRewardService.isBirthdayMatch(
                LocalDate.of(2027, 2, 27), LocalDate.of(2000, 2, 29)));
    }
}
