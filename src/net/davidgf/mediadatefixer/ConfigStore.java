package net.davidgf.mediadatefixer;

import android.content.Context;
import android.content.SharedPreferences;

import net.davidgf.mediadatefixer.core.JobConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Advanced-mode settings and named presets, stored as JSON in SharedPreferences. */
final class ConfigStore {
    private ConfigStore() {}

    private static final String PREFS = "settings";
    private static final String KEY_CURRENT = "advanced";
    private static final String KEY_PRESETS = "presets";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static JobConfig load(Context c) {
        String s = prefs(c).getString(KEY_CURRENT, null);
        if (s != null) {
            try {
                return fromJson(new JSONObject(s));
            } catch (JSONException ignored) {
                // fall back to defaults
            }
        }
        return new JobConfig();
    }

    static void save(Context c, JobConfig cfg) {
        prefs(c).edit().putString(KEY_CURRENT, toJson(cfg).toString()).apply();
    }

    static Map<String, JobConfig> presets(Context c) {
        Map<String, JobConfig> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try {
            JSONObject o = new JSONObject(prefs(c).getString(KEY_PRESETS, "{}"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                out.put(k, fromJson(o.getJSONObject(k)));
            }
        } catch (JSONException ignored) {
            // corrupt: start over
        }
        return out;
    }

    static void savePreset(Context c, String name, JobConfig cfg) {
        Map<String, JobConfig> all = presets(c);
        all.put(name, cfg);
        writePresets(c, all);
    }

    static void deletePreset(Context c, String name) {
        Map<String, JobConfig> all = presets(c);
        all.remove(name);
        writePresets(c, all);
    }

    private static void writePresets(Context c, Map<String, JobConfig> all) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, JobConfig> e : all.entrySet()) o.put(e.getKey(), toJson(e.getValue()));
        } catch (JSONException ignored) {
            return;
        }
        prefs(c).edit().putString(KEY_PRESETS, o.toString()).apply();
    }

    static JSONObject toJson(JobConfig c) {
        JSONObject o = new JSONObject();
        try {
            o.put("folders", new JSONArray(c.folders));
            o.put("recursive", c.recursive);
            JSONArray order = new JSONArray();
            for (JobConfig.Source s : c.order) order.put(s.name());
            o.put("order", order);
            JSONArray enabled = new JSONArray();
            for (JobConfig.Source s : c.enabled) enabled.put(s.name());
            o.put("enabled", enabled);
            o.put("presets", new JSONArray(c.presets));
            o.put("customRegex", c.customRegex);
            o.put("nameRegex", c.nameRegex);
            o.put("extensions", c.extensions);
            o.put("dateFrom", c.dateFrom);
            o.put("dateTo", c.dateTo);
            o.put("rangeOn", c.rangeOn.name());
            o.put("minSize", c.minSize);
            o.put("maxSize", c.maxSize);
            o.put("exifFilter", c.exifFilter.name());
            o.put("mismatch", c.mismatch.name());
            o.put("dayToleranceMin", c.dayToleranceMin);
            o.put("setMtime", c.setMtime);
            o.put("writeExif", c.writeExif);
            o.put("exifTags", new JSONArray(c.exifTags));
            o.put("patchMp4", c.patchMp4);
            o.put("zoneId", c.zoneId);
        } catch (JSONException ignored) {
            // cannot happen with these value types
        }
        return o;
    }

    /** Missing or unreadable values keep {@link JobConfig}'s defaults, one setting at a time. */
    static JobConfig fromJson(JSONObject o) {
        JobConfig c = new JobConfig();
        JSONArray a = o.optJSONArray("folders");
        if (a != null) {
            c.folders = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) c.folders.add(a.optString(i));
        }
        c.recursive = o.optBoolean("recursive", c.recursive);
        a = o.optJSONArray("order");
        if (a != null) {
            List<JobConfig.Source> order = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JobConfig.Source s = enumOr(JobConfig.Source.class, a.optString(i), null);
                if (s != null && !order.contains(s)) order.add(s);
            }
            for (JobConfig.Source s : JobConfig.Source.values()) if (!order.contains(s)) order.add(s);
            c.order = order;
        }
        a = o.optJSONArray("enabled");
        if (a != null) {
            c.enabled = EnumSet.noneOf(JobConfig.Source.class);
            for (int i = 0; i < a.length(); i++) {
                JobConfig.Source s = enumOr(JobConfig.Source.class, a.optString(i), null);
                if (s != null) c.enabled.add(s);
            }
        }
        a = o.optJSONArray("presets");
        if (a != null) {
            c.presets = new LinkedHashSet<>();
            for (int i = 0; i < a.length(); i++) c.presets.add(a.optString(i));
        }
        c.customRegex = o.optString("customRegex", c.customRegex);
        c.nameRegex = o.optString("nameRegex", c.nameRegex);
        c.extensions = o.optString("extensions", c.extensions);
        c.dateFrom = o.optLong("dateFrom", c.dateFrom);
        c.dateTo = o.optLong("dateTo", c.dateTo);
        c.minSize = o.optLong("minSize", c.minSize);
        c.maxSize = o.optLong("maxSize", c.maxSize);
        c.exifFilter = enumOr(JobConfig.ExifFilter.class, o.optString("exifFilter"), c.exifFilter);
        c.mismatch = enumOr(JobConfig.Mismatch.class, o.optString("mismatch"), c.mismatch);
        c.dayToleranceMin = o.optInt("dayToleranceMin", c.dayToleranceMin);
        c.rangeOn = enumOr(JobConfig.RangeOn.class, o.optString("rangeOn"), c.rangeOn);
        c.setMtime = o.optBoolean("setMtime", c.setMtime);
        c.writeExif = o.optBoolean("writeExif", c.writeExif);
        JSONArray tags = o.optJSONArray("exifTags");
        if (tags != null && tags.length() > 0) {
            c.exifTags = new LinkedHashSet<>();
            for (int i = 0; i < tags.length(); i++) c.exifTags.add(tags.optString(i));
        }
        c.patchMp4 = o.optBoolean("patchMp4", c.patchMp4);
        c.zoneId = o.optString("zoneId", c.zoneId);
        return c;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return fallback;
        }
    }
}
