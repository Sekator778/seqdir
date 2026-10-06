# Releasing

Releases are cut from the maintainer's machine.

1. Set `<version>` in `pom.xml` to `X.Y.Z`, commit as `Release X.Y.Z`.
2. Run `mvn -P release deploy`. It signs the artifacts with the maintainer's GPG key and publishes them to Maven Central.
3. Check that `io.github.sekator778:seqdir:X.Y.Z` is on Maven Central.
4. Tag the commit `vX.Y.Z`, push the tag and write the GitHub release.
5. Set `<version>` in `pom.xml` to the next snapshot, commit.
