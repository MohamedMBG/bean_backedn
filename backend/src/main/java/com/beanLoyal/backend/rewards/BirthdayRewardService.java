package com.beanLoyal.backend.rewards;

import com.beanLoyal.backend.activity.ActivityService;
import com.beanLoyal.backend.common.ApiException;
import com.beanLoyal.backend.common.ApiResponse;
import com.beanLoyal.backend.common.IdempotencyService;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Grants the once-per-calendar-year birthday points reward per {@code docs/BUSINESS_RULES.md §3.7}.
 * <p>
 * Invoked as {@code IdempotencyService.TransactionalWork} from {@link RewardsController} — every
 * read and write here runs inside the caller-supplied Firestore {@link Transaction}, sharing the
 * same commit as the idempotency record write, so a retried claim can never double-grant points
 * even before the {@code birthday_claims} marker is consulted.
 *
 * <h2>Schema assumptions (undocumented elsewhere at time of writing)</h2>
 * <ul>
 *   <li>{@code users/{uid}.birthday} — ISO-8601 date string {@code yyyy-MM-dd}. Chosen because
 *       §3.7's Feb-29 edge case ("server treats Feb 28 as their birthday in non-leap years") only
 *       makes sense against a month/day comparison, and {@code yyyy-MM-dd} is the standard
 *       Android date-picker / {@code LocalDate} wire format. Revisit if the Android client uses a
 *       different representation.</li>
 *   <li>{@code users/{uid}.points} — integer point balance, backend-owned (matches §2.1's points model).</li>
 *   <li>{@code users/{uid}} is assumed to already exist for any authenticated caller (created at
 *       account signup, out of this backend's scope). If it does not, the transaction's
 *       {@code update} call fails at commit and the request surfaces as a generic 500 — no
 *       business rule currently defines a "no profile" response for this endpoint.</li>
 * </ul>
 */
@Service
public class BirthdayRewardService {

    /**
     * Fixed points granted per birthday claim. Locked as an MVP default (owner decision,
     * 2026-07-02) — see {@code docs/BUSINESS_RULES.md §3.7}. Amend there and here together if
     * the owner changes the value.
     */
    static final long BIRTHDAY_REWARD_POINTS = 50L;

    private final Firestore firestore;
    private final ActivityService activityService;

    public BirthdayRewardService(Firestore firestore, ActivityService activityService) {
        this.firestore = firestore;
        this.activityService = activityService;
    }

    /**
     * Validate and grant the birthday reward for {@code uid} inside {@code transaction}.
     * <p>
     * Order of checks matches the §2.8/§3.9-style error tables: missing/unparseable birthday →
     * {@code BIRTHDAY_NOT_SET} (422), birthday isn't today → {@code BIRTHDAY_NOT_TODAY} (422),
     * already claimed this calendar year (UTC) → {@code BIRTHDAY_ALREADY_CLAIMED} (409). All
     * reads (user doc, claim doc) happen before any write, satisfying Firestore's
     * read-before-write transaction rule.
     *
     * @param transaction live Firestore transaction supplied by {@code IdempotencyService}; every
     *                    Firestore access in this method MUST use it, never a nested transaction.
     * @param uid         verified Firebase UID of the caller (never trust a client-supplied id).
     * @return {@link IdempotencyService.BusinessOutcome} wrapping {@code 200} + {@link ApiResponse}
     *         of {@link BirthdayClaimResponse} on success.
     * @throws ApiException         on any business-rule rejection (see above); aborts the transaction
     *                              before any write is staged.
     * @throws ExecutionException   propagated from the underlying Firestore {@code get()} calls.
     * @throws InterruptedException propagated from the underlying Firestore {@code get()} calls.
     */
    public IdempotencyService.BusinessOutcome claim(Transaction transaction, String uid)
            throws ExecutionException, InterruptedException {

        DocumentReference userRef = firestore.collection("users").document(uid);
        DocumentSnapshot userSnap = transaction.get(userRef).get();

        LocalDate birthday = parseBirthday(userSnap);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        if (!isBirthdayMatch(today, birthday)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BIRTHDAY_NOT_TODAY", "Today is not your birthday");
        }

        int year = today.getYear();
        DocumentReference claimRef = firestore.collection("birthday_claims").document(uid + "_" + year);
        DocumentSnapshot claimSnap = transaction.get(claimRef).get();
        if (claimSnap.exists()) {
            throw new ApiException(HttpStatus.CONFLICT, "BIRTHDAY_ALREADY_CLAIMED", "Birthday reward already claimed this year");
        }

        Long existingPoints = userSnap.getLong("points");
        long totalPoints = (existingPoints == null ? 0L : existingPoints) + BIRTHDAY_REWARD_POINTS;

        Map<String, Object> claimDoc = new LinkedHashMap<>();
        claimDoc.put("uid", uid);
        claimDoc.put("year", year);
        claimDoc.put("pointsGranted", BIRTHDAY_REWARD_POINTS);
        claimDoc.put("claimedAt", FieldValue.serverTimestamp());
        transaction.set(claimRef, claimDoc);
        transaction.update(userRef, "points", totalPoints);
        // Canonical activity feed entry (§11); refId ties back to the birthday_claims doc.
        activityService.record(transaction, uid, ActivityService.TYPE_BIRTHDAY,
                BIRTHDAY_REWARD_POINTS, uid + "_" + year, totalPoints);

        BirthdayClaimResponse body = new BirthdayClaimResponse(BIRTHDAY_REWARD_POINTS, totalPoints, year);
        return new IdempotencyService.BusinessOutcome(HttpStatus.OK, ApiResponse.of(body));
    }

    /**
     * Read and parse {@code users/{uid}.birthday}. Missing doc, missing field, blank value, or a
     * value that fails ISO-8601 parsing are all treated as "not set" — the client-facing signal is
     * identical ({@code BIRTHDAY_NOT_SET}) either way; only the server log distinguishes them.
     */
    private LocalDate parseBirthday(DocumentSnapshot userSnap) {
        String raw = userSnap.exists() ? userSnap.getString("birthday") : null;
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BIRTHDAY_NOT_SET", "Birthday not set on profile");
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BIRTHDAY_NOT_SET", "Birthday value is invalid");
        }
    }

    /**
     * True if {@code today} is the caller's birthday, comparing month + day only (year on the
     * stored birthday is the birth year, not relevant to the match).
     * <p>
     * Implements the §3.7 leap-year accepted edge case verbatim: a user born Feb 29 is treated as
     * having their birthday on Feb 28 in a non-leap year, so they still get exactly one claim
     * window per calendar year instead of skipping every non-leap year entirely.
     * <p>
     * Package-private + static: pure function, unit-tested directly without a Firestore mock.
     */
    static boolean isBirthdayMatch(LocalDate today, LocalDate birthday) {
        if (today.getMonthValue() == birthday.getMonthValue() && today.getDayOfMonth() == birthday.getDayOfMonth()) {
            return true;
        }
        return birthday.getMonthValue() == 2 && birthday.getDayOfMonth() == 29
                && today.getMonthValue() == 2 && today.getDayOfMonth() == 28
                && !Year.isLeap(today.getYear());
    }
}
