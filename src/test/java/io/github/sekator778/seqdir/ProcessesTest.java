package io.github.sekator778.seqdir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 420, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ProcessesTest {

    @TempDir
    Path tmp;

    /** Work that the test JVM does while the children run. */
    private interface Body {

        void run(long start) throws Exception;
    }

    /** Starts the JVMs together and returns the file names each printed, per JVM. */
    private List<List<String>> launch(
        final Path shared, final int jvms, final int each, final String mode
    ) throws Exception {
        return this.launch(shared, jvms, each, mode, start -> { }, null);
    }

    /**
     * Like the short variant, and runs a body in this JVM at the same start
     * time; the file names it collected in the given list, if any, are added as
     * the last item of the result.
     */
    private List<List<String>> launch(
        final Path shared, final int jvms, final int each, final String mode,
        final Body body, final List<String> mine
    ) throws Exception {
        final Path logs = Files.createDirectories(this.tmp.resolve("logs"));
        final String java = Paths.get(
            System.getProperty("java.home"), "bin", "java"
        ).toString();
        final long start = System.currentTimeMillis() + 3000L;
        final List<Process> procs = new ArrayList<>();
        for (int id = 0; id < jvms; ++id) {
            final ProcessBuilder builder = new ProcessBuilder(
                java, "-cp", System.getProperty("java.class.path"),
                ClaimMain.class.getName(),
                shared.toString(), Integer.toString(each), Integer.toString(id),
                Long.toString(start), Integer.toString(id % 2 == 0 ? 2 : 5), mode
            );
            builder.redirectOutput(logs.resolve("out" + id + ".txt").toFile());
            builder.redirectError(logs.resolve("err" + id + ".txt").toFile());
            procs.add(builder.start());
        }
        body.run(start);
        final List<List<String>> out = new ArrayList<>();
        for (int id = 0; id < jvms; ++id) {
            final Process proc = procs.get(id);
            if (!proc.waitFor(180, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
            assertEquals(
                0, proc.exitValue(),
                "exit code of JVM " + id + ", stderr: " + new String(
                    Files.readAllBytes(logs.resolve("err" + id + ".txt")), StandardCharsets.UTF_8
                )
            );
            out.add(Files.readAllLines(logs.resolve("out" + id + ".txt"), StandardCharsets.UTF_8));
        }
        if (mine != null) {
            out.add(mine);
        }
        return out;
    }

    /** One entry per name, whoever asked, and numbers 1 to the count of names. */
    private static void assertOnePerName(
        final Path shared, final List<List<String>> out, final int names
    ) throws Exception {
        final Map<String, Set<String>> byName = new HashMap<>();
        for (final List<String> one : out) {
            for (final String entry : one) {
                final String name = entry.substring(entry.indexOf('-') + 1);
                byName.computeIfAbsent(name, key -> new HashSet<>()).add(entry);
            }
        }
        assertEquals(names, byName.size(), "names asked for: " + byName.keySet());
        for (final Map.Entry<String, Set<String>> each : byName.entrySet()) {
            assertEquals(1, each.getValue().size(), "one entry for " + each.getKey());
        }
        final Set<Long> numbers = new HashSet<>();
        for (final String entry : Entries.names(shared)) {
            if (!Seqdir.LOCK_FILE.equals(entry)) {
                assertTrue(numbers.add(Entries.number(entry)), "unique number in " + entry);
            }
        }
        assertEquals(names, numbers.size(), "entries in the directory");
        for (long expected = 1; expected <= names; ++expected) {
            assertTrue(numbers.contains(expected), "number present: " + expected);
        }
    }

    @Test
    void sixJvmsOfEightThreadsCallingOnceForTheSameFreshNamesEndWithOneEntryPerName()
        throws Exception {
        final Path shared = this.tmp.resolve("shared");
        final int rounds = 8;
        final List<List<String>> out = this.launch(shared, 6, rounds, "threads");
        for (int idx = 0; idx < 6; ++idx) {
            assertEquals(8 * rounds * 3, out.get(idx).size());
        }
        ProcessesTest.assertOnePerName(shared, out, rounds * 3);
    }

    @Test
    void twoCopiesOfTheLibraryInOneJvmAndTwoChildJvmsEndWithOneEntryPerName()
        throws Exception {
        final Path shared = this.tmp.resolve("shared");
        final int rounds = 8;
        final List<String> mine = Collections.synchronizedList(new ArrayList<String>());
        final List<List<String>> out = this.launch(
            shared, 2, rounds, "threads", start -> ProcessesTest.copies(shared, start, rounds, mine),
            mine
        );
        assertEquals(8 * rounds * 3, mine.size());
        ProcessesTest.assertOnePerName(shared, out, rounds * 3);
    }

    /**
     * Loads the library twice through class loaders that share nothing but the
     * JDK, and lets four threads of each copy ask for the same fresh names.
     */
    private static void copies(
        final Path shared, final long start, final int rounds, final List<String> mine
    ) throws Exception {
        final URL classes = Seqdir.class.getProtectionDomain().getCodeSource().getLocation();
        final int threads = 8;
        final CyclicBarrier barrier = new CyclicBarrier(threads);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final List<URLClassLoader> loaders = new ArrayList<>();
        try {
            final List<Future<?>> futures = new ArrayList<>();
            Class<?> previous = null;
            for (int copy = 0; copy < 2; ++copy) {
                final URLClassLoader loader = new URLClassLoader(new URL[] {classes}, null);
                loaders.add(loader);
                final Class<?> seqdir = loader.loadClass(Seqdir.class.getName());
                assertNotSame(Seqdir.class, seqdir);
                assertNotSame(previous, seqdir);
                previous = seqdir;
                final Object dirs = seqdir.getMethod("dirs").invoke(
                    seqdir.getConstructor(Path.class, int.class).newInstance(shared, 3)
                );
                final Method once = loader.loadClass(Sequence.class.getName())
                    .getMethod("once", String.class);
                for (int thread = 0; thread < threads / 2; ++thread) {
                    futures.add(
                        pool.submit(
                            () -> {
                                while (System.currentTimeMillis() < start) {
                                    Thread.yield();
                                }
                                for (int round = 0; round < rounds; ++round) {
                                    barrier.await();
                                    for (int idx = 0; idx < 3; ++idx) {
                                        final Path path = (Path) once.invoke(
                                            dirs, "r" + round + "n" + idx
                                        );
                                        mine.add(path.getFileName().toString());
                                    }
                                }
                                return null;
                            }
                        )
                    );
                }
            }
            for (final Future<?> future : futures) {
                future.get(180L, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            for (final URLClassLoader loader : loaders) {
                loader.close();
            }
        }
    }

    @Test
    void sixJvmsCallingOnceWithOneNameEndWithOneEntry() throws Exception {
        final Path shared = this.tmp.resolve("shared");
        final List<List<String>> out = this.launch(shared, 6, 50, "same");
        final Set<String> seen = new HashSet<>();
        for (final List<String> one : out) {
            assertEquals(50, one.size());
            seen.addAll(one);
        }
        assertEquals(1, seen.size(), "one entry for all callers: " + seen);
        final String entry = seen.iterator().next();
        assertEquals(1L, Entries.number(entry));
        assertTrue(entry.endsWith("-same"), entry);
        assertEquals(
            Arrays.asList(Seqdir.LOCK_FILE, entry), Entries.names(shared)
        );
        assertTrue(Files.isDirectory(shared.resolve(entry)));
    }

    @Test
    void sixJvmsCallingOnceWithDifferentNamesGetUniqueNumbers() throws Exception {
        final Path shared = this.tmp.resolve("shared");
        final List<List<String>> out = this.launch(shared, 6, 60, "own");
        final List<String> all = new ArrayList<>();
        for (final List<String> one : out) {
            all.addAll(one);
        }
        final int total = 6 * 60;
        assertEquals(total, all.size());
        assertEquals(total, new HashSet<>(all).size(), "names are distinct");
        final Set<Long> numbers = new HashSet<>();
        for (final String name : all) {
            numbers.add(Entries.number(name));
        }
        assertEquals(total, numbers.size(), "numbers are distinct");
        for (long expected = 1; expected <= total; ++expected) {
            assertTrue(numbers.contains(expected), "number present: " + expected);
        }
        assertEquals(total + 1, Entries.names(shared).size(), "entries and the lock file");
        assertEquals(
            Collections.singletonList(Seqdir.LOCK_FILE), Entries.unfinished(shared)
        );
    }

    @Test
    void sixJvmsSharingThreeNamesAndAddingTheirOwnKeepOneEntryPerSharedName()
        throws Exception {
        final Path shared = this.tmp.resolve("shared");
        final List<List<String>> out = this.launch(shared, 6, 30, "shared");
        final Set<String> names = new HashSet<>();
        int sharedCalls = 0;
        for (final List<String> one : out) {
            for (final String name : one) {
                if (name.endsWith("-s0") || name.endsWith("-s1") || name.endsWith("-s2")) {
                    ++sharedCalls;
                    names.add(name);
                }
            }
        }
        assertEquals(6 * 15, sharedCalls);
        assertEquals(3, names.size(), "one entry per shared name: " + names);
        final Set<Long> numbers = new HashSet<>();
        for (final String name : Entries.names(shared)) {
            if (!Seqdir.LOCK_FILE.equals(name)) {
                assertTrue(numbers.add(Entries.number(name)), "unique number in " + name);
            }
        }
        assertEquals(3 + 6 * 15, numbers.size());
    }

    @Test
    void sixJvmsOfTwoWidthsClaimEighteenHundredNamesInOneDirectory() throws Exception {
        final int jvms = 6;
        final int each = 300;
        final Path shared = this.tmp.resolve("shared");
        final Path logs = Files.createDirectories(this.tmp.resolve("logs"));
        final String java = Paths.get(
            System.getProperty("java.home"), "bin", "java"
        ).toString();
        final long start = System.currentTimeMillis() + 3000L;
        final List<Process> procs = new ArrayList<>();
        for (int id = 0; id < jvms; ++id) {
            final ProcessBuilder builder = new ProcessBuilder(
                java, "-cp", System.getProperty("java.class.path"),
                ClaimMain.class.getName(),
                shared.toString(), Integer.toString(each), Integer.toString(id),
                Long.toString(start), Integer.toString(id % 2 == 0 ? 2 : 5)
            );
            builder.redirectOutput(logs.resolve("out" + id + ".txt").toFile());
            builder.redirectError(logs.resolve("err" + id + ".txt").toFile());
            procs.add(builder.start());
        }
        for (int id = 0; id < jvms; ++id) {
            final Process proc = procs.get(id);
            if (!proc.waitFor(180, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
            assertEquals(
                0, proc.exitValue(),
                "exit code of JVM " + id + ", stderr: " + new String(
                    Files.readAllBytes(logs.resolve("err" + id + ".txt")), StandardCharsets.UTF_8
                )
            );
        }
        final List<String> all = new ArrayList<>();
        for (int id = 0; id < jvms; ++id) {
            all.addAll(Files.readAllLines(logs.resolve("out" + id + ".txt"), StandardCharsets.UTF_8));
        }
        final int total = jvms * each;
        assertEquals(total, all.size());
        assertEquals(total, new HashSet<>(all).size(), "names are distinct");
        final Set<Long> numbers = new HashSet<>();
        for (final String name : all) {
            numbers.add(Entries.number(name));
        }
        assertEquals(total, numbers.size(), "numbers are distinct");
        for (long expected = 1; expected <= total; ++expected) {
            assertTrue(numbers.contains(expected), "number present: " + expected);
        }
        assertEquals(total, Entries.names(shared).size(), "entries in directory");
        assertEquals(0, Entries.unfinished(shared).size(), "bare claims left");
        for (final String name : all) {
            assertTrue(Harness.emptyEntry(shared.resolve(name)), "empty: " + name);
        }
    }
}
