package net.davidgf.mediadatefixer;

import android.app.AlertDialog;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import net.davidgf.mediadatefixer.core.JobConfig;
import net.davidgf.mediadatefixer.core.Journal;
import net.davidgf.mediadatefixer.core.JournalItem;
import net.davidgf.mediadatefixer.core.PlanItem;
import net.davidgf.mediadatefixer.core.Planner;
import net.davidgf.mediadatefixer.core.RestoreDetector;
import net.davidgf.mediadatefixer.core.Scanner;

import java.io.File;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** Scan + dry-run preview. Nothing is written until Apply. */
public final class PreviewActivity extends BaseActivity {
    static final String EXTRA_WHATSAPP = "whatsapp";

    private static final int F_ALL = 0, F_CHANGE = 1, F_UNCHANGED = 2, F_NODATE = 3, F_CONFLICT = 4;

    private final AtomicBoolean cancel = new AtomicBoolean();
    private final List<PlanItem> all = new ArrayList<>();
    private final List<PlanItem> shown = new ArrayList<>();
    private final int[] counts = new int[5];
    private JobConfig cfg;
    private Planner planner;
    private ZoneId zone;
    /** Everything the scan planned (WhatsApp mode plans everything, see {@link JobConfig#whatsapp}). */
    private List<PlanItem> found;
    /** Planned changes the current scope leaves alone (shown as unchanged). The plan itself is never modified. */
    private Predicate<PlanItem> leave = it -> false;
    private List<RestoreDetector.Cluster> clusters = new ArrayList<>();
    private String scopeTitle = "";
    private int filter = F_ALL;
    private int scanned, excluded;
    private BaseAdapter adapter;
    private ListView list;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);
        setupHeader(R.string.preview_title);
        boolean wa = getIntent().getBooleanExtra(EXTRA_WHATSAPP, false);
        cfg = wa ? JobConfig.whatsapp(Paths.whatsAppPaths()) : ConfigStore.load(this);
        list = findViewById(R.id.list);
        emptyView = findViewById(R.id.list_empty);
        adapter = Ui.adapter(this, shown, R.layout.row_preview, this::bindRow);
        list.setAdapter(adapter);
        list.setEmptyView(emptyView);
        list.setOnItemClickListener((p, v, pos, id) -> MediaPreview.show(this, shown.get(pos).file,
                ((TextView) v.findViewById(R.id.row_dates)).getText(),
                ((TextView) v.findViewById(R.id.row_meta)).getText()));
        scan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancel.set(true);
    }

    private void scan() {
        TextView status = findViewById(R.id.scan_status);
        TextView path = findViewById(R.id.scan_path);
        new Thread(() -> {
            try {
                planner = new Planner(cfg, new AndroidMedia.Meta(), System.currentTimeMillis());
            } catch (IllegalArgumentException e) {
                runOnUiThread(() -> fail(e.getMessage()));
                return;
            }
            zone = planner.zone();
            List<File> roots = new ArrayList<>();
            for (String f : cfg.folders) roots.add(new File(f));
            List<PlanItem> planned = new ArrayList<>();
            final int[] n = {0};
            try {
                Scanner.walk(roots, cfg.recursive, planner.scanExtensions(), cancel, f -> {
                    try {
                        PlanItem it = planner.plan(f);
                        if (it != null) planned.add(it);
                    } catch (RuntimeException e) {
                        // unreadable file (permissions, I/O): leave it out of the plan
                    }
                    if (++n[0] % 25 == 0) {
                        final int c = n[0];
                        final String dir = Paths.pretty(f.getParent());
                        runOnUiThread(() -> {
                            status.setText(getString(R.string.scanning_count, Ui.num(c)));
                            path.setText(dir);
                        });
                    }
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> fail(e.getMessage()));
                return;
            }
            if (cancel.get()) return;
            final int total = n[0], rejected = planner.filtered;
            List<RestoreDetector.Cluster> cs = cfg.whatsappMode
                    ? RestoreDetector.detect(planned, zone) : new ArrayList<>();
            runOnUiThread(() -> scanned(planned, cs, total, rejected));
        }, "scan").start();
    }

    private void fail(String msg) {
        if (isDestroyed()) return;
        new AlertDialog.Builder(this).setMessage(getString(R.string.scan_failed, msg))
                .setPositiveButton(R.string.ok, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void scanned(List<PlanItem> items, List<RestoreDetector.Cluster> cs, int total, int rejected) {
        if (isDestroyed()) return;
        found = items;
        clusters = cs;
        scanned = total;
        excluded = rejected;
        if (!cfg.whatsappMode) show(found, getString(R.string.summary_extra, Ui.num(scanned), Ui.num(excluded)));
        else if (clusters.isEmpty()) selectAll();
        else showChooser();
    }

    @Override
    public void onBackPressed() {
        if (!clusters.isEmpty() && findViewById(R.id.content).getVisibility() == View.VISIBLE) showChooser();
        else super.onBackPressed();
    }

    /** The item's kind within the current scope. */
    private int kind(PlanItem it) {
        return leave.test(it) ? PlanItem.UNCHANGED : it.kind;
    }

    /** WhatsApp "all files": a planned change whose mtime is already on the right day is kept. */
    private boolean onRightDay(PlanItem it) {
        return it.kind == PlanItem.CHANGE && planner.onRightDay(it);
    }

    private void showChooser() {
        findViewById(R.id.scanning).setVisibility(View.GONE);
        findViewById(R.id.content).setVisibility(View.GONE);
        findViewById(R.id.bottom).setVisibility(View.GONE);
        findViewById(R.id.choose).setVisibility(View.VISIBLE);
        LinearLayout list = findViewById(R.id.choose_list);
        list.removeAllViews();
        for (int k = 0; k < clusters.size(); k++) {
            RestoreDetector.Cluster c = clusters.get(k);
            int changes = 0;
            for (PlanItem it : found) if (c.contains(it.mtime) && it.kind == PlanItem.CHANGE) changes++;
            list.addView(choice(k == 0 ? getString(R.string.choose_best) : null,
                    Ui.range(c.start, c.end, zone),
                    getString(R.string.choose_files, Ui.num(c.files), Ui.num(changes)) + "\n"
                            + getString(R.string.choose_names, Ui.day(c.nameMin, zone), Ui.day(c.nameMax, zone)),
                    v -> selectCluster(c)));
        }
        int changes = 0;
        for (PlanItem it : found) if (it.kind == PlanItem.CHANGE && !onRightDay(it)) changes++;
        list.addView(choice(null, getString(R.string.choose_all),
                getString(R.string.choose_all_body, cfg.dayToleranceMin) + "\n" + getString(R.string.choose_all_count, Ui.num(changes)),
                v -> selectAll()));
    }

    private View choice(String badge, String title, String body, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = Ui.dp(this, 20);
        card.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 16));
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        card.setForeground(getDrawable(tv.resourceId));
        card.setClipToOutline(true);
        card.setOnClickListener(click);
        if (badge != null) {
            TextView b = Ui.text(this, badge, R.style.Text_Badge);
            b.setPadding(0, 0, 0, Ui.dp(this, 4));
            card.addView(b);
        }
        card.addView(Ui.text(this, title, R.style.Text_Subtitle));
        TextView d = Ui.text(this, body, R.style.Text_Caption);
        d.setPadding(0, Ui.dp(this, 4), 0, 0);
        d.setLineSpacing(0, 1.15f);
        card.addView(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 12);
        card.setLayoutParams(lp);
        return card;
    }

    /** Restore group: every file written in it is fixed from its name, even on the same day. */
    private void selectCluster(RestoreDetector.Cluster c) {
        leave = it -> false;
        List<PlanItem> in = new ArrayList<>();
        for (PlanItem it : found) if (c.contains(it.mtime)) in.add(it);
        String when = Ui.range(c.start, c.end, zone);
        scopeTitle = getString(R.string.title_restore, when);
        show(in, getString(R.string.scope_cluster, when, Ui.num(scanned), Ui.num(scanned - in.size())));
    }

    private void selectAll() {
        leave = this::onRightDay;
        scopeTitle = "";
        show(found, getString(R.string.summary_extra, Ui.num(scanned), Ui.num(excluded)));
    }

    private void show(List<PlanItem> items, String summary) {
        if (isDestroyed()) return;
        all.clear();
        all.addAll(items);
        Arrays.fill(counts, 0);
        for (PlanItem it : all) {
            int k = kind(it);
            if (k == PlanItem.CHANGE) counts[F_CHANGE]++;
            else if (k == PlanItem.UNCHANGED) counts[F_UNCHANGED]++;
            else counts[F_NODATE]++;
            if (it.conflict) counts[F_CONFLICT]++;
        }
        counts[F_ALL] = all.size();
        findViewById(R.id.scanning).setVisibility(View.GONE);
        findViewById(R.id.choose).setVisibility(View.GONE);
        findViewById(R.id.content).setVisibility(View.VISIBLE);
        findViewById(R.id.bottom).setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.n_change)).setText(Ui.num(counts[F_CHANGE]));
        ((TextView) findViewById(R.id.n_unchanged)).setText(Ui.num(counts[F_UNCHANGED]));
        ((TextView) findViewById(R.id.n_nodate)).setText(Ui.num(counts[F_NODATE]));
        ((TextView) findViewById(R.id.n_conflict)).setText(Ui.num(counts[F_CONFLICT]));
        ((TextView) findViewById(R.id.summary_extra)).setText(summary);

        int[] labels = {R.string.filter_all, R.string.stat_change, R.string.stat_unchanged, R.string.stat_nodate, R.string.stat_conflict};
        CharSequence[] chipLabels = new CharSequence[labels.length];
        for (int i = 0; i < labels.length; i++) chipLabels[i] = getString(labels[i]) + "  " + Ui.num(counts[i]);
        filter = counts[F_CHANGE] > 0 ? F_CHANGE : F_ALL;
        Ui.chips(findViewById(R.id.filter_chips), chipLabels, filter, i -> {
            filter = i;
            applyFilter();
        });
        applyFilter();

        Button apply = findViewById(R.id.apply);
        int n = counts[F_CHANGE];
        apply.setEnabled(n > 0);
        apply.setText(n > 0 ? getString(R.string.apply_n, Ui.num(n)) : getString(R.string.nothing_to_do));
        apply.setOnClickListener(v -> confirm(n));
    }

    private void applyFilter() {
        shown.clear();
        for (PlanItem it : all) {
            boolean ok;
            switch (filter) {
                case F_CHANGE: ok = kind(it) == PlanItem.CHANGE; break;
                case F_UNCHANGED: ok = kind(it) == PlanItem.UNCHANGED; break;
                case F_NODATE: ok = kind(it) == PlanItem.NO_DATE; break;
                case F_CONFLICT: ok = it.conflict; break;
                default: ok = true;
            }
            if (ok) shown.add(it);
        }
        adapter.notifyDataSetChanged();
        list.setSelection(0);
    }

    private void confirm(int n) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_title)
                .setMessage(getString(R.string.confirm_body, Ui.num(n)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.apply, (d, w) -> apply())
                .show();
    }

    private void apply() {
        List<JournalItem> items = new ArrayList<>();
        for (PlanItem it : all) if (kind(it) == PlanItem.CHANGE) items.add(it.toJournalItem());
        String title = cfg.whatsappMode ? getString(R.string.wa_title) + (scopeTitle.isEmpty() ? "" : " · " + scopeTitle)
                : getString(R.string.adv_title) + " · " + folderSummary();
        Jobs.start(this, Journal.TYPE_APPLY, title, "", items, this::finish);
    }

    private String folderSummary() {
        StringBuilder sb = new StringBuilder();
        for (String f : cfg.folders) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(new File(f).getName());
            if (sb.length() > 40) {
                sb.append("…");
                break;
            }
        }
        return sb.toString();
    }

    private void bindRow(View v, PlanItem it, int pos) {
        ((TextView) v.findViewById(R.id.row_name)).setText(it.file.getName());
        TextView dates = v.findViewById(R.id.row_dates);
        TextView meta = v.findViewById(R.id.row_meta);
        View stripe = v.findViewById(R.id.row_stripe);
        String old = Ui.date(it.mtime, zone);
        int color, k = kind(it);
        if (k == PlanItem.CHANGE) {
            dates.setText(old + "\n→ " + Ui.date(it.derived, zone));
            color = it.conflict ? R.color.warning : R.color.accent;
        } else if (k == PlanItem.UNCHANGED) {
            dates.setText(getString(R.string.row_unchanged, old));
            color = R.color.outline;
        } else {
            dates.setText(getString(R.string.row_nodate, old));
            color = R.color.error;
        }
        stripe.getBackground().mutate().setTint(getColor(color));
        StringBuilder m = new StringBuilder();
        if (!it.source.isEmpty()) m.append(it.source);
        if (it.newExif != null) m.append(m.length() > 0 ? " · " : "").append(getString(R.string.tag_exif));
        if (it.newMp4 >= 0) m.append(m.length() > 0 ? " · " : "").append(getString(R.string.tag_mp4));
        if (it.conflict) m.append(m.length() > 0 ? " · " : "").append(getString(R.string.tag_conflict));
        m.append(m.length() > 0 ? " · " : "").append(Paths.pretty(it.file.getParent()));
        meta.setText(m);
    }
}
