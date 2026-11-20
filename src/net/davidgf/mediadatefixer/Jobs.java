package net.davidgf.mediadatefixer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;

import net.davidgf.mediadatefixer.core.Journal;
import net.davidgf.mediadatefixer.core.JournalItem;
import net.davidgf.mediadatefixer.core.JournalStore;

import java.io.File;
import java.util.List;

/** Creating, resuming and undoing jobs. */
final class Jobs {
    private Jobs() {}

    private static final int KEEP_JOURNALS = 20;

    static JournalStore store(Context c) {
        return new JournalStore(new File(c.getFilesDir(), "journals"));
    }

    /** Intent for the progress / result screen of a journal. */
    static Intent intent(Context c, File journal) {
        return new Intent(c, JobActivity.class).putExtra(JobActivity.EXTRA_JOURNAL, journal.getAbsolutePath());
    }

    static void open(Context c, File journal) {
        c.startActivity(intent(c, journal));
    }

    /** Starts the service on an existing journal and shows its progress. */
    private static boolean launch(BaseActivity a, File journal, String type) {
        if (!ApplyService.start(a, journal, Journal.TYPE_UNDO.equals(type))) {
            Ui.toast(a, a.getString(R.string.already_running));
            return false;
        }
        open(a, journal);
        return true;
    }

    /** Writes the journal (before touching any file), then starts the service. */
    static void start(BaseActivity a, String type, String title, String ref, List<JournalItem> items, Runnable onStarted) {
        if (JobState.running) {
            Ui.toast(a, a.getString(R.string.already_running));
            return;
        }
        a.withNotificationPermission(() -> Ui.async(a, () -> {
            JournalStore s = store(a);
            s.prune(KEEP_JOURNALS);
            File f = s.newFile(type);
            Journal.create(f, type, title, ref, items);
            return f;
        }, f -> {
            if (launch(a, f, type) && onStarted != null) onStarted.run();
        }, e -> Ui.toast(a, a.getString(R.string.error, e.getMessage()))));
    }

    static void resume(BaseActivity a, Journal j) {
        a.withNotificationPermission(() -> launch(a, j.file, j.type));
    }

    /** Gives up on an interrupted job (marks it finished, cancelled), then runs {@code after}. */
    static void discard(Activity a, Journal j, Runnable after) {
        Ui.async(a, () -> {
            j.markFinished(true);
            return null;
        }, x -> after.run(), e -> after.run());
    }

    /** Asks, then deletes a finished job's journal (its undo record), then runs {@code after}. */
    static void confirmRemove(Activity a, Journal j, Runnable after) {
        new AlertDialog.Builder(a)
                .setTitle(R.string.remove_confirm_title)
                .setMessage(j.canUndo() ? R.string.remove_confirm_undo : R.string.remove_confirm_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (d, w) -> Ui.async(a, () -> {
                    if (!JobState.isRunning(j.file) && !j.file.delete() && j.file.exists()) {
                        throw new java.io.IOException("Could not delete " + j.file);
                    }
                    return null;
                }, x -> after.run(), e -> Ui.toast(a, a.getString(R.string.error, e.getMessage()))))
                .show();
    }

    static void confirmUndo(BaseActivity a, Journal j) {
        List<JournalItem> items = j.undoItems();
        new AlertDialog.Builder(a)
                .setTitle(R.string.undo_confirm_title)
                .setMessage(a.getString(R.string.undo_confirm_body, Ui.num(items.size())))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.undo, (d, w) ->
                        start(a, Journal.TYPE_UNDO, a.getString(R.string.undo) + " · " + j.title, j.id(), items, null))
                .show();
    }
}
