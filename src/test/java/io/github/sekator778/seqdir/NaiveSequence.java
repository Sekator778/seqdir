package io.github.sekator778.seqdir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The obvious but wrong implementation: scan, take the highest number plus one
 * and create the final name directly, with no claim. It exists only to show
 * that the thread harness can detect a broken implementation.
 */
final class NaiveSequence implements Sequence {

    private final Path dir;
    private final boolean directory;

    NaiveSequence(final Path dir, final boolean directory) {
        this.dir = dir;
        this.directory = directory;
    }

    @Override
    public Path next(final String name) throws IOException {
        Files.createDirectories(this.dir);
        long max = 0L;
        for (final String entry : Entries.names(this.dir)) {
            max = Math.max(max, Entries.number(entry));
        }
        final Path path = this.dir.resolve(String.format("%03d-%s", max + 1L, name));
        if (this.directory) {
            Files.createDirectory(path);
        } else {
            Files.createFile(path);
        }
        return path;
    }
}
