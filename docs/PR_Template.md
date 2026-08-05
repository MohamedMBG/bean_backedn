## Summary

Briefly explain what this PR changes.

Example:
This PR adds the reward redemption endpoint with idempotency protection and Firestore transaction handling.

## Why

Explain the reason behind the change.

Example:
Cashiers need a backend-enforced redemption flow so point balances cannot be manipulated client-side.

## Changes Made

* Added redemption controller and service
* Added request validation and idempotency key handling
* Wrapped balance updates in a Firestore transaction
* Added unit tests for success, insufficient-points, and replay cases
* Updated BUSINESS_RULES.md and the implementation plan

## Screenshots / Demo

Add screenshots, videos, or before/after images if the PR changes the UI.

## How to Test

1. Step-by-step instructions a reviewer can follow.
2. Include the required role or token setup and any test data.
3. State the expected response or behavior at each step.

Example:
1. Start the backend with the dev profile.
2. Authenticate as a cashier and call `POST /api/rewards/redeem` with a valid reward ID.
3. Confirm the points balance decreases exactly once, even when the request is retried with the same idempotency key.

## Test Results

* Backend tests (`.\gradlew.bat test`): Passed / Failed / Not run (reason)
* Build (`.\gradlew.bat build`): Passed / Failed / Not run (reason)
* Manual test: Passed / Failed / Not run (reason)

## Performance / Security Notes

State any change to latency, Firestore read/write cost, rate limits, authentication, public endpoints, or validation. Write "None" if not applicable.

## Notes for Reviewers

Mention anything important reviewers should focus on.

Example:
Please review the validation logic and API error handling.

## Related Issues / Tasks

Closes #123
Related to #120
