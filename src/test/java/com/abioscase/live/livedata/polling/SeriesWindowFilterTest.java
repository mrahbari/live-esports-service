package com.abioscase.live.livedata.polling;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class SeriesWindowFilterTest {

    private static final Instant NOW = Instant.parse("2026-05-03T12:00:00Z");

    // ── isWithinWindowStrict (upcoming) ─────────────────────────────────────────

    @Test
    void strict_withinWindow_returnsTrue() {
        int days = 5;
        Instant justInsideLower = NOW.minus(days, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS);
        Instant justInsideUpper = NOW.plus(days, ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS);

        assertTrue(SeriesWindowFilter.isWithinWindowStrict(justInsideLower, NOW, days));
        assertTrue(SeriesWindowFilter.isWithinWindowStrict(justInsideUpper, NOW, days));
    }

    @Test
    void strict_outsideWindow_returnsFalse() {
        int days = 5;
        Instant justBeyondLower = NOW.minus(days, ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS);
        Instant justBeyondUpper = NOW.plus(days, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS);

        assertFalse(SeriesWindowFilter.isWithinWindowStrict(justBeyondLower, NOW, days));
        assertFalse(SeriesWindowFilter.isWithinWindowStrict(justBeyondUpper, NOW, days));
    }

    @Test
    void strict_exactBoundary_returnsTrue() {
        int days = 5;
        Instant exactLower = NOW.minus(days, ChronoUnit.DAYS);
        Instant exactUpper = NOW.plus(days, ChronoUnit.DAYS);

        assertTrue(SeriesWindowFilter.isWithinWindowStrict(exactLower, NOW, days));
        assertTrue(SeriesWindowFilter.isWithinWindowStrict(exactUpper, NOW, days));
    }

    @Test
    void strict_defaultWindow25Days_withinWindow_returnsTrue() {
        int days = 25;
        Instant withinWindow = NOW.plus(20, ChronoUnit.DAYS);
        Instant outsideWindow = NOW.plus(26, ChronoUnit.DAYS);

        assertTrue(SeriesWindowFilter.isWithinWindowStrict(withinWindow, NOW, days));
        assertFalse(SeriesWindowFilter.isWithinWindowStrict(outsideWindow, NOW, days));
    }

    @Test
    void strict_windowZero_onlyNow_returnsTrue() {
        assertTrue(SeriesWindowFilter.isWithinWindowStrict(NOW, NOW, 0));
        assertFalse(SeriesWindowFilter.isWithinWindowStrict(NOW.plus(1, ChronoUnit.HOURS), NOW, 0));
        assertFalse(SeriesWindowFilter.isWithinWindowStrict(NOW.minus(1, ChronoUnit.HOURS), NOW, 0));
    }

    @Test
    void strict_nullStartTime_returnsTrue() {
        assertTrue(SeriesWindowFilter.isWithinWindowStrict(null, NOW, 5));
    }

    // ── isWithinWindowLive (live series — upper bound only) ─────────────────────

    @Test
    void live_pastStartTime_returnsTrue() {
        int days = 5;
        Instant veryOld = NOW.minus(30, ChronoUnit.DAYS);
        assertTrue(SeriesWindowFilter.isWithinWindowLive(veryOld, NOW, days));
    }

    @Test
    void live_withinUpperBound_returnsTrue() {
        int days = 5;
        Instant nearFuture = NOW.plus(3, ChronoUnit.DAYS);
        assertTrue(SeriesWindowFilter.isWithinWindowLive(nearFuture, NOW, days));
    }

    @Test
    void live_beyondUpperBound_returnsFalse() {
        int days = 5;
        Instant farFuture = NOW.plus(days, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS);
        assertFalse(SeriesWindowFilter.isWithinWindowLive(farFuture, NOW, days));
    }

    @Test
    void live_exactUpperBoundary_returnsTrue() {
        int days = 5;
        Instant exactUpper = NOW.plus(days, ChronoUnit.DAYS);
        assertTrue(SeriesWindowFilter.isWithinWindowLive(exactUpper, NOW, days));
    }

    @Test
    void live_nullStartTime_returnsTrue() {
        assertTrue(SeriesWindowFilter.isWithinWindowLive(null, NOW, 5));
    }

    // ── parseInstant ────────────────────────────────────────────────────────────

    @Test
    void parseInstant_validIso_returnsInstant() {
        Instant result = SeriesWindowFilter.parseInstant("2026-05-03T12:00:00Z");
        assertEquals(NOW, result);
    }

    @Test
    void parseInstant_null_returnsNull() {
        assertNull(SeriesWindowFilter.parseInstant(null));
    }

    @Test
    void parseInstant_blank_returnsNull() {
        assertNull(SeriesWindowFilter.parseInstant("   "));
    }

    @Test
    void parseInstant_invalid_returnsNull() {
        assertNull(SeriesWindowFilter.parseInstant("not-a-date"));
    }
}
