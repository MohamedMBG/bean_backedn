package com.beanLoyal.backend.push;

import com.beanLoyal.backend.common.ApiResponse;
import com.beanLoyal.backend.common.ApiV1;
import com.beanLoyal.backend.common.ClientIpResolver;
import com.beanLoyal.backend.common.RateLimitPolicy;
import com.beanLoyal.backend.common.RateLimitService;
import com.beanLoyal.backend.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Admin-only audience preview and push-delivery endpoints under {@code /api/v1/admin/push}.
 * Preview and send share {@link PushCampaignService}'s selector so the displayed reach and final
 * recipients cannot drift. Message sending requires an idempotency key and is audit logged.
 */
@ApiV1
@RequestMapping("/admin/push")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPushController {

    private final PushCampaignService campaigns;
    private final RateLimitService rateLimits;

    public AdminPushController(PushCampaignService campaigns, RateLimitService rateLimits) {
        this.campaigns = campaigns;
        this.rateLimits = rateLimits;
    }

    /**
     * {@code POST /api/v1/admin/push/preview}: count profiles and active devices matching filters.
     * Auth: admin role. Side effects: none. Common failures: 400 {@code INVALID_PUSH_FILTER},
     * 413 audience/device bounds, 429 rate limit.
     */
    @PostMapping("/preview")
    public ApiResponse<PushPreviewResponse> preview(@AuthenticationPrincipal CurrentUser user,
                                                    @RequestBody PushPreviewRequest request,
                                                    HttpServletRequest http) {
        rateLimits.check(RateLimitPolicy.CASHIER_ADMIN, ClientIpResolver.resolve(http), user.uid());
        return ApiResponse.of(campaigns.preview(request == null ? null : request.filters()));
    }

    /**
     * {@code POST /api/v1/admin/push/send}: send one filtered FCM campaign.
     * Auth: admin role. Idempotency-Key: required; replays return the stored result without sending.
     * Response reports matched/reachable users plus device-level FCM success/failure counts.
     */
    @PostMapping("/send")
    public ApiResponse<PushSendResponse> send(@AuthenticationPrincipal CurrentUser user,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                              @Valid @RequestBody PushSendRequest request,
                                              HttpServletRequest http) {
        rateLimits.check(RateLimitPolicy.PUSH_SEND, ClientIpResolver.resolve(http), user.uid());
        return ApiResponse.of(campaigns.send(user.uid(), key, request));
    }
}
