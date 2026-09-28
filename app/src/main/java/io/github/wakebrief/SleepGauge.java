package io.github.wakebrief;

/**
 * What the hero ring says about tonight's sleep, from the time left until the next alarm.
 * The ring is measured against 8 hours (a good night): full at 8 h or more, emptying below that.
 * <ul>
 *   <li>blue: more than 12 h to go, plenty of time;</li>
 *   <li>green: 8 to 12 h, a good moment to get ready for bed;</li>
 *   <li>yellow: under 8 h, sleep is getting cut short;</li>
 *   <li>red: under 5 h, not enough sleep to be healthy.</li>
 * </ul>
 */
final class SleepGauge {

    static final int BLUE = 0xFF5AB8FF, GREEN = 0xFF3EE08F, YELLOW = 0xFFFFD24D, RED = 0xFFFF5C6C;

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long GOOD_NIGHT = 8 * HOUR;

    /** How full the ring is, 0..1 (1 = 8 h of sleep or more). */
    final float fill;
    final int color;
    /** Big text in the ring, e.g. "6h 40m". */
    final String value;
    /** Small text under it in the ring, e.g. "of sleep". */
    final String caption;
    /** One short line of advice shown under the ring. */
    final String advice;

    private SleepGauge(float fill, int color, String value, String caption, String advice) {
        this.fill = fill;
        this.color = color;
        this.value = value;
        this.caption = caption;
        this.advice = advice;
    }

    /** @param msLeft time until the next alarm, or a negative value when there is none. */
    static SleepGauge of(long msLeft) {
        if (msLeft < 0 || msLeft > 24 * HOUR) {
            return new SleepGauge(1f, BLUE, "24h+", "no alarm", "No alarm in the next 24 hours");
        }
        String left = shortTime(msLeft);
        float fill = Math.min(1f, msLeft / (float) GOOD_NIGHT);
        if (msLeft > 12 * HOUR) return new SleepGauge(1f, BLUE, left, "to go", "Plenty of time before bed");
        if (msLeft > 10 * HOUR) return new SleepGauge(1f, GREEN, left, "to go", "Start winding down tonight");
        if (msLeft >= 8 * HOUR) return new SleepGauge(1f, GREEN, left, "of sleep", "Good time to get ready for bed");
        if (msLeft >= 7 * HOUR) return new SleepGauge(fill, YELLOW, left, "of sleep", "Under 8 h: head to bed now");
        if (msLeft >= 6 * HOUR) return new SleepGauge(fill, YELLOW, left, "of sleep", "Getting short: sleep soon");
        if (msLeft >= 5 * HOUR) return new SleepGauge(fill, YELLOW, left, "of sleep", "Short night: sleep right away");
        if (msLeft >= 4 * HOUR) return new SleepGauge(fill, RED, left, "of sleep", "Under 5 h: move the alarm later?");
        return new SleepGauge(fill, RED, left, "of sleep", "Health risk: reschedule if you can");
    }

    /** "13h 50m", "7h 05m" or "45m": short enough for the ring. */
    static String shortTime(long ms) {
        long mins = Math.max(0, ms / MINUTE); // rounded down, like the "in 4 h 29 min" beside it
        long h = mins / 60;
        long m = mins % 60;
        if (h == 0) return m + "m";
        return h + "h " + (m < 10 ? "0" : "") + m + "m";
    }
}
