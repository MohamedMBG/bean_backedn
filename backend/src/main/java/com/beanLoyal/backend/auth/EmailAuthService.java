package com.beanLoyal.backend.auth;

import com.beanLoyal.backend.common.ApiException;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import com.google.firebase.auth.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;

/** Email possession plus a device-held verifier are required to sign in. */
@Service
public class EmailAuthService {
    private final Firestore db;
    private final FirebaseAuth auth;
    private final ObjectProvider<JavaMailSender> mail;
    private final String from;
    private final String publicUrl;

    public EmailAuthService(Firestore db, FirebaseAuth auth, ObjectProvider<JavaMailSender> mail,
            @Value("${auth.email.from:}") String from,
            @Value("${auth.email.public-url:}") String publicUrl) {
        this.db = db;
        this.auth = auth;
        this.mail = mail;
        this.from = from;
        this.publicUrl = publicUrl;
    }

    public void register(String email, String challenge) throws Exception {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null || from.isBlank() || !publicUrl.startsWith("https://")) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_UNAVAILABLE",
                    "Email sign-in is temporarily unavailable.");
        }
        String token = newToken();
        DocumentReference ref = db.collection("email_signins").document(hash(token));
        ref.set(Map.of("email", email, "challenge", challenge,
                "expiresAt", Timestamp.ofTimeSecondsAndNanos(Instant.now().plusSeconds(900).getEpochSecond(), 0),
                "used", false)).get();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Sign in to BeanLoyal");
        // Fragment stays out of HTTP access logs and referrer headers.
        message.setText("Open this link on the phone where you requested it to sign in to BeanLoyal:\n\n"
                + publicUrl + "#" + token
                + "\n\nThis link expires in 15 minutes and can be used once."
                + " If you did not request it, ignore this email.");
        try {
            sender.send(message);
        } catch (org.springframework.mail.MailException e) {
            ref.delete().get();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_UNAVAILABLE",
                    "Could not send the email. Please try again later.");
        }
    }

    public SignInResponse verify(String token, String verifier) throws Exception {
        DocumentReference ref = db.collection("email_signins").document(hash(token));
        String email;
        try {
            email = db.runTransaction(tx -> {
                DocumentSnapshot doc = tx.get(ref).get();
                validateLink(doc.exists(), doc.getBoolean("used"), doc.getTimestamp("expiresAt"),
                        doc.getString("challenge"), verifier, Instant.now());
                tx.update(ref, "used", true);
                return doc.getString("email");
            }).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ApiException api) throw api;
            throw e;
        }
        // A consumed link is never reopened on a downstream failure. Request a new email to retry.
        UserRecord user;
        try {
            user = auth.getUserByEmail(email);
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() != AuthErrorCode.USER_NOT_FOUND) throw e;
            try {
                user = auth.createUser(new UserRecord.CreateRequest().setEmail(email).setEmailVerified(true));
            } catch (FirebaseAuthException createError) {
                if (createError.getAuthErrorCode() != AuthErrorCode.EMAIL_ALREADY_EXISTS) throw createError;
                user = auth.getUserByEmail(email);
            }
        }
        // This endpoint must not become an alternative login method for privileged staff.
        if (user.isDisabled() || user.getCustomClaims().containsKey("role")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SIGNIN_NOT_ALLOWED",
                    "This account cannot use customer email sign-in.");
        }
        auth.updateUser(new UserRecord.UpdateRequest(user.getUid()).setEmailVerified(true));
        DocumentReference profile = db.collection("users").document(user.getUid());
        db.runTransaction(tx -> {
            DocumentSnapshot doc = tx.get(profile).get();
            Map<String, Object> fields = new HashMap<>();
            fields.put("email", email);
            fields.put("uid", profile.getId());
            fields.put("isVerified", true);
            fields.put("updatedAt", FieldValue.serverTimestamp());
            if (!doc.exists()) {
                fields.put("createdAt", FieldValue.serverTimestamp());
                fields.put("profileComplete", false);
                fields.put("points", 0L);
                fields.put("visits", 0L);
            }
            tx.set(profile, fields, SetOptions.merge());
            return null;
        }).get();
        return new SignInResponse(true, email, auth.createCustomToken(user.getUid()));
    }

    static void validateLink(boolean exists, Boolean used, Timestamp expiry, String challenge,
            String verifier, Instant now) {
        if (!exists || !Boolean.FALSE.equals(used) || expiry == null
                || expiry.getSeconds() <= now.getEpochSecond() || challenge == null
                || !MessageDigest.isEqual(challenge.getBytes(StandardCharsets.UTF_8),
                        hash(verifier).getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL_LINK",
                    "Link invalid, expired, already used, or requested on another phone. Request a new email.");
        }
    }

    public static String hash(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record SignInResponse(boolean ok, String email, String customToken) {}
}
