package com.insightgiver.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Random;

import org.junit.jupiter.api.Test;

class TeaserPolicyTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-23T02:00:00Z");
    private static final String BODY = "You wrote about the river twice now, and both times the run ended with a clear head.";

    private final Random random = new Random(7);

    @Test
    void alwaysTeasesWhenTheAppWentUnopened() {
        TeaserPolicy policy = policy(0.0);

        String preview = policy.preview(BODY, NOW.minusDays(3), NOW, random);

        assertThat(preview).isNotNull().endsWith("…");
        assertThat(BODY).startsWith(preview.substring(0, preview.length() - 1));
    }

    @Test
    void showsEverythingToRegularsWhenTheDiceSaySo() {
        TeaserPolicy policy = policy(0.0);

        assertThat(policy.preview(BODY, NOW.minusHours(3), NOW, random)).isNull();
    }

    @Test
    void sometimesTeasesRegularsToo() {
        TeaserPolicy policy = policy(0.25);
        int teased = 0;
        for (int i = 0; i < 1_000; i++) {
            if (policy.preview(BODY, NOW.minusHours(3), NOW, random) != null) {
                teased++;
            }
        }
        assertThat(teased).isBetween(180, 320);
    }

    @Test
    void teaserHidesTheSecondHalf() {
        String teaser = TeaserPolicy.teaser(BODY);

        assertThat(teaser.length()).isLessThan(BODY.length() / 2 + 2);
        assertThat(teaser).isEqualTo("You wrote about the river twice now,…");
    }

    @Test
    void evenShortInsightsLoseSomething() {
        assertThat(TeaserPolicy.teaser("Rain again.")).isEqualTo("Rain…");
    }

    private static TeaserPolicy policy(double probability) {
        return new TeaserPolicy(new InsightProperties("0 */5 * * * *", DeliveryScheduleTest.DELIVERY, 1, 3, Duration.ofDays(7), Duration.ofDays(2), 8,
                0.5, 0.08, Duration.ofDays(30), 500, InsightProperties.Writer.TEMPLATE,
                new InsightProperties.Teaser(Duration.ofDays(2), probability)));
    }
}
