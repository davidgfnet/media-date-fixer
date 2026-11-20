package net.davidgf.mediadatefixer.core;

import static net.davidgf.mediadatefixer.testing.Testing.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything that reads or writes a date: generic helpers, EXIF strings, file names, MP4 headers. */
public class DatesTest {
    private static long at(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    // ---- Dates

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Test
    public void videoDates() {
        assertEquals(at("2023-01-15T14:30:22Z"), Dates.parseVideoDate("20230115T143022.000Z"));
        assertEquals(at("2023-01-15T14:30:22Z"), Dates.parseVideoDate("20230115T143022Z"));
        assertEquals(at("2023-01-15T14:30:22.500Z"), Dates.parseVideoDate("2023-01-15T14:30:22.500000Z"));
        assertEquals(at("2023-01-15T13:30:22Z"), Dates.parseVideoDate("20230115T143022.000+0100"));
        assertEquals(Dates.NONE, Dates.parseVideoDate("19040101T000000.000Z"));
        assertEquals(Dates.NONE, Dates.parseVideoDate("garbage"));
    }

    @Test
    public void plausibility() {
        long now = at("2024-01-01T00:00:00Z");
        assertTrue(Dates.plausible(at("2023-01-01T00:00:00Z"), now));
        assertFalse(Dates.plausible(at("1985-01-01T00:00:00Z"), now));
        assertFalse(Dates.plausible(at("2024-01-03T00:00:00Z"), now));
        assertFalse(Dates.plausible(Dates.NONE, now));
    }

    @Test
    public void withinDayWidensBothEdges() {
        long day = at("2025-01-04T10:00:00Z"); // Jan 4 in Madrid: [Jan 3 23:00Z, Jan 4 23:00Z)
        long hour = 3_600_000L;
        assertTrue(Dates.withinDay(at("2025-01-03T23:00:00Z"), day, MADRID, 0));
        assertFalse(Dates.withinDay(at("2025-01-03T22:40:00Z"), day, MADRID, 0)); // 23:40 the evening before
        assertTrue(Dates.withinDay(at("2025-01-03T22:40:00Z"), day, MADRID, hour));
        assertTrue(Dates.withinDay(at("2025-01-04T23:30:00Z"), day, MADRID, hour)); // 00:30 the day after
        assertFalse(Dates.withinDay(at("2025-01-05T00:00:00Z"), day, MADRID, hour));
    }

    @Test
    public void sameDayRespectsZone() {
        long a = at("2023-01-15T23:30:00Z"); // 00:30 on the 16th in Madrid
        long b = at("2023-01-16T10:00:00Z");
        assertTrue(Dates.sameDay(a, b, MADRID));
        assertFalse(Dates.sameDay(a, b, ZoneId.of("UTC")));
    }

    // ---- ExifDates

    private static final String T = "2025:01:04 00:00:03|+01:00";

    @Test
    public void exifLocalUsesZone() {
        assertEquals(at("2023-01-15T13:30:22Z"), ExifDates.parse("2023:01:15 14:30:22", null, null, MADRID));
        assertEquals(at("2023-01-15T19:30:22Z"), ExifDates.parse("2023:01:15 14:30:22", null, null, NY));
    }

    @Test
    public void exifOffsetWinsOverZone() {
        assertEquals(at("2023-01-15T05:30:22Z"), ExifDates.parse("2023:01:15 14:30:22", "+09:00", null, MADRID));
        assertEquals(at("2023-01-15T13:30:22.450Z"), ExifDates.parse("2023:01:15 14:30:22", "+01:00", "45", MADRID));
    }

    @Test
    public void exifGarbage() {
        assertEquals(Dates.NONE, ExifDates.parse("0000:00:00 00:00:00", null, null, MADRID));
        assertEquals(Dates.NONE, ExifDates.parse("    :  :     :  :  ", null, null, MADRID));
        assertEquals(Dates.NONE, ExifDates.parse(null, null, null, MADRID));
    }

    @Test
    public void exifFormatIsDstAware() {
        assertArrayEquals(new String[] {"2023:07:15 14:00:00", "+02:00"}, ExifDates.format(at("2023-07-15T12:00:00Z"), MADRID));
        assertArrayEquals(new String[] {"2023:12:15 14:00:00", "+01:00"}, ExifDates.format(at("2023-12-15T13:00:00Z"), MADRID));
        assertArrayEquals(new String[] {"2023:12:15 13:00:00", "+00:00"}, ExifDates.format(at("2023-12-15T13:00:00Z"), ZoneId.of("UTC")));
    }

    @Test
    public void exifRoundTrip() {
        long t = at("2023-10-29T00:30:00Z"); // inside the fall-back overlap
        String[] f = ExifDates.format(t, MADRID);
        assertEquals(t, ExifDates.parse(f[0], f[1], null, MADRID));
    }

    @Test
    public void exifEncoding() {
        assertEquals("", ExifDates.encodeValue(null, "+01:00"));
        String e = ExifDates.encodeValue("2023:01:15 14:30:22", "+01:00");
        assertArrayEquals(new String[] {"2023:01:15 14:30:22", "+01:00"}, ExifDates.decodeValue(e));
        assertArrayEquals(new String[] {"2023:01:15 14:30:22", null}, ExifDates.decodeValue(ExifDates.encodeValue("2023:01:15 14:30:22", null)));
        assertTrue(ExifDates.valueMatches("2023:01:15 14:30:22|+01:00", "2023:01:15 14:30:22|+01:00"));
        assertFalse(ExifDates.valueMatches("2023:01:15 14:30:22|", "2023:01:15 14:30:22|+01:00"));
        assertTrue(ExifDates.valueMatches("", ""));
        assertFalse(ExifDates.valueMatches("2023:01:15 14:30:22|", ""));
        assertNull(ExifDates.decodeValue("")[0]);
    }

    @Test
    public void roundTripAndLegacy() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("O", T);
        m.put("M", "");
        String enc = ExifDates.encode(m);
        assertEquals("O=" + T + ";M=", enc);
        assertEquals(m, ExifDates.decode(enc));
        assertEquals(T, ExifDates.decode(T).get("O")); // legacy single value
        assertTrue(ExifDates.decode("").isEmpty());
    }

