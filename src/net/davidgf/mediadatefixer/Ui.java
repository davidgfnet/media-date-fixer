package net.davidgf.mediadatefixer;

import android.app.Activity;
import android.widget.BaseAdapter;
import android.content.Context;
import android.graphics.Insets;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import net.davidgf.mediadatefixer.core.Dates;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Small UI helpers shared by all screens. */
final class Ui {
    private Ui() {}

    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** Draws behind system bars and pads the root view by the bar/keyboard insets instead. */
    static void edgeToEdge(Activity a) {
        a.getWindow().setDecorFitsSystemWindows(false);
        View root = ((ViewGroup) a.findViewById(android.R.id.content)).getChildAt(0);
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + Math.max(bars.bottom, ime.bottom));
            return WindowInsets.CONSUMED;
        });
    }

    static String date(long ms, ZoneId zone) {
        return FULL.format(Instant.ofEpochMilli(ms).atZone(zone));
    }

    static String day(long ms, ZoneId zone) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(ms).atZone(zone));
    }

    static String time(long ms, ZoneId zone) {
        return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(ms).atZone(zone));
    }

    /** "17 Jan 2025, 10:02 - 10:40" (with an en dash), or with both days if the range crosses midnight. */
    static String range(long a, long b, ZoneId zone) {
        String end = Dates.sameDay(a, b, zone) ? time(b, zone) : day(b, zone) + ", " + time(b, zone);
        return day(a, zone) + ", " + time(a, zone) + " – " + end;
    }

    static String dateTimeShort(long ms) {
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }

    static String num(long n) {
        return String.format(Locale.getDefault(), "%,d", n);
    }

    static void toast(Context c, CharSequence s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }

    /** Background work whose result (or error) is delivered on the UI thread, unless the activity is gone. */
    interface Work<T> {
        T run() throws Exception;
    }

    static <T> void async(Activity a, Work<T> work, Consumer<T> done) {
        async(a, work, done, null);
    }

    static <T> void async(Activity a, Work<T> work, Consumer<T> done, Consumer<Exception> failed) {
        new Thread(() -> {
            T result = null;
            Exception error = null;
            try {
                result = work.run();
            } catch (Exception e) {
                error = e;
            }
            final T r = result;
            final Exception e = error;
            a.runOnUiThread(() -> {
                if (a.isDestroyed()) return;
                if (e == null) {
                    if (done != null) done.accept(r);
                } else if (failed != null) {
                    failed.accept(e);
                }
            });
        }, "async").start();
    }

    /** Binds one list row. */
    interface Binder<T> {
        void bind(View row, T item, int pos);
    }

    /** A ListView adapter over {@code items}, one {@code layout} row per item. */
    static <T> BaseAdapter adapter(Activity a, List<T> items, int layout, Binder<T> binder) {
        return new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int pos, View v, ViewGroup parent) {
                if (v == null) v = a.getLayoutInflater().inflate(layout, parent, false);
                binder.bind(v, items.get(pos), pos);
                return v;
            }
        };
    }

    /** Padded vertical container for a custom dialog body. */
    static LinearLayout dialogBox(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), 0);
        return box;
    }

    /** A label styled with one of the {@code Text.*} styles. */
    static TextView text(Context c, CharSequence s, int style) {
        TextView t = new TextView(c, null, 0, style);
        t.setText(s);
        return t;
    }

    /** A checkbox that adds {@code key} to / removes it from {@code set}, then runs {@code after}. */
    static <T> CheckBox check(Context c, CharSequence label, Set<T> set, T key, Runnable after) {
        CheckBox cb = new CheckBox(c, null, 0, R.style.Check);
        cb.setText(label);
        cb.setChecked(set.contains(key));
        cb.setOnCheckedChangeListener((b, on) -> {
            if (on) set.add(key);
            else set.remove(key);
            if (after != null) after.run();
        });
        return cb;
    }

    static TextView chip(Context c, CharSequence label) {
        TextView t = text(c, label, R.style.Chip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(c, 8));
        t.setLayoutParams(lp);
        return t;
    }

    /** Single-choice chip row. Returns the chip views so labels can be updated later. */
    static TextView[] chips(LinearLayout parent, CharSequence[] labels, int selected, IntConsumer onSelect) {
        parent.removeAllViews();
        TextView[] views = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = chip(parent.getContext(), labels[i]);
            t.setSelected(i == selected);
            t.setOnClickListener(v -> {
                for (TextView o : views) o.setSelected(o == v);
                onSelect.accept(idx);
            });
            views[i] = t;
            parent.addView(t);
        }
        return views;
    }
}
