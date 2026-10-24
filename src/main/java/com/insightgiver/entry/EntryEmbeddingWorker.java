package com.insightgiver.entry;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import com.insightgiver.embedding.EmbeddingClient;
import com.insightgiver.support.Times;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Embeds one entry per call on the bounded {@value #EXECUTOR} pool. */
@Component
class EntryEmbeddingWorker {

    static final String EXECUTOR = "embeddingExecutor";

    private static final Logger log = LoggerFactory.getLogger(EntryEmbeddingWorker.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final EntryRepository entries;
    private final EmbeddingClient embeddings;
    private final QueueProperties properties;
    private final Clock clock;

    EntryEmbeddingWorker(EntryRepository entries, EmbeddingClient embeddings, QueueProperties properties, Clock clock) {
        this.entries = entries;
        this.embeddings = embeddings;
        this.properties = properties;
        this.clock = clock;
    }

    @Async(EXECUTOR)
    public void embed(UUID entryId) {
        // An id can be queued twice (sweeper and original push); whichever runs second finds nothing to do.
        Optional<String> content = entries.findPendingContent(entryId);
        if (content.isEmpty()) {
            log.debug("Entry {} is no longer pending", entryId);
            return;
        }
        try {
            float[] embedding = embeddings.embed(content.get());
            entries.markEmbedded(entryId, embedding, embeddings.model(), Times.now(clock));
            log.debug("Embedded entry {} with {}", entryId, embeddings.model());
        } catch (RuntimeException e) {
            Optional<EntryStatus> status = entries.recordFailure(entryId, describe(e), properties.maxAttempts(),
                    Times.now(clock), properties.retryBackoff(), properties.maxRetryBackoff());
            if (status.orElse(null) == EntryStatus.FAILED) {
                log.error("Giving up on embedding entry {} after {} attempts", entryId, properties.maxAttempts(), e);
            } else {
                log.warn("Embedding entry {} failed, it will be retried", entryId, e);
            }
        }
    }

    private static String describe(Exception e) {
        String message = e.getClass().getSimpleName() + ": " + e.getMessage();
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
