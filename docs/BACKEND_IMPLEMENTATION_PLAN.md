# BeanLoyal Backend Implementation Plan

**Date:** 2026-06-28
**Owner:** Solo dev
**Source roadmap:** `beanLoyal_customer/docs/HYBRID_BACKEND_IMPLEMENTATION_ROADMAP.md`
**Stack:** Spring Boot 3.5.16 · Java 21 · Firebase Admin SDK 9.3.0 · Render (host)
**Backend root:** `beanloyal_backend/backend/`

---

## 1. Scope

Backend owns every trusted loyalty operation. Android app reads from Firestore but never writes economy data directly.

Backend responsibilities:
- Verify Firebase ID token on every protected endpoint
- Own points, visits, QR earn, reward redemption, birthday rewards, device registration
- Write Firestore read models + activity logs
- Enforce idempotency, anti-fraud, rate limits
- Role-protected cashier/admin endpoints
- Audit log every admin action

Firestore role: mobile read model + backend-owned operational store. PostgreSQL postponed until reporting/admin needs justify it.

---

## 2. Decisions (Phase 0)

| Decision | Choice |
|---|---|
| Backend repo location | `beanloyal_backend/` (separate repo from Android app) |
| Backend language | Java (not Kotlin — Initializr skeleton chose Java) |
| Build system | Gradle Kotlin DSL |
| Hosting (dev/test) | Render free tier |
| Hosting (prod) | TBD — Render paid or Cloud Run |
| Database | Firestore only. Postgres deferred |
| Firebase projects | Three projects: `beanloyal-dev`, `beanloyal-staging`, `beanloyal-prod`. Locked 2026-06-29 |
| API versioning | `/api/v1` namespace via `@ApiV1` marker + `WebMvcConfig` path prefix. Locked 2026-06-29 |
| Idempotency | `Idempotency-Key` header required on state-mutating routes. See `BUSINESS_RULES.md` §1. Locked 2026-06-29 |
| Credential delivery | `FIREBASE_CREDENTIALS_PATH` env var → Render Secret File at `/etc/secrets/sa.json` |
| Auth strategy | Firebase ID token verified via Firebase Admin SDK |
| API style | REST + JSON. Stateless. No sessions |
| Email provider | TBD (SendGrid/Resend candidates) |
| QR earn rules | TBD — see Phase 0 open items |
| Redemption rules | TBD — see Phase 0 open items |

### Phase 0 open items (block Phase 5/6)
- Points per earn code
- Earn code expiry
- Single-use vs multi-use earn codes
- Visit cooldown (same user repeated scan)
- Pending redeem code expiry duration
- Refund on cancel? Refund on expiry?

**Action:** Write decision note in `docs/BUSINESS_RULES.md` before Phase 5 starts.

---

## 3. Target Architecture

```
┌────────────────┐       ┌──────────────────────┐       ┌──────────────┐
│  Android app   │──────▶│  Spring Boot backend │──────▶│  Firestore   │
│  (Java/XML)    │  HTTPS│   (Render)           │  Admin│  read model  │
│                │ +Bearer│  Firebase Admin SDK │  SDK  │  + ops data  │
└────────────────┘       └──────────────────────┘       └──────────────┘
       │                          │
       │ Firebase Auth            │ FCM push
       ▼                          ▼
┌────────────────┐       ┌──────────────────────┐
│ Firebase Auth  │       │  FCM                 │
└────────────────┘       └──────────────────────┘
```

Trust boundary: backend = only writer of points/visits/QR/redeem/activity. Android = reader + safe profile writer.

---

## 4. Package Layout

```
backend/src/main/java/com/beanLoyal/backend/
├── BackendApplication.java                  ✅ exists
├── config/
│   ├── FirebaseAdminConfig.java             ✅ done
│   └── SecurityConfig.java                  ⏳ Phase 2
├── security/
│   ├── FirebaseAuthFilter.java              ⏳ Phase 2
│   └── CurrentUser.java                     ⏳ Phase 2
├── common/
│   ├── ApiError.java                        ⏳ Phase 2
│   ├── ApiResponse.java                     ⏳ Phase 2
│   ├── GlobalExceptionHandler.java          ⏳ Phase 2
│   └── RequestLoggingFilter.java            ⏳ Phase 2
├── health/
│   └── HealthController.java                ⏳ Phase 2
├── rewards/
│   ├── RewardsController.java               ⏳ Phase 4, 6
│   ├── BirthdayRewardService.java           ⏳ Phase 4
│   ├── RewardRedemptionService.java         ⏳ Phase 6
│   └── RedeemCodeService.java               ⏳ Phase 6, 7
├── loyalty/
│   ├── LoyaltyController.java               ⏳ Phase 5
│   ├── LoyaltyService.java                  ⏳ Phase 5
│   └── EarnCodeService.java                 ⏳ Phase 5
├── cashier/
│   └── CashierController.java               ⏳ Phase 7
├── admin/
│   └── AdminController.java                 ⏳ Phase 10
├── audit/
│   └── AuditService.java                    ⏳ Phase 5+
├── push/
│   └── DeviceController.java                ⏳ Phase 9
└── jobs/
    └── ExpiredRedemptionJob.java            ⏳ Phase 7
```

