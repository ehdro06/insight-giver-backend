package com.insightgiver.insight;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class InsightRepository {

    private final JdbcClient jdbc;

    InsightRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Insight insight, UUID seedEntryId, UUID memoryEntryId, double similarity, String writer) {
        jdbc.sql("""
                INSERT INTO insights (id, account_id, body, preview, seed_entry_id, memory_entry_id, similarity, writer, created_at)
                VALUES (:id, :accountId, :body, :preview, :seedEntryId, :memoryEntryId, :similarity, :writer, :createdAt)
                """)
                .param("id", insight.id())
                .param("accountId", insight.accountId())
                .param("body", insight.body())
                .param("preview", insight.preview())
                .param("seedEntryId", seedEntryId)
                .param("memoryEntryId", memoryEntryId)
                .param("similarity", similarity)
                .param("writer", writer)
                .param("createdAt", insight.createdAt())
                .update();
    }

    void markPushed(UUID id, OffsetDateTime now) {
        jdbc.sql("UPDATE insights SET pushed_at = :now WHERE id = :id").param("id", id).param("now", now).update();
    }

    Optional<Insight> findLatest(UUID accountId) {
        return jdbc.sql("""
                SELECT id, account_id, body, preview, created_at FROM insights
                WHERE account_id = :accountId
                ORDER BY created_at DESC
                LIMIT 1
                """)
                .param("accountId", accountId)
                .query((rs, row) -> new Insight(
                        rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getString("body"),
                        rs.getString("preview"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .optional();
    }
}
