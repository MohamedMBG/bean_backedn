package com.beanLoyal.backend.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link AdminLogsService}'s pure limit clamp. The Firestore read paths need the emulator and
 * are deferred to integration tests, same note the other {@code *ServiceTest}s carry.
 */
class AdminLogsServiceTest {

    @Test
    void capClampsToBounds() {
        assertEquals(AdminLogsService.DEFAULT_LIMIT, AdminLogsService.cap(0));
        assertEquals(AdminLogsService.DEFAULT_LIMIT, AdminLogsService.cap(-1));
        assertEquals(25, AdminLogsService.cap(25));
        assertEquals(AdminLogsService.MAX_LIMIT, AdminLogsService.cap(9999));
    }
}
