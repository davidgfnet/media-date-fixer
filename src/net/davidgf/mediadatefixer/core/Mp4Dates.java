package net.davidgf.mediadatefixer.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and patches creation/modification times in ISO-BMFF (MP4/MOV/3GP) files in place:
 * moov/mvhd plus every trak/tkhd and trak/mdia/mdhd. Fields keep their size, so the file
 * layout never changes. Values are seconds since 1904-01-01 UTC ("raw").
 */
public final class Mp4Dates {
    private Mp4Dates() {}

    public static final long EPOCH_OFFSET = 2082844800L; // seconds between 1904 and 1970

    static final class Field {
        final long pos;
        final int version;
        final String box;

        Field(long pos, int version, String box) {
            this.pos = pos;
            this.version = version;
            this.box = box;
        }
    }

    public static long rawToMillis(long raw) {
        return raw <= 0 ? Dates.NONE : (raw - EPOCH_OFFSET) * 1000L;
    }

    public static long millisToRaw(long ms) {
        return Math.floorDiv(ms, 1000L) + EPOCH_OFFSET;
    }

    /** mvhd creation time (raw), or -1 if the file has no readable mvhd. */
    public static long readCreationRaw(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            for (Field fd : find(raf)) {
                if (!fd.box.equals("mvhd")) continue;
                raf.seek(fd.pos);
                return fd.version == 1 ? raf.readLong() : (raf.readInt() & 0xffffffffL);
            }
        }
        return -1;
    }

    /** Writes raw creation+modification time to all header boxes. Returns boxes patched. */
    public static int writeCreationRaw(File f, long raw) throws IOException {
        if (raw < 0) throw new IOException("negative MP4 time");
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            List<Field> fields = find(raf);
            boolean hasMvhd = false;
            for (Field fd : fields) hasMvhd |= fd.box.equals("mvhd");
            if (!hasMvhd) throw new IOException("no mvhd box");
            for (Field fd : fields) {
                raf.seek(fd.pos);
                if (fd.version == 1) {
                    raf.writeLong(raw);
                    raf.writeLong(raw);
                } else {
                    if (raw > 0xffffffffL) throw new IOException("time does not fit 32-bit " + fd.box);
                    raf.writeInt((int) raw);
                    raf.writeInt((int) raw);
                }
            }
            raf.getFD().sync();
            return fields.size();
        }
    }

    static List<Field> find(RandomAccessFile raf) throws IOException {
        List<Field> out = new ArrayList<>();
        long len = raf.length();
        long pos = 0;
        while (pos + 8 <= len) {
            long[] h = header(raf, pos, len);
            if (h == null) break;
            if (h[2] == fourcc("moov")) {
                walk(raf, pos + h[1], pos + h[0], out, 0);
                break;
            }
            pos += h[0];
        }
        return out;
    }

    private static void walk(RandomAccessFile raf, long start, long end, List<Field> out, int depth) throws IOException {
        long pos = start;
        while (pos + 8 <= end) {
            long[] h = header(raf, pos, end);
            if (h == null) return;
            int type = (int) h[2];
            if (type == fourcc("mvhd") || type == fourcc("tkhd") || type == fourcc("mdhd")) {
                long body = pos + h[1];
                if (body + 20 <= pos + h[0]) {
                    raf.seek(body);
                    int version = raf.read();
                    if (version == 0 || version == 1) out.add(new Field(body + 4, version, name(type)));
                }
            } else if ((type == fourcc("trak") || type == fourcc("mdia")) && depth < 4) {
                walk(raf, pos + h[1], pos + h[0], out, depth + 1);
            }
            pos += h[0];
        }
    }

    /** Returns {size, headerLength, type} or null if the box is malformed. */
    private static long[] header(RandomAccessFile raf, long pos, long end) throws IOException {
        raf.seek(pos);
        long size = raf.readInt() & 0xffffffffL;
        int type = raf.readInt();
        long hl = 8;
        if (size == 1) {
            if (pos + 16 > end) return null;
            size = raf.readLong();
            hl = 16;
        } else if (size == 0) {
            size = end - pos;
        }
        if (size < hl || pos + size > end) return null;
        return new long[] {size, hl, type};
    }

    static int fourcc(String s) {
        return (s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3);
    }

    private static String name(int t) {
        return new String(new char[] {(char) ((t >>> 24) & 0xff), (char) ((t >>> 16) & 0xff), (char) ((t >>> 8) & 0xff), (char) (t & 0xff)});
    }
}
