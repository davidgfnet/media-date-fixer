package net.davidgf.mediadatefixer;

import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Well-known media folders. */
final class Paths {
    private Paths() {}

    private static final String[] WHATSAPP = {
            "Android/media/com.whatsapp/WhatsApp/Media",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media",
            "WhatsApp/Media", // pre-Android 11 layout, still present after some restores
            "WhatsApp Business/Media",
    };
    private static final String[] COMMON = {"DCIM", "Pictures", "Download", "Movies"};

    static File root() {
        return Environment.getExternalStorageDirectory();
    }

    static List<File> whatsApp() {
        return existing(WHATSAPP);
    }

    static List<String> whatsAppPaths() {
        List<String> out = new ArrayList<>();
        for (File f : whatsApp()) out.add(f.getAbsolutePath());
        return out;
    }

    static List<File> suggestions() {
        List<File> out = existing(COMMON);
        out.addAll(whatsApp());
        return out;
    }

    /** The folders in {@code relative} (to internal storage) that exist. */
    private static List<File> existing(String[] relative) {
        List<File> out = new ArrayList<>();
        for (String p : relative) {
            File f = new File(root(), p);
            if (f.isDirectory()) out.add(f);
        }
        return out;
    }

    /** Path relative to internal storage, for display. */
    static String pretty(String path) {
        String r = root().getAbsolutePath();
        if (path.equals(r)) return "/";
        if (path.startsWith(r + "/")) return path.substring(r.length() + 1);
        return path;
    }

    /** Short label for a suggestion chip. */
    static String label(File f) {
        String p = pretty(f.getAbsolutePath());
        if (p.contains("WhatsApp Business")) return "WhatsApp Business";
        if (p.contains("WhatsApp")) return "WhatsApp";
        return f.getName();
    }
}
