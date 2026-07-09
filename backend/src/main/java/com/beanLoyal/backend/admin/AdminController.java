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
 * rate-limited by {@link RateLimitPolicy#CASHIER_ADMIN}; the three write routes are idempotency-guarded
 * and write an atomic {@code audit} entry.
 */
@ApiV1
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;
    private final IdempotencyService idempotencyService;
    private final RateLimitService rateLimitService;

    public AdminController(AdminService adminService, IdempotencyService idempotencyService,
                          RateLimitService rateLimitService) {
        this.adminService = adminService;
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
     */
    @PostMapping("/earn-codes")
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
     */
    @PostMapping("/earn-codes/{codeId}/revoke")
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
     */
    @GetMapping("/users/search")
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
     * Not idempotency-keyed — the auth account's email uniqueness is the natural guard. Audit-logged.
     * Response: 200 {@code ApiResponse<CreateCashierResponse>}. Rejections: 400 {@code INVALID_CASHIER},
     * 409 {@code CASHIER_EMAIL_EXISTS}.
     */
    @PostMapping("/cashiers")
    public ApiResponse<CreateCashierResponse> createCashier(@AuthenticationPrincipal CurrentUser user,
                                                            @RequestBody CreateCashierRequest request,
                                                            HttpServletRequest http)
            throws ExecutionException, InterruptedException {
        rateLimit(user, http);
        return ApiResponse.of(adminService.createCashier(user.uid(), request.email(),
                request.password(), request.name()));
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