---

## 5. Build & Dependencies

`backend/build.gradle.kts` current state:
- Spring Boot starters: web, security, validation, actuator
- Firebase Admin SDK 9.3.0 ✅
- Lombok
- Prometheus registry
- Spring DevTools (dev only)
- spring-security-test

Planned additions:
- `bucket4j-spring-boot-starter` — rate limiting (Phase 2)
- `org.springframework.boot:spring-boot-starter-data-redis` — only if rate limit needs distributed store (likely skip)

---

## 6. Configuration

### `application.yaml` (base)
```yaml
spring:
  application:
    name: beanloyal-backend

server:
  port: ${PORT:8080}

firebase:
  credentials:
    path: ${FIREBASE_CREDENTIALS_PATH:}
    json: ${FIREBASE_CREDENTIALS_JSON:}

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true

logging:
  pattern:
    level: "%5p [${spring.application.name},%X{requestId:-}]"
```

### Profiles
- `application-dev.yaml` — verbose logs, local Firebase path
- `application-staging.yaml` — Render staging service
- `application-prod.yaml` — Render prod service, stricter rate limits

### Environment variables
| Var | Purpose | Where |
|---|---|---|
| `PORT` | HTTP port | Render injects |
| `FIREBASE_PROJECT_ID` | Project ID — `beanloyal-dev` / `beanloyal-staging` / `beanloyal-prod` | Per env |
| `FIREBASE_CREDENTIALS_PATH` | Service account file (env-specific) | `/etc/secrets/sa.json` on Render, local path locally |
| `SPRING_PROFILES_ACTIVE` | Profile selector | `dev` / `staging` / `prod` |

---

## 7. Phase Tracker

Status: ✅ done · ⏳ in progress · ⬜ not started · ⛔ blocked

| Phase | Item | Status |
|---|---|---|
| 0 | Project decisions | ✅ idempotency ✅, versioning ✅, env split ✅, QR rules ✅, redemption rules ✅ |
| 0 | `BUSINESS_RULES.md` created | ✅ |
| 0 | API versioning `/api/v1` (`@ApiV1` + `WebMvcConfig`) | ✅ |
| 0 | Per-env Spring profiles + Firebase project IDs | ✅ (config wired; user must create 3 Firebase projects + supply credentials) |
| 0 | Spring Boot skeleton | ✅ |
| 0 | Backend location chosen | ✅ |
| 1 | Firestore rules locked | ⬜ |
| 1 | API key restrictions | ⬜ |
| 1 | Rules tests / verification | ⬜ |
| 2 | Firebase Admin SDK dep | ✅ |
| 2 | `FirebaseAdminConfig` | ✅ |
| 2 | `ApiError` + `ApiResponse` | ✅ |
| 2 | `HealthController` `GET /health` | ✅ |
| 2 | `SecurityConfig` SecurityFilterChain | ✅ |
| 2 | `FirebaseAuthFilter` | ✅ verifyIdToken + SecurityContext + ApiError 401 |
| 2 | `CurrentUser` argument resolver | ✅ wired via `@AuthenticationPrincipal` (Spring default resolver) |
| 2 | `GlobalExceptionHandler` | ✅ validation, constraint, JSON, illegal-arg, access-denied, 404, 405, fallthrough |
| 2 | Structured request logging | ✅ `RequestLoggingFilter` — requestId UUID in MDC, `X-Request-ID` header, one INFO line per request |
| 2 | Token redaction in logs | ✅ achieved by never logging headers — no allow/deny list to maintain |
| 2 | `AuthenticationEntryPoint` (unified 401 shape) | ✅ inline lambda in `SecurityConfig` returns `ApiError(AUTH_REQUIRED)` |
| 2 | Rate limit on sensitive routes | ⬜ |
| 2 | Render deploy of skeleton + `/health` | ⬜ |
| 2 | Local AI agent documentation/progress instructions | ✅ `AGENTS.md` + `CLAUDE.md` created, ignored by Git, and updated with documentation + planning rules on 2026-06-29 |
| 3 | Android `BuildConfig.BACKEND_BASE_URL` | ⬜ |
| 3 | Android API DTO package | ⬜ |
| 3 | Authenticated request helper | ⬜ |
| 4 | `POST /api/rewards/birthday` | ⬜ |
| 5 | `POST /api/loyalty/earn` | ⬜ |
| 6 | `POST /api/rewards/redeem` | ⬜ |
| 7 | `POST /api/rewards/redeem/cancel` | ⬜ |
| 7 | `POST /api/cashier/redeem/complete` | ⬜ |
| 7 | Expiration job | ⬜ |
| 7 | Cashier role | ⬜ |
| 8 | Activity canonical schema | ⬜ |
| 9 | Device registration cleanup | ⬜ |
| 10 | Admin endpoints | ⬜ |
| 11 | Backend tests | ⬜ |
| 11 | Android tests | ⬜ |
| 11 | Manual QA pass | ⬜ |
| 12 | Render staging deploy | ⬜ |
| 12 | Render prod deploy | ⬜ |
| 12 | Monitoring + alerts | ⬜ |

