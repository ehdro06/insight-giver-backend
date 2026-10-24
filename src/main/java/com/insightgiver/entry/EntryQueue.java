package com.insightgiver.entry;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis list of entry ids waiting for an embedding. It only carries ids: the entries themselves
 * are already in Postgres, so anything lost here is re-queued by {@link PendingEntrySweeper}.
 */
@Component
class EntryQueue {

    private final StringRedisTemplate redis;
    private final QueueProperties properties;

    EntryQueue(StringRedisTemplate redis, QueueProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    void push(UUID entryId) {
        redis.opsForList().leftPush(properties.key(), entryId.toString());
    }

    /** Blocks for up to {@code poll-timeout}; empty when nothing arrived in time. */
    Optional<UUID> poll() {
        return Optional.ofNullable(redis.opsForList().rightPop(properties.key(), properties.pollTimeout()))
                .map(UUID::fromString);
    }
}
