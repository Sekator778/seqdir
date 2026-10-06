package io.github.sekator778.seqdir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Runs many threads against a pair of sequences and returns what they got. */
final class Harness {

    private Harness() {
    }

    /**
     * Starts the threads together and lets each claim {@code per} names,
     * alternating between the file and the directory sequence.
     */
    static List<Path> run(
        final Sequence files, final Sequence dirs,
        final int threads, final int per
    ) throws Exception {
        return Harness.run(
            Collections.singletonList(files), Collections.singletonList(dirs),
            threads, per, ""
        );
    }

    /**
     * Like {@link #run(Sequence, Sequence, int, int)}, but thread number
     * {@code t} uses the pair at index {@code t % files.size()}.
     */
    static List<Path> run(
        final List<Sequence> files, final List<Sequence> dirs,
        final int threads, final int per
    ) throws Exception {
        return Harness.run(files, dirs, threads, per, "");
    }

    /** Like the list variant, with {@code infix} inserted into every name. */
    static List<Path> run(
        final List<Sequence> files, final List<Sequence> dirs,
        final int threads, final int per, final String infix
    ) throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final CountDownLatch ready = new CountDownLatch(threads);
            final CountDownLatch go = new CountDownLatch(1);
            final List<Future<List<Path>>> futures = new ArrayList<>();
            for (int thread = 0; thread < threads; ++thread) {
                final int id = thread;
                final Callable<List<Path>> job = () -> {
                    ready.countDown();
                    go.await();
                    final List<Path> got = new ArrayList<>();
                    for (int idx = 0; idx < per; ++idx) {
                        final boolean dir = (id + idx) % 2 == 0;
                        final String name = "t" + id + "-" + idx + infix + (dir ? "" : ".txt");
                        final int pick = id % files.size();
                        got.add((dir ? dirs : files).get(pick).next(name));
                    }
                    return got;
                };
                futures.add(pool.submit(job));
            }
            if (!ready.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("threads did not start");
            }
            go.countDown();
            final List<Path> all = new ArrayList<>();
            for (final Future<List<Path>> future : futures) {
                all.addAll(future.get(120, TimeUnit.SECONDS));
            }
            return all;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Whether the entry at the path is empty and of the kind its name says. */
    static boolean emptyEntry(final Path path) throws IOException {
        if (Files.isDirectory(path)) {
            return Entries.names(path).isEmpty();
        }
        return Files.isRegularFile(path) && Files.size(path) == 0L;
    }
}
