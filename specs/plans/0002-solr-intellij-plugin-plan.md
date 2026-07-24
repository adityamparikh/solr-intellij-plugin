---
specbuddy-type: plan
spec-file: specs/0002-solr-intellij-plugin.md
---

# Implementation Plan: Solr IntelliJ Plugin — Phase 1

## Overview

Phase 1 delivers pure static-analysis language intelligence for Apache Solr configsets
(`managed-schema`/`schema.xml`, `solrconfig.xml`) inside IntelliJ IDEA — completion, cross-file
navigation, rename, inspections, match-capability hints/quick-fixes, and inline docs, with no Solr
connection required. The project is currently the JetBrains IntelliJ Platform plugin template
(`org.jetbrains.plugins.template`) with Solr build metadata already applied; this plan replaces the
template scaffolding with the Phase 1 feature set and its P1 documentation deliverables.

## Goals

- Implement S1–S7 for the two most recent Solr minor lines' schema versions.
- Build the reference graph foundation that powers navigation (S2), rename (S3), and inspections (S4).
- Generate factory/attribute/documentation reference data from Solr & Lucene artifacts rather than hand-maintaining it.
- Achieve zero false positives on the `_default` and `sample_techproducts_configs` configsets, enforced by CI.
- Ship all `[P1]` documentation items with the release.

## Scope

**In scope:** Phase 1 functional requirements S1–S7, configset detection, generated reference data,
golden-file CI, and P1 docs (D1, D2, D3, D4, D6, D8).

**Out of scope:** All Phase 2–5 work (connections, query console, indexing, SolrJ code integration,
collection explorer, MCP). Non-P1 docs (D5, D7, D9). No network/credential surface.

## Prerequisites

- [ ] JDK configured for the IntelliJ Platform Gradle plugin (see `build.gradle.kts`).
- [ ] `./gradlew build` succeeds on the current template scaffolding (baseline green build).
- [ ] Local copies of `_default` and `sample_techproducts_configs` configsets available for golden-file test data (from a Solr distribution).
- [ ] Solr/Lucene artifacts resolvable from Maven Central for the two supported minor lines (for the reference-data generator).

## Implementation Steps

### Step 1: Replace template scaffolding & establish package/configset detection foundation

Strip the template's demo tool window / startup activity / services and re-root the codebase under a
Solr package, then implement configset detection so downstream features activate only on recognized
files. This leaves a buildable plugin whose only user-visible behavior is correct file recognition.

**Context:**
- See `src/main/resources/META-INF/plugin.xml` (currently registers `MyToolWindowFactory`, `MyProjectActivity`).
- See `src/main/kotlin/org/jetbrains/plugins/template/` (template classes to remove/replace).
- See spec "Configset detection" and non-functional "manual override" (`specs/0002-solr-intellij-plugin.md#L244-L248`).

**Actions:**
1. Choose the target package (e.g. `org.apache.solr.ide`, matching `group` in `gradle.properties`) and move sources there; delete `toolWindow/`, `startup/`, `services/` template demos.
2. Rewrite `plugin.xml` `<extensions>` to drop template registrations (keep `com.intellij.modules.xml` depends).
3. Implement configset detection: directory heuristics + filename matching (`managed-schema`, `schema.xml`, `solrconfig.xml`) that gates feature activation, with a per-project manual override (a simple project-level setting/service).
4. Rename `MyBundle`/`MyBundle.properties` to a Solr-named resource bundle and update `<resource-bundle>`.
5. Run: `./gradlew build`

**Success Criteria:**
- [ ] No remaining references to `org.jetbrains.plugins.template` in `src/`.
- [ ] `plugin.xml` no longer registers the template tool window or startup activity.
- [ ] A detection component identifies the three configset filenames and exposes a manual-override toggle.
- [ ] `./gradlew build` passes.

**Dependencies:** none

### Step 2: Generated reference data pipeline

Add a build-time generator that produces the factory/attribute/documentation dataset by reflecting over
`org.apache.solr.*` analysis factory classes and the Lucene analysis SPI, plus Reference Guide-sourced
doc strings. This dataset is the single source feeding S1 completion/validation and S7 docs, and is the
mechanism that makes the version-support policy sustainable.