---

## 8. Phase 2 — Backend Foundation (CURRENT FOCUS)

### Remaining tasks (ordered)

1. **`ApiError` record** — error response shape `{ok, code, message}`
2. **`ApiResponse<T>` record** — success wrapper `{ok: true, data}` (optional, choose convention)
3. **`HealthController`** — public `GET /health` → 200
4. **`SecurityConfig`**
   - `SecurityFilterChain` bean
   - permit `/health`, `/actuator/health`, `/actuator/info`
   - require auth elsewhere
   - disable CSRF (stateless REST)
   - stateless session policy
   - register `FirebaseAuthFilter` before `UsernamePasswordAuthenticationFilter`
5. **`FirebaseAuthFilter`** (`OncePerRequestFilter`)
   - extract `Authorization: Bearer <token>`
   - call `firebaseAuth.verifyIdToken(token)`
   - build `CurrentUser` (uid, email, claims)
   - set `Authentication` in `SecurityContextHolder`
   - 401 + `ApiError` JSON on missing/invalid
6. **`CurrentUser`** — record holding `uid`, `email`, `Map<String,Object> claims`
7. **`@AuthenticationPrincipal CurrentUser` resolver** — controllers inject current user
8. **`GlobalExceptionHandler`** (`@RestControllerAdvice`)
   - map `MethodArgumentNotValidException` → 400
   - map `AccessDeniedException` → 403
   - map `IllegalArgumentException` → 400
   - map fallthrough `Exception` → 500
   - all return `ApiError` JSON
9. **`RequestLoggingFilter`** (or use `CommonsRequestLoggingFilter`)
   - generate request ID, put in MDC
   - log endpoint, status, duration
   - redact `Authorization` + any `*token*` header
10. **Rate limiting** — `bucket4j-spring-boot-starter`, apply per-IP + per-uid on sensitive routes
11. **Render deploy** — push branch, configure secret file, hit `https://<service>.onrender.com/health`

### Phase 2 acceptance
- `GET /health` returns 200 with no auth
- Test protected endpoint returns 401 without token
- Same endpoint returns 200 with valid Firebase ID token
- No Firebase tokens or FCM tokens in logs
- App boots on Render with secret file mounted

---

## 9. Phase Dependency Graph

```
Phase 0 ─┬─▶ Phase 1 (Firestore rules)
         │
         └─▶ Phase 2 (backend foundation) ──┬─▶ Phase 3 (Android API plumbing)
                                            │
                                            ├─▶ Phase 4 (birthday)  ◀── Phase 3
                                            │
                                            └─▶ Phase 5 (QR earn) ◀── Phase 1, 3
                                                       │
                                                       ▼
                                                Phase 6 (redemption)
                                                       │
                                                       ▼
                                                Phase 7 (cancel/expire/cashier)
                                                       │
                              ┌────────────────────────┼──────────────┐
                              ▼                        ▼              ▼
                       Phase 8 (activity)       Phase 9 (device)   Phase 10 (admin)
                              │                        │              │
                              └────────────────────────┼──────────────┘
                                                       ▼
                                              Phase 11 (tests/QA)
                                                       │
                                                       ▼
                                              Phase 12 (deploy)
```

Critical path: 0 → 2 → 3 → 5 → 6 → 7 → 11 → 12.

---

## 10. Endpoint Catalog (Target State)

### Public
- `GET /health` — liveness
- `GET /actuator/health` — Spring health (probes)
- `GET /actuator/prometheus` — metrics scrape (internal only in prod)

