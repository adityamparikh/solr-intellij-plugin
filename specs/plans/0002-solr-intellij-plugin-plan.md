---
specbuddy-type: plan
spec-file: specs/0002-solr-intellij-plugin.md
---

# Implementation Plan: Solr IntelliJ Plugin — Phase 1

## Overview

Phase 1 delivers pure static-analysis language intelligence for Apache Solr configsets
(`managed-schema`/`schema.xml`, `solrconfig.xml`) inside IntelliJ IDEA — completion, cross-file
navigation, rename, inspections, match-capability hints/quick-fixes, and inline docs, with no Solr
connection required.

**Current state.** The template scaffolding is gone and the codebase is re-rooted under
`org.apache.solr.ide`. What exists is the Phase 1 *activation gate* — `SolrConfigsetDetector`,
`SolrConfigsetFileKind`, and the `SolrConfigsetSettings` manual override — plus `SolrBundle`. Every
Phase 1 feature (S1–S9) is still unbuilt. Step 1 below is therefore closed as done; the plan resumes
at Step 1b.

**Cross-reference convention.** Spec references name the section (`spec § "Configset detection"`)
rather than citing line numbers. Line anchors went stale the first time the spec was revised, and
will again.

## Goals

- Implement S1–S9 for the schema versions of every non-EOL Solr line (currently 10.x and 9.10.x).
- Make the plugin API-first: where the schema is Solr-managed, render writes as Schema API requests rather than file edits.
- Build the reference graph foundation that powers navigation (S2), rename (S3), and inspections (S4).
- Generate factory/attribute/documentation reference data from Solr & Lucene artifacts rather than hand-maintaining it.
- Achieve zero false positives on the `_default` and `sample_techproducts_configs` configsets, enforced by CI.
- Ship all `[P1]` documentation items with the release.

## Scope

**In scope:** Phase 1 functional requirements S1–S9, configset detection (including schema provenance), Schema API payload rendering, project-derived reference data,
golden-file CI, and P1 docs (D1, D2, D3, D4, D6, D8).

**Out of scope:** All Phase 2–5 work (connections, query console, indexing, SolrJ code integration,
collection explorer, MCP). Non-P1 docs (D5, D7, D9). No network/credential surface.

## Prerequisites

- [x] JDK configured for the IntelliJ Platform Gradle plugin (see `build.gradle.kts`).
- [x] `./gradlew build` succeeds (baseline green build, CI-verified).
- [x] JDK 21 or later available: Solr 10 requires Java 21, which sets the generator's toolchain floor. Enforced by `jvmToolchain(21)` in `build.gradle.kts`.
- [ ] Local copies of `_default` and `sample_techproducts_configs` configsets available for golden-file test data (from a Solr distribution). Needed by Step 8; not a blocker before then.
- [x] Solr/Lucene artifacts resolvable from Maven Central for both supported lines — verified: Solr 10.0.0 (Lucene 10.3.2) and 9.10.1 (Lucene 9.12.3). Needed only to build the bundled catalog (Step 2, v0.2).

## Implementation Steps

### Step 1: Replace template scaffolding & establish configset detection — **DONE**

Stripped the template's demo tool window / startup activity / services, re-rooted the codebase under
`org.apache.solr.ide`, and implemented configset detection so downstream features activate only on
recognized files. The plugin builds and its only user-visible behavior is correct file recognition.

**What shipped:**
- `SolrConfigsetDetector` — filename matching (`SolrConfigsetFileKind`: `schema.xml`,
  `managed-schema`, `managed-schema.xml`, `solrconfig.xml`) corroborated by directory heuristics.
- `SolrConfigsetSettings` — per-project manual configset roots and a master off switch, persisted to
  the shared `solr.xml` with paths collapsed through `PathMacroManager`.
- `SolrBundle` + `SolrBundle.properties` replacing the template bundle.
- `plugin.xml` registers `managed-schema` / `managed-schema.xml` with the XML file type; no template
  registrations remain.

**Success Criteria:**
- [x] No remaining references to `org.jetbrains.plugins.template` in `src/`.
- [x] `plugin.xml` no longer registers the template tool window or startup activity.
- [x] A detection component identifies the configset filenames and exposes a manual-override toggle.
- [x] `./gradlew build` passes.

**Deferred out of this step:** schema provenance and the pending-conversion inspection, originally
bundled here, are now Step 1b — they have their own caching design and five classification cases.

**Dependencies:** none

### Step 1b: Schema provenance (S8)

