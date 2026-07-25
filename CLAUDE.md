# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew build            # compile, test, and run both gates below
./gradlew check            # tests + coverage floor + documentation gate
./gradlew test --tests "*.SolrConfigsetDetectorTest"                          # one test class
./gradlew test --tests "*.SolrConfigsetDetectorTest.testSchemaUnderConfDirIsRecognized"  # one test
./gradlew runIde           # launch a sandbox IDE with the plugin installed
./gradlew buildPlugin      # produce the distributable ZIP in build/distributions/
./gradlew verifyPlugin     # IntelliJ Plugin Verifier + plugin structure checks
./gradlew dokkaGenerate    # render API docs to build/dokka/html
./gradlew koverXmlReport   # coverage report at build/reports/kover/report.xml
```

Equivalent IDE run configurations live in `.run/`: Run Plugin, Run Tests, Run Verifications.

## Two build gates that will bite you

**Documentation coverage.** Dokka runs with `reportUndocumented` and `failOnWarning`, and
`dokkaGenerate` is a dependency of `check`. Any public class, function or property in
`src/main/kotlin` without KDoc fails the build, naming the declaration. Adding public API means
documenting it in the same change. Tests are exempt — only the `main` source set is gated.

**Test coverage.** Kover enforces an 80% line floor via `koverVerify`, also bound to `check`.
`SolrBundle` is excluded (platform plumbing with no branches of its own). The floor sits below
actual coverage on purpose, so that landing hard-to-unit-test UI and PSI code does not immediately
block a PR; SonarCloud's new-code gate is what catches gradual erosion.

CI runs these deliberately last, in a single `./gradlew check` step, so that a gate failure still
leaves the coverage report uploaded and the SonarCloud analysis published. Report-producing tasks
run first. Preserve that ordering when adding steps — an artifact upload placed before `check` will
find nothing, because `check` is what generates it.

Package and module overview prose lives in `docs/Module.md`. Note that KDoc reads `[foo]` as a
symbol link, so Markdown reference-style links (`[text][ref]`) do not work there; use inline
`[text](url)`.

## Architecture

An IntelliJ Platform plugin providing Apache Solr tooling, built with the IntelliJ Platform Gradle
plugin against the unified `intellijIdea("2026.2")` artifact (Community and Ultimate merged as of
2025.3).

**The spec is the source of truth for intent**: `specs/0002-solr-intellij-plugin.md` defines a
five-phase program and details Phase 1 (S1–S9) and the documentation deliverables (D1–D9) to
implementation depth. Read it before designing a feature — requirements are referenced by these IDs
in commits and KDoc.

`specs/plans/0002-solr-intellij-plugin-plan.md` is the ordered path to that intent, and it owns
which steps are done. Read it before starting feature work: it records why steps are split the way
they are — S8 provenance is its own step precisely to keep an XML parse off the per-file detection
path — and which steps block which. Do not mirror its step status into this file. Position changes
every step and orientation does not, so a copy here goes stale while the plan stays correct.

`docs/solr-configuration-files.md` is the companion reference: which Solr configuration files are
hand-edited, which are written by an API, and what the plugin covers. Consult it before adding a
feature that *writes* to a configset — S9 makes the plugin API-first, so a write against a mutable
managed schema renders a Schema API request rather than editing the file. Provenance (S8) gates
writes only; read-side features must never consult it.

**What is actually implemented is a small fraction of that.** Today the codebase contains only the
Phase 1 *activation gate*:

- `org.apache.solr.ide.configset` — deciding whether a file belongs to a Solr configset.
  `SolrConfigsetDetector` gates every future feature: a recognized file name
  (`SolrConfigsetFileKind`) plus corroboration, either directory heuristics or a user-marked root
  persisted in `SolrConfigsetSettings`.
- `org.apache.solr.ide` — `SolrBundle`, the localization bundle.

Phase 1 proper (schema completion, cross-file references, rename, inspections, match-capability
hints, quick documentation) is unbuilt. Do not infer status from the API reference; check the spec.

Detection today is name-plus-directory only. S8 adds *schema provenance* — reading `<schemaFactory>`
from the sibling `solrconfig.xml` to tell a hand-authored schema from a Solr-managed one. Keep that
off the per-file detection path: `SolrConfigsetDetector` runs on every file the user opens and its
signals are deliberately cheap and local, whereas parsing a sibling XML file is neither. Resolve
provenance once per configset directory and cache it.

Detection is deliberately heuristic and therefore fallible in both directions, which is why
`SolrConfigsetSettings` exists as an escape hatch — manual configset roots and a master off switch.
When features "don't activate", that override is the answer.

Those settings persist to the shared `solr.xml`, not workspace-local storage, because a marked root
is a fact about the project rather than about one machine. Paths are therefore collapsed through
`PathMacroManager` on write (`$PROJECT_DIR$/core/conf`) and expanded on read. Treat
`state.manualConfigsetRoots` as storage-form only and read `manualRoots` for usable absolute paths.

`plugin.xml` registers the extensionless `managed-schema` (and `managed-schema.xml`) with the XML
file type so configsets parse as XML for the PSI-based features to come.

## Tests

Tests extend `BasePlatformTestCase`, which is JUnit 3-style despite the JUnit 4 dependency: test
methods must be named `testSomething()` and are discovered by that prefix, not by `@Test`. Build
fixtures with `myFixture.addFileToProject(path, content)` — the path shapes the directory structure
the detector's heuristics read, so it is part of the test's meaning rather than incidental.

Anything touching `SolrConfigsetSettings` must extend `SolrConfigsetTestCase` instead.
`BasePlatformTestCase` reuses one light project across test methods *and* test classes, and the
settings are a project-level `PersistentStateComponent`, so state leaks between tests: one test
disabling detection silently changes the starting conditions of every test after it. That base
class resets in `setUp`, which holds even when a preceding test fails partway through.

## Conventions

Commits use conventional-commit subjects (`docs:`, `fix:`, `build:`, `ci:`) and must carry
`Signed-off-by` (`git commit -s`). Commit bodies here carry real weight — for several changes they
are the only record of *why* a constraint exists.
GitHub Actions are pinned to full commit SHAs with a trailing version comment — keep it that way.
Tags are mutable, and CI has `SONAR_TOKEN` in scope; the trailing comment is what lets Dependabot
still propose upgrades. Gradle dependency verification was tried and deliberately removed (the
manual regeneration on every bump was not worth it for a pre-release plugin), so do not read its
absence as an oversight — `19a0e0f` and `e675f26` are what that regeneration actually cost, and are
the only place the reasoning is written down. Both touch a `gradle/verification-metadata.xml` that
no longer exists: read them as the history behind the removal, not as live build guidance.

`README.md` describes the plugin and states its status honestly — the Marketplace badges and
template TODO list are gone, because the plugin is unpublished and the placeholders rendered as
broken links. Its Status section is the authority on what is actually built; keep it truthful when
landing features, since the spec describes intent rather than state.

The build pins `jvmToolchain(21)`. The floor comes from the version-support policy: Solr 10 requires
Java 21, and the reference-data generator reflects over Solr artifacts from every supported line.
Supported lines are those Apache Solr has not declared EOL — currently 10.x and 9.10.x — so a Solr
EOL announcement is a maintenance trigger, not a background event.
