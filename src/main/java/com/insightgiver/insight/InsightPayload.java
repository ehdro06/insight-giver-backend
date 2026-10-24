package com.insightgiver.insight;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The FCM data payload for an insight. The mobile app's native push handlers read these keys
 * straight from the push, without booting React Native, so they are a contract with
 * {@code insight-giver-mobile/modules/insight-native}: change both together.
 */
final class InsightPayload {

    /** FCM caps data messages at 4096 bytes, and on iOS the APNs payload (also 4 KB) adds its own keys. */
    static final int MAX_BYTES = 3_500;

    private InsightPayload() {
    }

    static Map<String, String> of(Insight insight) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "insight");
        data.put("insightId", insight.id().toString());
        data.put("body", insight.body());
        if (insight.preview() != null) {
            data.put("preview", insight.preview());
        }
        data.put("createdAt", insight.createdAt().toInstant().toString());
        int size = data.entrySet().stream()
                .mapToInt(entry -> utf8Length(entry.getKey()) + utf8Length(entry.getValue()))
                .sum();
        if (size > MAX_BYTES) {
            // Unreachable while insight-giver.insights.max-length stays within its 500-char bound.
            throw new IllegalStateException("Insight " + insight.id() + " needs " + size + " bytes of push payload");
        }
        return data;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
