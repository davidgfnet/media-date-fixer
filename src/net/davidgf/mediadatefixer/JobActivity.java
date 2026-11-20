package net.davidgf.mediadatefixer;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import net.davidgf.mediadatefixer.core.Journal;
import net.davidgf.mediadatefixer.core.JournalItem;
import net.davidgf.mediadatefixer.core.JobRunner;

import java.io.File;

/** Live progress of a running job, or the result / resume screen of a stored journal. */
public final class JobActivity extends BaseActivity implements JobState.Listener {
    static final String EXTRA_JOURNAL = "journal";
    private static final int MAX_PROBLEMS = 200;

    private File file;
    private Journal loaded;
    private boolean showingProgress;
    private int loadToken;

    private View progressCard, resultCard, problemsCard, interruptedCard, bottom;
    private TextView progressTitle, progressCount, progressFile;
    private ProgressBar progress;
    private Button pauseButton, undoButton, doneButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_job);
        setupHeader(R.string.job_title);
        progressCard = findViewById(R.id.progress_card);
        resultCard = findViewById(R.id.result_card);
        problemsCard = findViewById(R.id.problems_card);
        interruptedCard = findViewById(R.id.interrupted_card);
        bottom = findViewById(R.id.job_bottom);
        progressTitle = findViewById(R.id.progress_title);
        progressCount = findViewById(R.id.progress_count);
        progressFile = findViewById(R.id.progress_file);
        progress = findViewById(R.id.progress);
        pauseButton = findViewById(R.id.pause_button);
        undoButton = findViewById(R.id.undo_button);
        doneButton = findViewById(R.id.done_button);

        pauseButton.setOnClickListener(v -> {
            if (JobState.runner == null) return;
            ApplyService.control(this, JobState.paused() ? ApplyService.ACTION_RESUME : ApplyService.ACTION_PAUSE);
        });
        findViewById(R.id.cancel_button).setOnClickListener(v -> ApplyService.control(this, ApplyService.ACTION_CANCEL));
        setFile(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setFile(intent);
        render();
    }

    private void setFile(Intent i) {
        String p = i.getStringExtra(EXTRA_JOURNAL);
        file = p == null ? null : new File(p);
        loaded = null;
        if (file == null) finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        JobState.add(this);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        JobState.remove(this);
    }

    @Override
    public void onJobUpdate() {
        render();
    }

    private void render() {
        if (file == null) return;
        if (JobState.isRunning(file)) {
            showingProgress = true;
            loaded = null;
            showProgress(JobState.progress);
            return;
        }
        if (loaded != null && !showingProgress) return;
        showingProgress = false;
        final int token = ++loadToken;
        final File f = file;
        Ui.async(this, () -> Journal.load(f), j -> {
            if (token != loadToken) return;
            loaded = j;
            showJournal(j);
        }, e -> finish());
    }

    private void showProgress(JobRunner.Progress p) {
        progressCard.setVisibility(View.VISIBLE);
        resultCard.setVisibility(View.GONE);
        problemsCard.setVisibility(View.GONE);
        interruptedCard.setVisibility(View.GONE);
        bottom.setVisibility(View.GONE);
        progressTitle.setText(JobState.titleRes());
        pauseButton.setText(JobState.paused() ? R.string.resume : R.string.pause);
        pauseButton.setEnabled(JobState.runner != null);
        if (p == null || p.total == 0) {
            progress.setProgress(0);
            progressCount.setText("");
            progressFile.setText("");
            return;
        }
        progress.setProgress((int) (1000L * p.processed / p.total));
        progressCount.setText(getString(R.string.progress_count, Ui.num(p.processed), Ui.num(p.total)));
        progressFile.setText(p.current);
    }

    private void showJournal(Journal j) {
        progressCard.setVisibility(View.GONE);
        bottom.setVisibility(View.VISIBLE);
        int[] c = j.counts();
        int total = j.items.size();
        if (!j.isFinished()) {
            resultCard.setVisibility(View.GONE);
            problemsCard.setVisibility(View.GONE);
            interruptedCard.setVisibility(View.VISIBLE);
            ((TextView) findViewById(R.id.interrupted_body)).setText(getString(R.string.interrupted_job_body,
                    Ui.num(total - j.pending()), Ui.num(total)));
            undoButton.setVisibility(View.VISIBLE);
            undoButton.setText(R.string.discard);
            undoButton.setOnClickListener(v -> Jobs.discard(this, j, () -> {
                loaded = null;
                render();
            }));
            doneButton.setText(R.string.resume);
            doneButton.setOnClickListener(v -> Jobs.resume(this, j));
            return;
        }

        interruptedCard.setVisibility(View.GONE);
        resultCard.setVisibility(View.VISIBLE);
        boolean undoJob = Journal.TYPE_UNDO.equals(j.type);
        ((TextView) findViewById(R.id.result_title)).setText(
                j.cancelled ? R.string.result_cancelled : undoJob ? R.string.result_undone : R.string.result_done);
        ((TextView) findViewById(R.id.result_summary)).setText(getString(R.string.result_summary, j.title, Ui.dateTimeShort(j.created)));
        LinearLayout stats = findViewById(R.id.result_stats);
        stats.removeAllViews();
        addStat(stats, R.string.res_changed, c[JournalItem.DONE], R.color.success, true);
        addStat(stats, R.string.res_already, c[JournalItem.ALREADY], R.color.on_surface, false);
        addStat(stats, R.string.res_skipped, c[JournalItem.SKIPPED] + c[JournalItem.MISSING], R.color.warning, false);
        addStat(stats, R.string.res_failed, c[JournalItem.FAILED], R.color.error, false);
        addStat(stats, R.string.res_mismatch, c[JournalItem.MISMATCH], R.color.error, false);
        addStat(stats, R.string.res_pending, c[JournalItem.PENDING], R.color.on_surface_variant, false);

        TextView note = findViewById(R.id.result_note);
        String noteText = null;
        if (c[JournalItem.MISMATCH] > 0) noteText = getString(R.string.mismatch_note);
        if (j.undoneBy != null) noteText = getString(R.string.undone_already);
        note.setVisibility(noteText == null ? View.GONE : View.VISIBLE);
        note.setText(noteText);

        LinearLayout problems = findViewById(R.id.problems);
        problems.removeAllViews();
        int shownProblems = 0, more = 0;
        for (JournalItem it : j.items) {
            if (!it.isProblem()) continue;
            if (shownProblems >= MAX_PROBLEMS) {
                more++;
                continue;
            }
            shownProblems++;
            TextView t = Ui.text(this, it.name() + "\n" + (it.msg == null ? "" : it.msg), R.style.Text_ItemSmall);
            t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            problems.addView(t);
        }
        if (more > 0) problems.addView(Ui.text(this, getString(R.string.problems_more, Ui.num(more)), R.style.Text_Caption));
        problemsCard.setVisibility(shownProblems > 0 ? View.VISIBLE : View.GONE);

        undoButton.setVisibility(j.canUndo() ? View.VISIBLE : View.GONE);
        undoButton.setText(R.string.undo);
        undoButton.setOnClickListener(v -> Jobs.confirmUndo(this, j));
        doneButton.setText(R.string.done);
        doneButton.setOnClickListener(v -> {
            startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
    }

    private void addStat(LinearLayout parent, int label, int value, int color, boolean always) {
        if (value == 0 && !always) return;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        row.addView(Ui.text(this, getString(label), R.style.Text_Body),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = Ui.text(this, Ui.num(value), R.style.Text_Value);
        v.setTextColor(getColor(value == 0 ? R.color.on_surface_variant : color));
        row.addView(v);
        parent.addView(row);
    }
}
