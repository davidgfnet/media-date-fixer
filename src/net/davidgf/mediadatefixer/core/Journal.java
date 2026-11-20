package net.davidgf.mediadatefixer.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Text job journal. Written atomically (temp + rename) with all planned items before anything
 * is touched; per-file status lines are appended and fsync'ed after every chunk.
 *
 * <pre>
 * H  MDFJ1 type created ref title
 * I  idx size oldMtime newMtime oldExif newExif oldMp4 newMp4 path
 * S  idx status actualMtime actualSize msg      (appended; last one wins)
 * E  time cancelled(0|1)                         (job finished)
 * U  undoJournalId                               (this job was undone)
 * </pre>
 * Fields are tab-separated; strings are escaped; "\N" is null. Malformed (torn) lines are ignored.
 */
public final class Journal {
    public static final String MAGIC = "MDFJ1";
    public static final String TYPE_APPLY = "apply";
    public static final String TYPE_UNDO = "undo";
    public static final String EXT = ".mdj";

    public final File file;
    public String type = TYPE_APPLY;
    public String title = "";
    public String ref = "";
    public long created;
    public long finishedAt;
    public boolean cancelled;
    public String undoneBy;
    public final List<JournalItem> items = new ArrayList<>();

    private FileOutputStream fos;
    private Writer out;

    private Journal(File file) {
        this.file = file;
    }

    public String id() {
        String n = file.getName();
        return n.endsWith(EXT) ? n.substring(0, n.length() - EXT.length()) : n;
    }

    public boolean isFinished() {
        return finishedAt > 0;
    }