### Authenticated (Firebase ID token required, all under `/api/v1`)
- `POST /api/v1/loyalty/earn` — QR earn
- `POST /api/v1/rewards/birthday` — birthday reward claim
- `POST /api/v1/rewards/redeem` — redeem reward → pending code
- `POST /api/v1/rewards/redeem/cancel` — cancel pending
- `POST /api/v1/push/registerDevice` — register FCM token

### Cashier role
- `POST /api/v1/cashier/redeem/complete` — mark redeem code completed

### Admin role
- `POST /api/v1/admin/earn-codes` — create earn code
- `POST /api/v1/admin/earn-codes/{codeId}/revoke` — revoke
- `GET /api/v1/admin/users/search` — search by email/phone
- `GET /api/v1/admin/users/{uid}/activity` — recent activity
- `POST /api/v1/admin/users/{uid}/points-adjustment` — manual adjustment (reason required)
- `GET /api/v1/admin/audit` — view audit logs

---

## 11. Firestore Collections (Target)

| Collection | Owner | Notes |
|---|---|---|
| `users/{uid}` | Backend writes economy; client writes profile fields only | Read model + profile |
| `users/{uid}/activities/{id}` | Backend only | Canonical schema (Phase 8) |
| `earn_codes/{id}` | Backend only | Status: active/used/expired/revoked |
| `redeem_codes/{id}` | Backend only | Status: pending/completed/cancelled/expired |
| `birthday_claims/{uid_year}` | Backend only | Idempotency marker |
| `devices/{id}` | Backend only | FCM tokens, lastSeen, disabled |
| `menu_items/{id}` | Backend writes, client reads | Catalog read model |
| `rewards_catalog/{id}` | Backend writes, client reads | Catalog read model |
| `audit/{id}` | Backend only, append-only | Admin/cashier actions |

---

## 12. Render Deployment

### Service setup
- **Type:** Web Service
- **Runtime:** Java (or Docker if needed)
- **Build command:** `./gradlew clean build -x test`
- **Start command:** `java -jar build/libs/backend-0.0.1-SNAPSHOT.jar`
- **Plan:** Free (dev/test)
- **Region:** US-East (closest to default Firebase region)

### Env vars (Render dashboard)
```
FIREBASE_CREDENTIALS_PATH=/etc/secrets/sa.json
SPRING_PROFILES_ACTIVE=staging
```
`PORT` injected automatically.

### Secret files
- Filename: `sa.json`
- Contents: full Firebase service account JSON (Firebase Console → Project Settings → Service Accounts → Generate new private key)

### Known limitations
- Free tier sleeps after 15 min idle → ~30-60s cold start + ~2-3s Firebase init
- No persistent disk on free tier → pipe logs to external (Logtail/Better Stack) if retention needed
- Single instance only → bucket4j in-memory rate limit fine

### Deployment checklist (per release)
- [ ] `./gradlew test` passes locally
- [ ] `./gradlew bootJar` produces runnable jar
- [ ] No service account file committed
- [ ] `.gitignore` covers `sa.json`, `*firebase-adminsdk*.json`
- [ ] Render env vars set
- [ ] Render Secret File uploaded
- [ ] Deploy → check logs for "Firebase Admin SDK initialized"
- [ ] `curl https://<service>.onrender.com/health` → 200
- [ ] `curl https://<service>.onrender.com/api/anything` → 401

---

## 13. Security Rules

### Always
- Verify Firebase ID token on every `/api/**` route (except explicit public list)
- Use Firebase Admin SDK only — never trust client-supplied uid
- Derive `uid` from verified token only
- Apply transactions/idempotency on every point mutation
- Audit log every admin/cashier action

### Never
- Log `Authorization` header
- Log Firebase ID token, custom token, FCM token, verification token
- Log full request body for sensitive endpoints
- Commit service account JSON
- Allow Android to write `points`, `visits`, `isVerified`, QR/redeem status, activity logs, device ownership
- Trust user-supplied uid in request body
- Skip rate limit on auth or redemption routes

---

## 14. Logging Standard

### Required fields per request
- `requestId` (UUID, generated by filter, returned in `X-Request-ID` response header)
- `method`
- `path`
- `status`
- `durationMs`
- `uid` (after auth, if available)

### Redaction
- `Authorization` header → `***`
- Headers/body fields matching `(?i)token|secret|password|key` → `***`

### Log levels
- `INFO` — request completion, business events (earn, redeem, cashier complete)
- `WARN` — auth failures, rate limit triggers, expired codes
- `ERROR` — uncaught exceptions, Firebase SDK failures, infra errors

