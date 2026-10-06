package io.github.sekator778.seqdir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;

/**
 * Creates files and directories inside one directory under the first
 * available number-prefixed name: {@code 001-foo.txt}, {@code 002-bar.txt}.
 *
 * <h2>Numbering</h2>
 *
 * <p>An entry of the directory counts as numbered when its file name starts
 * with a non-empty run of ASCII digits {@code '0'} to {@code '9'} that is
 * followed by the end of the name or by a dash; whatever follows the dash,
 * line terminators included, is irrelevant. Other digits (Arabic-Indic,
 * fullwidth, Devanagari and so on) are not digits here, and names that do not
 * match are ignored. Entries of any kind (file, directory, link) count, and
 * files and directories share one numbering. The number is the value of the
 * digits, so leading zeros do not matter. A new entry gets the highest number
 * present plus one, left padded with zeros to the width given to the
 * constructor, which is between 1 and 18; a number that needs more digits is
 * written in full. Numbers have at most 18 digits: a numbered entry with more
 * significant digits is an error, not something to ignore, and makes
 * {@link Sequence#next(String)} throw an {@link IOException} naming the entry
 * before anything is created. The same exception is thrown, also before
 * anything is created, when the highest number is 999999999999999999 and the
 * sequence is exhausted.
 *
 * <p>Numbers are not remembered anywhere: the next number is the highest one
 * present plus one, so deleting the highest entry frees its number again,
 * while deleting a lower entry leaves a gap that is never filled. Every call
 * lists the directory twice, so the cost of a call is linear in the number of
 * entries.
 *
 * <h2>Claim protocol</h2>
 *
 * <p>This class holds no state besides the base path and the width, and uses
 * no locks or caches. Every guarantee comes from the file system, so threads
 * of one process and separate processes are covered by the same mechanism.
 * One attempt goes as follows:
 *
 * <ol>
 *   <li>Scan the directory; N is the highest number found plus one.</li>
 *   <li>Atomically create the bare claim entry, named just the plain decimal
 *    number without padding (for example {@code 5}, whatever the width), as
 *    an empty file or an empty directory, with
 *    {@link Files#createFile} or {@link Files#createDirectory}. These fail
 *    with {@link FileAlreadyExistsException} if anything has that name.</li>
 *   <li>If the creation failed because the name exists, start over at 1.</li>
 *   <li>Scan again. If any other entry with the number N exists now, someone
 *    finished claiming N between our scan and our claim: delete the bare
 *    entry and start over at 1.</li>
 *   <li>Rename the bare entry to {@code NNN-name} with an atomic move. If the
 *    file system cannot move atomically, fall back to a plain move that does
 *    not replace an existing target.</li>
 *   <li>Return the final path.</li>
 * </ol>
 *
 * <p>Why this is enough: a number is held by whoever owns the bare entry, and
 * only one creator can win the creation of that name. The bare name depends
 * on the number alone, never on the width, so objects with different widths
 * may share a directory: for one number they all contend for the same bare
 * name, and the scan in step 4 sees the finished entries of every width.
 * After the rename the number stays visible as {@code NNN-name}, so a later
 * creator that claims the bare name again (possible because the bare name is
 * free after the rename) sees that entry in step 4 and gives way. The bare
 * entry is visible to everyone else from step 2 until the rename. Because the
 * number is unique for every finished entry, the final name {@code NNN-name}
 * cannot collide with another entry created by this protocol; the rename in
 * step 5 never replaces anything unless some program outside the protocol
 * created that exact name in the meantime. A program that creates numbered
 * names on its own, without this class, is outside the protocol and can
 * collide with it.
 *
 * <p>The loop is bounded ({@value #MAX_ATTEMPTS} attempts) and never
 * recursive; when it is exhausted an {@link IOException} naming the directory
 * is thrown. A bare entry left behind by a crashed process is not cleaned up:
 * it counts as a taken number, and it may carry the number of a finished
 * entry, which is harmless. If the rename fails with an
 * {@link IOException} (for example because the name is too long for the file
 * system), the bare entry is removed again before the exception propagates.
 *
 * <p>Instances are immutable and may be shared freely between threads.
 */
