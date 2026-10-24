package com.insightgiver.entry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.insightgiver.ApiClient;
import com.insightgiver.ApiClient.Session;
import com.insightgiver.IntegrationTest;
import com.insightgiver.TestFixtures.MutableClock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class EntryPipelineIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-23T02:00:00Z");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EntryRepository entries;

    @Autowired
    private PendingEntrySweeper sweeper;

    @Autowired
    private MutableClock clock;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        api = new ApiClient(mvc);
    }

    @Test
    void storesTheEntryAndEmbedsItInTheBackground() throws Exception {
        Session session = api.register("UTC");

        UUID id = api.postEntry(session, "c-1", "Rainy Sunday, finally finished the book", NOW.minusSeconds(60));

        await().atMost(Duration.ofSeconds(20)).until(() -> status(id).equals("EMBEDDED"));
        assertThat(jdbc.sql("SELECT embedding_model FROM entries WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("hashing-v1");
    }

    @Test
    void resendingAnEntryDoesNotDuplicateIt() throws Exception {
        Session session = api.register("UTC");

        UUID first = api.postEntry(session, "same-client-id", "Coffee with Mina", NOW);
        UUID second = api.postEntry(session, "same-client-id", "Coffee with Mina", NOW);

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("SELECT count(*) FROM entries WHERE account_id = :id").param("id", session.accountId())
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void sweeperRequeuesEntriesTheQueueLost() throws Exception {
        Session session = api.register("UTC");
        // As if Redis lost the id right after the API stored the entry.
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        entries.insertIfAbsent(id, session.accountId(), "lost-1", "An entry Redis forgot about", now, now,
                now.plusMinutes(2));

        sweeper.sweep();
        assertThat(status(id)).isEqualTo("PENDING");

        clock.set(NOW.plus(Duration.ofMinutes(3)));
        sweeper.sweep();
        await().atMost(Duration.ofSeconds(20)).until(() -> status(id).equals("EMBEDDED"));
    }

    @Test
    void failuresBackOffAndEventuallyGiveUp() throws Exception {
        Session session = api.register("UTC");
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        // Retry time far ahead so the background dispatcher never sees this entry.
        entries.insertIfAbsent(id, session.accountId(), "flaky-1", "Embedding provider is down", now, now,
                now.plusDays(1));

        Duration backoff = Duration.ofMinutes(1);
        Duration maxBackoff = Duration.ofMinutes(5);
        assertThat(entries.recordFailure(id, "boom", 3, now, backoff, maxBackoff)).contains(EntryStatus.PENDING);
        assertThat(retryAt(id)).isEqualTo(now.plusMinutes(1));
        assertThat(entries.recordFailure(id, "boom", 3, now, backoff, maxBackoff)).contains(EntryStatus.PENDING);
        assertThat(retryAt(id)).isEqualTo(now.plusMinutes(2));
        assertThat(entries.recordFailure(id, "boom", 3, now, backoff, maxBackoff)).contains(EntryStatus.FAILED);
        assertThat(entries.recordFailure(id, "boom", 3, now, backoff, maxBackoff)).isEmpty();
    }

    @Test
    void rejectsBlankEntries() throws Exception {
        Session session = api.register("UTC");

        assertThat(mvc.post().uri("/api/v1/entries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"blank\",\"content\":\"  \",\"writtenAt\":\"2026-10-23T01:00:00Z\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsUnknownTimeZones() {
        assertThat(mvc.post().uri("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"timeZone\":\"Mars/Olympus_Mons\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM entries WHERE id = :id").param("id", id).query(String.class).single();
    }

    private OffsetDateTime retryAt(UUID id) {
        return jdbc.sql("SELECT retry_at FROM entries WHERE id = :id").param("id", id)
                .query((rs, row) -> rs.getObject("retry_at", OffsetDateTime.class)).single();
    }
}
