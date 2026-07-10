package com.beanLoyal.backend.admin;

import java.util.List;

/**
 * Response for {@code GET /api/v1/admin/analytics}: aggregated dashboard metrics over a time window.
 * Wrapped in {@link com.beanLoyal.backend.common.ApiResponse}. Admins cannot read the
 * {@code earn_codes}/{@code redeem_codes} collections directly (Firestore rules), so the dashboard
 * reads these aggregates through the backend.
 *
 * @param revenue        sum of {@code earn_codes.amountMAD} for codes created in the window (the sale
 *                       is booked at code creation).
 * @param pointsIssued   sum of {@code earn_codes.points} for codes created in the window.
 * @param pointsRedeemed sum of {@code redeem_codes.cost} for redeems completed in the window.
 * @param gifts          count of redeems completed in the window.
 * @param newClients     count of {@code users} created in the window.
 * @param uniqueVisitors distinct customers who scanned an earn code in the window.
 * @param cashiers       per-cashier breakdown (issuers of earn codes and/or completers of redeems).
 * @param series         per-UTC-day buckets over the window, for the dashboard chart.
 */
public record AnalyticsResponse(double revenue, long pointsIssued, long pointsRedeemed, long gifts,
                                long newClients, long uniqueVisitors, List<CashierStat> cashiers,
                                List<DayBucket> series) {

    /**
     * @param cashierUid       the cashier's uid.
     * @param cashierName       display name (from {@code users/{uid}.name}), or the uid if unknown.
     * @param codesIssued      earn codes this cashier created in the window.
     * @param revenue          sum of {@code amountMAD} on those codes.
     * @param redeemsCompleted redeems this cashier completed in the window.
     */
    public record CashierStat(String cashierUid, String cashierName, long codesIssued, double revenue,
                              long redeemsCompleted) {
    }

    /**
     * @param dayStartEpochMs UTC midnight of the day.
     * @param earnCount       earn codes created that day.
     * @param revenue         sum of {@code amountMAD} that day.
     */
    public record DayBucket(long dayStartEpochMs, long earnCount, double revenue) {
    }
}
