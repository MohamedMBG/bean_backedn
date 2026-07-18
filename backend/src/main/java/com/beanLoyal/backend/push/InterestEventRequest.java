package com.beanLoyal.backend.push;

/**
 * Customer behavioral-interest event produced when a menu category or item is selected.
 *
 * @param category client-visible menu category; normalized and validated by {@link InterestService}.
 */
public record InterestEventRequest(String category) {
}
