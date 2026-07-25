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
- **Steer writes toward the Schema API where it applies**, rather than
  encouraging hand edits that Solr will overwrite — so the plugin reinforces
  Solr's own guidance instead of working around it.
- Keep the plugin maintainable across Solr releases by **deriving** reference
  data (analysis factories, field attributes) from Solr/Lucene artifacts —
  preferring the ones the open project itself resolves — rather than
  hand-maintaining tables.
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

Any case for Solr schema tooling has to begin with the banner at the top of the
default configset: *this file is managed by the Schema API, do not edit it by
hand*. Solr has offered a Schema API for over a decade, and the default
configset's `managed-schema.xml` is generated rather than authored. Tooling that
assumes hand-edited XML has to say why that assumption survives.

It survives because the API displaces a narrower slice of the work than the
banner implies. It does not cover `solrconfig.xml`, which is where request
handlers and relevance parameters live. It is not how analyzer chains get
designed. And it does nothing for the far more frequent act of *reading* a
configset — the XML is still what sits in ZooKeeper, what appears in the pull
request, and what an engineer opens when a query returns nothing. The section
below breaks the workflows down and states plainly where this plugin helps and
where it does not.

Within that scope, developers building on Solr work across three disconnected
surfaces: configset XML edited with no language support, queries iterated in the
Admin UI or curl, and client code that references field names as unchecked string
literals. Every boundary between these surfaces produces a class of silent
runtime failure:

- A typo'd field name in a query returns empty results rather than an error.
- A `copyField` pointing at a removed field fails only at core reload.
- A `qf` param referencing an unindexed field degrades relevance with no warning.

Comparable ecosystems have closed this gap with IDE tooling — Elasticsearch has
multiple actively maintained JetBrains plugins (including an official
Elastic-authored one), and Kafka has the Confluent Plugin for JetBrains IDEs.
Solr has none on the JetBrains Marketplace.

### Where configset XML is authored

Configsets reach a running Solr through four workflows, and this plugin's value
differs sharply across them:

| Workflow | How the XML is produced | Phase 1 value |
|---|---|---|
| **Version-controlled configset** — `ClassicIndexSchemaFactory`, or `ManagedIndexSchemaFactory` with `mutable="false"`; configset in Git, uploaded to ZooKeeper by CI | Hand-edited, code-reviewed | Full — read and write |
| **Container-mounted configset** — Solr Operator / ConfigMap / Helm; a Git-tracked directory mounted into the pod | Hand-edited, code-reviewed | Full — read and write |
| **Mutable managed schema** — fields added by POST to `/solr/<collection>/schema`; Solr rewrites the file | Generated, then read | Read-side in full; write-side redirects to the Schema API (S9) |
| **Schemaless** — the `add-unknown-fields-to-the-schema` update chain guesses types at ingest | Generated | Minimal — but schemaless projects pin their types before production and become one of the rows above |

The first two rows are the plugin's home ground and are not a marginal
population: keeping the configset in version control is what makes a Solr
deployment reproducible, and it is the reason `mutable="false"` and
`ClassicIndexSchemaFactory` remain in wide use.

More importantly, three properties of the Phase 1 feature set hold across *all
four* rows:

- **`solrconfig.xml` has no real API alternative.** The Config API covers common
  properties, component registration and user properties, writing them to
  `configoverlay.json` — a format that is awkward to review and does not reach
  the parts that matter most. Request-handler `defaults`/`appends`/`invariants`,
  dismax `qf`/`pf`/`bf`/`mm` tuning, update-processor chains, cache sizing and
  `autoCommit` are hand-edited XML in every topology above, including the managed
  ones. Request-handler-param resolution and the `qf`/`df` inspections live
  entirely here and are unaffected by how the schema was authored.
