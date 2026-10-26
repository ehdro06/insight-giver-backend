package com.insightgiver.insight;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import com.insightgiver.account.AccountRepository.InsightCandidate;
import com.insightgiver.account.DeliveryPreferences;

import org.springframework.stereotype.Component;

/**
 * When an account's insight is due: once per local day, from the time its delivery mode names.
 * Surprise times are derived from the account id and the date, so every run (and every instance)
 * agrees on today's time without storing it, and tomorrow's is different.
 */
@Component
class DeliverySchedule {

    private final InsightProperties.Delivery delivery;
    private final int surpriseSlots;

    DeliverySchedule(InsightProperties properties) {
        this.delivery = properties.delivery();
        for (LocalTime time : new LocalTime[] {delivery.morning(), delivery.surpriseFrom(), delivery.surpriseTo()}) {
            if (!DeliveryPreferences.onGrid(time)) {
                throw new IllegalArgumentException("Delivery times must fall on multiples of %d minutes: %s"
                        .formatted(DeliveryPreferences.MINUTE_STEP, time));
            }
        }
        if (!delivery.surpriseFrom().isBefore(delivery.surpriseTo())) {
            throw new IllegalArgumentException("The surprise window must end after it starts");
        }
        this.surpriseSlots = (int) (Duration.between(delivery.surpriseFrom(), delivery.surpriseTo()).toMinutes()
                / DeliveryPreferences.MINUTE_STEP);
    }

    boolean isDue(InsightCandidate account, OffsetDateTime now) {
        ZoneId zone = account.timeZone();
        ZonedDateTime local = now.atZoneSameInstant(zone);
        LocalDate today = local.toLocalDate();
        if (account.lastInsightAt() != null
                && !account.lastInsightAt().atZoneSameInstant(zone).toLocalDate().isBefore(today)) {
            return false;
        }
        // A time skipped by a daylight-saving jump moves forward with it.
        return !local.isBefore(today.atTime(timeOn(account.id(), account.delivery(), today)).atZone(zone));
    }

    LocalTime timeOn(UUID accountId, DeliveryPreferences preferences, LocalDate date) {
        return switch (preferences.mode()) {
            case MORNING -> delivery.morning();
            case FIXED -> preferences.time() != null ? preferences.time() : delivery.morning();
            case SURPRISE -> {
                long hash = mix(accountId.getMostSignificantBits()
                        ^ mix(accountId.getLeastSignificantBits() ^ mix(date.toEpochDay())));
                yield delivery.surpriseFrom()
                        .plusMinutes((long) Math.floorMod(hash, surpriseSlots) * DeliveryPreferences.MINUTE_STEP);
            }
        };
    }

    /** SplitMix64's finalizer: nearby inputs (consecutive days) give unrelated outputs. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
