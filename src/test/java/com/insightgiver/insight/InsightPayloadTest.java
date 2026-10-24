package com.insightgiver.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class InsightPayloadTest {

    private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-10-23T02:00:00Z");

    @Test
    void carriesWhatTheNativeHandlersRead() {
        UUID id = UUID.randomUUID();
        Map<String, String> payload = InsightPayload.of(new Insight(id, UUID.randomUUID(), "Body", "Bo…", CREATED));

        assertThat(payload).containsExactly(
                Map.entry("type", "insight"),
                Map.entry("insightId", id.toString()),
                Map.entry("body", "Body"),
                Map.entry("preview", "Bo…"),
                Map.entry("createdAt", "2026-10-23T02:00:00Z"));
    }

    @Test
    void omitsThePreviewWhenTheWidgetShowsEverything() {
        Map<String, String> payload = InsightPayload.of(new Insight(UUID.randomUUID(), UUID.randomUUID(), "Body", null, CREATED));

        assertThat(payload).doesNotContainKey("preview");
    }

    @Test
    void theLongestAllowedInsightFitsInAPush() {
        // 500 chars of 3-byte UTF-8 (the worst case per Java char) plus its teaser.
        String body = "한".repeat(500);
        Map<String, String> payload = InsightPayload.of(
                new Insight(UUID.randomUUID(), UUID.randomUUID(), body, TeaserPolicy.teaser(body), CREATED));

        int bytes = payload.entrySet().stream()
                .mapToInt(e -> e.getKey().getBytes(StandardCharsets.UTF_8).length + e.getValue().getBytes(StandardCharsets.UTF_8).length)
                .sum();
        assertThat(bytes).isLessThanOrEqualTo(InsightPayload.MAX_BYTES);
    }
}
