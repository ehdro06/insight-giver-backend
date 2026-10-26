package com.insightgiver.insight;

import java.time.Duration;
import java.time.LocalTime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param cron          when to look for accounts that are due (UTC)
 * @param delivery      when in their local day accounts get their insight
 * @param concurrency   accounts processed in parallel during one run
 * @param minEntries    embedded entries an account needs before it gets insights
 * @param seedWindow    how recent the entry that seeds recall must be
 * @param memoryGap     minimum time between the seed and the memory it recalls
 * @param candidates    nearest memories considered before sampling one
 * @param blur          relative noise added to the seed vector; ~0.5 keeps cosine ~0.9 to the original
 * @param temperature   softmax temperature over similarity; lower means "always the closest memory"
 * @param inactiveAfter accounts unseen for this long stop getting insights
 * @param maxLength     hard cap on insight length, keeping the push payload far below 4 KB
 * @param writer        who words the insight
 */
@Validated
@ConfigurationProperties("insight-giver.insights")
public record InsightProperties(
        @NotBlank String cron,
        @Valid @NotNull Delivery delivery,
        @Min(1) int concurrency,
        @Min(2) int minEntries,
        @NotNull Duration seedWindow,
        @NotNull Duration memoryGap,
        @Min(1) int candidates,
        @DecimalMin("0") double blur,
        @Positive double temperature,
        @NotNull Duration inactiveAfter,
        @Min(40) @Max(500) int maxLength,
        @NotNull Writer writer,
        @Valid @NotNull Teaser teaser) {

    public enum Writer {
        CLAUDE,
        /** Fixed wording, no API calls: for development and tests. */
        TEMPLATE
    }

    /**
     * Times are local to each account and fall on {@code DeliveryPreferences.MINUTE_STEP}.
     *
     * @param morning      for the MORNING mode
     * @param surpriseFrom first possible time for the SURPRISE mode
     * @param surpriseTo   end of the SURPRISE window (exclusive)
     * @param retryAfter   pause before an account whose insight failed is tried again
     */
    public record Delivery(@NotNull LocalTime morning, @NotNull LocalTime surpriseFrom, @NotNull LocalTime surpriseTo,
            @NotNull Duration retryAfter) {
    }

    /**
     * @param awayAfter   always tease when the app hasn't been opened for this long
     * @param probability chance of teasing otherwise, so taps stay a habit
     */
    public record Teaser(@NotNull Duration awayAfter, @DecimalMin("0") @DecimalMax("1") double probability) {
    }
}
