package net.davidgf.mediadatefixer.core;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EXIF date strings: parsing/formatting the "yyyy:MM:dd HH:mm:ss" values, and the encoding used to
 * carry them through plans and journals.
 * <p>
 * The three EXIF date tags, each paired with its offset tag:
 * <ul>
 *   <li>{@link #ORIGINAL}: DateTimeOriginal + OffsetTimeOriginal ("date taken"; what MediaStore reads)</li>
 *   <li>{@link #DIGITIZED}: DateTimeDigitized + OffsetTimeDigitized</li>
 *   <li>{@link #MODIFIED}: DateTime + OffsetTime ("file changed"; ignored by MediaStore)</li>
 * </ul>
 * Several tags travel as one string: {@code "O=dt|off;D=dt|off"}, where each value is a
 * {@link #encodeValue} string and an empty value means "tag absent". A legacy single value
 * without a key (no '=') is read as {@link #ORIGINAL}.
 */
public final class ExifDates {
    private ExifDates() {}

    public static final String ORIGINAL = "O", DIGITIZED = "D", MODIFIED = "M";
    public static final String[] KEYS = {ORIGINAL, DIGITIZED, MODIFIED};

    private static final Pattern EXIF_DT = Pattern.compile(
            "^\\s*(\\d{4})[:\\-/](\\d{2})[:\\-/](\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})");
    private static final DateTimeFormatter EXIF_FMT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    /** Parses EXIF "yyyy:MM:dd HH:mm:ss" with optional OffsetTime and SubSecTime. */
    public static long parse(String dt, String offset, String subsec, ZoneId zone) {
        if (dt == null) return Dates.NONE;
        Matcher m = EXIF_DT.matcher(dt);
        if (!m.find()) return Dates.NONE;
        try {
            LocalDateTime ldt = LocalDateTime.of(i(m, 1), i(m, 2), i(m, 3), i(m, 4), i(m, 5), i(m, 6),
                    Dates.fractionMillis(subsec) * 1_000_000);
            ZoneOffset off = Dates.parseOffset(offset);
            if (off != null) return ldt.toInstant(off).toEpochMilli();
            return Dates.localToEpoch(ldt, zone);
        } catch (DateTimeException e) {
            return Dates.NONE; // e.g. "0000:00:00 00:00:00"
        }
    }

    /** Returns {DateTimeOriginal, OffsetTimeOriginal} for an instant in the given zone. */
    public static String[] format(long ms, ZoneId zone) {
        ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(zone);
        String off = z.getOffset().getId();
        if (off.equals("Z")) off = "+00:00";
        return new String[] {EXIF_FMT.format(z), off};
    }

    /** One tag's date as a single string "dt|offset"; "" means "tag absent". */
    public static String encodeValue(String dt, String offset) {
        if (dt == null || dt.trim().isEmpty()) return "";
        return dt.trim() + "|" + (offset == null ? "" : offset.trim());
    }

    /** Inverse of {@link #encodeValue}; elements may be null. */
    public static String[] decodeValue(String enc) {
        if (enc == null || enc.isEmpty()) return new String[] {null, null};
        int bar = enc.indexOf('|');
        if (bar < 0) return new String[] {enc, null};
        String off = enc.substring(bar + 1);
        return new String[] {enc.substring(0, bar), off.isEmpty() ? null : off};
    }

    /** True if the current EXIF value already satisfies the target. */
    public static boolean valueMatches(String current, String target) {
        String[] c = decodeValue(current), t = decodeValue(target);
        if (t[0] == null) return c[0] == null;
        if (!t[0].equals(c[0])) return false;
        return t[1] == null || t[1].equals(c[1]);
    }


    public static Map<String, String> decode(String enc) {
        Map<String, String> out = new LinkedHashMap<>();
        if (enc == null || enc.isEmpty()) return out;
        if (enc.indexOf('=') < 0) {
            out.put(ORIGINAL, enc);
            return out;
        }
        for (String part : enc.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) out.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return out;
    }

    public static String encode(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue() == null ? "" : e.getValue());
        }
        return sb.toString();
    }

    /** True if every tag named in {@code target} already has its target value in {@code current}. */
    public static boolean matches(String current, String target) {
        Map<String, String> c = decode(current);
        for (Map.Entry<String, String> t : decode(target).entrySet()) {
            String cur = c.get(t.getKey());
            if (!valueMatches(cur == null ? "" : cur, t.getValue())) return false;
        }
        return true;
    }

    /** The values in {@code before} for exactly the tags {@code changed} touches (absent -> ""). */
    public static String restoring(String before, String changed) {
        Map<String, String> b = decode(before), out = new LinkedHashMap<>();
        boolean legacyEmpty = before == null || before.isEmpty();
        for (String k : decode(changed).keySet()) {
            String v = legacyEmpty ? null : b.get(k);
            out.put(k, v == null ? "" : v);
        }
        return encode(out);
    }

    private static int i(Matcher m, int g) {
        return Integer.parseInt(m.group(g));
    }
}
