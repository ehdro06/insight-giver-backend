package com.insightgiver.account;

import java.time.LocalTime;

/** @param time the picked time; used by {@link DeliveryMode#FIXED}, remembered by the other modes */
public record DeliveryPreferences(DeliveryMode mode, LocalTime time) {

    /**
     * Delivery times fall on this many minutes past the hour, so a scheduler that ticks at least
     * this often (on the same grid) never steps over a slot.
     */
    public static final int MINUTE_STEP = 15;

    public static boolean onGrid(LocalTime time) {
        return time.getSecond() == 0 && time.getNano() == 0 && time.getMinute() % MINUTE_STEP == 0;
    }
}
