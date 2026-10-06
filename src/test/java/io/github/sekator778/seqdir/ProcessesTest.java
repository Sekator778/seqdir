package io.github.sekator778.seqdir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessesTest {

    @TempDir
    Path tmp;

    @Test
    void sixJvmsOfTwoWidthsClaimEighteenHundredNamesInOneDirectory() throws Exception {
        final int jvms = 6;
        final int each = 300;
        final Path shared = this.tmp.resolve("shared");
        final Path logs = Files.createDirectories(this.tmp.resolve("logs"));
        final String java = System.getProperty("java.home")
            + File.separator + "bin" + File.separator + "java";
        final long start = System.currentTimeMillis() + 3000L;
        final List<Process> procs = new ArrayList<>();
        for (int id = 0; id < jvms; ++id) {
            final ProcessBuilder builder = new ProcessBuilder(
                java, "-cp", System.getProperty("java.class.path"),
                ClaimMain.class.getName(),
                shared.toString(), Integer.toString(each), Integer.toString(id),
                Long.toString(start), Integer.toString(id % 2 == 0 ? 2 : 5)
            );
            builder.redirectOutput(logs.resolve("out" + id + ".txt").toFile());
            builder.redirectError(logs.resolve("err" + id + ".txt").toFile());
            procs.add(builder.start());
        }
        for (int id = 0; id < jvms; ++id) {
            final Process proc = procs.get(id);
            if (!proc.waitFor(180, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
            assertEquals(
                0, proc.exitValue(),
                "exit code of JVM " + id + ", stderr: " + new String(
                    Files.readAllBytes(logs.resolve("err" + id + ".txt")), StandardCharsets.UTF_8
                )
            );
        }
        final List<String> all = new ArrayList<>();
        for (int id = 0; id < jvms; ++id) {
            all.addAll(Files.readAllLines(logs.resolve("out" + id + ".txt"), StandardCharsets.UTF_8));
        }
        final int total = jvms * each;
        assertEquals(total, all.size());
        assertEquals(total, new HashSet<>(all).size(), "names are distinct");
        final Set<Long> numbers = new HashSet<>();
        for (final String name : all) {
            numbers.add(Entries.number(name));
        }
        assertEquals(total, numbers.size(), "numbers are distinct");
        for (long expected = 1; expected <= total; ++expected) {
            assertTrue(numbers.contains(expected), "number present: " + expected);
        }
        assertEquals(total, Entries.names(shared).size(), "entries in directory");
        assertEquals(0, Entries.unfinished(shared).size(), "bare claims left");
        for (final String name : all) {
            assertTrue(Harness.emptyEntry(shared.resolve(name)), "empty: " + name);
        }
    }
}
