package net.davidgf.mediadatefixer;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;

import net.davidgf.mediadatefixer.core.Dates;
import net.davidgf.mediadatefixer.core.ExifDates;
import net.davidgf.mediadatefixer.core.JobRunner;
import net.davidgf.mediadatefixer.core.Planner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Android implementations of metadata reading and file operations. */
final class AndroidMedia {
    private AndroidMedia() {}

    /** {@link ExifDates} key -> {date tag, offset tag}. */
    private static final Map<String, String[]> TAGS = new LinkedHashMap<>();
    static {
        TAGS.put(ExifDates.ORIGINAL, new String[] {ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL});
        TAGS.put(ExifDates.DIGITIZED, new String[] {ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_DIGITIZED});
        TAGS.put(ExifDates.MODIFIED, new String[] {ExifInterface.TAG_DATETIME, ExifInterface.TAG_OFFSET_TIME});
    }

    /** Current values of all three date tags as an {@link ExifDates} string. */
    static String readAll(ExifInterface e) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> t : TAGS.entrySet()) {
            m.put(t.getKey(), ExifDates.encodeValue(e.getAttribute(t.getValue()[0]), e.getAttribute(t.getValue()[1])));
        }
        return ExifDates.encode(m);
    }

    static final class Meta implements Planner.MetaReader {
        @Override
        public Planner.ExifData readExif(File f) {
            try {
                ExifInterface e = new ExifInterface(f);
                return new Planner.ExifData(readAll(e), e.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL));
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        @Override
        public long readVideoDate(File f) {
            try (MediaMetadataRetriever r = new MediaMetadataRetriever()) {
                r.setDataSource(f.getAbsolutePath());
                return Dates.parseVideoDate(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE));
            } catch (IOException | RuntimeException e) {
                return Dates.NONE;
            }
        }
    }

    static final class Ops extends JobRunner.FileOps {
        /** Head-room kept free on top of the temp copy. */
        private static final long SPARE_BYTES = 32L * 1024 * 1024;
        private final Context ctx;

        Ops(Context ctx) {
            this.ctx = ctx.getApplicationContext();
        }

        @Override
        public String readExif(File f) throws IOException {
            return readAll(new ExifInterface(f));
        }

        /**
         * Copy -> edit copy -> verify copy (tags + image still decodes) -> rename over original.
         * The caller sets the mtime afterwards (the rewrite resets it).
         */
        @Override
        public void writeExif(File f, String encoded) throws IOException {
            Map<String, String> tags = ExifDates.decode(encoded);
            File dir = f.getParentFile();
            if (dir == null) throw new IOException("No parent folder");
            if (dir.getUsableSpace() < f.length() + SPARE_BYTES) throw new IOException("Not enough free space");
            File tmp = new File(dir, "." + f.getName() + ".mdf-tmp");
            try {
                copy(f, tmp);
                ExifInterface e = new ExifInterface(tmp);
                for (Map.Entry<String, String> t : tags.entrySet()) {
                    String[] names = TAGS.get(t.getKey());
                    if (names == null) throw new IOException("Unknown EXIF tag " + t.getKey());
                    String[] v = ExifDates.decodeValue(t.getValue());
                    e.setAttribute(names[0], v[0]);
                    e.setAttribute(names[1], v[1]);
                }
                e.saveAttributes();
                if (!ExifDates.matches(readAll(new ExifInterface(tmp)), encoded)) {
                    throw new IOException("EXIF did not verify on the temporary copy");
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(tmp.getAbsolutePath(), o);
                if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("Rewritten image does not decode");
                sync(tmp);
                if (!tmp.renameTo(f)) throw new IOException("Could not replace original");
            } finally {
                if (tmp.exists()) tmp.delete();
            }
        }

        @Override
        public void scan(List<String> paths) {
            MediaScannerConnection.scanFile(ctx, paths.toArray(new String[0]), null, null);
        }

        private static void copy(File from, File to) throws IOException {
            try (FileInputStream in = new FileInputStream(from);
                 FileOutputStream out = new FileOutputStream(to)) {
                FileChannel src = in.getChannel(), dst = out.getChannel();
                long size = src.size(), pos = 0;
                while (pos < size) pos += src.transferTo(pos, size - pos, dst);
                out.getFD().sync();
            }
        }

        private static void sync(File f) throws IOException {
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.getFD().sync();
            }
        }
    }
}
