package net.davidgf.mediadatefixer.testing;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A tiny JUnit-style test framework, so the build needs no downloaded jars. Tests use
 * {@code import static net.davidgf.mediadatefixer.testing.Testing.*;}, which brings in the
 * annotations, the asserts and {@link TemporaryFolder}.
 * <p>
 * Run with {@code java Testing com.example.FooTest ...}: every {@link Test} method runs on a fresh
 * instance, after its {@link Before} methods, with {@link Rule} temporary folders. Exits non-zero
 * if anything failed.
 */
public final class Testing {
    private Testing() {}

    // ---- annotations

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Test {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Before {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Rule {}

    // ---- asserts

    public static void fail(String msg) {
        throw new AssertionError(msg);
    }

    public static void assertTrue(boolean b) {
        if (!b) fail("expected true");
    }

    public static void assertFalse(boolean b) {
        if (b) fail("expected false");
    }

    public static void assertNull(Object o) {
        if (o != null) fail("expected null but was <" + o + ">");
    }

    public static void assertNotNull(Object o) {
        if (o == null) fail("expected non-null");
    }

    public static void assertEquals(long expected, long actual) {
        if (expected != actual) fail("expected <" + expected + "> but was <" + actual + ">");
    }

    public static void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) fail("expected <" + expected + "> but was <" + actual + ">");
    }

    public static void assertArrayEquals(Object[] expected, Object[] actual) {
        if (!Arrays.equals(expected, actual)) {
            fail("expected " + Arrays.toString(expected) + " but was " + Arrays.toString(actual));
        }
    }

    // ---- temporary folders

    /** A fresh temporary directory per test, deleted by the runner afterwards. */
    public static final class TemporaryFolder {
        private File root;

        public File getRoot() {
            if (root == null) {
                try {
                    root = Files.createTempDirectory("mdf-test").toFile();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return root;
        }

        public File newFolder(String... path) throws IOException {
            File f = getRoot();
            for (String p : path) f = new File(f, p);
            if (!f.mkdirs()) throw new IOException("Could not create " + f);
            return f;
        }

        public File newFile(String name) throws IOException {
            File f = new File(getRoot(), name);
            if (!f.createNewFile()) throw new IOException("Could not create " + f);
            return f;
        }

        void delete() {
            if (root != null) delete(root);
            root = null;
        }

        private static void delete(File f) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) delete(k);
            f.delete();
        }
    }

    // ---- runner

    public static void main(String[] args) throws Exception {
        int run = 0;
        List<String> failures = new ArrayList<>();
        for (String name : args) {
            Class<?> c = Class.forName(name);
            Method[] methods = c.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName));
            for (Method m : methods) {
                if (!m.isAnnotationPresent(Test.class)) continue;
                run++;
                String id = c.getSimpleName() + "." + m.getName();
                List<TemporaryFolder> tmps = new ArrayList<>();
                try {
                    Object o = c.getDeclaredConstructor().newInstance();
                    for (Field f : c.getDeclaredFields()) {
                        if (f.isAnnotationPresent(Rule.class) && f.getType() == TemporaryFolder.class) {
                            f.setAccessible(true);
                            tmps.add((TemporaryFolder) f.get(o));
                        }
                    }
                    for (Method b : methods) if (b.isAnnotationPresent(Before.class)) b.invoke(o);
                    m.invoke(o);
                } catch (InvocationTargetException e) {
                    failures.add(id + ": " + describe(e.getCause()));
                } catch (ReflectiveOperationException e) {
                    failures.add(id + ": " + describe(e));
                } finally {
                    for (TemporaryFolder t : tmps) t.delete();
                }
            }
        }
        for (String f : failures) System.out.println("FAIL " + f);
        System.out.println(run + " tests, " + failures.size() + " failed");
        if (!failures.isEmpty()) System.exit(1);
    }

    /** The exception plus the first stack frame inside the test sources. */
    private static String describe(Throwable t) {
        String where = "";
        for (StackTraceElement e : t.getStackTrace()) {
            if (e.getClassName().startsWith("net.davidgf.") && !e.getClassName().contains(".testing.")) {
                where = " (" + e.getFileName() + ":" + e.getLineNumber() + ")";
                break;
            }
        }
        return t + where;
    }
}
