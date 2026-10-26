package com.insightgiver.account;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    /** The parts of an account the daily insight job needs. */
    public record InsightCandidate(UUID id, ZoneId timeZone, DeliveryPreferences delivery, OffsetDateTime lastSeenAt,
            OffsetDateTime lastInsightAt) {
    }

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void create(UUID id, byte[] tokenHash, ZoneId timeZone, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO accounts (id, token_hash, time_zone, created_at, last_seen_at)
                VALUES (:id, :tokenHash, :timeZone, :now, :now)
                """)
                .param("id", id)
                .param("tokenHash", tokenHash)
                .param("timeZone", timeZone.getId())
                .param("now", now)
                .update();
    }

    Optional<UUID> findIdByTokenHash(byte[] tokenHash) {
        return jdbc.sql("SELECT id FROM accounts WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .query(UUID.class)
                .optional();
    }

    /** Records app activity; skips the write when the account was already seen after {@code notBefore}. */
    void touchLastSeen(UUID id, OffsetDateTime now, OffsetDateTime notBefore) {
        jdbc.sql("UPDATE accounts SET last_seen_at = :now WHERE id = :id AND last_seen_at < :notBefore")
                .param("id", id)
                .param("now", now)
                .param("notBefore", notBefore)
                .update();
    }

    void updateTimeZone(UUID id, ZoneId timeZone) {
        jdbc.sql("UPDATE accounts SET time_zone = :timeZone WHERE id = :id")
                .param("id", id)
                .param("timeZone", timeZone.getId())
                .update();
    }

    Optional<DeliveryPreferences> findDeliveryPreferences(UUID id) {
        return jdbc.sql("SELECT delivery_mode, delivery_time FROM accounts WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> new DeliveryPreferences(DeliveryMode.valueOf(rs.getString("delivery_mode")),
                        rs.getObject("delivery_time", LocalTime.class)))
                .optional();
    }

    void updateDeliveryPreferences(UUID id, DeliveryPreferences preferences) {
        jdbc.sql("UPDATE accounts SET delivery_mode = :mode, delivery_time = :time WHERE id = :id")
                .param("id", id)
                .param("mode", preferences.mode().name())
                .param("time", preferences.time())
                .update();
    }

    /**
     * Accounts seen since {@code activeSince} that have had no insight since {@code noInsightSince}
     * and are not backing off after a failed attempt.
     */
    public List<InsightCandidate> findInsightCandidates(OffsetDateTime activeSince, OffsetDateTime noInsightSince,
            OffsetDateTime now) {
        return jdbc.sql("""
                SELECT id, time_zone, delivery_mode, delivery_time, last_seen_at, last_insight_at
                FROM accounts
                WHERE last_seen_at >= :activeSince
                  AND (last_insight_at IS NULL OR last_insight_at < :noInsightSince)
                  AND (insight_retry_at IS NULL OR insight_retry_at <= :now)
                ORDER BY id
                """)
                .param("activeSince", activeSince)
                .param("noInsightSince", noInsightSince)
                .param("now", now)
                .query((rs, row) -> new InsightCandidate(
                        rs.getObject("id", UUID.class),
                        zoneOrUtc(rs.getString("time_zone")),
                        new DeliveryPreferences(DeliveryMode.valueOf(rs.getString("delivery_mode")),
                                rs.getObject("delivery_time", LocalTime.class)),
                        rs.getObject("last_seen_at", OffsetDateTime.class),
                        rs.getObject("last_insight_at", OffsetDateTime.class)))
                .list();
    }

    /** Zones are validated on write, but a JVM with older tz data must not break the whole job. */
    private static ZoneId zoneOrUtc(String timeZone) {
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    /**
     * Atomically takes today's insight slot. Fails when another run (or instance) already moved
     * {@code last_insight_at} away from the value the caller saw.
     */
    public boolean claimInsightSlot(UUID id, OffsetDateTime seenLastInsightAt, OffsetDateTime now) {
        String guard = seenLastInsightAt == null ? "last_insight_at IS NULL" : "last_insight_at = :seen";
        String sql = "UPDATE accounts SET last_insight_at = :now, insight_retry_at = NULL WHERE id = :id AND " + guard;
        var statement = jdbc.sql(sql)
                .param("id", id)
                .param("now", now);
        if (seenLastInsightAt != null) {
            statement = statement.param("seen", seenLastInsightAt);
        }
        return statement.update() == 1;
    }

    /** Gives a claimed slot back after a failed attempt so that a run from {@code retryAt} on tries again. */
    public void releaseInsightSlot(UUID id, OffsetDateTime claimedAt, OffsetDateTime previous, OffsetDateTime retryAt) {
        jdbc.sql("""
                UPDATE accounts SET last_insight_at = :previous, insight_retry_at = :retryAt
                WHERE id = :id AND last_insight_at = :claimedAt
                """)
                .param("id", id)
                .param("claimedAt", claimedAt)
                .param("previous", previous)
                .param("retryAt", retryAt)
                .update();
    }
}
