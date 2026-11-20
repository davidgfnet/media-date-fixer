package net.davidgf.mediadatefixer.core;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Turns a scanned file into a {@link PlanItem} using the configured sources and filters. */
public final class Planner {

    /** Platform metadata access (Android ExifInterface / MediaMetadataRetriever). */
    public interface MetaReader {
        /** Returns EXIF date fields, or null if unreadable. */
        ExifData readExif(File f);

        /** Container date in epoch millis, or {@link Dates#NONE}. */
        long readVideoDate(File f);
    }

    public static final class ExifData {
        /** Current values of all date tags, as an {@link ExifDates} string. */
        public final String all;
        /** SubSecTimeOriginal; DateTimeOriginal (in {@link #all}) is the EXIF date source. */
        public final String subsec;

        public ExifData(String all, String subsec) {
            this.all = all;
            this.subsec = subsec;
        }

        /** The EXIF date source: DateTimeOriginal with its offset and sub-seconds. */
        long date(ZoneId zone) {
            String[] o = ExifDates.decodeValue(ExifDates.decode(all).get(ExifDates.ORIGINAL));
            return ExifDates.parse(o[0], o[1], subsec, zone);
        }
    }

    public static final MetaReader NO_META = new MetaReader() {
        @Override public ExifData readExif(File f) { return null; }
        @Override public long readVideoDate(File f) { return Dates.NONE; }
    };

    /** Sources disagreeing by more than this (or on the day, for date-only sources) are a conflict. */
    static final long CONFLICT_MS = 60_000L;

    private final JobConfig cfg;
    private final MetaReader meta;
    private final long now;
    private final ZoneId zone;
    private final List<FilenamePattern> patterns;
    private final List<JobConfig.Source> chain;
    private final Pattern nameFilter;
    private final Set<String> exts;
    private final boolean needExif;

    /** Number of files rejected by filters. */
    public int filtered;

    public Planner(JobConfig cfg, MetaReader meta, long now) {
        this.cfg = cfg;
        this.meta = meta;
        this.now = now;
        this.zone = Dates.zone(cfg.zoneId);
        this.patterns = cfg.patterns();
        this.chain = cfg.chain();
        this.nameFilter = cfg.nameRegex == null || cfg.nameRegex.trim().isEmpty() ? null : Pattern.compile(cfg.nameRegex.trim());
        this.exts = MediaTypes.parseList(cfg.extensions);
        this.needExif = chain.contains(JobConfig.Source.EXIF) || cfg.exifFilter != JobConfig.ExifFilter.ANY || cfg.writeExif
                || cfg.rangeUses(JobConfig.RangeOn.EXIF);
    }

    public ZoneId zone() {
        return zone;
    }

    /** Extensions the scanner should even look at. */
    public Set<String> scanExtensions() {
        return exts != null ? exts : MediaTypes.ALL;
    }

