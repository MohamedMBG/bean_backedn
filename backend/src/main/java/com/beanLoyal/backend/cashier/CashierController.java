package com.beanLoyal.backend.cashier;

import com.beanLoyal.backend.common.ApiV1;
import com.beanLoyal.backend.common.ClientIpResolver;
import com.beanLoyal.backend.common.IdempotencyService;
import com.beanLoyal.backend.common.RateLimitPolicy;
import com.beanLoyal.backend.common.RateLimitService;
import com.beanLoyal.backend.rewards.RedeemCodeService;
import com.beanLoyal.backend.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Cashier-scoped endpoints — Phase 7 redeem completion.
 * {@code @ApiV1} publishes every mapping under {@code /api/v1} via {@link com.beanLoyal.backend.config.WebMvcConfig}.
 * All routes require Firebase authentication AND the {@code cashier} role (Firebase custom claim
 * {@code role: cashier} → authority {@code ROLE_CASHIER}, see {@code FirebaseAuthFilter} / §5b).
 */
@ApiV1
@RequestMapping("/cashier")
public class CashierController {

    private final RedeemCodeService redeemCodeService;
    private final IdempotencyService idempotencyService;
    private final RateLimitService rateLimitService;

    public CashierController(RedeemCodeService redeemCodeService,
                            IdempotencyService idempotencyService,
                            RateLimitService rateLimitService) {
        this.redeemCodeService = redeemCodeService;
        this.idempotencyService = idempotencyService;
        this.rateLimitService = rateLimitService;
    }

    /**
     * {@code POST /api/v1/cashier/redeem/complete} — mark a customer's pending redeem code completed
     * once the reward is handed over (BUSINESS_RULES.md §3.9/§3.1).
     * <p>
     * Auth: Firebase ID token with {@code role=cashier} — {@code @PreAuthorize("hasRole('CASHIER')")}
     * rejects any other caller with 403 {@code FORBIDDEN} before any Firestore or idempotency work.
     * Idempotency: {@code Idempotency-Key} header REQUIRED (§1) — same key + same body replayed →
     * cached response, no second completion. Rate limit: {@link RateLimitPolicy#CASHIER_ADMIN}
     * (60/min per IP and per UID) — 429 {@code RATE_LIMITED} on breach, checked before Firestore.
     * <p>
     * Request body: {@link CashierCompleteRequest}. Response: 200
     * {@code ApiResponse<CashierCompleteResponse>}. Business rejections (404
     * {@code REDEEM_NOT_FOUND}, 409 {@code REDEEM_NOT_PENDING} — including an expired-but-not-yet-swept
     * code) come from {@link RedeemCodeService#complete}. The completion writes an
     * {@code audit/{id}} entry atomically with the status flip; no points move.
     *
     * @param user           verified cashier identity; {@code user.uid()} is the audit actor.
     * @param idempotencyKey raw {@code Idempotency-Key} header, or {@code null} if omitted.
     * @param request        the pending redeem code presented by the customer.
     * @param httpRequest    used to resolve the caller's IP for the rate-limit check.
     * @return the JSON body produced by {@link IdempotencyService#execute}, fresh or replayed.
     */
    @PostMapping("/redeem/complete")
    @PreAuthorize("hasRole('CASHIER')")
    public ResponseEntity<String> completeRedeem(@AuthenticationPrincipal CurrentUser user,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                 @RequestBody CashierCompleteRequest request,
                                                 HttpServletRequest httpRequest) {
        rateLimitService.check(RateLimitPolicy.CASHIER_ADMIN, ClientIpResolver.resolve(httpRequest), user.uid());

        return idempotencyService.execute(
                user.uid(),
                httpRequest.getRequestURI(),
                idempotencyKey,
                request,
                transaction -> redeemCodeService.complete(transaction, user.uid(), request.code())
        );
    }
}
