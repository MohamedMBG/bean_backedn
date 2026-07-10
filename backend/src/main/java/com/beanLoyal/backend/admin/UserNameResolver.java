package com.beanLoyal.backend.admin;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Resolves {@code users/{uid}.name} display names for a set of uids in ONE batched
 * {@code Firestore.getAll} round-trip. Shared by the admin analytics + log endpoints so per-cashier
 * / per-customer name lookups don't fan out into a fetch-per-row.
 */
@Component
public class UserNameResolver {

    private final Firestore firestore;

    public UserNameResolver(Firestore firestore) {
        this.firestore = firestore;
    }

    /**
     * Map each non-null uid in {@code uids} to a display name, falling back to the uid itself when
     * the user doc or its name is missing. Returns an empty map for an empty/all-null input.
     */
    public Map<String, String> resolve(Collection<String> uids)
            throws ExecutionException, InterruptedException {
        Set<String> distinct = new HashSet<>();
        for (String u : uids) {
            if (u != null && !u.isBlank()) distinct.add(u);
        }
        if (distinct.isEmpty()) return Map.of();

        DocumentReference[] refs = distinct.stream()
                .map(u -> firestore.collection("users").document(u))
                .toArray(DocumentReference[]::new);
        Map<String, String> names = new HashMap<>();
        for (DocumentSnapshot snap : firestore.getAll(refs).get()) {
            String name = firstNonBlank(snap.getString("name"), snap.getString("fullName"),
                    snap.getString("userDisplayName"));
            names.put(snap.getId(), name != null ? name : snap.getId());
        }
        return names;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
