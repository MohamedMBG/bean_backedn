# BeanLoyal Backend

[![Sourcery](https://img.shields.io/badge/Sourcery-AI%20Code%20Review-blueviolet)](https://sourcery.ai/)

Spring Boot backend for BeanLoyal. Uses Firebase Admin SDK for auth.

Every PR is automatically reviewed by [Sourcery AI](https://sourcery.ai/). Config: `.sourcery.yaml` at repo root.

## Stack

- Java 21
- Spring Boot 3.5.x (web, security, validation, actuator)
- Firebase Admin SDK 9.3.0
- Gradle (Kotlin DSL)
- Micrometer + Prometheus

## Requirements

- JDK 21
- Firebase project + service-account credentials

## Configuration

Firebase credentials supplied via env vars (see `src/main/resources/application.yaml`):

| Variable | Description |
|---|---|
| `FIREBASE_CREDENTIALS_PATH` | Path to service-account JSON file |
| `FIREBASE_CREDENTIALS_JSON` | Raw service-account JSON string |

Set one. Never commit credential files — `.gitignore` blocks `*serviceAccount*.json`, `*firebase-adminsdk*.json`, `*credentials*.json`.

## Run

### Customer email signup and sign-in

The Android client uses `POST /api/v1/auth/register` with `{email, challenge}`,
then `POST /api/v1/auth/verify` with `{token, verifier}`. The verifier is 32 random
bytes encoded as unpadded base64url; the challenge is its SHA-256 digest encoded
the same way. Verification returns `{ok, email, customToken}`, which the app
exchanges through Firebase `signInWithCustomToken`.

Configure these environment variables on the backend before testing delivery:

| Variable | Value |
|---|---|
| `SPRING_MAIL_HOST` | SMTP provider hostname (required to enable the mail sender) |
| `SMTP_PORT` | SMTP port; defaults to `587` |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | SMTP provider credentials |
| `AUTH_EMAIL_FROM` | Sender address authorized by the SMTP provider |
| `AUTH_EMAIL_PUBLIC_URL` | `https://bean-backend-ejzg.onrender.com/api/v1/auth/email` for the current deployment |

STARTTLS and SMTP authentication default to enabled (`SMTP_STARTTLS`, `SMTP_AUTH`).
Firebase Admin credentials must belong to the same project as the Android app
and support Auth account management, custom-token signing, and Firestore access.
Missing email configuration returns `503 EMAIL_UNAVAILABLE`.

Links expire after 15 minutes, are single-use, and require the verifier retained
on the requesting phone. The HTTPS landing page offers an explicit “Open BeanLoyal”
link; email scanners do not consume the token. New users receive a verified Auth
account and a server-created profile with zero points/visits. Returning users keep
their balances and profile. Disabled users and accounts with a staff role are rejected.
Firestore client rules must deny access to `email_signins` (the customer rules'
default deny does this). Configure Firestore TTL on `email_signins.expiresAt` for
storage cleanup; expiry enforcement does not depend on TTL deletion.

After deploying the backend and installing the updated APK, enter a new email,
open its email link on the same phone, tap “Open BeanLoyal”, and confirm the app
opens the profile tab. Sign out and repeat to verify the same UID and balances
are retained. Reopening a consumed link or using it on another phone must fail.
Automated tests mock Firebase and SMTP; this device/inbox check is still required
to validate production credentials and delivery.

```bash
./gradlew bootRun
```

## Build

```bash
./gradlew build
```

Artifact: `build/libs/backend-0.0.1-SNAPSHOT.jar`

## Test

```bash
./gradlew test
```

## Endpoints

- `GET /health` — health check
- Actuator under `/actuator/*`
- Prometheus metrics: `/actuator/prometheus`

`POST /api/v1/admin/cashiers` requires an admin Firebase bearer token and an
`Idempotency-Key` header. Reuse the same key when retrying the same email/password/name request;
completed replays return `Idempotency-Replayed: true` and never create another Auth account or
audit entry.

## Operational limits

### Recovering customer rewards

`GET /api/v1/rewards/redeem/pending` requires a Firebase bearer token and returns
`{ok:true,data:{pending:null}}` when the caller has no pending reward. Otherwise
`pending` contains `code`, `rewardId`, `rewardName`, `cost`, `status`, and
`expiresAtEpochMs`. The owner is always taken from the verified token; no client
UID is accepted. Responses use `Cache-Control: no-store`.

Status is `pending` while the QR is usable, or `awaiting_refund` after expiry until
cancellation or the existing expiry job refunds it. Lookup does not deduct points
or change reward state. It reuses the existing `redeem_codes(uid,status)` index.
Completed, cancelled, and refunded rewards are excluded.

The Android Rewards screen checks on opening, returning to the tab, refreshing,
and failed redemption/cancellation responses. “My Rewards” retrieves the code
again after dialog dismissal. Expired codes show refund status with the QR hidden.
Deploy the backend before the updated app. Device verification: request a reward,
dismiss and reopen through My Rewards, restart and reopen Rewards, and interrupt
the create response before reopening. Each must restore the same code without
another points deduction. Complete/cancel the reward and reopen to confirm it is
no longer offered; verify an expired pending code displays refund status.

- Admin analytics accepts a maximum 31-day range and 10,000 events per metric. A larger request
  returns `ANALYTICS_RANGE_TOO_LARGE` rather than partial totals.
- The expired-redemption job processes up to 100 codes every five minutes; a backlog drains over
  later runs.
- Local rate-limit buckets idle for two days are evicted hourly. Limits are per application
  instance; use a shared store before deploying multiple instances.

See [`../docs/BACKEND_SCALABILITY.md`](../docs/BACKEND_SCALABILITY.md) for rationale, verification,
and the scale-out plan.
