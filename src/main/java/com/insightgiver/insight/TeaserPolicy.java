package com.insightgiver.insight;

import java.time.OffsetDateTime;
import java.util.random.RandomGenerator;

import com.insightgiver.support.Text;

import org.springframework.stereotype.Component;

/**
 * Decides when the home-screen widget shows only part of an insight. Both iOS and Android ration
 * background pushes by how much the app is used, and a widget alone barely counts. A truncated
 * insight gets the person to tap through to the app, which resets that engagement signal and keeps
 * the daily pushes flowing.
 */
@Component
class TeaserPolicy {

    private static final double PREVIEW_SHARE = 0.45;
    private static final int MIN_PREVIEW_CHARS = 16;

    private final InsightProperties.Teaser properties;

    TeaserPolicy(InsightProperties properties) {
        this.properties = properties.teaser();
    }

    /**
     * Always teases once the app has gone unopened for {@code away-after}; otherwise at random
     * with {@code probability}, so tapping stays a habit rather than a chore.
     *
     * @return the text the widget should show instead of {@code body}, or null to show all of it
     */
    String preview(String body, OffsetDateTime lastSeenAt, OffsetDateTime now, RandomGenerator random) {
        boolean away = !lastSeenAt.isAfter(now.minus(properties.awayAfter()));
        if (!away && random.nextDouble() >= properties.probability()) {
            return null;
        }
        return teaser(body);
    }

    static String teaser(String body) {
        int length = Math.max(MIN_PREVIEW_CHARS, (int) (body.length() * PREVIEW_SHARE));
        if (length >= body.length()) {
            length = body.length() / 2;
        }
        return Text.clip(body, length) + "…";
    }
}
