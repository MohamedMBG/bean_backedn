package com.beanLoyal.backend.auth;

import com.beanLoyal.backend.common.ApiException;
import com.google.api.core.ApiFutures;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import com.google.firebase.auth.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailAuthServiceTest {
    Firestore db = mock(Firestore.class);
    FirebaseAuth auth = mock(FirebaseAuth.class);
    SignInMailer mailer = mock(SignInMailer.class);
    DocumentReference link = mock(DocumentReference.class);
    DocumentReference profile = mock(DocumentReference.class);
    DocumentSnapshot linkDoc = mock(DocumentSnapshot.class);
    DocumentSnapshot profileDoc = mock(DocumentSnapshot.class);
    Transaction tx = mock(Transaction.class);
    UserRecord user = mock(UserRecord.class);
    EmailAuthService service;
    String verifier = "v".repeat(43);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        when(mailer.isAvailable()).thenReturn(true);
        service = new EmailAuthService(db, auth, mailer, "hello@example.com",
                "https://example.com/api/v1/auth/email");
        CollectionReference links = mock(CollectionReference.class);
        CollectionReference users = mock(CollectionReference.class);
        when(db.collection("email_signins")).thenReturn(links);
        when(db.collection("users")).thenReturn(users);
        when(links.document(anyString())).thenReturn(link);
        when(users.document("customer")).thenReturn(profile);
        when(profile.getId()).thenReturn("customer");
        when(link.set(any())).thenReturn(ApiFutures.immediateFuture(null));
        when(link.delete()).thenReturn(ApiFutures.immediateFuture(null));
        when(tx.get(link)).thenReturn(ApiFutures.immediateFuture(linkDoc));
        when(tx.get(profile)).thenReturn(ApiFutures.immediateFuture(profileDoc));
        when(db.runTransaction(any(Transaction.Function.class))).thenAnswer(call -> {
            try {
                return ApiFutures.immediateFuture(((Transaction.Function<?>) call.getArgument(0)).updateCallback(tx));
            } catch (Exception error) {
                return ApiFutures.immediateFailedFuture(error);
            }
        });
        when(linkDoc.exists()).thenReturn(true);
        when(linkDoc.getBoolean("used")).thenReturn(false);
        when(linkDoc.getTimestamp("expiresAt")).thenReturn(Timestamp.ofTimeSecondsAndNanos(Instant.now().plusSeconds(900).getEpochSecond(), 0));
        when(linkDoc.getString("challenge")).thenReturn(EmailAuthService.hash(verifier));
        when(linkDoc.getString("email")).thenReturn("customer@example.com");
        when(user.getUid()).thenReturn("customer");
        when(user.getCustomClaims()).thenReturn(Map.of());
        when(auth.getUserByEmail(anyString())).thenReturn(user);
        when(auth.createCustomToken("customer")).thenReturn("firebase-custom-token");
    }

    @Test
    void sendsLinkWithoutCreatingAnUnverifiedAccount() throws Exception {
        service.register("customer@example.com", EmailAuthService.hash(verifier));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq("hello@example.com"), eq("customer@example.com"), anyString(), body.capture());
        assertThat(body.getValue()).contains("https://example.com/api/v1/auth/email#");
        verifyNoInteractions(auth);
    }

    @Test
    void newCustomerGetsVerifiedProfileAndCustomToken() throws Exception {
        FirebaseAuthException missing = mock(FirebaseAuthException.class);
        when(missing.getAuthErrorCode()).thenReturn(AuthErrorCode.USER_NOT_FOUND);
        when(auth.getUserByEmail(anyString())).thenThrow(missing);
        when(auth.createUser(any())).thenReturn(user);
        assertThat(service.verify("t".repeat(43), verifier).customToken()).isEqualTo("firebase-custom-token");
        verify(auth).createUser(any());
        verify(auth).updateUser(any());
        verify(tx).update(link, "used", true);
        verify(tx).set(eq(profile), argThat((Map<String, Object> fields) ->
                Boolean.TRUE.equals(fields.get("isVerified")) && Long.valueOf(0).equals(fields.get("points"))
                && Boolean.FALSE.equals(fields.get("profileComplete"))), any(SetOptions.class));
    }

    @Test
    void returningCustomerKeepsBalancesAndProfile() throws Exception {
        when(profileDoc.exists()).thenReturn(true);
        assertThat(service.verify("t".repeat(43), verifier).ok()).isTrue();
        verify(auth, never()).createUser(any());
        verify(tx).set(eq(profile), argThat((Map<String, Object> fields) ->
                !fields.containsKey("points") && !fields.containsKey("visits")
                && !fields.containsKey("profileComplete")), any(SetOptions.class));
    }

    @Test
    void wrongDeviceCannotConsumeLink() {
        assertThatThrownBy(() -> service.verify("t".repeat(43), "wrong"))
                .isInstanceOf(ApiException.class);
        verify(tx, never()).update(eq(link), anyString(), any());
        verifyNoInteractions(auth);
    }

    @Test
    void expiredAndReplayedLinksAreRejected() {
        for (boolean used : new boolean[]{false, true}) {
            when(linkDoc.getBoolean("used")).thenReturn(used);
            when(linkDoc.getTimestamp("expiresAt")).thenReturn(Timestamp.ofTimeSecondsAndNanos(
                    used ? Instant.now().plusSeconds(900).getEpochSecond() : 0, 0));
            assertThatThrownBy(() -> service.verify("t".repeat(43), verifier)).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(auth);
    }

    @Test
    void staffAndDisabledAccountsCannotUseCustomerSignIn() throws Exception {
        when(user.getCustomClaims()).thenReturn(Map.of("role", "admin"));
        assertThatThrownBy(() -> service.verify("t".repeat(43), verifier)).isInstanceOf(ApiException.class);
        when(user.getCustomClaims()).thenReturn(Map.of());
        when(user.isDisabled()).thenReturn(true);
        assertThatThrownBy(() -> service.verify("t".repeat(43), verifier)).isInstanceOf(ApiException.class);
        verify(auth, never()).createCustomToken(anyString());
    }

    @Test
    void mailFailureDeletesUndeliveredLink() {
        doThrow(new MailDeliveryException("unavailable", null))
                .when(mailer).send(anyString(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> service.register("customer@example.com", EmailAuthService.hash(verifier)))
                .isInstanceOf(ApiException.class);
        verify(link).delete();
    }
}
