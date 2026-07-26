---
specbuddy-type: plan
spec-file: specs/0002-solr-intellij-plugin.md
---

# Implementation Plan: Solr IntelliJ Plugin

## Overview

Deliver the plugin described in the spec: configuration intelligence, a live Solr
connection, and Java/Kotlin code support, unified by one field model.

**Current state.** The build, CI and documentation tooling are complete and stay as
they are. The Solr code is four files implementing the activation gate —
`SolrConfigsetDetector`, `SolrConfigsetFileKind`, `SolrConfigsetSettings`, `SolrBundle`.
Everything the spec describes is unbuilt.

**What changed from the previous plan.** The provenance classification, the API-first
write gating, and the runtime derivation of reference data from project jars are gone —
see the spec's "The old approach, and why this document replaces it" and "The factory
catalog". Roughly a third of the previous plan's complexity was serving decisions this
spec reverses.

**Cross-reference convention.** Reference spec sections by name, never by line number.
Line anchors go stale on the first revision.

## Build order

[Step 2](#step-2-overhaul-the-activation-gate) unblocks everything. After
[Step 3](#step-3-repository-reader-and-field-model) the work splits into three tracks
that do not depend on each other and can be built in any order or in parallel.

### Foundation — build first

- [Step 1 — Activation gate](#step-1-activation-gate-done) — **done**
- [Step 2 — Overhaul the activation gate](#step-2-overhaul-the-activation-gate)
- [Step 3 — Repository reader and field model](#step-3-repository-reader-and-field-model)
- [Step 4 — Match analysis](#step-4-match-analysis)

### Editor track

- [Step 5 — References, navigation and Find Usages](#step-5-references-navigation-and-find-usages)
- [Step 6 — Inspections](#step-6-inspections)
- [Step 7 — Match hints and quick-fixes](#step-7-match-hints-and-quick-fixes)
- [Step 8 — Rename](#step-8-rename)
- [Step 9 — Factory catalog generator](#step-9-factory-catalog-generator)
- [Step 10 — Completion, validation and quick documentation](#step-10-completion-validation-and-quick-documentation)

### Server track

- [Step 11 — HTTP client, connections and the server reader](#step-11-http-client-connections-and-the-server-reader)
- [Step 12 — Collections tool window](#step-12-collections-tool-window)
- [Step 13 — Query console](#step-13-query-console)
- [Step 14 — Drift view, upload and reload](#step-14-drift-view-upload-and-reload)
- [Step 15 — Indexing test documents](#step-15-indexing-test-documents)

### Code track

- [Step 16 — Recognizer interface and SolrJ](#step-16-recognizer-interface-and-solrj)
- [Step 17 — Query syntax and the console bridge](#step-17-query-syntax-and-the-console-bridge)
- [Step 18 — Framework configuration](#step-18-framework-configuration)
- [Step 19 — Apache Camel](#step-19-apache-camel)

### Cross-cutting — continuous, finished last

- [Step 20 — CI gates](#step-20-ci-gates)
- [Step 21 — Documentation](#step-21-documentation)

Within a track the order is the dependency order. Across tracks, the only meeting point
is [Step 14](#step-14-drift-view-upload-and-reload), the drift view — which is also the
feature that most justifies building both halves.

## Prerequisites

- [x] JDK 21 toolchain, green build, CI verified.
- [x] Solr and Lucene artifacts resolvable from Maven Central for both supported lines —
      verified: Solr 10.0.0 with Lucene 10.3.2, Solr 9.10.1 with Lucene 9.12.3. Needed
      by [Step 9](#step-9-factory-catalog-generator).
- [ ] Local copies of the `_default` and `sample_techproducts_configs` configsets Solr
      ships, for test fixtures. Needed by [Step 20](#step-20-ci-gates).
- [ ] A local Solr for manual verification. Not needed by any automated test — every
      test uses the fake HTTP layer from
      [Step 11](#step-11-http-client-connections-and-the-server-reader).
- [ ] **Decision required before [Step 2](#step-2-overhaul-the-activation-gate):** the
      `org.apache.solr` package namespace. See spec, "What changes in the existing code".

---

## Standing rules for every step

These apply to all steps and are not repeated in each one.

- `./gradlew build` passes, which runs the tests, the coverage floor and the
  documentation gate. Any new public declaration needs KDoc in the same change.
- Nothing on the editor path may contact a server or block the UI thread.
- No automated test may require a running Solr.
- Anything touching persistent project settings extends `SolrConfigsetTestCase`, because
  the platform's test base class shares one project across test classes and leaks state.

---

## Foundation

### Step 1: Activation gate (done)

Stripped the IntelliJ plugin template, re-rooted the code under `org.apache.solr.ide`,
and implemented configset detection so features activate only on recognized files.

**What shipped:**
- `SolrConfigsetDetector` — file-name matching corroborated by directory heuristics.
- `SolrConfigsetFileKind` — the schema names and `solrconfig.xml`.
- `SolrConfigsetSettings` — manual configset roots and a detection switch, persisted to
  the shared project file with paths collapsed through `PathMacroManager`.
- `SolrBundle`, and `plugin.xml` registering `managed-schema` as XML.

**Success criteria:**
- [x] No template references remain in `src/`.
- [x] Detection identifies configset files and exposes a manual override.
- [x] `./gradlew build` passes.

[Step 2](#step-2-overhaul-the-activation-gate) reworks this for the model the spec
describes; it is extended, not replaced.

**Acceptance:** Enables [demo runbook](../../docs/demo/README.md) step 21 — the plugin activates on the demo configset at all. No demo step of its own.

**Dependencies:** none

### Step 2: Overhaul the activation gate

The existing detection code is correct for the feature set it was written for and
insufficient for this one. Fix it before building on it.

**Actions:**
1. Resolve the package namespace question and, if renaming, do it now while the code is
   four files.
2. Establish package layout for the components the spec names: repository reader, field
   model, server client, recognizers, UI. Empty packages with a package-level doc
   comment each, so later work has an obvious home.
3. Extend detection from *is this a configset file* to *which configset does this file
   belong to*. A configset is a directory; the model is per-configset; a project may
   contain several. Keep the per-file check as the activation gate.
4. Cache detection per directory and invalidate on file-system change. `hasDirectoryEvidence`
   currently lists directory children on every call with no cache, which is affordable
   now and not once editor-path features depend on it.
5. Widen `SolrConfigsetFileKind` to the rest of a configset: `params.json`,
   `elevate.xml`, `currency.xml`, `enumsConfig.xml`, and the analyzer resource files —
   `stopwords.txt`, `synonyms.txt`, `protwords.txt`, `lang/`.
6. Add a per-user settings surface for connections, separate from the existing shared
   project settings. Connection credentials go to PasswordSafe and must never reach a
   shared file. Extend `SolrConfigsetTestCase` to reset it, for the same reason it
   resets the existing settings.

**Success criteria:**
- [ ] A file resolves to its owning configset; a project with two configsets keeps them
      distinct.
- [ ] Detection results are cached and invalidate correctly on file change.
- [ ] The widened file kinds are recognized.
- [ ] Connection settings persist per-user; configset roots stay shared.

**Acceptance:** [demo runbook](../../docs/demo/README.md) step 21 — opening the demo schema activates the plugin, and a project holding two configsets keeps them apart.

**Dependencies:** [Step 1](#step-1-activation-gate-done)

### Step 3: Repository reader and field model

The spine. Everything else reads this.

**Actions:**
1. Parse a configset into declared fields, dynamic fields, field types, analyzer chains,
   copy fields, and the request-handler parameters that name fields.
2. Build the field model: merge sources, record the origin of every fact, expose the
   four agreement states — repository only, server only, agreeing, disagreeing. The
   server half stays empty until [Step 11](#step-11-http-client-connections-and-the-server-reader); build the seam now so it does not have to be
   retrofitted.
3. Cache per configset, invalidate on file change.
4. Test the model directly, with no IDE fixtures where possible. This is the component
   that must be exhaustively correct.

**Success criteria:**
- [ ] A configset parses to a complete field model, including dynamic fields.
- [ ] The four agreement states are representable and tested with a synthetic server
      half.
- [ ] Model rebuilds on file change and not otherwise.

**Acceptance:** No demo step of its own; every step from 22 onward fails without it. Verify through the steps that consume it.

**Dependencies:** [Step 2](#step-2-overhaul-the-activation-gate)

### Step 4: Match analysis

A pure function from analyzer chain to match capability. Independent of everything;
buildable in parallel with [Step 3](#step-3-repository-reader-and-field-model).

**Actions:**
1. Classify a field's index-time chain: whole value or tokenized, prefix-capable or not,
   case-sensitive or not.
2. Name the factories that determine this in code — roughly fifteen, and a deliberate
   exception to generating things, because this set *defines* the semantics rather than
   enumerating what exists, and has been stable across Solr majors.
3. Test exhaustively against canonical types and against the orderings that change the
   answer.

**Success criteria:**
- [ ] Correct classification for string, tokenized text, EdgeNGram and lowercased
      variants.
- [ ] Filter ordering that changes the result is covered by tests.

**Acceptance:** Correctness behind [demo runbook](../../docs/demo/README.md) steps 28 to 31 — the hints those steps show are only as good as this. Test it directly, not through the annotator.

**Dependencies:** none

---

## Editor track

### Step 5: References, navigation and Find Usages

**Actions:**
1. Reference providers for: a field's `type` to its field type; `copyField` source and
   destination to fields; request-handler parameters in `solrconfig.xml` to schema
   fields; a filter's resource attribute to the `stopwords.txt` or `synonyms.txt` it
   names.
2. Expose a reference-graph query surface that Steps 6 and 8 reuse.
3. Reference tests asserting resolve targets on representative configsets.

**Success criteria:**
- [ ] All four reference kinds resolve; Find Usages returns every reference.

**Acceptance:** [demo runbook](../../docs/demo/README.md) steps 22, 23, 24 and 27 — Ctrl-click a field's type, a copy rule's destination, and a `qf` name in `solrconfig.xml`; then Find Usages on a field type.

**Dependencies:** [Step 3](#step-3-repository-reader-and-field-model)

### Step 6: Inspections

Where the zero-false-positive requirement gets teeth.

**Actions:**
1. Implement: dangling `copyField` source or target; handler naming a nonexistent field;
   relevance parameters on non-indexed fields; unused field types; known-bad analyzer
   chain orderings; configuration elements removed in the targeted Solr line.
2. A description file per inspection, written as user-facing prose — it is also the
   published catalog entry.
3. Test each on both flagged and clean fixtures.

**Success criteria:**
- [ ] Every inspection fires on crafted-bad fixtures and on nothing clean.
- [ ] Every registered inspection has a description file.

**Acceptance:** [demo runbook](../../docs/demo/README.md) steps 25 and 26 — the planted dangling copy rule is underlined, and deleting a referenced field flags its rule immediately.

**Dependencies:** [Step 5](#step-5-references-navigation-and-find-usages)

### Step 7: Match hints and quick-fixes

**Actions:**
1. Annotator surfacing each field's match capability from [Step 4](#step-4-match-analysis).
2. Intentions adding a missing capability: an `_exact` companion plus `copyField`, an
   EdgeNGram-backed `_prefix` field. Phrased as efficient index-time support, since
   wildcards already provide slow partial matching.
3. Edit the file directly. No provenance check, no warning, no redirect.

**Success criteria:**
- [ ] Fields annotated correctly for canonical types.
- [ ] Quick-fixes produce valid configset edits.

**Acceptance:** [demo runbook](../../docs/demo/README.md) steps 28 to 33 — a `string` field reads as whole-value and case-sensitive, a tokenised field does not offer prefix matching, an edge-n-gram field does, and Alt-Enter generates the companion field with its copy rule.

**Dependencies:** [Step 3](#step-3-repository-reader-and-field-model), [Step 4](#step-4-match-analysis)

### Step 8: Rename

**Actions:**
1. Rename fields and field types, updating every reference through the [Step 5](#step-5-references-navigation-and-find-usages) graph.
2. Extend the existing `src/test/testData/rename/` fixtures with before/after pairs
   asserting no dangling references remain.

**Success criteria:**
- [ ] Every resolved reference updates; no dangling references after rename.

**Acceptance:** [demo runbook](../../docs/demo/README.md) step 34 — renaming a field updates its copy rules *and* the `qf` line in `solrconfig.xml`.

**Dependencies:** [Step 5](#step-5-references-navigation-and-find-usages)

### Step 9: Factory catalog generator

**Actions:**
1. A Gradle task that reflects over Solr and Lucene artifacts per supported line and
   emits a catalog of factories, their attributes, and documentation strings. Runs in
   the build, where loading Solr classes is ordinary.
2. Declare supported lines in one place so adding or dropping one is a single edit.
3. A loader selecting the entry by: connected server, then `<luceneMatchVersion>`
   translated through the Lucene-to-Solr table, then newest supported line. Record which
   source answered.
4. Only ALv2-compatible documentation content may be embedded.

**Success criteria:**
- [ ] Catalog generated at build time for both supported lines and present on the plugin
      classpath.
- [ ] `wordDelimiterGraph` exposes all twelve of its attributes — the case that defeats
      naive field reflection.
- [ ] Selection order is correct and the answering source is recorded.
- [ ] `./gradlew build` passes and regenerates.

**Acceptance:** Feeds [demo runbook](../../docs/demo/README.md) steps 68 to 70. Step 69 is the one to run by hand: `WordDelimiterGraphFilterFactory` must offer all twelve attributes, because an implementation reading field names produces a plausible short list instead.

**Dependencies:** [Step 2](#step-2-overhaul-the-activation-gate)

### Step 10: Completion, validation and quick documentation

**Actions:**
1. Completion for field types, factories, their attributes, and field attributes.
2. Structural validation flagging unknown factories and invalid attributes.
3. Dynamic field pattern awareness.
4. Documentation provider keyed by factory and attribute, surfacing which catalog source
   answered.

**Success criteria:**
- [ ] Completion and validation work against the catalog.
- [ ] Quick documentation resolves for factories and attributes.

**Acceptance:** [demo runbook](../../docs/demo/README.md) steps 68 to 70 — factories complete inside an analyser chain, attributes complete on a factory that has many, and Ctrl-Q resolves.

**Dependencies:** [Step 3](#step-3-repository-reader-and-field-model), [Step 9](#step-9-factory-catalog-generator)
---

## Server track

### Step 11: HTTP client, connections and the server reader

**Actions:**
1. A minimal HTTP and JSON client for the endpoints the plugin needs. No SolrJ
   dependency — see the spec.
2. Connection definitions in per-user settings, credentials in PasswordSafe. Basic auth
   and TLS.
3. Server reader: schema, collections, cores, and the fields actually present in the
   index.
4. Populate the server half of the field model.
5. **A fake HTTP layer for tests**, covering success, timeout, auth failure, malformed
   response, and unrecognized server version. No automated test may require a running
   Solr. This fixture is part of the step.
6. Every call asynchronous and timeout-bounded; nothing on the editor path.

**Success criteria:**
- [ ] A connection can be created, stored and used; credentials never reach project
      files.
- [ ] The server half of the model populates.
- [ ] All five failure modes tested against the fake layer.
- [ ] Server state refreshes only on request or connection change — never on a timer.

**Acceptance:** [demo runbook](../../docs/demo/README.md) step 35 — a connection can be created and used.

**Dependencies:** [Step 3](#step-3-repository-reader-and-field-model)

### Step 12: Collections tool window

**Actions:**
1. Browse collections, cores, shards, replicas, aliases, and the server's actual fields.
2. Show the selected connection persistently.
3. Report failure inline, once, showing Solr's own message.

**Success criteria:**
- [ ] The topology renders; the selected connection is always visible.
- [ ] An unreachable server degrades to an inline message, not a popup.

**Acceptance:** [demo runbook](../../docs/demo/README.md) step 36 — collections, cores and the server's actual fields render, and the selected connection stays visible.

**Dependencies:** [Step 11](#step-11-http-client-connections-and-the-server-reader)

### Step 13: Query console

**Actions:**
1. Field completion from the model.
2. Results as a table; scoring explanation as an expandable tree.
3. History, and queries saveable into the project so they are version-controllable.

**Success criteria:**
- [ ] Queries run and render structurally; completion works with no configset present.
- [ ] Saved queries round-trip through the project.

**Acceptance:** [demo runbook](../../docs/demo/README.md) steps 37 and 38 — a query runs with completion from the live schema, results render as a table, and the scoring explanation expands as a tree.

**Dependencies:** [Step 11](#step-11-http-client-connections-and-the-server-reader)

### Step 14: Drift view, upload and reload

The feature that justifies both halves.

**Actions:**
1. Render the model's disagreement states: repository-only fields, server-only fields,
   differing definitions.
2. Upload a configset and reload a collection, each invoked by name and confirming its
   target.
3. Where a change maps onto the Schema API, offer to apply it — as an action from this
   view, not from the editor.

**Success criteria:**
- [ ] All three disagreement categories render correctly.
- [ ] Upload and reload confirm and name their target server.
- [ ] No write occurs without explicit invocation.

**Acceptance:** [demo runbook](../../docs/demo/README.md) steps 39 and 40 — a field added to the repository and not deployed shows as a difference; upload and reload clear it, naming the target server first.

**Dependencies:** [Step 3](#step-3-repository-reader-and-field-model), [Step 11](#step-11-http-client-connections-and-the-server-reader)

### Step 15: Indexing test documents

**Actions:**
1. A document editor with schema-aware completion and validation.
2. Sample document generation from the schema.
3. Index into a selected collection with explicit commit behavior and confirmation.

**Success criteria:**
- [ ] Documents can be authored with completion and indexed on explicit invocation.

**Acceptance:** [demo runbook](../../docs/demo/README.md) step 71 — a document authored with completion indexes into the local collection and is then findable.

**Dependencies:** [Step 11](#step-11-http-client-connections-and-the-server-reader)

---

## Code track

### Step 16: Recognizer interface and SolrJ

**Actions:**
1. Define the recognizer interface: reports endpoints and field references. Keep it
   minimal — Steps 18 and 19 depend on it being right.
2. SolrJ recognizer: client construction supplies endpoints; `SolrQuery` builder calls,
   raw parameter strings, `SolrInputDocument` field names and `@Field` annotations
   supply field references.
3. Inspection flagging field references absent from the model, and completion for them.
4. **Silence where resolution fails.** Assert this in tests explicitly — precision
   matters more than recall.

**Success criteria:**
- [ ] Field references resolve in builder calls, raw strings, document building and bean
      annotations.
- [ ] Unresolvable constructs produce no warning.

**Acceptance:** [demo runbook](../../docs/demo/README.md) steps 41 to 44 and 47 — the planted typo in a filter query is flagged, so is a field that never existed, so is the misspelled `@Field` annotation. Step 47 matters most: constructs the plugin cannot resolve produce no warning at all.

**Dependencies:** [Step 3](#step-3-repository-reader-and-field-model)

### Step 17: Query syntax and the console bridge

**Actions:**
1. Treat the query string inside a literal as its own language, so it gets structure and
   highlighting.
2. Gutter action running that query in the console against a selected connection.
3. Navigation from a field name in code to its schema definition.

**Success criteria:**
- [ ] Query strings render structurally inside Java and Kotlin literals.
- [ ] The gutter action runs the query; navigation resolves when a configset is present.

**Acceptance:** [demo runbook](../../docs/demo/README.md) steps 45 and 46 — the query string renders as a query rather than a grey blob, and the gutter icon runs it in the console.

**Dependencies:** [Step 13](#step-13-query-console), [Step 16](#step-16-recognizer-interface-and-solrj)

### Step 18: Framework configuration

**Actions:**
1. Verify which platform framework-configuration APIs are available to plugins and in
   which editions. Prefer the platform's model over parsing configuration directly.
2. Declare optional dependencies so these features appear when the supporting
   functionality is present and the plugin loads normally when it is not.
3. Resolve a Solr URL per profile with each framework's own precedence: Spring Boot
   profile files, **Quarkus inline `%profile.` prefixes in a single file**, Micronaut
   environments, MicroProfile ordinals.
4. Offer discovered endpoints as connection candidates. Never connect automatically.
5. **Real project fixtures per framework**, not synthetic strings.

**Success criteria:**
- [ ] Boot profile files and Quarkus inline prefixes both resolve correctly.
- [ ] The plugin loads and functions with no framework support present.
- [ ] Discovered endpoints are offered, never adopted silently.

**Acceptance:** [demo runbook](../../docs/demo/README.md) step 35 for Spring — the URL is offered by following `${app.solr.url}` from the SolrJ client bean into the active profile, and is never connected to automatically. Quarkus, Micronaut and MicroProfile have no demo step by decision; their acceptance is fixture tests here. Quarkus is the one to get right: its profiles are inline key prefixes in one file, so a Spring-shaped implementation finds nothing in a Quarkus project and finds it silently.

**Dependencies:** [Step 16](#step-16-recognizer-interface-and-solrj)

### Step 19: Apache Camel

**Actions:**
1. Recognize Solr endpoint URIs in Camel routes as connection candidates.
2. Validate the URI's component options against the known set.
3. Check field references in route parameters and document construction.
4. Java and XML route definitions first. Where the IDE or an installed plugin models
   routes, read that model rather than writing another URI parser.

**Success criteria:**
- [ ] Endpoints recognized from Java and XML routes; options validated.

**Acceptance:** No demo step, by decision — the [demo runbook](../../docs/demo/README.md) explains why only Spring gets one. Acceptance is fixture tests here: a Solr endpoint in a Java route and in an XML route is offered as a connection candidate, and a misspelled URI option is flagged.

**Dependencies:** [Step 16](#step-16-recognizer-interface-and-solrj)

---

## Cross-cutting

### Step 20: CI gates

**Actions:**
1. Add `_default` and `sample_techproducts_configs` as fixtures.
2. Golden-file test running every inspection over both, asserting zero highlights. This
   gate must be in place before the release, not after.
3. Assert every registered inspection has a description file.
4. Assert documented versions match the compatibility matrix.
5. Catalog tests per supported line.

**Success criteria:**
- [ ] Zero false positives on both shipped configsets, enforced in CI.
- [ ] Missing description files and version drift both fail the build.

**Acceptance:** This step *is* the automated gate, so it has no demo step. It is what stops the demo passing while the suite quietly rots.

**Dependencies:** [Step 6](#step-6-inspections) for the golden-file gate; [Step 9](#step-9-factory-catalog-generator) for catalog tests

### Step 21: Documentation

**Actions:**
1. README and quick start; feature reference with screenshots and stated limits,
   including how match capability is derived and the wildcard caveat.
2. Inspection catalog assembled from the description files.
3. Contributor guide.
4. Compatibility matrix and changelog in keep-a-changelog format.
5. Marketplace listing: summary, screenshots, a recording of the headline features,
   tags, compatibility statement.

**Success criteria:**
- [ ] All release-blocking documentation exists and CI checks pass.

**Acceptance:** No demo step. The documentation check in this step asserts that every inspection shipped in step 6 has a description file.

**Dependencies:** [Step 6](#step-6-inspections), [Step 20](#step-20-ci-gates)
---

## Validation checklist

- [x] Build, CI, coverage and documentation gates in place.
- [x] Configset detection with a manual override.
- [ ] Package namespace decided.
- [ ] Detection resolves configset identity and is cached.
- [ ] Field model tracks source and exposes all four agreement states.
- [ ] Editor features work with no connection.
- [ ] Server features work with no configset in the project.
- [ ] Code features stay silent where they cannot resolve.
- [ ] Zero false positives on both shipped configsets, CI-enforced.
- [ ] No automated test requires a running Solr.
- [ ] No write occurs without explicit human invocation naming its target.
- [ ] Release documentation published.
- [ ] `./gradlew build` passes end to end.

## Risks

- **Code analysis produces false positives.** The most likely cause of bad reviews.
  Mitigation: silence is the default where resolution fails, asserted in tests ([Step 16](#step-16-recognizer-interface-and-solrj)).
- **Framework configuration works only on the author's machine.** Real projects nest
  configuration in unexpected places. Mitigation: real project fixtures per framework,
  and prefer the platform's model over our own parsing ([Step 18](#step-18-framework-configuration)).
- **Scope exceeds what can be polished.** This plan is large and the quality bar is
  explicit. Mitigation: the track structure means Editor, Server and Code can each reach
  a shippable state independently — if something must be cut, cut a whole track rather
  than leaving three half-built.
- **A server version the plugin has never seen.** Mitigation: ignore unknown response
  fields, report rather than refuse unrecognized versions, and cover it in the fake HTTP
  layer ([Step 11](#step-11-http-client-connections-and-the-server-reader)).
- **Reference resolution edge cases cause dangling renames.** Mitigation: the reference
  graph is unit-tested in [Step 5](#step-5-references-navigation-and-find-usages) before rename consumes it in [Step 8](#step-8-rename).

## References

- Spec: `specs/0002-solr-intellij-plugin.md`
- Configuration file survey: `docs/solr-configuration-files.md`
- Plugin development tutorial: `docs/modern-intellij-plugin-development.md`
- Existing rename fixtures: `src/test/testData/rename/`
- IntelliJ Platform SDK: https://plugins.jetbrains.com/docs/intellij/