    @Test
    public void matchesOnlyChecksTargetTags() {
        String cur = "O=" + T + ";D=;M=2020:01:01 00:00:00|";
        assertTrue(ExifDates.matches(cur, "O=" + T));
        assertFalse(ExifDates.matches(cur, "O=" + T + ";D=" + T));
        assertTrue(ExifDates.matches(cur, "D="));
        assertFalse(ExifDates.matches(cur, "M="));
        assertTrue(ExifDates.matches("O=" + T, T)); // legacy target
    }

    @Test
    public void restoringCoversExactlyTheChangedTags() {
        assertEquals("O=;M=2020:01:01 00:00:00|", ExifDates.restoring("O=;M=2020:01:01 00:00:00|", "O=" + T + ";M=" + T));
        assertEquals("D=", ExifDates.restoring("O=x|", "D=" + T)); // tag was absent before
        assertEquals("O=", ExifDates.restoring("", T)); // legacy journal
    }

    // ---- FilenamePattern

    /** The app's res/raw/patterns.xml (tests run from the project root). */
    static void loadPresets() {
        if (FilenamePattern.presetsLoaded()) return;
        try (InputStream in = new FileInputStream("res/raw/patterns.xml")) {
            FilenamePattern.loadPresets(in);
        } catch (IOException e) {
            throw new AssertionError("patterns.xml: " + e.getMessage(), e);
        }
    }

    static {
        loadPresets();
    }

    private static long parse(String preset, String name) {
        return FilenamePattern.preset(preset).parse(name, MADRID);
    }

    /** Every {@code <example>} in patterns.xml: local times are read in Madrid, "...Z" as instants. */
    @Test
    public void builtInPatternsMatchTheirExamples() {
        int checked = 0;
        for (FilenamePattern p : FilenamePattern.presets()) {
            for (String[] ex : p.examples) {
                String date = ex[1];
                long expected = date.equals("none") ? Dates.NONE
                        : date.endsWith("Z") ? Instant.parse(date).toEpochMilli()
                        : Dates.localToEpoch(LocalDateTime.parse(date), MADRID);
                long got = p.parse(ex[0], MADRID);
                if (got != expected) {
                    Object shown = got == Dates.NONE ? "none" : date.endsWith("Z") ? Instant.ofEpochMilli(got)
                            : LocalDateTime.ofInstant(Instant.ofEpochMilli(got), MADRID);
                    fail(p.id + ": " + ex[0] + " gave " + shown + ", expected " + date);
                }
                checked++;
            }
        }
        assertTrue(checked >= 20);
    }

    @Test
    public void presetFileShape() {
        assertEquals(Arrays.asList("whatsapp", "pixel", "screenshot", "camera", "epoch"), ids(FilenamePattern.presets()));
        assertEquals(Arrays.asList("whatsapp", "pixel", "screenshot", "camera"), FilenamePattern.defaultPresetIds());
        assertTrue(FilenamePattern.preset("pixel").utc);
        assertFalse(FilenamePattern.preset(FilenamePattern.WHATSAPP).hasTime);
        assertEquals("WhatsApp", FilenamePattern.preset(FilenamePattern.WHATSAPP).label);
    }

    private static List<String> ids(List<FilenamePattern> ps) {
        List<String> out = new ArrayList<>();
        for (FilenamePattern p : ps) out.add(p.id);
        return out;
    }

    @Test
    public void whatsappSequenceKeepsOrder() {
        assertTrue(parse(FilenamePattern.WHATSAPP, "IMG-20230115-WA0002.jpg")
                > parse(FilenamePattern.WHATSAPP, "IMG-20230115-WA0001.jpg"));
    }