---

## 15. Testing Strategy

### Phase 2 tests (foundation)
- `FirebaseAuthFilter` — missing token → 401, invalid → 401, valid mock → 200
- `GlobalExceptionHandler` — validation error → 400 with `ApiError` shape
- `HealthController` — 200 with no auth

### Phase 4-7 tests (economy)
- Birthday: first claim, duplicate, missing birthday, not birthday
- Earn: valid, used, expired, not found, rate-limited
- Redemption: enough points, insufficient, inactive reward, cancel refund, expire refund
- Cashier complete: valid role, normal user rejected, expired rejected, completed rejected

### Test approach
- Use `@SpringBootTest` with mocked `FirebaseAuth` bean
- Avoid hitting real Firebase in unit tests
- Single integration test against Firebase emulator (if time permits)

---

## 16. Risk Register

| Risk | Severity | Mitigation |
|---|---|---|
| Firestore rules deployed before Android migration breaks app | High | Stage rules; migrate endpoints first |
| Token verification bug accepts invalid tokens | High | Tests for missing/invalid/valid |
| Duplicate point grants from network retries | High | Transactions + idempotency markers |
| Render free tier cold start frustrates testing | Medium | Use uptime pinger or upgrade to starter plan |
| Service account JSON leaked in git | High | `.gitignore` + pre-commit hook |
| Business rules undefined → Phase 5 blocked | High | Write `BUSINESS_RULES.md` before Phase 5 |
| Bucket4j in-memory rate limit broken if Render scales | Low | Free tier = 1 instance; revisit on paid |
| Firebase Admin SDK init logs leak credentials | Medium | Verify no `credentialsJson` value logged |

---

## 17. Definition of Done (Backend MVP)

- [ ] All Phase 2 acceptance criteria met
- [ ] Endpoints in section 10 (authenticated + cashier) implemented
- [ ] Firestore rules block direct client mutation of economy fields
- [ ] Idempotent birthday + QR earn + redemption
- [ ] Cashier role enforced
- [ ] Activity canonical schema written by all economy endpoints
- [ ] Device registration backend-owned
- [ ] No secrets in logs (manual log review)
- [ ] Backend deployed to Render staging
- [ ] Android pointed at staging URL passes manual QA
- [ ] Production deploy + rollback plan documented

---

## 18. Out of Scope (For Now)

- PostgreSQL migration — deferred until reporting/admin needs justify
- Admin web UI — minimal endpoint set only (Phase 10)
- Multi-store / multi-tenant — single-shop assumption
- i18n / localization
- Payments / pricing
- Email/SMS sending pipeline (only addressed if business rules require)
- Mobile push beyond device registration + FCM delivery
- Refresh token rotation strategy beyond Firebase defaults
- Distributed rate limiting (single-instance Render)

---

## 19. Next Actions (Immediate)

Completed:
- ✅ `common/ApiError.java`
- ✅ `health/HealthController.java`
- ✅ `config/SecurityConfig.java` — permit health, require auth elsewhere, stateless, CSRF off, filter wired before `UsernamePasswordAuthenticationFilter`
- ✅ `security/CurrentUser.java` — record `(uid, email, claims)`

Remaining:
1. Build real `security/FirebaseAuthFilter` body
   - Extract `Authorization: Bearer <token>`
   - `firebaseAuth.verifyIdToken(token)`
   - Construct `CurrentUser` from `FirebaseToken`
   - Set `UsernamePasswordAuthenticationToken` in `SecurityContextHolder`
   - 401 + `ApiError` JSON on missing/invalid
2. `common/GlobalExceptionHandler` (`@RestControllerAdvice`) — map validation/access/illegal/fallthrough to `ApiError`
3. `common/RequestLoggingFilter` — requestId in MDC, redact `Authorization` + `*token*` headers
4. Smoke test boot locally with `FIREBASE_CREDENTIALS_PATH` set
5. Bucket4j rate limit on sensitive routes
6. Render service + Secret File
7. Deploy skeleton, verify `/health` works publicly
8. Write `docs/BUSINESS_RULES.md` — QR + redemption rules (unblocks Phase 5/6)

---

## 20. References

- Roadmap: `beanLoyal_customer/docs/HYBRID_BACKEND_IMPLEMENTATION_ROADMAP.md`
- Architecture: `beanLoyal_customer/docs/HYBRID_FIREBASE_BACKEND_PLAN.md`
- Firebase Admin SDK: https://github.com/firebase/firebase-admin-java
- Render Java docs: https://render.com/docs/deploy-spring-boot
- Spring Security ref: https://docs.spring.io/spring-security/reference/
