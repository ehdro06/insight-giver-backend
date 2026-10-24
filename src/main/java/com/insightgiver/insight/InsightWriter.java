package com.insightgiver.insight;

import java.time.LocalDate;
import java.time.ZoneId;

/** Words the daily note that connects a recent entry with the memory it recalled. */
public interface InsightWriter {

    /** @param today the account's local date, for phrasing like "three weeks ago" */
    record Request(Memory recent, Memory recalled, ZoneId timeZone, LocalDate today) {
    }

    /** Stored with each insight, to tell generated wording from template wording. */
    String name();

    String write(Request request);
}