Classify each configset as hand-authored or Solr-managed by reading `<schemaFactory>` from the
sibling `solrconfig.xml`, and expose that classification to the write-side features. This is split
from Step 1 because it is not a detection *signal* — it is a separate, more expensive resolution with
a different lifetime, and conflating the two would put an XML parse on the per-file path.

**Context:**
- See spec § "S8 — Schema provenance detection" (requirements) and § "Configset detection"
  (technical design); the provenance data model is in § "Data Models".
- Extends `src/main/kotlin/org/apache/solr/ide/configset/` alongside `SolrConfigsetDetector`.

**Actions:**
1. Implement provenance resolution: read `<schemaFactory>` (class + `mutable`) from the sibling `solrconfig.xml` and classify as hand-authored (`ClassicIndexSchemaFactory`, or managed with `mutable="false"`) or Solr-managed (managed and mutable).
2. Handle both fallbacks: **an absent `<schemaFactory>` classifies as managed** — that is Solr's own default — and only a missing `solrconfig.xml` falls back to the schema filename, erring toward managed.
3. **Cache the classification once per configset directory**, off `SolrConfigsetDetector`'s per-file path. Detection runs on every file the user opens and its signals are deliberately cheap and local; parsing a sibling XML file is neither. Invalidate on modification of the owning `solrconfig.xml`.
4. Expose it as a query the write-side features (Steps 5, 7) consult. Read-side features (S1, S2, S5, S7) and inspections (S4) must never consult it — provenance gates writes only, so it can never suppress a hint, a reference or an inspection.
5. Add the pending-conversion inspection: a `schema.xml` in a managed-factory configset with no managed schema file beside it will be renamed to `schema.xml.bak` and rewritten as `managed-schema.xml` by Solr on first load, so the file being edited is about to be replaced. Ship it with a `description.html` (same requirement as Step 6).
6. Test via `SolrConfigsetTestCase` (not `BasePlatformTestCase`) if it touches `SolrConfigsetSettings` — that base class resets project-level persistent state between methods.
7. Run: `./gradlew build`

**Success Criteria:**
- [ ] Provenance classification resolves all five cases: `ClassicIndexSchemaFactory`, `ManagedIndexSchemaFactory` with `mutable="true"` and with `mutable="false"`, absent `<schemaFactory>` (→ managed), and missing `solrconfig.xml` (→ filename fallback).
- [ ] Classification is resolved once per configset directory and cached; `SolrConfigsetDetector`'s per-file path does not parse `solrconfig.xml`.
- [ ] The pending-conversion inspection fires on a `schema.xml` in a managed-factory configset, and has a `description.html`.
- [ ] No read-side code path consults provenance.
- [ ] `./gradlew build` passes.

**Dependencies:** Step 1

### Step 2: Reference data from the project's own artifacts — **v0.2**

Resolve the factory/attribute/documentation dataset at runtime from the artifacts the open project
resolves, falling back to a bundled catalog. This feeds S1 completion/validation and S7 docs, both of
which are v0.2 — so this step comes *after* the v0.1 release, not before it.

**Context:**
- See spec § "Reference data from the project's own Solr artifacts (critical design decision)" and § "Data Models".
- Read jars as bytecode. The IDE bundles its own Lucene (`intellij.libraries.lucene.common`, plus a `lucene-core-2.4.1` inside the Maven plugin), so loading a project's Lucene into the IDE JVM risks class conflicts; instantiating arbitrary factory constructors in-process is not acceptable regardless.

**Actions:**
1. Implement the resolution order from the spec: (a) the owning module's classpath, (b) `<luceneMatchVersion>` from the sibling `solrconfig.xml` plus the bundled catalog entry for that line, (c) the bundled catalog at the newest supported line. Resolve per *file* via its module, never as a project-wide union.
2. Extract SPI registrations (`TokenizerFactory.availableTokenizers()` and peers — 131 entries on Lucene 10.3.2) and attribute names. Attribute names come from the constructor bytecode: the `AbstractAnalysisFactory` accessors (`get`, `requireInt`, `getBoolean`, …) take the attribute name as a string literal. Do **not** derive them from field names — `WordDelimiterGraphFilterFactory` packs twelve attributes into one `int flags` field and names another `wordFiles` when the attribute is `protected`.
3. Build the bundled catalog for each supported line as a build-time artifact, using the same extractor, so bundled and project-derived data cannot drift.
4. Model the dataset: factory class → valid attributes; field attributes (`indexed`/`stored`/`docValues`/`multiValued`); doc string per factory/attribute; plus the provenance of the answer (which rung resolved it) so the UI can surface it.
5. Add a loader exposing the dataset to completion/validation/docs consumers, caching per module.
6. Run: `./gradlew build`

