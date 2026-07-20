package com.beanLoyal.backend.push;

/**
 * Result of recording a behavioral-interest signal.
 *
 * @param topInterest category with the highest accumulated score after the event.
 * @param categoryScore updated score for the submitted category.
 */
public record InterestEventResponse(String topInterest, long categoryScore) {
}
