package net.davidgf.mediadatefixer.core;

import static net.davidgf.mediadatefixer.testing.Testing.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Scanning and planning: Scanner, Planner, RestoreDetector. */
public class PlannerTest {
    private static long at(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    // ---- Planner

    static {
        DatesTest.loadPresets();
    }

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final long NOW = at("2024-06-01T00:00:00Z");
    private static final long RESTORED = at("2024-05-20T10:00:00Z");
    private final Map<String, Planner.ExifData> exif = new HashMap<>();
    private final Planner.MetaReader meta = new Planner.MetaReader() {
        @Override public Planner.ExifData readExif(File f) { return exif.get(f.getName()); }
        @Override public long readVideoDate(File f) { return Dates.NONE; }
    };
    private JobConfig cfg;

    @Before
    public void setUp() {
        cfg = new JobConfig();
        cfg.zoneId = "Europe/Madrid";
    }

    private File file(String name, int size, long mtime) throws IOException {
        File f = new File(tmp.getRoot(), name);
        Files.write(f.toPath(), new byte[size]);
        assertTrue(f.setLastModified(mtime));
        return f;
    }

    private PlanItem plan(File f) {
        return new Planner(cfg, meta, NOW).plan(f);
    }

    @Test
    public void whatsappModePlansEverythingAndOnRightDayDecides() throws IOException {
        cfg = JobConfig.whatsapp(Arrays.asList(tmp.getRoot().getPath()));
        cfg.zoneId = "Europe/Madrid";
        Planner p = new Planner(cfg, meta, NOW);
        PlanItem a = p.plan(file("IMG-20230115-WA0007.jpg", 10, RESTORED));
        assertEquals(PlanItem.CHANGE, a.kind);
        assertEquals(at("2023-01-14T23:00:07Z"), a.newMtime);
        assertNull(a.newExif);
        assertFalse(p.onRightDay(a));
        // Already on the right day (e.g. the real receive time): planned, but the preview keeps it.
        PlanItem b = p.plan(file("IMG-20240520-WA0001.jpg", 10, RESTORED));
        assertEquals(PlanItem.CHANGE, b.kind);
        assertTrue(p.onRightDay(b));
        PlanItem c = p.plan(file("random.jpg", 10, RESTORED));
        assertEquals(PlanItem.NO_DATE, c.kind);
        assertFalse(p.onRightDay(c));
    }

    @Test
    public void differentDayMidnightMargin() throws IOException {
        cfg.enabled = EnumSet.of(JobConfig.Source.FILENAME);
        cfg.mismatch = JobConfig.Mismatch.DAY;
        // Received 23:40 local the evening before the name's date (Madrid = UTC+1 in January).
        File evening = file("IMG-20240104-WA0002.jpg", 10, at("2024-01-03T22:40:00Z"));
        assertEquals(PlanItem.UNCHANGED, plan(evening).kind);
        cfg.dayToleranceMin = 0;
        assertEquals(PlanItem.CHANGE, plan(evening).kind);
        // Restore stamp 02:00 on the 17th must still fix a file named the 16th with the 1 h default.
        cfg.dayToleranceMin = 60;
        File restored = file("IMG-20240116-WA0001.jpg", 10, at("2024-01-17T01:00:00Z"));
        assertEquals(PlanItem.CHANGE, plan(restored).kind);
    }

    @Test
    public void priorityChainAndConflicts() throws IOException {
        exif.put("IMG_20230115_143022.jpg", new Planner.ExifData("O=2023:01:15 14:30:22|", null));
        exif.put("IMG_20230116_143022.jpg", new Planner.ExifData("O=2023:03:01 09:00:00|+01:00", null));
        PlanItem same = plan(file("IMG_20230115_143022.jpg", 10, RESTORED));
        assertEquals("filename:camera", same.source);
        assertFalse(same.conflict);
        PlanItem diff = plan(file("IMG_20230116_143022.jpg", 10, RESTORED));
        assertTrue(diff.conflict);
        assertEquals(at("2023-01-16T13:30:22Z"), diff.newMtime);

        cfg.order = new ArrayList<>(Arrays.asList(JobConfig.Source.EXIF, JobConfig.Source.FILENAME, JobConfig.Source.VIDEO, JobConfig.Source.MTIME));
        PlanItem exifFirst = plan(new File(tmp.getRoot(), "IMG_20230116_143022.jpg"));
        assertEquals("exif", exifFirst.source);
        assertEquals(at("2023-03-01T08:00:00Z"), exifFirst.newMtime);
    }

    @Test
    public void filters() throws IOException {
        File small = file("IMG_20230115_143022.jpg", 10, RESTORED);
        File video = file("VID_20230115_143022.mp4", 2000, RESTORED);
        cfg.extensions = "mp4";
        assertNull(plan(small));
        assertNotNull(plan(video));
        cfg.extensions = "";
        cfg.nameRegex = "^VID_";
        assertNull(plan(small));
        cfg.nameRegex = "";
        cfg.minSize = 1000;
        assertNull(plan(small));
        assertNotNull(plan(video));
        cfg.minSize = 0;
        cfg.maxSize = 1000;
        assertNull(plan(video));
        cfg.maxSize = 0;
        cfg.dateFrom = at("2023-02-01T00:00:00Z");
        assertNull(plan(small));
        cfg.dateFrom = 0;
        cfg.dateTo = at("2023-01-01T00:00:00Z");
        assertNull(plan(small));
    }

    @Test
    public void dateRangeOnMtimeOrExif() throws IOException {
        long otherDay = at("2024-05-21T10:00:00Z");
        exif.put("a_20230115_143022.jpg", new Planner.ExifData("O=2023:01:15 14:30:22|", null));
        File restored = file("a_20230115_143022.jpg", 10, RESTORED);
        File later = file("b_20230115_143022.jpg", 10, otherDay);
        cfg.dateFrom = at("2024-05-19T22:00:00Z"); // 2024-05-20 local (Madrid)
        cfg.dateTo = at("2024-05-20T21:59:59.999Z");
        cfg.rangeOn = JobConfig.RangeOn.MTIME;
        PlanItem p = plan(restored);
        assertNotNull(p);
        assertEquals(PlanItem.CHANGE, p.kind); // derived (2023) is far outside the range: still processed
        assertNull(plan(later));

        cfg.rangeOn = JobConfig.RangeOn.DERIVED;
        assertNull(plan(restored));

        cfg.rangeOn = JobConfig.RangeOn.EXIF;
        cfg.enabled = EnumSet.of(JobConfig.Source.FILENAME); // EXIF read just for the filter
        cfg.dateFrom = at("2023-01-15T00:00:00Z");
        cfg.dateTo = at("2023-01-16T00:00:00Z");
        assertNotNull(plan(restored));
        assertNull(plan(later)); // no EXIF at all
    }

    @Test
    public void writesOnlySelectedExifTagsThatDiffer() throws IOException {
        String name = "IMG-20240104-WA0003.jpg";
        String target = "2024:01:04 00:00:03|+01:00";
        exif.put(name, new Planner.ExifData("O=;D=" + target + ";M=2024:01:17 10:00:00|", null));
        File f = file(name, 10, RESTORED);
        cfg.writeExif = true;
        cfg.exifTags = new java.util.LinkedHashSet<>(Arrays.asList(ExifDates.DIGITIZED, ExifDates.MODIFIED));
        PlanItem p = plan(f);
        assertEquals(PlanItem.CHANGE, p.kind);
        assertEquals("M=" + target, p.newExif); // D already right, O not selected
        assertEquals("M=2024:01:17 10:00:00|", p.oldExif);
    }

    @Test
    public void exifPresenceFilter() throws IOException {
        exif.put("a_20230115_143022.jpg", new Planner.ExifData("O=2023:01:15 14:30:22|", null));
        File with = file("a_20230115_143022.jpg", 10, RESTORED);
        File without = file("b_20230115_143022.jpg", 10, RESTORED);
        cfg.exifFilter = JobConfig.ExifFilter.HAS;
        assertNotNull(plan(with));
        assertNull(plan(without));
        cfg.exifFilter = JobConfig.ExifFilter.LACKS;
        assertNull(plan(with));
        assertNotNull(plan(without));
    }

    @Test
    public void mismatchAnyMarksMatchingUnchanged() throws IOException {
        cfg.mismatch = JobConfig.Mismatch.ANY;
        cfg.writeExif = true;
        File ok = file("IMG_20230115_143022.jpg", 10, at("2023-01-15T13:30:22Z"));
        PlanItem it = plan(ok);
        assertEquals(PlanItem.UNCHANGED, it.kind);
        assertNull(it.newExif); // excluded entirely, even though EXIF is missing
    }

    @Test
    public void exifOnlyKeepsMtime() throws IOException {
        cfg.setMtime = false;
        cfg.writeExif = true;
        File f = file("IMG_20230715_140000.jpg", 10, RESTORED);
        PlanItem it = plan(f);
        assertEquals(PlanItem.CHANGE, it.kind);
        assertEquals(RESTORED, it.newMtime);
        assertEquals("O=", it.oldExif);
        assertEquals("O=2023:07:15 14:00:00|+02:00", it.newExif);
        // EXIF is not writable for HEIC
        PlanItem heic = plan(file("IMG_20230715_140000.heic", 10, RESTORED));
        assertEquals(PlanItem.UNCHANGED, heic.kind);
    }

    @Test
    public void mtimeSourceAsFallback() throws IOException {
        cfg.enabled = EnumSet.of(JobConfig.Source.FILENAME, JobConfig.Source.MTIME);
        cfg.writeExif = true;
        PlanItem it = plan(file("holiday.jpg", 10, RESTORED));
        assertEquals("mtime", it.source);
        assertEquals(RESTORED, it.newMtime);
        assertNotNull(it.newExif);
    }

    @Test
    public void videoFromMp4Header() throws IOException {
        cfg.enabled = EnumSet.of(JobConfig.Source.VIDEO);
        File f = DatesTest.sample(tmp.getRoot(), Mp4Dates.millisToRaw(at("2022-08-01T10:00:00Z")));
        File renamed = new File(tmp.getRoot(), "clip.mp4");
        assertTrue(f.renameTo(renamed));
        renamed.setLastModified(RESTORED);
        PlanItem it = plan(renamed);
        assertEquals("video", it.source);
        assertEquals(at("2022-08-01T10:00:00Z"), it.newMtime);
    }

    @Test
    public void mp4PatchPlanned() throws IOException {
        cfg.patchMp4 = true;
        File f = DatesTest.sample(tmp.getRoot(), 0);
        File renamed = new File(tmp.getRoot(), "VID_20230115_143022.mp4");
        assertTrue(f.renameTo(renamed));
        PlanItem it = plan(renamed);
        assertEquals(0, it.oldMp4);
        assertEquals(Mp4Dates.millisToRaw(at("2023-01-15T13:30:22Z")), it.newMp4);
    }

    @Test
    public void scannerSkipsHiddenAndRecurses() throws IOException {
        File sub = tmp.newFolder("a", "b");
        File hidden = tmp.newFolder(".Statuses");
        Files.write(new File(sub, "x.jpg").toPath(), new byte[1]);
        Files.write(new File(hidden, "y.jpg").toPath(), new byte[1]);
        Files.write(new File(tmp.getRoot(), ".z.jpg").toPath(), new byte[1]);
        Files.write(new File(tmp.getRoot(), "notes.txt").toPath(), new byte[1]);
        List<String> found = new ArrayList<>();
        Scanner.walk(Arrays.asList(tmp.getRoot(), sub), true, MediaTypes.ALL, null, f -> found.add(f.getName()));
        assertEquals(Arrays.asList("x.jpg"), found);
        found.clear();
        Scanner.walk(Arrays.asList(tmp.getRoot()), false, MediaTypes.ALL, null, f -> found.add(f.getName()));
        assertTrue(found.isEmpty());
    }

    // ---- RestoreDetector

    private static final ZoneId Z = ZoneId.of("Europe/Madrid");
    private static final long DAY = 86_400_000L, MIN = 60_000L;

    private static PlanItem item(long mtime, long derived) {
        PlanItem p = new PlanItem(new File("x"), 1, mtime);
        p.derived = derived;
        return p;
    }

    @Test
    public void findsRestoresBiggestFirstAndIgnoresBusyDays() {
        List<PlanItem> items = new ArrayList<>();
        long restore = at("2025-01-17T09:00:00Z"), copy = at("2025-02-01T18:00:00Z");
        long names = at("2024-03-01T00:00:00Z");
        // Restore: 300 files over ~2.5 h, with one 20-minute pause, names spread over months.
        for (int i = 0; i < 300; i++) items.add(item(restore + i * 30_000L + (i >= 150 ? 20 * MIN : 0), names + (i % 200) * DAY));
        // A later copy of 40 files in one minute.
        for (int i = 0; i < 40; i++) items.add(item(copy + i * 1000L, names + i * DAY));
        // Busy chat day: 100 files in an hour, names on the same day.
        long busy = at("2024-12-24T18:00:00Z");
        for (int i = 0; i < 100; i++) items.add(item(busy + i * 36_000L, at("2024-12-24T12:00:00Z")));
        // Scattered normal files.
        for (int i = 0; i < 50; i++) items.add(item(names + i * 7 * DAY + 3_600_000L, names + i * 7 * DAY));

        List<RestoreDetector.Cluster> cs = RestoreDetector.detect(items, Z);
        assertEquals(2, cs.size());
        RestoreDetector.Cluster r = cs.get(0);
        assertEquals(300, r.files);
        assertEquals(restore, r.start);
        assertTrue(r.contains(restore + 2 * 3_600_000L));
        assertFalse(r.contains(copy));
        assertEquals(200, r.nameDays);
        assertEquals(40, cs.get(1).files);
    }

    @Test
    public void smallOrSameDayGroupsAreNotRestores() {
        List<PlanItem> items = new ArrayList<>();
        long t = at("2025-01-17T09:00:00Z");
        for (int i = 0; i < 10; i++) items.add(item(t + i * 1000L, at("2024-01-01T00:00:00Z") + i * DAY)); // too few
        for (int i = 0; i < 30; i++) items.add(item(t + 10 * 3_600_000L + i * 1000L, at("2024-06-01T00:00:00Z"))); // one name day
        assertTrue(RestoreDetector.detect(items, Z).isEmpty());
    }
}
