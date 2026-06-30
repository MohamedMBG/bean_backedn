# Backend Architecture Hardening — 2026-06-29

**Owner:** Solo dev
**Trigger:** Architecture review of `BACKEND_IMPLEMENTATION_PLAN.md` surfaced three high-risk gaps to close before Phase 5.
**Phase impact:** Phase 0 decisions extended. Phase 2 scope unchanged. Phase 5/6 unblocked from one of two prerequisites.

---

## 1. Summary

Three changes shipped together because each one is cheap now and painful later:

1. Per-environment Firebase project isolation
2. `Idempotency-Key` contract locked
3. `/api/v1` namespace introduced via marker annotation

Plus supporting doc: `BUSINESS_RULES.md` created as the single source of truth for non-code decisions.

---

## 2. Change 1 — Per-Environment Firebase Projects

### What

Replaced single Firebase project with three: `beanloyal-dev`, `beanloyal-staging`, `beanloyal-prod`.

### How

Created Spring profile files:
- `backend/src/main/resources/application-dev.yaml`
- `backend/src/main/resources/application-staging.yaml`
- `backend/src/main/resources/application-prod.yaml`

Each binds `firebase.project-id` to a distinct project. Credentials path remains `FIREBASE_CREDENTIALS_PATH` per env (local path for dev, Render Secret File for staging/prod).

Base `application.yaml` updated:
- Added `spring.profiles.active: ${SPRING_PROFILES_ACTIVE:dev}` so missing env var still boots locally
- Added `firebase.project-id: ${FIREBASE_PROJECT_ID:}` placeholder

### Why

Original plan stated "single Firebase project, split per env later." Concrete risks:
- Staging test data written to same Firestore that serves prod users
- One bad rules deploy in staging immediately affects prod reads
- uid collisions impossible to reverse after merge (Firebase UIDs are project-scoped, so a real split later requires user re-creation)
- Service account leak in dev = prod compromise

Cost to do now: ~30 min in Firebase Console + 3 yaml files.
Cost to do later: data export/import, uid remap, user re-onboarding.

### Manual steps required from user

1. Firebase Console → create `beanloyal-dev`, `beanloyal-staging`, `beanloyal-prod`
2. Generate service account JSON per project
3. Local: place dev JSON, set `FIREBASE_CREDENTIALS_PATH` + `FIREBASE_PROJECT_ID=beanloyal-dev`
4. Render: upload staging + prod JSONs as Secret Files, set env vars per service

---

## 3. Change 2 — Idempotency Contract

### What

Locked `Idempotency-Key` header contract for all state-mutating endpoints. Spec lives in `docs/BUSINESS_RULES.md` §1.

### How

No code yet — Phase 5 will implement. Contract specifies:

- Header name: `Idempotency-Key` (UUID v4, client-generated)
- Required on: earn, redeem, cancel, birthday, cashier complete, admin writes
- Server key: `sha256(uid + ":" + idempotencyKey + ":" + path)`
- Storage: Firestore collection `idempotency/{key}` with `{requestHash, responseHash, responseBody, status, expiresAt}`
- TTL: 24h (Firestore TTL policy on `expiresAt`)
- Replay within TTL → return stored response, do not re-run business logic
- Same key + different body → 409 `IDEMPOTENCY_KEY_REUSED`
- Missing header on required route → 400 `IDEMPOTENCY_KEY_REQUIRED`

### Why

Mobile networks fail mid-request. Default client retry behavior = duplicate POST. Without idempotency:
- Earn QR scan retried = double points
- Redeem retried = double balance deduction or duplicate pending codes
- Birthday claim retried = silent skip works only because of `birthday_claims/{uid_year}` marker — every other endpoint lacks equivalent

Original plan mentioned "idempotency markers" abstractly. Different from a header contract: a marker only covers one specific dedup key (year, code id), header contract covers arbitrary retries of any operation. Need both.

24h TTL covers offline-then-recovered phone scenarios without bloating storage.

