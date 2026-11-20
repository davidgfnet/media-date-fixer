package net.davidgf.mediadatefixer.core;

import java.io.IOException;
import java.io.InputStream;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * A filename date pattern using named groups:
 * year, month, day, hour, minute, second, millis (local time unless UTC), epoch (s or ms),
 * seq (seconds added to the date, used for WhatsApp's WAnnnn counter).
 * <p>
 * The built-in patterns ("presets") are data: {@code res/raw/patterns.xml}, loaded once with
 * {@link #loadPresets}. A custom pattern is the same thing typed in by the user.
 */
public final class FilenamePattern {
    /** The WhatsApp preset; WhatsApp mode relies on it being in patterns.xml. */
    public static final String WHATSAPP = "whatsapp";
    public static final String CUSTOM = "custom";

    private static final Pattern GROUP_NAME = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>");
    private static volatile List<FilenamePattern> presets;

    public final String id;
    /** Display name; for presets, from patterns.xml. */
    public final String label;
    public final Pattern pattern;
    /** Fields are UTC rather than local time (e.g. Pixel "PXL_" names). */
    public final boolean utc;
    /** Enabled in a fresh configuration. */
    public final boolean isDefault;
    /** False if the pattern only yields a calendar date (no time of day). */
    public final boolean hasTime;
    /** {name, expected date} pairs from patterns.xml (documentation, checked by the tests). */
    public final List<String[]> examples;
    private final boolean gEpoch, gSeq, gHour, gMinute, gSecond, gMillis;

    private FilenamePattern(String id, String label, String regex, boolean utc, boolean isDefault, List<String[]> examples) {
        this.id = id;
        this.label = label;
        this.utc = utc;
        this.isDefault = isDefault;
        this.examples = examples;
        this.pattern = Pattern.compile(regex);
        Set<String> g = new HashSet<>();
        Matcher m = GROUP_NAME.matcher(regex);
        while (m.find()) g.add(m.group(1));
        gEpoch = g.contains("epoch");
        if (!gEpoch && !(g.contains("year") && g.contains("month") && g.contains("day"))) {
            throw new IllegalArgumentException(
                    "Pattern needs (?<epoch>...) or all of (?<year>...), (?<month>...), (?<day>...)");
        }
        gSeq = g.contains("seq");
        gHour = g.contains("hour");
        gMinute = g.contains("minute");
        gSecond = g.contains("second");
        gMillis = g.contains("millis");
        hasTime = gEpoch || gHour;
    }

    public static FilenamePattern custom(String regex) {
        return new FilenamePattern(CUSTOM, CUSTOM, regex, false, false, Collections.emptyList());
    }

    /**
     * Reads the built-in patterns (the {@code <patterns>} XML, see res/raw/patterns.xml) and makes
     * them the presets. Throws if the file is malformed or a regex is invalid.
     */
    public static synchronized void loadPresets(InputStream in) throws IOException {
        List<FilenamePattern> out = new ArrayList<>();
        try {
            Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in).getDocumentElement();
            NodeList nodes = root.getElementsByTagName("pattern");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element e = (Element) nodes.item(i);
                List<String[]> examples = new ArrayList<>();
                NodeList ex = e.getElementsByTagName("example");
                for (int k = 0; k < ex.getLength(); k++) {
                    Element x = (Element) ex.item(k);
                    examples.add(new String[] {x.getAttribute("name"), x.getAttribute("date")});
                }
                NodeList regex = e.getElementsByTagName("regex");
                if (regex.getLength() != 1) throw new IOException("Pattern " + e.getAttribute("id") + " needs one <regex>");
                out.add(new FilenamePattern(e.getAttribute("id"), e.getAttribute("label"),
                        regex.item(0).getTextContent().trim(), Boolean.parseBoolean(e.getAttribute("utc")),
                        Boolean.parseBoolean(e.getAttribute("default")), Collections.unmodifiableList(examples)));
            }
        } catch (ParserConfigurationException | SAXException | IllegalArgumentException e) {
            throw new IOException("Bad patterns file: " + e.getMessage(), e);
        }
        presets = Collections.unmodifiableList(out);
    }

    public static boolean presetsLoaded() {
        return presets != null;
    }

    /** Built-in patterns in matching order (most specific first). */
    public static List<FilenamePattern> presets() {
        List<FilenamePattern> p = presets;
        if (p == null) throw new IllegalStateException("Built-in filename patterns not loaded");
        return p;
    }

    public static FilenamePattern preset(String id) {
        for (FilenamePattern p : presets()) if (p.id.equals(id)) return p;
        throw new IllegalArgumentException("Unknown preset " + id);
    }

    /** Ids of the presets enabled in a fresh configuration. */
    public static List<String> defaultPresetIds() {
        List<String> out = new ArrayList<>();
        for (FilenamePattern p : presets()) if (p.isDefault) out.add(p.id);
        return out;
    }

    /** Returns epoch millis or {@link Dates#NONE}. Tries a few successive matches. */
    public long parse(String name, ZoneId zone) {
        Matcher m = pattern.matcher(name);
        for (int guard = 0; guard < 8 && m.find(); guard++) {
            long v = fromMatch(m, zone);
            if (v != Dates.NONE) return v;
        }
        return Dates.NONE;
    }

    private long fromMatch(Matcher m, ZoneId zone) {
        try {
            if (gEpoch) {
                String s = m.group("epoch");
                if (s == null) return Dates.NONE;
                long v = Long.parseLong(s);
                return s.length() >= 13 ? v : v * 1000L;
            }
            ZoneId z = utc ? ZoneOffset.UTC : zone;
            LocalDate date = LocalDate.of(num(m, "year", true), num(m, "month", true), num(m, "day", true));
            long ms;
            String hour = gHour ? m.group("hour") : null;
            if (hour != null) {
                int min = gMinute ? num(m, "minute", false) : 0;
                int sec = gSecond ? num(m, "second", false) : 0;
                int millis = gMillis ? Dates.fractionMillis(m.group("millis")) : 0;
                LocalDateTime ldt = date.atTime(Integer.parseInt(hour), min, sec, millis * 1_000_000);
                ms = Dates.localToEpoch(ldt, z);
            } else {
                ms = Dates.startOfDay(date, z);
            }
            if (gSeq) {
                String s = m.group("seq");
                if (s != null) ms += Math.min(Long.parseLong(s), 86_399L) * 1000L;
            }
            return ms;
        } catch (NumberFormatException | DateTimeException e) {
            return Dates.NONE;
        }
    }

    private static int num(Matcher m, String group, boolean required) {
        String s = m.group(group);
        if (s == null) {
            if (required) throw new NumberFormatException(group);
            return 0;
        }
        return Integer.parseInt(s);
    }
}
