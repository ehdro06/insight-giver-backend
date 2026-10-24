package com.insightgiver.insight;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.pgvector.PGvector;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read side of {@code entries} for recall. Only ever compares vectors from the same embedding model. */
@Repository
class MemoryRepository {

    record Cue(Memory memory, float[] embedding) {
    }

    record Recalled(Memory memory, double similarity) {
    }

    private final JdbcClient jdbc;

    MemoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    int countEmbedded(UUID accountId, String model) {
        return jdbc.sql("""
                SELECT count(*) FROM entries
                WHERE account_id = :accountId AND status = 'EMBEDDED' AND embedding_model = :model
                """)
                .param("accountId", accountId)
                .param("model", model)
                .query(Integer.class)
                .single();
    }

    /** The account's newest embedded entries, newest first, with their vectors. */
    List<Cue> findLatest(UUID accountId, String model, int limit) {
        return jdbc.sql("""
                SELECT id, content, written_at, embedding::text AS embedding FROM entries
                WHERE account_id = :accountId AND status = 'EMBEDDED' AND embedding_model = :model
                ORDER BY written_at DESC
                LIMIT :limit
                """)
                .param("accountId", accountId)
                .param("model", model)
                .param("limit", limit)
                .query((rs, row) -> new Cue(memory(rs), new PGvector(rs.getString("embedding")).toArray()))
                .list();
    }

    /**
     * Exact cosine nearest neighbours of {@code cue} among entries written before
     * {@code writtenBefore}. Exact rather than ANN: see the note on indexes in V1__init.sql.
     */
    List<Recalled> findNearest(UUID accountId, String model, float[] cue, OffsetDateTime writtenBefore, int limit) {
        return jdbc.sql("""
                SELECT id, content, written_at, 1 - (embedding <=> :cue) AS similarity FROM entries
                WHERE account_id = :accountId AND status = 'EMBEDDED' AND embedding_model = :model
                  AND written_at < :writtenBefore
                ORDER BY embedding <=> :cue
                LIMIT :limit
                """)
                .param("accountId", accountId)
                .param("model", model)
                .param("cue", new PGvector(cue))
                .param("writtenBefore", writtenBefore)
                .param("limit", limit)
                .query((rs, row) -> new Recalled(memory(rs), rs.getDouble("similarity")))
                .list();
    }

    private static Memory memory(ResultSet rs) throws SQLException {
        return new Memory(rs.getObject("id", UUID.class), rs.getString("content"),
                rs.getObject("written_at", OffsetDateTime.class));
    }
}
