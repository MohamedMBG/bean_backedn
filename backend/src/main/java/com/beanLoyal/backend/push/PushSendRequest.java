package com.beanLoyal.backend.push;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/admin/push/send}.
 *
 * @param title   notification title, shown by the customer application.
 * @param message notification body, shown by the customer application.
 * @param filters audience criteria; null is treated as an unsegmented audience.
 */
public record PushSendRequest(
        @NotBlank @Size(max = 80) String title,
        @NotBlank @Size(max = 500) String message,
        @Valid PushAudienceFilter filters) {
}
