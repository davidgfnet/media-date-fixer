package net.davidgf.mediadatefixer;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import net.davidgf.mediadatefixer.core.Journal;
import net.davidgf.mediadatefixer.core.JournalItem;
import net.davidgf.mediadatefixer.core.JobRunner;

import java.io.File;
import java.util.List;

public final class HomeActivity extends BaseActivity implements JobState.Listener {
    private View jobCard, lastCard;
    private TextView jobTitle, jobBody, waPath, lastBody;
    private Button jobPrimary, jobSecondary, waButton, lastDetails, lastUndo, lastRemove;
    private boolean wasRunning;
    private int loadToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        jobCard = findViewById(R.id.job_card);
        jobTitle = findViewById(R.id.job_title);
        jobBody = findViewById(R.id.job_body);
        jobPrimary = findViewById(R.id.job_primary);
        jobSecondary = findViewById(R.id.job_secondary);
        waPath = findViewById(R.id.wa_path);
        waButton = findViewById(R.id.wa_button);
        lastCard = findViewById(R.id.last_card);
        lastBody = findViewById(R.id.last_body);
        lastDetails = findViewById(R.id.last_details);
        lastUndo = findViewById(R.id.last_undo);
        lastRemove = findViewById(R.id.last_remove);

        waButton.setOnClickListener(v -> startActivity(new Intent(this, PreviewActivity.class).putExtra(PreviewActivity.EXTRA_WHATSAPP, true)));
        findViewById(R.id.adv_button).setOnClickListener(v -> startActivity(new Intent(this, AdvancedActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Environment.isExternalStorageManager()) {
            startActivity(new Intent(this, OnboardingActivity.class));
            return;
        }
        JobState.add(this);
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        JobState.remove(this);
    }

    @Override
    public void onJobUpdate() {
        if (JobState.running) {
            bindRunning();
        } else if (wasRunning) {
            refresh();
        }
    }

    private void refresh() {
        List<File> wa = Paths.whatsApp();
        if (wa.isEmpty()) {
            waPath.setText(R.string.wa_not_found);
            waButton.setEnabled(false);
        } else {
            StringBuilder sb = new StringBuilder();
            for (File f : wa) sb.append(sb.length() == 0 ? "" : "\n").append(getString(R.string.wa_found, Paths.pretty(f.getAbsolutePath())));
            waPath.setText(sb);
            waButton.setEnabled(true);
        }
        if (JobState.running) bindRunning();
        final int token = ++loadToken;
        Ui.async(this, this::loadRecent, r -> {
            if (token == loadToken) bind(r[0], r[1]);
        });
    }

    /** {newest unfinished journal, newest finished one}, either may be null. Looks at the 10 newest. */
    private Journal[] loadRecent() {
        Journal interrupted = null, last = null;
        int n = 0;
        for (File f : Jobs.store(this).list()) {
            if (JobState.isRunning(f)) continue;
            try {
                Journal j = Journal.load(f);
                if (!j.isFinished()) {
                    if (interrupted == null) interrupted = j;
                } else if (last == null) {
                    last = j;
                }
            } catch (Exception ignored) {
                // unreadable journal: ignore
            }
            if ((interrupted != null && last != null) || ++n >= 10) break;
        }
        return new Journal[] {interrupted, last};
    }

    private void bindRunning() {
        wasRunning = true;
        JobRunner.Progress p = JobState.progress;
        jobCard.setVisibility(View.VISIBLE);
        jobTitle.setText(R.string.running_title);
        jobBody.setText(getString(R.string.running_body, Ui.num(p == null ? 0 : p.processed), Ui.num(p == null ? 0 : p.total)));
        jobSecondary.setVisibility(View.GONE);
        jobPrimary.setText(R.string.show_progress);
        jobPrimary.setOnClickListener(v -> Jobs.open(this, JobState.journalFile));
    }

    private void bind(Journal interrupted, Journal last) {
        if (JobState.running) {
            bindRunning();
        } else {
            wasRunning = false;
            if (interrupted != null) {
                int total = interrupted.items.size();
                jobCard.setVisibility(View.VISIBLE);
                jobTitle.setText(R.string.interrupted_title);
                jobBody.setText(getString(R.string.interrupted_body, interrupted.title,
                        Ui.num(total - interrupted.pending()), Ui.num(total)));
                jobPrimary.setText(R.string.resume);
                jobPrimary.setOnClickListener(v -> Jobs.resume(this, interrupted));
                jobSecondary.setVisibility(View.VISIBLE);
                jobSecondary.setOnClickListener(v -> Jobs.discard(this, interrupted, this::refresh));
            } else {
                jobCard.setVisibility(View.GONE);
            }
        }

        if (last == null) {
            lastCard.setVisibility(View.GONE);
            return;
        }
        lastCard.setVisibility(View.VISIBLE);
        int[] c = last.counts();
        String body = getString(R.string.last_body, last.title, Ui.dateTimeShort(last.created),
                Ui.num(c[JournalItem.DONE]), Ui.num(JournalItem.problems(c)));
        if (last.undoneBy != null) body += "\n" + getString(R.string.last_undone);
        lastBody.setText(body);
        lastDetails.setOnClickListener(v -> Jobs.open(this, last.file));
        lastUndo.setVisibility(last.canUndo() ? View.VISIBLE : View.GONE);
        lastUndo.setOnClickListener(v -> Jobs.confirmUndo(this, last));
        lastRemove.setOnClickListener(v -> Jobs.confirmRemove(this, last, this::refresh));
    }
}
