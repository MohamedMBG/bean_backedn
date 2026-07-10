package com.beanLoyal.backend.admin;

import com.beanLoyal.backend.loyalty.EarnCodeService;
import com.beanLoyal.backend.rewards.RedeemCode;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Backs the admin scan-log + reward-log screens (§10): most-recent earn/redeem codes with cashier
 * and customer names resolved. Admins can no longer read {@code earn_codes}/{@code redeem_codes}
 * directly (Firestore rules), and these are per-row lists (not the aggregates {@link AnalyticsService}
 * serves), so they need their own endpoints.
 * <p>
 * ponytail: limit-only (most-recent N), no pagination cursor yet — matches the old client screens'
 * "recent 100". Add {@code startAfter} paging when a screen needs to scroll deeper.
 */
@Service
public class AdminLogsService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final Firestore firestore;
    private final UserNameResolver nameResolver;

    public AdminLogsService(Firestore firestore, UserNameResolver nameResolver) {
        this.firestore = firestore;
        this.nameResolver = nameResolver;
    }

    /** Most-recent earn codes (newest first) with cashier + customer names resolved. */
    public EarnLogResponse earnLog(int limit) throws ExecutionException, InterruptedException {
        List<QueryDocumentSnapshot> docs = firestore.collection(EarnCodeService.COLLECTION)
                .orderBy(EarnCodeService.CREATED_AT, Query.Direction.DESCENDING)
                .limit(cap(limit))
                .get().get().getDocuments();

        Set<String> uids = new HashSet<>();
        for (QueryDocumentSnapshot d : docs) {
            uids.add(d.getString(EarnCodeService.CREATED_BY));
            uids.add(d.getString(EarnCodeService.REDEEMED_BY));
        }
        Map<String, String> names = nameResolver.resolve(uids);

        List<EarnLogResponse.EarnLogItem> items = new ArrayList<>();
        for (QueryDocumentSnapshot d : docs) {
            String cashierUid = d.getString(EarnCodeService.CREATED_BY);
            String clientUid = d.getString(EarnCodeService.REDEEMED_BY);
            items.add(new EarnLogResponse.EarnLogItem(
                    d.getId(),
                    orZeroD(d.getDouble(EarnCodeService.AMOUNT_MAD)),
                    orZero(d.getLong(EarnCodeService.POINTS)),
                    d.getString(EarnCodeService.STATUS),
                    epochMillis(d.getTimestamp(EarnCodeService.CREATED_AT)),
                    epochMillis(d.getTimestamp(EarnCodeService.REDEEMED_AT)),
                    cashierUid, nameOr(names, cashierUid),
                    clientUid, nameOr(names, clientUid)));
        }
        return new EarnLogResponse(items);
    }

    /** Most-recent redeem codes (newest first) with customer + cashier names resolved. */
    public RedeemLogResponse redeemLog(int limit) throws ExecutionException, InterruptedException {
        List<QueryDocumentSnapshot> docs = firestore.collection(RedeemCode.COLLECTION)
                .orderBy(RedeemCode.CREATED_AT, Query.Direction.DESCENDING)
                .limit(cap(limit))
                .get().get().getDocuments();

        Set<String> uids = new HashSet<>();
        for (QueryDocumentSnapshot d : docs) {
            uids.add(d.getString(RedeemCode.UID));
            uids.add(d.getString(RedeemCode.COMPLETED_BY));
        }
        Map<String, String> names = nameResolver.resolve(uids);

        List<RedeemLogResponse.RedeemLogItem> items = new ArrayList<>();
        for (QueryDocumentSnapshot d : docs) {
            String clientUid = d.getString(RedeemCode.UID);
            String cashierUid = d.getString(RedeemCode.COMPLETED_BY);
            items.add(new RedeemLogResponse.RedeemLogItem(
                    d.getId(),
                    d.getString(RedeemCode.REWARD_NAME),
                    orZero(d.getLong(RedeemCode.COST)),
                    d.getString(RedeemCode.STATUS),
                    epochMillis(d.getTimestamp(RedeemCode.CREATED_AT)),
                    epochMillis(d.getTimestamp(RedeemCode.TERMINAL_AT)),
                    clientUid, nameOr(names, clientUid),
                    cashierUid, nameOr(names, cashierUid)));
        }
        return new RedeemLogResponse(items);
    }

    static int cap(int limit) {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    private static String nameOr(Map<String, String> names, String uid) {
        return uid == null ? null : names.getOrDefault(uid, uid);
    }

    private static long epochMillis(Timestamp t) {
        return t == null ? 0L : t.toDate().getTime();
    }

    private static long orZero(Long v) {
        return v == null ? 0L : v;
    }

    private static double orZeroD(Double v) {
        return v == null ? 0.0 : v;
    }
}