    @Test
    public void dstGapShiftsForward() {
        // 2023-03-26 02:30 does not exist in Madrid (clocks jump 02:00 -> 03:00): 03:30 CEST.
        assertEquals(at("2023-03-26T01:30:00Z"), parse("camera", "IMG_20230326_023000.jpg"));
    }

    @Test
    public void dstOverlapUsesEarlierOffset() {
        // 2023-10-29 02:30 happens twice; the first one is CEST (+02:00).
        assertEquals(at("2023-10-29T00:30:00Z"), parse("camera", "IMG_20231029_023000.jpg"));
    }

    @Test
    public void summerAndWinterOffsets() {
        assertEquals(at("2023-07-15T12:00:00Z"), parse("camera", "IMG_20230715_140000.jpg"));
        assertEquals(at("2023-12-15T13:00:00Z"), parse("camera", "IMG_20231215_140000.jpg"));
    }

    @Test
    public void customRegex() {
        FilenamePattern p = FilenamePattern.custom("^scan_(?<day>\\d{2})\\.(?<month>\\d{2})\\.(?<year>\\d{4})");
        assertEquals(at("2021-05-02T22:00:00Z"), p.parse("scan_03.05.2021_page1.jpg", MADRID));
        assertFalse(p.hasTime);
        FilenamePattern e = FilenamePattern.custom("ts(?<epoch>\\d+)");
        assertEquals(1600000000000L, e.parse("ts1600000000.png", MADRID));
    }

    @Test
    public void customRegexValidation() {
        try {
            FilenamePattern.custom("(?<year>\\d{4})(?<month>\\d{2})");
            fail("missing day group accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            FilenamePattern.custom("(?<year>\\d{4}");
            fail("bad regex accepted");
        } catch (IllegalArgumentException expected) {
            // PatternSyntaxException is an IllegalArgumentException
        }
    }

    // ---- Mp4Dates

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static byte[] box(String type, byte[]... children) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] c : children) body.write(c);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(8 + body.size());
        d.writeBytes(type);
        d.write(body.toByteArray());
        return out.toByteArray();
    }

    /** Full box with version, flags, creation, modification, then filler. */
    static byte[] header(String type, int version, long time) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(body);
        d.writeInt(version << 24);
        if (version == 1) {
            d.writeLong(time);
            d.writeLong(time);
        } else {
            d.writeInt((int) time);
            d.writeInt((int) time);
        }
        d.write(new byte[80]);
        return box(type, body.toByteArray());
    }

    /** mdat with a 64-bit size header, placed before moov like many camera files. */
    static byte[] largeMdat(int payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(1);
        d.writeBytes("mdat");
        d.writeLong(16 + payload);
        d.write(new byte[payload]);
        return out.toByteArray();
    }

    static File sample(File dir, long time) throws IOException {
        File f = new File(dir, "v.mp4");
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(box("ftyp", "isom\0\0\0\0isom".getBytes("US-ASCII")));
        all.write(largeMdat(1000));
        all.write(box("moov",
                header("mvhd", 0, time),
                box("trak", header("tkhd", 0, time), box("mdia", header("mdhd", 1, time))),
                box("udta")));
        Files.write(f.toPath(), all.toByteArray());
        return f;
    }

    @Test
    public void readsAndPatchesAllHeaders() throws IOException {
        long t0 = Mp4Dates.millisToRaw(1673793022000L);
        File f = sample(tmp.getRoot(), t0);
        long len = f.length();
        assertEquals(t0, Mp4Dates.readCreationRaw(f));
        assertEquals(1673793022000L, Mp4Dates.rawToMillis(t0));

        long t1 = Mp4Dates.millisToRaw(1500000000000L);
        assertEquals(3, Mp4Dates.writeCreationRaw(f, t1));
        assertEquals(t1, Mp4Dates.readCreationRaw(f));
        assertEquals(len, f.length());

        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            int n = 0;
            for (Mp4Dates.Field fd : Mp4Dates.find(raf)) {
                raf.seek(fd.pos);
                long create = fd.version == 1 ? raf.readLong() : raf.readInt() & 0xffffffffL;
                long modify = fd.version == 1 ? raf.readLong() : raf.readInt() & 0xffffffffL;
                assertEquals(t1, create);
                assertEquals(t1, modify);
                n++;
            }
            assertEquals(3, n);
        }
    }

    @Test
    public void notAnMp4() throws IOException {
        File f = tmp.newFile("x.mp4");
        Files.write(f.toPath(), "hello world, definitely not a box".getBytes("US-ASCII"));
        assertEquals(-1, Mp4Dates.readCreationRaw(f));
        try {
            Mp4Dates.writeCreationRaw(f, 1);
            throw new AssertionError("expected failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("mvhd"));
        }
    }
}
