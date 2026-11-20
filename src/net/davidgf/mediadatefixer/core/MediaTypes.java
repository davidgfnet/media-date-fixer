package net.davidgf.mediadatefixer.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** File extension groups. */
public final class MediaTypes {
    private MediaTypes() {}

    public static final Set<String> IMAGE = set("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "dng", "avif", "bmp");
    public static final Set<String> VIDEO = set("mp4", "m4v", "mov", "3gp", "3g2", "mkv", "webm", "avi");
    public static final Set<String> AUDIO = set("m4a", "aac", "mp3", "opus", "ogg", "amr", "wav", "flac");
    /** Formats ExifInterface can read a date from. */
    public static final Set<String> EXIF_READ = set("jpg", "jpeg", "png", "webp", "heic", "heif", "dng");
    /** Formats ExifInterface can write. */
    public static final Set<String> EXIF_WRITE = set("jpg", "jpeg", "png", "webp");
    /** ISO-BMFF containers with a patchable mvhd box. */
    public static final Set<String> MP4 = set("mp4", "m4v", "mov", "3gp", "3g2", "m4a");
    public static final Set<String> ALL;

    static {
        Set<String> all = new HashSet<>(IMAGE);
        all.addAll(VIDEO);
        all.addAll(AUDIO);
        ALL = Collections.unmodifiableSet(all);
    }

    public static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Parses "jpg, .MP4 png" into a lowercase set; empty input gives null (= no restriction). */
    public static Set<String> parseList(String s) {
        if (s == null) return null;
        Set<String> out = new HashSet<>();
        for (String p : s.split("[,;\\s]+")) {
            p = p.trim().toLowerCase(Locale.ROOT);
            if (p.startsWith(".")) p = p.substring(1);
            if (!p.isEmpty()) out.add(p);
        }
        return out.isEmpty() ? null : out;
    }

    private static Set<String> set(String... s) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(s)));
    }
}
