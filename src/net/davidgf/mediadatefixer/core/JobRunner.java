package net.davidgf.mediadatefixer.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Applies a journal. Chunks of {@link #CHUNK} files: content writes (EXIF / MP4) run on a small
 * pool, then mtimes are set sequentially (always last, since content writes reset mtime), then
 * statuses are fsync'ed and the chunk is handed to the media scanner. Every step is idempotent,
 * so a journal can be re-run after a crash.
 */
public final class JobRunner {
    public static final int CHUNK = 100;

    /** File operations; the base class is pure Java (mtime + MP4), Android adds EXIF + rescan. */
    public static class FileOps {
        public boolean exists(File f) { return f.isFile(); }
        public long size(File f) { return f.length(); }
        public long mtime(File f) { return f.lastModified(); }
        public boolean setMtime(File f, long ms) { return f.setLastModified(ms); }

        /** Current EXIF date tags as an {@link ExifDates} string. */
        public String readExif(File f) throws IOException { throw new IOException("EXIF not supported"); }
        /** Writes the tags in {@code encoded} (an {@link ExifDates} string). */
        public void writeExif(File f, String encoded) throws IOException { throw new IOException("EXIF not supported"); }

        public long readMp4(File f) throws IOException { return Mp4Dates.readCreationRaw(f); }
        public void writeMp4(File f, long raw) throws IOException { Mp4Dates.writeCreationRaw(f, raw); }

        /** Notify the media index that these files changed. */
        public void scan(List<String> paths) {}
    }

    public interface Listener {
        void onProgress(Progress p);
    }

    public static final class Progress {
        public int total;
        public int processed;
        public int[] counts = new int[JournalItem.STATUS_COUNT];
        public String current = "";
        public boolean paused;
        public boolean finished;
    }

    private final Object lock = new Object();
    private volatile boolean paused, cancelled, interrupted;

    public void pause() { synchronized (lock) { paused = true; } }
    public void resume() { synchronized (lock) { paused = false; lock.notifyAll(); } }
    /** User cancel: stop after the current file; the job is considered finished. */
    public void cancel() { synchronized (lock) { cancelled = true; paused = false; lock.notifyAll(); } }
    /** System stop (e.g. FGS timeout): stop like cancel, but the job stays resumable. */
    public void interrupt() { synchronized (lock) { interrupted = true; cancelled = true; paused = false; lock.notifyAll(); } }
    public boolean isPaused() { return paused; }
    public boolean isCancelled() { return cancelled && !interrupted; }

    /** Blocks while paused. Returns false if the job was stopped. */
    private boolean checkpoint() {
        synchronized (lock) {
            while (paused && !cancelled) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelled = interrupted = true;
                }
            }
            return !cancelled;
        }
    }

    private static final int PROCEED = 0, STOPPED = 1, RESOLVED = 2;

    public Progress run(Journal j, FileOps ops, int threads, Listener listener) throws IOException {
        Progress p = new Progress();
        p.total = j.items.size();
        List<JournalItem> todo = new ArrayList<>();
        for (JournalItem it : j.items) {
            if (it.status == JournalItem.PENDING) todo.add(it);
            else p.processed++;
            p.counts[it.status]++;
        }
        boolean anyContent = false;
        for (JournalItem it : todo) anyContent |= it.hasContentChange();
        ExecutorService pool = anyContent && threads > 1 ? Executors.newFixedThreadPool(threads) : null;
        try {
            for (int start = 0; start < todo.size() && !cancelled; start += CHUNK) {
                List<JournalItem> chunk = todo.subList(start, Math.min(todo.size(), start + CHUNK));
                runChunk(j, ops, pool, chunk, p, listener);
            }
        } finally {
            if (pool != null) pool.shutdownNow();
        }
        p.finished = p.counts[JournalItem.PENDING] == 0;
        p.paused = false;
        if (listener != null) listener.onProgress(p);
        return p;
    }

    private void runChunk(Journal j, FileOps ops, ExecutorService pool, List<JournalItem> chunk,
                          Progress p, Listener listener) throws IOException {
        int[] state = new int[chunk.size()];
        java.util.Arrays.fill(state, STOPPED);
        List<Future<?>> futures = new ArrayList<>();
        for (int k = 0; k < chunk.size(); k++) {
            final int i = k;
            JournalItem it = chunk.get(k);
            if (pool != null && it.hasContentChange()) {
                futures.add(pool.submit(() -> state[i] = prepare(it, ops)));
            } else {
                state[i] = prepare(it, ops);
                if (state[i] == STOPPED) break;
            }
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                // prepare() never throws; treat as stop
            }
        }

        List<String> changed = new ArrayList<>();
        for (int k = 0; k < chunk.size(); k++) {
            JournalItem it = chunk.get(k);
            if (state[k] == STOPPED) continue;
            if (state[k] == PROCEED) {
                // Pause/cancel is honoured per file, except when content (EXIF/MP4) was already
                // written: then the mtime must follow, or the file would keep "now" as its date.
                if (!it.hasContentChange() && !checkpoint()) continue;
                p.current = it.name();
                applyMtime(it, ops);
                if (it.status == JournalItem.DONE || it.status == JournalItem.MISMATCH) changed.add(it.path);
            }
            j.record(it);
            p.counts[JournalItem.PENDING]--;
            p.counts[it.status]++;
            p.processed++;
            p.paused = paused;
            if (listener != null) listener.onProgress(p);
        }
        j.flush();
        if (!changed.isEmpty()) ops.scan(changed);
    }

    /** Pre-checks and content writes. Sets a final status, or returns PROCEED for the mtime step. */
    private int prepare(JournalItem it, FileOps ops) {
        if (!checkpoint()) return STOPPED;
        File f = new File(it.path);
        try {
            if (!ops.exists(f)) return resolve(it, JournalItem.MISSING, 0, 0, "File not found");
            long size = ops.size(f), mtime = ops.mtime(f);
            boolean contentDone = true;
            if (it.newExif != null && !ExifDates.matches(ops.readExif(f), it.newExif)) contentDone = false;
            if (it.newMp4 >= 0 && ops.readMp4(f) != it.newMp4) contentDone = false;

            if (!contentDone) {
                if (size != it.size || !Dates.near(mtime, it.oldMtime)) {
                    return resolve(it, JournalItem.SKIPPED, mtime, size, "Changed since scan");
                }
                if (it.newExif != null) {
                    ops.writeExif(f, it.newExif);
                    if (!ExifDates.matches(ops.readExif(f), it.newExif)) {
                        return resolve(it, JournalItem.FAILED, ops.mtime(f), ops.size(f), "EXIF verify failed");
                    }
                }
                if (it.newMp4 >= 0) {
                    ops.writeMp4(f, it.newMp4);
                    if (ops.readMp4(f) != it.newMp4) {
                        return resolve(it, JournalItem.FAILED, ops.mtime(f), ops.size(f), "MP4 verify failed");
                    }
                }
                return PROCEED;
            }
            if (!it.hasContentChange()) {
                if (size != it.size) return resolve(it, JournalItem.SKIPPED, mtime, size, "Size changed since scan");
                if (Dates.near(mtime, it.newMtime)) return resolve(it, JournalItem.ALREADY, mtime, size, null);
                if (!Dates.near(mtime, it.oldMtime)) return resolve(it, JournalItem.SKIPPED, mtime, size, "Modified since scan");
                return PROCEED;
            }
            // Content already has the target values (we wrote it before a crash); size and mtime
            // legitimately differ now, so just (re)apply the mtime.
            if (Dates.near(mtime, it.newMtime)) return resolve(it, JournalItem.ALREADY, mtime, size, null);
            return PROCEED;
        } catch (IOException | RuntimeException e) {
            return resolve(it, JournalItem.FAILED, ops.mtime(f), ops.size(f), String.valueOf(e.getMessage()));
        }
    }

    private void applyMtime(JournalItem it, FileOps ops) {
        File f = new File(it.path);
        try {
            boolean ok = ops.setMtime(f, it.newMtime);
            long actual = ops.mtime(f);
            it.actualMtime = actual;
            it.actualSize = ops.size(f);
            if (!ok) {
                it.status = JournalItem.FAILED;
                it.msg = "Could not set modification time";
            } else if (!Dates.near(actual, it.newMtime)) {
                it.status = JournalItem.MISMATCH;
                it.msg = "Date did not stick (storage reported a different value)";
            } else {
                it.status = JournalItem.DONE;
            }
        } catch (RuntimeException e) {
            it.status = JournalItem.FAILED;
            it.msg = String.valueOf(e.getMessage());
        }
    }

    private static int resolve(JournalItem it, int status, long mtime, long size, String msg) {
        it.actualMtime = mtime;
        it.actualSize = size;
        it.msg = msg;
        it.status = status;
        return RESOLVED;
    }
}
