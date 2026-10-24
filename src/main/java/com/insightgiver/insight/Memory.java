package com.insightgiver.insight;

import java.time.OffsetDateTime;
import java.util.UUID;

/** An embedded diary entry, as recall sees it. */
public record Memory(UUID id, String content, OffsetDateTime writtenAt) {
}
