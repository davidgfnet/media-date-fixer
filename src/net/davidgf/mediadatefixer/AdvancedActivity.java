package net.davidgf.mediadatefixer;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import net.davidgf.mediadatefixer.core.Dates;
import net.davidgf.mediadatefixer.core.ExifDates;
import net.davidgf.mediadatefixer.core.FilenamePattern;
import net.davidgf.mediadatefixer.core.JobConfig;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class AdvancedActivity extends BaseActivity {
    private static final int REQ_PICK = 1;

    private JobConfig cfg;
    private LinearLayout folderList, suggestionChips, sourceList, presetList, exifChips, mismatchChips, rangeChips;
    private TextView folderEmpty, regexStatus, testResult;
    private EditText customRegex, testName, nameFilter, extFilter, sizeMin, sizeMax;
    private Button zoneButton, dateFrom, dateTo;
    private Switch recursive, actMtime, actExif, actMp4;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_advanced);
        setupHeader(R.string.adv_title);
        cfg = ConfigStore.load(this);

        folderList = findViewById(R.id.folder_list);
        folderEmpty = findViewById(R.id.folder_empty);
        suggestionChips = findViewById(R.id.suggestion_chips);
        recursive = findViewById(R.id.recursive);
        sourceList = findViewById(R.id.source_list);
        presetList = findViewById(R.id.preset_list);
        customRegex = findViewById(R.id.custom_regex);
        regexStatus = findViewById(R.id.regex_status);
        testName = findViewById(R.id.test_name);
        testResult = findViewById(R.id.test_result);
        zoneButton = findViewById(R.id.zone_button);
        nameFilter = findViewById(R.id.name_filter);
        extFilter = findViewById(R.id.ext_filter);
        dateFrom = findViewById(R.id.date_from);
        dateTo = findViewById(R.id.date_to);
        sizeMin = findViewById(R.id.size_min);
        sizeMax = findViewById(R.id.size_max);
        exifChips = findViewById(R.id.exif_chips);
        mismatchChips = findViewById(R.id.mismatch_chips);
        rangeChips = findViewById(R.id.range_chips);
        actMtime = findViewById(R.id.act_mtime);
        actExif = findViewById(R.id.act_exif);
        actMp4 = findViewById(R.id.act_mp4);

        findViewById(R.id.add_folder).setOnClickListener(v -> startActivityForResult(
                new Intent(this, FolderPickerActivity.class).putStringArrayListExtra(FolderPickerActivity.EXTRA_PATHS, new ArrayList<>(cfg.folders)),
                REQ_PICK));
        TextWatcher patternWatcher = new SimpleWatcher(this::onPatternChanged);
        customRegex.addTextChangedListener(patternWatcher);
        testName.addTextChangedListener(patternWatcher);
        zoneButton.setOnClickListener(v -> pickZone());
        dateFrom.setOnClickListener(v -> pickDate(true));
        dateTo.setOnClickListener(v -> pickDate(false));
        findViewById(R.id.date_clear).setOnClickListener(v -> {
            cfg.dateFrom = cfg.dateTo = 0;
            updateDates();
        });
        findViewById(R.id.save_preset).setOnClickListener(v -> savePreset());
        findViewById(R.id.load_preset).setOnClickListener(v -> loadPreset());
        findViewById(R.id.scan).setOnClickListener(v -> scan());
        populate();
    }

    @Override
    protected void onPause() {
        super.onPause();
        collect();
        ConfigStore.save(this, cfg);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            ArrayList<String> paths = data.getStringArrayListExtra(FolderPickerActivity.EXTRA_PATHS);
            if (paths != null) {
                cfg.folders = paths;
                renderFolders();
            }
        }
    }

    /** Config -> UI. */
    private void populate() {
        renderFolders();
        recursive.setChecked(cfg.recursive);
        renderSources();
        presetList.removeAllViews();
        for (FilenamePattern p : FilenamePattern.presets()) {
            presetList.addView(Ui.check(this, presetLabel(p), cfg.presets, p.id, this::onPatternChanged));
        }
        customRegex.setText(cfg.customRegex);
        nameFilter.setText(cfg.nameRegex);
        extFilter.setText(cfg.extensions);
        sizeMin.setText(cfg.minSize > 0 ? mb(cfg.minSize) : "");
        sizeMax.setText(cfg.maxSize > 0 ? mb(cfg.maxSize) : "");
        Ui.chips(exifChips, new CharSequence[] {getString(R.string.exif_any), getString(R.string.exif_has), getString(R.string.exif_lacks)},
                cfg.exifFilter.ordinal(), i -> cfg.exifFilter = JobConfig.ExifFilter.values()[i]);
        Ui.chips(mismatchChips, new CharSequence[] {getString(R.string.mismatch_off), getString(R.string.mismatch_any), getString(R.string.mismatch_day)},
                cfg.mismatch.ordinal(), i -> {
                    cfg.mismatch = JobConfig.Mismatch.values()[i];
                    updateTolerance();
                });
        int[] tol = JobConfig.DAY_TOLERANCES;
        CharSequence[] tolLabels = new CharSequence[tol.length];
        int tolSel = 1;
        for (int i = 0; i < tol.length; i++) {
            tolLabels[i] = tol[i] == 0 ? getString(R.string.tolerance_none) : getString(R.string.tolerance_hours, tol[i] / 60);
            if (tol[i] == cfg.dayToleranceMin) tolSel = i;
        }
        cfg.dayToleranceMin = tol[tolSel];
        Ui.chips(findViewById(R.id.tolerance_chips), tolLabels, tolSel, i -> {
            cfg.dayToleranceMin = tol[i];
            updateTolerance();
        });
        updateTolerance();
        Ui.chips(rangeChips, new CharSequence[] {getString(R.string.range_derived), getString(R.string.range_mtime), getString(R.string.range_exif)},
                cfg.rangeOn.ordinal(), i -> cfg.rangeOn = JobConfig.RangeOn.values()[i]);
        actMtime.setChecked(cfg.setMtime);
        actExif.setChecked(cfg.writeExif);
        actMp4.setChecked(cfg.patchMp4);
        renderExifTags();
        updateZone();
        updateDates();
        onPatternChanged();
    }

    private void updateTolerance() {
        boolean day = cfg.mismatch == JobConfig.Mismatch.DAY;
        findViewById(R.id.tolerance_box).setVisibility(day ? View.VISIBLE : View.GONE);
        findViewById(R.id.tolerance_warning).setVisibility(day && cfg.dayToleranceMin >= 180 ? View.VISIBLE : View.GONE);
    }

    private void renderExifTags() {
        LinearLayout box = findViewById(R.id.exif_tags);
        box.removeAllViews();
        String[] keys = ExifDates.KEYS;
        int[] labels = {R.string.exif_tag_original, R.string.exif_tag_digitized, R.string.exif_tag_modified};
        for (int i = 0; i < keys.length; i++) box.addView(Ui.check(this, getText(labels[i]), cfg.exifTags, keys[i], null));
        View expert = findViewById(R.id.expert);
        TextView state = findViewById(R.id.expert_state);
        boolean custom = !(cfg.exifTags.size() == 1 && cfg.exifTags.contains(ExifDates.ORIGINAL));
        expert.setVisibility(custom ? View.VISIBLE : View.GONE);
        state.setText(custom ? R.string.hide : R.string.show);
        findViewById(R.id.expert_toggle).setOnClickListener(v -> {
            boolean open = expert.getVisibility() != View.VISIBLE;
            expert.setVisibility(open ? View.VISIBLE : View.GONE);
            state.setText(open ? R.string.hide : R.string.show);
        });
    }

    /** UI -> config (fields that are not updated live). */
    private void collect() {
        cfg.whatsappMode = false;
        cfg.recursive = recursive.isChecked();
        cfg.customRegex = customRegex.getText().toString().trim();
        cfg.nameRegex = nameFilter.getText().toString().trim();
        cfg.extensions = extFilter.getText().toString().trim();
        cfg.minSize = bytes(sizeMin);
        cfg.maxSize = bytes(sizeMax);
        cfg.setMtime = actMtime.isChecked();
        cfg.writeExif = actExif.isChecked();
        cfg.patchMp4 = actMp4.isChecked();
    }

    private void renderFolders() {
        folderList.removeAllViews();
        for (String path : new ArrayList<>(cfg.folders)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(Ui.text(this, Paths.pretty(path), R.style.Text_Item),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ImageButton rm = iconButton(R.drawable.ic_close, R.string.remove);
            rm.setOnClickListener(v -> {
                cfg.folders.remove(path);
                renderFolders();
            });
            row.addView(rm);
            folderList.addView(row);
        }
        folderEmpty.setVisibility(cfg.folders.isEmpty() ? View.VISIBLE : View.GONE);

        suggestionChips.removeAllViews();
        for (File f : Paths.suggestions()) {
            String p = f.getAbsolutePath();
            if (cfg.folders.contains(p)) continue;
            TextView chip = Ui.chip(this, "+ " + Paths.label(f));
            chip.setOnClickListener(v -> {
                cfg.folders.add(p);
                renderFolders();
            });
            suggestionChips.addView(chip);
        }
        ((View) suggestionChips.getParent()).setVisibility(suggestionChips.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void renderSources() {
        sourceList.removeAllViews();
        for (int i = 0; i < cfg.order.size(); i++) {
            final int idx = i;
            JobConfig.Source s = cfg.order.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(Ui.check(this, getText(sourceLabel(s)), cfg.enabled, s, null),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ImageButton up = iconButton(R.drawable.ic_up, R.string.move_up);
            up.setEnabled(i > 0);
            up.setAlpha(i > 0 ? 1f : 0.3f);
            up.setOnClickListener(v -> {
                Collections.swap(cfg.order, idx, idx - 1);
                renderSources();
            });
            ImageButton down = iconButton(R.drawable.ic_down, R.string.move_down);
            boolean last = i == cfg.order.size() - 1;
            down.setEnabled(!last);
            down.setAlpha(last ? 0.3f : 1f);
            down.setOnClickListener(v -> {
                Collections.swap(cfg.order, idx, idx + 1);
                renderSources();
            });
            row.addView(up);
            row.addView(down);
            sourceList.addView(row);
        }
    }

    /** "Camera (IMG_20230115_143022)": the label plus the first matching example, without extension. */
    private static String presetLabel(FilenamePattern p) {
        for (String[] ex : p.examples) {
            if (ex[1].equals("none")) continue;
            int dot = ex[0].lastIndexOf('.');
            return p.label + " (" + (dot > 0 ? ex[0].substring(0, dot) : ex[0]) + ")";
        }
        return p.label;
    }

    private int sourceLabel(JobConfig.Source s) {
        switch (s) {
            case FILENAME: return R.string.src_filename;
            case EXIF: return R.string.src_exif;
            case VIDEO: return R.string.src_video;
            default: return R.string.src_mtime;
        }
    }

    private ImageButton iconButton(int icon, int desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setBackgroundResource(R.drawable.bg_btn_text);
        b.setContentDescription(getString(desc));
        int s = Ui.dp(this, 44);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return b;
    }

    /** Validates the custom regex and runs the filename tester. */
    private void onPatternChanged() {
        cfg.customRegex = customRegex.getText().toString().trim();
        regexStatus.setText(R.string.custom_regex_help);
        regexStatus.setTextColor(getColor(R.color.on_surface_variant));
        List<FilenamePattern> patterns;
        try {
            patterns = cfg.patterns();
        } catch (IllegalArgumentException e) {
            regexStatus.setText(getString(R.string.err_regex, firstLine(e.getMessage())));
            regexStatus.setTextColor(getColor(R.color.error));
            patterns = cfg.presetPatterns(); // still test the presets
        }
        String name = testName.getText().toString().trim();
        if (name.isEmpty()) {
            testResult.setVisibility(View.GONE);
            return;
        }
        ZoneId zone = Dates.zone(cfg.zoneId);
        String result = getString(R.string.test_none);
        for (FilenamePattern p : patterns) {
            long v = p.parse(name, zone);
            if (v != Dates.NONE) {
                result = getString(R.string.test_result, Ui.date(v, zone), p.id);
                break;
            }
        }
        testResult.setText(result);
        testResult.setVisibility(View.VISIBLE);
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private void updateZone() {
        zoneButton.setText(cfg.zoneId.isEmpty() ? getString(R.string.tz_system, ZoneId.systemDefault().getId()) : cfg.zoneId);
    }

    private void pickZone() {
        List<String> all = new ArrayList<>(ZoneId.getAvailableZoneIds());
        Collections.sort(all);
        String system = getString(R.string.tz_system, ZoneId.systemDefault().getId());
        LinearLayout box = Ui.dialogBox(this);
        EditText filter = new EditText(this);
        filter.setHint(R.string.tz_filter);
        filter.setSingleLine(true);
        filter.setBackgroundResource(R.drawable.bg_input);
        filter.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        box.addView(filter);
        ListView lv = new ListView(this);
        List<String> shown = new ArrayList<>();
        shown.add(system);
        shown.addAll(all);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>(shown));
        lv.setAdapter(ad);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 360)));
        AlertDialog d = new AlertDialog.Builder(this).setTitle(R.string.timezone).setView(box)
                .setNegativeButton(R.string.cancel, null).create();
        filter.addTextChangedListener(new SimpleWatcher(() -> {
            String q = filter.getText().toString().trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            shown.clear();
            if (q.isEmpty()) shown.add(system);
            for (String z : all) if (q.isEmpty() || z.toLowerCase(Locale.ROOT).contains(q)) shown.add(z);
            ad.clear();
            ad.addAll(shown);
        }));
        lv.setOnItemClickListener((p, v, pos, id) -> {
            String z = shown.get(pos);
            cfg.zoneId = z.equals(system) ? "" : z;
            updateZone();
            onPatternChanged();
            d.dismiss();
        });
        d.show();
    }

    private void pickDate(boolean from) {
        ZoneId zone = Dates.zone(cfg.zoneId);
        long cur = from ? cfg.dateFrom : cfg.dateTo;
        LocalDate init = cur > 0 ? Dates.localDate(cur, zone) : LocalDate.now(zone);
        new DatePickerDialog(this, (v, y, m, day) -> {
            LocalDate d = LocalDate.of(y, m + 1, day);
            if (from) cfg.dateFrom = Dates.startOfDay(d, zone);
            else cfg.dateTo = Dates.startOfDay(d.plusDays(1), zone) - 1; // inclusive end of the day
            updateDates();
        }, init.getYear(), init.getMonthValue() - 1, init.getDayOfMonth()).show();
    }

    private void updateDates() {
        ZoneId zone = Dates.zone(cfg.zoneId);
        dateFrom.setText(getString(R.string.date_from, cfg.dateFrom > 0 ? Ui.day(cfg.dateFrom, zone) : getString(R.string.any)));
        dateTo.setText(getString(R.string.date_to, cfg.dateTo > 0 ? Ui.day(cfg.dateTo, zone) : getString(R.string.any)));
    }

    private void savePreset() {
        collect();
        EditText name = new EditText(this);
        name.setHint(R.string.preset_name);
        name.setSingleLine(true);
        LinearLayout box = Ui.dialogBox(this);
        box.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle(R.string.save_preset).setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) return;
                    ConfigStore.savePreset(this, n, ConfigStore.fromJson(ConfigStore.toJson(cfg)));
                    Ui.toast(this, getString(R.string.preset_saved));
                })
                .show();
    }

    private void loadPreset() {
        Map<String, JobConfig> presets = ConfigStore.presets(this);
        if (presets.isEmpty()) {
            Ui.toast(this, getString(R.string.no_presets));
            return;
        }
        String[] names = presets.keySet().toArray(new String[0]);
        AlertDialog d = new AlertDialog.Builder(this).setTitle(R.string.load_preset)
                .setItems(names, (dlg, which) -> {
                    cfg = presets.get(names[which]);
                    populate();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.setOnShowListener(x -> d.getListView().setOnItemLongClickListener((p, v, pos, id) -> {
            new AlertDialog.Builder(this).setMessage(getString(R.string.preset_delete_q, names[pos]))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (a, b) -> {
                        ConfigStore.deletePreset(this, names[pos]);
                        d.dismiss();
                    })
                    .show();
            return true;
        }));
        d.show();
    }

    private void scan() {
        collect();
        String err = validate();
        if (err != null) {
            new AlertDialog.Builder(this).setMessage(err).setPositiveButton(R.string.ok, null).show();
            return;
        }
        ConfigStore.save(this, cfg);
        startActivity(new Intent(this, PreviewActivity.class));
    }

    private String validate() {
        if (cfg.folders.isEmpty()) return getString(R.string.err_no_folders);
        if (cfg.chain().isEmpty()) return getString(R.string.err_no_sources);
        if (!cfg.setMtime && !cfg.writeExif && !cfg.patchMp4) return getString(R.string.err_no_actions);
        if (cfg.writeExif && cfg.exifTags.isEmpty()) return getString(R.string.err_no_exif_tags);
        try {
            if (cfg.enabled.contains(JobConfig.Source.FILENAME) && cfg.patterns().isEmpty()) return getString(R.string.err_no_patterns);
            if (!cfg.nameRegex.isEmpty()) Pattern.compile(cfg.nameRegex);
        } catch (IllegalArgumentException e) {
            return getString(R.string.err_regex, firstLine(e.getMessage()));
        }
        return null;
    }

    private static String mb(long bytes) {
        double v = bytes / (1024.0 * 1024.0);
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    private static long bytes(EditText e) {
        try {
            double v = Double.parseDouble(e.getText().toString().trim().replace(',', '.'));
            return v > 0 ? (long) (v * 1024 * 1024) : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static final class SimpleWatcher implements TextWatcher {
        private final Runnable r;

        SimpleWatcher(Runnable r) {
            this.r = r;
        }

        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable s) { r.run(); }
    }
}
