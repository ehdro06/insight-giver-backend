package com.insightgiver.insight;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
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
 * The daily insight batch. It wakes up on {@code insight-giver.insights.cron} (every five minutes
 * by default) and serves every account whose delivery time has passed today, in its own time zone,
 * without an insight yet (see {@link DeliverySchedule}). A failed attempt is retried by a later
 * run once the account's back-off has passed.
 */
@Component
class InsightScheduler {

    private static final Logger log = LoggerFactory.getLogger(InsightScheduler.class);

    /** Never two insights within this span, even across a change of time zone. */
    private static final Duration MIN_SPACING = Duration.ofHours(12);

    private final AccountRepository accounts;
    private final InsightService insights;
    private final DeliverySchedule schedule;
    private final InsightProperties properties;
    private final Clock clock;

    InsightScheduler(AccountRepository accounts, InsightService insights, DeliverySchedule schedule,
            InsightProperties properties, Clock clock) {
        this.accounts = accounts;
        this.insights = insights;
        this.schedule = schedule;
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
        return accounts.findInsightCandidates(now.minus(properties.inactiveAfter()), now.minus(MIN_SPACING), now)
                .stream()
                .filter(account -> schedule.isDue(account, now))
                .toList();
    }
}
