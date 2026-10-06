# seqdir

Creates a file or a directory under the next number-prefixed name in a
directory: `001-foo.txt`, `002-bar.txt`, and so on. Safe for threads and for
separate processes that share the directory.

```java
Seqdir seq = new Seqdir("/tmp/stages", 3);
Path first = seq.files().next("foo.txt");  // /tmp/stages/001-foo.txt, an empty file
Path second = seq.dirs().next("bar");      // /tmp/stages/002-bar, an empty directory
```

## Install

```xml
<dependency>
  <groupId>io.github.sekator778</groupId>
  <artifactId>seqdir</artifactId>
  <version>0.1.0</version>
</dependency>
```

Java 8 or later, no dependencies. The first release, 0.1.0, is on its way to
Maven Central.

## What it guarantees

- `next(name)` returns a path that already exists: an empty file from
  `files()`, an empty directory from `dirs()`.
- No two entries of a directory get the same number, whether the callers are
  threads, objects with different widths, or separate JVMs. Files and
  directories share one numbering.
- The number is the highest one present plus one, padded with zeros to the
  width (1 to 18). An entry counts when its name is digits followed by a dash
  or by nothing; everything else in the directory is ignored.
- Nothing is kept in memory: no static state, no locks, no caches. An object
  holds a path and a width.

## How

A call lists the directory, then creates an empty entry named just the number,
`5`. Creating a name is atomic and fails if the name exists, so one caller
wins. The winner lists the directory once more, in case somebody finished the
same number in between, and then renames `5` to `005-foo.txt`, which is atomic
too. Whoever loses starts over. The loop is bounded and never recursive.

## Limits

- A call lists the directory twice, so its cost grows with the number of
  entries: about 2 ms at 1,000 entries and 10 ms at 10,000, measured on a
  MacBook SSD.
- Numbers are not remembered. Delete the highest entry and its number is given
  out again.
- A process killed in the middle of a call, or a directory that turns
  read-only in the middle of one, can leave an empty entry named just a
  number. It counts as taken and is never reused.
- A program that creates `NNN-name` entries on its own, without this library,
  can collide with it.

## How it is tested

The test suite runs 32 threads at once, six JVMs on one directory, objects of
different widths on one directory, and a deliberately naive implementation that
the same tests must catch producing duplicates. Before the first release the
jar was also driven as a black box on APFS, HFS+, ExFAT and FAT32, by processes
on JDK 8 to 25 at the same time, and with `kill -9` in the middle of calls.

## License

MIT
