package com.insightgiver.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ClaudeInsightWriterTest {

    @Test
    void framesBothEntriesWithLocalDates() {
        Memory recent = new Memory(UUID.randomUUID(), "  Ran along the river again.\n", OffsetDateTime.parse("2026-10-22T15:30:00Z"));
        Memory older = new Memory(UUID.randomUUID(), "First run in years.", OffsetDateTime.parse("2026-09-12T23:00:00Z"));

        String message = ClaudeInsightWriter.userMessage(
                new InsightWriter.Request(recent, older, ZoneId.of("Asia/Seoul"), LocalDate.parse("2026-10-23")));

        assertThat(message).isEqualTo("""
                Recent entry (2026-10-23, today):
                <recent_entry>
                Ran along the river again.
                </recent_entry>

                Older entry (2026-09-13, 40 days ago):
                <older_entry>
                First run in years.
                </older_entry>""");
    }
}
