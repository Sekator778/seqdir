# seqdir

Creates a file or a directory under the next number-prefixed name in a
directory: `001-foo.txt`, `002-bar.txt`, and so on. Safe for threads and for
separate processes that share the directory.

```java
Seqdir seq = new Seqdir("/tmp/stages", 3);
Path first = seq.files().next("foo.txt");  // /tmp/stages/001-foo.txt, an empty file
Path second = seq.dirs().next("bar");      // /tmp/stages/002-bar, an empty directory
```

When the same name is asked for again and again, use `once`: it returns the
entry that already has that name, or creates it, and gives every caller, in any
thread and process, the same one.

```java
Path parse = seq.dirs().once("parse");     // /tmp/stages/003-parse, created on the first call
Path again = seq.dirs().once("parse");     // the same path, nothing new is created
```

`find(name)` is the lookup alone: it returns an `Optional<Path>` and creates
nothing, not even the base directory.

## Install

```xml
<dependency>
  <groupId>io.github.sekator778</groupId>
  <artifactId>seqdir</artifactId>
  <version>0.2.0</version>
</dependency>
```

Java 8 or later, no dependencies.

## What it guarantees

- `next(name)` returns a path that already exists: an empty file from
  `files()`, an empty directory from `dirs()`.
- No two named entries of a directory get the same number, whether the callers
  are threads, objects with different widths, or separate JVMs. Files and
  directories share one numbering.
- The number is the highest one present plus one, padded with zeros to the
  width (1 to 18). An entry counts when its name is digits followed by a dash
  or by nothing; everything else in the directory is ignored.
- Nothing is kept in memory: no static state, no caches. An object holds a
  path and a width. `next` and `find` take no locks.
- `once` takes two locks, and only when it has to create: a monitor inside the
  JVM, shared by every copy of the library in it, and a file lock on
  `.seqdir.lock` in the directory, which stays there.
- Names are compared after Unicode normalisation to NFC, so `e` plus a
  combining accent and the single accented letter are one name. When `next` has
  made several entries with a name, `once` returns the one with the lowest
  number.

## How

`next` lists the directory, then creates an empty entry named just the number,
`5`. Creating a name is atomic and fails if the name exists, so one caller
wins. The winner lists the directory once more, in case somebody finished the
same number in between, and then renames `5` to `005-foo.txt`, which is atomic
too. Whoever loses starts over. The loop is bounded and never recursive.

`once` looks the name up first. Only when it is missing does it take the
locks, look again, and run the same steps. The monitor is needed because on
POSIX systems closing any channel on a file drops every lock the process holds
on it, so two channels on the lock file must never be open at once in a JVM.

## Limits

- `next` lists the directory twice, so its cost grows with the number of
  entries: about 2 ms at 1,000 entries and 10 ms at 10,000, measured on a
  MacBook SSD. `find`, and `once` for a name that exists, list it once.
- Numbers are not remembered. Delete the highest entry and its number is given
  out again.
- A process killed in the middle of a call, or a directory that turns
  read-only in the middle of one, can leave an empty entry named just a
  number. It counts as taken and is never reused.
- The lock file `.seqdir.lock` must stay while the directory is in use. If it
  is deleted or replaced while callers are inside `once`, they are no longer
  excluded from each other and a name can get two entries. A lock file that
  disappears between calls, or while a caller waits, is noticed and created
  again. Deleting the whole directory is fine.
- `once` needs a file system that can lock files, and every process that uses
  it on a directory must be able to write `.seqdir.lock`, which gets the
  permissions of the process that creates it. `once` waits for the lock with no
  timeout; a thread whose interrupt flag is already set gets an
  `InterruptedIOException` when `once` has to create.
- A program that creates `NNN-name` entries on its own, without this library,
  can collide with it. So can `next`, if it creates a name that `once` is asked
  for: `once` promises one entry per name only among its own callers.

## How it is tested

The test suite runs 32 threads at once, six JVMs on one directory, objects of
different widths on one directory, and a deliberately naive implementation that
the same tests must catch producing duplicates. For `once`: many threads and
six JVMs asking for the same new names, six JVMs of eight threads each, two
copies of the library loaded through separate class loaders next to two other
JVMs, and a child process that holds the file lock while a waiting thread is
interrupted. Before each release the jar is also driven as a black box on APFS,
HFS+, ExFAT and FAT32, by processes on JDK 8 to 25 at the same time, and with
`kill -9` in the middle of calls; before 0.2.0 also on Linux in a container.
CI runs the suite on Linux, macOS and Windows with JDK 11, 17, 21 and 25.

## Changes in 0.2.0

New: `find` and `once` on `Sequence`. Code that implements `Sequence` itself
must add them.

## License

MIT
