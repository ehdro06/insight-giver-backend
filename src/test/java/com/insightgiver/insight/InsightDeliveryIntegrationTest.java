package com.insightgiver.insight;

import static java.time.temporal.ChronoUnit.DAYS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.insightgiver.ApiClient;
import com.insightgiver.ApiClient.Session;
import com.insightgiver.IntegrationTest;
import com.insightgiver.TestFixtures.MutableClock;
import com.insightgiver.TestFixtures.RecordingPushGateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class InsightDeliveryIntegrationTest {

    /** 11:00 in Seoul: past the 09:00 delivery hour. */
    private static final Instant NOW = Instant.parse("2026-10-23T02:00:00Z");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private InsightScheduler scheduler;

    @Autowired
    private MutableClock clock;

    @Autowired
    private RecordingPushGateway pushes;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        api = new ApiClient(mvc);
    }

    @Test
    void entriesAreEmbeddedInTheBackgroundAndRecalledIntoOneInsightADay() throws Exception {
        Session session = api.register("Asia/Seoul");
        api.registerDevice(session, "IOS", "Asia/Seoul");
        writeDiary(session);

        scheduler.deliverDueInsights();

        List<Map<String, String>> payloads = pushesTo(session);
        assertThat(payloads).hasSize(1);
        Map<String, String> payload = payloads.getFirst();
        assertThat(payload).containsEntry("type", "insight").containsKeys("insightId", "body", "createdAt");
        assertThat(payload.get("body")).startsWith("On ").contains("you wrote");

        assertThat(api.latestInsight(session)).hasStatusOk()
                .bodyJson().extractingPath("$.id").isEqualTo(payload.get("insightId"));

        // Later the same day: today's slot is taken.
        clock.set(NOW.plus(Duration.ofHours(5)));
        scheduler.deliverDueInsights();
        assertThat(pushesTo(session)).hasSize(1);

        // The next local morning brings the next one.
        clock.set(NOW.plus(Duration.ofDays(1)));
        scheduler.deliverDueInsights();
        assertThat(pushesTo(session)).hasSize(2);
    }

    @Test
    void waitsForTheLocalDeliveryHour() throws Exception {
        clock.set(Instant.parse("2026-10-23T11:00:00Z")); // 07:00 in New York
        Session session = api.register("America/New_York");
        api.registerDevice(session, "ANDROID", "America/New_York");
        writeDiary(session);

        scheduler.deliverDueInsights();
        assertThat(pushesTo(session)).isEmpty();

        clock.set(Instant.parse("2026-10-23T13:00:00Z")); // 09:00 in New York
        scheduler.deliverDueInsights();
        assertThat(pushesTo(session)).hasSize(1);
    }

    @Test
    void teasesSomeoneWhoHasNotOpenedTheAppForAWhile() throws Exception {
        Session session = api.register("Asia/Seoul");
        api.registerDevice(session, "IOS", "Asia/Seoul");
        writeDiary(session);
        jdbc.sql("UPDATE accounts SET last_seen_at = :seen WHERE id = :id")
                .param("seen", OffsetDateTime.ofInstant(NOW.minus(5, DAYS), ZoneOffset.UTC))
                .param("id", session.accountId())
                .update();

        scheduler.deliverDueInsights();

        Map<String, String> payload = pushesTo(session).getFirst();
        assertThat(payload.get("preview")).endsWith("…");
        assertThat(payload.get("preview").length()).isLessThan(payload.get("body").length());
    }

    @Test
    void needsAFewEntriesBeforeTheFirstInsight() throws Exception {
        Session session = api.register("Asia/Seoul");
        api.registerDevice(session, "IOS", "Asia/Seoul");
        api.postEntry(session, "only-1", "First day with the app", NOW.minus(3, DAYS));
        api.postEntry(session, "only-2", "Second day, still not sure what to write", NOW.minus(1, DAYS));
        awaitEmbedded(session.accountId(), 2);

        scheduler.deliverDueInsights();

        assertThat(pushesTo(session)).isEmpty();
        assertThat(api.latestInsight(session)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void rejectsRequestsWithoutAValidToken() {
        assertThat(mvc.get().uri("/api/v1/insights/latest")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/v1/insights/latest").header("Authorization", "Bearer not-a-token"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private void writeDiary(Session session) throws Exception {
        api.postEntry(session, "run-1", "Went running by the river, my knees hurt but my head felt clear", NOW.minus(40, DAYS));
        api.postEntry(session, "work-1", "Deadline stress at work, could not sleep, kept replaying the launch", NOW.minus(20, DAYS));
        api.postEntry(session, "family-1", "Cooked dinner for my sister and we laughed about being kids", NOW.minus(10, DAYS));
        api.postEntry(session, "run-2", "Ran along the river this morning and the cold air cleared my head", NOW.minus(1, DAYS));
        awaitEmbedded(session.accountId(), 4);
    }

    private void awaitEmbedded(UUID accountId, int expected) {
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc
                .sql("SELECT count(*) FROM entries WHERE account_id = :id AND status = 'EMBEDDED'")
                .param("id", accountId)
                .query(Integer.class)
                .single() == expected);
    }

    private List<Map<String, String>> pushesTo(Session session) {
        return pushes.sent().stream()
                .filter(sent -> sent.device().accountId().equals(session.accountId()))
                .map(RecordingPushGateway.Sent::data)
                .toList();
    }
}
