package net.davidgf.mediadatefixer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import net.davidgf.mediadatefixer.core.Journal;
import net.davidgf.mediadatefixer.core.JournalItem;
import net.davidgf.mediadatefixer.core.JobRunner;

import java.io.File;

/** Foreground service (type dataSync) that runs one journal at a time. */
public final class ApplyService extends Service {
    static final String ACTION_START = "start";
    static final String ACTION_PAUSE = "pause";
    static final String ACTION_RESUME = "resume";
    static final String ACTION_CANCEL = "cancel";
    static final String EXTRA_JOURNAL = "journal";

    private static final String CHANNEL_PROGRESS = "progress";
    private static final String CHANNEL_DONE = "done";
    private static final int ID_PROGRESS = 1;
    private static final int ID_DONE = 2;
    private static final int EXIF_THREADS = 3;
    private static final long WAKE_MS = 10 * 60 * 1000L;

    private NotificationManager nm;
    private Thread worker;
    private long lastNotify;

    /** Starts a job; returns false if another one is running. */
    static boolean start(Context c, File journal, boolean undo) {
        if (!JobState.claim(journal, undo)) return false;
        try {
            c.startForegroundService(new Intent(c, ApplyService.class).setAction(ACTION_START)
                    .putExtra(EXTRA_JOURNAL, journal.getAbsolutePath()));
            return true;
        } catch (RuntimeException e) {
            JobState.release();
            throw e;
        }
    }

    static void control(Context c, String action) {
        if (!JobState.running) return;
        c.startService(new Intent(c, ApplyService.class).setAction(action));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_PROGRESS, getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            startForeground(ID_PROGRESS, build(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            if (worker == null) {
                File f = new File(intent.getStringExtra(EXTRA_JOURNAL));
                worker = new Thread(() -> runJob(f), "apply");
                worker.start();
            }
            return START_NOT_STICKY;
        }
        JobRunner r = JobState.runner;
        if (worker == null || r == null) {
            // Stale notification action after the job ended (or process restart): nothing to do.
            if (worker == null) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) r.pause();
        else if (ACTION_RESUME.equals(action)) r.resume();
        else if (ACTION_CANCEL.equals(action)) r.cancel();
        notifyProgress(true);
        JobState.post(true);
        return START_NOT_STICKY;
    }

    /** Android 15 dataSync time limit reached: stop now, leave the journal resumable. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        JobRunner r = JobState.runner;
        if (r != null) r.interrupt();
    }

    private void runJob(File file) {
        PowerManager.WakeLock wl = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediaDateFixer:apply");
        wl.setReferenceCounted(false);
        wl.acquire(WAKE_MS);
        Journal j = null;
        JobRunner runner = new JobRunner();
        JobRunner.Progress result = null;
        String error = null;
        try {
            j = Journal.load(file);
            JobState.runner = runner;
            result = runner.run(j, new AndroidMedia.Ops(this), EXIF_THREADS, p -> {
                JobState.publish(p);
                notifyProgress(false);
                wl.acquire(WAKE_MS); // extend while making progress
            });
            if (runner.isCancelled()) {
                j.markFinished(true);
            } else if (result.finished) {
                j.markFinished(false);
                if (Journal.TYPE_UNDO.equals(j.type) && !j.ref.isEmpty()) {
                    File orig = Jobs.store(this).byId(j.ref);
                    if (orig.exists()) Journal.load(orig).markUndone(j.id());
                }
            }
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
        } finally {
            if (j != null) j.close();
            if (wl.isHeld()) wl.release();
        }
        final JobRunner.Progress res = result;
        final String err = error;
        final boolean cancelled = runner.isCancelled();
        final File f = file;
        getMainExecutor().execute(() -> finish(f, res, err, cancelled));
    }

    private void finish(File file, JobRunner.Progress p, String error, boolean cancelled) {
        worker = null;
        JobState.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .setAutoCancel(true)
                .setContentIntent(openJob(file));
        if (error != null) {
            b.setContentText(getString(R.string.error, error));
        } else if (p == null || (!p.finished && !cancelled)) {
            b.setContentText(getString(R.string.notif_stopped));
        } else {
            b.setContentText(getString(R.string.notif_done, Ui.num(p.counts[JournalItem.DONE]), Ui.num(JournalItem.problems(p.counts))));
        }
        nm.notify(ID_DONE, b.build());
        stopSelf();
    }

    private void notifyProgress(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastNotify < 1000) return;
        lastNotify = now;
        nm.notify(ID_PROGRESS, build(JobState.progress));
    }

    private Notification build(JobRunner.Progress p) {
        boolean paused = JobState.paused();
        File f = JobState.journalFile;
        Notification.Builder b = new Notification.Builder(this, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentTitle(getString(JobState.titleRes()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        if (f != null) b.setContentIntent(openJob(f));
        if (p != null && p.total > 0) {
            b.setProgress(p.total, p.processed, false);
            b.setContentText(getString(R.string.progress_count, Ui.num(p.processed), Ui.num(p.total)));
        } else {
            b.setProgress(0, 0, true);
        }
        if (JobState.runner != null) {
            b.addAction(action(paused ? ACTION_RESUME : ACTION_PAUSE, paused ? R.string.resume : R.string.pause, 1));
            b.addAction(action(ACTION_CANCEL, R.string.cancel, 2));
        }
        return b.build();
    }

    private Notification.Action action(String action, int label, int code) {
        PendingIntent pi = PendingIntent.getService(this, code, new Intent(this, ApplyService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), getString(label), pi).build();
    }

    private PendingIntent openJob(File f) {
        Intent i = Jobs.intent(this, f).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
