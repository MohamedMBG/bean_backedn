package com.beanLoyal.backend.admin;

import com.beanLoyal.backend.common.ApiException;
import com.beanLoyal.backend.loyalty.EarnCodeService;
import com.beanLoyal.backend.rewards.RedeemCode;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.AggregateQuerySnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;

/**
 * Aggregates dashboard metrics over a time window from the backend-owned {@code earn_codes},
 * {@code redeem_codes}, and {@code users} collections (§10). Reads the raw docs in-range and folds
 * them in Java — Firestore can't group-by, and the admin app can no longer read these collections
 * directly. Revenue is booked at earn-code creation; gifts/points-redeemed at redeem completion.
 * <p>
 * Each raw-event query is capped at {@link #MAX_EVENTS_PER_QUERY} plus one sentinel document. If
 * the cap is exceeded, the request fails rather than returning incomplete metrics. Requests are
 * also limited to {@link #MAX_WINDOW_DAYS} days. Add write-time daily rollups before increasing
 * either limit for a higher-volume deployment.
 * <p>
 * The four Firestore reads (earn window, redeem window, scan window, new-client count) are mutually
 * independent, so they are dispatched together and awaited afterwards — the round-trips overlap and
 * endpoint latency is the slowest single query rather than the sum of all four.
 */
@Service
public class AnalyticsService {

    private static final String USERS = "users";
    private static final String CREATED_AT = "createdAt";
    static final int MAX_WINDOW_DAYS = 31;
    static final int MAX_EVENTS_PER_QUERY = 10_000;

    private final Firestore firestore;
    private final UserNameResolver nameResolver;

    public AnalyticsService(Firestore firestore, UserNameResolver nameResolver) {
        this.firestore = firestore;
        this.nameResolver = nameResolver;
    }

    /** Mutable per-cashier accumulator. */
    private static final class Acc {
        long codesIssued;
        double revenue;
        long redeemsCompleted;
    }

    /** Mutable per-day accumulator. */
    private static final class DayAcc {
        long earnCount;
        double revenue;
    }

    public AnalyticsResponse compute(long fromEpochMs, long toEpochMs)
            throws ExecutionException, InterruptedException {
        validateRange(fromEpochMs, toEpochMs);
        Timestamp from = toTimestamp(fromEpochMs);
        Timestamp to = toTimestamp(toEpochMs);

        double revenue = 0;
        long pointsIssued = 0;
        Map<String, Acc> byCashier = new LinkedHashMap<>();
        Map<Long, DayAcc> byDay = new LinkedHashMap<>();

        // The four dashboard reads are independent, so fire all of them before awaiting any — the
        // Firestore round-trips overlap and endpoint latency becomes the slowest single query, not
        // the sum. Awaited below via .get() in the same order.
        com.google.api.core.ApiFuture<com.google.cloud.firestore.QuerySnapshot> earnFuture =
                firestore.collection(EarnCodeService.COLLECTION)
                        .whereGreaterThanOrEqualTo(EarnCodeService.CREATED_AT, from)
                        .whereLessThan(EarnCodeService.CREATED_AT, to)
                        .limit(MAX_EVENTS_PER_QUERY + 1)
                        .get();
        com.google.api.core.ApiFuture<com.google.cloud.firestore.QuerySnapshot> redeemFuture =
                firestore.collection(RedeemCode.COLLECTION)
                        .whereGreaterThanOrEqualTo(RedeemCode.TERMINAL_AT, from)
                        .whereLessThan(RedeemCode.TERMINAL_AT, to)
                        .limit(MAX_EVENTS_PER_QUERY + 1)
                        .get();
        com.google.api.core.ApiFuture<com.google.cloud.firestore.QuerySnapshot> visitorFuture =
                firestore.collection(EarnCodeService.COLLECTION)
                        .whereGreaterThanOrEqualTo(EarnCodeService.REDEEMED_AT, from)
                        .whereLessThan(EarnCodeService.REDEEMED_AT, to)
                        .limit(MAX_EVENTS_PER_QUERY + 1)
                        .get();
        com.google.api.core.ApiFuture<AggregateQuerySnapshot> newClientsFuture =
                firestore.collection(USERS)
                        .whereGreaterThanOrEqualTo(CREATED_AT, from)
                        .whereLessThan(CREATED_AT, to)
                        .limit(MAX_EVENTS_PER_QUERY + 1)
                        .count().get();

        List<QueryDocumentSnapshot> earnDocuments = requireWithinBound(earnFuture.get(), "earn codes");
        List<QueryDocumentSnapshot> redeemDocuments = requireWithinBound(redeemFuture.get(), "redeem codes");
        List<QueryDocumentSnapshot> visitorDocuments = requireWithinBound(visitorFuture.get(), "earn-code scans");
        long newClients = requireWithinBound(newClientsFuture.get().getCount(), "new clients");

        // Earn codes created in the window → revenue, points issued, per-cashier issuance, day series.
        for (QueryDocumentSnapshot doc : earnDocuments) {
            double amount = orZeroD(doc.getDouble(EarnCodeService.AMOUNT_MAD));
            long points = orZero(doc.getLong(EarnCodeService.POINTS));
            revenue += amount;
            pointsIssued += points;

            String cashier = doc.getString(EarnCodeService.CREATED_BY);
            if (cashier != null) {
                Acc a = byCashier.computeIfAbsent(cashier, k -> new Acc());
                a.codesIssued++;
                a.revenue += amount;
            }

            Timestamp createdAt = doc.getTimestamp(EarnCodeService.CREATED_AT);
            if (createdAt != null) {
                DayAcc d = byDay.computeIfAbsent(floorToUtcDay(createdAt.toDate().getTime()), k -> new DayAcc());
                d.earnCount++;
                d.revenue += amount;
            }
        }

        // Redeems completed in the window → gifts, points redeemed, per-cashier completions.
        // Query by terminalAt range and filter status in-code to avoid a (status, terminalAt) index.
        long pointsRedeemed = 0;
        long gifts = 0;
        for (QueryDocumentSnapshot doc : redeemDocuments) {
            if (!RedeemCode.STATUS_COMPLETED.equals(doc.getString(RedeemCode.STATUS))) continue;
            gifts++;
            pointsRedeemed += orZero(doc.getLong(RedeemCode.COST));
            String cashier = doc.getString(RedeemCode.COMPLETED_BY);
            if (cashier != null) {
                byCashier.computeIfAbsent(cashier, k -> new Acc()).redeemsCompleted++;
            }
        }

        // Unique visitors: distinct customers who SCANNED a code in the window (redeemedAt), which is
        // a different event than code creation, so it needs its own query on the scan timestamp.
        Set<String> visitors = new java.util.HashSet<>();
        for (QueryDocumentSnapshot doc : visitorDocuments) {
            String scanner = doc.getString(EarnCodeService.REDEEMED_BY);
            if (scanner != null) visitors.add(scanner);
        }

        Map<String, String> cashierNames = nameResolver.resolve(byCashier.keySet());
        return new AnalyticsResponse(revenue, pointsIssued, pointsRedeemed, gifts, newClients,
                visitors.size(), buildCashierStats(byCashier, cashierNames), buildSeries(byDay));
    }

