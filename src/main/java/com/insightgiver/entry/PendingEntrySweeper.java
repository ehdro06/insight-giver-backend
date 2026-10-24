package com.insightgiver.entry;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.insightgiver.support.Times;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net for the Redis queue: re-queues PENDING entries that were never processed (Redis
 * restart, crashed worker, enqueue failure) and failed attempts whose backoff has elapsed.
 */
@Component
class PendingEntrySweeper {

    private static final Logger log = LoggerFactory.getLogger(PendingEntrySweeper.class);
    private static final int BATCH_SIZE = 500;

    private final EntryRepository entries;
    private final EntryQueue queue;
    private final QueueProperties properties;
    private final Clock clock;

    PendingEntrySweeper(EntryRepository entries, EntryQueue queue, QueueProperties properties, Clock clock) {
        this.entries = entries;
        this.queue = queue;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${insight-giver.queue.sweep-interval}", initialDelayString = "${insight-giver.queue.sweep-interval}")
    void sweep() {
        OffsetDateTime now = Times.now(clock);
        List<UUID> due = entries.claimDueForRetry(now, now.plus(properties.staleAfter()), BATCH_SIZE);
        due.forEach(queue::push);
        if (!due.isEmpty()) {
            log.info("Re-queued {} pending entries", due.size());
        }
    }
}