    /** Returns the plan for a file, or null if a filter excludes it. */
    public PlanItem plan(File f) {
        String name = f.getName();
        String ext = MediaTypes.ext(name);
        if (exts != null && !exts.contains(ext)) return reject();
        if (nameFilter != null && !nameFilter.matcher(name).find()) return reject();
        long size = f.length();
        if ((cfg.minSize > 0 && size < cfg.minSize) || (cfg.maxSize > 0 && size > cfg.maxSize)) return reject();
        PlanItem it = new PlanItem(f, size, f.lastModified());
        // Cheap and checked first: e.g. "everything a restore copied on 2024-05-20".
        if (cfg.rangeRejects(JobConfig.RangeOn.MTIME, it.mtime)) return reject();

        ExifData exif = null;
        long exifDate = Dates.NONE;
        if (needExif && MediaTypes.EXIF_READ.contains(ext)) {
            exif = meta.readExif(f);
            if (exif != null) exifDate = exif.date(zone);
        }
        boolean hasExifDate = Dates.plausible(exifDate, now);
        if (cfg.exifFilter == JobConfig.ExifFilter.HAS && !hasExifDate) return reject();
        if (cfg.exifFilter == JobConfig.ExifFilter.LACKS && hasExifDate) return reject();
        if (cfg.rangeRejects(JobConfig.RangeOn.EXIF, hasExifDate ? exifDate : Dates.NONE)) return reject();

        long chosen = Dates.NONE;
        boolean chosenDateOnly = false;
        boolean chosenIsMtime = false;
        for (JobConfig.Source s : chain) {
            // Keep evaluating content sources after a hit (conflict detection), but mtime never
            // conflicts with anything: it is the value being fixed.
            if (chosen != Dates.NONE && chosenIsMtime) break;
            if (chosen != Dates.NONE && s == JobConfig.Source.MTIME) continue;
            long v = Dates.NONE;
            boolean dateOnly = false;
            String label = null;
            switch (s) {
                case FILENAME:
                    for (FilenamePattern p : patterns) {
                        long x = p.parse(name, zone);
                        if (Dates.plausible(x, now)) {
                            v = x;
                            dateOnly = !p.hasTime;
                            label = "filename:" + p.id;
                            break;
                        }
                    }
                    break;
                case EXIF:
                    if (hasExifDate) {
                        v = exifDate;
                        label = "exif";
                    }
                    break;
                case VIDEO:
                    if (MediaTypes.VIDEO.contains(ext) || MediaTypes.AUDIO.contains(ext)) {
                        long x = videoDate(f, ext);
                        if (Dates.plausible(x, now)) {
                            v = x;
                            label = "video";
                        }
                    }
                    break;
                case MTIME:
                    if (Dates.plausible(it.mtime, now)) {
                        v = it.mtime;
                        label = "mtime";
                    }
                    break;
            }
            if (v == Dates.NONE) continue;
            if (chosen == Dates.NONE) {
                chosen = v;
                chosenDateOnly = dateOnly;
                chosenIsMtime = s == JobConfig.Source.MTIME;
                it.source = label;
            } else if (conflicting(chosen, chosenDateOnly, v, dateOnly)) {
                it.conflict = true;
            }
        }

        if (chosen == Dates.NONE) {
            it.kind = PlanItem.NO_DATE;
            return it;
        }
        if (cfg.rangeRejects(JobConfig.RangeOn.DERIVED, chosen)) return reject();
        it.derived = chosen;

        if (cfg.mismatch != JobConfig.Mismatch.OFF) {
            boolean matches = cfg.mismatch == JobConfig.Mismatch.DAY ? onRightDay(it) : Dates.near(it.mtime, chosen);
            if (matches) {
                it.kind = PlanItem.UNCHANGED;
                return it;
            }
        }

        it.newMtime = cfg.setMtime ? chosen : it.mtime;

        if (cfg.writeExif && MediaTypes.EXIF_WRITE.contains(ext)) {
            String[] t = ExifDates.format(chosen, zone);
            String target = ExifDates.encodeValue(t[0], t[1]);
            Map<String, String> cur = ExifDates.decode(exif == null ? "" : exif.all);
            Map<String, String> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            for (String k : ExifDates.KEYS) {
                if (!cfg.exifTags.contains(k)) continue;
                String c = cur.containsKey(k) ? cur.get(k) : "";
                if (!ExifDates.valueMatches(c, target)) {
                    before.put(k, c);
                    after.put(k, target);
                }
            }
            if (!after.isEmpty()) {
                it.oldExif = ExifDates.encode(before);
                it.newExif = ExifDates.encode(after);
            }
        }
        if (cfg.patchMp4 && MediaTypes.MP4.contains(ext)) {
            try {
                long raw = Mp4Dates.readCreationRaw(f);
                long target = Mp4Dates.millisToRaw(chosen);
                if (raw >= 0 && Math.abs(raw - target) > 1) {
                    it.oldMp4 = raw;
                    it.newMp4 = target;
                }
            } catch (IOException ignored) {
                // Not a parseable container: leave its header alone.
            }
        }
        boolean changed = !Dates.near(it.newMtime, it.mtime) || it.newExif != null || it.newMp4 >= 0;
        it.kind = changed ? PlanItem.CHANGE : PlanItem.UNCHANGED;
        return it;
    }

    /**
     * The file's current mtime is on the calendar day of its derived date, within the configured
     * midnight margin ({@link JobConfig#dayToleranceMin}).
     */
    public boolean onRightDay(PlanItem it) {
        return it.derived != Dates.NONE && Dates.withinDay(it.mtime, it.derived, zone, cfg.dayToleranceMin * 60_000L);
    }

    private long videoDate(File f, String ext) {
        if (MediaTypes.MP4.contains(ext)) {
            try {
                long raw = Mp4Dates.readCreationRaw(f);
                if (raw > 0) return Mp4Dates.rawToMillis(raw);
            } catch (IOException ignored) {
                // fall through to the platform reader
            }
        }
        return meta.readVideoDate(f);
    }

    private boolean conflicting(long a, boolean aDateOnly, long b, boolean bDateOnly) {
        if (aDateOnly || bDateOnly) return !Dates.sameDay(a, b, zone);
        return Math.abs(a - b) > CONFLICT_MS;
    }

    private PlanItem reject() {
        filtered++;
        return null;
    }
}
