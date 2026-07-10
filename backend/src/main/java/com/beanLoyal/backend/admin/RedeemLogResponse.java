package com.beanLoyal.backend.admin;

import java.util.List;

/**
 * Response for {@code GET /api/v1/admin/redeem-codes} (the admin reward-log screen): recent redeem
 * codes with customer + cashier names resolved. Wrapped in {@link com.beanLoyal.backend.common.ApiResponse}.
 * Admins cannot read {@code redeem_codes} directly (Firestore rules), so the log reads through here.
 *
 * @param items most-recent-first redeem-code rows (capped in the service).
 */
public record RedeemLogResponse(List<RedeemLogItem> items) {

    /**
     * @param code        the redeem code (document id).
     * @param rewardName  reward display name, or {@code null}.
     * @param cost        points cost.
     * @param status      {@code pending | completed | cancelled | expired}.
     * @param createdAt   creation epoch millis.
     * @param terminalAt  epoch millis the code left pending, or {@code 0} if still pending.
     * @param clientUid   owning customer uid, or {@code null}.
     * @param clientName  resolved customer display name, or the uid/{@code null}.
     * @param cashierUid  uid that completed the redeem, or {@code null}.
     * @param cashierName resolved cashier display name, or the uid/{@code null}.
     */
    public record RedeemLogItem(String code, String rewardName, long cost, String status, long createdAt,
                                long terminalAt, String clientUid, String clientName, String cashierUid,
                                String cashierName) {
    }
}
