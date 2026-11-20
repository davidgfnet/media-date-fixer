package net.davidgf.mediadatefixer.core;

import java.io.File;

/** One scanned file and the change planned for it. */
public final class PlanItem {
    public static final int CHANGE = 0, UNCHANGED = 1, NO_DATE = 2;

    public final File file;
    public final long size;
    public final long mtime;

    public int kind = UNCHANGED;
    /** Date derived from the source chain, or {@link Dates#NONE}. */
    public long derived = Dates.NONE;
    public long newMtime;
    /** Source label, e.g. "filename:whatsapp", "exif", "video", "mtime". */
    public String source = "";
    /** Another enabled source disagrees with the chosen one. */
    public boolean conflict;
    /** EXIF tags before/after (an {@link ExifDates} string), null when EXIF is not touched. */
    public String oldExif, newExif;
    /** MP4 raw times, -1 when not touched. */
    public long oldMp4 = -1, newMp4 = -1;

    public PlanItem(File file, long size, long mtime) {
        this.file = file;
        this.size = size;
        this.mtime = mtime;
        this.newMtime = mtime;
    }

    public JournalItem toJournalItem() {
        JournalItem j = new JournalItem();
        j.path = file.getAbsolutePath();
        j.size = size;
        j.oldMtime = mtime;
        j.newMtime = newMtime;
        j.oldExif = oldExif;
        j.newExif = newExif;
        j.oldMp4 = oldMp4;
        j.newMp4 = newMp4;
        return j;
    }
}
