package com.beanLoyal.backend.push;

import java.util.List;

/**
 * Audience criteria for an admin push campaign.
 * <p>
 * Different dimensions are combined with AND semantics; values inside one list (for example
 * {@code genders} or {@code interests}) use OR semantics. {@code lastVisitWithinDays} targets
 * recent visitors, while {@code lastVisitBeforeDays} targets lapsed or never-visited members.
 * When {@code sendToAll} is true every other field is ignored.
 *
 * @param sendToAll             bypass all segmentation when true.
 * @param minAge               inclusive minimum age derived from {@code users.birthday}.
 * @param maxAge               inclusive maximum age derived from {@code users.birthday}.
 * @param genders              accepted profile gender values, matched case-insensitively.
 * @param locations            address/neighborhood fragments; {@code other} matches addresses
 *                             outside the configured known locations.
 * @param interests            top behavioral menu interests, such as coffee or pastries.
 * @param lastVisitWithinDays  require {@code lastEarnAt} within this many days.
 * @param lastVisitBeforeDays  require {@code lastEarnAt} older than this many days; members who
 *                             have never earned also match.
 * @param birthdayToday        require the member's birthday to fall today.
 */
public record PushAudienceFilter(Boolean sendToAll,
                                 Integer minAge,
                                 Integer maxAge,
                                 List<String> genders,
                                 List<String> locations,
                                 List<String> interests,
                                 Integer lastVisitWithinDays,
                                 Integer lastVisitBeforeDays,
                                 Boolean birthdayToday) {
}
