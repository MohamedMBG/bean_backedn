package com.beanLoyal.backend.rewards;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;

/**
 * Generates pending redeem codes per {@code docs/BUSINESS_RULES.md §3}.
 * <p>
 * A redeem code is the {@code redeem_codes/{code}} document id the customer shows the cashier
 * (§3.1). Like the earn code (§2.5), it uses a human-readable alphabet so a cashier can type it
 * off a phone screen when the QR is unscannable.
 * <p>
 * Phase 6 only generates codes; the read/validate/cancel/complete paths land with the cashier and
 * cancel endpoints in Phase 7.
 */
@Service
public class RedeemCodeService {

    /**
     * Redeem code alphabet — uppercase letters + digits, excluding visually ambiguous characters
     * ({@code 0/O/1/I/L}) so a cashier can type a code read off a screen without transcription
     * errors. Kept independent of the earn-code alphabet (a one-line duplicate) so the two code
     * types can diverge without coupling {@code loyalty} and {@code rewards} packages.
     */
    static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** Fixed redeem code length. 32^10 keyspace → collisions negligible at MVP scale. */
    static final int CODE_LENGTH = 10;

    private final SecureRandom random = new SecureRandom();

    /**
     * Generate a fresh {@value #CODE_LENGTH}-character redeem code from {@link #CODE_ALPHABET}.
     * <p>
     * ponytail: no Firestore existence check / collision-retry loop — at 32^10 the birthday-bound
     * collision probability is negligible for MVP volumes, and the caller writes with
     * {@code transaction.set}, so a (practically impossible) collision would overwrite one stale
     * doc rather than corrupt balances. Add a read-then-retry loop only if code volume ever
     * approaches that keyspace.
     *
     * @return an uppercase alphanumeric code with no ambiguous characters.
     */
    String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
