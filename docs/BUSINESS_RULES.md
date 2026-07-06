# BeanLoyal Business Rules

**Date:** 2026-06-29
**Owner:** Solo dev
**Status:** §1 + §2 LOCKED & IMPLEMENTED. §3 fully IMPLEMENTED: redeem creation (§3.1, §3.4, §3.5, §3.6, §3.8) + birthday (§3.7) in Phase 6; refund-on-cancel (§3.2), refund-on-expiry (§3.3), cashier complete + expiration job in Phase 7. Activity feed (§11) canonical schema locked in `ActivityService`, written by cancel/expire; earn/redeem/birthday adoption = Phase 8.

---

## 1. Idempotency Contract (LOCKED — IMPLEMENTED 2026-07-02)

Every state-mutating endpoint (POST/PUT/DELETE under `/api/v1`) MUST honor `Idempotency-Key` header.

Implemented by `common/IdempotencyService.execute(...)`: endpoints pass their business logic as
`TransactionalWork`; the record write and the economy write share one Firestore transaction.
`IdempotencyException` maps to 400 `IDEMPOTENCY_KEY_REQUIRED` / 409 `IDEMPOTENCY_KEY_REUSED`.

### Header
- Name: `Idempotency-Key`
- Value: client-generated UUID v4
- Required on: `POST /api/v1/loyalty/earn`, `POST /api/v1/rewards/redeem`, `POST /api/v1/rewards/redeem/cancel`, `POST /api/v1/rewards/birthday`, `POST /api/v1/cashier/redeem/complete`, all `/api/v1/admin/**` writes
- Optional on: `POST /api/v1/push/registerDevice` (device id already idempotent)

### Server behavior
1. Compute key = `sha256(uid + ":" + idempotencyKey + ":" + path)`
2. Look up Firestore `idempotency/{key}`
3. If absent: process request, then write `{status, responseHash, responseBody, createdAt, expiresAt}` atomically with business write
4. If present and within TTL: return stored `responseBody` + original status. Do NOT re-run business logic.
5. If present but request body hash differs from stored requestHash: return 409 `IDEMPOTENCY_KEY_REUSED`
6. If absent header on required route: return 400 `IDEMPOTENCY_KEY_REQUIRED`

### TTL
- 24 hours. Firestore TTL policy on `expiresAt` field handles cleanup.

### Storage shape
```
idempotency/{key}
  uid: string
  path: string
  requestHash: string         // sha256 of canonical request body
  responseHash: string
  responseBody: string        // JSON serialized
  status: number              // HTTP status
  createdAt: timestamp
  expiresAt: timestamp        // createdAt + 24h
```

### Why
- Mobile retries on flaky network = guaranteed duplicate POSTs. Without this contract = double point grants, double redemptions.
- 24h TTL covers offline-then-recovered scenarios without bloating collection.
- Body hash check catches client bug where same key is reused with different payload (treat as error, not silent overwrite).

---

## 2. QR Earn Rules (LOCKED 2026-06-30 — IMPLEMENTED 2026-07-03)

These are the MVP defaults. Owner can override per rule by amending this file and bumping the lock date.

Implemented by `loyalty/LoyaltyController` (`POST /api/v1/loyalty/earn`) + `loyalty/LoyaltyService` +
`loyalty/EarnCodeService`, wrapped in `IdempotencyService.execute(...)` and rate-limited via
`RateLimitPolicy.EARN`. Every rule below (§2.1–§2.8) matches the shipped code exactly.

### 2.1 Points per earn code

**Stored per code, set at creation time. Default value = 1 point.**

- `earn_codes/{id}.points` is the source of truth
- Admin creates codes with any positive integer value via `POST /api/v1/admin/earn-codes`
- No per-user tier multipliers in MVP

**Why:** lets the owner print receipts at different point values without code changes. Tier multipliers can layer on later without changing the earn flow.

### 2.2 Earn code TTL

**24 hours from creation.**

- `earn_codes/{id}.expiresAt = createdAt + 24h`
- Scan after `expiresAt` → 410 `EARN_CODE_EXPIRED`
- Firestore TTL policy on `expiresAt` auto-deletes expired docs after 7 more days (keeps reporting window without bloating collection)

**Why:** receipts printed today are scanned today. Long enough for a forgetful customer to scan tomorrow morning, short enough to bound lost-receipt fraud.

### 2.3 Single-use vs multi-use

**Single-use globally. First valid scan burns the code.**

- `earn_codes/{id}.status` transitions `active → used` atomically inside the earn transaction
- Second scan of a used code → 409 `EARN_CODE_ALREADY_USED`

