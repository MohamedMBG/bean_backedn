package com.beanLoyal.backend.admin;

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

/**
 * Aggregates dashboard metrics over a time window from the backend-owned {@code earn_codes},
 * {@code redeem_codes}, and {@code users} collections (§10). Reads the raw docs in-range and folds
 * them in Java — Firestore can't group-by, and the admin app can no longer read these collections
 * directly. Revenue is booked at earn-code creation; gifts/points-redeemed at redeem completion.
 * <p>
 * ponytail: reads every in-range doc (same shape as the old client dashboard did). Fine for a
 * day/week/month window at MVP volume; add server-side pre-aggregation if a window ever spans a
 * very large code count.
 * <p>
 * The four Firestore reads (earn window, redeem window, scan window, new-client count) are mutually
 * independent, so they are dispatched together and awaited afterwards — the round-trips overlap and
 * endpoint latency is the slowest single query rather than the sum of all four.
 */
@Service
public class AnalyticsService {

    private static final String USERS = "users";
    private static final String CREATED_AT = "createdAt";

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
                        .get();
        com.google.api.core.ApiFuture<com.google.cloud.firestore.QuerySnapshot> redeemFuture =
                firestore.collection(RedeemCode.COLLECTION)
                        .whereGreaterThanOrEqualTo(RedeemCode.TERMINAL_AT, from)
                        .whereLessThan(RedeemCode.TERMINAL_AT, to)
                        .get();
        com.google.api.core.ApiFuture<com.google.cloud.firestore.QuerySnapshot> visitorFuture =
                firestore.collection(EarnCodeService.COLLECTION)
                        .whereGreaterThanOrEqualTo(EarnCodeService.REDEEMED_AT, from)
                        .whereLessThan(EarnCodeService.REDEEMED_AT, to)
                        .get();
        com.google.api.core.ApiFuture<AggregateQuerySnapshot> newClientsFuture =
                firestore.collection(USERS)
                        .whereGreaterThanOrEqualTo(CREATED_AT, from)
                        .whereLessThan(CREATED_AT, to)
                        .count().get();

        // Earn codes created in the window → revenue, points issued, per-cashier issuance, day series.
        for (QueryDocumentSnapshot doc : earnFuture.get().getDocuments()) {
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
        for (QueryDocumentSnapshot doc : redeemFuture.get().getDocuments()) {
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
        for (QueryDocumentSnapshot doc : visitorFuture.get().getDocuments()) {
            String scanner = doc.getString(EarnCodeService.REDEEMED_BY);
            if (scanner != null) visitors.add(scanner);
        }

        // New clients: an aggregate count, no per-doc read needed.
        long newClients = newClientsFuture.get().getCount();

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
