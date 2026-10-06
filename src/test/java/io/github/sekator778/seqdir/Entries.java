package io.github.sekator778.seqdir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Helpers that look at a directory the way the tests need it. */
final class Entries {

    static final Pattern NUMBERED = Pattern.compile("^([0-9]+)-.+$", Pattern.DOTALL);

    private Entries() {
    }

    /** Sorted names of all entries of a directory. */
    static List<String> names(final Path dir) throws IOException {
        final List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (final Path entry : stream) {
                out.add(entry.getFileName().toString());
            }
        }
        Collections.sort(out);
        return out;
    }

    /** The number of a final name such as 0042-x, or -1 when it is bare or unnumbered. */
    static long number(final String name) {
        final Matcher match = NUMBERED.matcher(name);
        return match.matches() ? Long.parseLong(match.group(1)) : -1L;
    }

    /** Names of entries that are not of the form number-name (bare claims included). */
    static List<String> unfinished(final Path dir) throws IOException {
        final List<String> out = new ArrayList<>();
        for (final String name : names(dir)) {
            if (number(name) < 0) {
                out.add(name);
            }
        }
        return out;
    }
}
