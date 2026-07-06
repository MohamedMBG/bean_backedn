package com.beanLoyal.backend.push;

import com.beanLoyal.backend.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers {@link Device}'s pure validation + doc-factory (BUSINESS_RULES §8). The Firestore upsert
 * path ({@code DeviceService.register}) needs the emulator and is deferred to integration tests,
 * same note the other {@code *ServiceTest}s carry.
 */
class DeviceTest {

    @Test
    void acceptsValidDeviceId() {
        assertDoesNotThrow(() -> Device.validateDeviceId("a-uuid_1234"));
    }

    @Test
    void rejectsNullDeviceId() {
        assertEquals("DEVICE_ID_INVALID",
                assertThrows(ApiException.class, () -> Device.validateDeviceId(null)).getCode());
    }

    @Test
    void rejectsBlankDeviceId() {
        assertEquals("DEVICE_ID_INVALID",
                assertThrows(ApiException.class, () -> Device.validateDeviceId("")).getCode());
    }

    @Test
    void rejectsDeviceIdWithSlash() {
        // Firestore path safety: '/' would create a nested path, not a doc id.
        assertEquals("DEVICE_ID_INVALID",
                assertThrows(ApiException.class, () -> Device.validateDeviceId("a/b")).getCode());
    }

    @Test
    void rejectsReservedDeviceId() {
        assertEquals("DEVICE_ID_INVALID",
                assertThrows(ApiException.class, () -> Device.validateDeviceId("__x__")).getCode());
    }

    @Test
    void rejectsOverlongDeviceId() {
        assertEquals("DEVICE_ID_INVALID",
                assertThrows(ApiException.class, () -> Device.validateDeviceId("a".repeat(129))).getCode());
    }

    @Test
    void rejectsBlankToken() {
        assertEquals("FCM_TOKEN_INVALID",
                assertThrows(ApiException.class, () -> Device.validateToken("  ")).getCode());
    }

    @Test
    void rejectsOverlongToken() {
        assertEquals("FCM_TOKEN_INVALID",
                assertThrows(ApiException.class, () -> Device.validateToken("t".repeat(Device.FCM_TOKEN_MAX + 1))).getCode());
    }

    @Test
    void normalizePlatformLowercasesAndroid() {
        assertEquals("android", Device.normalizePlatform("ANDROID"));
    }

    @Test
    void rejectsUnknownPlatform() {
        assertEquals("INVALID_PLATFORM",
                assertThrows(ApiException.class, () -> Device.normalizePlatform("windows")).getCode());
    }

    @Test
    void docContainsAllFieldsAndDisabledFalse() {
        var doc = Device.doc("uid1", "tok", "android", Instant.parse("2026-07-06T12:00:00Z"));
        assertEquals("uid1", doc.get(Device.UID));
        assertEquals("tok", doc.get(Device.FCM_TOKEN));
        assertEquals("android", doc.get(Device.PLATFORM));
        assertEquals(Boolean.FALSE, doc.get(Device.DISABLED));
        assertEquals(true, doc.containsKey(Device.LAST_SEEN_AT));
    }
}
