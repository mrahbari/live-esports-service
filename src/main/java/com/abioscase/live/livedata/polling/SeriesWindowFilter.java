package com.abioscase.live.livedata.polling;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class SeriesWindowFilter {

    private SeriesWindowFilter() {}

    /**
     * Strict window: both lower and upper bounds are enforced.
     * Used for upcoming series (task2 §5: strict filtering by window).
     */
    public static boolean isWithinWindowStrict(Instant startTime, Instant now, int windowDays) {
        if (startTime == null) return true;
        Instant lowerBound = now.minus(windowDays, ChronoUnit.DAYS);
        Instant upperBound = now.plus(windowDays, ChronoUnit.DAYS);
        return !startTime.isBefore(lowerBound) && !startTime.isAfter(upperBound);
    }

    /**
     * Lenient window: only the upper bound is enforced.
     * Used for live series — already started, so lower bound is irrelevant (task2 §5).
     */
    public static boolean isWithinWindowLive(Instant startTime, Instant now, int windowDays) {
        if (startTime == null) return true;
        Instant upperBound = now.plus(windowDays, ChronoUnit.DAYS);
        return !startTime.isAfter(upperBound);
    }

    public static Instant parseInstant(String ts) {
        if (ts == null || ts.isBlank()) return null;
        try {
            return Instant.parse(ts);
        } catch (Exception e) {
            return null;
        }
    }
}
