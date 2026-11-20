package net.davidgf.mediadatefixer;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import net.davidgf.mediadatefixer.core.JobRunner;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-process bridge between {@link ApplyService} and the UI. */
final class JobState {
    private JobState() {}

    interface Listener {
        void onJobUpdate();
    }

    /** True from the moment a job is requested until its worker thread ends. */
    static volatile boolean running;
    static volatile File journalFile;
    static volatile boolean undo;
    static volatile JobRunner runner;
    /** Snapshot for the UI (never mutated after publishing). */
    static volatile JobRunner.Progress progress;

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static long lastPost;

    static synchronized boolean claim(File journal, boolean isUndo) {
        if (running) return false;
        running = true;
        journalFile = journal;
        undo = isUndo;
        progress = null;
        runner = null;
        return true;
    }

    static synchronized void release() {
        running = false;
        runner = null;
        post(true);
    }

    static boolean paused() {
        JobRunner r = runner;
        return r != null && r.isPaused();
    }

    /** Title for the progress screen and notification. */
    static int titleRes() {
        return paused() ? R.string.paused : undo ? R.string.undoing : R.string.applying;
    }

    static boolean isRunning(File f) {
        return running && f != null && f.equals(journalFile);
    }

    static void publish(JobRunner.Progress p) {
        JobRunner.Progress s = new JobRunner.Progress();
        s.total = p.total;
        s.processed = p.processed;
        s.counts = p.counts.clone();
        s.current = p.current;
        s.paused = p.paused;
        s.finished = p.finished;
        progress = s;
        post(false);
    }

    static void post(boolean force) {
        long now = SystemClock.uptimeMillis();
        synchronized (JobState.class) {
            if (!force && now - lastPost < 150) return;
            lastPost = now;
        }
        main.post(() -> {
            for (Listener l : listeners) l.onJobUpdate();
        });
    }

    static void add(Listener l) {
        listeners.add(l);
    }

    static void remove(Listener l) {
        listeners.remove(l);
    }
}
