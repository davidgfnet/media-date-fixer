package net.davidgf.mediadatefixer.core;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic date helpers: plausibility, zones, video dates, day comparisons. EXIF date strings live
 * in {@link ExifDates}. Pure Java, no Android deps.
 */
public final class Dates {
    private Dates() {}

    /** Sentinel for "no date". */
    public static final long NONE = Long.MIN_VALUE;
    /** 1990-01-01T00:00:00Z: anything older is treated as bogus. */
    public static final long MIN_PLAUSIBLE = 631152000000L;
    /** Two timestamps closer than this are "equal" (FAT/exFAT have 2 s granularity). */
    public static final long TOLERANCE_MS = 2000;

    private static final Pattern OFFSET = Pattern.compile("^\\s*([+-])(\\d{2}):?(\\d{2})\\s*$");
    private static final Pattern VIDEO_DT = Pattern.compile(
            "^\\s*(\\d{4})-?(\\d{2})-?(\\d{2})[T ](\\d{2}):?(\\d{2}):?(\\d{2})(?:\\.(\\d+))?\\s*(Z|[+-]\\d{2}:?\\d{2})?");

    public static boolean plausible(long ms, long now) {
        return ms != NONE && ms >= MIN_PLAUSIBLE && ms <= now + 86_400_000L;
    }

    public static boolean near(long a, long b) {
        return Math.abs(a - b) < TOLERANCE_MS;
    }

    /** Resolves a zone id; empty/invalid means the device default. */
    public static ZoneId zone(String id) {
        if (id == null || id.isEmpty()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            return ZoneId.systemDefault();
        }
    }

    /**
     * Local wall-clock time to epoch millis, DST-aware: in a gap (spring forward) the time is
     * shifted forward by the gap length; in an overlap (fall back) the earlier offset is used.
     */
    public static long localToEpoch(LocalDateTime ldt, ZoneId zone) {
        return ZonedDateTime.ofLocal(ldt, zone, null).toInstant().toEpochMilli();
    }

    /** Parses MediaMetadataRetriever METADATA_KEY_DATE values like "20230115T143022.000Z". */
    public static long parseVideoDate(String s) {
        if (s == null) return NONE;
        Matcher m = VIDEO_DT.matcher(s);
        if (!m.find()) return NONE;
        try {
            int year = i(m, 1);
            if (year < 1971) return NONE; // 1904/1970 "unset" sentinels
            LocalDateTime ldt = LocalDateTime.of(year, i(m, 2), i(m, 3), i(m, 4), i(m, 5), i(m, 6),
                    fractionMillis(m.group(7)) * 1_000_000);
            String z = m.group(8);
            ZoneOffset off = (z == null || z.equals("Z")) ? ZoneOffset.UTC : parseOffset(z);
            if (off == null) off = ZoneOffset.UTC;
            return ldt.toInstant(off).toEpochMilli();
        } catch (DateTimeException e) {
            return NONE;
        }
    }

    public static ZoneOffset parseOffset(String s) {
        if (s == null) return null;
        Matcher m = OFFSET.matcher(s);
        if (!m.matches()) return null;
        int sign = m.group(1).equals("-") ? -1 : 1;
        try {
            return ZoneOffset.ofHoursMinutes(sign * Integer.parseInt(m.group(2)), sign * Integer.parseInt(m.group(3)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    /**
     * True if {@code t} falls on the local calendar day of {@code day}, widened by
     * {@code toleranceMs} on both sides. With 0 this is {@link #sameDay}.
     */
    public static boolean withinDay(long t, long day, ZoneId z, long toleranceMs) {
        LocalDate d = localDate(day, z);
        return t >= startOfDay(d, z) - toleranceMs && t < startOfDay(d.plusDays(1), z) + toleranceMs;
    }

    public static boolean sameDay(long a, long b, ZoneId z) {
        return localDate(a, z).equals(localDate(b, z));
    }

    /** The local calendar day of an instant. */
    public static LocalDate localDate(long ms, ZoneId z) {
        return Instant.ofEpochMilli(ms).atZone(z).toLocalDate();
    }

    /** First instant of a local day (DST-aware: a day may start at 01:00 in some zones). */
    public static long startOfDay(LocalDate d, ZoneId z) {
        return d.atStartOfDay(z).toInstant().toEpochMilli();
    }

    static int fractionMillis(String digits) {
        if (digits == null) return 0;
        StringBuilder sb = new StringBuilder(3);
        for (int k = 0; k < digits.length() && sb.length() < 3; k++) {
            char c = digits.charAt(k);
            if (c < '0' || c > '9') break;
            sb.append(c);
        }
        if (sb.length() == 0) return 0;
        while (sb.length() < 3) sb.append('0');
        return Integer.parseInt(sb.toString());
    }

    private static int i(Matcher m, int g) {
        return Integer.parseInt(m.group(g));
    }
}
