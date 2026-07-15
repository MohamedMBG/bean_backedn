# Backend Scalability Guardrails

**Updated:** 2026-07-15

This document records the operational limits enforced in the Spring Boot backend. They keep a
busy or delayed instance predictable without changing loyalty balances, authentication, or public
API authorization.

## Rate-limit bucket retention

`RateLimitService` keeps Bucket4j buckets in process. It creates one for each policy and IP/UID
combination, so it cannot retain every distinct caller forever.

- An hourly scheduled sweep removes entries not touched for two days.
- Two days exceeds the birthday UID policy's one-day refill interval, so cleanup cannot reset a
  still-relevant quota.
- Enforcement remains per instance. Move to a shared Redis-backed store before horizontal scaling.

Why: an unbounded `ConcurrentHashMap` turns unique-IP traffic into permanent memory growth. Idle
eviction bounds growth while retaining existing behavior for active callers.

## Redemption-expiry work

The five-minute expiration job reads at most **100** pending expired redeem codes per run, ordered
by `expiresAt`.

- A backlog drains over later runs.
- Each code still uses its own Firestore transaction, preventing one failure or concurrent
  cancellation from blocking or double-refunding other codes.
- The existing Firestore composite index on `(status, expiresAt)` remains required.

Why: after a scheduler outage, an unbounded read could exhaust memory, Firestore reads, or the
scheduler thread. The cap establishes a known maximum per run.

## Analytics reads

`GET /api/v1/admin/analytics` accepts a maximum **31-day** window. Each raw-event query and the
new-client aggregate are limited to **10,001** rows: 10,000 usable rows plus a sentinel that
detects overflow.

- At 10,000 or fewer records, the result is complete.
- With more than 10,000 records for any metric, the API returns HTTP 400
  `ANALYTICS_RANGE_TOO_LARGE`; it never returns partial totals.
- Clients should request a shorter range. Add write-time daily aggregates before raising limits.

Why: Firestore has no server-side group-by for these dashboard calculations. Loading every matching
document made latency and cost grow without a limit. Explicit failure preserves metric correctness.

## Impact

| Area | Result |
|---|---|
| Authentication, roles, loyalty transactions | Unchanged |
| Rate limits | Same policies; idle local buckets evicted after two days |
| Expired redemption backlog | 100 codes per five-minute sweep |
| Admin analytics | Maximum 31 days and 10,000 rows per metric |

No environment variables, dependencies, public endpoints, or Firestore schema fields changed.

## Verification and follow-up

`backend\\gradlew.bat test` passed on 2026-07-15. New tests cover rate-limit eviction and
analytics range validation.

Before horizontal scaling or when reporting regularly exceeds 10,000 events in 31 days:

1. Replace local Bucket4j storage with a shared rate-limit backend.
2. Write daily analytics rollups transactionally with source events.
3. Serve long-range dashboards from rollups and retain raw-event queries for drill-down.
