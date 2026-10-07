package io.github.sekator778.seqdir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Entry point of a child JVM that holds a file lock: takes the lock on the
 * file given as the only argument, prints {@code locked}, and releases the lock
 * when its standard input ends or delivers a byte.
 */
public final class LockMain {

    private LockMain() {
    }

    public static void main(final String[] args) throws Exception {
        try (FileChannel channel = FileChannel.open(
            Paths.get(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE
        )) {
            final FileLock lock = channel.lock();
            System.out.println("locked");
            System.out.flush();
            System.in.read();
            lock.release();
        }
    }
}
