package com.insightgiver.entry;

public enum EntryStatus {
    /** Stored, waiting for an embedding. */
    PENDING,
    /** Embedded and available for recall. */
    EMBEDDED,
    /** Gave up after {@code insight-giver.queue.max-attempts}. */
    FAILED
}