**Context:**
- See spec "Generated reference data (critical design decision)" (`specs/0002-solr-intellij-plugin.md#L232-L241`) and "Data Models" (`#L260-L261`).
- See `build.gradle.kts` and `settings.gradle.kts` for where to add the generator task and Solr/Lucene dependency resolution.

**Actions:**
1. Add a Gradle task (own source set or `buildSrc`) that resolves Solr/Lucene artifacts and reflects over analysis factories (tokenizer/filter/charFilter) + field-attribute metadata, emitting a structured resource (JSON) into generated resources.
2. Model the emitted dataset: factory class → valid attributes; field attributes (`indexed`/`stored`/`docValues`/`multiValued`); doc string per factory/attribute.
3. Wire the task into `processResources` so `./gradlew build` regenerates it; commit a snapshot for offline builds if needed.
4. Add a loader in plugin code exposing the dataset to completion/validation/docs consumers.
5. Run: `./gradlew build`

**Success Criteria:**
- [ ] A Gradle task generates the dataset from Solr/Lucene artifacts (no hand-authored factory tables).
- [ ] Generated resource is present on the plugin classpath after build.
- [ ] A loader class exposes factories, valid attributes, and doc strings by key.
- [ ] `./gradlew build` passes and regenerates the dataset.

**Dependencies:** Step 1

### Step 3: Configset reference model (PSI references + reference graph)

Implement the custom `PsiReference` layer over XML PSI that resolves the S2 cross-file links —
`copyField` source/dest → field, `field type=` → `fieldType`, and `solrconfig.xml` request-handler
params (`df`, `qf`, spellcheck/highlight/facet fields) → schema fields. This is the foundation S2/S3/S4
all consume, so it is built and unit-tested before them.

**Context:**
- See spec "PSI & reference model" (`specs/0002-solr-intellij-plugin.md#L212-L218`) and requirement S2 (`#L127-L132`).
- The existing `src/test/testData/rename/` fixtures indicate the testData convention to follow.

**Actions:**
1. Add `PsiReferenceContributor`/`PsiReferenceProvider` implementations for each reference kind, scoped to configset files via Step 1 detection.
2. Resolve field/fieldType definitions within `schema.xml`; resolve request-handler params in `solrconfig.xml` across to schema fields.
3. Expose a reusable reference-graph query surface (find definition, find usages) that Steps 4–6 reuse.
4. Add unit/reference tests (`ReferenceTestCase`-style) on representative configsets asserting resolve targets.
5. Run: `./gradlew build`

**Success Criteria:**
- [ ] Ctrl-click resolves `copyField` source/dest and `field type=` to their definitions.
- [ ] Request-handler params in `solrconfig.xml` resolve to schema fields.
- [ ] Find Usages returns all references to a given field/fieldType.
- [ ] Reference tests pass; `./gradlew build` passes.

**Dependencies:** Step 1

### Step 4: Schema completion & structural validation (S1)

Add completion contributors and XML structure validation driven by the Step 2 dataset: field types,
factory classes and their valid attributes, field attributes, and `dynamicField` patterns.

**Context:**
- See requirement S1 (`specs/0002-solr-intellij-plugin.md#L121-L126`) and "Completion & validation (S1)" (`#L219-L220`).

**Actions:**
1. Register a `CompletionContributor` offering field types, factory classes, and valid attributes from the generated dataset.
2. Add structural validation (annotator) flagging unknown factory classes / invalid attributes for a given factory.
3. Support `dynamicField` pattern awareness in completion/validation.
4. Add completion + highlighting tests on fixture configsets.
5. Run: `./gradlew build`

**Success Criteria:**
- [ ] Completion suggests valid factories and their attributes in schema files.
- [ ] Invalid factory/attribute combinations are highlighted.
- [ ] Tests cover completion and validation on representative fixtures.
- [ ] `./gradlew build` passes.

**Dependencies:** Step 2, Step 3

### Step 5: Rename refactoring (S3)

Implement rename for fields and `fieldType`s that updates all references via the Step 3 reference graph,
leaving no dangling references.

**Context:**
- See requirement S3 (`specs/0002-solr-intellij-plugin.md#L133-L134`) and existing `src/test/testData/rename/foo.xml` + `foo_after.xml`.

