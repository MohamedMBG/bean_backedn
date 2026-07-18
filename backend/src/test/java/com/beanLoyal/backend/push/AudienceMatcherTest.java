package com.beanLoyal.backend.push;

import com.beanLoyal.backend.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudienceMatcherTest {

    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    @Test
    void combinesDimensionsWithAndAndValuesWithinDimensionWithOr() {
        AudienceProfile matching = new AudienceProfile(
                "user-1", "Female", "1996-07-18", "Agdal, Rabat",
                NOW.minusSeconds(2 * 86_400L), "Coffee");
        PushAudienceFilter filter = new PushAudienceFilter(
                false, 25, 35, List.of("female", "other"), List.of("hassan", "agdal"),
                List.of("coffee", "tea"), 7, null, true);

        assertTrue(AudienceMatcher.matches(matching, filter, NOW));
        assertFalse(AudienceMatcher.matches(
                new AudienceProfile("user-2", "Female", "1996-07-18", "Agdal, Rabat",
                        NOW.minusSeconds(2 * 86_400L), "Lunch"),
                filter, NOW));
    }

    @Test
    void ageBoundsAreInclusiveAndMissingBirthdayDoesNotMatch() {
        PushAudienceFilter exactlyThirty = new PushAudienceFilter(
                false, 30, 30, null, null, null, null, null, null);

        assertTrue(AudienceMatcher.matches(
                new AudienceProfile("user-1", null, "1996-07-18", null, null, null),
                exactlyThirty, NOW));
        assertFalse(AudienceMatcher.matches(
                new AudienceProfile("user-2", null, null, null, null, null),
                exactlyThirty, NOW));
    }

    @Test
    void february29BirthdayMatchesFebruary28InNonLeapYear() {
        Instant nonLeapBirthday = Instant.parse("2025-02-28T12:00:00Z");
        PushAudienceFilter birthdayToday = new PushAudienceFilter(
                false, null, null, null, null, null, null, null, true);

        assertTrue(AudienceMatcher.matches(
                new AudienceProfile("user-1", null, "2000-02-29", null, null, null),
                birthdayToday, nonLeapBirthday));
    }

    @Test
    void birthdayUsesMoroccoBusinessDateNearUtcMidnight() {
        Instant localJuly18 = Instant.parse("2026-07-17T23:30:00Z");
        PushAudienceFilter birthdayToday = new PushAudienceFilter(
                false, null, null, null, null, null, null, null, true);

        assertTrue(AudienceMatcher.matches(
                new AudienceProfile("user-1", null, "1996-07-18", null, null, null),
                birthdayToday, localJuly18));
    }

    @Test
    void recentAndLapsedRulesHandleBoundaryAndNeverVisitedUsers() {
        PushAudienceFilter recent = new PushAudienceFilter(
                false, null, null, null, null, null, 7, null, null);
        PushAudienceFilter lapsed = new PushAudienceFilter(
                false, null, null, null, null, null, null, 30, null);

        assertTrue(AudienceMatcher.matches(profileWithVisit(NOW.minusSeconds(7 * 86_400L)), recent, NOW));
        assertFalse(AudienceMatcher.matches(profileWithVisit(NOW.minusSeconds(7 * 86_400L + 1)), recent, NOW));
        assertTrue(AudienceMatcher.matches(profileWithVisit(NOW.minusSeconds(30 * 86_400L + 1)), lapsed, NOW));
        assertFalse(AudienceMatcher.matches(profileWithVisit(NOW.minusSeconds(30 * 86_400L)), lapsed, NOW));
        assertTrue(AudienceMatcher.matches(profileWithVisit(null), lapsed, NOW));
    }

    @Test
    void otherLocationMatchesOnlyAddressesOutsideKnownNeighborhoods() {
        PushAudienceFilter other = new PushAudienceFilter(
                false, null, null, null, List.of("other"), null, null, null, null);

        assertTrue(AudienceMatcher.matches(
                new AudienceProfile("user-1", null, null, "Hay Riad, Rabat", null, null),
                other, NOW));
        assertFalse(AudienceMatcher.matches(
                new AudienceProfile("user-2", null, null, "Agdal, Rabat", null, null),
                other, NOW));
    }

    @Test
    void rejectsInvalidOrContradictoryRanges() {
        assertThrows(ApiException.class, () -> AudienceMatcher.validate(new PushAudienceFilter(
                false, 40, 20, null, null, null, null, null, null)));
        assertThrows(ApiException.class, () -> AudienceMatcher.validate(new PushAudienceFilter(
                false, null, null, null, null, null, 30, 30, null)));
    }

    private static AudienceProfile profileWithVisit(Instant lastVisit) {
        return new AudienceProfile("user-1", null, null, null, lastVisit, null);
    }
}
