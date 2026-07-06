package com.beanLoyal.backend.jobs;

import com.beanLoyal.backend.rewards.RedeemCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled sweep that expires pending redeem codes past their TTL and refunds them
 * ({@code docs/BUSINESS_RULES.md §3.1 + §3.3}).
 * <p>
 * Runs every 5 minutes. Each code is expired in its OWN transaction (via
 * {@link RedeemCodeService#expireCode}) so one failure never blocks the rest, and the in-transaction
 * status re-read makes a refund happen at most once even under a concurrent user cancel.
 * <p>
 * Not idempotency-guarded — there is no client request or key here; the guard is the status re-read
 * inside {@code expireCode}. ponytail: safe under accidental double execution (e.g. if Render ever
 * scales past one instance) because a second run sees a non-pending status and skips; single-instance
 * today per {@code BACKEND_IMPLEMENTATION_PLAN.md §12}, revisit with a distributed lock only if that
 * changes.
 */
@Component
public class ExpiredRedemptionJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiredRedemptionJob.class);

    private final RedeemCodeService redeemCodeService;
    private final Clock clock;

    public ExpiredRedemptionJob(RedeemCodeService redeemCodeService, Clock clock) {
        this.redeemCodeService = redeemCodeService;
        this.clock = clock;
    }

    /**
     * {@code initialDelay=5min} keeps the sweep from firing during a {@code @SpringBootTest} context
     * load. Upgrade path if a test ever needs scheduling fully off: move {@code @EnableScheduling} to
     * a {@code @Profile("!test")} config.
     */
    @Scheduled(initialDelay = 5, fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void run() {
        Instant now = Instant.now(clock);
        List<String> codes;
        try {
            codes = redeemCodeService.findExpiredPendingCodeIds(now);
        } catch (Exception e) {
            // e.g. missing composite index → FAILED_PRECONDITION. Log and let the next tick retry;
            // never let a query failure kill the scheduler thread.
            log.error("Expired-redemption sweep query failed", e);
            return;
        }

        int expired = 0;
        for (String code : codes) {
            try {
                if (redeemCodeService.expireCode(code)) {
                    expired++;
                }
            } catch (Exception e) {
                // One code's failure (e.g. its user doc was deleted) must not block the others; it
                // stays pending and is retried next sweep.
                log.error("Failed to expire redeem code {}", code, e);
            }
        }
        if (expired > 0) {
            log.info("Expired {} pending redeem code(s)", expired);
        }
    }
}
