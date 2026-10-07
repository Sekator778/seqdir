package io.github.sekator778.seqdir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Names that look alike, a lock file that changes or is not a file, and entries that cannot be inspected. */
class OnceEdgeTest {

    private static final String COMPOSED = "café";

    private static final String DECOMPOSED = "café";

    @TempDir
    Path tmp;

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /** The entries of the directory that are not the lock file. */
    private static List<String> entries(final Path dir) throws IOException {
        final List<String> out = new ArrayList<>(Entries.names(dir));
        out.remove(Seqdir.LOCK_FILE);
        return out;
    }

    @Test
    void anEntryCreatedComposedIsFoundDecomposed() throws Exception {
        final Path made = Files.createDirectory(this.tmp.resolve("001-" + COMPOSED));
        final Optional<Path> found = new Seqdir(this.tmp, 3).dirs().find(DECOMPOSED);
        assertTrue(found.isPresent());
        assertEquals(made.getFileName().toString(), found.get().getFileName().toString());
    }

    @Test
    void anEntryCreatedDecomposedIsFoundComposed() throws Exception {
        final Path made = Files.createDirectory(this.tmp.resolve("001-" + DECOMPOSED));
        final Optional<Path> found = new Seqdir(this.tmp, 3).dirs().find(COMPOSED);
        assertTrue(found.isPresent());
        assertEquals(made.getFileName().toString(), found.get().getFileName().toString());
    }

    @Test
    void onceCalledThreeTimesWithTheDecomposedNameEndsWithOneEntry() throws Exception {
        final Sequence dirs = new Seqdir(this.tmp, 3).dirs();
        final Path first = dirs.once(DECOMPOSED);
        assertEquals(first, dirs.once(DECOMPOSED));
        assertEquals(first, dirs.once(DECOMPOSED));
        assertEquals(first, dirs.once(COMPOSED));
        assertEquals(1, OnceEdgeTest.entries(this.tmp).size(), String.valueOf(Entries.names(this.tmp)));
    }

    @Test
    void namesThatDifferInAnotherWayAreNotMatches() throws Exception {
        Files.createDirectory(this.tmp.resolve("001-" + COMPOSED));
        final Sequence dirs = new Seqdir(this.tmp, 3).dirs();
        assertFalse(dirs.find("CAFÉ").isPresent(), "case differs");
        assertFalse(dirs.find(DECOMPOSED + "́").isPresent(), "an extra combining mark");
        assertFalse(dirs.find("cafe").isPresent(), "no accent");
        assertFalse(dirs.find(COMPOSED + "x").isPresent(), "a suffix");
        assertTrue(dirs.find(COMPOSED).isPresent());
    }

    @Test
    void asciiNamesStillMatchExactly() throws Exception {
        Files.createDirectory(this.tmp.resolve("001-abc"));
        final Sequence dirs = new Seqdir(this.tmp, 3).dirs();
        assertTrue(dirs.find("abc").isPresent());
        assertFalse(dirs.find("ABC").isPresent());
        assertFalse(dirs.find("ab").isPresent());
    }

