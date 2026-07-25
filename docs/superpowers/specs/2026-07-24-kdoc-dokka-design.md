# KDoc + Dokka: API documentation and a doc-coverage gate

Date: 2026-07-24
Status: Approved

## Problem

The plugin's Kotlin sources are partially documented. Every file carries
class-level KDoc explaining intent, but member-level documentation is absent:
`isConfigsetFile`, `addManualRoot`, `removeManualRoot`, `setDetectionEnabled`,
`SolrBundle.message` and others have no KDoc at all, and no existing comment uses
the `@param`/`@return`/`@see` tags that render as structured sections.

Two consequences. First, quick documentation (F1 / Ctrl-Q) on the plugin's own
symbols shows nothing while developing it. Second, nothing prevents the gap from
widening — Phase 1 adds reference providers, inspections, intention actions and a
documentation provider (S1–S7 in `specs/0002-solr-intellij-plugin.md`), which is
where undocumented public surface would accumulate fastest.

The spec already commits to documentation deliverables D6 (contributor guide) and
D7 (architecture doc). This work does not satisfy either — both are prose
deliverables — but it serves the same contributor persona and gives D7 a starting
point.

## Goals

- Every public declaration in `src/main/kotlin` carries KDoc.
- Undocumented public declarations fail the build, so the property holds for all
  future changes rather than decaying after this PR merges.
- Generated HTML API docs are browsable locally and downloadable from any PR.

## Non-goals

- **Publishing docs to GitHub Pages.** IntelliJ Platform plugins ship as a ZIP
  installed into an IDE, not as a dependency compiled against, so there is no
  third-party API surface to browse. Comparable plugins do not host KDoc sites,
  and the official JetBrains plugin template ships no Dokka config. Worth
  revisiting if the plugin ever exposes an extension point for other plugins.
- **Documenting the test source set.** `reportUndocumented` applies to `main`
  only. `@param` tags on `testLoneSchemaWithoutEvidenceIsNotRecognized` would be
  noise.
- **Kotlin explicit API mode.** `explicitApi()` enforces explicit visibility and
  return types, not documentation. Orthogonal concern, and mostly aimed at
  libraries.

## Design

### 1. Build wiring

Dokka 2.2.0 (current stable; the DGP v2 plugin, which supports Gradle's
configuration cache — required here, since `org.gradle.configuration-cache=true`
is set in `gradle.properties`).

Version declared in `settings.gradle.kts` `pluginManagement`, matching how
`org.jetbrains.kotlin.jvm` and `org.jetbrains.changelog` are already pinned
there. Applied in `build.gradle.kts`:

```kotlin
dokka {
    moduleName.set("Solr IntelliJ Plugin")

    dokkaPublications.html {
        failOnWarning.set(true)
    }

    dokkaSourceSets.main {
        reportUndocumented.set(true)
        includes.from("docs/Module.md")
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/adityamparikh/solr-intellij-plugin/tree/main/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
}

tasks.check { dependsOn(tasks.dokkaGenerate) }
```

`reportUndocumented` emits a warning per undocumented public declaration;
`failOnWarning` turns those warnings into a failed `dokkaGenerate`. Wiring
`dokkaGenerate` into `check` means the gate fires on `./gradlew build` locally
and in the existing CI `test` job, which already runs `./gradlew check` — no new
workflow, no new tooling.

`sourceLink` makes every symbol in the generated HTML deep-link to its exact line
on GitHub. This is most of Dokka's practical value over reading the source
directly.

### 2. Dependency verification (superseded)

This section originally planned for `gradle/verification-metadata.xml`, which
pinned a SHA-256 for every resolved component. Adding Dokka introduced a new
dependency subtree with no recorded checksums, so the metadata had to be
regenerated — with `--refresh-dependencies`, because a write pass against a warm
Gradle cache never re-resolves already-cached artifacts and so records no
checksum for them, producing metadata that passes locally and fails on a clean
runner (commit `19a0e0f`).

