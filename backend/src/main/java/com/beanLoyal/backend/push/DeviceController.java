package com.beanLoyal.backend.push;

import com.beanLoyal.backend.common.ApiResponse;
import com.beanLoyal.backend.common.ApiV1;
import com.beanLoyal.backend.common.ClientIpResolver;
import com.beanLoyal.backend.common.RateLimitPolicy;
import com.beanLoyal.backend.common.RateLimitService;
import com.beanLoyal.backend.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.concurrent.ExecutionException;

/**
 * Push device endpoints — Phase 9 FCM device registration.
 * {@code @ApiV1} publishes every mapping under {@code /api/v1} via {@link com.beanLoyal.backend.config.WebMvcConfig}.
 * All routes require Firebase authentication (default-secured in {@code SecurityConfig}) plus a
 * caller-scoped Firebase UID resolved via {@link CurrentUser}.
 */
@ApiV1
@RequestMapping("/push")
public class DeviceController {

    private final DeviceService deviceService;
    private final InterestService interestService;
    private final RateLimitService rateLimitService;

    public DeviceController(DeviceService deviceService, InterestService interestService,
                            RateLimitService rateLimitService) {
        this.deviceService = deviceService;
        this.interestService = interestService;
        this.rateLimitService = rateLimitService;
    }

    /**
     * {@code POST /api/v1/push/registerDevice} — register or refresh the caller's FCM device token
     * (BUSINESS_RULES.md §8).
     * <p>
     * Auth: Firebase ID token required (any role). Idempotency: NOT applied — the write is a
     * naturally idempotent upsert keyed by the client's stable {@code deviceId} (§1 lists this route
     * as idempotency-optional); an {@code Idempotency-Key} header, if sent, is ignored. Rate limit:
     * {@link RateLimitPolicy#REGISTER_DEVICE} (30/min per IP, 10/min per UID) — 429
     * {@code RATE_LIMITED} on breach, checked before any Firestore access.
     * <p>
     * Request body: {@link RegisterDeviceRequest}. Response: 200
     * {@code ApiResponse<RegisterDeviceResponse>}. Validation rejections (400
     * {@code DEVICE_ID_INVALID}, {@code FCM_TOKEN_INVALID}, {@code INVALID_PLATFORM}) come from
     * {@link Device}. The {@code fcmToken} is never logged.
     *
     * @param user        verified caller identity; {@code user.uid()} is written as the device owner
     *                    (never accepts a uid from the client).
     * @param request     client-supplied device payload.
     * @param httpRequest used only to resolve the caller's IP for the rate-limit check.
     * @return 200 {@code ApiResponse<RegisterDeviceResponse>}.
     * @throws ExecutionException   propagated from the Firestore write → 500 {@code INTERNAL_ERROR}.
     * @throws InterruptedException propagated from the Firestore write → 500 {@code INTERNAL_ERROR}.
     */
    @PostMapping("/registerDevice")
    public ApiResponse<RegisterDeviceResponse> registerDevice(@AuthenticationPrincipal CurrentUser user,
                                                              @RequestBody RegisterDeviceRequest request,
                                                              HttpServletRequest httpRequest)
            throws ExecutionException, InterruptedException {
        rateLimitService.check(RateLimitPolicy.REGISTER_DEVICE, ClientIpResolver.resolve(httpRequest), user.uid());
        return ApiResponse.of(deviceService.register(user.uid(), request));
    }

    /**
     * {@code POST /api/v1/push/unregisterDevice}: disable and redact this install's token before
     * logout. Authentication is required so ownership can be checked. The operation is naturally
     * idempotent and rate-limited with the device-registration bucket.
     */
    @PostMapping("/unregisterDevice")
    public ApiResponse<UnregisterDeviceResponse> unregisterDevice(
            @AuthenticationPrincipal CurrentUser user,
            @RequestBody UnregisterDeviceRequest request,
            HttpServletRequest httpRequest) {
        rateLimitService.check(RateLimitPolicy.REGISTER_DEVICE,
                ClientIpResolver.resolve(httpRequest), user.uid());
        return ApiResponse.of(deviceService.unregister(user.uid(), request));
    }

    /**
     * {@code POST /api/v1/push/interest}: record the caller's menu-category behavior for interest
     * segmentation. The caller UID comes exclusively from the verified token; rate limiting bounds
     * click spam. Response contains only the caller's aggregate category score.
     */
    @PostMapping("/interest")
    public ApiResponse<InterestEventResponse> recordInterest(@AuthenticationPrincipal CurrentUser user,
                                                             @RequestBody InterestEventRequest request,
                                                             HttpServletRequest httpRequest) {
        rateLimitService.check(RateLimitPolicy.INTEREST_EVENT,
                ClientIpResolver.resolve(httpRequest), user.uid());
        return ApiResponse.of(interestService.record(user.uid(), request));
    }
}
