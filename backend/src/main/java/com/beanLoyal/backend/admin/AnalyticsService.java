package com.beanLoyal.backend.admin;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.AggregateQuerySnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 */
@Service
public class AnalyticsService {

    private final Firestore firestore;

    public AnalyticsService(Firestore firestore) {
        this.firestore = firestore;
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

        // Earn codes created in the window → revenue, points issued, per-cashier issuance, day series.
        for (QueryDocumentSnapshot doc : firestore.collection("earn_codes")
                .whereGreaterThanOrEqualTo("createdAt", from)
                .whereLessThan("createdAt", to)
                .get().get().getDocuments()) {
            double amount = orZeroD(doc.getDouble("amountMAD"));
            long points = orZero(doc.getLong("points"));
            revenue += amount;
            pointsIssued += points;

            String cashier = doc.getString("createdBy");
            if (cashier != null) {
                Acc a = byCashier.computeIfAbsent(cashier, k -> new Acc());
                a.codesIssued++;
                a.revenue += amount;
            }

            Timestamp createdAt = doc.getTimestamp("createdAt");
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
        for (QueryDocumentSnapshot doc : firestore.collection("redeem_codes")
                .whereGreaterThanOrEqualTo("terminalAt", from)
                .whereLessThan("terminalAt", to)
                .get().get().getDocuments()) {
            if (!"completed".equals(doc.getString("status"))) continue;
            gifts++;
            pointsRedeemed += orZero(doc.getLong("cost"));
            String cashier = doc.getString("completedByUid");
            if (cashier != null) {
                byCashier.computeIfAbsent(cashier, k -> new Acc()).redeemsCompleted++;
            }
        }

        // New clients: an aggregate count, no per-doc read needed.
        AggregateQuerySnapshot newClientsSnap = firestore.collection("users")
                .whereGreaterThanOrEqualTo("createdAt", from)
                .whereLessThan("createdAt", to)
                .count().get().get();
        long newClients = newClientsSnap.getCount();

        return new AnalyticsResponse(revenue, pointsIssued, pointsRedeemed, gifts, newClients,
                buildCashierStats(byCashier), buildSeries(byDay));
    }

    private List<AnalyticsResponse.CashierStat> buildCashierStats(Map<String, Acc> byCashier)
            throws ExecutionException, InterruptedException {
        List<AnalyticsResponse.CashierStat> out = new ArrayList<>();
        for (Map.Entry<String, Acc> e : byCashier.entrySet()) {
            Acc a = e.getValue();
            out.add(new AnalyticsResponse.CashierStat(e.getKey(), resolveName(e.getKey()),
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

    /** Resolve a cashier's display name; falls back to the uid if the doc/name is missing. */
    private String resolveName(String uid) throws ExecutionException, InterruptedException {
        String name = firestore.collection("users").document(uid).get().get().getString("name");
        return (name == null || name.isBlank()) ? uid : name;
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
