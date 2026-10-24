package com.insightgiver.account;

import java.time.DateTimeException;
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
    public record InsightCandidate(UUID id, ZoneId timeZone, OffsetDateTime lastSeenAt, OffsetDateTime lastInsightAt) {
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

    /** Accounts seen since {@code activeSince} that have had no insight since {@code noInsightSince}. */
    public List<InsightCandidate> findInsightCandidates(OffsetDateTime activeSince, OffsetDateTime noInsightSince) {
        return jdbc.sql("""
                SELECT id, time_zone, last_seen_at, last_insight_at
                FROM accounts
                WHERE last_seen_at >= :activeSince
                  AND (last_insight_at IS NULL OR last_insight_at < :noInsightSince)
                ORDER BY id
                """)
                .param("activeSince", activeSince)
                .param("noInsightSince", noInsightSince)
                .query((rs, row) -> new InsightCandidate(
                        rs.getObject("id", UUID.class),
                        zoneOrUtc(rs.getString("time_zone")),
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
        var statement = jdbc.sql("UPDATE accounts SET last_insight_at = :now WHERE id = :id AND " + guard)
                .param("id", id)
                .param("now", now);
        if (seenLastInsightAt != null) {
            statement = statement.param("seen", seenLastInsightAt);
        }
        return statement.update() == 1;
    }

    /** Gives a claimed slot back after a failed attempt so the next run retries. */
    public void releaseInsightSlot(UUID id, OffsetDateTime claimedAt, OffsetDateTime previous) {
        jdbc.sql("UPDATE accounts SET last_insight_at = :previous WHERE id = :id AND last_insight_at = :claimedAt")
                .param("id", id)
                .param("claimedAt", claimedAt)
                .param("previous", previous)
                .update();
    }
}
