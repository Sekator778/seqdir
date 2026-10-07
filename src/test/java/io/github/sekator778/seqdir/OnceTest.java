package io.github.sekator778.seqdir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OnceTest {

    @TempDir
    Path tmp;

    @Test
    void findInAnAbsentDirectoryIsEmptyAndCreatesNothing() throws Exception {
        final Path base = this.tmp.resolve("later").resolve("deeper");
        assertFalse(new Seqdir(base, 3).dirs().find("x").isPresent());
        assertFalse(new Seqdir(base, 3).files().find("x").isPresent());
        assertFalse(Files.exists(this.tmp.resolve("later")));
        assertTrue(Entries.names(this.tmp).isEmpty());
    }

    @Test
    void findWithoutAMatchIsEmptyAndCreatesNothing() throws Exception {
        Files.createDirectory(this.tmp.resolve("001-a"));
        Files.createFile(this.tmp.resolve("unnumbered"));
        assertFalse(new Seqdir(this.tmp, 3).dirs().find("b").isPresent());
        assertEquals(Arrays.asList("001-a", "unnumbered"), Entries.names(this.tmp));
    }

    @Test
    void findMatchesTheKindOfTheSequence() throws Exception {
        Files.createDirectory(this.tmp.resolve("001-d"));
        Files.createFile(this.tmp.resolve("002-f"));
        final Seqdir seq = new Seqdir(this.tmp, 3);
        assertEquals(Optional.of(this.tmp.resolve("001-d")), seq.dirs().find("d"));
        assertFalse(seq.files().find("d").isPresent());
        assertEquals(Optional.of(this.tmp.resolve("002-f")), seq.files().find("f"));
        assertFalse(seq.dirs().find("f").isPresent());
    }

    @Test
    void findPrefersTheLowestNumber() throws Exception {
        Files.createDirectory(this.tmp.resolve("010-x"));
        Files.createDirectory(this.tmp.resolve("003-x"));
        Files.createDirectory(this.tmp.resolve("007-x"));
        assertEquals(
            Optional.of(this.tmp.resolve("003-x")),
            new Seqdir(this.tmp, 3).dirs().find("x")
        );
    }

    @Test
    void findComparesNumbersByValueNotByText() throws Exception {
        Files.createDirectory(this.tmp.resolve("9-x"));
        Files.createDirectory(this.tmp.resolve("10-x"));
        assertEquals(
            Optional.of(this.tmp.resolve("9-x")),
            new Seqdir(this.tmp, 3).dirs().find("x")
        );
    }

    @Test
    void findBreaksATieByTheSmallerFileName() throws Exception {
        Files.createDirectory(this.tmp.resolve("1-x"));
        Files.createDirectory(this.tmp.resolve("01-x"));
        Files.createDirectory(this.tmp.resolve("001-x"));
        assertEquals(
            Optional.of(this.tmp.resolve("001-x")),
            new Seqdir(this.tmp, 3).dirs().find("x")
        );
    }

    @Test
    void findNeedsExactlyTheName() throws Exception {
        Files.createDirectory(this.tmp.resolve("001-xy"));
        Files.createDirectory(this.tmp.resolve("002-yx"));
        Files.createDirectory(this.tmp.resolve("003-x-y"));
        Files.createDirectory(this.tmp.resolve("004-x.txt"));
        Files.createDirectory(this.tmp.resolve("005-X"));
        final Sequence dirs = new Seqdir(this.tmp, 3).dirs();
        assertFalse(dirs.find("x").isPresent());
        assertFalse(dirs.find("y").isPresent());
        assertEquals(Optional.of(this.tmp.resolve("001-xy")), dirs.find("xy"));
        assertEquals(Optional.of(this.tmp.resolve("003-x-y")), dirs.find("x-y"));
    }

    @Test
    void findIgnoresBareEntriesAndUnnumberedNames() throws Exception {
        Files.createDirectory(this.tmp.resolve("5"));
        Files.createDirectory(this.tmp.resolve("005"));
        Files.createDirectory(this.tmp.resolve("x"));
        Files.createDirectory(this.tmp.resolve("-x"));
        Files.createDirectory(this.tmp.resolve("a-x"));
        Files.createDirectory(this.tmp.resolve("٣-x"));
        Files.createDirectory(this.tmp.resolve("5x-x"));
        assertFalse(new Seqdir(this.tmp, 3).dirs().find("x").isPresent());
    }

    @Test
    void findDoesNotMatchTheLockFile() throws Exception {
        new Seqdir(this.tmp, 3).dirs().once("a");
        assertTrue(Files.exists(this.tmp.resolve(Seqdir.LOCK_FILE)));
        assertFalse(new Seqdir(this.tmp, 3).files().find(Seqdir.LOCK_FILE).isPresent());
        assertFalse(new Seqdir(this.tmp, 3).files().find("lock").isPresent());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {".", "..", "a/b", "/a", "a/", "a\\b", "a\u0000b"})
    void findAndOnceRejectInvalidNames(final String name) throws Exception {
        final Path base = this.tmp.resolve("base");
        final Seqdir seq = new Seqdir(base, 3);
        assertThrows(IllegalArgumentException.class, () -> seq.files().find(name));
        assertThrows(IllegalArgumentException.class, () -> seq.dirs().find(name));
        assertThrows(IllegalArgumentException.class, () -> seq.files().once(name));
        assertThrows(IllegalArgumentException.class, () -> seq.dirs().once(name));
        assertFalse(Files.exists(base), "nothing created");
    }

    @Test
    void findFailsOnAnEntryWithTooLargeNumber() throws Exception {
        Files.createFile(this.tmp.resolve("1000000000000000000-x"));
        final Seqdir seq = new Seqdir(this.tmp, 3);
        final IOException ex = assertThrows(IOException.class, () -> seq.files().find("x"));
        assertTrue(ex.getMessage().contains("1000000000000000000-x"), ex.getMessage());
        assertTrue(ex.getMessage().contains("too large"), ex.getMessage());
        assertThrows(IOException.class, () -> seq.dirs().once("n"));
        assertEquals(
            Collections.singletonList("1000000000000000000-x"), Entries.names(this.tmp)
        );
    }

    @Test
    void findOnABaseThatIsAFileFails() throws Exception {
        final Path file = Files.createFile(this.tmp.resolve("plain"));
        assertThrows(IOException.class, () -> new Seqdir(file, 3).dirs().find("x"));
    }

    @Test
    void onceCreatesOnTheFirstCall() throws Exception {
        final Path got = new Seqdir(this.tmp, 3).dirs().once("parse");
        assertEquals(this.tmp.resolve("001-parse"), got);
        assertTrue(Files.isDirectory(got));
        assertTrue(Entries.names(got).isEmpty());
    }

    @Test
    void onceCreatesTheBaseDirectory() throws Exception {
        final Path base = this.tmp.resolve("a").resolve("b");
        final Path got = new Seqdir(base, 2).files().once("x");
        assertEquals(base.resolve("01-x"), got);
        assertTrue(Files.isRegularFile(got));
    }

    @Test
    void onceReturnsTheSamePathEveryTime() throws Exception {
        final Sequence dirs = new Seqdir(this.tmp, 3).dirs();
        final Path first = dirs.once("x");
        assertEquals(first, dirs.once("x"));
        assertEquals(first, new Seqdir(this.tmp, 3).dirs().once("x"));
        assertEquals(first, new Seqdir(this.tmp.toString(), 3).dirs().once("x"));
        assertEquals(first, new Seqdir(this.tmp, 7).dirs().once("x"));
        assertEquals(first, new Seqdir(this.tmp, 1).dirs().find("x").get());
        assertEquals(
            Arrays.asList(Seqdir.LOCK_FILE, "001-x"), Entries.names(this.tmp)
        );
    }

    @Test
    void onceReusesAnEntryThatExistsUnderAnyNumber() throws Exception {
        Files.createDirectory(this.tmp.resolve("17-x"));
        final Sequence dirs = new Seqdir(this.tmp, 3).dirs();
        assertEquals(this.tmp.resolve("17-x"), dirs.once("x"));
        assertEquals(this.tmp.resolve("018-y"), dirs.once("y"));
        assertEquals(this.tmp.resolve("17-x"), dirs.once("x"));
    }

    @Test
    void onceReusesAnEntryThatNextCreated() throws Exception {
        final Sequence dirs = new Seqdir(this.tmp, 3).dirs();
        final Path made = dirs.next("x");
        assertEquals(made, dirs.once("x"));
        assertFalse(Files.exists(this.tmp.resolve("002-x")));
    }

    @Test
    void onceDoesNotReuseAnEntryOfTheOtherKind() throws Exception {
        Files.createFile(this.tmp.resolve("001-x"));
        final Seqdir seq = new Seqdir(this.tmp, 3);
        final Path dir = seq.dirs().once("x");
        assertEquals(this.tmp.resolve("002-x"), dir);
        assertTrue(Files.isDirectory(dir));
        assertEquals(this.tmp.resolve("001-x"), seq.files().once("x"));
        assertEquals(dir, seq.dirs().once("x"));
    }

    @Test
    void onceGivesDifferentNamesDifferentNumbers() throws Exception {
        final Seqdir seq = new Seqdir(this.tmp, 3);
        assertEquals(this.tmp.resolve("001-a"), seq.dirs().once("a"));
        assertEquals(this.tmp.resolve("002-b"), seq.files().once("b"));
        assertEquals(this.tmp.resolve("003-c"), seq.dirs().once("c"));
        assertEquals(this.tmp.resolve("001-a"), seq.dirs().once("a"));
        assertEquals(this.tmp.resolve("004-d"), seq.dirs().next("d"));
    }

    @Test
    void theLockFileStaysAndDoesNotDisturbNext() throws Exception {
        final Seqdir seq = new Seqdir(this.tmp, 3);
        seq.dirs().once("a");
        assertTrue(Files.isRegularFile(this.tmp.resolve(Seqdir.LOCK_FILE)));
        assertEquals(this.tmp.resolve("002-b"), seq.files().next("b"));
        assertEquals(this.tmp.resolve("003-c"), seq.dirs().once("c"));
        assertEquals(this.tmp.resolve("004-d"), seq.dirs().next("d"));
        assertEquals(-1L, Entries.number(Seqdir.LOCK_FILE));
        assertEquals(
            Collections.singletonList(Seqdir.LOCK_FILE), Entries.unfinished(this.tmp)
        );
    }

    @Test
    void theLockFileNameIsNotNumbered() {
        assertFalse(Character.isDigit(Seqdir.LOCK_FILE.charAt(0)));
    }

    @Test
    void onceWaitsForAFileLockHeldInThisJvm() throws Exception {
        Files.createDirectories(this.tmp);
        final Path lockFile = this.tmp.resolve(Seqdir.LOCK_FILE);
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try (FileChannel channel = FileChannel.open(
            lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE
        )) {
            final FileLock held = channel.lock();
            final Future<Path> pending = pool.submit(
                () -> new Seqdir(this.tmp, 3).dirs().once("x")
            );
            Thread.sleep(300L);
            assertFalse(pending.isDone(), "waits while the lock is held");
            held.release();
            assertEquals(this.tmp.resolve("001-x"), pending.get(30L, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void interruptionWhileWaitingForTheFileLockEndsWithInterruptedIoException()
        throws Exception {
        final Path lockFile = Files.createDirectories(this.tmp).resolve(Seqdir.LOCK_FILE);
        try (FileChannel channel = FileChannel.open(
            lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE
        )) {
            final FileLock held = channel.lock();
            this.assertInterrupted();
            held.release();
        }
        assertEquals(this.tmp.resolve("001-x"), new Seqdir(this.tmp, 3).dirs().once("x"));
    }

    @Test
    void interruptionWhileAnotherProcessHoldsTheFileLockEndsWithInterruptedIoException()
        throws Exception {
        final Path lockFile = this.tmp.resolve(Seqdir.LOCK_FILE);
        final Process child = new ProcessBuilder(
            Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"),
            LockMain.class.getName(), lockFile.toString()
        ).redirectErrorStream(true).start();
        try {
            final BufferedReader out = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8)
            );
            assertEquals("locked", out.readLine());
            this.assertInterrupted();
        } finally {
            child.getOutputStream().close();
            assertTrue(child.waitFor(60L, TimeUnit.SECONDS), "the child ended");
        }
        assertEquals(0, child.exitValue());
        assertEquals(this.tmp.resolve("001-x"), new Seqdir(this.tmp, 3).dirs().once("x"));
    }

    @Test
    void anInterruptedThreadStillFindsAnExistingEntryWithoutWaiting() throws Exception {
        final Path made = new Seqdir(this.tmp, 3).dirs().once("x");
        Thread.currentThread().interrupt();
        try {
            assertEquals(made, new Seqdir(this.tmp, 3).dirs().once("x"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /** Starts a thread that calls once and is stuck, interrupts it and checks the outcome. */
    private void assertInterrupted() throws Exception {
        final Throwable[] failure = {null};
        final boolean[] flag = {false};
        final Thread waiter = new Thread(
            () -> {
                try {
                    new Seqdir(this.tmp, 3).dirs().once("x");
                } catch (final IOException | RuntimeException ex) {
                    failure[0] = ex;
                    flag[0] = Thread.currentThread().isInterrupted();
                }
            }
        );
        waiter.start();
        Thread.sleep(300L);
        assertTrue(waiter.isAlive(), "the thread is waiting");
        waiter.interrupt();
        waiter.join(30_000L);
        assertFalse(waiter.isAlive(), "the thread ended");
        assertTrue(failure[0] instanceof InterruptedIOException, String.valueOf(failure[0]));
        assertTrue(flag[0], "the interrupt flag is restored");
        assertFalse(Files.exists(this.tmp.resolve("001-x")), "nothing created");
    }
}
