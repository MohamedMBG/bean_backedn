package com.beanLoyal.backend.push;

import com.beanLoyal.backend.common.ApiException;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Records customer menu-category behavior on {@code users/{uid}}. The backend owns these fields,
 * so a client cannot forge another user's interest or overwrite the aggregate directly.
 * <p>
 * Each event atomically increments {@code interestScores[category]} and updates
 * {@code topInterest}. Push segmentation reads only {@code topInterest}; the full score map remains
 * available for future analytics. Transport retries are locally debounced by the Android client,
 * and this endpoint is rate-limited to bound intentional click spam.
 */
@Service
public class InterestService {

    private static final Pattern CATEGORY_PATTERN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} &'_-]{0,39}");
    private static final int MAX_CATEGORIES = 50;
    private final Firestore firestore;

    public InterestService(Firestore firestore) {
        this.firestore = firestore;
    }

    /**
     * Record one category-selection event for the authenticated user.
     *
     * @param uid verified Firebase UID; never accepted from the request body.
     * @param request client category event.
     * @return the updated category score and top interest.
     * @throws ApiException 400 {@code INTEREST_CATEGORY_INVALID} for unsafe/blank categories, 404
     *                      {@code USER_NOT_FOUND} when the caller has no profile document.
     */
    public InterestEventResponse record(String uid, InterestEventRequest request) {
        String category = normalizeCategory(request == null ? null : request.category());
        DocumentReference userRef = firestore.collection("users").document(uid);
        try {
            return firestore.runTransaction(transaction -> {
                DocumentSnapshot snapshot = transaction.get(userRef).get();
                if (!snapshot.exists()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User profile not found");
                }
                Map<String, Long> scores = readScores(snapshot.get("interestScores"));
                if (!scores.containsKey(category) && scores.size() >= MAX_CATEGORIES) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INTEREST_LIMIT_REACHED",
                            "Too many distinct interest categories");
                }
                long current = scores.getOrDefault(category, 0L);
                long next = current == Long.MAX_VALUE ? Long.MAX_VALUE : current + 1L;
                scores.put(category, next);

                String currentTop = AudienceMatcher.normalize(snapshot.getString("topInterest"));
                long currentTopScore = scores.getOrDefault(currentTop, 0L);
                String top = currentTop.isEmpty() || next > currentTopScore ? category : currentTop;

                Map<String, Object> updates = new LinkedHashMap<>();
                updates.put("interestScores", scores);
                updates.put("topInterest", top);
                updates.put("interestUpdatedAt", FieldValue.serverTimestamp());
                transaction.update(userRef, updates);
                return new InterestEventResponse(top, next);
            }).get();
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Interest transaction failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interest transaction interrupted", e);
        }
    }

    static String normalizeCategory(String raw) {
        if (raw == null) invalidCategory();
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!CATEGORY_PATTERN.matcher(normalized).matches()) invalidCategory();
        return normalized;
    }

    private static Map<String, Long> readScores(Object raw) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) return result;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof Number value) {
                result.put(key, Math.max(0L, value.longValue()));
            }
        }
        return result;
    }

    private static void invalidCategory() {
        throw new ApiException(HttpStatus.BAD_REQUEST, "INTEREST_CATEGORY_INVALID",
                "category must be 1-40 letters, numbers, spaces, apostrophes, ampersands, hyphens or underscores");
    }
}
