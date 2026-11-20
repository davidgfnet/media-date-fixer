package net.davidgf.mediadatefixer;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** Explains and requests MANAGE_EXTERNAL_STORAGE; explains more if the user comes back without it. */
public final class OnboardingActivity extends BaseActivity {
    private static final String KEY_ATTEMPTS = "perm_attempts";
    private TextView title, more;
    private Button grant;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);
        title = findViewById(R.id.perm_title);
        more = findViewById(R.id.perm_more);
        grant = findViewById(R.id.perm_grant);
        grant.setOnClickListener(v -> openSettings());
        findViewById(R.id.perm_exit).setOnClickListener(v -> finishAffinity());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Environment.isExternalStorageManager()) {
            finish();
            return;
        }
        boolean retry = ConfigStore.prefs(this).getInt(KEY_ATTEMPTS, 0) > 0;
        title.setText(retry ? R.string.perm_more_title : R.string.perm_title);
        more.setVisibility(retry ? View.VISIBLE : View.GONE);
        grant.setText(retry ? R.string.perm_again : R.string.perm_grant);
    }

    private void openSettings() {
        ConfigStore.prefs(this).edit().putInt(KEY_ATTEMPTS, ConfigStore.prefs(this).getInt(KEY_ATTEMPTS, 0) + 1).apply();
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    @Override
    public void onBackPressed() {
        finishAffinity();
    }
}
