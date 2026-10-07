package io.github.sekator778.seqdir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 180, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class OnceConcurrencyTest {

    @TempDir
    Path tmp;

    /** Runs the jobs on that many threads started together and returns what each returned. */
    private static <T> List<T> together(final int threads, final Callable<T> job)
        throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final CountDownLatch ready = new CountDownLatch(threads);
            final CountDownLatch go = new CountDownLatch(1);
            final List<Future<T>> futures = new ArrayList<>();
            for (int idx = 0; idx < threads; ++idx) {
                futures.add(
                    pool.submit(
                        () -> {
                            ready.countDown();
                            go.await();
                            return job.call();
                        }
                    )
                );
            }
            assertTrue(ready.await(60L, TimeUnit.SECONDS), "threads did not start");
            go.countDown();
            final List<T> all = new ArrayList<>();
            for (final Future<T> future : futures) {
                all.add(future.get(120L, TimeUnit.SECONDS));
            }
            return all;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Set<Long> numbers(final Path dir) throws Exception {
        final Set<Long> out = new HashSet<>();
        for (final String name : Entries.names(dir)) {
            if (!Seqdir.LOCK_FILE.equals(name)) {
                out.add(Entries.number(name));
            }
        }
        return out;
    }

    @RepeatedTest(10)
    void manyThreadsOnOneNameEndWithOneEntry() throws Exception {
        final Path dir = this.tmp.resolve("base");
        final List<Path> got = OnceConcurrencyTest.together(
            32, () -> new Seqdir(dir, 3).dirs().once("same")
        );
        assertEquals(1, new HashSet<>(got).size(), "one path for all callers");
        assertEquals(dir.resolve("001-same"), got.get(0));
        assertEquals(
            Arrays.asList(Seqdir.LOCK_FILE, "001-same"), Entries.names(dir)
        );
    }

    @RepeatedTest(10)
    void manyThreadsOnSeveralNamesEndWithOneEntryPerName() throws Exception {
        final Path dir = this.tmp.resolve("base");
        final int names = 8;
        final List<List<Path>> got = OnceConcurrencyTest.together(
            24, () -> {
                final Sequence dirs = new Seqdir(dir, 2).dirs();
                final List<Path> mine = new ArrayList<>();
                for (int idx = 0; idx < names; ++idx) {
                    mine.add(dirs.once("n" + idx));
                }
                return mine;
            }
        );
        for (int idx = 0; idx < names; ++idx) {
            final Set<Path> paths = new HashSet<>();
            for (final List<Path> one : got) {
                paths.add(one.get(idx));
            }
            assertEquals(1, paths.size(), "one path for n" + idx);
        }
        final Set<Long> numbers = OnceConcurrencyTest.numbers(dir);
        assertEquals(names, numbers.size(), "unique numbers");
        for (long expected = 1; expected <= names; ++expected) {
            assertTrue(numbers.contains(expected), "number present: " + expected);
        }
        assertEquals(names + 1, Entries.names(dir).size(), "entries and the lock file");
        assertEquals(1, Entries.unfinished(dir).size(), "no bare claims");
    }

    @Test
    void onceAndNextMixedOnDifferentNamesKeepNumbersUnique() throws Exception {
        final Path dir = this.tmp.resolve("base");
        final AtomicCounter ids = new AtomicCounter();
        final List<List<Path>> got = OnceConcurrencyTest.together(
            16, () -> {
                final int id = ids.next();
                final Seqdir seq = new Seqdir(dir, 3);
                final List<Path> mine = new ArrayList<>();
                for (int idx = 0; idx < 20; ++idx) {
                    final boolean once = (id + idx) % 2 == 0;
                    final String name = (once ? "o" : "n") + id + "-" + idx;
                    mine.add(once ? seq.dirs().once(name) : seq.files().next(name));
                }
                return mine;
            }
        );
        final Set<Path> all = new HashSet<>();
        for (final List<Path> one : got) {
            all.addAll(one);
        }
        assertEquals(16 * 20, all.size());
        final Set<Long> numbers = OnceConcurrencyTest.numbers(dir);
        assertEquals(16 * 20, numbers.size(), "unique numbers");
        for (long expected = 1; expected <= 16 * 20; ++expected) {
            assertTrue(numbers.contains(expected), "number present: " + expected);
        }
        assertEquals(1, Entries.unfinished(dir).size(), "only the lock file is unnumbered");
        for (final Path path : all) {
            assertTrue(Files.exists(path));
        }
    }

    @Test
    void onceOnTwoDirectoriesDoesNotMixThem() throws Exception {
        final Path one = this.tmp.resolve("one");
        final Path two = this.tmp.resolve("two");
        final List<List<Path>> got = OnceConcurrencyTest.together(
            16, () -> Arrays.asList(
                new Seqdir(one, 3).dirs().once("x"), new Seqdir(two, 3).dirs().once("x")
            )
        );
        for (final List<Path> pair : got) {
            assertEquals(one.resolve("001-x"), pair.get(0));
            assertEquals(two.resolve("001-x"), pair.get(1));
        }
    }

    /** A counter that hands out 0, 1, 2 and so on to the threads. */
    private static final class AtomicCounter {

        private final AtomicInteger value = new AtomicInteger();

        int next() {
            return this.value.getAndIncrement();
        }
    }
}