- **Analyzer chains are not designed through an API.** A `fieldType` carrying a
  tokenizer chain *can* be POSTed, but chain design is holistic work — comparing
  an index chain against its query chain, deciding whether
  `WordDelimiterGraphFilter` precedes `LowerCaseFilter`, keeping an EdgeNGram on
  the index side only. That is editing, and it is precisely what schema
  completion, the match-capability hints and quick-fixes, and inline
  documentation target. The highest-value part of Phase 1 is the part the Schema
  API least displaces.
- **Comprehension is workflow-independent.** Even when every field arrived via
  the API, the resulting file is what sits in ZooKeeper, what appears in the
  diff, and what gets opened during debugging. Ctrl-click from a `copyField`
  destination to its field, Find Usages on a `fieldType`, Ctrl-Q on
  `ASCIIFoldingFilterFactory` — none of these require having written the line.
  Phase 1 is comprehension tooling as much as authoring tooling, which is why the
  reference graph (see Technical Design) is the foundation both halves build on.

The one place the workflows genuinely diverge is *writing*, and the plugin takes
a position rather than staying neutral. Against a mutable managed schema Solr
owns the file, so the plugin's default answer to a write is *use the Schema API*
— it renders the intended edit as an API request instead of applying it to the
file. Against a hand-authored schema no API is available, editing the file is
correct, and nothing is warned or redirected. Provenance detection classifies
which case a configset is in; the API-first write rule defines what follows.

This is deliberately not a blanket "never edit XML" stance. The version-
controlled workflows in the first two rows have chosen file-as-source-of-truth
so the configset stays reviewable in Git and deployable by CI; steering those
users to the API would bypass their review process and create drift between
Git and ZooKeeper. The redirect is conditional on provenance for that reason,
and it stops at the Schema API — `solrconfig.xml` and every supporting configset
file remain hand-edited by design.

A file-by-file survey of the whole Solr configuration surface — what each file
holds, which APIs write which files, and what this plugin does and does not
cover — is in
[`docs/solr-configuration-files.md`](../docs/solr-configuration-files.md).

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

The nine requirements below carry short IDs so commits and code comments can cite
them precisely. **The IDs are labels, not names** — this document refers to
features by what they do. If you meet one elsewhere, decode it here:

| ID | Feature |
|----|---------|
| S1 | Schema editing support — highlighting, completion, structural validation |
| S2 | Cross-file reference resolution — Ctrl-click and Find Usages |
| S3 | Rename refactoring |
| S4 | Configset inspections |
| S5 | Match-capability hints |
| S6 | Match-capability quick-fixes |
| S7 | Inline component documentation (Ctrl-Q) |
| S8 | Schema provenance detection |
| S9 | API-first writes against a managed schema |

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
- **S8 — Schema provenance detection.** Detection reads the configset's
  `<schemaFactory>` declaration and its `mutable` setting, and classifies the
  schema as *hand-authored* (classic, or managed with `mutable="false"`) or
  *Solr-managed* (managed and mutable). Everything that only reads — completion,
  navigation, match-capability hints, documentation and the inspections —
  behaves identically in both cases; provenance gates writes only, never reads. Where **no `<schemaFactory>` is declared, the configset is
  classified as managed** — Solr's own default is `ManagedIndexSchemaFactory`, so
  an absent declaration means managed, not classic. Where `solrconfig.xml` is not
  part of the project at all, classification falls back to the schema filename and
  errs toward managed. Detection also raises a **pending-conversion** inspection
  for a `schema.xml` in a managed-factory configset with no managed schema file
  beside it: on first load Solr renames that file to `schema.xml.bak` and rewrites
  its content as `managed-schema.xml`, so the file being edited is about to be
  replaced.
