package com.insightgiver.entry;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.pgvector.PGvector;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class EntryRepository {

    record EntryRef(UUID id, EntryStatus status) {
    }

    private final JdbcClient jdbc;

    EntryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a PENDING entry unless the app already sent this {@code clientId}; returns whether it was new. */
    boolean insertIfAbsent(UUID id, UUID accountId, String clientId, String content, OffsetDateTime writtenAt,
            OffsetDateTime now, OffsetDateTime retryAt) {
        return jdbc.sql("""
                INSERT INTO entries (id, account_id, client_id, content, written_at, received_at, status, retry_at)
                VALUES (:id, :accountId, :clientId, :content, :writtenAt, :now, 'PENDING', :retryAt)
                ON CONFLICT (account_id, client_id) DO NOTHING
                """)
                .param("id", id)
                .param("accountId", accountId)
                .param("clientId", clientId)
                .param("content", content)
                .param("writtenAt", writtenAt)
                .param("now", now)
                .param("retryAt", retryAt)
                .update() == 1;
    }

    EntryRef findByClientId(UUID accountId, String clientId) {
        return jdbc.sql("SELECT id, status FROM entries WHERE account_id = :accountId AND client_id = :clientId")
                .param("accountId", accountId)
                .param("clientId", clientId)
                .query((rs, row) -> new EntryRef(rs.getObject("id", UUID.class), EntryStatus.valueOf(rs.getString("status"))))
                .single();
    }

    Optional<String> findPendingContent(UUID id) {
        return jdbc.sql("SELECT content FROM entries WHERE id = :id AND status = 'PENDING'")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    boolean markEmbedded(UUID id, float[] embedding, String model, OffsetDateTime now) {
        return jdbc.sql("""
                UPDATE entries
                SET status = 'EMBEDDED', embedding = :embedding, embedding_model = :model, embedded_at = :now, last_error = NULL
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("id", id)
                .param("embedding", new PGvector(embedding))
                .param("model", model)
                .param("now", now)
                .update() == 1;
    }

    /**
     * Counts a failed attempt and schedules the next one with exponential backoff, or marks the
     * entry FAILED once {@code maxAttempts} is reached. Returns the resulting status.
     */
    Optional<EntryStatus> recordFailure(UUID id, String error, int maxAttempts, OffsetDateTime now,
            Duration backoff, Duration maxBackoff) {
        return jdbc.sql("""
                UPDATE entries
                SET attempts = attempts + 1,
                    last_error = :error,
                    status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE status END,
                    retry_at = :now + make_interval(secs => LEAST(:backoffSeconds * power(2, attempts), :maxBackoffSeconds))
                WHERE id = :id AND status = 'PENDING'
                RETURNING status
                """)
                .param("id", id)
                .param("error", error)
                .param("maxAttempts", maxAttempts)
                .param("now", now)
                .param("backoffSeconds", (double) backoff.toSeconds())
                .param("maxBackoffSeconds", (double) maxBackoff.toSeconds())
                .query((rs, row) -> EntryStatus.valueOf(rs.getString("status")))
                .optional();
    }

    /**
     * Claims PENDING entries whose retry time has passed and moves it to {@code nextRetryAt}, so
     * concurrent sweepers (or the next sweep) don't queue them again while they wait in Redis.
     */
    List<UUID> claimDueForRetry(OffsetDateTime now, OffsetDateTime nextRetryAt, int limit) {
        return jdbc.sql("""
                UPDATE entries
                SET retry_at = :nextRetryAt
                WHERE id IN (
                    SELECT id FROM entries
                    WHERE status = 'PENDING' AND retry_at <= :now
                    ORDER BY retry_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
                RETURNING id
                """)
                .param("now", now)
                .param("nextRetryAt", nextRetryAt)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }
}
