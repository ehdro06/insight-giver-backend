package com.insightgiver.insight;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.insightgiver.account.AccountRepository;
import com.insightgiver.account.AccountRepository.InsightCandidate;
import com.insightgiver.support.Times;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily insight batch. It wakes up on {@code insight-giver.insights.cron} (hourly by default)
 * and serves every account whose local delivery hour has passed today without an insight, so
 * each person gets theirs in the morning of their own time zone, and a failed attempt is simply
 * retried by the next run.
 */
@Component
class InsightScheduler {

    private static final Logger log = LoggerFactory.getLogger(InsightScheduler.class);

    /** Never two insights within this span, even across a change of time zone. */
    private static final Duration MIN_SPACING = Duration.ofHours(12);

    private final AccountRepository accounts;
    private final InsightService insights;
    private final InsightProperties properties;
    private final Clock clock;

    InsightScheduler(AccountRepository accounts, InsightService insights, InsightProperties properties, Clock clock) {
        this.accounts = accounts;
        this.insights = insights;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${insight-giver.insights.cron}", zone = "UTC")
    void deliverDueInsights() {
        OffsetDateTime now = Times.now(clock);
        List<InsightCandidate> due = findDue(now);
        AtomicInteger delivered = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(properties.concurrency(),
                Thread.ofVirtual().name("insight-", 0).factory())) {
            for (InsightCandidate account : due) {
                executor.submit(() -> {
                    try {
                        insights.deliverDailyInsight(account, now).ifPresent(insight -> delivered.incrementAndGet());
                    } catch (RuntimeException e) {
                        log.warn("Insight for account {} failed; the next run retries", account.id(), e);
                    }
                });
            }
        }
        if (!due.isEmpty()) {
            log.info("Delivered {} insights to {} due accounts", delivered.get(), due.size());
        }
    }

    List<InsightCandidate> findDue(OffsetDateTime now) {
        return accounts.findInsightCandidates(now.minus(properties.inactiveAfter()), now.minus(MIN_SPACING))
                .stream()
                .filter(account -> isDue(account, now))
                .toList();
    }

    private boolean isDue(InsightCandidate account, OffsetDateTime now) {
        ZonedDateTime local = now.atZoneSameInstant(account.timeZone());
        OffsetDateTime slot = local.toLocalDate()
                .atTime(properties.deliveryHour(), 0)
                .atZone(account.timeZone())
                .toOffsetDateTime();
        return !now.isBefore(slot) && (account.lastInsightAt() == null || account.lastInsightAt().isBefore(slot));
    }
}