**Why:** matches the printed-receipt model. A table-sticker / multi-use model would be a different code type (deferred — out of scope for MVP).

### 2.4 Visit cooldown

**30 minutes between successful earns for the same uid.**

- Backend reads `users/{uid}.lastEarnAt`
- If `now - lastEarnAt < 30 min` → 429 `VISIT_COOLDOWN`
- Cooldown applies across ALL codes, not per-code

**Why:** prevents a customer handing fresh receipts to friends for rapid-fire scanning. A real customer is unlikely to complete two distinct purchases inside 30 minutes.

**Owner override knob:** `firebase remote config → earn.visitCooldownMinutes` (Phase 5+ if dynamic tuning matters).

### 2.5 Earn code format

**10-character alphanumeric, uppercase + digits, ambiguous chars excluded.**

- Alphabet: `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (32 chars — no `0/O/1/I/L`)
- Length 10 → ~10^15 keyspace → collision-free at MVP scale
- Stored as-is in `earn_codes/{id}` where `id` IS the code
- Validated against alphabet on inbound before Firestore lookup (reject 400 `BAD_REQUEST` early)

**Why:** human-readable so a cashier can type it manually when QR is unscannable. JWT signing is rejected — the server hits Firestore for status anyway, so a signature adds nothing beyond CPU cost.

### 2.6 Geofencing

**Skipped for MVP.**

- No GPS check on earn
- Single shop, customer is physically present to receive receipt
- Revisit if multi-shop or franchise model arrives

**Why:** adds Android location permission prompts, false negatives in basements/parking lots, and complexity for zero MVP fraud reduction (the printed receipt itself is the proof of presence).

### 2.7 Visit counter

**Every successful earn increments `users/{uid}.visits` by 1.**

- No "first scan per day" branching
- Same Firestore transaction as the points update

**Why:** receipt = real purchase = real visit. Branching by day adds calendar/timezone code for negligible accuracy gain — owner can derive daily metrics from activity log if needed.

### 2.8 Error code reference

| Code | HTTP | Trigger |
|---|---|---|
| `EARN_CODE_NOT_FOUND` | 404 | No `earn_codes/{id}` doc |
| `EARN_CODE_EXPIRED` | 410 | `now > expiresAt` |
| `EARN_CODE_ALREADY_USED` | 409 | `status != active` |
| `VISIT_COOLDOWN` | 429 | `now - lastEarnAt < 30 min` |
| `EARN_CODE_INVALID_FORMAT` | 400 | Fails alphabet/length check |
| `USER_NOT_FOUND` | 404 | `users/{uid}` doc does not exist (schema assumption violated — see `LoyaltyService` Javadoc) |
| `RATE_LIMITED` | 429 | Bucket4j per-uid or per-IP cap hit (see §4) |

---

## 3. Redemption Rules (LOCKED 2026-06-30)

MVP defaults. Override per rule by amending this file and bumping the lock date.

### 3.1 Pending redeem code TTL

**15 minutes from creation.**

- `redeem_codes/{id}.expiresAt = createdAt + 15min`
- Expiration job (issue #16) flips `status: pending → expired` every 5 min for codes past `expiresAt`
- Expired code seen by cashier → 409 `REDEEM_NOT_PENDING`

**Why:** customer redeemed at the counter — if the cashier hasn't processed in 15 min, the customer has left or the QR scanner is broken. Shorter = friction (slow service = expiry). Longer = points locked up + risk of cashier serving a stale code from yesterday.

### 3.2 Refund on user cancel

**YES — full refund of original points cost, atomic with status flip.**

- `redeem_codes/{id}.status: pending → cancelled` + `users/{uid}.points += originalCost` in single Firestore transaction
- Activity log written with `type: cancel`, `pointsDelta: +cost`, `refId: codeId`

**Why:** user explicitly chose to cancel. Holding their points captive = bad UX. No fraud surface — they earned those points fairly, they still have them.

### 3.3 Refund on expiry

**YES — full refund, atomic with status flip.**

- Expiration job applies same transaction shape as cancel: status + balance + activity log
- Activity log uses `type: expire` instead of `cancel`

**Why:** expiry is a system/cashier fault, not a user fault. Penalizing the customer = bad. Auto-refund + audit log so the owner can spot patterns (repeated expirations from a specific cashier = training signal).

### 3.4 Multiple pending codes per user

**NO — at most 1 pending redeem code per user at any time.**

- Backend reads `redeem_codes` where `uid = caller AND status = pending`
- If a pending code exists → 409 `REDEEM_PENDING_LIMIT`
- User must complete or cancel current code before requesting another

**Why:** prevents UI confusion ("which code do I show?"), prevents cashier confusion ("did this user pay for both?"), keeps fraud surface bounded.

**Tradeoff:** customer wanting 2 different rewards must redeem-complete-redeem sequentially. Acceptable for MVP — coffee shop redemptions are one-item events.

### 3.5 Insufficient points response

**422 Unprocessable Entity with `ApiError(INSUFFICIENT_POINTS)`.**

- Body: `{ ok: false, code: "INSUFFICIENT_POINTS", message: "Not enough points for this reward" }`
- No partial redeem, no "pay difference" fallback
- Balance left untouched

**Why:** 422 = "request understood, semantically invalid" — exact REST fit. 400 is for malformed; 200-with-error breaks the one-shape contract.

### 3.6 Inactive reward mid-flow

**Honor existing pending codes. Reject new redemption attempts.**

- New redeem against `rewards_catalog/{id}.active = false` → 410 `REWARD_INACTIVE`
- Pending code created while reward was active → cashier can still complete it
- User can still cancel and get refund (per §3.2)

**Why:** user already paid points and got a code in good faith. Voiding mid-flow = punishing them for the owner's catalog edit. Blocking only NEW attempts protects the owner's intent without retroactively breaking trust.

### 3.7 Birthday reward — once per calendar year

**Reward = fixed 50 points, credited to `users/{uid}.points` (locked 2026-07-02, owner decision).**

- Not a catalog reward / redeem code — a direct points grant, same shape as an earn-code credit (§2.1).
- Implemented by `rewards/BirthdayRewardService.claim(...)`, called from `POST /api/v1/rewards/birthday`
  inside `IdempotencyService.execute(...)` — the points update and the `birthday_claims` write share
  one Firestore transaction.

**Schema assumption (not defined elsewhere at time of writing):** `users/{uid}.birthday` is stored as
an ISO-8601 date string (`yyyy-MM-dd`). Chosen because the Feb-29 edge case below only makes sense as
a month/day comparison. Revisit if the Android client uses a different representation.

**Calendar year, keyed by `birthday_claims/{uid}_{year}`.**

- Key derived from current calendar year in UTC
- Doc presence = already claimed this year
- First claim of year → create doc + grant reward atomically
- Second claim same year → 409 `BIRTHDAY_ALREADY_CLAIMED`

**Why:** matches the existing `birthday_claims/{uid_year}` schema in the plan. Rolling 365 days adds leap-year edge cases and a "claim slid from Jan to Dec" UX trap. Calendar year is how customers actually think about birthdays.

**Edge case accepted:** user born Feb 29 — server treats Feb 28 as their birthday in non-leap years. Documented; no special handling.

### 3.8 Reward catalog shape (referenced by §3.5–3.6)

`rewards_catalog/{id}`:
```
{
  name: string,
  cost: number,           // points required
  active: boolean,        // owner toggle
  category: 'drink' | 'food' | 'merch' | 'other',
  imageUrl: string,
  createdAt: timestamp,
  updatedAt: timestamp
}
```

### 3.9 Error code reference

| Code | HTTP | Trigger |
|---|---|---|
| `REWARD_NOT_FOUND` | 404 | No `rewards_catalog/{id}` doc |
| `REWARD_INACTIVE` | 410 | `active = false` on new redeem attempt (existing pending unaffected) |
| `INSUFFICIENT_POINTS` | 422 | `users/{uid}.points < reward.cost` |
| `REDEEM_PENDING_LIMIT` | 409 | User already has a pending code |
| `REDEEM_NOT_FOUND` | 404 | No `redeem_codes/{id}` doc (cancel/complete) |
| `REDEEM_NOT_OWNED` | 403 | Cancel attempted by uid that didn't create the code |
| `REDEEM_NOT_PENDING` | 409 | Status is completed / cancelled / expired |
| `BIRTHDAY_NOT_TODAY` | 422 | Today's date != user's birthday |
| `BIRTHDAY_NOT_SET` | 422 | `users/{uid}.birthday` is null |
| `BIRTHDAY_ALREADY_CLAIMED` | 409 | `birthday_claims/{uid}_{year}` exists |

---

## 4. Rate Limits (LOCKED, revisit Phase 11)

Bucket4j in-memory. Per-IP and per-uid where authenticated.

| Route | Per-IP | Per-UID |
|---|---|---|
| `POST /api/v1/loyalty/earn` | 30/min | 10/min |
| `POST /api/v1/rewards/redeem` | 20/min | 5/min |
| `POST /api/v1/rewards/birthday` | 20/min | 3/day |
| `POST /api/v1/cashier/**` | 60/min | 60/min |
| `POST /api/v1/admin/**` | 60/min | 60/min |
| `GET /health` | unlimited | n/a |

429 response = `ApiError(code: "RATE_LIMITED", message: "Too many requests")` + `Retry-After` header.

**Client IP resolution:** Render terminates TLS at its own reverse proxy, so `request.getRemoteAddr()` is always Render, never the client. `ClientIpResolver` reads the LAST entry of `X-Forwarded-For` instead of the first — with exactly one trusted proxy (Render) in front of this backend, that last entry is the one Render itself appended (the real peer IP), while any earlier entries are attacker-settable by the client. Revisit this resolver if an additional CDN/WAF is ever placed in front of Render.

---

## 5. API Versioning (LOCKED)

- All authenticated routes live under `/api/v1`
- Breaking changes ship at `/api/v2` while v1 stays alive for ≥ 1 mobile release cycle
- Non-breaking additions stay in v1
- `/health` + `/actuator/**` are NOT versioned

---

## 5b. Roles & Custom Claims (LOCKED 2026-06-30)

### Model
- Single role per user, stored as Firebase custom claim `role` (string).
- Recognized values: `cashier`, `admin`. Unknown / missing = regular user.
- Backend maps `role: X` → Spring authority `ROLE_X` (uppercased) in `FirebaseAuthFilter`.
- Endpoints guard with `@PreAuthorize("hasRole('CASHIER')")` or `hasRole('ADMIN')`.
- Denial → 403 `FORBIDDEN` via `GlobalExceptionHandler` (no role enumeration leak).

### How to assign a role

**Option A — Firebase Admin SDK (preferred, scriptable):**
```java
FirebaseAuth.getInstance().setCustomUserClaims(
    uid,
    Map.of("role", "cashier")  // or "admin"
);
```
Run from a one-off `CommandLineRunner` or future admin endpoint (#20).

**Option B — Node CLI:**
```bash
node -e "
const admin = require('firebase-admin');
admin.initializeApp({ credential: admin.credential.cert(require('./sa.json')) });
admin.auth().setCustomUserClaims('USER_UID', { role: 'cashier' })
  .then(() => console.log('done')).catch(console.error);
"
```

### Critical client behavior

After granting/revoking a role, the client's existing ID token still carries the OLD claims (good for up to 1 hour). Force a refresh on the client to ship new claims immediately:

```kotlin
FirebaseAuth.getInstance().currentUser?.getIdToken(true)  // forceRefresh = true
```

Without forced refresh, role changes apply lazily on the next token rotation.

### Why single-role string, not roles array

MVP has 3 categories: regular / cashier / admin. A role array adds set-intersection logic for zero MVP benefit. If a 4th category (e.g. `manager` = cashier + limited admin) is needed, switch to a `roles` array claim then — change is localized to `FirebaseAuthFilter.extractAuthorities`.

---

## 6. Environment Isolation (LOCKED)

- Three Firebase projects: `beanloyal-dev`, `beanloyal-staging`, `beanloyal-prod`
- Each backend env binds to exactly one Firebase project via `FIREBASE_PROJECT_ID` + `FIREBASE_CREDENTIALS_PATH`
- Service accounts NEVER shared across envs
- Android app picks backend URL via `BuildConfig.BACKEND_BASE_URL` per flavor (dev/staging/prod)

---

## 7. Decision Log

| Date | Decision | Reason |
|---|---|---|
| 2026-06-29 | Locked idempotency header contract | Mobile retry on flaky network was unmitigated risk |
| 2026-06-29 | Added `/api/v1` namespace | Future breaking change without forced mobile rollout |
| 2026-06-29 | Split Firebase per env | Staging pollution risk to prod |
| 2026-06-30 | Locked §2 QR earn rules | Unblocked issue #12 (earn endpoint); MVP defaults chosen, owner overridable |
| 2026-06-30 | Locked §3 redemption rules | Unblocked issues #13/#14/#15/#11 (redeem, cancel, cashier complete, birthday); MVP defaults chosen, owner overridable |
| 2026-06-30 | Locked §5b roles/claims model | Single-role string, `ROLE_<UPPER>` authority mapping; unblocks #15 (cashier complete) + #20 (admin endpoints) |
| 2026-07-02 | Locked §3.7 birthday reward = fixed 50 points | §3.7 said "grant reward" without specifying what/how much; owner chose a direct points grant over a catalog-reward redemption to avoid coupling Phase 4 to the Phase 6 catalog |
