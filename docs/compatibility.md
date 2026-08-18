# Compatibility

What this plugin runs in, what it understands, and how each of those is decided.

**This page is written from the build rather than beside it.** Every version below is declared in one
place in `build.gradle.kts` or `gradle.properties`, named in the table's last column, and a test
asserts the two agree — a matrix promising something the build does not is worse than no matrix,
because it is believed.

## Plugin 0.1.0

| What | Supported | Declared in |
|---|---|---|
| IDE | **IntelliJ IDEA 2026.2 and later** | `intellijIdea("2026.2")` |
| Verified against | IntelliJ IDEA `IU-262.8665.258` | `verifiedIdeBuilds` |
| Apache Solr | **9.10.1** and **10.0.0** | `supportedSolrLines` |
| Build toolchain | JDK 21 | `jvmToolchain(21)` |

### IntelliJ IDEA only, and that is a decision rather than an omission

The plugin declares a hard dependency on `com.intellij.modules.java`, because resolving the class a
`class` attribute names goes through Java PSI. IDEA has been a single unified distribution since
2025.3 and bundles Java, so the dependency is satisfied wherever the plugin runs — but it does mean
the plugin does not install in PyCharm, WebStorm or the other IDEs.

An earlier revision took that dependency *optionally*, so the plugin would load in an IDE without Java
and simply lack class navigation. Nothing verified that arrangement and nothing needed to; it was
removed rather than left as a claim.

### "2026.2 and later" is a claim about the past, not the future

The descriptor sets `since-build="262"` with **no upper bound**, so the plugin declares itself
compatible with every IDE build from 2026.2 onward. Nothing can verify a build that has not shipped:
the Plugin Verifier runs against `IU-262.8665.258` in CI on every pull request, and that is the extent
of what "verified" means here. A later IDE that breaks the plugin will do so without this matrix
changing, which is what the open upper bound trades away for not having to ship a release per platform
bump.

### The two Solr lines are the ones Apache still maintains

The policy is upstream's rather than this project's: the plugin supports the release lines Apache Solr
has not declared end-of-life, and drops a line in the release after Solr declares it. The exact
releases matter because the plugin's class, parameter and element catalogs are **generated from those
artifacts** — a fact shown in a popup came from the jar named here, not from a table someone typed.

Both are exercised on every build: the catalogs are generated per line, and every inspection runs over
the `_default` and `sample_techproducts_configs` configsets **from both lines** and must report
nothing.

### What a server's version does *not* affect

Nothing here yet. Reading a version from a connected server belongs to the Server track, which is not
in 0.1.0 — see [the release notes](../CHANGELOG.md) for what this release does and does not contain.

## Changing any of this

Adding or dropping a Solr line is one edit to `supportedSolrLines`; the catalogs regenerate and the
tests that pin them will name whatever else needs to follow. Moving to a newer platform is one edit to
`intellijIdea(...)` and one to `verifiedIdeBuilds`, and both belong in the same commit — the second is
what the Verifier checks, and a matrix promising a build the Verifier never checked is the failure
this page exists to prevent.
