package com.beanLoyal.backend.auth;

/** The sign-in email could not be handed to the mail provider. */
public class MailDeliveryException extends RuntimeException {
    public MailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
