package com.beanLoyal.backend.admin;

import com.beanLoyal.backend.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers {@link AdminService}'s pure validators + limit clamp. The Firestore transaction/query paths
 * (create/revoke/adjust/search/activity/audit) need the emulator and are deferred to integration
 * tests, same note the other {@code *ServiceTest}s carry.
 */
class AdminServiceTest {

    @Test
    void validateAmountRejectsNonPositiveOrNonFinite() {
        assertEquals("INVALID_AMOUNT",
                assertThrows(ApiException.class, () -> AdminService.validateAmount(0.0)).getCode());
        assertEquals("INVALID_AMOUNT",
                assertThrows(ApiException.class, () -> AdminService.validateAmount(-5.0)).getCode());
        assertEquals("INVALID_AMOUNT",
                assertThrows(ApiException.class, () -> AdminService.validateAmount(null)).getCode());
        assertEquals("INVALID_AMOUNT",
                assertThrows(ApiException.class, () -> AdminService.validateAmount(Double.NaN)).getCode());
    }

    @Test
    void validateAmountAcceptsPositive() {
        assertDoesNotThrow(() -> AdminService.validateAmount(50.0));
    }

    @Test
    void pointsForAmountUsesRatio() {
        assertEquals(2500L, com.beanLoyal.backend.loyalty.EarnCodeService.pointsForAmount(50.0));
        assertEquals(75L, com.beanLoyal.backend.loyalty.EarnCodeService.pointsForAmount(1.5));
    }

    @Test
    void validateAdjustmentRejectsZeroDelta() {
        assertEquals("INVALID_ADJUSTMENT",
                assertThrows(ApiException.class, () -> AdminService.validateAdjustment(0, "x")).getCode());
    }

    @Test
    void validateAdjustmentRejectsBlankReason() {
        assertEquals("ADJUSTMENT_REASON_REQUIRED",
                assertThrows(ApiException.class, () -> AdminService.validateAdjustment(10, "  ")).getCode());
    }

    @Test
    void validateAdjustmentAcceptsNegativeDeltaWithReason() {
        assertDoesNotThrow(() -> AdminService.validateAdjustment(-10, "correction"));
    }

    @Test
    void validateRewardRejectsBlankName() {
        assertEquals("INVALID_REWARD",
                assertThrows(ApiException.class,
                        () -> AdminService.validateReward(new RewardRequest("  ", 10, null, null, null))).getCode());
    }

    @Test
    void validateRewardRejectsNullOrNegativeCost() {
        assertEquals("INVALID_REWARD",
                assertThrows(ApiException.class,
                        () -> AdminService.validateReward(new RewardRequest("Latte", null, null, null, null))).getCode());
        assertEquals("INVALID_REWARD",
                assertThrows(ApiException.class,
                        () -> AdminService.validateReward(new RewardRequest("Latte", -1, null, null, null))).getCode());
    }

    @Test
    void validateRewardAcceptsValid() {
        assertDoesNotThrow(() -> AdminService.validateReward(new RewardRequest("Latte", 0, "Coffee", null, true)));
    }

    @Test
    void validateCashierRejectsBadEmailOrShortPassword() {
        assertEquals("INVALID_CASHIER",
                assertThrows(ApiException.class, () -> AdminService.validateCashier("notanemail", "secret1")).getCode());
        assertEquals("INVALID_CASHIER",
                assertThrows(ApiException.class, () -> AdminService.validateCashier("a@b.com", "123")).getCode());
    }

    @Test
    void validateCashierAcceptsValid() {
        assertDoesNotThrow(() -> AdminService.validateCashier("cashier@shop.com", "secret1"));
    }

    @Test
    void adjustedBalanceRejectsBelowZero() {
        assertEquals("ADJUSTMENT_NEGATIVE_BALANCE",
                assertThrows(ApiException.class, () -> AdminService.adjustedBalance(5, -10)).getCode());
    }

    @Test
    void adjustedBalanceComputesSum() {
        assertEquals(15L, AdminService.adjustedBalance(5, 10));
        assertEquals(0L, AdminService.adjustedBalance(10, -10));
    }

    @Test
    void capClampsToBounds() {
        assertEquals(AdminService.DEFAULT_LIMIT, AdminService.cap(0));
        assertEquals(AdminService.DEFAULT_LIMIT, AdminService.cap(-3));
        assertEquals(25, AdminService.cap(25));
        assertEquals(AdminService.MAX_LIMIT, AdminService.cap(9999));
    }
}
