package com.beanLoyal.backend.admin;

import java.util.List;
import java.util.Map;

/**
 * Response for {@code GET /api/v1/admin/audit}: recent audit-log entries.
 * Wrapped in {@link com.beanLoyal.backend.common.ApiResponse}. Reads the append-only
 * {@code audit} collection written by {@code AuditService}.
 *
 * @param entries most-recent-first audit entries (capped in the service).
 */
public record AuditListResponse(List<AuditEntry> entries) {

    /**
     * @param actorUid  privileged caller who performed the action.
     * @param action    dotted action name (e.g. {@code "points.adjust"}).
     * @param targetId  primary object id, or {@code null}.
     * @param targetUid affected user, or {@code null}.
     * @param details   action-specific fields, or {@code null}.
     * @param createdAt epoch millis, or {@code 0} if the server timestamp has not resolved yet.
     */
    public record AuditEntry(String actorUid, String action, String targetId, String targetUid,
                             Map<String, Object> details, long createdAt) {
    }
}