public final class Seqdir {

    /** The most attempts one call of {@link Sequence#next(String)} makes. */
    static final int MAX_ATTEMPTS = 10_000;

    /** The most digits a number may have. */
    private static final int MAX_DIGITS = 18;

    /** The highest number that can be handed out. */
    private static final long MAX_NUMBER = 999_999_999_999_999_999L;

    /** The directory that holds the entries. */
    private final Path dir;

    /** The minimum number of digits of the prefix. */
    private final int width;

    /**
     * Creates a sequence over a directory.
     *
     * @param dir the directory; created on first use if absent
     * @param width the minimum number of digits of the prefix, from 1 to 18
     * @throws NullPointerException if the directory is null
     * @throws IllegalArgumentException if the width is not between 1 and 18
     */
    public Seqdir(final String dir, final int width) {
        this(Paths.get(Objects.requireNonNull(dir, "dir")), width);
    }

    /**
     * Creates a sequence over a directory.
     *
     * @param dir the directory; created on first use if absent
     * @param width the minimum number of digits of the prefix, from 1 to 18
     * @throws NullPointerException if the directory is null
     * @throws IllegalArgumentException if the width is not between 1 and 18
     */
    public Seqdir(final Path dir, final int width) {
        Objects.requireNonNull(dir, "dir");
        if (width < 1 || width > MAX_DIGITS) {
            throw new IllegalArgumentException(
                "width must be between 1 and " + MAX_DIGITS + ", got " + width
            );
        }
        this.dir = dir;
        this.width = width;
    }

    /**
     * A sequence whose {@link Sequence#next(String)} creates an empty regular
     * file.
     *
     * @return the sequence
     */
    public Sequence files() {
        return new Claiming(this.dir, this.width, false);
    }

    /**
     * A sequence whose {@link Sequence#next(String)} creates an empty
     * directory.
     *
     * @return the sequence
     */
    public Sequence dirs() {
        return new Claiming(this.dir, this.width, true);
    }

    /** The implementation of the claim protocol. */
    private static final class Claiming implements Sequence {

        /** The directory that holds the entries. */
        private final Path dir;

        /** The minimum number of digits of the prefix. */
        private final int width;

        /** True to create directories, false to create files. */
        private final boolean directory;

        Claiming(final Path dir, final int width, final boolean directory) {
            this.dir = dir;
            this.width = width;
            this.directory = directory;
        }

        @Override
        public Path next(final String name) throws IOException {
            this.check(name);
            this.prepare();
            for (int attempt = 0; attempt < MAX_ATTEMPTS; ++attempt) {
                final long highest = this.highest();
                if (highest >= MAX_NUMBER) {
                    throw new IOException(
                        "the sequence in " + this.dir + " is exhausted"
                    );
                }
                final long number = highest + 1L;
                final String bare = Long.toString(number);
                final Path claim = this.dir.resolve(bare);
                try {
                    this.create(claim);
                } catch (final FileAlreadyExistsException ex) {
                    continue;
                }
                final boolean other;
                try {
                    other = this.taken(number, bare);
                } catch (final IOException ex) {
                    Claiming.discard(claim, ex);
                    throw ex;
                }
                if (other) {
                    Files.delete(claim);
                    continue;
                }
                final Path target = this.dir.resolve(
                    this.pad(number) + '-' + name
                );
                try {
                    Claiming.rename(claim, target);
                } catch (final IOException ex) {
                    Claiming.discard(claim, ex);
                    throw ex;
                }
                return target;
            }
            throw new IOException(
                String.format(
                    "no free number in %s after %d attempts",
                    this.dir, MAX_ATTEMPTS
                )
            );
        }

        private void check(final String name) {
            if (name == null || name.isEmpty()
                || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
                throw new IllegalArgumentException(
                    "invalid name: " + (name == null ? "null" : "'" + name + "'")
                );
            }
            try {
                this.dir.resolve("0-" + name);
            } catch (final InvalidPathException ex) {
                throw new IllegalArgumentException(
                    "invalid name: '" + name + "'", ex
                );
            }
        }

