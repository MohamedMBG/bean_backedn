package com.beanLoyal.backend.push;

/**
 * Request body for {@code POST /api/v1/admin/push/preview}.
 *
 * @param filters audience criteria; null is treated as an unsegmented audience.
 */
public record PushPreviewRequest(PushAudienceFilter filters) {
}