    /** Starts a child that holds the lock file and returns it. */
    private Process holder(final Path lockFile) throws Exception {
        final Process child = new ProcessBuilder(
            Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"),
            LockMain.class.getName(), lockFile.toString()
        ).redirectErrorStream(true).start();
        final BufferedReader out = new BufferedReader(
            new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8)
        );
        assertEquals("locked", out.readLine());
        return child;
    }

    private void changedWhileWaiting(final boolean recreate) throws Exception {
        assumeFalse(OnceEdgeTest.windows(), "Windows does not delete a file that is locked");
        final Path lockFile = this.tmp.resolve(Seqdir.LOCK_FILE);
        final Process child = this.holder(lockFile);
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            final Future<Path> waiting = pool.submit(
                () -> new Seqdir(this.tmp, 3).dirs().once("x")
            );
            Thread.sleep(500L);
            assertFalse(waiting.isDone(), "waits while the child holds the lock");
            Files.delete(lockFile);
            if (recreate) {
                Files.createFile(lockFile);
            }
            child.getOutputStream().close();
            assertTrue(child.waitFor(60L, TimeUnit.SECONDS), "the child ended");
            assertEquals(this.tmp.resolve("001-x"), waiting.get(60L, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            child.destroy();
        }
        assertTrue(Files.isRegularFile(lockFile), "the lock file is there again");
        assertEquals(Collections.singletonList("001-x"), OnceEdgeTest.entries(this.tmp));
        assertEquals(this.tmp.resolve("001-x"), new Seqdir(this.tmp, 3).dirs().once("x"));
    }

    @Test
    void aLockFileDeletedWhileAThreadWaitsIsCreatedAgain() throws Exception {
        this.changedWhileWaiting(false);
    }

    @Test
    void aLockFileDeletedAndRecreatedWhileAThreadWaitsGivesTheSameResult() throws Exception {
        this.changedWhileWaiting(true);
    }

    private void assertRejected(final Path lockFile) throws Exception {
        final IOException ex = assertThrows(
            IOException.class, () -> new Seqdir(this.tmp, 3).dirs().once("x")
        );
        assertTrue(ex.getMessage().contains(Seqdir.LOCK_FILE), ex.getMessage());
        assertFalse(Files.exists(this.tmp.resolve("001-x")), "nothing created");
    }

    @Test
    void aLockPathThatIsADirectoryFails() throws Exception {
        final Path lockFile = Files.createDirectory(this.tmp.resolve(Seqdir.LOCK_FILE));
        this.assertRejected(lockFile);
        assertTrue(Files.isDirectory(lockFile));
    }

    @Test
    void aLockPathThatIsASymbolicLinkToAFileFails() throws Exception {
        final Path real = Files.createFile(this.tmp.resolve("real"));
        final Path link = this.tmp.resolve(Seqdir.LOCK_FILE);
        OnceEdgeTest.link(link, real);
        this.assertRejected(link);
        assertEquals(0L, Files.size(real));
    }

    @Test
    void aLockPathThatIsADanglingSymbolicLinkFailsAndCreatesNothingOutside()
        throws Exception {
        final Path outside = Files.createDirectory(this.tmp.resolve("outside"));
        final Path base = Files.createDirectory(this.tmp.resolve("base"));
        final Path target = outside.resolve("created");
        OnceEdgeTest.link(base.resolve(Seqdir.LOCK_FILE), target);
        assertThrows(IOException.class, () -> new Seqdir(base, 3).dirs().once("x"));
        assertFalse(Files.exists(target), "nothing created outside the directory");
        assertTrue(Entries.names(outside).isEmpty());
    }

    @Test
    void aLockPathThatIsAPipeFailsInsteadOfHanging() throws Exception {
        assumeFalse(OnceEdgeTest.windows());
        final Path fifo = this.tmp.resolve(Seqdir.LOCK_FILE);
        boolean made = false;
        try {
            made = new ProcessBuilder("mkfifo", fifo.toString())
                .redirectErrorStream(true).start().waitFor() == 0;
        } catch (final IOException ex) {
            made = false;
        }
        assumeTrue(made && Files.exists(fifo), "mkfifo is not available");
        assertTimeoutPreemptively(Duration.ofSeconds(20L), () -> this.assertRejected(fifo));
    }

    private static void link(final Path link, final Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (final IOException | UnsupportedOperationException ex) {
            assumeTrue(false, "symbolic links are not available: " + ex);
        }
    }

    @Test
    void findDoesNotHideAnEntryItCannotInspect() throws Exception {
        assumeTrue(this.tmp.getFileSystem().supportedFileAttributeViews().contains("posix"));
        final Path base = Files.createDirectory(this.tmp.resolve("base"));
        Files.createDirectory(base.resolve("001-x"));
        try {
            Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rw-------"));
            assumeFalse(
                Files.exists(base.resolve("001-x")),
                "permissions do not bite for this user"
            );
            assertThrows(IOException.class, () -> new Seqdir(base, 3).dirs().find("x"));
        } finally {
            Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwx------"));
        }
        assertEquals(
            Optional.of(base.resolve("001-x")), new Seqdir(base, 3).dirs().find("x")
        );
    }

    @Test
    void findTreatsADanglingSymbolicLinkAsNoMatch() throws Exception {
        OnceEdgeTest.link(this.tmp.resolve("001-x"), this.tmp.resolve("missing"));
        assertFalse(new Seqdir(this.tmp, 3).dirs().find("x").isPresent());
        assertFalse(new Seqdir(this.tmp, 3).files().find("x").isPresent());
        assertEquals(Collections.singletonList("001-x"), Entries.names(this.tmp));
    }
}
