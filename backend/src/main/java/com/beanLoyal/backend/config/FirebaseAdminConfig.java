package com.beanLoyal.backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.cloud.FirestoreClient;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

// Spring config class. Beans defined here loaded at startup.
@Configuration
// Holds Firebase Admin SDK setup logic.
public class FirebaseAdminConfig {

    // Inject value from application.yaml key firebase.credentials.path. Default empty if missing.
    @Value("${firebase.credentials.path:}")
    // File path to service account JSON. Used for local dev + Render Secret File.
    private String firebaseCredentialsPath;

    // Inject value from application.yaml key firebase.credentials.json. Default empty if missing.
    @Value("${firebase.credentials.json:}")
    // Raw JSON string of service account. Fallback when no file available (inline env var).
    private String firebaseCredentialsJson;

    // Spring runs this method once after dependency injection finishes. Boots Firebase before any other bean uses it.
    @PostConstruct
    // Init method. Throws IOException if credential stream read fails.
    public void init() throws IOException {
        // Guard against double init. If FirebaseApp already exists, skip — re-init throws.
        if (!(FirebaseApp.getApps().isEmpty())) {
            // Exit early. Nothing to do.
            return;
        }
        // Will hold resolved Google credentials object passed to FirebaseOptions.
        GoogleCredentials creds;
        // Prefer inline JSON if provided. Useful when secret stored directly in env var.
        if (!firebaseCredentialsJson.isEmpty()) {
            // Wrap JSON string as InputStream for SDK parser. try-with-resources auto-closes.
            try (InputStream in = new ByteArrayInputStream(firebaseCredentialsJson.getBytes(StandardCharsets.UTF_8))) {
                // Parse JSON bytes into credentials.
                creds = GoogleCredentials.fromStream(in);
            }
        }
        // Otherwise try file path. Standard mode for local dev + Render Secret File mount.
        else if (!firebaseCredentialsPath.isEmpty()) {
            // Open file from disk. try-with-resources closes stream.
            try (InputStream in = new FileInputStream(firebaseCredentialsPath)) {
                // Parse file bytes into credentials.
                creds = GoogleCredentials.fromStream(in);
            }
        // No credentials configured at all. Fail fast — better than running with broken auth.
        } else {
            // Throw to abort startup. Logs make missing config visible immediately.
            throw new IllegalArgumentException("Firebase credentials not provided");
        }

        // Build Firebase options object using resolved credentials.
        FirebaseOptions options = FirebaseOptions.builder()
                // Attach the parsed credentials.
                .setCredentials(creds)
                // Finalize builder.
                .build();
        // Register default FirebaseApp instance with these options. Singleton across process.
        FirebaseApp.initializeApp(options);

    }

    // Expose FirebaseAuth as Spring bean so controllers/filters inject it directly.
    @Bean
    // Returns the auth client tied to the default FirebaseApp initialized above.
    public FirebaseAuth firebaseAuth() {
        // Pulls singleton from initialized FirebaseApp. Used to verify ID tokens.
        return FirebaseAuth.getInstance();
    }

    /**
     * Firestore client tied to the default {@link FirebaseApp} initialized in {@link #init()}.
     * <p>
     * Injected by services that read/write operational Firestore collections
     * (idempotency records, earn codes, redeem codes, birthday claims, activity
     * logs, devices, audit). Reuses the same service-account credentials as
     * {@link #firebaseAuth()} — no separate configuration required.
     * <p>
     * The returned instance is thread-safe and expected to be shared across
     * the whole application. It picks up the Firebase project selected via
     * {@code FIREBASE_CREDENTIALS_PATH} / {@code FIREBASE_CREDENTIALS_JSON}
     * (see {@code application-*.yaml} per Spring profile).
     *
     * @return singleton Firestore client for the active Firebase project.
     */
    @Bean
    public Firestore firestore() {
        // Depends on init() having populated the default FirebaseApp. Same guarantee
        // as firebaseAuth() above: @PostConstruct runs before any @Bean factory
        // method on this configuration class is invoked by the container.
        return FirestoreClient.getFirestore();
    }

}
