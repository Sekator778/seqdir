package io.github.sekator778.seqdir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A source of number-prefixed entries inside one directory.
 *
 * <p>Implementations are obtained from {@link Seqdir#files()} and
 * {@link Seqdir#dirs()}. {@link #next(String)} and {@link #find(String)} keep
 * no state and take no locks; {@link #once(String)} takes locks, described
 * there.
 */
public interface Sequence {

    /**
     * Creates the next entry and returns its path.
     *
     * <p>The entry is named {@code <number>-<name>}, where the number is one
     * more than the highest number already present in the directory, left
     * padded with zeros to the configured width. The entry exists when this
     * method returns: an empty regular file or an empty directory, depending
     * on the sequence.
     *
     * @param name the name after the number prefix; must not be null, empty,
     *  {@code "."}, {@code ".."} or contain {@code '/'} or {@code '\\'}, and
     *  must be acceptable to the platform in a path (for example no NUL
     *  character, and none of the characters Windows forbids in file names)
     * @return the path of the created entry, resolved against the base
     *  directory given to {@link Seqdir}
     * @throws IllegalArgumentException if the name is not acceptable,
     *  including a name that cannot be turned into a path; this is checked
     *  before anything is created, not even the base directory
     * @throws IOException if the base directory cannot be created or is not
     *  a directory, if the file system refuses the entry (for example a name
     *  that is too long or a read-only directory), if no free number was
     *  obtained within the attempt limit, if an entry of the directory has a
     *  number of more than 18 digits (the message names the entry), or if the
     *  sequence is exhausted because the highest number is 999999999999999999
     */
    Path next(String name) throws IOException;

    /**
     * Finds the entry of this sequence's kind (a directory for
     * {@link Seqdir#dirs()}, a regular file for {@link Seqdir#files()}) named
     * {@code <number>-<name>}, with the same number parsing as
     * {@link #next(String)}: ASCII digits, a dash, then exactly the name.
     *
     * <p>If several entries match, the one with the lowest number wins; for
     * equal numbers (such as {@code 1-x} and {@code 01-x}) the one whose file
     * name is lexicographically smaller. Names are compared after Unicode
     * normalisation to NFC, so the composed and the decomposed spelling of a
     * name are the same name. Nothing is created, not even the base directory,
     * and no lock is taken: a missing base directory gives an empty result.
     * The result reflects the directory at the moment it was listed. The
     * directory is listed once, so the cost grows with the number of entries,
     * as for {@link #next(String)}.
     *
     * @param name the name after the number prefix; the same rules as for
     *  {@link #next(String)}
     * @return the path of the entry, or empty if there is none
     * @throws IllegalArgumentException if the name is not acceptable; checked
     *  before the directory is read
     * @throws IOException if the base directory cannot be read, if the kind of
     *  an entry that has the name cannot be read (for example in a directory
     *  that can be listed but not searched), or if an entry of it has a number
     *  of more than 18 digits (the message names the entry)
     */
    Optional<Path> find(String name) throws IOException;

    /**
     * Returns the entry {@link #find(String)} finds for the name, or creates
     * it under the next number when there is none.
     *
     * <p>Every caller that goes through this method, in any thread and any
     * process that shares the directory, gets the same entry for the same
     * name. Entries that {@link #next(String)} creates under the same name are
     * outside that promise; when {@code next} has made several, this method
     * returns the one with the lowest number. When the entry exists, this is
     * a plain {@code find}, with no lock. Otherwise creation is serialised by
     * a monitor inside the JVM, shared by every copy of this library in it,
     * and by a file lock on the file {@code .seqdir.lock} in the directory,
     * which stays there; the operating system releases the file lock when a
     * process dies. Under both the entry is looked up again and, if it is
     * still missing, created as {@link #next(String)} does.
     *
     * <p>A file system that cannot lock files makes this method fail with the
     * {@link IOException} of the locking attempt; there is no fallback. The
     * method waits for the file lock for as long as another process holds it,
     * with no timeout. Every process that uses it on a directory must be able
     * to write {@code .seqdir.lock}, which is created with the permissions the
     * umask of the process gives. A thread that is interrupted while it waits
     * for the file lock, which another process may hold, gets an
     * {@link java.io.InterruptedIOException}, and its interrupt flag stays
     * set; so does a thread whose flag is already set when the method has to
     * create. Waiting for the monitor is not interruptible: a thread that
     * waits for another thread of the same JVM notices the interruption when
     * it reaches the file lock. The lock file must stay while the directory is
     * in use: if it is deleted or replaced while callers are inside this
     * method, they are no longer excluded from each other and a name can get
     * two entries; a lock file that disappears between calls, or while a
     * caller waits, is noticed and created again.
     *
     * @param name the name after the number prefix; the same rules as for
     *  {@link #next(String)}
     * @return the path of the entry, which exists when this method returns
     * @throws IllegalArgumentException if the name is not acceptable
     * @throws IOException for the reasons {@link #next(String)} and
     *  {@link #find(String)} give, if the lock cannot be taken, if the lock
     *  file is not a regular file, or if the thread is interrupted
     */
    Path once(String name) throws IOException;
}
