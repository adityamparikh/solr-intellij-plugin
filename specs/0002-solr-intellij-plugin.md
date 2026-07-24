---
specbuddy-type: spec
plan-file: specs/plans/0002-solr-intellij-plugin-plan.md
---

# Solr IntelliJ Platform Plugin

## Overview

This specification defines an Apache-licensed (ALv2) IDE plugin for the IntelliJ
Platform that brings first-class Apache Solr development tooling into IntelliJ
IDEA. The plugin closes the gap between the three disconnected surfaces Solr
developers work across today — configset XML, ad-hoc queries, and SolrJ client
code — by providing language intelligence, cross-file navigation, inspections,
and (in later phases) live query and dev-loop operations.

The work is structured as an **umbrella effort** delivered in phases. This
document specifies the whole program at a high level and details **Phase 1**
(schema/config language intelligence) and the **documentation deliverables** to
implementation-ready depth, since those are the committed first releases.
Phases 2–5 are specified at scope level and will be broken out into their own
specs as Phase 1 validates the approach.

## Goals

- Deliver a maintained, ALv2-licensed Solr plugin on the JetBrains Marketplace —
  a category in which Solr currently has no maintained tooling, unlike
  Elasticsearch and Kafka.
- Eliminate the classes of silent runtime failure that arise at the boundaries
  between configset, query, and client code (typo'd field names, dangling
  `copyField` references, params targeting non-indexed fields).
- Ship a Phase 1 MVP that is **useful offline** — pure static analysis of
  configset files with no Solr connection required.
- Keep the plugin maintainable across Solr releases by **generating** reference
  data (analysis factories, field attributes) from Solr/Lucene artifacts rather
  than hand-maintaining tables.
- Target IntelliJ IDEA (Community and Ultimate) first and SolrJ as the client
  integration surface.

## Non-Goals

- **Bulk / production ingestion and transformation pipelines** — indexing
  support (Phase 2) is deliberately scoped to development workflows only.
- **Spring Data Solr integration** — the project is unmaintained; only Spring
  Boot autoconfiguration properties may be read for connection detection.
- **Other JetBrains IDEs** (PyCharm, GoLand, etc.) at launch — a later,
  exploratory goal (Phase 5).
- **Localized documentation, video tutorials, and blog posts** — an author's
  independent blog series may cover the plugin but is not an ASF deliverable.
- **A committed Solr MCP server dependency** — routing queries through an MCP
  server (SOLR-17944) is exploratory (Phase 5), not part of the committed scope.

## Background

### Motivation

Developers building on Solr work across three disconnected surfaces: configset
XML edited with no language support, queries iterated in the Admin UI or curl,
and client code that references field names as unchecked string literals. Every
boundary between these surfaces produces a class of silent runtime failure:

- A typo'd field name in a query returns empty results rather than an error.
- A `copyField` pointing at a removed field fails only at core reload.
- A `qf` param referencing an unindexed field degrades relevance with no warning.

Comparable ecosystems have closed this gap with IDE tooling — Elasticsearch has
multiple actively maintained JetBrains plugins (including an official
Elastic-authored one), and Kafka has the Confluent Plugin for JetBrains IDEs.
Solr has none on the JetBrains Marketplace.

### Persona

All user stories share one persona unless noted: **a software engineer building
applications on Apache Solr using IntelliJ IDEA**, editing configsets and SolrJ
code in the same project. The documentation deliverables additionally address a
**contributor** persona (a developer extending the plugin itself).

### Phased scope (program-level)

| Phase | Theme | Server connection | Status |
|-------|-------|-------------------|--------|
| **1** | Schema & config intelligence (static analysis) | None | **Committed — this spec** |
| **2** | Connections & query console; dev-oriented indexing | Live | Scoped; follow-up spec |
| **3** | SolrJ code integration | Live | Scoped; follow-up spec |
| **4** | Collection explorer & dev-loop operations | Live | Scoped; follow-up spec |
| **5** | Ecosystem (MCP routing, more IDEs) | Optional | Exploratory, not committed |