- **S9 — API-first writes against a managed schema.** Where S8 classifies a
  schema as Solr-managed, the plugin's default answer to a write is *use the
  Schema API*, not *edit the file*. The two features that write — rename and the
  match-capability quick-fixes — offer two actions, with the API path first:
  - **Copy as Schema API request** (default) — the intended edit is rendered as a
    Schema API JSON payload and a ready-to-run `curl` command placed on the
    clipboard, rather than applied to the file. The collection URL is emitted as
    a placeholder (`http://localhost:8983/solr/<collection>/schema`) since Phase 1
    has no connection; Phase 2 substitutes a configured connection and Phase 4 may
    execute the request directly.
  - **Edit the file directly** (secondary) — proceeds, after a warning that Solr
    owns this file and may overwrite the edit on the next API call.

  Against a hand-authored schema this behavior does **not** apply: no Schema API
  is available, file editing is correct, and no warning or redirect is shown.
  Payload generation is pure text generation and requires no network, preserving
  the Phase 1 offline guarantee.

  **Scope limit:** this covers the Schema API only. It deliberately does not extend
  to `solrconfig.xml` and the Config API, whose coverage is partial and whose
  writes land in `configoverlay.json` rather than the XML — a trade-off rather
  than a clear improvement. Hand editing remains the expected workflow for
  `solrconfig.xml` and for every supporting configset file.

### Phase 1 — Release sequencing (v0.1 / v0.2)

Phase 1 ships in two releases. The split is drawn along one line: **v0.1 contains
everything that needs nothing but the user's own configset files.** Reference
data derived from artifacts is the single largest piece of Phase 1, and gating a
first release on it would delay every feature that does not need it.

**v0.1 — everything that reads the configset and edits it in place:**

- Ctrl-click and Find Usages across the configset (S2)
- Rename a field or field type, updating every reference (S3)
- Inspections for dangling `copyField`s, unused field types, handlers naming
  fields that do not exist, and `qf`/`df` pointing at non-indexed fields (S4)
- Per-field hints for what the field can actually match — exact, tokenized,
  prefix, case-sensitive (S5)
- Quick-fixes that add a missing match capability, such as an `_exact` companion
  field plus its `copyField` (S6)
- Detection of whether a schema is hand-edited or Solr-managed (S8)

**v0.2 — everything needing the derived factory catalog, plus the API write path:**

- Code completion and structural validation in schema files (S1)
- Quick documentation on analysis factories and field attributes (S7)
- Rendering an edit as a Schema API request instead of a file edit (S9)

**Writes in v0.1 are allowed exactly where no API displaces them.** Rename and
the quick-fixes edit the file when the schema is hand-authored, and always for
`solrconfig.xml`, which has no API alternative. Against a mutable managed schema
v0.1 **withholds** the write and explains that Solr owns the file — it does not
silently edit, and it does not yet offer the Schema API alternative. v0.2
replaces that refusal with the "Copy as Schema API request" action, at which
point the write-side story is complete.

This is why provenance detection ships in v0.1 even though it exists only to gate
writes: without it the plugin cannot tell which files it is allowed to edit.

**v0.1 names its ~15 match-semantics factories in code**, which is a
hand-maintained table and therefore an exception to the derive-don't-author
principle above. It is a deliberate one: the set is small, it is the set that
*defines* match semantics rather than an enumeration of what exists
(`KeywordTokenizer`, `EdgeNGramFilter`, `NGramFilter`, `LowerCaseFilter` and
their kin), and it has been stable across Solr majors. Pulling in the whole
derivation pipeline to avoid fifteen constants would invert the cost. The full
catalog arrives with completion in v0.2, at which point these constants become a
classification over derived data rather than a substitute for it.

### Phase 1 — Non-functional requirements

- **Platform support:** installs on IntelliJ IDEA Community and Ultimate
  (current release and previous major).
- **Version coverage:** S1–S9 implemented for the schema versions of every Solr
  release line that Apache Solr has **not** declared end-of-life — see the
  version-support policy below. At time of writing that is **Solr 10.x** and
  **Solr 9.10.x**.
- **Zero false positives** on the `_default` and `sample_techproducts_configs`
  configsets shipped with Solr — enforced by CI golden-file tests.
