package net.davidgf.mediadatefixer.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Everything that defines a scan: folders, date sources, filters, actions. */
public final class JobConfig {
    public enum Source { FILENAME, EXIF, VIDEO, MTIME }
    public enum ExifFilter { ANY, HAS, LACKS }
    /** "Only where dates mismatch": off, any difference, or only when the calendar day differs. */
    public enum Mismatch { OFF, ANY, DAY }
    /** Which date the from/to range is checked against. */
    public enum RangeOn { DERIVED, MTIME, EXIF }

    public boolean whatsappMode;
    public List<String> folders = new ArrayList<>();
    public boolean recursive = true;

    /** All sources in priority order; only those in {@link #enabled} are used. */
    public List<Source> order = new ArrayList<>(Arrays.asList(Source.values()));
    public Set<Source> enabled = EnumSet.of(Source.FILENAME, Source.EXIF, Source.VIDEO);
    /** Enabled preset ids; defaults come from patterns.xml (needs {@link FilenamePattern#loadPresets}). */
    public Set<String> presets = new LinkedHashSet<>(FilenamePattern.defaultPresetIds());
    public String customRegex = "";

    public String nameRegex = "";
    public String extensions = "";
    /** Inclusive epoch-millis bounds on the {@link #rangeOn} date; 0 = unbounded. */
    public long dateFrom, dateTo;
    public RangeOn rangeOn = RangeOn.DERIVED;
    /** Bytes; 0 = unbounded. */
    public long minSize, maxSize;
    public ExifFilter exifFilter = ExifFilter.ANY;
    public Mismatch mismatch = Mismatch.OFF;
    /**
     * For {@link Mismatch#DAY}: an mtime this many minutes before or after the target day still
     * counts as "right day" (midnight crossings, small zone differences). Big values are risky: a
     * restore stamp shortly after midnight would then pass as correct for the previous day.
     */
    public int dayToleranceMin = 60;
    public static final int[] DAY_TOLERANCES = {0, 60, 180, 720};

    public boolean setMtime = true;
    public boolean writeExif;
    /** Which EXIF date tags to write ({@link ExifDates} keys). */
    public Set<String> exifTags = new LinkedHashSet<>(Arrays.asList(ExifDates.ORIGINAL));
    public boolean patchMp4;

    /** Zone for local-time sources; empty = device default. */
    public String zoneId = "";

    /**
     * One-tap WhatsApp mode: filename only, mtime only. Everything is planned (mismatch OFF); the
     * preview then picks the scope in memory: a restore group, where every file is fixed, or all
     * files minus those already on the right day ({@link Planner#onRightDay}), so real receive
     * times (more precise than the WAnnnn counter) survive.
     */
    public static JobConfig whatsapp(List<String> folders) {
        JobConfig c = new JobConfig();
        c.whatsappMode = true;
        c.folders = new ArrayList<>(folders);
        c.enabled = EnumSet.of(Source.FILENAME);
        c.presets = new LinkedHashSet<>(Arrays.asList(FilenamePattern.WHATSAPP));
        return c;
    }

    public boolean hasDateRange() {
        return dateFrom > 0 || dateTo > 0;
    }

    public boolean inRange(long t) {
        return (dateFrom <= 0 || t >= dateFrom) && (dateTo <= 0 || t <= dateTo);
    }

    /** A from/to range is set and checked against {@code on}. */
    public boolean rangeUses(RangeOn on) {
        return hasDateRange() && rangeOn == on;
    }

    /** The range filter on {@code on} excludes date {@code t} ({@link Dates#NONE} is always excluded). */
    public boolean rangeRejects(RangeOn on, long t) {
        return rangeUses(on) && (t == Dates.NONE || !inRange(t));
    }

    public List<Source> chain() {
        List<Source> out = new ArrayList<>();
        for (Source s : order) if (enabled.contains(s)) out.add(s);
        return out;
    }

    /** Compiled filename patterns, custom first. Throws IllegalArgumentException on a bad regex. */
    public List<FilenamePattern> patterns() {
        List<FilenamePattern> out = new ArrayList<>();
        if (customRegex != null && !customRegex.trim().isEmpty()) out.add(FilenamePattern.custom(customRegex.trim()));
        out.addAll(presetPatterns());
        return out;
    }

    /** The enabled presets, in matching order. */
    public List<FilenamePattern> presetPatterns() {
        List<FilenamePattern> out = new ArrayList<>();
        for (FilenamePattern p : FilenamePattern.presets()) if (presets.contains(p.id)) out.add(p);
        return out;
    }
}
