package net.davidgf.mediadatefixer.core;

import static net.davidgf.mediadatefixer.testing.Testing.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Applying changes: the journal (write, resume, store) and JobRunner (apply, verify, undo). */
public class JobTest {
    private static long at(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    // ---- Journal

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static JournalItem item(String path, long oldM, long newM) {
        JournalItem it = new JournalItem();
        it.path = path;
        it.size = 42;
        it.oldMtime = oldM;
        it.newMtime = newM;
        return it;
    }

    @Test
    public void roundTripWithEscaping() throws IOException {
        List<JournalItem> items = new ArrayList<>();
        items.add(item("/sdcard/weird\tname\\with\nstuff.jpg", 1, 2));
        JournalItem ex = item("/sdcard/b.jpg", 3, 4);
        ex.oldExif = "";
        ex.newExif = "2023:01:15 14:30:22|+01:00";
        ex.oldMp4 = 5;
        ex.newMp4 = 6;
        items.add(ex);
        File f = new File(tmp.getRoot(), "j.mdj");
        Journal.create(f, Journal.TYPE_APPLY, "Tést\ttitle", "", items);

        Journal j = Journal.load(f);
        assertEquals("Tést\ttitle", j.title);
        assertEquals(2, j.items.size());
        assertEquals("/sdcard/weird\tname\\with\nstuff.jpg", j.items.get(0).path);
        assertNull(j.items.get(0).newExif);
        assertEquals("", j.items.get(1).oldExif);
        assertEquals("2023:01:15 14:30:22|+01:00", j.items.get(1).newExif);
        assertEquals(6, j.items.get(1).newMp4);
        assertEquals(2, j.pending());
        assertFalse(j.isFinished());
    }

    @Test
    public void resumeAfterTornWrite() throws IOException {
        List<JournalItem> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) items.add(item("/f" + i, 10, 20));
        File f = new File(tmp.getRoot(), "j.mdj");
        Journal j = Journal.create(f, Journal.TYPE_APPLY, "t", "", items);
        for (int i = 0; i < 3; i++) {
            JournalItem it = j.items.get(i);
            it.status = i == 1 ? JournalItem.FAILED : JournalItem.DONE;
            it.actualMtime = 20;
            it.actualSize = 42;
            it.msg = i == 1 ? "boom" : null;
            j.record(it);
        }
        j.flush();
        j.close();
        // Simulate a crash in the middle of writing a status line.
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write("S\t3\t1\t2".getBytes(StandardCharsets.UTF_8));
        }

        Journal r = Journal.load(f);
        int[] c = r.counts();
        assertEquals(2, c[JournalItem.DONE]);
        assertEquals(1, c[JournalItem.FAILED]);
        assertEquals(2, c[JournalItem.PENDING]);
        assertEquals("boom", r.items.get(1).msg);
        assertFalse(r.isFinished());

