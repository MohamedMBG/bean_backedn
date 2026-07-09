package com.beanLoyal.backend.admin;

import com.beanLoyal.backend.loyalty.EarnCodeService;
import com.beanLoyal.backend.rewards.RedeemCode;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.AggregateQuerySnapshot;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
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
 */
@Service
public class AnalyticsService {

    private static final String USERS = "users";
    private static final String USER_NAME = "name";
    private static final String CREATED_AT = "createdAt";

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
        for (QueryDocumentSnapshot doc : firestore.collection(EarnCodeService.COLLECTION)
                .whereGreaterThanOrEqualTo(EarnCodeService.CREATED_AT, from)
                .whereLessThan(EarnCodeService.CREATED_AT, to)
                .get().get().getDocuments()) {
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
        for (QueryDocumentSnapshot doc : firestore.collection(RedeemCode.COLLECTION)
                .whereGreaterThanOrEqualTo(RedeemCode.TERMINAL_AT, from)
                .whereLessThan(RedeemCode.TERMINAL_AT, to)
                .get().get().getDocuments()) {
            if (!RedeemCode.STATUS_COMPLETED.equals(doc.getString(RedeemCode.STATUS))) continue;
            gifts++;
            pointsRedeemed += orZero(doc.getLong(RedeemCode.COST));
            String cashier = doc.getString(RedeemCode.COMPLETED_BY);
            if (cashier != null) {
                byCashier.computeIfAbsent(cashier, k -> new Acc()).redeemsCompleted++;
            }
        }

        // New clients: an aggregate count, no per-doc read needed.
        AggregateQuerySnapshot newClientsSnap = firestore.collection(USERS)
                .whereGreaterThanOrEqualTo(CREATED_AT, from)
                .whereLessThan(CREATED_AT, to)
                .count().get().get();
        long newClients = newClientsSnap.getCount();

        Map<String, String> cashierNames = resolveNames(byCashier.keySet());
        return new AnalyticsResponse(revenue, pointsIssued, pointsRedeemed, gifts, newClients,
                buildCashierStats(byCashier, cashierNames), buildSeries(byDay));
    }

    /** Resolve display names for all cashier uids in ONE batched {@code getAll}, uid as fallback. */
    private Map<String, String> resolveNames(Set<String> uids)
            throws ExecutionException, InterruptedException {
        if (uids.isEmpty()) return Map.of();
        DocumentReference[] refs = uids.stream()
                .map(u -> firestore.collection(USERS).document(u))
                .toArray(DocumentReference[]::new);
        Map<String, String> names = new HashMap<>();
        for (DocumentSnapshot snap : firestore.getAll(refs).get()) {
            String name = snap.getString(USER_NAME);
            names.put(snap.getId(), (name == null || name.isBlank()) ? snap.getId() : name);
        }
        return names;
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
