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

## Operational limits

- Admin analytics accepts a maximum 31-day range and 10,000 events per metric. A larger request
  returns `ANALYTICS_RANGE_TOO_LARGE` rather than partial totals.
- The expired-redemption job processes up to 100 codes every five minutes; a backlog drains over
  later runs.
- Local rate-limit buckets idle for two days are evicted hourly. Limits are per application
  instance; use a shared store before deploying multiple instances.

See [`../docs/BACKEND_SCALABILITY.md`](../docs/BACKEND_SCALABILITY.md) for rationale, verification,
and the scale-out plan.
