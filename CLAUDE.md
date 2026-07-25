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
five-phase program and details Phase 1 (S1–S7) and the documentation deliverables (D1–D9) to
implementation depth. Read it before designing a feature — requirements are referenced by these IDs
in commits and KDoc.

**What is actually implemented is a small fraction of that.** Today the codebase contains only the
Phase 1 *activation gate*:

- `org.apache.solr.ide.configset` — deciding whether a file belongs to a Solr configset.
  `SolrConfigsetDetector` gates every future feature: a recognized file name
  (`SolrConfigsetFileKind`) plus corroboration, either directory heuristics or a user-marked root
  persisted in `SolrConfigsetSettings`.
- `org.apache.solr.ide` — `SolrBundle`, the localization bundle.

Phase 1 proper (schema completion, cross-file references, rename, inspections, match-capability
hints, quick documentation) is unbuilt. Do not infer status from the API reference; check the spec.

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
`Signed-off-by` (`git commit -s`). Commit bodies here carry real weight — they record *why* a
constraint exists, and several (`19a0e0f`, `e675f26`) are the only documentation of build pitfalls.
GitHub Actions are pinned to full commit SHAs with a trailing version comment — keep it that way.
Tags are mutable, and CI has `SONAR_TOKEN` in scope; the trailing comment is what lets Dependabot
still propose upgrades. Gradle dependency verification was tried and deliberately removed (the
manual regeneration on every bump was not worth it for a pre-release plugin), so do not read its
absence as an oversight.

The `README.md` is still largely unmodified IntelliJ Platform Plugin Template boilerplate, including
a template TODO list and `MARKETPLACE_ID` placeholders. Do not treat it as a description of this
plugin.
