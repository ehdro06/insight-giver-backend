package com.insightgiver.support;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

public final class Times {

    private Times() {
    }

    /** Current UTC time at Postgres timestamp precision, so values read back compare equal. */
    public static OffsetDateTime now(Clock clock) {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