        // Undo only covers the files that were actually changed.
        List<JournalItem> undo = r.undoItems();
        assertEquals(2, undo.size());
        assertEquals(20, undo.get(0).oldMtime);
        assertEquals(10, undo.get(0).newMtime);
    }

    @Test
    public void lastStatusWinsAndFinishMarkers() throws IOException {
        List<JournalItem> items = new ArrayList<>();
        items.add(item("/a", 1, 2));
        File f = new File(tmp.getRoot(), "j.mdj");
        Journal j = Journal.create(f, Journal.TYPE_APPLY, "", "", items);
        JournalItem it = j.items.get(0);
        it.status = JournalItem.FAILED;
        j.record(it);
        it.status = JournalItem.DONE;
        j.record(it);
        j.markFinished(true);
        Journal.load(f).markUndone("123-undo");

        Journal r = Journal.load(f);
        assertEquals(JournalItem.DONE, r.items.get(0).status);
        assertTrue(r.isFinished());
        assertTrue(r.cancelled);
        assertEquals("123-undo", r.undoneBy);
    }

    @Test
    public void storeOrdersAndPrunes() throws IOException {
        JournalStore s = new JournalStore(tmp.newFolder("journals"));
        File a = s.newFile("apply");
        Journal.create(a, Journal.TYPE_APPLY, "", "", new ArrayList<>()).markFinished(false);
        File b = s.newFile("apply");
        Journal.create(b, Journal.TYPE_APPLY, "", "", new ArrayList<>()); // unfinished
        File c = s.newFile("undo");
        Journal.create(c, Journal.TYPE_UNDO, "", "", new ArrayList<>()).markFinished(false);
        assertEquals(c, s.list().get(0));
        s.prune(1);
        assertFalse(a.exists());
        assertTrue(b.exists());
        assertTrue(c.exists());
    }

    @Test
    public void canUndoOnlyAppliedJobsThatTouchedFiles() throws IOException {
        List<JournalItem> items = new ArrayList<>();
        items.add(item("/x/a.jpg", 1000, 2000));
        Journal j = Journal.create(new File(tmp.getRoot(), "a.mdj"), Journal.TYPE_APPLY, "t", "", items);
        assertFalse(j.canUndo()); // nothing done yet
        j.items.get(0).status = JournalItem.DONE;
        assertTrue(j.canUndo());
        j.undoneBy = "later";
        assertFalse(j.canUndo());
        j.undoneBy = null;
        j.type = Journal.TYPE_UNDO;
        assertFalse(j.canUndo());
    }

    @Test
    public void problemStatuses() {
        int[] c = new int[JournalItem.STATUS_COUNT];
        for (int st = 0; st < c.length; st++) c[st] = 1 << st; // distinct per status
        int expected = c[JournalItem.SKIPPED] + c[JournalItem.FAILED] + c[JournalItem.MISMATCH] + c[JournalItem.MISSING];
        assertEquals(expected, JournalItem.problems(c));
        assertFalse(JournalItem.isProblem(JournalItem.DONE));
        assertFalse(JournalItem.isProblem(JournalItem.ALREADY));
        assertTrue(JournalItem.isProblem(JournalItem.MISMATCH));
    }

    // ---- JobRunner

    private static final long OLD = 1716199200000L; // 2024-05-20
    private static final long NEW = 1673789000000L; // 2023-01-15

    /** Fake EXIF store; like the real thing, writing EXIF rewrites the file (size + mtime change). */
    static class FakeOps extends JobRunner.FileOps {
        final Map<String, String> exif = new ConcurrentHashMap<>();
        final List<String> scanned = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger exifWrites = new AtomicInteger();
        volatile boolean ignoreMtime;

        @Override public String readExif(File f) { return exif.getOrDefault(f.getPath(), ""); }

        @Override public void writeExif(File f, String enc) throws IOException {
            exifWrites.incrementAndGet();
            exif.put(f.getPath(), enc);
            Files.write(f.toPath(), new byte[(int) f.length() + 7]);
        }

        @Override public boolean setMtime(File f, long ms) { return ignoreMtime || super.setMtime(f, ms); }

        @Override public void scan(List<String> paths) { scanned.addAll(paths); }
    }

    private List<JournalItem> files(int n, boolean exif) throws IOException {
        List<JournalItem> items = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            File f = new File(tmp.getRoot(), "f" + i + ".jpg");
            Files.write(f.toPath(), new byte[10]);
            assertTrue(f.setLastModified(OLD));
            JournalItem it = new JournalItem();
            it.path = f.getPath();
            it.size = 10;
            it.oldMtime = OLD;
            it.newMtime = NEW + i * 1000L;
            if (exif) {
                it.oldExif = "";
                it.newExif = "2023:01:15 14:30:" + String.format("%02d", i % 60) + "|+01:00";
            }
            items.add(it);
        }
        return items;
    }

    private Journal journal(List<JournalItem> items) throws IOException {
        return Journal.create(new File(tmp.getRoot(), System.nanoTime() + ".mdj"), Journal.TYPE_APPLY, "", "", items);
    }

    @Test
    public void appliesAndVerifies() throws IOException {
        FakeOps ops = new FakeOps();
        Journal j = journal(files(250, false));
        JobRunner.Progress p = new JobRunner().run(j, ops, 1, null);
        assertTrue(p.finished);
        assertEquals(250, p.counts[JournalItem.DONE]);
        for (JournalItem it : j.items) assertEquals(it.newMtime, new File(it.path).lastModified());
        assertEquals(250, ops.scanned.size());
        assertEquals(250, Journal.load(j.file).counts()[JournalItem.DONE]);
    }

    @Test
    public void exifWrittenInParallelThenMtimeLast() throws IOException {
        FakeOps ops = new FakeOps();
        Journal j = journal(files(120, true));
        JobRunner.Progress p = new JobRunner().run(j, ops, 3, null);
        assertEquals(120, p.counts[JournalItem.DONE]);
        for (JournalItem it : j.items) {
            assertEquals(it.newMtime, new File(it.path).lastModified());
            assertEquals(it.newExif, ops.exif.get(it.path));
            assertEquals(17, it.actualSize);
        }
    }

    @Test
    public void resumeIsIdempotent() throws IOException {
        FakeOps ops = new FakeOps();
        List<JournalItem> items = files(10, true);
        Journal j = journal(items);
        // Pretend a previous run crashed: file 0 fully done, file 1 got EXIF but no mtime yet.
        new File(items.get(0).path).setLastModified(items.get(0).newMtime);
        ops.writeExif(new File(items.get(0).path), items.get(0).newExif);
        new File(items.get(0).path).setLastModified(items.get(0).newMtime);
        ops.writeExif(new File(items.get(1).path), items.get(1).newExif);
        ops.exifWrites.set(0);

        Journal reloaded = Journal.load(j.file);
        JobRunner.Progress p = new JobRunner().run(reloaded, ops, 2, null);
        assertEquals(1, p.counts[JournalItem.ALREADY]);
        assertEquals(9, p.counts[JournalItem.DONE]);
        assertEquals(8, ops.exifWrites.get());
        assertEquals(items.get(1).newMtime, new File(items.get(1).path).lastModified());
    }

    @Test
    public void skipsFilesChangedSinceScan() throws IOException {
        FakeOps ops = new FakeOps();
        List<JournalItem> items = files(3, false);
        new File(items.get(0).path).setLastModified(OLD + 3_600_000L);
        Files.write(new File(items.get(1).path).toPath(), new byte[99]);
        new File(items.get(1).path).setLastModified(OLD);
        new File(items.get(2).path).delete();
        JobRunner.Progress p = new JobRunner().run(journal(items), ops, 1, null);
        assertEquals(2, p.counts[JournalItem.SKIPPED]);
        assertEquals(1, p.counts[JournalItem.MISSING]);
        assertEquals(OLD + 3_600_000L, new File(items.get(0).path).lastModified());
    }

    @Test
    public void detectsMtimeThatDoesNotStick() throws IOException {
        FakeOps ops = new FakeOps();
        ops.ignoreMtime = true;
        JobRunner.Progress p = new JobRunner().run(journal(files(2, false)), ops, 1, null);
        assertEquals(2, p.counts[JournalItem.MISMATCH]);
    }

    @Test
    public void cancelStopsAndLeavesRestPending() throws IOException {
        FakeOps ops = new FakeOps();
        JobRunner r = new JobRunner();
        Journal j = journal(files(300, false));
        JobRunner.Progress p = r.run(j, ops, 1, prog -> {
            if (prog.processed == 150) r.cancel();
        });
        assertFalse(p.finished);
        assertTrue(r.isCancelled());
        assertEquals(150, p.counts[JournalItem.DONE]);
        assertEquals(150, Journal.load(j.file).pending());
    }

    @Test
    public void undoRestoresEverything() throws IOException {
        FakeOps ops = new FakeOps();
        List<JournalItem> items = files(5, true);
        Journal j = journal(items);
        new JobRunner().run(j, ops, 2, null);
        j.markFinished(false);

        Journal undo = Journal.create(new File(tmp.getRoot(), "u.mdj"), Journal.TYPE_UNDO, "", j.id(), Journal.load(j.file).undoItems());
        JobRunner.Progress p = new JobRunner().run(undo, ops, 2, null);
        assertEquals(5, p.counts[JournalItem.DONE]);
        for (JournalItem it : items) {
            assertEquals(OLD, new File(it.path).lastModified());
            assertEquals("O=", ops.exif.get(it.path)); // tag removed again
        }
    }

    @Test
    public void pauseBlocksUntilResume() throws Exception {
        FakeOps ops = new FakeOps();
        JobRunner r = new JobRunner();
        Journal j = journal(files(5, false));
        r.pause();
        Thread t = new Thread(() -> {
            try {
                r.run(j, ops, 1, null);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        t.start();
        Thread.sleep(200);
        assertEquals(OLD, new File(j.items.get(0).path).lastModified());
        r.resume();
        t.join(5000);
        assertFalse(t.isAlive());
        assertEquals(5, j.counts()[JournalItem.DONE]);
    }
}