**Success Criteria:**
- [ ] Factory and attribute data is read from the owning module's Solr/Lucene jars when present.
- [ ] A configset with no module resolves via `<luceneMatchVersion>` to the right bundled entry.
- [ ] A multi-module project describes each configset against its own module.
- [ ] Attributes for `wordDelimiterGraph` include all twelve options (the field-reflection failure case).
- [ ] No project class is loaded into the IDE JVM.
- [ ] `./gradlew build` passes.

**Dependencies:** Step 1

### Step 3: Configset reference model (PSI references + reference graph)

Implement the custom `PsiReference` layer over XML PSI that resolves the S2 cross-file links —
`copyField` source/dest → field, `field type=` → `fieldType`, and `solrconfig.xml` request-handler
params (`df`, `qf`, spellcheck/highlight/facet fields) → schema fields. This is the foundation S2/S3/S4
all consume, so it is built and unit-tested before them.

**Context:**
- See spec § "Architecture approach" ("PSI & reference model" bullet) and requirement S2.
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
- See requirement S1 and spec § "Architecture approach" ("Completion & validation" bullet).

**Actions:**
1. Register a `CompletionContributor` offering field types, factory classes, and valid attributes from the reference dataset.
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
- See requirements S3 and S9, and existing `src/test/testData/rename/foo.xml` + `foo_after.xml`.

**Actions:**
1. Add a `RenamePsiElementProcessor` (or reference-based rename) for field/fieldType elements reusing the Step 3 graph.
2. Ensure `copyField` refs, `field type=` refs, and request-handler param refs are all updated.
3. Consult Step 1b provenance (S9): against a Solr-managed schema, offer "Copy as Schema API request" as the default action — rendering the rename as a `replace-field` / `replace-field-type` payload plus `curl` with a placeholder collection URL — and direct file edit as a warned secondary. Against a hand-authored schema, edit the file with no warning or redirect.
4. Extend the existing `rename` testData with before/after fixtures asserting completeness, plus a managed-schema fixture asserting the API payload is offered first and a hand-authored fixture asserting it is not.
5. Run: `./gradlew build`

**Success Criteria:**
- [ ] Renaming a field updates every resolved reference (copyField, request-handler params).
- [ ] Renaming a `fieldType` updates all `field type=` references.
- [ ] No dangling references remain after rename (asserted by before/after fixtures).
- [ ] Rename against a managed schema offers a valid Schema API payload as the default action; against a hand-authored schema it edits the file with no prompt.
- [ ] `./gradlew build` passes.

**Dependencies:** Step 1b, Step 3

### Step 6: Configset inspections (S4) with description.html

Add local inspection tools for configset errors, each with a Platform `description.html` (which doubles
as the D4 catalog entry). This is where the zero-false-positive requirement gets its teeth.

**Context:**
- See requirement S4 and spec § "Architecture approach" ("Inspections" bullet).
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
quick documentation from the reference dataset (S7).

**Context:**
- See requirements S5, S6, S7 and S9, and spec § "Architecture approach" ("Match-capability analysis" bullet).
- Depends on the Step 2 dataset for the doc provider (S7).

**Actions:**
1. Build a match-capability model classifying exact / tokenized / prefix-substring / case-sensitivity by walking the field's index-time analyzer chain.
2. Add an annotator (S5) surfacing derived match semantics per field.
3. Add intention actions (S6) that create exact-match/prefix companions (`<name>_exact` string + copyField; EdgeNGram fieldType + `<name>_prefix` + copyField), phrased as efficient index-time support.
4. Have the S6 intentions consult Step 1b provenance and, on a managed schema, offer the edit as a Schema API payload (`add-field`, `add-copy-field`, `add-field-type`) ahead of the direct file edit — same rule as Step 5. S5 hints and the S7 doc provider must not consult provenance at all.
5. Add a `DocumentationProvider` (S7) keyed by factory/attribute, sourced from the reference dataset.
6. Add tests asserting derived semantics for canonical types (string, tokenized text, EdgeNGram) and valid, reindex-free-where-possible quick-fix output.
7. Run: `./gradlew build`

**Success Criteria:**
- [ ] Fields are annotated with correct match semantics for canonical field types.
- [ ] S6 intentions produce valid configset edits (companion field + copyField), and on a managed schema offer an equivalent Schema API payload as the default action.
- [ ] Ctrl-Q shows documentation for factories and field attributes.
- [ ] Tests pass; `./gradlew build` passes.

