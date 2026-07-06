package com.beanLoyal.backend.push;

import com.beanLoyal.backend.common.ApiException;
import com.google.cloud.Timestamp;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Single schema definition + doc-factory + pure validation for the {@code devices/{deviceId}}
 * collection ({@code docs/BACKEND_IMPLEMENTATION_PLAN.md §11}). Mirrors {@code rewards/RedeemCode}
 * so the Phase 9 registration path and any future FCM-send path share one set of field-name
 * constants and cannot drift or typo keys.
 * <p>
 * Design notes (see {@code docs/BUSINESS_RULES.md §8}):
 * <ul>
 *   <li>Doc id is the client-supplied stable per-install {@code deviceId}, NOT a hash of the FCM
 *       token — tokens rotate, and hashing would mint a new doc on every rotation, orphaning the old
 *       one. A stable id gives exactly one doc per device, so re-registration overwrites in place.</li>
 *   <li>No {@code createdAt} is stored: preserving a "first seen" across re-registration would need a
 *       read; {@code lastSeenAt} suffices for MVP. ponytail: add {@code createdAt} only if a device
 *       age metric is ever needed.</li>
 * </ul>
 */
final class Device {

    private Device() {
    }

    static final String COLLECTION = "devices";
    static final String UID = "uid";
    static final String FCM_TOKEN = "fcmToken";
    static final String PLATFORM = "platform";
    static final String LAST_SEEN_AT = "lastSeenAt";
    static final String DISABLED = "disabled";

    static final int DEVICE_ID_MAX = 128;
    static final int FCM_TOKEN_MAX = 4096;
    static final Set<String> ALLOWED_PLATFORMS = Set.of("android", "ios", "web");
    // Charset also guarantees a Firestore-path-safe doc id: no '/', '.', '..', or empty.
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1," + DEVICE_ID_MAX + "}");
    // Firestore reserves ids matching __.*__ .
    private static final Pattern RESERVED_ID = Pattern.compile("__.*__");

    /**
     * Validate the client-supplied device id: non-null, charset {@code [A-Za-z0-9_-]}, length 1..128,
     * not a Firestore reserved id. The charset check also blocks path traversal ({@code /}, {@code .},
     * {@code ..}) before the value is used as a document id.
     *
     * @throws ApiException 400 {@code DEVICE_ID_INVALID} on any failure.
     */
    static void validateDeviceId(String id) {
        if (id == null || !DEVICE_ID_PATTERN.matcher(id).matches() || RESERVED_ID.matcher(id).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DEVICE_ID_INVALID",
                    "deviceId must be 1-" + DEVICE_ID_MAX + " chars of [A-Za-z0-9_-]");
        }
    }

    /**
     * Validate the FCM token: non-blank, length &le; {@value #FCM_TOKEN_MAX}. No charset check — FCM
     * tokens are opaque. The token is NEVER logged.
     *
     * @throws ApiException 400 {@code FCM_TOKEN_INVALID} on any failure.
     */
    static void validateToken(String token) {
        if (token == null || token.isBlank() || token.length() > FCM_TOKEN_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FCM_TOKEN_INVALID",
                    "fcmToken must be non-blank and <= " + FCM_TOKEN_MAX + " chars");
        }
    }

    /**
     * Lower-case and validate the platform against {@link #ALLOWED_PLATFORMS}.
     *
     * @return the normalized platform value.
     * @throws ApiException 400 {@code INVALID_PLATFORM} if null or unknown.
     */
    static String normalizePlatform(String platform) {
        if (platform == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PLATFORM", "platform is required");
        }
        String normalized = platform.toLowerCase(Locale.ROOT);
        if (!ALLOWED_PLATFORMS.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PLATFORM",
                    "platform must be one of " + ALLOWED_PLATFORMS);
        }
        return normalized;
    }

    /**
     * Build the Firestore payload for an upsert. {@code disabled} is always {@code false}, so a
     * re-registration re-enables a device a future FCM-send path had flagged dead.
     */
    static Map<String, Object> doc(String uid, String fcmToken, String platform, Instant lastSeenAt) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(UID, uid);
        doc.put(FCM_TOKEN, fcmToken);
        doc.put(PLATFORM, platform);
        doc.put(LAST_SEEN_AT, toTimestamp(lastSeenAt));
        doc.put(DISABLED, Boolean.FALSE);
        return doc;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
    }
}
