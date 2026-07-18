package com.beanLoyal.backend.push;

import java.util.List;

/**
 * Outbound push-delivery boundary. Production uses Firebase Cloud Messaging; the interface keeps
 * campaign selection and idempotency testable without network calls.
 */
public interface PushGateway {

    /**
     * Send one FCM batch. Callers must keep {@code tokens} at or below FCM's 500-token multicast
     * limit. The returned indexes refer to positions in the submitted token list.
     *
     * @param title notification title.
     * @param message notification body.
     * @param tokens active FCM registration tokens; never logged.
     * @return accepted/rejected counts and permanently unregistered token indexes.
     * @throws Exception when FCM rejects the whole batch before per-token results exist.
     */
    DeliveryResult send(String title, String message, List<String> tokens) throws Exception;

    /** Per-batch FCM outcome. */
    record DeliveryResult(int successCount, int failureCount, List<Integer> unregisteredIndexes) {
    }
}
