# BeanLoyal — Android Implementation Plan (Customer + Admin)

**Date:** 2026-07-07
**Scope:** Migrate both Android apps onto the Spring Boot backend contract.

---

## The core finding

Both apps predate the backend and **do economy mutations directly in Firestore**. The backend + `firestore.rules` make that illegal. Once `firestore.rules` is deployed, every direct economy write from both apps **fails** (rules deny client writes to `points`/`visits`/`earn_codes`/`redeem_codes`/...).

This is not "add features" — it is a **migration**: move all economy operations from client Firestore transactions → backend REST, keep only reads-of-own-data direct in Firestore, and fix schema drift.

### Backend contract (source of truth)

- Base: `/api/v1`
- Auth: Firebase ID token — `Authorization: Bearer <token>`
- All writes: `Idempotency-Key: <uuidv4>` header
- Envelope: `ApiResponse` (`{ok:true, data:...}`) or `ApiError` (`{ok:false, code, message}`) + `Retry-After` on 429

| Endpoint | Role | Who calls | Body → Response |
|---|---|---|---|
| `POST /loyalty/earn` | any | Customer | `{code}` → `{pointsGranted,totalPoints,totalVisits}` |
| `POST /rewards/redeem` | any | Customer | `{rewardId}` → `{code,rewardId,cost,totalPoints,expiresAtEpochMs}` |
| `POST /rewards/redeem/cancel` | any (owner) | Customer | `{code}` → `{code,refunded,totalPoints}` |
| `POST /rewards/birthday` | any | Customer | — → `{pointsGranted,totalPoints,year}` |
| `POST /push/registerDevice` | any | Both | `{deviceId,fcmToken,platform}` → `{deviceId,lastSeenAt}` (no idempotency key) |
| `POST /cashier/redeem/complete` | **cashier** | Admin | `{code}` → `{code,rewardName,status}` |
| `POST /admin/earn-codes` | **admin** | Admin | `{points}` → created code |
| `POST /admin/earn-codes/{id}/revoke` | **admin** | Admin | — |
| `POST /admin/users/{uid}/points-adjustment` | **admin** | Admin | `{delta,reason}` |
| `GET /admin/users/search?email=\|phone=` | **admin** | Admin | user list |
| `GET /admin/users/{uid}/activity?limit=` | **admin** | Admin | activity list |
| `GET /admin/audit?limit=` | **admin** | Admin | audit list |

### Reads that stay direct-Firestore (allowed by rules)

- `users/{uid}` — own profile (points, visits, birthday, ...)
- `users/{uid}/activities/*` — own feed
- `rewards_catalog/*`, `menu_items/*` — any signed-in user

Everything else (`earn_codes`, `redeem_codes`, `devices`, `audit`, `birthday_claims`, `idempotency`) = **no client access at all**. Admin app reads of those must move to the backend (currently read direct).

---

## Schema drift — must fix (biggest risk)

Client/admin field names ≠ backend. After migration, read models must match what the backend writes.

| Collection | App uses now | Backend writes | Action |
|---|---|---|---|
| `users/{uid}/activities` | `delta`, `ts`, `desc`, `status` | `pointsDelta`, `createdAt`, `refId`, `balanceAfter` | Customer `ActivityRepository`/`ActivityEvent` reparse; `orderBy("ts")` → `orderBy("createdAt")` |
| `rewards_catalog` | customer `redeemPoints`, admin `costPoints` | `cost`, `name`, `active`, `category`, `imageUrl` | Both apps reparse |
| `redeem_codes` | `userUid`, `costPoints`, `type=REDEEM`, `status:ACTIVE` | `uid`, `cost`, `status:pending`, `expiresAt` | Client stops reading; admin stops writing |
| `earn_codes` | `orderNo`, `amountMAD`, `validForSec`, `status:pending/redeemed`, `nonce` | id=code, `points`, `status:active/used`, `expiresAt` | Admin stops minting direct |

Canonical activity `type` values: `earn | redeem | cancel | expire | birthday | adjust`.

---

## Shared foundation (build once per app, reuse)

1. **Auth'd Retrofit client** — OkHttp interceptor: `getIdToken(false)` (blocking `Tasks.await` inside interceptor), inject `Authorization: Bearer`. On 401, one retry with `getIdToken(true)` (force refresh — needed after role grant, §5b). Customer `ApiClient` has no interceptor today; admin has **no** REST client at all (only `InboxRepository` uses http).
2. **Idempotency helper** — generate `UUID.randomUUID()` per logical write, **persist across network retries** of the same action (store in the ViewModel/repo call, not per-HTTP-retry) so a retry replays instead of re-charging. Header on writes only.
3. **Error envelope + mapping** — parse `{ok,code,message}`; map `code` → user string. Handle 429 `RATE_LIMITED`/`VISIT_COOLDOWN` (respect `Retry-After`), 422 `INSUFFICIENT_POINTS`, 409 `*_ALREADY_*`/`REDEEM_PENDING_LIMIT`, 410 expired.
4. **BuildConfig flavors** dev/staging/prod → `API_BASE_URL` + matching `google-services.json` per Firebase project (§6). Customer already reads `BuildConfig.API_BASE_URL`; verify flavors exist. Admin: add.
5. **Retrofit `ApiService` interface** matching the table above — typed DTOs, not `Map`.

---

## Customer app plan

Rip legacy Firestore economy, route through backend. Keep profile/activity/catalog reads.

