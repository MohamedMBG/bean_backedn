package com.beanLoyal.backend.admin;

import java.util.List;

/**
 * Response for {@code GET /api/v1/admin/earn-codes} (the admin scan-log screen): recent earn codes
 * with cashier + customer names resolved. Wrapped in {@link com.beanLoyal.backend.common.ApiResponse}.
 * Admins cannot read {@code earn_codes} directly (Firestore rules), so the log reads through here.
 *
 * @param items most-recent-first earn-code rows (capped in the service).
 */
public record EarnLogResponse(List<EarnLogItem> items) {

    /**
     * @param code        the earn code (document id).
     * @param amountMad    purchase amount in MAD.
     * @param points      points granted.
     * @param status      {@code active | used | revoked}.
     * @param createdAt   creation epoch millis.
     * @param redeemedAt  scan epoch millis, or {@code 0} if not yet scanned.
     * @param cashierUid  uid that created the code, or {@code null}.
     * @param cashierName resolved cashier display name, or the uid/{@code null}.
     * @param clientUid   uid that scanned the code, or {@code null}.
     * @param clientName  resolved customer display name, or the uid/{@code null}.
     */
    public record EarnLogItem(String code, double amountMad, long points, String status, long createdAt,
                              long redeemedAt, String cashierUid, String cashierName, String clientUid,
                              String clientName) {
    }
}