        private void prepare() throws IOException {
            if (Files.isDirectory(this.dir)) {
                return;
            }
            // Another thread or process may create the directory between
            // the checks, so every refusal looks again before it is final.
            if (Files.exists(this.dir, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isDirectory(this.dir)) {
                    return;
                }
                throw this.notDirectory();
            }
            try {
                Files.createDirectories(this.dir);
            } catch (final FileAlreadyExistsException ex) {
                if (!Files.isDirectory(this.dir)) {
                    throw this.notDirectory();
                }
            }
        }

        private IOException notDirectory() {
            return new IOException(this.dir + " exists and is not a directory");
        }

        private void create(final Path path) throws IOException {
            if (this.directory) {
                Files.createDirectory(path);
            } else {
                Files.createFile(path);
            }
        }

        private String pad(final long number) {
            final String plain = Long.toString(number);
            final int zeros = Math.max(0, this.width - plain.length());
            final char[] text = new char[zeros + plain.length()];
            Arrays.fill(text, 0, zeros, '0');
            plain.getChars(0, plain.length(), text, zeros);
            return new String(text);
        }

        /**
         * The highest number among the entries of the directory.
         *
         * @return the highest number, or 0 if there is no numbered entry
         * @throws IOException if the directory cannot be read
         */
        private long highest() throws IOException {
            final long[] max = {0L};
            this.scan(
                entry -> {
                    max[0] = Math.max(max[0], Claiming.number(entry));
                    return false;
                }
            );
            return max[0];
        }

        /**
         * Whether an entry other than the bare claim has this number.
         *
         * @param number the number
         * @param bare the name of our own bare claim entry
         * @return true if someone else holds the number
         * @throws IOException if the directory cannot be read
         */
        private boolean taken(final long number, final String bare)
            throws IOException {
            return this.scan(
                entry -> Claiming.number(entry) == number
                    && !bare.equals(entry.getFileName().toString())
            );
        }

        /**
         * Offers every entry of the directory to a visitor until it accepts
         * one. The unchecked {@link DirectoryIteratorException} of the stream
         * is turned back into the {@link IOException} it carries.
         *
         * @param visitor the visitor; returns true to stop the scan
         * @return true if the visitor stopped the scan
         * @throws IOException if the directory cannot be read or the visitor
         *  fails
         */
        private boolean scan(final Visitor visitor) throws IOException {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.dir)) {
                for (final Path entry : stream) {
                    if (visitor.visit(entry)) {
                        return true;
                    }
                }
            } catch (final DirectoryIteratorException ex) {
                throw ex.getCause();
            }
            return false;
        }

        /** Looks at one entry of the directory. */
        private interface Visitor {

            /**
             * Looks at an entry.
             *
             * @param entry the entry
             * @return true to stop the scan
             * @throws IOException on failure
             */
            boolean visit(Path entry) throws IOException;
        }

        /**
         * The number in the name of an entry.
         *
         * @param entry the entry
         * @return the number, or -1 if the name is not numbered
         * @throws IOException if the number has more than 18 digits
         */
        private static long number(final Path entry) throws IOException {
            final String name = entry.getFileName().toString();
            int end = 0;
            while (end < name.length()
                && name.charAt(end) >= '0' && name.charAt(end) <= '9') {
                ++end;
            }
            if (end == 0 || end < name.length() && name.charAt(end) != '-') {
                return -1L;
            }
            int start = 0;
            while (start < end && name.charAt(start) == '0') {
                ++start;
            }
            if (end - start > MAX_DIGITS) {
                throw new IOException(entry + ": the number is too large");
            }
            long result = 0L;
            for (int idx = start; idx < end; ++idx) {
                result = result * 10L + (name.charAt(idx) - '0');
            }
            return result;
        }

        private static void rename(final Path from, final Path to)
            throws IOException {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException ex) {
                Files.move(from, to);
            }
        }

        private static void discard(final Path claim, final IOException cause) {
            try {
                Files.deleteIfExists(claim);
            } catch (final IOException ex) {
                cause.addSuppressed(ex);
            }
        }
    }
}