- **Earn (Scan)** — delete `ScanRepository.executeEarnTransaction`. Scan QR → extract code → `POST /loyalty/earn {code}`. Map errors `EARN_CODE_NOT_FOUND/EXPIRED/ALREADY_USED`, `VISIT_COOLDOWN`, `EARN_CODE_INVALID_FORMAT`. Update UI from `totalPoints/totalVisits` (or let the `users/{uid}` snapshot listener refresh).
- **Redeem** — delete `RewardsRepository.submitRedemption` + `ScanRepository.executeSpendTransaction`. `POST /rewards/redeem {rewardId}` → show returned `code` + countdown from `expiresAtEpochMs` (15 min, §3.1). Handle `INSUFFICIENT_POINTS`(422), `REWARD_INACTIVE`(410), `REDEEM_PENDING_LIMIT`(409 — one pending at a time).
- **Cancel pending** — new: `POST /rewards/redeem/cancel {code}` → refund. No cancel today; add it (needed to escape `REDEEM_PENDING_LIMIT`).
- **Birthday** — `ApiService.claimBirthdayReward` hits wrong path `api/rewards/birthday` with a body. Fix → `POST /rewards/birthday` (no body, Idempotency-Key). Handle `BIRTHDAY_NOT_SET/NOT_TODAY/ALREADY_CLAIMED`. Requires `users/{uid}.birthday` as `yyyy-MM-dd` (§3.7) — ensure profile edit writes that format.
- **Rewards catalog** — `RewardsRepository.fetchRewards`: `redeemPoints` → `cost`, image field → `imageUrl`. Keep direct read.
- **Activity feed** — `ActivityRepository`/`ActivityEvent`: reparse to `pointsDelta/createdAt/refId/balanceAfter`, `orderBy("createdAt")`.
- **Profile** — `UserRepository` stays (rules allow own read + profile-field writes). Confirm writes touch only allow-listed keys (`displayName,name,birthday,phone,email,photoUrl,fcmOptIn`) or the write is rejected.
- **Device/FCM** — `TokenRegistrar` body wrong: sends `{token,role,platform,topics,points}` to `/api/push/registerDevice`. Fix → `POST /api/v1/push/registerDevice {deviceId,fcmToken,platform}`. `deviceId` = stable per-install id (generate once, store in app-private prefs). Drop `role/topics/points`. Auth guard already correct (skips call without bearer).
- **Stale DTOs** — delete `EmailRequest/EmailResponse/VerifyRequest/VerifyResponse` + `api/register`/`api/verify` methods (no backend routes exist).

---

## Admin app plan

Admin has **no REST client** and mints codes directly in Firestore with legacy schema. All economy → backend.

- **Cashier: create earn code** — delete `CashierRepository.createVoucherTransaction` (mints `earn_codes` with `orderNo/amountMAD/validForSec`). Backend `POST /admin/earn-codes {points}` takes a **fixed point value**, not MAD-with-ratio. Product decision: (a) admin enters points directly, or (b) keep MAD→points math client-side then send computed `points`. Requires **admin** role. Display returned code as QR for the customer to scan.
- **Cashier: complete redeem** — delete `RedeemingRepository.createRedeemCode` (deducts points + writes `redeem_codes` direct). Correct flow: customer creates the pending code in *their* app; cashier scans it → `POST /cashier/redeem/complete {code}`. Requires **cashier** role. Handle `REDEEM_NOT_FOUND`(404), `REDEEM_NOT_PENDING`(409, incl. expired).
- **Points adjustment** — `POST /admin/users/{uid}/points-adjustment {delta,reason}` (reason required). New admin screen.
- **User search / activity / audit** — replace direct `users` queries (`RedeemingRepository.searchUserBy*`) with `GET /admin/users/search`, `/users/{uid}/activity`, `/audit`. Direct reads of `audit` are rules-denied anyway.
- **Rewards admin** — `RewardsAdminRepository`: **no backend write endpoint exists** for catalog CRUD (backend-owned, client writes rules-denied). Options: (a) keep catalog editing out of MVP, or (b) **backend gap — add `POST/PUT /admin/rewards`**. Flag to owner. Reads: `costPoints` → `cost`.
- **Role tokens** — after any role grant, admin client must `getIdToken(true)` (§5b) or `hasRole` checks 403 for up to 1h.
- **Create-cashier** — assigning `role:cashier` needs `setCustomUserClaims` (Admin SDK, backend-only). No endpoint → **backend gap**, or use a one-off script/console for MVP. Flag to owner.

---

## Backend gaps to flag (block parts of admin)

1. No catalog CRUD endpoint (`rewards_catalog` writes) — admin RewardsAdmin can't function through backend.
2. No cashier-provisioning endpoint (set `role` claim) — CreateCashier can't function.
3. No customer-facing "unregister device" on logout (§8.5 open) — logged-out users may keep receiving pushes.
4. `menu_items` / `promotions` collections read by apps but backend never writes them — confirm ownership.

---

## Sequencing

1. **Foundation both apps** — Retrofit + auth interceptor + idempotency + error mapping + flavors. *(no behavior change yet)*
2. **Align read-model field names** — activity, catalog. Apps still read Firestore, just correct fields.
3. **Customer economy → REST** — earn, redeem, cancel, birthday, device. **Then deploy `firestore.rules`** — must come after customer stops direct writes or the app breaks.
4. **Admin economy → REST** — create earn code, complete redeem, adjust, search/audit.
5. **Resolve backend gaps** — catalog CRUD, cashier provisioning — or scope out of MVP.
6. **Verify** — earn → redeem → complete round-trip across both apps against staging.

**Hard cutover:** deploying `firestore.rules` is one-shot — do it only once **both** apps' economy paths route through the backend, else direct-write screens 403 mid-session.

---

## Open product decisions

1. Earn code value = **points-direct** vs **MAD-ratio** (client computes points)?
2. Are **catalog admin** and **cashier provisioning** in MVP? Both need new backend endpoints.
