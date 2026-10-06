package io.github.sekator778.seqdir;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point of a child JVM: claims names in a shared directory and prints
 * the file name of each returned path, one per line.
 *
 * <p>Arguments: directory, count, id, start time in epoch milliseconds, and
 * optionally the width (default 3). The
 * process busy-waits until the start time so that all children begin at once.
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
        final StringBuilder out = new StringBuilder();
        for (int idx = 0; idx < count; ++idx) {
            final boolean dir = idx % 2 == 1;
            final Path path = (dir ? dirs : files).next("p" + id + "-" + idx + (dir ? "" : ".txt"));
            out.append(path.getFileName()).append('\n');
        }
        System.out.print(out);
        System.out.flush();
    }

    static Path dir(final String text) {
        return Paths.get(text);
    }
}