    private static List<AnalyticsResponse.CashierStat> buildCashierStats(Map<String, Acc> byCashier,
                                                                         Map<String, String> names) {
        List<AnalyticsResponse.CashierStat> out = new ArrayList<>();
        for (Map.Entry<String, Acc> e : byCashier.entrySet()) {
            Acc a = e.getValue();
            out.add(new AnalyticsResponse.CashierStat(e.getKey(), names.getOrDefault(e.getKey(), e.getKey()),
                    a.codesIssued, a.revenue, a.redeemsCompleted));
        }
        out.sort((x, y) -> Long.compare(y.codesIssued() + y.redeemsCompleted(),
                x.codesIssued() + x.redeemsCompleted()));
        return out;
    }

    private static List<AnalyticsResponse.DayBucket> buildSeries(Map<Long, DayAcc> byDay) {
        List<AnalyticsResponse.DayBucket> out = new ArrayList<>();
        for (Map.Entry<Long, DayAcc> e : byDay.entrySet()) {
            out.add(new AnalyticsResponse.DayBucket(e.getKey(), e.getValue().earnCount, e.getValue().revenue));
        }
        out.sort((x, y) -> Long.compare(x.dayStartEpochMs(), y.dayStartEpochMs()));
        return out;
    }

    /** UTC midnight (epoch ms) of the day containing {@code epochMs}. Pure — unit-tested. */
    static long floorToUtcDay(long epochMs) {
        long day = 86_400_000L;
        return Math.floorDiv(epochMs, day) * day;
    }

    /** Rejects invalid or too-large dashboard windows before Firestore work is dispatched. */
    static void validateRange(long fromEpochMs, long toEpochMs) {
        if (fromEpochMs >= toEpochMs) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "from must be before to");
        }
        long windowMillis;
        try {
            windowMillis = Math.subtractExact(toEpochMs, fromEpochMs);
        } catch (ArithmeticException ignored) {
            throw analyticsRangeTooLarge("events");
        }
        if (windowMillis > TimeUnit.DAYS.toMillis(MAX_WINDOW_DAYS)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ANALYTICS_RANGE_TOO_LARGE",
                    "Analytics requests are limited to " + MAX_WINDOW_DAYS + " days");
        }
    }

    private static List<QueryDocumentSnapshot> requireWithinBound(
            com.google.cloud.firestore.QuerySnapshot snapshot, String metric) {
        if (snapshot.size() > MAX_EVENTS_PER_QUERY) {
            throw analyticsRangeTooLarge(metric);
        }
        return snapshot.getDocuments();
    }

    private static long requireWithinBound(long count, String metric) {
        if (count > MAX_EVENTS_PER_QUERY) {
            throw analyticsRangeTooLarge(metric);
        }
        return count;
    }

    private static ApiException analyticsRangeTooLarge(String metric) {
        return new ApiException(HttpStatus.BAD_REQUEST, "ANALYTICS_RANGE_TOO_LARGE",
                "Analytics range contains too many " + metric + "; use a shorter range");
    }

    private static Timestamp toTimestamp(long epochMs) {
        return Timestamp.ofTimeSecondsAndNanos(Math.floorDiv(epochMs, 1000L),
                (int) (Math.floorMod(epochMs, 1000L) * 1_000_000L));
    }

    private static long orZero(Long v) {
        return v == null ? 0L : v;
    }

    private static double orZeroD(Double v) {
        return v == null ? 0.0 : v;
    }
}