    public static Journal create(File file, String type, String title, String ref, List<JournalItem> items) throws IOException {
        Journal j = new Journal(file);
        j.type = type;
        j.title = title == null ? "" : title;
        j.ref = ref == null ? "" : ref;
        j.created = System.currentTimeMillis();
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            Writer w = new BufferedWriter(new OutputStreamWriter(fo, StandardCharsets.UTF_8), 1 << 16);
            w.write("H\t" + MAGIC + "\t" + esc(j.type) + "\t" + j.created + "\t" + esc(j.ref) + "\t" + esc(j.title) + "\n");
            int idx = 0;
            for (JournalItem it : items) {
                it.idx = idx++;
                it.status = JournalItem.PENDING;
                w.write("I\t" + it.idx + "\t" + it.size + "\t" + it.oldMtime + "\t" + it.newMtime + "\t"
                        + esc(it.oldExif) + "\t" + esc(it.newExif) + "\t" + it.oldMp4 + "\t" + it.newMp4 + "\t"
                        + esc(it.path) + "\n");
                j.items.add(it);
            }
            w.flush();
            fo.getFD().sync();
        }
        if (!tmp.renameTo(file)) {
            tmp.delete();
            throw new IOException("Could not write journal " + file);
        }
        return j;
    }

    public static Journal load(File file) throws IOException {
        Journal j = new Journal(file);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8), 1 << 16)) {
            String line = r.readLine();
            if (line == null) throw new IOException("Empty journal");
            String[] h = line.split("\t", -1);
            if (h.length < 6 || !h[0].equals("H") || !h[1].equals(MAGIC)) throw new IOException("Not a journal: " + file);
            j.type = unesc(h[2]);
            j.created = Long.parseLong(h[3]);
            j.ref = unesc(h[4]);
            j.title = unesc(h[5]);
            while ((line = r.readLine()) != null) {
                try {
                    j.parseLine(line.split("\t", -1));
                } catch (RuntimeException ignored) {
                    // torn/garbled line from a crash: skip it
                }
            }
        }
        return j;
    }

    private void parseLine(String[] f) {
        switch (f[0]) {
            case "I": {
                if (f.length != 10) return;
                JournalItem it = new JournalItem();
                it.idx = Integer.parseInt(f[1]);
                it.size = Long.parseLong(f[2]);
                it.oldMtime = Long.parseLong(f[3]);
                it.newMtime = Long.parseLong(f[4]);
                it.oldExif = unesc(f[5]);
                it.newExif = unesc(f[6]);
                it.oldMp4 = Long.parseLong(f[7]);
                it.newMp4 = Long.parseLong(f[8]);
                it.path = unesc(f[9]);
                if (it.idx != items.size()) return;
                items.add(it);
                break;
            }
            case "S": {
                if (f.length != 6) return;
                int idx = Integer.parseInt(f[1]);
                int status = Integer.parseInt(f[2]);
                if (idx < 0 || idx >= items.size() || status < 0 || status >= JournalItem.STATUS_COUNT) return;
                JournalItem it = items.get(idx);
                it.actualMtime = Long.parseLong(f[3]);
                it.actualSize = Long.parseLong(f[4]);
                it.msg = unesc(f[5]);
                it.status = status;
                break;
            }
            case "E":
                if (f.length < 3) return;
                finishedAt = Long.parseLong(f[1]);
                cancelled = f[2].equals("1");
                break;
            case "U":
                if (f.length >= 2) undoneBy = unesc(f[1]);
                break;
            default:
                break;
        }
    }

    /** Appends a status line (buffered; call {@link #flush()} to make it durable). */
    public synchronized void record(JournalItem it) throws IOException {
        open();
        out.write("S\t" + it.idx + "\t" + it.status + "\t" + it.actualMtime + "\t" + it.actualSize + "\t" + esc(it.msg) + "\n");
    }

    /** Flushes and fsyncs pending status lines. */
    public synchronized void flush() throws IOException {
        if (out == null) return;
        out.flush();
        fos.getFD().sync();
    }

    public synchronized void markFinished(boolean cancelled) throws IOException {
        finishedAt = System.currentTimeMillis();
        this.cancelled = cancelled;
        appendFinal("E\t" + finishedAt + "\t" + (cancelled ? "1" : "0"));
    }

    public synchronized void markUndone(String undoId) throws IOException {
        undoneBy = undoId;
        appendFinal("U\t" + esc(undoId));
    }

    /** Appends one record, makes it durable and closes the file. */
    private void appendFinal(String line) throws IOException {
        open();
        out.write(line + "\n");
        flush();
        close();
    }

    public synchronized void close() {
        if (out == null) return;
        try {
            out.flush();
            fos.getFD().sync();
            out.close();
        } catch (IOException ignored) {
            // best effort
        }
        out = null;
        fos = null;
    }

    private void open() throws IOException {
        if (out != null) return;
        fos = new FileOutputStream(file, true);
        out = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8), 1 << 15);
    }

    public int[] counts() {
        int[] c = new int[JournalItem.STATUS_COUNT];
        for (JournalItem it : items) c[it.status]++;
        return c;
    }

    public int pending() {
        return counts()[JournalItem.PENDING];
    }

    /** An apply job that was not undone yet and actually touched files. */
    public boolean canUndo() {
        return TYPE_APPLY.equals(type) && undoneBy == null && !undoItems().isEmpty();
    }

    /** Items that reverse what this job did (only files it actually touched). */
    public List<JournalItem> undoItems() {
        List<JournalItem> out = new ArrayList<>();
        for (JournalItem it : items) {
            if (it.status != JournalItem.DONE && it.status != JournalItem.ALREADY && it.status != JournalItem.MISMATCH) continue;
            JournalItem u = new JournalItem();
            u.path = it.path;
            u.size = it.actualSize;
            u.oldMtime = it.actualMtime;
            u.newMtime = it.oldMtime;
            if (it.newExif != null) {
                u.oldExif = it.newExif;
                u.newExif = ExifDates.restoring(it.oldExif, it.newExif);
            }
            if (it.newMp4 >= 0 && it.oldMp4 >= 0) {
                u.oldMp4 = it.newMp4;
                u.newMp4 = it.oldMp4;
            }
            out.add(u);
        }
        return out;
    }

    static String esc(String s) {
        if (s == null) return "\\N";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\t': sb.append("\\t"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unesc(String s) {
        if (s.equals("\\N")) return null;
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
