package net.davidgf.mediadatefixer;

import android.content.Intent;
import android.os.Bundle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/** Minimal multi-select folder browser over raw paths (needs all-files access). */
public final class FolderPickerActivity extends BaseActivity {
    static final String EXTRA_PATHS = "paths";

    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private final List<File> dirs = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private final List<File> volumes = new ArrayList<>();
    private final List<String> volumeLabels = new ArrayList<>();
    /** null = list of storage volumes. */
    private File current;
    private TextView pathView, countView, emptyView;
    private CheckBox thisBox;
    private ListView list;
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picker);
        setupHeader(R.string.picker_title);
        ArrayList<String> init = getIntent().getStringArrayListExtra(EXTRA_PATHS);
        if (init != null) selected.addAll(init);

        pathView = findViewById(R.id.picker_path);
        countView = findViewById(R.id.picker_count);
        emptyView = findViewById(R.id.picker_empty);
        thisBox = findViewById(R.id.picker_this);
        list = findViewById(R.id.picker_list);
        adapter = Ui.adapter(this, dirs, R.layout.row_folder, (v, d, pos) -> {
            ((TextView) v.findViewById(R.id.row_name)).setText(labels.get(pos));
            CheckBox cb = v.findViewById(R.id.row_check);
            cb.setChecked(selected.contains(d.getAbsolutePath()));
            cb.setOnClickListener(x -> toggle(d.getAbsolutePath(), cb.isChecked()));
        });
        list.setAdapter(adapter);
        list.setEmptyView(emptyView);
        list.setOnItemClickListener((p, v, pos, id) -> navigate(dirs.get(pos)));
        thisBox.setOnClickListener(v -> toggle(current.getAbsolutePath(), thisBox.isChecked()));
        findViewById(R.id.picker_done).setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putStringArrayListExtra(EXTRA_PATHS, new ArrayList<>(selected)));
            finish();
        });

        StorageManager sm = getSystemService(StorageManager.class);
        for (StorageVolume v : sm.getStorageVolumes()) {
            File d = v.getDirectory();
            if (d != null && d.canRead()) {
                volumes.add(d);
                volumeLabels.add(v.getDescription(this));
            }
        }
        navigate(Paths.root());
    }

    private void toggle(String path, boolean on) {
        if (on) selected.add(path);
        else selected.remove(path);
        updateCount();
    }

    private void updateCount() {
        countView.setText(getString(R.string.picker_selected, Ui.num(selected.size())));
    }

    private void navigate(File dir) {
        current = dir;
        dirs.clear();
        labels.clear();
        if (dir == null) {
            dirs.addAll(volumes);
            labels.addAll(volumeLabels);
            pathView.setText(R.string.picker_storage);
            thisBox.setVisibility(View.GONE);
        } else {
            File[] children = dir.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
            if (children != null) {
                Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File c : children) {
                    dirs.add(c);
                    labels.add(c.getName());
                }
            }
            pathView.setText(dir.getAbsolutePath());
            thisBox.setVisibility(View.VISIBLE);
            thisBox.setChecked(selected.contains(dir.getAbsolutePath()));
        }
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        updateCount();
    }

    @Override
    public void onBackPressed() {
        if (current == null) {
            super.onBackPressed();
            return;
        }
        File parent = current.getParentFile();
        boolean atVolumeRoot = volumes.contains(current);
        if (atVolumeRoot || parent == null || !parent.canRead()) {
            if (volumes.size() > 1 || !atVolumeRoot) navigate(null);
            else super.onBackPressed();
        } else {
            navigate(parent);
        }
    }
}
