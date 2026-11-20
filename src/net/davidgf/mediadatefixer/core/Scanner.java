package net.davidgf.mediadatefixer.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Walks folders (optionally recursive), skipping hidden entries, de-duplicating overlaps. */
public final class Scanner {
    private Scanner() {}

    public interface Callback {
        void onFile(File f);
    }

    public static void walk(List<File> roots, boolean recursive, Set<String> exts, AtomicBoolean cancel, Callback cb) {
        Set<String> seenDirs = new HashSet<>();
        Set<String> seenFiles = new HashSet<>();
        for (File root : roots) {
            Deque<File> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                if (cancel != null && cancel.get()) return;
                File dir = stack.pop();
                if (!seenDirs.add(canonical(dir))) continue;
                File[] children = dir.listFiles();
                if (children == null) continue;
                Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                // Push subdirectories in reverse so they are visited in alphabetical order.
                for (int i = children.length - 1; i >= 0; i--) {
                    File c = children[i];
                    if (recursive && c.isDirectory() && !c.getName().startsWith(".")) stack.push(c);
                }
                for (File c : children) {
                    if (cancel != null && cancel.get()) return;
                    String name = c.getName();
                    if (name.startsWith(".") || !c.isFile()) continue;
                    if (exts != null && !exts.contains(MediaTypes.ext(name))) continue;
                    if (!seenFiles.add(c.getAbsolutePath())) continue;
                    cb.onFile(c);
                }
            }
        }
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }
}