**Actions:**
1. Add a `RenamePsiElementProcessor` (or reference-based rename) for field/fieldType elements reusing the Step 3 graph.
2. Ensure `copyField` refs, `field type=` refs, and request-handler param refs are all updated.
3. Extend the existing `rename` testData with before/after fixtures asserting completeness.
4. Run: `./gradlew build`

**Success Criteria:**
- [ ] Renaming a field updates every resolved reference (copyField, request-handler params).
- [ ] Renaming a `fieldType` updates all `field type=` references.
- [ ] No dangling references remain after rename (asserted by before/after fixtures).
- [ ] `./gradlew build` passes.

**Dependencies:** Step 3

### Step 6: Configset inspections (S4) with description.html

Add local inspection tools for configset errors, each with a Platform `description.html` (which doubles
as the D4 catalog entry). This is where the zero-false-positive requirement gets its teeth.

**Context:**
- See requirement S4 (`specs/0002-solr-intellij-plugin.md#L135-L140`) and "Inspections (S4)" (`#L222-L225`).
- Consider the `hibernate-jpa-validator` and `code-review` skills only if relevant; primary guidance is the IntelliJ inspection API.

**Actions:**
1. Implement inspections: dangling `copyField` source/target, unused `fieldType`, request handlers referencing nonexistent fields, `qf`/`df` on non-indexed fields, known-problematic analyzer-chain orderings.
2. Author a `description.html` per inspection (rationale + flagged example + fix) under the inspection description resources.
3. Register inspections in `plugin.xml` with categories/keys.
4. Add tests per inspection on positive (flagged) and negative fixtures.
5. Run: `./gradlew build`

**Success Criteria:**
- [ ] All listed inspections are registered and fire on crafted-bad fixtures.
- [ ] Every registered inspection has a `description.html`.
- [ ] No inspection fires on clean fixtures (precursor to Step 8 golden-file gate).
- [ ] `./gradlew build` passes.

**Dependencies:** Step 3

### Step 7: Match-capability hints (S5), quick-fixes (S6), and inline docs (S7)

Model each field's index-time analyzer chain to classify effective match semantics, surface them as
annotator hints (S5), apply the standard multi-field patterns as intention actions (S6), and provide
quick documentation from the generated dataset (S7).

**Context:**
- See requirements S5/S6 (`specs/0002-solr-intellij-plugin.md#L141-L149`), S7 (`#L150-L151`), and "Match-capability analysis" (`#L227-L231`).
- Depends on the Step 2 dataset for the doc provider (S7).

**Actions:**
1. Build a match-capability model classifying exact / tokenized / prefix-substring / case-sensitivity by walking the field's index-time analyzer chain.
2. Add an annotator (S5) surfacing derived match semantics per field.
3. Add intention actions (S6) that create exact-match/prefix companions (`<name>_exact` string + copyField; EdgeNGram fieldType + `<name>_prefix` + copyField), phrased as efficient index-time support.
4. Add a `DocumentationProvider` (S7) keyed by factory/attribute, sourced from the generated dataset.
5. Add tests asserting derived semantics for canonical types (string, tokenized text, EdgeNGram) and valid, reindex-free-where-possible quick-fix output.
6. Run: `./gradlew build`

**Success Criteria:**
- [ ] Fields are annotated with correct match semantics for canonical field types.
- [ ] S6 intentions produce valid configset edits (companion field + copyField).
- [ ] Ctrl-Q shows documentation for factories and field attributes.
- [ ] Tests pass; `./gradlew build` passes.

**Dependencies:** Step 2, Step 3

### Step 8: Golden-file CI gate & cross-version test matrix

Add the CI-gating test suite that runs all inspections against the `_default` and
`sample_techproducts_configs` configsets asserting zero false positives, plus generated-data tests and
the two-most-recent-minor-line matrix.

**Context:**
- See spec "Testing Strategy" (`specs/0002-solr-intellij-plugin.md#L288-L302`) and non-functional "Zero false positives" (`#L156-L157`).

