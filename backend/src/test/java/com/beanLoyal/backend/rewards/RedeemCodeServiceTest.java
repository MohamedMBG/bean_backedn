package com.beanLoyal.backend.rewards;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the pure logic in {@link RedeemCodeService}: {@link RedeemCodeService#generateCode} (§3.1
 * code shape) and {@link RedeemCodeService#isExpired} (§3.1 TTL boundary). The Firestore transaction
 * paths (cancel/complete/expire/refund) need the emulator and are exercised by integration tests
 * later. Firestore/Activity/Audit/Clock deps are {@code null} here — the pure methods never touch them.
 */
class RedeemCodeServiceTest {

    private final RedeemCodeService service = new RedeemCodeService(null, null, null, null);

    @Test
    void generatesCorrectLengthAndAlphabet() {
        // 200 samples: every character of every code must be in the ambiguity-free alphabet and
        // the length must match, so a cashier can always transcribe a generated code (§3.1).
        for (int i = 0; i < 200; i++) {
            String code = service.generateCode();
            assertEquals(RedeemCodeService.CODE_LENGTH, code.length());
            for (int c = 0; c < code.length(); c++) {
                assertTrue(RedeemCodeService.CODE_ALPHABET.indexOf(code.charAt(c)) >= 0,
                        "unexpected char in generated code: " + code);
            }
        }
    }

    @Test
    void generatesVaryingCodes() {
        // Not a collision proof — just guards against a broken generator returning a constant.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            seen.add(service.generateCode());
        }
        assertTrue(seen.size() > 90, "generator produced too many duplicates: " + seen.size());
    }

    @Test
    void notExpiredBeforeExpiry() {
        Instant expiresAt = Instant.parse("2026-07-06T12:00:00Z");
        assertFalse(RedeemCodeService.isExpired(expiresAt, expiresAt.minusSeconds(1)));
    }

    @Test
    void expiredAtBoundaryInclusive() {
        // §3.1 "past expiresAt" is inclusive of the exact instant (fail-safe direction).
        Instant expiresAt = Instant.parse("2026-07-06T12:00:00Z");
        assertTrue(RedeemCodeService.isExpired(expiresAt, expiresAt));
    }

    @Test
    void expiredAfterExpiry() {
        Instant expiresAt = Instant.parse("2026-07-06T12:00:00Z");
        assertTrue(RedeemCodeService.isExpired(expiresAt, expiresAt.plusSeconds(1)));
    }

    @Test
    void nullExpiryTreatedAsExpired() {
        // Malformed doc with no expiresAt fails safe (matches EarnCodeService/IdempotencyService).
        assertTrue(RedeemCodeService.isExpired(null, Instant.parse("2026-07-06T12:00:00Z")));
    }
}
