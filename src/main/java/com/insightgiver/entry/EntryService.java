package com.insightgiver.entry;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.insightgiver.support.Times;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class EntryService {

    private static final Logger log = LoggerFactory.getLogger(EntryService.class);

    /** Device clocks drift; anything further ahead than this is treated as written "now". */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    record Accepted(UUID id, EntryStatus status) {
    }

    private final EntryRepository entries;
    private final EntryQueue queue;
    private final QueueProperties properties;
    private final Clock clock;

    EntryService(EntryRepository entries, EntryQueue queue, QueueProperties properties, Clock clock) {
        this.entries = entries;
        this.queue = queue;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Stores the entry and hands its id to the embedding queue. Deliberately not transactional:
     * the insert must be committed before a worker can pop the id. Idempotent per {@code clientId},
     * so the app can retry until it sees a response.
     */
    Accepted accept(UUID accountId, String clientId, String content, OffsetDateTime writtenAt) {
        OffsetDateTime now = Times.now(clock);
        if (writtenAt.isAfter(now.plus(MAX_CLOCK_SKEW))) {
            writtenAt = now;
        }
        UUID id = UUID.randomUUID();
        boolean inserted = entries.insertIfAbsent(id, accountId, clientId, content, writtenAt, now,
                now.plus(properties.staleAfter()));
        if (!inserted) {
            EntryRepository.EntryRef existing = entries.findByClientId(accountId, clientId);
            return new Accepted(existing.id(), existing.status());
        }
        try {
            queue.push(id);
        } catch (RuntimeException e) {
            // The entry is safe in Postgres; the sweeper queues it once retry_at passes.
            log.warn("Could not queue entry {} for embedding, leaving it to the sweeper", id, e);
        }
        return new Accepted(id, EntryStatus.PENDING);
    }
}
