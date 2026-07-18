package com.beanLoyal.backend.push;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Firebase Cloud Messaging implementation of {@link PushGateway}. The Firebase client is resolved
 * lazily so the Spring test profile can load without service-account credentials; production calls
 * use the default {@code FirebaseApp} initialized by {@code FirebaseAdminConfig}.
 */
@Service
public class FirebasePushGateway implements PushGateway {

    /** Send one notification to at most 500 device tokens and classify unregistered tokens. */
    @Override
    public DeliveryResult send(String title, String message, List<String> tokens) throws Exception {
        if (tokens.isEmpty()) return new DeliveryResult(0, 0, List.of());
        if (tokens.size() > 500) throw new IllegalArgumentException("FCM multicast batch exceeds 500 tokens");

        MulticastMessage payload = MulticastMessage.builder()
                .setNotification(Notification.builder().setTitle(title).setBody(message).build())
                .addAllTokens(tokens)
                .build();
        BatchResponse response = FirebaseMessaging.getInstance().sendEachForMulticast(payload);
        List<Integer> unregistered = new ArrayList<>();
        List<SendResponse> results = response.getResponses();
        for (int i = 0; i < results.size(); i++) {
            SendResponse result = results.get(i);
            if (!result.isSuccessful() && result.getException() != null
                    && result.getException().getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
                unregistered.add(i);
            }
        }
        return new DeliveryResult(response.getSuccessCount(), response.getFailureCount(), unregistered);
    }
}
