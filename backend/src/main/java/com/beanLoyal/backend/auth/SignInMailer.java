package com.beanLoyal.backend.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Sends the sign-in link over whichever transport the deployment has.
 *
 * Brevo's HTTPS API wins over SMTP when a key is present: hosts commonly block
 * outbound traffic to ports 25/465/587 (Render does so on free instances), which
 * makes SMTP time out while ordinary HTTPS still works.
 */
@Service
public class SignInMailer {
    private static final String BREVO_SEND_URL = "https://api.brevo.com/v3/smtp/email";

    private final ObjectProvider<JavaMailSender> smtp;
    private final String brevoApiKey;
    private final RestClient http;

    public SignInMailer(ObjectProvider<JavaMailSender> smtp,
            @Value("${auth.email.brevo-api-key:}") String brevoApiKey,
            RestClient.Builder http) {
        this.smtp = smtp;
        this.brevoApiKey = brevoApiKey == null ? "" : brevoApiKey.trim();
        this.http = http.build();
    }

    /** False when neither a Brevo key nor an SMTP sender is configured. */
    public boolean isAvailable() {
        return !brevoApiKey.isBlank() || smtp.getIfAvailable() != null;
    }

    /** @throws MailDeliveryException when the provider rejected or never received the message. */
    public void send(String from, String to, String subject, String text) {
        if (!brevoApiKey.isBlank()) {
            sendOverHttp(from, to, subject, text);
            return;
        }
        JavaMailSender sender = smtp.getIfAvailable();
        if (sender == null) {
            throw new MailDeliveryException("No mail transport configured", null);
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        try {
            sender.send(message);
        } catch (MailException e) {
            throw new MailDeliveryException("SMTP send failed", e);
        }
    }

    private void sendOverHttp(String from, String to, String subject, String text) {
        // The sender address must be verified in the Brevo account or the API answers 400.
        Map<String, Object> body = Map.of(
                "sender", Map.of("email", from),
                "to", List.of(Map.of("email", to)),
                "subject", subject,
                "textContent", text);
        try {
            http.post()
                    .uri(BREVO_SEND_URL)
                    .header("api-key", brevoApiKey)
                    .header("accept", "application/json")
                    .header("content-type", "application/json")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            // The message carries the provider's response body, so it must not reach the client.
            throw new MailDeliveryException("Brevo send failed", e);
        }
    }
}
