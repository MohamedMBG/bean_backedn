package com.beanLoyal.backend.loyalty;

import com.beanLoyal.backend.common.ApiV1;
import com.beanLoyal.backend.common.ClientIpResolver;
import com.beanLoyal.backend.common.IdempotencyService;
import com.beanLoyal.backend.common.RateLimitPolicy;
import com.beanLoyal.backend.common.RateLimitService;
import com.beanLoyal.backend.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Loyalty earn endpoints — Phase 5 QR earn.
 * {@code @ApiV1} publishes every mapping under {@code /api/v1} via {@link com.beanLoyal.backend.config.WebMvcConfig}.
 * All routes require Firebase authentication (default-secured in {@code SecurityConfig}) plus a
 * caller-scoped Firebase UID resolved via {@link CurrentUser}.
 */
@ApiV1
@RequestMapping("/loyalty")
public class LoyaltyController {

    private final LoyaltyService loyaltyService;
    private final IdempotencyService idempotencyService;
    private final RateLimitService rateLimitService;

    public LoyaltyController(LoyaltyService loyaltyService,
                             IdempotencyService idempotencyService,
                             RateLimitService rateLimitService) {
        this.loyaltyService = loyaltyService;
        this.idempotencyService = idempotencyService;
        this.rateLimitService = rateLimitService;
    }

    /**
     * {@code POST /api/v1/loyalty/earn} — redeem a printed-receipt QR/manual earn code for points
     * (BUSINESS_RULES.md §2).
     * <p>
     * Auth: Firebase ID token required (any role). Idempotency: {@code Idempotency-Key} header
     * REQUIRED (§1) — missing header → 400 {@code IDEMPOTENCY_KEY_REQUIRED}, same key + same body
     * replayed → cached response returned without re-granting points or re-burning the code, same
     * key with a differing body → 409 {@code IDEMPOTENCY_KEY_REUSED}. Rate limit:
     * {@link RateLimitPolicy#EARN} (30/min per IP, 10/min per UID) — 429 {@code RATE_LIMITED} on
     * breach, checked before any Firestore access.
     * <p>
     * Request body: {@link EarnRequest}. Response: 200 {@code ApiResponse<EarnResponse>}. Business
     * rejections (400 {@code EARN_CODE_INVALID_FORMAT}, 404 {@code EARN_CODE_NOT_FOUND}, 410
     * {@code EARN_CODE_EXPIRED}, 409 {@code EARN_CODE_ALREADY_USED}, 429 {@code VISIT_COOLDOWN})
     * come from {@link LoyaltyService#earn}.
     *
     * @param user           verified caller identity; {@code user.uid()} is the only trusted source
     *                       of identity for the grant (never accepts a uid from the client).
     * @param idempotencyKey raw {@code Idempotency-Key} header value, or {@code null} if the client
     *                       omitted it (validated by {@link IdempotencyService}, not here).
     * @param request        client-supplied earn code payload; hashed by {@link IdempotencyService}
     *                       to detect key reuse with a changed body.
     * @param httpRequest    used only to resolve the caller's IP for the rate-limit check.
     * @return the JSON body produced by {@link IdempotencyService#execute}, either freshly computed
     *         or replayed from a prior identical request.
     */
    @PostMapping("/earn")
    public ResponseEntity<String> earn(@AuthenticationPrincipal CurrentUser user,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                       @RequestBody EarnRequest request,
                                       HttpServletRequest httpRequest) {
        rateLimitService.check(RateLimitPolicy.EARN, ClientIpResolver.resolve(httpRequest), user.uid());

        return idempotencyService.execute(
                user.uid(),
                httpRequest.getRequestURI(),
                idempotencyKey,
                request,
                transaction -> loyaltyService.earn(transaction, user.uid(), request.code())
        );
    }
}
