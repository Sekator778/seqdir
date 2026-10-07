package io.github.sekator778.seqdir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

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
 * <p>{@link Sequence#next(String)} and {@link Sequence#find(String)} hold no
 * state besides the base path and the width, and use no locks or caches. Every
 * guarantee comes from the file system, so threads of one process and
 * separate processes are covered by the same mechanism. One attempt of
 * {@code next} goes as follows:
 *
 * <ol>
 *   <li>Scan the directory; N is the highest number found plus one.</li>
 *   <li>Atomically create the bare claim entry, named just the plain decimal
 *    number without padding (for example {@code 5}, whatever the width), as
 *    an empty file or an empty directory, with
 *    {@link Files#createFile} or {@link Files#createDirectory}. These fail
 *    with {@link FileAlreadyExistsException} if anything has that name.</li>
 *   <li>If the creation failed because the name exists, start over at 1. So
 *    does a creation that failed with {@link AccessDeniedException}, because
 *    Windows reports a name held by an entry of the other kind, or by one
 *    that is being deleted, that way. Such a denial is retried while the base
 *    directory is writable, at most {@value #MAX_DENIALS} times in one call;
 *    then the last denial is thrown. A denial on a directory that is not
 *    writable is a real error and is thrown at once.</li>
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
 * <h2>Once per name</h2>
 *
 * <p>{@link Sequence#find(String)} looks an entry up by name, and
 * {@link Sequence#once(String)} returns it or creates it, so that every caller
 * gets the same entry for the same name. That cannot be done with the claim
 * protocol alone, because a bare claim does not carry the name, so creation by
 * {@code once} is serialised: first by a monitor inside the JVM, then by a
 * {@link FileLock} on the file {@value #LOCK_FILE} in the base directory.
 * The monitor is taken on an interned string made of a fixed prefix and the
 * real path of the base directory, which is one object for the whole JVM, also
 * across class loaders, so every copy of this library in the JVM serialises on
 * it. That is required, not an optimisation: on POSIX systems closing any
 * channel on a file releases every lock the process holds on that file, so two
 * channels on the lock file must never be open at once in one JVM. Under both
 * locks the entry is looked up again and, if it is still missing, created by
 * the claim protocol above. The operating system releases the file lock when a
 * process dies, so a crash cannot leave a name locked. The lock file stays in
 * the directory; its name does not start with a digit, so it is never a
 * numbered entry. A file system that cannot lock makes {@code once} fail with
 * an {@link IOException}. A lock on the same file taken by other code in the
 * JVM is reported as an {@link OverlappingFileLockException}, which
 * {@code once} treats as contention. {@code next} takes no lock, so entries it
 * creates are not covered by the promise of {@code once}. The library has no
 * static mutable state. The lock file must stay while the directory is in
 * use: if it is deleted or replaced while callers are inside {@code once},
 * they are no longer excluded from each other and a name can get two entries;
 * a lock file that disappears between calls, or while a caller waits, is
 * noticed and created again.
 *
 * <p>Instances are immutable and may be shared freely between threads.
 */
public final class Seqdir {

    /** The most attempts one call of {@link Sequence#next(String)} makes. */
    static final int MAX_ATTEMPTS = 10_000;

    /** The most access denials one call tolerates while claiming. */
    private static final int MAX_DENIALS = 1_000;

    /** The name of the file that carries the lock of {@code once}. */
    static final String LOCK_FILE = ".seqdir.lock";

    /** The prefix of the monitor that {@code once} takes inside the JVM. */
    private static final String MONITOR = "io.github.sekator778.seqdir:";

    /** How long {@code once} waits before it asks for a busy file lock again. */
    private static final long PAUSE = 10L;

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
            AccessDeniedException denied = null;
            int denials = 0;
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
                } catch (final AccessDeniedException ex) {
                    // Windows reports a name taken by an entry of the other
                    // kind, or by one being deleted, as access denied. While
                    // the directory is writable that is contention: retry,
                    // a bounded number of times.
                    ++denials;
                    if (!Files.isWritable(this.dir) || denials > MAX_DENIALS) {
                        throw ex;
                    }
                    denied = ex;
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
                ),
                denied
            );
        }

        @Override
        public Optional<Path> find(final String name) throws IOException {
            this.check(name);
            final Path[] best = {null};
            final long[] lowest = {-1L};
            try {
                this.scan(
                    entry -> {
                        final long number = Claiming.number(entry);
                        if (number >= 0L && this.matches(entry, name)
                            && Claiming.earlier(number, entry, lowest[0], best[0])) {
                            lowest[0] = number;
                            best[0] = entry;
                        }
                        return false;
                    }
                );
            } catch (final NoSuchFileException ex) {
                return Optional.empty();
            }
            return Optional.ofNullable(best[0]);
        }

        @Override
        public Path once(final String name) throws IOException {
            final Optional<Path> known = this.find(name);
            if (known.isPresent()) {
                return known.get();
            }
            this.prepare();
            // POSIX: closing any channel on a file releases every lock the
            // process holds on that file, so two channels on the lock file
            // must never be open at once in one JVM. An interned string is one
            // object for the whole JVM, also across class loaders, so every
            // copy of this library serialises on it.
            synchronized ((Seqdir.MONITOR + this.dir.toRealPath()).intern()) {
                return this.exclusive(name);
            }
        }

        /**
         * Takes the file lock of the directory, looks the name up again and
         * creates it if it is still missing.
         *
         * <p>The lock file may be deleted or replaced while a thread waits for
         * its lock, and the lock would then be held on a file that no longer
         * has the name, so that a newcomer locks another file and the two are
         * not kept apart. So the file the path names is looked at right after
         * the channel is opened and again once the lock is held; if the path
         * is gone or names another file, the lock is dropped and the attempt
         * starts over with a fresh channel. The check covers a deletion or a
         * replacement before the lock is held; one while the lock is held is
         * not covered at all. A file system that gives no file key is checked
         * for existence only.
         *
         * @param name the name
         * @return the path of the entry
         * @throws IOException if the lock cannot be taken, if the lock file is
         *  not a regular file, if it kept changing for the whole attempt
         *  limit, or if the entry cannot be looked up or created
         */
        private Path exclusive(final String name) throws IOException {
            final Path file = this.dir.resolve(LOCK_FILE);
            for (int attempt = 0; attempt < MAX_ATTEMPTS; ++attempt) {
                this.regular(file);
                try (FileChannel channel = FileChannel.open(
                    file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
                )) {
                    final BasicFileAttributes first = this.regular(file);
                    if (first == null) {
                        continue;
                    }
                    final FileLock lock = this.lock(channel);
                    try {
                        final BasicFileAttributes second = this.regular(file);
                        if (second != null
                            && Objects.equals(first.fileKey(), second.fileKey())) {
                            final Optional<Path> known = this.find(name);
                            if (known.isPresent()) {
                                return known.get();
                            }
                            return this.next(name);
                        }
                    } finally {
                        lock.release();
                    }
                }
            }
            throw new IOException(
                "the lock file in " + this.dir + " kept changing during "
                + MAX_ATTEMPTS + " attempts"
            );
        }

        /**
         * The attributes of the lock file, read without following links.
         *
         * @param file the path of the lock file
         * @return the attributes, or null if there is no such file
         * @throws IOException if the path names something that is not a
         *  regular file (a directory, a link, a pipe), or cannot be read
         */
        private BasicFileAttributes regular(final Path file) throws IOException {
            final BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
                );
            } catch (final NoSuchFileException ex) {
                return null;
            }
            if (!attrs.isRegularFile()) {
                throw new IOException(file + " is not a regular file");
            }
            return attrs;
        }

        /**
         * Takes the file lock, waiting for as long as it is busy. A lock held
         * by this JVM through another channel, which only code outside this
         * library can have, is reported by the channel as an
         * {@link OverlappingFileLockException}; that is contention, so it is
         * asked for again after a pause, on the same channel. A thread that is
         * interrupted while another process holds the lock gets a
         * {@link FileLockInterruptionException}.
         *
         * @param channel the open lock file
         * @return the lock
         * @throws IOException if the file system cannot lock, or if the thread
         *  is interrupted while it waits
         */
        private FileLock lock(final FileChannel channel) throws IOException {
            while (true) {
                try {
                    return channel.lock();
                } catch (final OverlappingFileLockException ex) {
                    try {
                        Thread.sleep(PAUSE);
                    } catch (final InterruptedException interrupt) {
                        throw this.interrupted();
                    }
                } catch (final FileLockInterruptionException
                    | ClosedByInterruptException ex) {
                    throw this.interrupted();
                }
            }
        }

        /**
         * The exception for a thread that was interrupted while waiting; the
         * interrupt flag is set again.
         *
         * @return the exception to throw
         */
        private InterruptedIOException interrupted() {
            Thread.currentThread().interrupt();
            return new InterruptedIOException(
                "interrupted while waiting for the lock of " + this.dir
            );
        }

        /**
         * Whether the entry is named {@code <number>-<name>} and is of the
         * kind of this sequence. The name part is compared after Unicode
         * normalisation to NFC, so that the composed and the decomposed
         * spelling of a name are the same name.
         *
         * @param entry the entry, known to start with a number
         * @param name the name
         * @return true if it matches
         * @throws IOException if the kind of a matching entry cannot be read
         *  for a reason other than that the entry is gone
         */
        private boolean matches(final Path entry, final String name)
            throws IOException {
            final String file = entry.getFileName().toString();
            int end = 0;
            while (end < file.length()
                && file.charAt(end) >= '0' && file.charAt(end) <= '9') {
                ++end;
            }
            if (end == file.length() || file.charAt(end) != '-'
                || !Claiming.same(file.substring(end + 1), name)) {
                return false;
            }
            final BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(entry, BasicFileAttributes.class);
            } catch (final NoSuchFileException ex) {
                return false;
            }
            return this.directory ? attrs.isDirectory() : attrs.isRegularFile();
        }

        /**
         * Whether two names are the same name: equal as they are, or equal
         * after normalisation to NFC. The normaliser is not reached when the
         * names are equal, when both are ASCII, or when both are normalised
         * already.
         *
         * @param left a name
         * @param right a name
         * @return true if they are the same name
         */
        private static boolean same(final String left, final String right) {
            if (left.equals(right)) {
                return true;
            }
            if (Claiming.ascii(left) && Claiming.ascii(right)) {
                return false;
            }
            if (Normalizer.isNormalized(left, Normalizer.Form.NFC)
                && Normalizer.isNormalized(right, Normalizer.Form.NFC)) {
                return false;
            }
            return Normalizer.normalize(left, Normalizer.Form.NFC).equals(
                Normalizer.normalize(right, Normalizer.Form.NFC)
            );
        }

        /**
         * Whether a text has only ASCII characters.
         *
         * @param text the text
         * @return true if every character is below 128
         */
        private static boolean ascii(final String text) {
            for (int idx = 0; idx < text.length(); ++idx) {
                if (text.charAt(idx) >= 128) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Whether a candidate comes before the best one so far.
         *
         * @param number the number of the candidate
         * @param entry the candidate
         * @param lowest the number of the best one, or -1 if there is none
         * @param best the best one, or null
         * @return true if the candidate has a lower number, or an equal number
         *  and a smaller file name
         */
        private static boolean earlier(
            final long number, final Path entry,
            final long lowest, final Path best) {
            return best == null || number < lowest
                || number == lowest && entry.getFileName().toString().compareTo(
                    best.getFileName().toString()
                ) < 0;
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
