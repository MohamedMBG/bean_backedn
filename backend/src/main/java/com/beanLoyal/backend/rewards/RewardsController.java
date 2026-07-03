package com.beanLoyal.backend.rewards;

import com.beanLoyal.backend.common.ApiV1;
import com.beanLoyal.backend.common.IdempotencyService;
import com.beanLoyal.backend.common.RateLimitPolicy;
import com.beanLoyal.backend.common.RateLimitService;
import com.beanLoyal.backend.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Rewards endpoints — Phase 4 birthday claim, Phase 6 redeem (not yet implemented).
 * {@code @ApiV1} publishes every mapping under {@code /api/v1} via {@link com.beanLoyal.backend.config.WebMvcConfig}.
 * All routes require Firebase authentication (default-secured in {@code SecurityConfig}) plus a
 * caller-scoped Firebase UID resolved via {@link CurrentUser}.
 */
@ApiV1
@RequestMapping("/rewards")
public class RewardsController {

    private final BirthdayRewardService birthdayRewardService;
    private final IdempotencyService idempotencyService;
    private final RateLimitService rateLimitService;

    public RewardsController(BirthdayRewardService birthdayRewardService,
                             IdempotencyService idempotencyService,
                             RateLimitService rateLimitService) {
        this.birthdayRewardService = birthdayRewardService;
        this.idempotencyService = idempotencyService;
        this.rateLimitService = rateLimitService;
    }

    /**
     * {@code POST /api/v1/rewards/birthday} — claim the once-per-calendar-year birthday points
     * reward (BUSINESS_RULES.md §3.7).
     * <p>
     * Auth: Firebase ID token required (any role). Idempotency: {@code Idempotency-Key} header
     * REQUIRED (§1) — missing header → 400 {@code IDEMPOTENCY_KEY_REQUIRED}, same key replayed →
     * cached response returned without re-granting points, same key with a differing body → 409
     * {@code IDEMPOTENCY_KEY_REUSED} (this route has no body, so reuse can only be triggered by
     * the same key across genuinely different requests, which cannot happen from a single client
     * call). Rate limit: {@link RateLimitPolicy#BIRTHDAY} (20/min per IP, 3/day per UID) — 429
     * {@code RATE_LIMITED} on breach, checked before any Firestore access.
     * <p>
     * No request body. Response: 200 {@code ApiResponse<BirthdayClaimResponse>}. Business
     * rejections (422 {@code BIRTHDAY_NOT_SET}/{@code BIRTHDAY_NOT_TODAY}, 409
     * {@code BIRTHDAY_ALREADY_CLAIMED}) come from {@link BirthdayRewardService#claim}.
     *
     * @param user           verified caller identity; {@code user.uid()} is the only trusted source
     *                       of identity for the claim (never accepts a uid from the client).
     * @param idempotencyKey raw {@code Idempotency-Key} header value, or {@code null} if the client
     *                       omitted it (validated by {@link IdempotencyService}, not here).
     * @param request        used only to resolve the caller's IP for the rate-limit check.
     * @return the JSON body produced by {@link IdempotencyService#execute}, either freshly computed
     *         or replayed from a prior identical request.
     */
    @PostMapping("/birthday")
    public ResponseEntity<String> claimBirthday(@AuthenticationPrincipal CurrentUser user,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                HttpServletRequest request) {
        rateLimitService.check(RateLimitPolicy.BIRTHDAY, clientIp(request), user.uid());

        return idempotencyService.execute(
                user.uid(),
                request.getRequestURI(),
                idempotencyKey,
                null,
                transaction -> birthdayRewardService.claim(transaction, user.uid())
        );
    }

    /**
     * Resolve the caller's IP for rate limiting. Render terminates TLS at a proxy, so the direct
     * socket address ({@code getRemoteAddr()}) is the proxy, not the client — prefer the first hop
     * in {@code X-Forwarded-For} when present, matching standard reverse-proxy convention.
     */
    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
