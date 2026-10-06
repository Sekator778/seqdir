# Releasing

1. Set `<version>` in `pom.xml` to `X.Y.Z`, commit.
2. Tag the commit `vX.Y.Z` and push the tag.
3. The `release` workflow runs `mvn -B -P release deploy`, signs with the GPG key from the secrets and publishes to Maven Central.
4. Check that `io.github.sekator778:seqdir:X.Y.Z` is visible on Maven Central.
5. Set `<version>` in `pom.xml` to the next snapshot, for example `X.Y.(Z+1)-SNAPSHOT`, and commit.