- **Offline:** no Solr connection or network access required for any Phase 1
  feature.
- **Reference data derived, not hand-maintained:** completion/annotation data for
  factories and attributes is read from Solr/Lucene artifacts — the project's own
  where the project resolves them, a bundled catalog otherwise — so a new Solr
  release requires no re-authoring, and a project on a newer Solr than the plugin
  knows about is described correctly rather than approximately.
- **Licensing/distribution:** published to JetBrains Marketplace under ALv2 with
  source linked.

### Version-support policy

**The plugin supports the Solr release lines that Apache Solr itself has not
declared end-of-life.** The policy is derived from upstream rather than invented
for the plugin, so it needs no separate sign-off and cannot drift from what the
project actually maintains.

Solr's stated lifecycle is that the current major line receives feature releases,
the previous major line receives occasional critical security and bug fixes, and
everything older is EOL and will not be updated. Applied at time of writing:

| Solr line | Upstream status | Plugin support |
|---|---|---|
| **10.x** | Current major — active feature releases | **Supported** |
| **9.10.x** | Previous major — critical fixes only | **Supported** |
| 9.9 and earlier, 8.x and below | EOL | Not supported |

Consequences that follow from adopting it:

- **Support is by line, not by count.** The previous policy — "the two most recent
  minor lines" — became ambiguous the moment Solr 10.0 shipped, since 10.0.0 and
  9.10.1 are the current and previous *majors*, not two minors of one line. Only
  the final minor of the previous major is non-EOL, which the table reflects.
- **Lines are dropped, not deprecated.** When Solr declares a line EOL, the
  plugin drops it in its next release, and the drop is recorded in the
  compatibility matrix and changelog (D8). Dropping a line means removing its
  entry from the bundled catalog and its row from the cross-version test matrix.
- **A new Solr major costs little.** This is the property the
  reference-data decision exists to protect: a project already on Solr 11 is
  described from its own jars before the plugin has heard of Solr 11. Adding it
  to the *bundled* catalog — the fallback for projects with no dependencies — is
  then a version bump and a matrix row, not a re-authoring.