**Actions:**
1. Add the shipped `_default` and `sample_techproducts_configs` configsets as test data.
2. Add a golden-file test running every registered inspection over them, asserting zero highlights.
3. Add generated-data tests verifying expected factories/attributes per supported Solr minor line.
4. Parameterize the suite across the two most recent Solr minor lines' schema versions.
5. Add a GitHub Actions workflow (see `.github/`) running `./gradlew build` on push/PR.
6. Run: `./gradlew build`

**Success Criteria:**
- [ ] Golden-file test passes with zero false positives on both shipped configsets.
- [ ] Generated-data tests pass for each supported minor line.
- [ ] CI workflow runs the full build and gates merges.
- [ ] `./gradlew build` passes.

**Dependencies:** Step 4, Step 5, Step 6, Step 7

### Step 9: P1 documentation deliverables & docs CI check

Publish the release-blocking `[P1]` docs and the CI check that keeps them consistent: README/quick start
(D1), Marketplace listing (D2), feature reference (D3), inspection catalog (D4, reusing Step 6
`description.html`), contributor guide (D6), and compatibility matrix + changelog (D8).

**Context:**
- See "Documentation requirements" (`specs/0002-solr-intellij-plugin.md#L168-L203`) and "Docs CI check" (`#L299-L301`).
- `CHANGELOG.md` and the `org.jetbrains.changelog` plugin are already present.

**Actions:**
1. Write `README.md` quick start (D1) and `docs/` feature reference (D3) incl. the match-capability semantics + wildcard-query caveat page.
2. Generate/curate the D4 inspection catalog from the Step 6 `description.html` files; add a docs CI check asserting every registered inspection has a `description.html`.
3. Write the D6 contributor guide (JDK/Gradle setup, sandbox IDE, running tests incl. golden-file).
4. Maintain the D8 compatibility matrix (plugin × IntelliJ × Solr) as the single source of truth, keep `CHANGELOG.md` in keep-a-changelog format, and prepare the D2 Marketplace listing (summary, screenshots, GIF, tags "Big Data"/"Data tools", compatibility statement).
5. Add a docs CI check asserting supported versions in docs match the compatibility matrix.
6. Run: `./gradlew build`

**Success Criteria:**
- [ ] D1, D3, D4, D6, D8 exist in-repo; D2 listing content prepared.
- [ ] CI fails if any inspection lacks a `description.html` or if doc versions diverge from the matrix.
- [ ] Changelog is in keep-a-changelog format and wired to the Marketplace what's-new.
- [ ] `./gradlew build` passes.

**Dependencies:** Step 6, Step 8

## Validation Checklist

- [ ] Plugin installs on IntelliJ IDEA (current + previous major); template scaffolding fully removed.
- [ ] Configset detection activates features on recognized files with a manual override.
- [ ] S1–S7 implemented for the two most recent Solr minor lines.
- [ ] Zero false positives on `_default` and `sample_techproducts_configs` (CI-enforced).
- [ ] Reference data generated from Solr artifacts (not hand-maintained).
- [ ] All `[P1]` docs published; docs CI checks green.
- [ ] `./gradlew build` passes end-to-end.

## Risks and Mitigations

- **Reflection-based generation breaks across Solr versions.** Mitigation: pin supported minor lines; generated-data tests (Step 8) catch drift; regeneration is a build step, not re-authoring.
- **False positives on real configsets block release.** Mitigation: golden-file gate (Step 8) built before docs; inspections tuned against shipped configsets.
- **Reference resolution edge cases (dynamicFields, param aliases) cause dangling renames.** Mitigation: reference graph unit-tested in Step 3 before rename (Step 5) consumes it.
- **Hosting/ownership open question affects vendor identity & repo URL.** Mitigation: `gradle.properties` already uses placeholder values; does not block Phase 1 implementation.
- **Reference Guide doc-string sourcing (S7) is licensing-sensitive.** Mitigation: source only ALv2-compatible content; key docs by factory/attribute in the generated dataset.

## References

- Spec: `specs/0002-solr-intellij-plugin.md`
- Current build: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
- Current plugin descriptor: `src/main/resources/META-INF/plugin.xml`
- Existing rename fixtures: `src/test/testData/rename/`
- IntelliJ Platform SDK: https://plugins.jetbrains.com/docs/intellij/

