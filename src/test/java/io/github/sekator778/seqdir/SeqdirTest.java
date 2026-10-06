package io.github.sekator778.seqdir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SeqdirTest {

    @TempDir
    Path tmp;

    @Test
    void firstFileIsEmptyAndSecondFollows() throws Exception {
        final Sequence files = new Seqdir(this.tmp, 3).files();
        final Path first = files.next("foo.txt");
        assertEquals(this.tmp.resolve("001-foo.txt"), first);
        assertTrue(Files.isRegularFile(first));
        assertEquals(0L, Files.size(first));
        assertEquals(this.tmp.resolve("002-bar.txt"), files.next("bar.txt"));
    }

    @Test
    void dirsCreateEmptyDirectories() throws Exception {
        final Path dir = new Seqdir(this.tmp, 3).dirs().next("d");
        assertEquals(this.tmp.resolve("001-d"), dir);
        assertTrue(Files.isDirectory(dir));
        assertTrue(Entries.names(dir).isEmpty());
    }

    @Test
    void filesAndDirsShareNumbering() throws Exception {
        final Seqdir seq = new Seqdir(this.tmp, 3);
        assertEquals(this.tmp.resolve("001-a"), seq.files().next("a"));
        assertEquals(this.tmp.resolve("002-b"), seq.dirs().next("b"));
        assertEquals(this.tmp.resolve("003-c"), seq.files().next("c"));
        assertTrue(Files.isRegularFile(this.tmp.resolve("003-c")));
        assertTrue(Files.isDirectory(this.tmp.resolve("002-b")));
    }

    @Test
    void widthOneGrowsToTwoDigits() throws Exception {
        final Sequence files = new Seqdir(this.tmp, 1).files();
        for (int idx = 1; idx <= 8; ++idx) {
            files.next("x");
        }
        assertEquals(this.tmp.resolve("9-a"), files.next("a"));
        assertEquals(this.tmp.resolve("10-b"), files.next("b"));
        assertEquals(this.tmp.resolve("11-c"), files.next("c"));
    }

    @Test
    void numberLargerThanWidthIsWrittenInFull() throws Exception {
        Files.createFile(this.tmp.resolve("999-x"));
        assertEquals(this.tmp.resolve("1000-y"), new Seqdir(this.tmp, 3).files().next("y"));
    }

    @Test
    void existingEntryRaisesTheNumber() throws Exception {
        Files.createFile(this.tmp.resolve("007-x"));
        assertEquals(this.tmp.resolve("008-y"), new Seqdir(this.tmp, 3).files().next("y"));
    }

    @Test
    void unnumberedNamesAreIgnored() throws Exception {
        for (final String name : Arrays.asList("abc", "x-1", ".hidden", "12abc", "7.txt", "-5", "")) {
            if (!name.isEmpty()) {
                Files.createFile(this.tmp.resolve(name));
            }
        }
        assertEquals(this.tmp.resolve("001-n"), new Seqdir(this.tmp, 3).files().next("n"));
    }

    @Test
    void bareUnpaddedFileIsCounted() throws Exception {
        Files.createFile(this.tmp.resolve("5"));
        assertEquals(this.tmp.resolve("006-n"), new Seqdir(this.tmp, 3).files().next("n"));
    }

    @Test
    void bareUnpaddedDirectoryIsCounted() throws Exception {
        Files.createDirectory(this.tmp.resolve("5"));
        assertEquals(this.tmp.resolve("006-n"), new Seqdir(this.tmp, 3).dirs().next("n"));
    }

    @Test
    void barePaddedFileIsCountedAsNumberFive() throws Exception {
        Files.createFile(this.tmp.resolve("005"));
        assertEquals(this.tmp.resolve("006-n"), new Seqdir(this.tmp, 3).files().next("n"));
    }

    @Test
    void barePaddedDirectoryIsCountedAsNumberFive() throws Exception {
        Files.createDirectory(this.tmp.resolve("005"));
        assertEquals(this.tmp.resolve("006-n"), new Seqdir(this.tmp, 3).dirs().next("n"));
    }

    @Test
    void fileIsCountedWhenAskingForDirs() throws Exception {
        Files.createFile(this.tmp.resolve("003-x"));
        assertEquals(this.tmp.resolve("004-d"), new Seqdir(this.tmp, 3).dirs().next("d"));
    }

    @Test
    void directoryIsCountedWhenAskingForFiles() throws Exception {
        Files.createDirectory(this.tmp.resolve("003-x"));
        assertEquals(this.tmp.resolve("004-f"), new Seqdir(this.tmp, 3).files().next("f"));
    }

    @Test
    void numberWithDifferentPaddingIsCounted() throws Exception {
        Files.createFile(this.tmp.resolve("5-x"));
        assertEquals(this.tmp.resolve("006-y"), new Seqdir(this.tmp, 3).files().next("y"));
    }

    @Test
    void sameNameTwiceGivesTwoEntries() throws Exception {
        final Sequence files = new Seqdir(this.tmp, 3).files();
        assertEquals(this.tmp.resolve("001-same"), files.next("same"));
        assertEquals(this.tmp.resolve("002-same"), files.next("same"));
    }

    @Test
    void nameMayContainDashesAndDots() throws Exception {
        assertEquals(
            this.tmp.resolve("001-a-b.c.d"),
            new Seqdir(this.tmp, 3).files().next("a-b.c.d")
        );
    }

    @Test
    void baseDirectoryIsCreatedWhenAbsent() throws Exception {
        final Path base = this.tmp.resolve("a").resolve("b");
        final Path got = new Seqdir(base, 3).files().next("x");
        assertEquals(base.resolve("001-x"), got);
        assertTrue(Files.isRegularFile(got));
    }

    @Test
    void baseThatIsARegularFileFails() throws Exception {
        final Path file = Files.createFile(this.tmp.resolve("plain"));
        final IOException ex = assertThrows(
            IOException.class, () -> new Seqdir(file, 3).files().next("x")
        );
        assertTrue(ex.getMessage().contains("not a directory"), ex.getMessage());
        assertTrue(ex.getMessage().contains("plain"), ex.getMessage());
    }

    @Test
    void baseWhoseParentIsARegularFileFails() throws Exception {
        final Path file = Files.createFile(this.tmp.resolve("plain"));
        assertThrows(IOException.class, () -> new Seqdir(file.resolve("sub"), 3).dirs().next("x"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void invalidWidthIsRejectedByBothConstructors(final int width) {
        assertThrows(IllegalArgumentException.class, () -> new Seqdir(this.tmp, width));
        assertThrows(IllegalArgumentException.class, () -> new Seqdir(this.tmp.toString(), width));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {".", "..", "a/b", "/a", "a/", "a\\b", "\\", "/"})
    void invalidNameIsRejected(final String name) throws Exception {
        final Seqdir seq = new Seqdir(this.tmp, 3);
        assertThrows(IllegalArgumentException.class, () -> seq.files().next(name));
        assertThrows(IllegalArgumentException.class, () -> seq.dirs().next(name));
        assertTrue(Entries.names(this.tmp).isEmpty(), "nothing created");
    }

    @Test
    void invalidNameDoesNotCreateTheBaseDirectory() {
        final Path base = this.tmp.resolve("later");
        assertThrows(IllegalArgumentException.class, () -> new Seqdir(base, 3).files().next(".."));
        assertFalse(Files.exists(base));
    }

    @Test
    void nameThatCannotBeAPathIsRejectedAndLeavesNothingBehind() throws Exception {
        final String bad = "a\u0000b";
        final Seqdir seq = new Seqdir(this.tmp, 3);
        final IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class, () -> seq.files().next(bad)
        );
        assertTrue(ex.getCause() instanceof InvalidPathException, String.valueOf(ex.getCause()));
        assertThrows(IllegalArgumentException.class, () -> seq.dirs().next(bad));
        assertTrue(Entries.names(this.tmp).isEmpty(), "nothing left in existing base");
        assertEquals(this.tmp.resolve("001-ok"), seq.files().next("ok"));
    }

    @Test
    void nameThatCannotBeAPathDoesNotCreateTheBaseDirectory() {
        final Path base = this.tmp.resolve("later");
        assertThrows(
            IllegalArgumentException.class,
            () -> new Seqdir(base, 3).files().next("a\u0000b")
        );
        assertFalse(Files.exists(base));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029"})
    void lineTerminatorInNameDoesNotHideTheNumber(final String term) throws Exception {
        final Seqdir seq = new Seqdir(this.tmp, 3);
        final String name = "a" + term + "b";
        final Path first = seq.files().next(name);
        assertEquals(this.tmp.resolve("001-" + name), first);
        Files.write(first, new byte[] {1, 2, 3, 4});
        final Path second = seq.files().next(name);
        assertEquals(this.tmp.resolve("002-" + name), second);
        assertEquals(4L, Files.size(first));
        assertEquals(0L, Files.size(second));
        final Path dir = seq.dirs().next(name);
        assertEquals(this.tmp.resolve("003-" + name), dir);
        assertTrue(Files.isDirectory(dir));
        assertEquals(this.tmp.resolve("004-x"), seq.files().next("x"));
        assertEquals(this.tmp.resolve("005-y"), seq.dirs().next("y"));
        assertEquals(4L, Files.size(first));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029"})
    void directoryWithLineTerminatorKeepsItsNumberForFiles(final String term) throws Exception {
        final Seqdir seq = new Seqdir(this.tmp, 3);
        final String name = "d" + term;
        assertEquals(this.tmp.resolve("001-" + name), seq.dirs().next(name));
        assertEquals(this.tmp.resolve("002-f"), seq.files().next("f"));
    }

    @Test
    void widthEighteenWorks() throws Exception {
        final Sequence files = new Seqdir(this.tmp, 18).files();
        assertEquals(this.tmp.resolve("000000000000000001-a"), files.next("a"));
        assertEquals(this.tmp.resolve("000000000000000002-a"), files.next("a"));
    }

    @ParameterizedTest
    @ValueSource(ints = {19, 20, 25, Integer.MAX_VALUE})
    void tooLargeWidthIsRejectedImmediately(final int width) {
        final long start = System.nanoTime();
        final IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class, () -> new Seqdir(this.tmp, width)
        );
        assertEquals("width must be between 1 and 18, got " + width, ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new Seqdir(this.tmp.toString(), width));
        assertTrue(System.nanoTime() - start < 500_000_000L, "returns at once");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1000000000000000000-x", "9223372036854775806-x", "1234567890123456789"})
    void entryWithTooLargeNumberFailsAndCreatesNothing(final String entry) throws Exception {
        Files.createFile(this.tmp.resolve(entry));
        final Seqdir seq = new Seqdir(this.tmp, 3);
        final IOException ex = assertThrows(IOException.class, () -> seq.files().next("n"));
        assertTrue(ex.getMessage().contains(entry), ex.getMessage());
        assertTrue(ex.getMessage().contains("too large"), ex.getMessage());
        assertThrows(IOException.class, () -> seq.dirs().next("n"));
        assertEquals(Collections.singletonList(entry), Entries.names(this.tmp));
    }

    @Test
    void leadingZerosDoNotCountTowardsTheLimit() throws Exception {
        Files.createFile(this.tmp.resolve("0000000000000000000000005-x"));
        assertEquals(this.tmp.resolve("006-n"), new Seqdir(this.tmp, 3).files().next("n"));
    }

    @Test
    void highestNumberMeansTheSequenceIsExhausted() throws Exception {
        Files.createFile(this.tmp.resolve("999999999999999999-x"));
        final IOException ex = assertThrows(
            IOException.class, () -> new Seqdir(this.tmp, 3).files().next("n")
        );
        assertTrue(ex.getMessage().contains("exhausted"), ex.getMessage());
        assertEquals(Collections.singletonList("999999999999999999-x"), Entries.names(this.tmp));
    }

    @Test
    void lastNumberBeforeTheLimitCanStillBeTaken() throws Exception {
        Files.createFile(this.tmp.resolve("999999999999999998-x"));
        assertEquals(
            this.tmp.resolve("999999999999999999-n"),
            new Seqdir(this.tmp, 3).files().next("n")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0663-x", "\uFF15-x", "\u096B", "\u0665", "\uFF15"})
    void nonAsciiDigitsAreNotNumbers(final String entry) throws Exception {
        Files.createFile(this.tmp.resolve(entry));
        assertEquals(this.tmp.resolve("001-n"), new Seqdir(this.tmp, 3).files().next("n"));
    }

    @Test
    void baseThatIsASymbolicLinkToADirectoryWorks() throws Exception {
        final Path real = Files.createDirectory(this.tmp.resolve("real"));
        final Path link = this.tmp.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (final IOException | UnsupportedOperationException ex) {
            assumeTrue(false, "symbolic links are not available: " + ex);
        }
        final Path got = new Seqdir(link, 3).files().next("x");
        assertEquals(link.resolve("001-x"), got);
        assertTrue(Files.isRegularFile(real.resolve("001-x")));
    }

    @Test
    void baseThatIsADanglingSymbolicLinkFailsAndCreatesNothing() throws Exception {
        final Path link = this.tmp.resolve("dangling");
        try {
            Files.createSymbolicLink(link, this.tmp.resolve("missing"));
        } catch (final IOException | UnsupportedOperationException ex) {
            assumeTrue(false, "symbolic links are not available: " + ex);
        }
        final IOException ex = assertThrows(
            IOException.class, () -> new Seqdir(link, 3).files().next("x")
        );
        assertTrue(ex.getMessage().contains("not a directory"), ex.getMessage());
        assertFalse(Files.exists(this.tmp.resolve("missing")));
        assertEquals(Collections.singletonList("dangling"), Entries.names(this.tmp));
    }

    @Test
    void twoObjectsOnTheSameDirectoryInterleave() throws Exception {
        final Seqdir one = new Seqdir(this.tmp, 3);
        final Seqdir two = new Seqdir(this.tmp, 3);
        assertEquals(this.tmp.resolve("001-a"), one.files().next("a"));
        assertEquals(this.tmp.resolve("002-b"), two.dirs().next("b"));
        assertEquals(this.tmp.resolve("003-c"), one.dirs().next("c"));
        assertEquals(this.tmp.resolve("004-d"), two.files().next("d"));
    }

    @Test
    void stringAndPathConstructorsAgree() throws Exception {
        final Path one = this.tmp.resolve("one");
        final Path two = this.tmp.resolve("two");
        final Path viaString = new Seqdir(one.toString(), 4).files().next("x");
        final Path viaPath = new Seqdir(two, 4).files().next("x");
        assertEquals("0001-x", viaString.getFileName().toString());
        assertEquals(viaString.getFileName(), viaPath.getFileName());
        assertEquals(one, viaString.getParent());
    }

    @Test
    void tooLongNameFailsWithIoExceptionAndLeavesNothingBehind() throws Exception {
        final char[] chars = new char[2000];
        Arrays.fill(chars, 'a');
        final Sequence files = new Seqdir(this.tmp, 3).files();
        assertThrows(IOException.class, () -> files.next(new String(chars)));
        assertEquals(Collections.emptyList(), Entries.names(this.tmp));
        assertEquals(this.tmp.resolve("001-ok"), files.next("ok"));
    }

    @Test
    void readOnlyDirectoryFailsWithIoException() throws Exception {
        assumeTrue(this.tmp.getFileSystem().supportedFileAttributeViews().contains("posix"));
        final Path ro = Files.createDirectory(this.tmp.resolve("ro"));
        Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeTrue(!Files.isWritable(ro), "running with rights that ignore permissions");
            assertThrows(IOException.class, () -> new Seqdir(ro, 3).files().next("x"));
            assertDoesNotThrow(() -> Entries.names(ro));
            assertTrue(Entries.names(ro).isEmpty());
        } finally {
            Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }
}