- **Toolchain floor.** Solr 10 requires Java 21, so the build targets JDK 21 or
  later — which also covers the lower baseline of the previous major. This floor
  is set by the newest supported line's class-file version, and rises when
  Solr's does. Reading class files rather than loading them softens this, but the
  bundled catalog is still produced against the newest line.

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
  version × IntelliJ version × Solr line, plus a keep-a-changelog changelog (also
  surfaced in the Marketplace what's-new). The matrix states support in terms of
  the version-support policy, and every EOL-driven drop of a Solr line is a
  changelog entry so users on that line are not surprised by it.
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
  cross-file references (`copyField`↔field, `field type=`↔`fieldType`,
  request-handler params↔schema fields). Reference resolution is the foundation
  that navigation, rename and the inspections all build on.
- **Completion & validation (S1)** — completion contributors and XML structure
  validation driven by the generated reference data (see below).
- **Rename refactoring (S3)** — reuses that reference graph so a rename updates
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
- **Schema provenance (S8)** — configset detection resolves a hand-authored /
  Solr-managed classification once per configset and exposes it to the write-side
  features, which consult it before modifying a file. Read-side features ignore
  it entirely, so the classification can never suppress a hint, a reference or an
  inspection.
- **Schema API payload rendering (S9)** — the intended edit behind a rename
  or quick-fix is expressed as an intermediate *schema change* value
  (add-field, add-copy-field, add-field-type, replace-field, …) which is then
  rendered either as a PSI edit or as a Schema API JSON payload. Modelling the
  change independently of its rendering is what lets one intention drive both
  paths without duplicating the logic.

### Reference data from the project's own Solr artifacts (critical design decision)

Rather than hand-maintaining tables of factory classes, valid attributes, and
their documentation, the plugin **derives** them from Solr and Lucene artifacts —
the analysis SPI registrations plus the attribute names each factory reads.

The artifacts are preferentially **the project's own**: where the open project
resolves Solr or Lucene on a module classpath, the plugin reads those jars, so
completion and validation describe the version the project actually builds
against rather than a version the plugin guessed. This is what removes the
version matrix as a build-time concern — there is no per-line dataset to
regenerate when a new Solr ships, because the answer comes from the project.

**Which jars: the module that owns the configset file.** Resolution is per-file,
not per-project — the file's containing module supplies the classpath to read. A
repository with one module on SolrJ 9.10 and another on 10.0 therefore gets each
configset described against the artifacts its own module builds against, which a
project-wide union could not do.

**The project usually states its own version even when it has no jars.** A
`solrconfig.xml` conventionally carries `<luceneMatchVersion>`, and every
configset Solr ships does, so a bare configset directory — XML files, no module,
no build file, which is the common shape for a version-controlled configset
repository — normally still declares what it targets. Solr defaults the value
when it is absent, so the plugin must tolerate its absence rather than rely on
it.

**`<luceneMatchVersion>` is a Lucene version, not a Solr one.** Solr 10.0.0's
`_default` configset declares `<luceneMatchVersion>10.3</luceneMatchVersion>` —
Lucene 10.3, against Solr line 10.0.0. Solr 9.10.1 pairs with Lucene 9.12.3. The
two version spaces diverged when Solr and Lucene stopped releasing in lockstep,
and conflating them would send rung 2 below to the wrong dataset.

This settles how the bundled catalog is keyed:

- **Analysis factories are keyed by Lucene version.** They are Lucene's classes,
  `<luceneMatchVersion>` names a Lucene version, and the jars read at rung 1 are
  Lucene jars. Keying by anything else would require a translation at every
  lookup.
- **Removed-element knowledge is keyed by Solr line.** `<lib>`,
  `CurrencyField`, the `python`/`ruby`/`php` writers and the rest are Solr's, not
  Lucene's, and they feed the inspections. Reaching them from a configset that declares only a
  Lucene version needs a small Lucene→Solr line table (10.3 → 10.x, 9.12 →
  9.10.x). That table is hand-maintained, which is a deliberate exception to the
  derive-don't-author principle: it has one row per supported line, changes only
  when a line is added or dropped, and no artifact publishes the mapping.

Resolution order, most specific first:

1. **Module classpath.** The owning module resolves Solr/Lucene: read those jars.
   Both the version and the factory data come from the artifacts themselves.
2. **`<luceneMatchVersion>` plus the bundled catalog.** No module or no Solr on
   its classpath, but the sibling `solrconfig.xml` declares a Lucene version: use
   the bundled catalog entry for it, and the Lucene→Solr table above for the
   Solr-side knowledge. The project supplies the version; the plugin supplies the
   data.
3. **Bundled catalog, newest supported line.** Nothing declares a version, or
   the declared version matches no supported line.

The bundled catalog therefore exists to answer *what a given version contains*,
never *which version this project is on* — that question belongs to the project
in every case but the last. Which rung answered is surfaced rather than hidden,
so a user seeing unexpected completions can tell whether the plugin read their
jars, believed their `<luceneMatchVersion>`, or fell back.

Two constraints shape the implementation:

- **Read bytecode; do not load classes.** The IDE ships its own Lucene
  (`intellij.libraries.lucene.common`, and an ancient `lucene-core` inside the
  Maven plugin). Loading a project's Lucene into the IDE JVM invites class
  conflicts, and instantiating arbitrary factory constructors inside the IDE
  process is not acceptable regardless. Attribute names are recovered by reading
  the constructor bytecode, which is also more complete than execution — it sees
  attributes behind conditional branches and past the point where a missing
  required argument would have thrown.
- **A bundled fallback is required, not optional.** The workflow this plugin
  serves best — a version-controlled configset repository — frequently has no
  Java module and no dependencies at all, just XML. SolrJ on the classpath is
  also the *client* version, which need not match the server the configset is
  deployed to. So the plugin ships a small catalog for the current supported
  lines and uses it whenever the project cannot answer, surfacing which source
  is in effect rather than silently guessing.

The resulting dataset feeds completion/validation and quick documentation. This
design is documented in D7 and referenced by Phase 1 non-functional requirements.

Empirically the two supported lines barely differ here: Lucene 9.12.3 (Solr
9.10.1) and Lucene 10.3.2 (Solr 10.0.0) expose 130 identical analysis factories,
with one addition in 10.x (`romanianNormalization`) and no removals. The version
sensitivity that matters for this plugin is not the analyzer vocabulary but the
*removed configuration elements* catalogued in
[`docs/solr-configuration-files.md`](../docs/solr-configuration-files.md) —
`<lib>`, `CurrencyField`/`EnumField`/`ExternalFileField`, the `python`/`ruby`/
`php` and XLSX response writers, `BlobHandler`, the legacy
`CircuitBreakerManager` form and `addHttpRequestToContext`. Those belong to the
the inspections, which is where per-version knowledge actually earns its keep.

### Configset detection

Features activate for files recognized as Solr configsets using directory
heuristics plus file-name matching (`managed-schema`, `schema.xml`,
`solrconfig.xml`), with a **manual override** for projects whose layout the
heuristics miss. Detection behavior and the override are documented in D5.

Detection also resolves **schema provenance** (S8) for the configset by reading
the `<schemaFactory>` element from the sibling `solrconfig.xml` — its class and,
for `ManagedIndexSchemaFactory`, its `mutable` setting — falling back to the
schema file's name when that element is absent or `solrconfig.xml` is not part of
the project. Provenance gates only rename and the quick-fixes; it never
affects which files activate.

## Data Models

Phase 1 introduces no persisted data model beyond IntelliJ's own project/PSI
model. The primary internal models are:

- **Reference graph** — resolved links between configset elements (fields,
  fieldTypes, copyFields, request-handler params), derived from PSI.
- **Match-capability model** — a per-field classification (exact / tokenized /
  prefix-substring / case-sensitivity) derived by walking the field's index-time
  analyzer chain.
- **Reference dataset** — factory classes, valid attributes and documentation
  strings, read from the project's Solr/Lucene jars where available and from the
  bundled catalog otherwise. Carries its own provenance, so the UI can say which
  source answered.
- **Schema provenance** — a per-configset hand-authored / Solr-managed
  classification derived from `<schemaFactory>` (S8), consulted by write-side
  features only.
- **Schema change** — a rendering-independent description of an intended schema
  edit (S9), emitted either as a PSI modification or as a Schema API payload.

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
- **Reference-resolution and rename tests:** verify navigation targets and
  rename completeness (no dangling references) on representative configsets.
- **Match-capability tests:** assert derived semantics (S5) for canonical field
  types (string, tokenized text, EdgeNGram) and verify the quick-fixes produce
  valid, reindex-free-where-possible configset edits.
- **Reference-data tests:** verify the expected factories/attributes are produced
  for each supported Solr line, from a project classpath carrying those artifacts
  and from the bundled catalog, asserting the two agree. Cover each rung of the
  resolution order: a module resolving Solr (jars answer), a bare configset
  directory whose `solrconfig.xml` declares `<luceneMatchVersion>` (that line's
  bundled entry answers), a configset declaring nothing (newest line), and a
  module on a Solr newer than any bundled entry (jars answer, catalog unused).
  Assert that a multi-module project resolves each configset against its own
  module rather than a project-wide union, and that a `<luceneMatchVersion>` is
  resolved as a *Lucene* version — a configset declaring `10.3` must select the
  Lucene 10.3 factory set and the Solr 10.x removed-element set, not a
  Solr 10.3 that does not exist. Rung 1 requires real Solr/Lucene jars on a test
  module's classpath, which `BasePlatformTestCase` does not provide by default;
  the fixture work to add them is part of this requirement, not incidental to it.
- **Schema-provenance tests (S8):** classify configsets declaring
  `ClassicIndexSchemaFactory`, `ManagedIndexSchemaFactory` with `mutable="true"`
  and with `mutable="false"`, plus the absent-`<schemaFactory>` case (must
  classify as managed) and the no-`solrconfig.xml` filename fallback; assert the
  write-side warning fires for every case resolving to a mutable managed schema
  and for no other, and that read-side features produce identical results across
  all of them. Cover the pending-conversion inspection with a
  `schema.xml`-in-managed-configset fixture.
- **Schema API payload tests (S9):** assert that each S3 rename and S6 quick-fix
  emits a valid Schema API payload for the managed case — correct command
  (`add-field`, `add-copy-field`, `add-field-type`, `replace-field`), correct
  attributes, and a payload that round-trips to the same schema state the direct
  PSI edit would produce. Assert no redirect or warning is offered for
  hand-authored schemas.
- **Docs CI check:** every registered inspection has a `description.html`
  (satisfies D4 completeness); docs state supported versions identically to the
  compatibility matrix (single source of truth).
- **Cross-version matrix:** validate against the schema versions of every
  supported (non-EOL) Solr line.

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
- S1–S9 are implemented for the schema versions of every supported (non-EOL)
  Solr line.
- Against a Solr-managed schema, write-side features offer a Schema API request as
  the default action and a warned file edit as the alternative; against a
  hand-authored schema they edit the file with no warning. Read-side features are
  unaffected by provenance in both cases.
- Inspections produce zero false positives on `_default` and
  `sample_techproducts_configs`, enforced by CI golden-file tests.
- Reference data is derived from Solr artifacts, preferring the project's own
  (not hand-maintained).
- Published to JetBrains Marketplace under ALv2 with source linked.
- All **[P1]** documentation items are published before/with the release.

## Resolved decisions

- **Hosting and ownership — settled.** The code lives in an external repository
  under the author's namespace (`adityamparikh/solr-intellij-plugin`), licensed
  ALv2, to be linked from the Solr Reference Guide as community tooling (D9).
  ASF donation is **not** being pursued until the plugin is built and released
  and has demonstrated it is useful. Nothing here precludes a later donation —
  the license is already the one an ASF repository would require — and deferring
  removes a governance dependency from the critical path of a pre-release plugin.
- **Marketplace vendor identity — moot for now.** It follows from the hosting
  decision: publication is under the author's own vendor account. An ASF vendor
  account and the PMC coordination it needs become relevant only if donation is
  revisited after release.

## Open Questions

- **Marketplace compatibility cadence:** how quickly the plugin must follow a new
  IntelliJ Platform release, given the version-support policy above is pinned to
  Solr's lifecycle rather than JetBrains'.
- **Evidence for the authoring split.** "Where configset XML is authored" argues
  qualitatively that version-controlled configsets remain a large population. That
  claim would be stronger with data — e.g. a survey of public configsets for
  `ClassicIndexSchemaFactory` and `mutable="false"` versus the mutable default, or
  a question to the user list. Worth gathering before the proposal goes to a wider
  audience; it does not block implementation.

## References

- Solr configuration-file survey (which files are hand-edited, which are
  API-written, and what this plugin covers):
  [`docs/solr-configuration-files.md`](../docs/solr-configuration-files.md)
- JetBrains Marketplace (no maintained Solr plugin exists):
  https://plugins.jetbrains.com/
- Precedents: Confluent Plugin for JetBrains IDEs; `elasticsearch4idea`; Elastic's
  official ES|QL IntelliJ plugin.
- IntelliJ Platform SDK: https://plugins.jetbrains.com/docs/intellij/
- Solr MCP server (Phase 5, exploratory): SOLR-17944.
