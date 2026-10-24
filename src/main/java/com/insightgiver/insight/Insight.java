package com.insightgiver.insight;

import java.time.OffsetDateTime;
import java.util.UUID;

/** @param preview what the widget shows until the app is opened; null when it shows the whole body */
public record Insight(UUID id, UUID accountId, String body, String preview, OffsetDateTime createdAt) {
}