Body hash check catches client bug where the same key is reused with different payload (treat as error, not silent overwrite — that's the worst kind of bug to debug).

### What this unblocks

Phase 5 (`POST /api/v1/loyalty/earn`) can be implemented against a real contract instead of "TBD idempotency."

### What still blocks Phase 5

QR earn business rules — see `BUSINESS_RULES.md` §2.

---

## 4. Change 3 — `/api/v1` Namespace

### What

All authenticated routes now live under `/api/v1`. Implemented via marker annotation, not per-controller path repetition.

### How

Two new files:

**`backend/src/main/java/com/beanLoyal/backend/common/ApiV1.java`**
- Composed annotation: meta-annotated with `@RestController`
- Apply to feature controllers in place of `@RestController`
- Acts as both bean declaration AND version tag

**`backend/src/main/java/com/beanLoyal/backend/config/WebMvcConfig.java`**
- Implements `WebMvcConfigurer`
- `configurePathMatch` calls `addPathPrefix("/api/v1", HandlerTypePredicate.forAnnotation(ApiV1.class))`
- Spring rewrites paths at request mapping resolution — controller declares `@GetMapping("/loyalty/earn")`, actual URL = `/api/v1/loyalty/earn`

`HealthController` stays `@RestController` (no `@ApiV1`), so `/health` is not prefixed. `SecurityConfig` whitelist unchanged.

Endpoint catalog in `BACKEND_IMPLEMENTATION_PLAN.md` §10 rewritten to use `/api/v1/...` prefix.

### Why

Versioning is the cheapest insurance policy in API design when added before clients ship. Cost after clients ship = forced mobile rollout for any breaking change. Cost now = 2 files, zero per-controller burden.

Marker-annotation approach (vs `server.servlet.context-path` or `@RequestMapping("/api/v1")` per controller):
- Context path would prefix `/health` too — would break Render uptime checks unless health route is renamed
- Per-controller `@RequestMapping("/api/v1/...")` = repetition + drift risk
- Marker = one annotation per controller, version bump = swap to `@ApiV2`, old controllers keep serving v1 in parallel

### What this unblocks

Future breaking changes ship at `/api/v2` while v1 stays alive for ≥ 1 mobile release cycle. Mobile app does not have to upgrade in lockstep with backend.

---

## 5. New Documentation File

`docs/BUSINESS_RULES.md` created. Replaces scattered "TBD" markers in `BACKEND_IMPLEMENTATION_PLAN.md` Phase 0 open items. Sections:

1. Idempotency Contract — LOCKED
2. QR Earn Rules — TODO (blocks Phase 5)
3. Redemption Rules — TODO (blocks Phase 6)
4. Rate Limits — LOCKED (revisit Phase 11)
5. API Versioning — LOCKED
6. Environment Isolation — LOCKED
7. Decision Log — append-only

Reason for separate file: implementation plan describes *how* code is structured. Business rules describe *what the system does*. Mixing them = plan doc grows past skim length and both audiences (dev = me, future contractor = ?) lose context.

---

## 6. Plan Doc Updates

`docs/BACKEND_IMPLEMENTATION_PLAN.md` changes:

- §2 Decisions table — added rows for Firebase per-env, API versioning, idempotency
- §6 Configuration — added `FIREBASE_PROJECT_ID` env var
- §7 Phase Tracker — added Phase 0 rows: `BUSINESS_RULES.md` ✅, `/api/v1` ✅, per-env profiles ✅
- §10 Endpoint Catalog — all authenticated routes rewritten with `/api/v1` prefix

---

## 7. What Did NOT Change

- `SecurityConfig` — whitelist already correct (only health + actuator public, no `/api/v1` exemption needed)
- `HealthController` — stays at `/health`, no version prefix
- `FirebaseAdminConfig` — does not need to know about project ID (the credentials JSON contains it; Firebase Admin SDK reads from there)
- Existing `ApiError`, `CurrentUser`, `FirebaseAuthFilter` stub — untouched

---

## 8. Risks Introduced

| Risk | Severity | Mitigation |
|---|---|---|
| Three Firebase projects = 3× console maintenance burden | Low | Solo dev, accept |
| `@ApiV1` annotation forgotten on new controller → route serves at root | Medium | Add convention check in PR review; consider integration test asserting all `/api/**` routes start with `/api/v1` |
| `Idempotency-Key` Firestore collection grows unbounded if TTL policy not set in console | Medium | Document TTL setup in Phase 5 deploy checklist |
| Per-env profile file drift (e.g., logging key added to dev but not staging) | Low | Diff-review the three files when one changes |

---

## 9. Files Touched

### Created
- `backend/src/main/resources/application-dev.yaml`
- `backend/src/main/resources/application-staging.yaml`
- `backend/src/main/resources/application-prod.yaml`
- `backend/src/main/java/com/beanLoyal/backend/common/ApiV1.java`
- `backend/src/main/java/com/beanLoyal/backend/config/WebMvcConfig.java`
- `docs/BUSINESS_RULES.md`
- `docs/CHANGELOG_2026-06-29.md` (this file)

### Modified
- `backend/src/main/resources/application.yaml`
- `docs/BACKEND_IMPLEMENTATION_PLAN.md`

### Untouched (confirmed correct)
- `backend/src/main/java/com/beanLoyal/backend/config/SecurityConfig.java`
- `backend/src/main/java/com/beanLoyal/backend/config/FirebaseAdminConfig.java`
- `backend/src/main/java/com/beanLoyal/backend/health/HealthController.java`
- `backend/src/main/java/com/beanLoyal/backend/security/CurrentUser.java`
- `backend/src/main/java/com/beanLoyal/backend/security/FirebaseAuthFilter.java`
- `backend/src/main/java/com/beanLoyal/backend/common/ApiError.java`

---

## 10. Next Action

Phase 2 task 5: real `FirebaseAuthFilter` body. Unchanged by these architecture changes — filter is version-agnostic, runs before path prefixing.

---

# Addendum — Same day, later

## 11. `FirebaseAuthFilter` Completed

### What

Replaced the pass-through stub with a real verification flow.

### How

`backend/src/main/java/com/beanLoyal/backend/security/FirebaseAuthFilter.java`:
- Constructor-injects `FirebaseAuth` (from `FirebaseAdminConfig`) + `ObjectMapper`
- `shouldNotFilter` skips `/health` and `/actuator/**` — saves a Firebase Admin SDK call on every uptime probe
- `doFilterInternal` flow:
  1. Read `Authorization` header
  2. If missing or wrong prefix → continue chain unauthenticated (Spring rejects via `.authenticated()`)
  3. Strip `Bearer ` prefix
  4. `firebaseAuth.verifyIdToken(token)` → throws on expired / bad sig / wrong project
  5. Build `CurrentUser(uid, email, claims)` from `FirebaseToken`
  6. Wrap in `UsernamePasswordAuthenticationToken`, place in `SecurityContextHolder`
  7. Continue chain
- Catches `FirebaseAuthException` + `IllegalArgumentException` → 401 with `ApiError` JSON
- `writeUnauthorized` helper writes 401 + JSON `ApiError` so every auth failure has identical shape

### Why this design

- **Skip whitelist via `shouldNotFilter`, not via route checks** — keeps filter focused, avoids re-implementing `SecurityConfig` whitelist logic
- **Continue chain on missing header instead of short-circuit** — lets Spring's standard authorization path handle "no creds"; future entry point can format that response consistently
- **Never log raw token** — only `FirebaseAuthException.getAuthErrorCode()`. Token in logs = credential leak even if file access is restricted
- **`verifyIdToken(token, false)`** (revocation check OFF) — adds latency for Firebase backend round-trip; defer until Phase 7+ if revocation matters
- **No role parsing here** — claims go on `CurrentUser`, role checks live in `@PreAuthorize` / service layer

### Earlier bugs fixed in stub

- Self-typed field (`FirebaseAuthFilter firebaseAuthFilter`) → corrected to `FirebaseAuth firebaseAuth`
- Used `ApiResponse` (success wrapper) for 401 → semantically wrong, replaced with `ApiError`
- `shouldNotFilter` missing return → would not compile
- Unused import `java.lang.runtime.ObjectMethods` → removed

---

## 12. `GlobalExceptionHandler` Completed

### What

`@RestControllerAdvice` mapping every controller exception to a uniform `ApiError` JSON.

### How

`backend/src/main/java/com/beanLoyal/backend/common/GlobalExceptionHandler.java`:

| Exception | HTTP | ApiError code | Notes |
|---|---|---|---|
| `MethodArgumentNotValidException` | 400 | `VALIDATION_FAILED` | Aggregates field errors as `field: msg; field: msg` |
| `ConstraintViolationException` | 400 | `VALIDATION_FAILED` | For `@RequestParam` / `@PathVariable` constraints |
| `HttpMessageNotReadableException` | 400 | `MALFORMED_JSON` | Generic message — Jackson detail leaks internals |
| `IllegalArgumentException` | 400 | `BAD_REQUEST` | Message echoed (service-thrown, author-controlled) |
| `AccessDeniedException` | 403 | `FORBIDDEN` | Generic — never reveal which role would have passed |
| `NoHandlerFoundException` | 404 | `NOT_FOUND` | Requires yaml flips below |
| `HttpRequestMethodNotSupportedException` | 405 | `METHOD_NOT_ALLOWED` | Sets `Allow` header from supported methods |
| `Exception` (fallthrough) | 500 | `INTERNAL_ERROR` | Generic body, full stack logged at ERROR |

### Why

- **One response shape across the whole API** — mobile client parses one format for success + failure
- **500 message NEVER echoed** — stack/SQL/path leak risk; mapped to generic `"Internal error"` while logging full detail server-side
- **Validation messages ECHOED** — author-controlled via bean validation annotations, safe
- **403 deliberately vague** — listing required roles is an information leak useful to attackers

### Config changes

`backend/src/main/resources/application.yaml` added two flips:
```yaml
spring:
  mvc:
    throw-exception-if-no-handler-found: true
  web:
    resources:
      add-mappings: false
```

Spring Boot 3 default is to swallow unmapped routes and serve a whitelabel page. Without these flips, `NoHandlerFoundException` is never thrown, and the 404 handler above is unreachable. `add-mappings: false` disables the default static resource handler that would otherwise catch unmapped paths.

### Trade-off accepted

`add-mappings: false` means Spring will not serve `/static/**` resources from the JAR. Backend is API-only, so this is intended. If we ever serve static assets, the flip must be revisited.

---

## 13. Verification

`./gradlew.bat test` attempted.

- ✅ `compileJava` succeeded — all code compiles
- ⏳ Test execution failed at the Gradle worker layer: `ConnectException: Connection timed out` binding `127.0.0.1:30010`. This is a sandbox network restriction, not a code failure.
- No test sources exist yet (`compileTestJava UP-TO-DATE`, `processTestResources NO-SOURCE`), so there are no real test results to report.

Phase 11 will add the test suite; until then this verification step always reduces to "compiles cleanly."

---

## 14. Updated Files (Addendum)

### Modified
- `backend/src/main/java/com/beanLoyal/backend/security/FirebaseAuthFilter.java` — real verification body
- `backend/src/main/java/com/beanLoyal/backend/common/GlobalExceptionHandler.java` — 8 handlers
- `backend/src/main/resources/application.yaml` — 404 handler flips
- `docs/BACKEND_IMPLEMENTATION_PLAN.md` — tracker rows for `FirebaseAuthFilter`, `CurrentUser`, `GlobalExceptionHandler` → ✅

---

## 15. Phase 2 Remaining

- Bucket4j rate limit on sensitive routes
- Render deploy + `/health` smoke test

---

# Addendum 2

## 16. `AuthenticationEntryPoint` (inline)

### What

Added unified 401 body for the "no credentials at all" case directly in `SecurityConfig` as a lambda — no separate class.

### Why inline, not its own class

One usage, ~10 lines. A dedicated class would add a file, an import, and a name to remember, for zero reuse. If a second `SecurityFilterChain` ever appears (e.g. internal API), extract then.

### Behavior

| Case | Handler | Response |
|---|---|---|
| Invalid / expired / malformed token | `FirebaseAuthFilter` | 401 `ApiError(AUTH_INVALID_TOKEN \| AUTH_MALFORMED_TOKEN)` |
| No token at all | `SecurityConfig` entry point | 401 `ApiError(AUTH_REQUIRED)` |

Clients now see a consistent JSON envelope for every auth-related 401.

---

## 17. `RequestLoggingFilter`

### What

`backend/src/main/java/com/beanLoyal/backend/common/RequestLoggingFilter.java`. `OncePerRequestFilter` at `Ordered.HIGHEST_PRECEDENCE`. Generates UUID per request, stores in SLF4J MDC under `requestId`, echoes as `X-Request-ID` response header, logs one INFO line per request: `METHOD path -> status (Xms)`.

Honors client-supplied `X-Request-ID` when present so mobile crash reports and server logs can be correlated.

### Why no header redaction logic

Plan called for redacting `Authorization` and `*token*` headers. Easier: don't log any headers. Same outcome (zero credential leak), zero allow/deny list to maintain, zero regex to keep in sync with future header names.

If we ever need request bodies in logs for debugging, add a Spring `CommonsRequestLoggingFilter` behind a `dev`-profile-only bean — and treat it as a separate decision.

### Filter ordering

`HIGHEST_PRECEDENCE` so the MDC value exists before `FirebaseAuthFilter` runs. Side benefit: 401s from the auth filter also carry a `requestId`, so logs around bad-token attempts are correlated.

### Trade-off accepted

We log nothing inside the request handler about request inputs (params, body). When debugging a specific failed request, you have method+path+status+duration+requestId — but not the payload that broke it. Acceptable for MVP; revisit in Phase 11 if test failures are hard to reproduce from logs alone.

---

## 18. Phase 2 Remaining (Updated)

- Bucket4j rate limit on sensitive routes
- Render deploy + `/health` smoke test (blocked on user creating 3 Firebase projects)

## 19. Verification

`./gradlew.bat compileJava` → BUILD SUCCESSFUL. No test sources yet, full `test` task still hits sandbox socket restriction as before.
