package io.github.sekator778.seqdir;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A source of number-prefixed entries inside one directory.
 *
 * <p>Implementations are obtained from {@link Seqdir#files()} and
 * {@link Seqdir#dirs()}.
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
}
