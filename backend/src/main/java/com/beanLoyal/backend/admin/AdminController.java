package com.beanLoyal.backend.admin;

import com.beanLoyal.backend.common.ApiResponse;
import com.beanLoyal.backend.common.ApiV1;
import com.beanLoyal.backend.common.ClientIpResolver;
import com.beanLoyal.backend.common.IdempotencyService;
import com.beanLoyal.backend.common.RateLimitPolicy;
import com.beanLoyal.backend.common.RateLimitService;
import com.beanLoyal.backend.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.concurrent.ExecutionException;

/**
 * Admin endpoints — Phase 10. Published under {@code /api/v1/admin} via {@code @ApiV1} + {@code WebMvcConfig}.
 * <p>
 * Every route requires Firebase authentication AND the {@code admin} role — the class-level
 * {@code @PreAuthorize("hasRole('ADMIN')")} rejects any other caller with 403 {@code FORBIDDEN}
 * (role mapped in {@code FirebaseAuthFilter}, §5b; {@code @EnableMethodSecurity} on). All routes are
 * rate-limited by {@link RateLimitPolicy#CASHIER_ADMIN}; write routes are idempotency-guarded and
 * write an audit entry atomically with their Firestore-owned state.
 */
@ApiV1
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;
    private final CashierProvisioningService cashierProvisioningService;
    private final AnalyticsService analyticsService;
    private final AdminLogsService logsService;
    private final IdempotencyService idempotencyService;
    private final RateLimitService rateLimitService;

    public AdminController(AdminService adminService, CashierProvisioningService cashierProvisioningService,
                           AnalyticsService analyticsService,
                           AdminLogsService logsService, IdempotencyService idempotencyService,
                           RateLimitService rateLimitService) {
        this.adminService = adminService;
        this.cashierProvisioningService = cashierProvisioningService;
        this.analyticsService = analyticsService;
        this.logsService = logsService;
        this.idempotencyService = idempotencyService;
        this.rateLimitService = rateLimitService;
    }

    private void rateLimit(CurrentUser user, HttpServletRequest http) {
        rateLimitService.check(RateLimitPolicy.CASHIER_ADMIN, ClientIpResolver.resolve(http), user.uid());
    }

    /**
     * {@code POST /api/v1/admin/earn-codes} — create a new active earn code for a MAD purchase
     * (§2.1/§2.2). The point value is derived server-side from {@code amountMad}. This is what makes
     * the Phase 5 earn endpoint usable. Idempotency-Key required; audit-logged.
     * Response: 200 {@code ApiResponse<CreateEarnCodeResponse>}. Rejection: 400 {@code INVALID_AMOUNT}.
     * Cashiers issue earn codes at the till, so this route also accepts {@code role=cashier}
     * (method-level annotation overrides the class-level admin-only rule).
     */
    @PostMapping("/earn-codes")
    @PreAuthorize("hasAnyRole('ADMIN','CASHIER')")
    public ResponseEntity<String> createEarnCode(@AuthenticationPrincipal CurrentUser user,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                 @RequestBody CreateEarnCodeRequest request,
                                                 HttpServletRequest http) {
        rateLimit(user, http);
        return idempotencyService.execute(user.uid(), http.getRequestURI(), idempotencyKey, request,
                tx -> adminService.createEarnCode(tx, user.uid(), request.amountMad()));
    }

    /**
     * {@code POST /api/v1/admin/earn-codes/{codeId}/revoke} — revoke an active earn code (§2).
     * Idempotency-Key required (server key includes {@code codeId}); audit-logged.
     * Response: 200 {@code ApiResponse<RevokeEarnCodeResponse>}. Rejections: 404
     * {@code EARN_CODE_NOT_FOUND}, 409 {@code EARN_CODE_NOT_ACTIVE}.
     * Cashiers can cancel a code they just issued, so cashier role is accepted too.
     */
    @PostMapping("/earn-codes/{codeId}/revoke")
    @PreAuthorize("hasAnyRole('ADMIN','CASHIER')")
    public ResponseEntity<String> revokeEarnCode(@AuthenticationPrincipal CurrentUser user,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                 @PathVariable String codeId,
                                                 HttpServletRequest http) {
        rateLimit(user, http);
        return idempotencyService.execute(user.uid(), http.getRequestURI(), idempotencyKey, null,
                tx -> adminService.revokeEarnCode(tx, user.uid(), codeId));
    }

    /**
     * {@code POST /api/v1/admin/users/{uid}/points-adjustment} — apply a signed manual points
     * adjustment with a required reason (§10). Writes an {@code adjust} activity + audit entry
     * atomically. Idempotency-Key required. Response: 200 {@code ApiResponse<PointsAdjustmentResponse>}.
     * Rejections: 400 {@code INVALID_ADJUSTMENT}/{@code ADJUSTMENT_REASON_REQUIRED}, 404
     * {@code USER_NOT_FOUND}, 422 {@code ADJUSTMENT_NEGATIVE_BALANCE}.
     */
    @PostMapping("/users/{uid}/points-adjustment")
    public ResponseEntity<String> adjustPoints(@AuthenticationPrincipal CurrentUser user,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                               @PathVariable("uid") String targetUid,
                                               @RequestBody PointsAdjustmentRequest request,
                                               HttpServletRequest http) {
        rateLimit(user, http);
        return idempotencyService.execute(user.uid(), http.getRequestURI(), idempotencyKey, request,
                tx -> adminService.adjustPoints(tx, user.uid(), targetUid, request.delta(), request.reason()));
    }

    /**
     * {@code GET /api/v1/admin/users/search?email=|phone=} — find users by exact email or phone (§10).
     * Response: 200 {@code ApiResponse<UserSearchResponse>}. Rejection: 400 {@code SEARCH_CRITERIA_REQUIRED}.
     * The cashier redeem screen looks customers up by email/phone, so cashier role is accepted too.
     */
    @GetMapping("/users/search")
    @PreAuthorize("hasAnyRole('ADMIN','CASHIER')")
    public ApiResponse<UserSearchResponse> searchUsers(@AuthenticationPrincipal CurrentUser user,
                                                       @RequestParam(required = false) String email,
                                                       @RequestParam(required = false) String phone,
                                                       HttpServletRequest http)
            throws ExecutionException, InterruptedException {
        rateLimit(user, http);
        return ApiResponse.of(adminService.searchUsers(email, phone));
    }

    /**
     * {@code POST /api/v1/admin/rewards} — create a catalog reward (§10). Idempotency-Key required;
     * audit-logged. Response: 200 {@code ApiResponse<RewardResponse>}. Rejection: 400 {@code INVALID_REWARD}.
     */
    @PostMapping("/rewards")
    public ResponseEntity<String> createReward(@AuthenticationPrincipal CurrentUser user,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                               @RequestBody RewardRequest request,
                                               HttpServletRequest http) {
        rateLimit(user, http);
        return idempotencyService.execute(user.uid(), http.getRequestURI(), idempotencyKey, request,
                tx -> adminService.createReward(tx, user.uid(), request));
    }

    /**
     * {@code PUT /api/v1/admin/rewards/{id}} — overwrite a catalog reward (§10). Idempotency-Key
     * required (server key includes {@code id}); audit-logged. Response: 200
     * {@code ApiResponse<RewardResponse>}. Rejections: 400 {@code INVALID_REWARD}, 404 {@code REWARD_NOT_FOUND}.
     */
    @PutMapping("/rewards/{id}")
    public ResponseEntity<String> updateReward(@AuthenticationPrincipal CurrentUser user,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                               @PathVariable String id,
                                               @RequestBody RewardRequest request,
                                               HttpServletRequest http) {
        rateLimit(user, http);
        return idempotencyService.execute(user.uid(), http.getRequestURI(), idempotencyKey, request,
                tx -> adminService.updateReward(tx, user.uid(), id, request));
    }

    /**
     * {@code DELETE /api/v1/admin/rewards/{id}} — hard-delete a catalog reward (§10). Idempotency-Key
     * required (server key includes {@code id}); audit-logged. Response: 200
     * {@code ApiResponse<RewardDeletedResponse>}. Rejection: 404 {@code REWARD_NOT_FOUND}.
     */
    @DeleteMapping("/rewards/{id}")
    public ResponseEntity<String> deleteReward(@AuthenticationPrincipal CurrentUser user,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                               @PathVariable String id,
                                               HttpServletRequest http) {
        rateLimit(user, http);
        return idempotencyService.execute(user.uid(), http.getRequestURI(), idempotencyKey, null,
                tx -> adminService.deleteReward(tx, user.uid(), id));
    }

    /**
     * {@code POST /api/v1/admin/cashiers} — provision a cashier account (§5b/§10): creates the
     * Firebase Auth user, grants the {@code role: cashier} custom claim, and writes the user doc.
     * Idempotency-Key required. Firebase Auth and Firestore are coordinated as a resumable workflow:
     * the profile + audit commit before the privileged role is granted, and a retry resumes safely.
     * Response: 200 {@code ApiResponse<CreateCashierResponse>}. Rejections: 400
     * {@code INVALID_CASHIER}/{@code IDEMPOTENCY_KEY_REQUIRED}, 409
     * {@code CASHIER_EMAIL_EXISTS}/{@code IDEMPOTENCY_KEY_REUSED}.
     */
    @PostMapping("/cashiers")
    public ResponseEntity<ApiResponse<CreateCashierResponse>> createCashier(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateCashierRequest request,
            HttpServletRequest http) {
        rateLimit(user, http);
        CashierProvisioningService.ProvisioningResult result =
                cashierProvisioningService.provision(user.uid(), idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replayed()) {
            response.header(IdempotencyService.REPLAY_HEADER, "true");
        }
        return response.body(ApiResponse.of(result.response()));
    }

    /**
     * {@code GET /api/v1/admin/users?limit=} — the admin client roster (§10). A single capped page.
     * Response: 200 {@code ApiResponse<UserListResponse>}.
     */
    @GetMapping("/users")
    public ApiResponse<UserListResponse> listUsers(@AuthenticationPrincipal CurrentUser user,
                                                   @RequestParam(defaultValue = "50") int limit,
                                                   HttpServletRequest http)
            throws ExecutionException, InterruptedException {
        rateLimit(user, http);
        return ApiResponse.of(adminService.listUsers(limit));
    }

    /**
     * {@code GET /api/v1/admin/users/{uid}} — one user's full profile (§10). The literal
     * {@code /users/search} route above takes precedence over this {@code {uid}} template.
     * Response: 200 {@code ApiResponse<UserDetailResponse>}. Rejection: 404 {@code USER_NOT_FOUND}.
     */
    @GetMapping("/users/{uid}")
    public ApiResponse<UserDetailResponse> getUser(@AuthenticationPrincipal CurrentUser user,
                                                   @PathVariable("uid") String targetUid,
                                                   HttpServletRequest http)
            throws ExecutionException, InterruptedException {
        rateLimit(user, http);
        return ApiResponse.of(adminService.getUser(targetUid));
    }

    /**
     * {@code GET /api/v1/admin/users/{uid}/activity?limit=} — a user's recent activity feed (§10).
     * Response: 200 {@code ApiResponse<UserActivityResponse>}. Rejection: 404 {@code USER_NOT_FOUND}.
     */
    @GetMapping("/users/{uid}/activity")
    public ApiResponse<UserActivityResponse> userActivity(@AuthenticationPrincipal CurrentUser user,
                                                          @PathVariable("uid") String targetUid,
                                                          @RequestParam(defaultValue = "50") int limit,
                                                          HttpServletRequest http)
            throws ExecutionException, InterruptedException {
        rateLimit(user, http);
        return ApiResponse.of(adminService.getUserActivity(targetUid, limit));
    }

    /**
     * {@code GET /api/v1/admin/analytics?from=&to=} — aggregated dashboard metrics over the
     * {@code [from, to)} epoch-millis window (§10): revenue, points issued/redeemed, gifts, new
     * clients, per-cashier breakdown, per-day series. Requests are limited to 31 days and 10,000
     * raw records per metric so reporting cannot start an unbounded Firestore scan. Response: 200
     * {@code ApiResponse<AnalyticsResponse>}. Rejection: 400 {@code INVALID_RANGE} or
     * {@code ANALYTICS_RANGE_TOO_LARGE}.
     */
    @GetMapping("/analytics")
    public ApiResponse<AnalyticsResponse> analytics(@AuthenticationPrincipal CurrentUser user,
                                                    @RequestParam long from,
                                                    @RequestParam long to,
                                                    HttpServletRequest http)
            throws ExecutionException, InterruptedException {
        rateLimit(user, http);
        return ApiResponse.of(analyticsService.compute(from, to));
    }

    /**
     * {@code GET /api/v1/admin/earn-codes?limit=} — recent earn codes for the scan-log screen (§10),
     * newest first, with cashier + customer names resolved. Response: 200
     * {@code ApiResponse<EarnLogResponse>}.
     */
    @GetMapping("/earn-codes")
    public ApiResponse<EarnLogResponse> earnLog(@AuthenticationPrincipal CurrentUser user,
                                                @RequestParam(defaultValue = "50") int limit,
                                                HttpServletRequest http)
            throws ExecutionException, InterruptedException {
        rateLimit(user, http);
        return ApiResponse.of(logsService.earnLog(limit));
    }

    /**
     * {@code GET /api/v1/admin/redeem-codes?limit=} — recent redeem codes for the reward-log screen
     * (§10), newest first, with customer + cashier names resolved. Response: 200
     * {@code ApiResponse<RedeemLogResponse>}.
     */
    @GetMapping("/redeem-codes")
    public ApiResponse<RedeemLogResponse> redeemLog(@AuthenticationPrincipal CurrentUser user,
                                                    @RequestParam(defaultValue = "50") int limit,
                                                    HttpServletRequest http)
            throws ExecutionException, InterruptedException {
        rateLimit(user, http);
        return ApiResponse.of(logsService.redeemLog(limit));
    }

    /**
     * {@code GET /api/v1/admin/audit?limit=} — recent audit-log entries (§10).
     * Response: 200 {@code ApiResponse<AuditListResponse>}.
     */
    @GetMapping("/audit")
    public ApiResponse<AuditListResponse> audit(@AuthenticationPrincipal CurrentUser user,
                                                @RequestParam(defaultValue = "50") int limit,
                                                HttpServletRequest http)
            throws ExecutionException, InterruptedException {
        rateLimit(user, http);
        return ApiResponse.of(adminService.listAudit(limit));
    }
}
