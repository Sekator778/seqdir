package io.github.sekator778.seqdir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConcurrencyTest {

    @TempDir
    Path tmp;

    private static void verify(final Path dir, final List<Path> got, final int total)
        throws Exception {
        assertEquals(total, got.size());
        assertEquals(total, new HashSet<>(got).size(), "paths are distinct");
        final Set<Long> numbers = new HashSet<>();
        for (final Path path : got) {
            assertTrue(Files.exists(path), "exists: " + path);
            assertTrue(Harness.emptyEntry(path), "empty: " + path);
            final String name = path.getFileName().toString();
            numbers.add(Entries.number(name));
            final boolean dirName = !name.endsWith(".txt");
            assertEquals(dirName, Files.isDirectory(path), "kind of " + name);
        }
        assertEquals(total, numbers.size(), "numbers are distinct");
        for (long expected = 1; expected <= total; ++expected) {
            assertTrue(numbers.contains(expected), "number present: " + expected);
        }
        assertEquals(total, Entries.names(dir).size(), "entries in directory");
        assertEquals(0, Entries.unfinished(dir).size(), "bare claims left");
    }

    @Test
    void thirtyTwoThreadsClaimThreeThousandTwoHundredNames() throws Exception {
        final Path dir = this.tmp.resolve("base");
        final Seqdir seq = new Seqdir(dir, 3);
        final List<Path> got = Harness.run(seq.files(), seq.dirs(), 32, 100);
        ConcurrencyTest.verify(dir, got, 3200);
    }

    private void mixedWidths(final int threads, final int per) throws Exception {
        final Path dir = this.tmp.resolve("base");
        final List<Sequence> files = new ArrayList<>();
        final List<Sequence> dirs = new ArrayList<>();
        for (final int width : new int[] {1, 3, 6}) {
            final Seqdir seq = new Seqdir(dir, width);
            files.add(seq.files());
            dirs.add(seq.dirs());
        }
        final List<Path> got = Harness.run(files, dirs, threads, per);
        assertEquals(threads * per, got.size());
        final Set<Long> numbers = new HashSet<>();
        for (final Path path : got) {
            assertTrue(Files.exists(path), "exists: " + path);
            assertTrue(Harness.emptyEntry(path), "empty: " + path);
            numbers.add(Entries.number(path.getFileName().toString()));
        }
        assertEquals(threads * per, numbers.size(), "numbers are distinct by value");
        for (long expected = 1; expected <= threads * per; ++expected) {
            assertTrue(numbers.contains(expected), "number present: " + expected);
        }
        assertEquals(threads * per, Entries.names(dir).size(), "entries in directory");
        assertEquals(0, Entries.unfinished(dir).size(), "bare claims left");
    }

    @Test
    void threeWidthsOnOneDirectoryTwelveThreadsClaimSevenHundredTwentyNames()
        throws Exception {
        this.mixedWidths(12, 60);
    }

    @RepeatedTest(20)
    void threeWidthsOnOneDirectorySixThreadsClaimOneHundredEightyNames()
        throws Exception {
        this.mixedWidths(6, 30);
    }

    @RepeatedTest(20)
    void eightThreadsClaimFourHundredNames() throws Exception {
        final Path dir = this.tmp.resolve("base");
        final Seqdir seq = new Seqdir(dir, 3);
        final List<Path> got = Harness.run(seq.files(), seq.dirs(), 8, 50);
        ConcurrencyTest.verify(dir, got, 400);
    }

    @Test
    void separateInstancesOnTheSameDirectoryShareNumbering() throws Exception {
        final Path dir = this.tmp.resolve("base");
        final List<Path> got = Harness.run(
            new Seqdir(dir, 3).files(), new Seqdir(dir.toString(), 3).dirs(), 8, 50
        );
        ConcurrencyTest.verify(dir, got, 400);
    }

    @Test
    void namesWithLineFeedsStillGetDistinctNumbers() throws Exception {
        final Path dir = this.tmp.resolve("base");
        final Seqdir seq = new Seqdir(dir, 3);
        assumeTrue(
            SeqdirTest.legalName("t0-0\n"),
            "the platform refuses a line feed in a path; SeqdirTest covers the rejection"
        );
        final List<Path> got = Harness.run(
            Collections.singletonList(seq.files()),
            Collections.singletonList(seq.dirs()), 8, 50, "\n"
        );
        ConcurrencyTest.verify(dir, got, 400);
    }

    @Test
    void naiveImplementationIsCaughtByTheSameHarness() throws Exception {
        int rounds = 0;
        int broken = 0;
        for (; rounds < 50; ++rounds) {
            final Path dir = this.tmp.resolve("naive" + rounds);
            Files.createDirectories(dir);
            final List<Path> got = Harness.run(
                new NaiveSequence(dir, false), new NaiveSequence(dir, true), 16, 40
            );
            final Set<Long> numbers = new HashSet<>();
            for (final Path path : got) {
                numbers.add(Entries.number(path.getFileName().toString()));
            }
            if (numbers.size() != got.size()) {
                ++broken;
            }
        }
        System.out.println("NAIVE rounds=" + rounds + " withDuplicates=" + broken);
        assertTrue(broken > 0, "naive version never produced a duplicate in " + rounds + " rounds");
    }
}
