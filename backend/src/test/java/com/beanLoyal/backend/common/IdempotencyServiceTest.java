package com.beanLoyal.backend.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Guards the pure key-derivation the whole idempotency contract rests on (BUSINESS_RULES §1).
 * The Firestore transaction path needs the emulator and is exercised by integration tests later.
 */
class IdempotencyServiceTest {

    @Test
    void sha256HexMatchesKnownVector() {
        // NIST test vector for SHA-256("abc") — fails loudly if the hashing primitive ever changes.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                IdempotencyService.sha256Hex("abc"));
    }

    @Test
    void serverKeyIsStableAndUidScoped() {
        String a = IdempotencyService.serverKey("uidA", "key-1", "/api/v1/rewards/birthday");
        String repeat = IdempotencyService.serverKey("uidA", "key-1", "/api/v1/rewards/birthday");
        String otherUid = IdempotencyService.serverKey("uidB", "key-1", "/api/v1/rewards/birthday");

        assertEquals(a, repeat, "same inputs must yield same key so retries dedup");
        assertNotEquals(a, otherUid, "different uid must yield different key so records never cross users");
    }
}
