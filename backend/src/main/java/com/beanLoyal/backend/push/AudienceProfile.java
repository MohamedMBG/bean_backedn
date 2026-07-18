package com.beanLoyal.backend.push;

import java.time.Instant;

/**
 * Minimal user projection used during push segmentation. Keeping the matcher independent of
 * Firestore makes age, birthday, location, interest and recency rules deterministic and testable.
 *
 * @param uid          Firebase user id.
 * @param gender       optional profile gender.
 * @param birthday     optional ISO birthday ({@code yyyy-MM-dd}).
 * @param address      optional free-form address/neighborhood.
 * @param lastEarnAt   last purchase-linked earn time; the system's visit timestamp.
 * @param topInterest  highest-scoring menu category collected from customer behavior.
 */
public record AudienceProfile(String uid,
                              String gender,
                              String birthday,
                              String address,
                              Instant lastEarnAt,
                              String topInterest) {
}