**Phase 2 scope:** named connections (standalone and SolrCloud, basic auth/TLS,
credentials in IDE PasswordSafe); auto-detection of connection details from
SolrJ configuration in the project; a query console with schema-derived field
completion, structured result rendering, `debugQuery` explain output as a
navigable score tree, query history, and version-controllable saved queries.
Dev-oriented indexing: an ad-hoc document editor with schema-aware
completion/validation, indexing JSON/CSV/XML files from the project with
explicit commit behavior, sample-document generation from the schema, and
round-trip edit/re-index of documents from query results. Indexing/delete
actions are gated behind a per-connection "protected" flag (default on for
non-localhost connections).

**Phase 3 scope:** field-name completion and validation inside query strings in
Java/Kotlin (SolrQuery parameters, string constants passed to SolrJ APIs)
validated against the project's schema; a "run in query console" gutter action
on query strings; inspections for schema-incompatible queries (e.g., sort/facet
on non-docValues fields).

**Phase 4 scope:** a tool window for collections/cores/shards/replicas/aliases;
upload configset to ZooKeeper + reload collection as IDE actions;
deployed-vs-repo config diff; classification of schema edits as
safe-live / requires-reload / requires-reindex; a run configuration launching
local Solr (Docker or binary) with the project configset; an analysis-chain
debugger (per-stage token output, equivalent to the Admin UI Analysis screen).

## Requirements

### Phase 1 — Functional requirements (schema & configset intelligence)

All Phase 1 features are **pure static analysis** of configset files
(`managed-schema` / `schema.xml`, `solrconfig.xml`) in the open project; no Solr
connection is required.

- **S1 — Schema editing support.** Syntax highlighting, code completion, and
  structural validation for `managed-schema`/`schema.xml`: field types,
  tokenizer/filter/charFilter factory classes and their valid attributes, field
  attributes (`indexed`, `stored`, `docValues`, `multiValued`), and
  `dynamicField` patterns.
- **S2 — Cross-file reference resolution.** Ctrl-click navigation and Find Usages
  across configset references: `copyField` source/dest → field definitions,
  `field type=` → `fieldType`, and `solrconfig.xml` request-handler params
  (`df`, `qf`, spellcheck/highlight/facet field params) → schema fields.
- **S3 — Rename refactoring.** Rename a field or `fieldType` and update all
  references across the configset, leaving no dangling references.
- **S4 — Configset inspections.** Editor-time inspections for configset errors:
  dangling `copyField` sources/targets, unused `fieldType`s, request handlers
  referencing nonexistent fields, `qf`/`df` on non-indexed fields, and
  known-problematic analyzer chain orderings.
- **S5 — Match-capability hints.** Annotate each field with its effective match
  semantics derived from its analyzer chain: exact whole-value match
  (`StrField` / `KeywordTokenizer`-based), tokenized term match, prefix/substring
  match (`EdgeNGramFilter` / `NGramFilter` in the index chain), and case
  sensitivity (`LowerCaseFilter` presence).
- **S6 — Match-capability quick-fixes.** Intention actions that add a missing
  match capability — e.g., "Add exact-match companion: create `<name>_exact` as
  string plus a copyField"; "Add prefix matching: create/reuse an EdgeNGram
  fieldType and a `<name>_prefix` field with copyField." Hints and fixes must be
  phrased as **efficient index-time** support, since wildcard/regex queries
  provide slow partial matching on any indexed field at query time.
- **S7 — Inline component documentation.** Quick documentation (Ctrl-Q) on
  analysis factories and field attributes, sourced from the Solr Reference Guide.

### Phase 1 — Non-functional requirements

- **Platform support:** installs on IntelliJ IDEA Community and Ultimate
  (current release and previous major).
- **Version coverage:** S1–S7 implemented for the schema versions used by the two
  most recent Solr minor lines (the proposed standing version-support policy).
- **Zero false positives** on the `_default` and `sample_techproducts_configs`
  configsets shipped with Solr — enforced by CI golden-file tests.
- **Offline:** no Solr connection or network access required for any Phase 1
  feature.
- **Generated reference data:** completion/annotation data for factories and
  attributes is generated from Solr artifacts (reflection over
  `org.apache.solr.*` factory classes + Lucene analysis SPI), not hand-maintained
  — so a new Solr release requires regeneration, not re-authoring.
- **Licensing/distribution:** published to JetBrains Marketplace under ALv2 with
  source linked.

### Documentation requirements

