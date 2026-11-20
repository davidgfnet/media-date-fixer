package net.davidgf.mediadatefixer.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds restore/copy bursts: many files whose mtimes sit close together while their names point to
 * many different days. A busy chat day is dense too, but there the names match the mtime's day.
 */
public final class RestoreDetector {
    private RestoreDetector() {}

    /** A quiet period longer than this splits two bursts. */
    public static final long GAP_MS = 30 * 60_000L;
    public static final int MIN_FILES = 20;
    /** Share of dated files whose name day differs from their mtime day. */
    public static final double MIN_MISMATCH = 0.5;
    public static final int MIN_NAME_DAYS = 3;

    public static final class Cluster {
        /** Inclusive mtime bounds. */
        public long start, end;
        public int files, dated, mismatched, nameDays;
        public long nameMin = Long.MAX_VALUE, nameMax = Long.MIN_VALUE;

        public boolean contains(long mtime) {
            return mtime >= start && mtime <= end;
        }
    }

    /** Bursts in {@code items} (any order), biggest first. Uses {@link PlanItem#derived} as the name date. */
    public static List<Cluster> detect(List<PlanItem> items, ZoneId zone) {
        List<PlanItem> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> Long.compare(a.mtime, b.mtime));
        List<Cluster> out = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            int j = i + 1;
            while (j < sorted.size() && sorted.get(j).mtime - sorted.get(j - 1).mtime <= GAP_MS) j++;
            Cluster c = summarize(sorted.subList(i, j), zone);
            if (c.files >= MIN_FILES && c.nameDays >= MIN_NAME_DAYS && c.dated > 0
                    && c.mismatched >= MIN_MISMATCH * c.dated) {
                out.add(c);
            }
            i = j;
        }
        out.sort((a, b) -> Integer.compare(b.files, a.files));
        return out;
    }

    private static Cluster summarize(List<PlanItem> run, ZoneId zone) {
        Cluster c = new Cluster();
        c.start = run.get(0).mtime;
        c.end = run.get(run.size() - 1).mtime;
        c.files = run.size();
        Set<LocalDate> days = new HashSet<>();
        for (PlanItem it : run) {
            if (it.derived == Dates.NONE) continue;
            c.dated++;
            if (!Dates.sameDay(it.mtime, it.derived, zone)) c.mismatched++;
            days.add(Dates.localDate(it.derived, zone));
            c.nameMin = Math.min(c.nameMin, it.derived);
            c.nameMax = Math.max(c.nameMax, it.derived);
        }
        c.nameDays = days.size();
        return c;
    }
}