That regeneration was done and verified green on a cold CI runner. It has since
been dropped: dependency verification was removed from the project entirely,
because the manual regeneration required on every dependency change outweighed
its value for a pre-release plugin. See the commit removing
`gradle/verification-metadata.xml`.

GitHub Actions SHA pinning is unaffected and remains in place — a separate
mechanism with no recurring maintenance cost.

### 3. Module and package documentation

`docs/Module.md`, fed to Dokka via `includes`, provides the module landing page
and per-package headers. Content is sourced from
`specs/0002-solr-intellij-plugin.md`:

- What the plugin is: the three disconnected surfaces Solr developers work
  across (configset XML, ad-hoc queries, SolrJ client code) and the silent
  runtime failures produced at their boundaries.
- The phased program, and Phase 1's scope — pure static analysis of configset
  files, no Solr connection required.
- **What is implemented today** (configset detection only) versus what Phase 1
  targets (S1–S7). Without this distinction the docs describe a plugin that does
  not exist yet.
- Per-package sections for `org.apache.solr.ide` (localization) and
  `org.apache.solr.ide.configset` (the detection gate).

It cross-references the spec rather than restating it, so the two do not drift.

### 4. KDoc coverage

Every public declaration in `src/main/kotlin`:

| File | Declarations needing KDoc |
|---|---|
| `SolrConfigsetDetector.kt` | `isConfigsetFile` (both overloads) |
| `SolrConfigsetSettings.kt` | `State`, `isDetectionEnabled`, `setDetectionEnabled`, `addManualRoot`, `removeManualRoot`, `getInstance` |
| `SolrConfigsetFiles.kt` | `SCHEMA`, `SOLR_CONFIG`, `fileNames` |
| `SolrBundle.kt` | `message`, `messagePointer` |

Existing class-level KDoc is extended with tags, not rewritten. The *why* it
already captures — why a lone `schema.xml` is rejected, why the manual override
exists — is the valuable part, and restating signatures in prose would lose it.

Where a declaration corresponds to a user-visible behavior named in the spec, the
KDoc says so. `SolrConfigsetSettings` is the code-level answer to D5
("why features didn't activate, and the manual override"), so its documentation
links the API to the symptom a user reports, not just to the field it sets.

### 5. CI

One `upload-artifact` step added to the existing `test` job in `build.yml`,
publishing `build/dokka/html`. Pinned to a full commit SHA, matching the
convention established in `152e0b7`. The `check` invocation in that job already
runs the gate.

## Testing strategy

- `./gradlew build` passes.
- **The gate is observed failing.** A public function without KDoc is added
  temporarily, `check` is run, the failure is confirmed to name that declaration,
  and the function is removed. An enforcement mechanism nobody has watched fail
  is not known to work.
- Generated HTML is inspected for the module overview and working source links.
- CI on the PR provides the cold-cache dependency-verification proof.

## Delivery

Two pull requests:

| PR | Branch | Contents |
|---|---|---|
| 1 | `docs/kdoc-dokka` | Dokka wiring, doc gate, KDoc, `docs/Module.md`, CI artifact step |
| 2 | branched from PR 1 head | `/init`-generated `CLAUDE.md` |

PR 2 branches from PR 1 rather than `main` so `/init` observes the Dokka setup
and records the doc gate as a build convention. Generating it from `main` would
produce a `CLAUDE.md` describing a build that is about to change. PR 2 is marked
as stacked on PR 1.

## Risks

- **Dokka may report undocumented declarations that are awkward to document** —
  enum entries, `BaseState` property overrides. Mitigation: document them
  genuinely where meaningful; if a declaration is truly not worth documenting,
  narrow it with `perPackageOption` rather than weakening `failOnWarning`
  globally.
- **Dokka must resolve the IntelliJ Platform dependency to analyze sources**,
  which is a large classpath. If this proves slow or unstable, the fallback is
  running `dokkaGenerate` only in CI rather than on every local `check`.