Docs ship incrementally. Items marked **[P1]** are required for the Phase 1
Marketplace release and block it; the rest land with their corresponding phases.
`D1, D3–D7` live in the plugin repo (`docs/` + `README`) so docs are versioned
with the code.

- **D1 — README & quick start [P1].** One-paragraph statement of what the plugin
  does, plus a quick start from install to first working feature (schema
  completion) in under five minutes.
- **D2 — Marketplace listing [P1].** Feature summary, annotated screenshots, a
  short GIF of headline features (schema completion, match-capability hints and
  quick-fixes), correct tags/categories ("Big Data", "Data tools"), and a
  compatibility statement.
- **D3 — Feature reference [P1, then per phase].** Each feature documented with a
  screenshot, what it does, and its limits — including an explicit page on
  match-capability semantics (how exact/tokenized/prefix support is derived from
  analyzer chains, and the wildcard-query caveat).
- **D4 — Inspection catalog [P1, then per phase].** Every inspection listed with
  rationale, an example of flagged config, and the fix; linking to the relevant
  Solr Reference Guide section where feasible.
- **D5 — Troubleshooting & FAQ.** Covers configset detection (why features didn't
  activate, and the manual override), version-compatibility questions, and how to
  report bugs with logs.
- **D6 — Contributor guide [P1].** Dev-environment setup (JDK, Gradle IntelliJ
  Platform plugin, running the sandbox IDE, running tests including the
  golden-file configset tests), project layout, and contribution workflow.
- **D7 — Architecture doc.** Key design decisions: how schema PSI/reference
  resolution is structured, how completion data is generated from Solr artifacts,
  and how match-capability analysis models analyzer chains. Decision records for
  non-obvious trade-offs.
