package net.davidgf.mediadatefixer.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Directory of journals named "<13-digit millis>-<type>.mdj" so names sort chronologically. */
public final class JournalStore {
    public final File dir;

    public JournalStore(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    private static long lastStamp;

    /** New journal file; stamps are strictly increasing so name order equals creation order. */
    public File newFile(String type) {
        synchronized (JournalStore.class) {
            long t = Math.max(System.currentTimeMillis(), lastStamp + 1);
            List<File> existing = list();
            if (!existing.isEmpty()) {
                try {
                    t = Math.max(t, Long.parseLong(existing.get(0).getName().substring(0, 13)) + 1);
                } catch (RuntimeException ignored) {
                    // foreign file name
                }
            }
            lastStamp = t;
            return new File(dir, String.format(Locale.ROOT, "%013d-%s%s", t, type, Journal.EXT));
        }
    }

    public File byId(String id) {
        return new File(dir, id + Journal.EXT);
    }

    /** Newest first. */
    public List<File> list() {
        File[] fs = dir.listFiles((d, n) -> n.endsWith(Journal.EXT));
        if (fs == null) return new ArrayList<>();
        Arrays.sort(fs, (a, b) -> b.getName().compareTo(a.getName()));
        return new ArrayList<>(Arrays.asList(fs));
    }

    /** Deletes finished journals beyond the newest {@code keep}. Unfinished ones are kept. */
    public void prune(int keep) {
        List<File> all = list();
        int kept = 0;
        for (File f : all) {
            if (kept < keep) {
                kept++;
                continue;
            }
            try {
                if (Journal.load(f).isFinished()) f.delete();
            } catch (Exception e) {
                f.delete();
            }
        }
    }
}
