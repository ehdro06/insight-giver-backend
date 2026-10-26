package com.insightgiver.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.insightgiver.account.AccountRepository.InsightCandidate;
import com.insightgiver.account.DeliveryMode;
import com.insightgiver.account.DeliveryPreferences;

import org.junit.jupiter.api.Test;

class DeliveryScheduleTest {

    static final InsightProperties.Delivery DELIVERY = new InsightProperties.Delivery(LocalTime.of(9, 0),
            LocalTime.of(9, 0), LocalTime.of(21, 0), Duration.ofMinutes(30));

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final UUID ACCOUNT = UUID.fromString("7b0c3f9e-2a41-4c55-9d1e-0f6a8b3c2d11");

    private final DeliverySchedule schedule = schedule(DELIVERY);

    @Test
    void morningAccountsGetTheirsFromTheServersMorningTime() {
        InsightCandidate account = account(DeliveryMode.MORNING, null, null);

        assertThat(schedule.isDue(account, seoul("2026-10-23T08:55"))).isFalse();
        assertThat(schedule.isDue(account, seoul("2026-10-23T09:00"))).isTrue();
        assertThat(schedule.isDue(account, seoul("2026-10-23T23:55"))).isTrue();
    }

    @Test
    void fixedAccountsGetTheirsAtThePickedTime() {
        InsightCandidate account = account(DeliveryMode.FIXED, LocalTime.of(21, 30), null);

        assertThat(schedule.isDue(account, seoul("2026-10-23T21:15"))).isFalse();
        assertThat(schedule.isDue(account, seoul("2026-10-23T21:30"))).isTrue();
    }

    @Test
    void atMostOneInsightPerLocalDayEvenWhenTheTimeMovesLater() {
        // Got this morning's, then switched to an evening time.
        InsightCandidate account = account(DeliveryMode.FIXED, LocalTime.of(21, 0), seoul("2026-10-23T09:00"));

        assertThat(schedule.isDue(account, seoul("2026-10-23T21:00"))).isFalse();
        assertThat(schedule.isDue(account, seoul("2026-10-24T21:00"))).isTrue();
    }

    @Test
    void surpriseTimesStayPutWithinADayAndMoveBetweenDays() {
        DeliveryPreferences surprise = new DeliveryPreferences(DeliveryMode.SURPRISE, null);
        LocalDate start = LocalDate.of(2026, 10, 1);
        Set<LocalTime> times = new HashSet<>();
        for (int day = 0; day < 60; day++) {
            LocalTime time = schedule.timeOn(ACCOUNT, surprise, start.plusDays(day));
            assertThat(time).isEqualTo(schedule.timeOn(ACCOUNT, surprise, start.plusDays(day)));
            assertThat(time).isBetween(LocalTime.of(9, 0), LocalTime.of(20, 45));
            assertThat(DeliveryPreferences.onGrid(time)).isTrue();
            times.add(time);
        }
        // 48 possible slots; a hash that ignored the date, or crept along with it, would repeat a lot.
        assertThat(times).hasSizeGreaterThan(25);
    }

    @Test
    void surpriseTimesDifferBetweenAccounts() {
        DeliveryPreferences surprise = new DeliveryPreferences(DeliveryMode.SURPRISE, null);
        LocalDate day = LocalDate.of(2026, 10, 23);
        Set<LocalTime> times = new HashSet<>();
        for (long i = 0; i < 40; i++) {
            times.add(schedule.timeOn(new UUID(0x1234L, i), surprise, day));
        }
        assertThat(times).hasSizeGreaterThan(20);
    }

    @Test
    void surpriseAccountsAreDueOnceTheirTimeHasCome() {
        InsightCandidate account = account(DeliveryMode.SURPRISE, null, null);
        LocalDate day = LocalDate.of(2026, 10, 23);
        LocalTime time = schedule.timeOn(ACCOUNT, account.delivery(), day);
        OffsetDateTime slot = day.atTime(time).atZone(SEOUL).toOffsetDateTime();

        assertThat(schedule.isDue(account, slot.minusMinutes(5))).isFalse();
        assertThat(schedule.isDue(account, slot)).isTrue();
    }

    @Test
    void aTimeSkippedByDaylightSavingMovesForward() {
        ZoneId newYork = ZoneId.of("America/New_York");
        InsightCandidate account = new InsightCandidate(ACCOUNT, newYork,
                new DeliveryPreferences(DeliveryMode.FIXED, LocalTime.of(2, 30)), OffsetDateTime.parse("2026-03-07T12:00Z"),
                null);

        // 2026-03-08 jumps from 02:00 to 03:00 EDT (07:00Z).
        assertThat(schedule.isDue(account, OffsetDateTime.parse("2026-03-08T07:15Z"))).isFalse();
        assertThat(schedule.isDue(account, OffsetDateTime.parse("2026-03-08T07:30Z"))).isTrue();
    }

    @Test
    void rejectsAMisconfiguredWindow() {
        assertThatIllegalArgumentException().isThrownBy(() -> schedule(new InsightProperties.Delivery(LocalTime.of(9, 0),
                LocalTime.of(21, 0), LocalTime.of(9, 0), Duration.ofMinutes(30))));
        assertThatIllegalArgumentException().isThrownBy(() -> schedule(new InsightProperties.Delivery(LocalTime.of(9, 10),
                LocalTime.of(9, 0), LocalTime.of(21, 0), Duration.ofMinutes(30))));
    }

    private static InsightCandidate account(DeliveryMode mode, LocalTime time, OffsetDateTime lastInsightAt) {
        return new InsightCandidate(ACCOUNT, SEOUL, new DeliveryPreferences(mode, time),
                OffsetDateTime.parse("2026-10-22T00:00Z"), lastInsightAt);
    }

    private static OffsetDateTime seoul(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SEOUL).toOffsetDateTime();
    }

    static DeliverySchedule schedule(InsightProperties.Delivery delivery) {
        return new DeliverySchedule(new InsightProperties("0 */5 * * * *", delivery, 1, 3, Duration.ofDays(7),
                Duration.ofDays(2), 8, 0.5, 0.08, Duration.ofDays(30), 500, InsightProperties.Writer.TEMPLATE,
                new InsightProperties.Teaser(Duration.ofDays(2), 0.25)));
    }
}