- **D8 — Compatibility matrix & changelog [P1].** Maintained matrix of plugin
  version × IntelliJ version × Solr version, plus a keep-a-changelog changelog
  (also surfaced in the Marketplace what's-new).
- **D9 — Solr Reference Guide cross-link.** The community-tools section of the
  Solr Reference Guide mentions the plugin with a link (a small follow-up PR to
  the Solr docs; requires PMC review).

## Technical Design

### Architecture approach

The plugin is an IntelliJ Platform plugin built with the Gradle IntelliJ
Platform plugin. Phase 1 is layered on standard IntelliJ language-support
extension points:

- **PSI & reference model** — Solr configset XML is modeled through the
  platform's XML PSI, with custom `PsiReference` implementations wiring the
  cross-file references in S2 (`copyField`↔field, `field type=`↔`fieldType`,
  request-handler params↔schema fields). Reference resolution is the foundation
  that S2 navigation, S3 rename, and S4 inspections all build on.
- **Completion & validation (S1)** — completion contributors and XML structure
  validation driven by the generated reference data (see below).
- **Rename refactoring (S3)** — reuses the S2 reference graph so a rename updates
  all resolved references.
- **Inspections (S4)** — local inspection tools, each with an
  IntelliJ-Platform inspection `description.html` (this doubles as the D4
  catalog's per-inspection description and satisfies the CI "every inspection has
  a description" check).
- **Match-capability analysis (S5/S6)** — a model of a field's analyzer chain
  that classifies effective match semantics; annotators surface hints (S5) and
  intention actions apply the standard multi-field patterns (S6).
- **Quick documentation (S7)** — a documentation provider sourced from Reference
  Guide content keyed by factory/attribute.

### Generated reference data (critical design decision)

Rather than hand-maintaining tables of factory classes, valid attributes, and
their documentation, Phase 1 **generates** this data from Solr and Lucene
artifacts — reflection over `org.apache.solr.*` factory classes and the Lucene
analysis SPI. This is the mechanism that makes the version-support policy (two
most recent Solr minor lines) sustainable: supporting a new release is a
regeneration step in the build, not a re-authoring effort. The generator's output
feeds S1 completion/validation and S7 documentation. This design is documented in
D7 and referenced by Phase 1 non-functional requirements.

### Configset detection

Features activate for files recognized as Solr configsets using directory
heuristics plus file-name matching (`managed-schema`, `schema.xml`,
`solrconfig.xml`), with a **manual override** for projects whose layout the
heuristics miss. Detection behavior and the override are documented in D5.

## Data Models

Phase 1 introduces no persisted data model beyond IntelliJ's own project/PSI
model. The primary internal models are:

- **Reference graph** — resolved links between configset elements (fields,
  fieldTypes, copyFields, request-handler params), derived from PSI.
- **Match-capability model** — a per-field classification (exact / tokenized /
  prefix-substring / case-sensitivity) derived by walking the field's index-time
  analyzer chain.
- **Generated reference dataset** — the build-time artifact enumerating factory
  classes, valid attributes, and documentation strings.

Later phases (2+) introduce connection profiles (stored in IDE settings with
credentials in PasswordSafe) and saved queries (version-controllable, stored in
the project) — specified in their own follow-up specs.

## API / Interfaces

Phase 1 exposes no external API. Its "interfaces" are IntelliJ Platform
extension points (completion contributors, reference providers, inspections,
intention actions, documentation providers, rename processors) and the
user-facing editor affordances they produce. The SolrJ client API becomes
relevant only in Phase 3.

## Security Considerations

- **Phase 1 has no network or credential surface** — it reads project files
  only, which is the primary security benefit of the static-analysis-first
  sequencing.
- **Phase 2+ credentials** must be stored in IntelliJ's PasswordSafe, never in
  project files or plain settings.
- **Destructive-action guard (Phase 2+):** indexing and delete actions are gated
  behind a per-connection "protected" flag, defaulting **on** for non-localhost
  connections, to prevent accidental writes against shared or production Solr.

## Testing Strategy

- **Golden-file configset tests (Phase 1, CI-gating):** run all inspections
  against the `_default` and `sample_techproducts_configs` configsets shipped
  with Solr and assert **zero false positives**.
- **Reference-resolution and rename tests:** verify S2 navigation targets and S3
  rename completeness (no dangling references) on representative configsets.
- **Match-capability tests:** assert derived semantics (S5) for canonical field
  types (string, tokenized text, EdgeNGram) and verify S6 quick-fixes produce
  valid, reindex-free-where-possible configset edits.
- **Generated-data tests:** verify the reference-data generator produces expected
  factories/attributes for each supported Solr minor line.
- **Docs CI check:** every registered inspection has a `description.html`
  (satisfies D4 completeness); docs state supported versions identically to the
  compatibility matrix (single source of truth).
- **Cross-version matrix:** validate against the schema versions of the two most
  recent Solr minor lines.

## Acceptance Criteria

**Umbrella is Done when:** (a) the community reaches consensus on
hosting/ownership (see Open Questions), and (b) Phase 1 ships a first release
installable from the JetBrains Marketplace. Phases 2–4 are filed as linked
follow-up tickets once Phase 1 validates the approach.

**Phase 1 is Done when:**
- The plugin installs on IntelliJ IDEA Community and Ultimate (current release
  and previous major).
- Configset detection activates features on recognized files, with a manual
  override.
- S1–S7 are implemented for the two most recent Solr minor lines' schema
  versions.
- Inspections produce zero false positives on `_default` and
  `sample_techproducts_configs`, enforced by CI golden-file tests.
- Reference data is generated from Solr artifacts (not hand-maintained).
- Published to JetBrains Marketplace under ALv2 with source linked.
- All **[P1]** documentation items are published before/with the release.

## Open Questions

- **Hosting and ownership.** Options: (a) code lives in an ASF repo under the
  Solr project (like `solr-operator`); (b) an external repo under a contributor's
  namespace, ALv2, linked from the Solr Reference Guide as community tooling.
  Option (b) has lower governance overhead to start and does not preclude later
  donation.
- **Marketplace vendor identity if (a):** publishing under an ASF vendor account
  requires PMC coordination.
- **Version support matrix:** proposal is to validate against the two most recent
  Solr minor lines at any time — needs community sign-off as a standing policy.

## References

- JetBrains Marketplace (no maintained Solr plugin exists):
  https://plugins.jetbrains.com/
- Precedents: Confluent Plugin for JetBrains IDEs; `elasticsearch4idea`; Elastic's
  official ES|QL IntelliJ plugin.
- IntelliJ Platform SDK: https://plugins.jetbrains.com/docs/intellij/
- Solr MCP server (Phase 5, exploratory): SOLR-17944.
