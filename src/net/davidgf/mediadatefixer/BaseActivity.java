package net.davidgf.mediadatefixer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import net.davidgf.mediadatefixer.core.FilenamePattern;

import java.io.IOException;
import java.io.InputStream;

/** Built-in patterns, edge-to-edge layout, header wiring and the one-time notification permission request. */
abstract class BaseActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 9001;
    private static final String KEY_ASKED_NOTIF = "asked_notifications";
    private Runnable afterPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadPresets(this);
    }

    /** Reads res/raw/patterns.xml once per process (every screen that plans or edits configs needs it). */
    static synchronized void loadPresets(Context c) {
        if (FilenamePattern.presetsLoaded()) return;
        try (InputStream in = c.getResources().openRawResource(R.raw.patterns)) {
            FilenamePattern.loadPresets(in);
        } catch (IOException e) {
            throw new IllegalStateException("Bundled patterns.xml is broken", e); // caught by make test first
        }
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        Ui.edgeToEdge(this);
    }

    protected void setupHeader(int title) {
        TextView t = findViewById(R.id.header_title);
        t.setText(title);
        View back = findViewById(R.id.header_back);
        back.setOnClickListener(v -> onBackPressed());
    }

    /** Progress notifications are optional; ask once (Android 13+), then continue regardless. */
    void withNotificationPermission(Runnable r) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                || ConfigStore.prefs(this).getBoolean(KEY_ASKED_NOTIF, false)) {
            r.run();
            return;
        }
        ConfigStore.prefs(this).edit().putBoolean(KEY_ASKED_NOTIF, true).apply();
        afterPermission = r;
        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_NOTIFICATIONS && afterPermission != null) {
            Runnable r = afterPermission;
            afterPermission = null;
            r.run();
        }
    }
}