**Dependencies:** Step 1b, Step 2, Step 3

### Step 8: Golden-file CI gate & cross-version test matrix

Add the CI-gating test suite that runs all inspections against the `_default` and
`sample_techproducts_configs` configsets asserting zero false positives, plus reference-data tests and
a matrix over every non-EOL Solr line (currently 10.x and 9.10.x).

**Context:**
- See spec § "Testing Strategy", § "Version-support policy", and the "Zero false positives" non-functional requirement.

**Actions:**
1. Add the shipped `_default` and `sample_techproducts_configs` configsets as test data.
2. Add a golden-file test running every registered inspection over them, asserting zero highlights.
3. Add reference-data tests covering each rung of the resolution order (module classpath, `<luceneMatchVersion>` + bundled catalog, bundled default) and asserting bundled and project-derived data agree.
4. Extend the Step 1b schema-provenance tests (S8) to the integration level: assert the write-side warning fires only for mutable-managed configsets and that read-side results are identical across all five classification cases.
5. Add Schema API payload tests (S9): each rename and quick-fix on a managed schema emits a valid payload with the correct command and attributes, round-tripping to the same schema state the direct PSI edit produces; no payload or warning is offered on a hand-authored schema.
6. Parameterize the suite across the schema versions of every supported (non-EOL) Solr line, so adding or dropping a line is a matrix row change.
7. Add a GitHub Actions workflow (see `.github/`) running `./gradlew build` on push/PR.
8. Run: `./gradlew build`

**Success Criteria:**
- [ ] Golden-file test passes with zero false positives on both shipped configsets.
- [ ] Reference-data tests pass for each supported Solr line and each resolution rung.
- [ ] Schema-provenance tests pass for all five classification cases.
- [ ] Schema API payload tests pass: each rename and quick-fix emits a valid payload that round-trips to the same schema state as the direct edit.
- [ ] CI workflow runs the full build and gates merges.
- [ ] `./gradlew build` passes.

**Dependencies:** Step 4, Step 5, Step 6, Step 7

### Step 9: P1 documentation deliverables & docs CI check

Publish the release-blocking `[P1]` docs and the CI check that keeps them consistent: README/quick start
(D1), Marketplace listing (D2), feature reference (D3), inspection catalog (D4, reusing Step 6
`description.html`), contributor guide (D6), and compatibility matrix + changelog (D8).

**Context:**
- See spec § "Documentation requirements" and the "Docs CI check" item in § "Testing Strategy".
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

- [ ] Plugin installs on IntelliJ IDEA (current + previous major).
- [x] Template scaffolding fully removed (Step 1).
- [x] Configset detection activates features on recognized files with a manual override (Step 1).
- [ ] Schema provenance resolves all five classification cases, cached per configset directory.
- [ ] S1–S9 implemented for every supported (non-EOL) Solr line.
- [ ] Write-side features (S3, S6) offer a Schema API request as the default action on a managed schema, and edit files unprompted on a hand-authored one; read-side features unaffected by provenance.
- [ ] Zero false positives on `_default` and `sample_techproducts_configs` (CI-enforced).
- [ ] Reference data derived from Solr artifacts, preferring the project's own (not hand-maintained).
- [ ] All `[P1]` docs published; docs CI checks green.
- [ ] `./gradlew build` passes end-to-end.

## Risks and Mitigations

- **Bytecode extraction breaks when Lucene changes its accessor shape.** Mitigation: reference-data tests (Step 8) assert known attribute sets per line and catch drift; reading the project's own jars means a new Solr line works before the plugin bundles it.
- **False positives on real configsets block release.** Mitigation: golden-file gate (Step 8) built before docs; inspections tuned against shipped configsets.
- **Reference resolution edge cases (dynamicFields, param aliases) cause dangling renames.** Mitigation: reference graph unit-tested in Step 3 before rename (Step 5) consumes it.
- **Hosting/ownership open question affects vendor identity & repo URL.** Mitigation: `gradle.properties` already uses placeholder values; does not block Phase 1 implementation.
- **Reference Guide doc-string sourcing (S7) is licensing-sensitive.** Mitigation: source only ALv2-compatible content; key docs by factory/attribute in the reference dataset.

## References

- Spec: `specs/0002-solr-intellij-plugin.md`
- Current build: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
- Current plugin descriptor: `src/main/resources/META-INF/plugin.xml`
- Existing rename fixtures: `src/test/testData/rename/`
- IntelliJ Platform SDK: https://plugins.jetbrains.com/docs/intellij/

