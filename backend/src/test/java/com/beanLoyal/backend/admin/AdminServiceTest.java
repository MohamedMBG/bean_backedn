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
    void validatePointsRejectsNonPositive() {
        assertEquals("INVALID_POINTS",
                assertThrows(ApiException.class, () -> AdminService.validatePoints(0)).getCode());
        assertEquals("INVALID_POINTS",
                assertThrows(ApiException.class, () -> AdminService.validatePoints(-5)).getCode());
        assertEquals("INVALID_POINTS",
                assertThrows(ApiException.class, () -> AdminService.validatePoints(null)).getCode());
    }

    @Test
    void validatePointsAcceptsPositive() {
        assertDoesNotThrow(() -> AdminService.validatePoints(1));
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
