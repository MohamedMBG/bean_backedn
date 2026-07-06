package com.beanLoyal.backend.rewards;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link RedeemCodeService#generateCode}, the pure code generator BUSINESS_RULES §3.1
 * depends on. The Firestore transaction path (redeem/cancel/complete) needs the emulator and is
 * exercised by integration tests later.
 */
class RedeemCodeServiceTest {

    private final RedeemCodeService service = new RedeemCodeService();

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
}
