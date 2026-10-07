package io.github.sekator778.seqdir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Entry point of a child JVM: claims names in a shared directory and prints
 * the file name of each returned path, one per line.
 *
 * <p>Arguments: directory, count, id, start time in epoch milliseconds, and
 * optionally the width (default 3) and a mode: {@code next} (default),
 * {@code same} (every call is {@code once} of one shared name), {@code own}
 * ({@code once} of a name of this process only) or {@code shared} ({@code once}
 * of one of three names shared by all processes, then of a name of this
 * process) or {@code threads} (eight threads, and for each of {@code count}
 * rounds every thread calls {@code once} for the same three names
 * {@code r<round>n<k>}; the names are fresh in every round). The process busy-waits until the start time so that all children begin at once.
 */
public final class ClaimMain {

    private ClaimMain() {
    }

    public static void main(final String[] args) throws Exception {
        final Seqdir seq = new Seqdir(args[0], args.length > 4 ? Integer.parseInt(args[4]) : 3);
        final int count = Integer.parseInt(args[1]);
        final String id = args[2];
        final long start = Long.parseLong(args[3]);
        final Sequence files = seq.files();
        final Sequence dirs = seq.dirs();
        while (System.currentTimeMillis() < start) {
            Thread.yield();
        }
        final String mode = args.length > 5 ? args[5] : "next";
        if ("threads".equals(mode)) {
            ClaimMain.threads(dirs, count, start);
            return;
        }
        final StringBuilder out = new StringBuilder();
        for (int idx = 0; idx < count; ++idx) {
            final boolean dir = idx % 2 == 1;
            final Path path;
            if ("same".equals(mode)) {
                path = dirs.once("same");
            } else if ("own".equals(mode)) {
                path = (dir ? dirs : files).once("p" + id + "-" + idx + (dir ? "" : ".txt"));
            } else if ("shared".equals(mode)) {
                path = idx % 2 == 0
                    ? dirs.once("s" + idx % 3) : files.once("p" + id + "-" + idx + ".txt");
            } else {
                path = (dir ? dirs : files).next("p" + id + "-" + idx + (dir ? "" : ".txt"));
            }
            out.append(path.getFileName()).append('\n');
        }
        System.out.print(out);
        System.out.flush();
    }

    /** Eight threads ask for the same three fresh names in every round. */
    static void threads(final Sequence dirs, final int rounds, final long start)
        throws Exception {
        final int threads = 8;
        final CyclicBarrier barrier = new CyclicBarrier(threads);
        final List<String> out = Collections.synchronizedList(new ArrayList<String>());
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final List<Future<?>> futures = new ArrayList<>();
        for (int thread = 0; thread < threads; ++thread) {
            futures.add(
                pool.submit(
                    () -> {
                        while (System.currentTimeMillis() < start) {
                            Thread.yield();
                        }
                        for (int round = 0; round < rounds; ++round) {
                            barrier.await();
                            for (int idx = 0; idx < 3; ++idx) {
                                out.add(
                                    dirs.once("r" + round + "n" + idx).getFileName().toString()
                                );
                            }
                        }
                        return null;
                    }
                )
            );
        }
        for (final Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();
        final StringBuilder text = new StringBuilder();
        for (final String name : out) {
            text.append(name).append('\n');
        }
        System.out.print(text);
        System.out.flush();
    }

    static Path dir(final String text) {
        return Paths.get(text);
    }
}
