package com.beanLoyal.backend.loyalty;

import com.beanLoyal.backend.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers {@link EarnCodeService#validateFormat}, the pure alphabet/length check BUSINESS_RULES
 * §2.5 depends on. The Firestore transaction path (readValid/burn) needs the emulator and is
 * exercised by integration tests later.
 */
class EarnCodeServiceTest {

    @Test
    void acceptsValidCode() {
        assertDoesNotThrow(() -> EarnCodeService.validateFormat("ABCDEFGH23"));
    }

    @Test
    void rejectsNullCode() {
        ApiException ex = assertThrows(ApiException.class, () -> EarnCodeService.validateFormat(null));
        assertEquals("EARN_CODE_INVALID_FORMAT", ex.getCode());
    }

    @Test
    void rejectsWrongLength() {
        ApiException ex = assertThrows(ApiException.class, () -> EarnCodeService.validateFormat("SHORT"));
        assertEquals("EARN_CODE_INVALID_FORMAT", ex.getCode());
    }

    @Test
    void rejectsAmbiguousCharacters() {
        // '0', 'O', '1', 'I', 'L' are excluded from the alphabet (§2.5) to avoid transcription errors.
        ApiException ex = assertThrows(ApiException.class, () -> EarnCodeService.validateFormat("ABCDEFGH0O"));
        assertEquals("EARN_CODE_INVALID_FORMAT", ex.getCode());
    }

    @Test
    void rejectsLowercase() {
        ApiException ex = assertThrows(ApiException.class, () -> EarnCodeService.validateFormat("abcdefgh23"));
        assertEquals("EARN_CODE_INVALID_FORMAT", ex.getCode());
    }
}
