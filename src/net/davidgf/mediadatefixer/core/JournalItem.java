package net.davidgf.mediadatefixer.core;

/** One file in a job journal: what to change, and what happened. */
public final class JournalItem {
    public static final int PENDING = 0;
    /** Changed and verified. */
    public static final int DONE = 1;
    /** Already had the target values (e.g. resumed after a crash). */
    public static final int ALREADY = 2;
    /** File changed since the scan: left alone. */
    public static final int SKIPPED = 3;
    public static final int FAILED = 4;
    /** mtime was set but did not stick (storage layer ignored it). */
    public static final int MISMATCH = 5;
    public static final int MISSING = 6;
    public static final int STATUS_COUNT = 7;

    public int idx;
    public String path;
    public long size;
    public long oldMtime;
    public long newMtime;
    public String oldExif, newExif;
    public long oldMp4 = -1, newMp4 = -1;

    public volatile int status = PENDING;
    public long actualMtime;
    public long actualSize;
    public String msg;

    public boolean hasContentChange() {
        return newExif != null || newMp4 >= 0;
    }

    public String name() {
        int s = path.lastIndexOf('/');
        return s < 0 ? path : path.substring(s + 1);
    }

    /** Statuses reported to the user as problems. */
    public static boolean isProblem(int status) {
        return status == SKIPPED || status == FAILED || status == MISMATCH || status == MISSING;
    }

    public boolean isProblem() {
        return isProblem(status);
    }

    /** Number of problems in a per-status count array (see {@link Journal#counts()}). */
    public static int problems(int[] counts) {
        int n = 0;
        for (int s = 0; s < counts.length; s++) if (isProblem(s)) n += counts[s];
        return n;
    }
}
