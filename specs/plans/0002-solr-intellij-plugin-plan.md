---
specbuddy-type: plan
spec-file: specs/0002-solr-intellij-plugin.md
---

# Implementation Plan: Solr IntelliJ Plugin

## Overview

Deliver the plugin described in the spec: configuration intelligence, a live Solr
connection, and Java/Kotlin code support, unified by one field model.

**Current state.** The build, CI and documentation tooling are complete and stay as they
are. Everything else is
[the activation gate](#step-1-activation-gate-done) and nothing more. Everything the spec
describes is unbuilt.

**What this plan dropped.** The provenance classification, the API-first write gating,
and the runtime derivation of reference data from project jars are gone. The spec records
why, in "What this replaces" and "The factory catalog"; roughly a third of the previous
plan existed to serve those decisions.

**Cross-reference convention.** Reference a step or a section by its name, never by a
number or a line alone. This holds in both directions — a reference to the demo runbook
carries the demo step's title too, because the runbook has its own numbering and a bare
number silently means the wrong thing.

## Build order

The activation gate overhaul unblocks everything except match analysis, which depends on
nothing and can start immediately. After the repository reader and field model, the work
splits into three tracks. Within a track the order is the dependency order.

The tracks meet twice. The drift view needs both the field model and the server reader —
it is the feature that most justifies building both halves. The gutter action inside
*query syntax and the console bridge* needs the query console, so the Code track cannot
finish without the Server track; the rest of that step can. If a track has to be cut, cut
it whole, and the gutter action goes with the Server track.

### Foundation — build first

- [Step 1 — Activation gate](#step-1-activation-gate-done) — **done**
- [Step 2 — Overhaul the activation gate](#step-2-overhaul-the-activation-gate)
- [Step 3 — Repository reader and field model](#step-3-repository-reader-and-field-model-done) — **done**
- [Step 4 — Match analysis](#step-4-match-analysis-done) — **done**
- [Step 22 — Settings and the detection escape hatch](#step-22-settings-and-the-detection-escape-hatch)
  — out of numerical order deliberately: added after the rest, belongs here. Its first
  half needs only the activation gate overhaul; its detected-configset list waits for the
  repository reader.

### Editor track

- [Step 5 — References, navigation and Find Usages](#step-5-references-navigation-and-find-usages)
- [Step 6 — Inspections](#step-6-inspections-partly-done) — **partly done**
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

## Prerequisites

- [x] JDK 21 toolchain, green build, CI verified.
- [x] Solr and Lucene artifacts resolvable from Maven Central for both supported lines —
      verified: Solr 10.0.0 with Lucene 10.3.2, Solr 9.10.1 with Lucene 9.12.3. Needed by
      [the factory catalog generator](#step-9-factory-catalog-generator).
- [ ] Solr's `-sources` artifacts resolvable for both lines, or a decision to ship the
      catalog without documentation text. Needed by
      [the factory catalog generator](#step-9-factory-catalog-generator), which cannot
      recover documentation from a compiled jar.
- [ ] Local copies of the `_default` and `sample_techproducts_configs` configsets Solr
      ships. They are the clean fixtures for [inspections](#step-6-inspections) and the
      subject of the golden-file gate in [CI gates](#step-20-ci-gates).
- [ ] A local Solr, for manual verification only.
- [ ] **Decision required before
      [the activation gate overhaul](#step-2-overhaul-the-activation-gate):** the
      `org.apache.solr` package namespace. See spec, "What changes in the existing code".

---

## Standing rules for every step

These apply to all steps and are not repeated in each one.

- `./gradlew build` passes, which runs the tests, the coverage floor and the
  documentation gate. Any new public declaration needs KDoc in the same change.
- Nothing on the editor path may contact a server or block the UI thread.
- No automated test may require a Solr that a developer started by hand. Tests use the
  fake HTTP layer, or a container the test starts and stops itself.
- Anything touching persistent project settings extends `SolrConfigsetTestCase`, because
  the platform's test base class shares one project across test classes and leaks state.

---

## Foundation

### Step 1: Activation gate (done)

Stripped the IntelliJ plugin template, re-rooted the code under `org.apache.solr.ide`,
and implemented configset detection so features activate only on recognized files.

**What shipped:**
- `SolrConfigsetDetector` — file-name matching corroborated by directory heuristics. Those
  heuristics were later removed in favour of the dependency gate; see
  [the activation gate overhaul](#step-2-overhaul-the-activation-gate).
- `SolrConfigsetFileKind` — the schema names and `solrconfig.xml`.
- `SolrConfigsetSettings` — manual configset roots and a detection switch, persisted to
  the shared project file with paths collapsed through `PathMacroManager`.
- `SolrBundle`, and `plugin.xml` registering `managed-schema` as XML.

**Success criteria:**
- [x] No template code remains in `src/main`.
- [x] Detection identifies configset files and exposes a manual override.

Two template artefacts survive and are not covered by that criterion:
`src/test/testData/rename/` still holds the scaffold's `foo.xml` / `foo_after.xml`, which
no test reads. [Rename](#step-8-rename) replaces them.

[The activation gate overhaul](#step-2-overhaul-the-activation-gate) reworks this for the
model the spec describes; it is extended, not replaced.

**Acceptance:** No demo step of its own. It is what makes
[demo step 21 — *enable the plugin and reopen*](../../docs/demo/README.md#step-21-enable-the-plugin-and-reopen)
possible at all.

**Dependencies:** none

### Step 2: Overhaul the activation gate (done)

The existing detection code is correct for the feature set it was written for and
insufficient for this one. Fix it before building on it.

**Actions:**
1. Resolve the package namespace question and, if renaming, do it now while the code is
   four files. *Settled: the namespace stays `org.apache.solr.ide`, so there is no rename.
   The spec records the decision and what it leaves open — the `<vendor>` element still
   names the ASF, which is a presentation question this step did not touch.*
2. Establish package layout for the components the spec names: repository reader, field
   model, server client, recognizers, UI. Empty packages with a package-level doc
   comment each, so later work has an obvious home.
3. Extend detection from *is this a configset file* to *which configset does this file
   belong to*. A configset is a directory; the model is per-configset; a project may
   contain several. Keep the per-file check as the activation gate.
4. Cache detection per directory and invalidate on file-system change. `hasDirectoryEvidence`
   currently lists directory children on every call with no cache, which is affordable
   now and not once editor-path features depend on it.
5. Widen `SolrConfigsetFileKind` to the rest of a configset, in two classes that are not
   interchangeable. *Identifying files* — `params.json`, `elevate.xml`, `currency.xml`,
   `enumsConfig.xml` — corroborate a configset and may gate activation. *Resource files*
   — `stopwords.txt`, `synonyms.txt`, `protwords.txt`, `lang/` — are recognized only when
   reached from a filter's resource attribute inside an already-identified configset.
   Their names are far too common outside Solr to be activation evidence on their own,
   and the enum needs to express a directory as well as a file name.
6. Add a per-user settings surface for connections, separate from the existing shared
   project settings. Connection credentials go to PasswordSafe and must never reach a
   shared file. Extend `SolrConfigsetTestCase` to reset it, for the same reason it
   resets the existing settings.

**Success criteria:**
- [x] A file resolves to its owning configset; a project with two configsets keeps them
      distinct.
- [x] Detection results are cached and invalidate correctly on file change.
- [x] The widened file kinds are recognized.
- [x] Connection settings persist per-user; configset roots stay shared.

**What shipped:**
- `SolrConfigsetLocator` — a project service resolving a file to its owning configset by a
  bounded walk up the directory tree, memoized and dropped on VFS *structure* changes or a
  settings change. Content edits deliberately do not invalidate: every signal is a name or
  a directory listing, so typing inside `schema.xml` cannot change the answer.
- `SolrConfigset` — the configset as a value, named for its parent when the root is `conf`
  so that a multi-core project does not display several identical `conf` entries.
- `SolrConfigsetFileRole` — the identifying/resource split.
- `SolrConnectionSettings` in the new `org.apache.solr.ide.server` package — connections
  in the per-user workspace file, secrets in PasswordSafe and never in the persisted state.
- `SolrProjectDetector` — the outer gate, added after the rest of this step: the plugin
  activates only in a project whose dependencies include a Solr client, matched by artifact
  id so that no version appears in the rule. The spec argues it out under "How the plugin
  decides to activate".

**The directory heuristics were removed, not extended.** This step originally restated the
`conf/`-parent and second-recognized-file rules on the directory rather than the file, and
they are now gone entirely. Inside a project that passed the dependency gate a recognized
file name is believed on its own, so the corroboration had nothing left to add and only
produced false negatives. Two consequences worth carrying: a manually marked root now
bypasses the outer gate — it is the *only* way a configset repository with no build file
activates, so it is load-bearing rather than a convenience — and the tests that asserted
corroboration were rewritten rather than deleted, so the old expectations are still visible
as the behaviour they became.

Action 2 landed as documentation rather than as empty directories. Kotlin has no
`package-info`, and Dokka cannot document a package with no declarations, so the planned
packages and their contents are named in `docs/Module.md` instead; each becomes a real
package with a `# Package` section when it gets its first file, as
`org.apache.solr.ide.server` just did.

**Acceptance:**
[demo step 21 — *enable the plugin and reopen*](../../docs/demo/README.md#step-21-enable-the-plugin-and-reopen).
Opening the demo schema activates the plugin, and a project holding two configsets keeps
them apart.

**Dependencies:** [the activation gate](#step-1-activation-gate-done)

### Step 3: Repository reader and field model (done)

The spine. Everything else reads this.

**Actions:**
1. Parse a configset into declared fields, dynamic fields, field types, analyzer chains,
   copy fields, and the request-handler parameters that name fields.
2. Build the field model: merge sources, record the origin of every fact, expose the
   four agreement states — repository only, server only, agreeing, disagreeing. The
   server half stays empty until
   [the server reader](#step-11-http-client-connections-and-the-server-reader) lands;
   build the seam now so it does not have to be retrofitted.
3. Cache per configset, invalidate on file change.
4. Enumerate every configset in the project, not just the one owning a given file.
   `SolrConfigsetLocator` answers per file, on demand, which is right for the editor path
   and insufficient here: a model of *the project's* configsets has to know what they are.
   Bound the scan so it does not walk `node_modules` and build output.
   [Settings and the detection escape hatch](#step-22-settings-and-the-detection-escape-hatch)
   consumes this to show the user what detection found; build it once, here.
5. Test the model directly, with no IDE fixtures where possible. This is the component
   that must be exhaustively correct.

**Success criteria:**
- [x] A configset parses to a complete field model, including dynamic fields.
- [x] The four agreement states are representable and tested with a synthetic server
      half.
- [x] Model rebuilds on file change and not otherwise.
- [x] Every configset in a project is enumerable, verified on a fixture with two of them
      and a directory tree the scan must decline to descend.

**What shipped:**
- `org.apache.solr.ide.model` — `SolrField`, `SolrDynamicField`, `SolrFieldType`,
  `SolrAnalyzerChain`, `SolrCopyField`, `SolrFieldReference`, and `SolrFieldModel` merging
  a repository half with a server half through `SolrFact` / `SolrAgreement`.
- `org.apache.solr.ide.repository` — `SolrSchemaParser` and `SolrConfigParser` (pure,
  string in and facts out), `SolrConfigsetReader` (per-configset cache), and
  `SolrConfigsetScanner` (project-wide enumeration).

**Parsing uses the JDK's DOM, not IntelliJ's XML PSI.** Criterion 5 is what forced it: a
model bound to PSI can only be tested inside an IDE fixture, and this is the component
that has to be exhaustively correct. The later PSI features resolve elements by name at
the point of use, which they must do regardless. Doctypes and external entities are
refused — a cloned repository is not trusted input, and entity resolution would run while
the user is merely opening a file.

**The cache is keyed on the modification stamps of the files actually read**, and takes
text from the in-memory document when one exists. That gives both halves of criterion 3 —
rebuild when the schema changes, and *not* when anything else does — and means an unsaved
edit is in the model before the file is written.

Ambiguous file names are still not evidence here: a directory holding only `schema.xml`
is not enumerated as a configset, matching
[the activation gate overhaul](#step-2-overhaul-the-activation-gate).

Match-capability analysis is deliberately absent — it is
[its own step](#step-4-match-analysis), and depends on nothing this one built beyond the
analyzer chains.

**Acceptance:** No demo step of its own. Nothing from the navigation demos onward works
without it, so it is verified through the steps that consume it.

**Dependencies:** [the activation gate overhaul](#step-2-overhaul-the-activation-gate)

### Step 4: Match analysis (done)

A pure function from analyzer chain to match capability. Independent of everything;
buildable in parallel with
[the repository reader](#step-3-repository-reader-and-field-model).

**Actions:**
1. Classify a field's index-time chain: whole value or tokenized, prefix-capable or not,
   case-sensitive or not.
2. Name the roughly fifteen factories that determine this in code, rather than reading
   them from the generated catalog. The spec's "The factory catalog" says why.
3. Test exhaustively against canonical types and against the orderings that change the
   answer.

**Success criteria:**
- [x] Correct classification for string, tokenized text, EdgeNGram and lowercased
      variants.
- [x] Filter ordering that changes the result is covered by tests.
- [x] Tested directly as a function, not through the annotator that displays it.

**What shipped:** `SolrMatchAnalysis`, a pure function from an index-time chain to a
`SolrMatchCapability`, in `org.apache.solr.ide.model`.

**The capability names the mechanism, not a boolean.** Demo step 32 pre-empts the objection
that wildcards exist — `wid*` works against any indexed field, slowly — so a hint reading
"supports prefix: true" would be simultaneously true and useless. `SolrPrefixSupport` is
therefore `NONE`, `EDGE_NGRAM`, `N_GRAM` or `PATH_HIERARCHY`, and the claim is about
*efficient* index-time matching.

**Every conclusion records the factory behind it.** Demo step 30 invites the room to
disagree, so "tokenized, case-insensitive" is worth much less than the same statement able
to name the filter that made it true.

**An unrecognized factory drops a `confident` flag rather than being assumed harmless.**
A wrong hint here is worse than no hint, so the display can decline to make a claim it
cannot defend. Neutral factories are therefore listed explicitly rather than being whatever
is left over.

The ordering case that changes the answer, and is tested both ways: a word-delimiter filter
after a `KeywordTokenizerFactory` makes the field tokenized despite its tokenizer, and the
evidence names the filter rather than the tokenizer.

**Acceptance:** No demo step of its own. It is the correctness behind
[demo steps 28 to 31 — the match-capability hints](../../docs/demo/README.md#step-28-show-the-hint-on-a-string-field),
which are only ever as good as this.

**Dependencies:** none

### Step 22: Settings and the detection escape hatch

Numbered last because it was added last; it belongs *here*, in Foundation. Step numbers in
this plan are stable anchors that other steps link to, so renumbering to insert one costs
more than the out-of-order number does. Read the section it sits in, not the number.

The activation gate has no user-facing surface at all. `plugin.xml` registers one file-type
mapping and nothing else — no settings page, no action — so the escape hatch the spec
promises exists only in code. `removeManualRoot` has no caller outside tests, which means a
marked root, once committed to the shared project file, cannot be undone through the UI by
the teammate who receives it.

**Actions:**
1. A project settings page under *Languages & Frameworks → Solr*: the detection switch, and
   the marked configset roots with add and remove. Pure wiring over
   `SolrConfigsetSettings`, which is already built and tested.
2. List the *detected* configsets on the same page alongside the marked ones, visibly
   distinguished. This is the half that carries the value — see the spec under "Seeing and
   correcting what activated" for why the silent failure is the one worth attacking. It
   needs a project-wide scan, which `SolrConfigsetLocator` does not do: it answers per file,
   on demand. Build that scan once, in
   [the repository reader](#step-3-repository-reader-and-field-model), which needs to
   enumerate configsets anyway, and consume it here rather than inventing a second sweep.
3. A *Mark Directory as Solr Configset Root* action in the Project View popup menu. The
   string `configset.action.markRoot` is already in the bundle, unused since
   [the activation gate](#step-1-activation-gate-done). Deliberately last of the three: it
   addresses false *negatives*, which the two-identifying-files rule from
   [the activation gate overhaul](#step-2-overhaul-the-activation-gate) already makes rare,
   whereas the list above addresses not knowing which failure you have.
4. A connections page as a sibling, once there are connections worth showing. Not before
   [the HTTP client and server reader](#step-11-http-client-connections-and-the-server-reader)
   — a page listing servers nothing can contact is a promise the plugin cannot keep.

**Success criteria:**
- [ ] Detection can be switched off, and a root marked and unmarked, without editing XML.
- [ ] The page lists detected configsets and marked ones, distinguishably.
- [ ] A root marked by one developer is visible and removable in another's checkout —
      tested by seeding the persisted state directly, as a teammate's commit would.

**Acceptance:**
[demo step 21 — *enable the plugin and reopen*](../../docs/demo/README.md#step-21-enable-the-plugin-and-reopen)
is the fallback if the demo configset does not activate on stage, which is the one failure
the runbook has no other recovery for.

**Dependencies:** [the activation gate overhaul](#step-2-overhaul-the-activation-gate) for
actions 1 and 3; [the repository reader](#step-3-repository-reader-and-field-model) for the
detected-configset list in action 2.

---

## Editor track

### Step 5: References, navigation and Find Usages

**Actions:**
1. Reference providers for: a field's `type` to its field type; `copyField` source and
   destination to fields; request-handler parameters in `solrconfig.xml` to schema
   fields; a filter's resource attribute to the `stopwords.txt` or `synonyms.txt` it
   names.
2. Expose a reference-graph query surface that [inspections](#step-6-inspections) and
   [rename](#step-8-rename) reuse.
3. Reference tests asserting resolve targets on representative configsets.

**Success criteria:**
- [ ] All four reference kinds resolve; Find Usages returns every reference.

**Acceptance:** demo steps
[22 — *navigate to a field type*](../../docs/demo/README.md#step-22-navigate-to-a-field-type),
[23 — *navigate along a copyField*](../../docs/demo/README.md#step-23-navigate-along-a-copyfield),
[24 — *cross the file boundary*](../../docs/demo/README.md#step-24-cross-the-file-boundary)
and [27 — *Find Usages on a field type*](../../docs/demo/README.md#step-27-find-usages-on-a-field-type).

**Dependencies:** [the repository reader and field model](#step-3-repository-reader-and-field-model)

### Step 6: Inspections (partly done)

Where the zero-false-positive requirement gets teeth.

**Actions:**
1. Implement: dangling `copyField` source or target; handler naming a nonexistent field;
   relevance parameters on non-indexed fields; unused field types; known-bad analyzer
   chain orderings; configuration elements removed in the targeted Solr line.
2. A description file per inspection, written as user-facing prose — it is also the
   published catalog entry.
3. Test each on both flagged and clean fixtures.

**Success criteria:**
- [x] Every inspection fires on crafted-bad fixtures and on nothing clean — for the three
      that have landed.

**What shipped:** the three reference inspections — a dangling `copyField` source or
destination, a field naming an undeclared field type, and a handler parameter naming a
field the schema does not declare.

**Taken before references and navigation, which this step nominally depends on.** That
dependency holds only for inspections written as unresolved-reference checks; driven off
the field model instead, none of them need reference infrastructure. The same reasoning let
the match hints ship early.

**The clean fixtures carry more weight than the flagged ones.** `fl` alone legitimately
contains `score`, `*`, `[docid]`, `max(price,0)` and `alias:name`, none of which any schema
declares — so the parser now excludes square brackets, and the inspections skip the names
Solr supplies itself. A glob `copyField` source is not reported either way, because whether
a dynamic field matches it is not a question the schema alone answers.

Still to come in this step: relevance parameters on non-indexed fields, unused field types,
known-bad analyzer chain orderings, and configuration elements removed in the targeted Solr
line. The last of those needs the version data the factory catalog carries.

**Acceptance:** demo steps
[25 — *show the dangling reference*](../../docs/demo/README.md#step-25-show-the-dangling-reference)
and [26 — *break something live*](../../docs/demo/README.md#step-26-break-something-live).
The planted dangling copy rule is underlined, and deleting a referenced field flags its
rule immediately.

**Dependencies:** [references and navigation](#step-5-references-navigation-and-find-usages)

### Step 7: Match hints and quick-fixes

Taken out of order, ahead of the Editor track's earlier steps. Its two dependencies are
both met, it needs no PSI reference infrastructure, and it is the first step that produces
anything a user can see — four steps of foundation had shipped with no exit to the UI.

**Actions:**
1. Inlay hints surfacing each field's match capability from
   [match analysis](#step-4-match-analysis), inline rather than on hover, so the demo does
   not depend on the presenter's mouse.
2. Quick documentation on a field's `type`, covering what the type is, what its analyzer
   chain does, and what a field of it can match — plus a Reference Guide link for the
   version the configset targets. The spec argues this out under "What quick documentation
   covers"; the part that matters here is that this half needs the model and match
   analysis, not the factory catalog, so it does not wait for
   [the catalog](#step-9-factory-catalog-generator).
3. Intentions adding a missing capability: an `_exact` companion plus `copyField`, an
   EdgeNGram-backed `_prefix` field. Phrased as efficient index-time support — the spec
   explains the wildcard caveat behind that wording.
4. Edit the file directly. No provenance check, no warning, no redirect.
5. Say nothing where match analysis is not confident. An unrecognized factory means the
   chain was not fully understood, and a wrong hint is worse than none.

**Success criteria:**
- [ ] Fields annotated correctly for canonical types.
- [ ] No hint is shown where match analysis is not confident.
- [ ] Quick documentation on a field's type resolves, and its Reference Guide link names
      the version the configset targets.
- [ ] Quick-fixes produce valid configset edits.

**Acceptance:** demo steps
[28 to 33 — the hints and the generated fix](../../docs/demo/README.md#step-28-show-the-hint-on-a-string-field).
A `string` field reads as whole-value and case-sensitive, a tokenised field does not offer
prefix matching, an edge-n-gram field does, and Alt-Enter generates the companion field
with its copy rule.

**Dependencies:** [the repository reader and field model](#step-3-repository-reader-and-field-model),
[match analysis](#step-4-match-analysis)

### Step 8: Rename

**Actions:**
1. Rename fields and field types, updating every reference through the graph built by
   [references and navigation](#step-5-references-navigation-and-find-usages).
2. Replace the plugin scaffold's leftover `src/test/testData/rename/` placeholders —
   `foo.xml` and `foo_after.xml`, which no test reads — with configset before/after pairs
   asserting no dangling references remain.

**Success criteria:**
- [ ] Every resolved reference updates; no dangling references after rename.
- [ ] No scaffold fixtures remain under `src/test/testData/`.

**Acceptance:**
[demo step 34 — *rename across files*](../../docs/demo/README.md#step-34-rename-across-files).
Renaming a field updates its copy rules *and* the `qf` line in `solrconfig.xml`.

**Dependencies:** [references and navigation](#step-5-references-navigation-and-find-usages)

### Step 9: Factory catalog generator

**Actions:**
1. A Gradle task that reads the Solr and Lucene artifacts per supported line and emits a
   catalog of factories, their attributes and their documentation. Runs in the build,
   where loading Solr classes is ordinary. It needs three sources, because no single one
   carries all three pieces:
   - **Factories** — reflection over the artifact jars, via the SPI service files Solr
     already ships to name them.
   - **Attributes** — bytecode analysis of each factory's constructor, collecting the
     string constants passed to `get`, `getInt`, `getBoolean` and friends. Reflection
     cannot see these: a factory reads its attributes out of a `Map<String, String>`, so
     the names are literals in the constructor body and appear as neither fields nor
     annotations.
   - **Documentation** — the `-sources` artifacts. Javadoc is not retained in bytecode,
     so a compiled jar cannot supply it at all.
2. Declare supported lines in one place so adding or dropping one is a single edit.
3. A loader selecting the entry by: connected server, then `<luceneMatchVersion>`
   translated through the Lucene-to-Solr table, then newest supported line. Record which
   source answered.
4. Only ALv2-compatible documentation content may be embedded. If the `-sources`
   artifacts turn out not to be resolvable for a line, ship that line's catalog without
   documentation rather than hand-writing it — the spec's reason for generating this at
   all is that the list is too large to maintain by hand.

**Success criteria:**
- [ ] Catalog generated at build time for both supported lines and present on the plugin
      classpath.
- [ ] `wordDelimiterGraph` exposes all twelve of its attributes. This is the criterion
      that proves the constructor-bytecode pass works, because reflection over fields
      produces a plausible short list instead of failing outright.
- [ ] Selection order is correct and the answering source is recorded.
- [ ] The catalog regenerates from a clean build.

**Acceptance:**
[demo step 69 — *attribute completion on a factory with many options*](../../docs/demo/README.md#step-69-attribute-completion-on-a-factory-with-many-options)
is the one to run by hand, for the reason in the criterion above.

**Dependencies:** [the activation gate overhaul](#step-2-overhaul-the-activation-gate)

### Step 10: Completion, validation and quick documentation

**Partly done.** Completion for the schema positions whose valid set is closed — a field's
`type` from the declared field types, a `copyField` source and destination from the declared
fields and dynamic patterns, and `true`/`false` for the boolean properties — landed ahead of
this step, because none of it needs the catalog. Positions where any value is legal are left
to the platform: a partial list implies the values not on it are wrong. What remains here is
the catalog-backed half, which is what the dependency below is really about.

**Actions:**
1. Completion for field types, factories, their attributes, and field attributes.
2. Structural validation flagging unknown factories and invalid attributes.
3. Dynamic field pattern awareness.
4. Documentation provider keyed by factory and attribute, surfacing which catalog source
   answered. The *field type* half of quick documentation does not belong here — it needs
   the model and match analysis rather than the catalog, so it ships with
   [match hints](#step-7-match-hints-and-quick-fixes) instead. Only the catalog-backed
   half waits for this step.

**Success criteria:**
- [ ] Completion and validation work against the catalog.
- [ ] Quick documentation resolves for factories and attributes.

**Acceptance:** demo steps
[68 — *completion inside an analyser chain*](../../docs/demo/README.md#step-68-completion-inside-an-analyser-chain),
[69 — *attribute completion on a factory with many options*](../../docs/demo/README.md#step-69-attribute-completion-on-a-factory-with-many-options)
and [70 — *quick documentation on a factory*](../../docs/demo/README.md#step-70-quick-documentation-on-a-factory).

**Dependencies:** [the repository reader and field model](#step-3-repository-reader-and-field-model),
[the factory catalog generator](#step-9-factory-catalog-generator)

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
   response, and unrecognized server version. This fixture is part of the step.
6. **A contract test per supported line against Testcontainers** — `solr:10.0.0` and
   `solr:9.10.1`, pinned by tag, never `latest`. The fake layer can only replay responses
   somebody imagined, which is the wrong instrument for the risk that a real server
   returns a shape nobody anticipated. This keeps the fake honest as Solr's wire format
   moves, and it still satisfies the standing rule: the container is started by the test,
   not by a developer.
7. Every call asynchronous and timeout-bounded; nothing on the editor path.

**Success criteria:**
- [ ] A connection can be created, stored and used; credentials never reach project
      files.
- [ ] The server half of the model populates.
- [ ] All five failure modes tested against the fake layer.
- [ ] The reader parses what a real Solr of each supported line actually returns.
- [ ] Server state refreshes only on request or connection change — never on a timer.

**Acceptance:**
[demo step 35 — *connect to a server*](../../docs/demo/README.md#step-35-connect-to-a-server).

**Dependencies:** [the repository reader and field model](#step-3-repository-reader-and-field-model)

### Step 12: Collections tool window

**Actions:**
1. Browse collections, cores, shards, replicas, aliases, and the server's actual fields.
2. Show the selected connection persistently.
3. Report failure inline, once, showing Solr's own message.

**Success criteria:**
- [ ] The topology renders; the selected connection is always visible.
- [ ] An unreachable server degrades to an inline message, not a popup.

**Acceptance:**
[demo step 36 — *browse what is actually there*](../../docs/demo/README.md#step-36-browse-what-is-actually-there).
Collections, cores and the server's actual fields render, and the selected connection
stays visible.

**Dependencies:** [the server reader](#step-11-http-client-connections-and-the-server-reader)

### Step 13: Query console

**Actions:**
1. Field completion from the model.
2. Results as a table; scoring explanation as an expandable tree.
3. History, and queries saveable into the project so they are version-controllable.

**Success criteria:**
- [ ] Queries run and render structurally; completion works with no configset present.
- [ ] Saved queries round-trip through the project.

**Acceptance:** demo steps
[37 — *run a query*](../../docs/demo/README.md#step-37-run-a-query) and
[38 — *show why a document scored*](../../docs/demo/README.md#step-38-show-why-a-document-scored).
Completion comes from the live schema, results render as a table, and the scoring
explanation expands as a tree.

**Dependencies:** [the server reader](#step-11-http-client-connections-and-the-server-reader)

### Step 14: Drift view, upload and reload

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

**Acceptance:** demo steps
[39 — *the drift demo*](../../docs/demo/README.md#step-39-the-drift-demo) and
[40 — *resolve it*](../../docs/demo/README.md#step-40-resolve-it). A field added to the
repository and not deployed shows as a difference; upload and reload clear it, naming the
target server first.

**Dependencies:** [the repository reader and field model](#step-3-repository-reader-and-field-model),
[the server reader](#step-11-http-client-connections-and-the-server-reader)

### Step 15: Indexing test documents

**Actions:**
1. A document editor with schema-aware completion and validation.
2. Sample document generation from the schema.
3. Index into a selected collection with explicit commit behavior and confirmation.

**Success criteria:**
- [ ] Documents can be authored with completion and indexed on explicit invocation.

**Acceptance:**
[demo step 71 — *author and index a test document*](../../docs/demo/README.md#step-71-author-and-index-a-test-document).
The document indexes into the local collection and is then findable.

**Dependencies:** [the server reader](#step-11-http-client-connections-and-the-server-reader)

---

## Code track

### Step 16: Recognizer interface and SolrJ

**Actions:**
1. Define the recognizer interface: reports endpoints and field references. Keep it
   minimal — [framework configuration](#step-18-framework-configuration) and
   [Apache Camel](#step-19-apache-camel) depend on it being right. An endpoint is a URL
   *and* the credential that goes with it, since framework configuration resolves both
   from the same profile; a reported endpoint that cannot carry a username forces that
   step to bolt one on afterwards.
2. Make the interface declare the library each recognizer needs, and gate activation on
   the *module's* dependencies rather than on the file being edited — no Solr client on
   the classpath, no SolrJ recognizer. The spec argues this out under "Recognizing Solr
   usage"; what matters here is the ordering. It belongs in the interface on the first
   day, because a recognizer written without it assumes it may inspect anything, and
   retrofitting the gate afterwards means revisiting every recognizer built on top.
3. SolrJ recognizer: client construction supplies endpoints; `SolrQuery` builder calls,
   raw parameter strings, `SolrInputDocument` field names and `@Field` annotations
   supply field references.
4. Inspection flagging field references absent from the model, and completion for them.
5. **Silence where resolution fails.** Assert this in tests explicitly — precision
   matters more than recall.

**Success criteria:**
- [ ] Field references resolve in builder calls, raw strings, document building and bean
      annotations.
- [ ] Unresolvable constructs produce no warning.
- [ ] A module with no Solr client on its classpath produces no findings at all, asserted
      on a fixture of two modules where only one depends on SolrJ.

**Acceptance:** demo steps
[41 to 44 — the field-name checks in Java](../../docs/demo/README.md#step-41-return-to-the-opening-bug),
and [47 — *volunteer the limitation*](../../docs/demo/README.md#step-47-volunteer-the-limitation).
The planted typo in a filter query is flagged, so is a field that never existed, so is the
misspelled `@Field` annotation. Step 47 matters most: constructs the plugin cannot resolve
produce no warning at all.

**Dependencies:** [the repository reader and field model](#step-3-repository-reader-and-field-model)

### Step 17: Query syntax and the console bridge

**Actions:**
1. Treat the query string inside a literal as its own language, so it gets structure and
   highlighting.
2. Gutter action running that query in the console against a selected connection.
3. Navigation from a field name in code to its schema definition.

**Success criteria:**
- [ ] Query strings render structurally inside Java and Kotlin literals.
- [ ] The gutter action runs the query; navigation resolves when a configset is present.

**Acceptance:** demo steps
[45 — *show the query as a language*](../../docs/demo/README.md#step-45-show-the-query-as-a-language)
and [46 — *run it from where it lives*](../../docs/demo/README.md#step-46-run-it-from-where-it-lives).

**Dependencies:** [the recognizer interface and SolrJ](#step-16-recognizer-interface-and-solrj);
the gutter action additionally needs [the query console](#step-13-query-console), which is
the Code track's one dependency on the Server track. The language and navigation work does
not.

### Step 18: Framework configuration

**Actions:**
1. Verify which platform framework-configuration APIs are available to plugins and in
   which editions. Prefer the platform's model over parsing configuration directly.
2. Declare optional dependencies so these features appear when the supporting
   functionality is present and the plugin loads normally when it is not.
3. Resolve a Solr URL **and its credentials** per profile with each framework's own
   precedence: Spring Boot
   profile files, **Quarkus inline `%profile.` prefixes in a single file**, Micronaut
   environments, MicroProfile ordinals.
4. Offer discovered endpoints as connection candidates. Never connect automatically.
5. Carry the credential with the endpoint. A username found beside the URL in a profile
   belongs to that profile's candidate, and on confirmation the secret is copied into
   PasswordSafe rather than re-read from the configuration file on each use. The spec sets
   the rules under "Recognizing Solr usage"; the consequence here is that a candidate is a
   URL *and* a credential, so the recognizer interface must be able to report both — which
   is why [the recognizer interface](#step-16-recognizer-interface-and-solrj) has to know
   about it before this step starts.
6. **Real project fixtures per framework**, not synthetic strings.

**Success criteria:**
- [ ] Boot profile files and Quarkus inline prefixes both resolve correctly.
- [ ] The plugin loads and functions with no framework support present.
- [ ] Discovered endpoints are offered, never adopted silently.
- [ ] Switching the active profile changes the offered username as well as the URL,
      asserted on the demo fixture, which carries a `dev` and a `staging` profile.
- [ ] A secret from a configuration file reaches PasswordSafe only after the user
      confirms, and never reaches the shared project file.

**Scope of the demo.** Only Spring gets a demo step. Each additional framework would need
its own fixture project and its own runtime on stage to show what the Spring fixture
already shows, and the recognizer is the same code either way. Quarkus, Micronaut and
MicroProfile are accepted by fixture tests in this step instead. Quarkus is the one to get
right, for the reason the spec gives under "Recognizing Solr usage".

**Acceptance:**
[demo step 35 — *connect to a server*](../../docs/demo/README.md#step-35-connect-to-a-server),
Spring only. The URL is offered by following `${app.solr.url}` from the SolrJ client bean
into the active profile, and is never connected to automatically. The other three
frameworks are accepted by their fixture tests here.

**Dependencies:** [the recognizer interface and SolrJ](#step-16-recognizer-interface-and-solrj)

### Step 19: Apache Camel

**Actions:**
1. Recognize Solr endpoint URIs in Camel routes as connection candidates.
2. Validate the URI's component options against the known set.
3. Check field references in route parameters and document construction.
4. Java and XML route definitions first. Where the IDE or an installed plugin models
   routes, read that model rather than writing another URI parser.

**Success criteria:**
- [ ] Endpoints recognized from Java and XML routes; options validated.

**Acceptance:** No demo step, for the same reason the other frameworks have none — a Camel
demo needs its own fixture project and a running route to show what a fixture test shows
in a second. Accepted here by fixture tests: a Solr endpoint in a Java route and in an XML
route is offered as a connection candidate, and a misspelled URI option is flagged.

**Dependencies:** [the recognizer interface and SolrJ](#step-16-recognizer-interface-and-solrj)

---

## Cross-cutting

### Step 20: CI gates

**Actions:**
1. Add `_default` and `sample_techproducts_configs` as fixtures.
2. Golden-file test running every inspection over both, asserting zero highlights. This
   gate must be in place before the release, not after.
3. Assert every registered inspection has a description file.
4. Assert documented versions match the compatibility matrix. This one only has teeth once
   [documentation](#step-21-documentation) has written the matrix; land the check here and
   expect it to pass vacuously until then.
5. Catalog tests per supported line.

**Success criteria:**
- [ ] Zero false positives on both shipped configsets, enforced in CI.
- [ ] Missing description files and version drift both fail the build.

**Acceptance:** No demo step — this step *is* the automated gate. It is what stops the
demo passing while the suite quietly rots.

**Dependencies:** [inspections](#step-6-inspections) for the golden-file gate;
[the factory catalog generator](#step-9-factory-catalog-generator) for catalog tests

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

**Acceptance:** No demo step. The compatibility matrix written here is what makes the
version-drift check in [CI gates](#step-20-ci-gates) meaningful rather than vacuous.

**Dependencies:** [inspections](#step-6-inspections) for the catalog content,
[CI gates](#step-20-ci-gates) for the checks that police it

---

## Validation checklist

The cross-track invariants no single step owns. Each step's own success criteria cover the
rest.

- [x] Build, CI, coverage and documentation gates in place.
- [ ] Package namespace decided.
- [ ] Editor features work with no connection.
- [ ] Server features work with no configset in the project.
- [ ] Code features stay silent where they cannot resolve.
- [ ] Zero false positives on both shipped configsets, CI-enforced.
- [ ] No write occurs without explicit human invocation naming its target.
- [ ] Release documentation published.

## Risks

Mitigations live in the steps; only the first entry states one, because it belongs to no
step.

- **Scope exceeds what can be polished.** This plan is large and the quality bar is
  explicit. The track structure is the mitigation: Editor, Server and Code can each reach
  a shippable state independently, so if something must be cut, cut a whole track rather
  than leaving three half-built.
- **Coverage floor blocks UI work.** Six of the remaining steps land tool windows,
  annotators and PSI code that is awkward to unit-test, against an 80% Kover floor bound
  to `check`. Decide the response before it bites — package-level exclusions, or moving
  the floor to changed lines and letting SonarCloud's new-code gate be the real defence.
- **Code analysis produces false positives** —
  [the recognizer interface and SolrJ](#step-16-recognizer-interface-and-solrj).
- **Framework configuration works only on the author's machine** —
  [framework configuration](#step-18-framework-configuration).
- **A server version the plugin has never seen** —
  [the server reader](#step-11-http-client-connections-and-the-server-reader).
- **Reference resolution edge cases cause dangling renames** —
  [references and navigation](#step-5-references-navigation-and-find-usages), which is
  unit-tested before [rename](#step-8-rename) consumes it.

## References

- Spec: `specs/0002-solr-intellij-plugin.md`
- Demo runbook and acceptance harness: `docs/demo/README.md`
- Configuration file survey: `docs/solr-configuration-files.md`
- Plugin development tutorial: `docs/modern-intellij-plugin-development.md`
- IntelliJ Platform SDK: https://plugins.jetbrains.com/docs/intellij/
