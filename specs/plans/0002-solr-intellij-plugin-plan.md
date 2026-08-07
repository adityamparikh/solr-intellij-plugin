---
specbuddy-type: plan
spec-file: specs/0002-solr-intellij-plugin.md
---

# Implementation Plan: Solr IntelliJ Plugin

## Overview

Deliver the plugin described in the spec: configuration intelligence, a live Solr connection, and Java/Kotlin code
support, unified by one field model.

**Current state.** The build, CI and documentation tooling are complete and stay as they are. Foundation is built apart
from its settings page. Of the three tracks the work then splits into, only the Editor one has moved, and what it built
is real rather than scaffolding: a configset parses into a field model, and that model reaches the screen as
match-capability inlay hints, quick documentation on schema elements, field types and
`class` values, eight inspections — several of them offering the valid names rather than only reporting the invalid one —
and completion for both the schema's own vocabulary and the catalog's classes and factory attributes. Two Alt-Enter
intentions now *write* rather than only describe — the `_prefix` and `_exact` companion patterns, generated from the
field that lacks them — which is the first time the plugin edits a configset rather than explaining one. The generated
catalog now carries each class's attributes with their value types — and, where the bytecode proves them, their literal
defaults and required markers — and covers the field-type classes as well as the factories. On the schema side, a
field's effective properties resolve against the
`version` attribute on the schema's own root element, which is what decides several of Solr's defaults and is a third
version number beside the Solr line and `luceneMatchVersion`.

**Two facts were recorded before anything showed them, and now two surfaces do.** The catalog's attribute defaults and
required markers went further than the class popup's `Accepts` table, which renders a name and a value type and stops;
[the per-attribute hover and the complete-configuration popup](#step-10-completion-validation-and-quick-documentation-in-progress)
now render the rest, the first for the attribute under the caret and the second for every attribute a factory tag
accepts. [The dimmed restated default](#step-26-showing-that-an-attribute-restates-the-default) is still unbuilt, and it
reads the same column to make a different claim: not what Solr will supply, but that a written value need not have been
written at all.

**The Server and Code tracks have not started**, which is two of the spec's three pillars.
`server/` holds `SolrConnectionSettings` and nothing else — no HTTP client, no tool window, no query console — and no
recognizer exists, so a Java or Kotlin file gets nothing at all. Neither track is blocked by the other, and neither is
blocked by the Editor track; they are simply unstarted. [The build order](#build-order) explains what cutting one whole
would cost.

**What this plan dropped.** The provenance classification, the API-first write gating, and the runtime derivation of
reference data from project jars are gone. The spec records why, in "What this replaces" and "The factory catalog";
roughly a third of the previous plan existed to serve those decisions.

**Cross-reference convention.** Reference a step or a section by its name, never by a number or a line alone. This holds
in both directions — a reference to the demo runbook carries the demo step's title too, because the runbook has its own
numbering and a bare number silently means the wrong thing.

## Build order

The activation gate overhaul unblocks everything except match analysis, which depends on nothing and can start
immediately. After the repository reader and field model, the work splits into three tracks. Within a track the order is
the dependency order.

The tracks meet twice. The drift view needs both the field model and the server reader — it is the feature that most
justifies building both halves. The gutter action inside *query syntax and the console bridge* needs the query console,
so the Code track cannot finish without the Server track; the rest of that step can. If a track has to be cut, cut it
whole, and the gutter action goes with the Server track.

### Foundation — build first

- [Step 1 — Activation gate](#step-1-activation-gate-done) — **done**
- [Step 2 — Overhaul the activation gate](#step-2-overhaul-the-activation-gate-done) — **done**
- [Step 3 — Repository reader and field model](#step-3-repository-reader-and-field-model-done) — **done**
- [Step 4 — Match analysis](#step-4-match-analysis-done) — **done**
- [Step 22 — Settings and the detection escape hatch](#step-22-settings-and-the-detection-escape-hatch)
  — out of numerical order deliberately: added after the rest, belongs here. Its first half needs only the activation
  gate overhaul; its detected-configset list waits for the repository reader.

### Editor track

- [Step 5 — References, navigation and Find Usages](#step-5-references-navigation-and-find-usages-done) — **done**
- [Step 6 — Inspections](#step-6-inspections-in-progress) — **in progress**; six of seven inspections shipped, one
  remains and it waits on the catalog
- [Step 7 — Match hints and quick-fixes](#step-7-match-hints-and-quick-fixes-done) — **done**
- [Step 23 — Explaining and correcting what is already on screen](#step-23-explaining-and-correcting-what-is-already-on-screen-done) —
  **done**
  — out of numerical order deliberately: added after the rest, belongs here. Needs nothing the catalog provides.
- [Step 24 — Completing the schema's own vocabulary](#step-24-completing-the-schemas-own-vocabulary-done) — **done**
  — likewise. Corrects a dependency that parked field attribute completion behind the catalog, which it never needed.
- [Step 26 — Showing that an attribute restates the default](#step-26-showing-that-an-attribute-restates-the-default)
  — likewise added late; belongs beside the two above. Its field half needs only the property table; its factory half
  waits on the catalog carrying defaults.
- [Step 27 — Saying what a property's value means](#step-27-saying-what-a-propertys-value-means-done) — **done**
  — likewise added late; belongs beside the three above. Extends the match-hint provider and the documentation provider
  both, so it needs the property table plus the two steps that already extend them.
- [Step 25 — solrconfig.xml as a first-class surface](#step-25-solrconfigxml-as-a-first-class-surface)
  — the largest step here, and entirely behind the catalog. Split it when it starts.
- [Step 28 — Declarations as targets](#step-28-declarations-as-targets)
  — likewise added late, and it belongs *before* rename rather than beside the popup work above. It
  closes a criterion [references and navigation](#step-5-references-navigation-and-find-usages-done)
  claimed and does not have, and it builds the target rename would otherwise have to build first.
- [Step 8 — Rename](#step-8-rename)
- [Step 9 — Factory catalog generator](#step-9-factory-catalog-generator-in-progress) — **in progress**; the generator
  is built and every fact it emits is asserted, and what is left is the server arm of version selection, which belongs
  to the Server track
- [Step 10 — Completion, validation and quick documentation](#step-10-completion-validation-and-quick-documentation-in-progress) —
  **in progress**; completion, validation, the class-value popup, the per-attribute hover and the factory's
  complete-configuration popup have all shipped, and every success criterion below the step is met. What holds the
  heading is action 3 alone — dynamic field pattern awareness — which no criterion states and no change so far has
  claimed

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
- [x] Solr and Lucene artifacts resolvable from Maven Central for both supported lines — verified: Solr 10.0.0 with
  Lucene 10.3.2, Solr 9.10.1 with Lucene 9.12.3. Needed by
  [the factory catalog generator](#step-9-factory-catalog-generator-in-progress).
- [x] Solr's `-sources` artifacts resolvable for both lines, or a decision to ship the catalog without documentation
  text. Needed by
  [the factory catalog generator](#step-9-factory-catalog-generator-in-progress), which cannot recover documentation
  from a compiled jar. **Verified resolvable for both lines**, and wired in: see Step 9 for what a resolved `-sources`
  jar can and cannot supply.
- [ ] Local copies of the `_default` and `sample_techproducts_configs` configsets Solr ships, vendored verbatim under
  `src/test/testData/configsets/<name>/conf/` and recording the Solr release they came from. The gate asserts *clean
  against what Solr itself ships*, which means nothing without naming which Solr. They are the clean fixtures
  for [inspections](#step-6-inspections-in-progress) and the subject of the golden-file gate
  in [CI gates](#step-20-ci-gates). This is also the first thing in the repository to need `testData` at all — every
  test today builds its fixture inline with
  `configureByText`, and `src/test/testData/` still holds nothing but the scaffold files
  [rename](#step-8-rename) is due to replace.
- [ ] A local Solr, for manual verification only.
- [x] Package namespace settled — it stays `org.apache.solr.ide`, so there is no rename.
  [The activation gate overhaul](#step-2-overhaul-the-activation-gate-done) is the step that had to know and is where
  the reasoning lives, including what the decision leaves open. This line records only that the question is closed,
  because it read as still open long after it was answered.

---

## Standing rules for every step

These apply to all steps and are not repeated in each one.

- `./gradlew build` passes, which runs the tests, the coverage floor and the documentation gate. Any new public
  declaration needs KDoc in the same change.
- Nothing on the editor path may contact a server or block the UI thread.
- No automated test may require a Solr that a developer started by hand. Tests use the fake HTTP layer, or a container
  the test starts and stops itself.
- Anything touching persistent project settings extends `SolrConfigsetTestCase`, because the platform's test base class
  shares one project across test classes and leaks state.
- **Three test tiers, and the fourth is deliberately absent.** Pure tests over
  `org.apache.solr.ide.model` and the parsers, which import nothing from the platform. Light fixture tests on
  `BasePlatformTestCase` for anything with PSI in it — these boot a headless IDE and run the platform's real analysis
  pass, so they are integration tests despite sitting beside the unit ones, and `checkHighlighting` failing on
  *unmarked*
  highlights is what makes the zero-false-positive bar enforceable per test rather than only in CI. Heavy tests only
  where a light project cannot express the fixture;
  [the recognizer interface](#step-16-recognizer-interface-and-solrj) is the one step that says so. **No test drives a
  running IDE.** Neither `intellij-ide-starter` nor Remote Robot is used: every claim this plugin makes is a highlight,
  a resolve target, a completion list or a model value, and all four are assertable headlessly. A tool window that
  merely renders is not a claim worth a flaky test, and a step that seems to need one has put logic in a component
  instead of in the model. This is also the answer to the coverage-floor risk below — decide what tests UI code and the
  floor stops being an argument about Kover exclusions.

---

## Foundation

### Step 1: Activation gate (done)

Stripped the IntelliJ plugin template, re-rooted the code under `org.apache.solr.ide`, and implemented configset
detection so features activate only on recognized files.

**What shipped:**

- `SolrConfigsetDetector` — file-name matching corroborated by directory heuristics. Those heuristics were later removed
  in favour of the dependency gate; see
  [the activation gate overhaul](#step-2-overhaul-the-activation-gate-done).
- `SolrConfigsetFileKind` — the schema names and `solrconfig.xml`.
- `SolrConfigsetSettings` — manual configset roots and a detection switch, persisted to the shared project file with
  paths collapsed through `PathMacroManager`.
- `SolrBundle`, and `plugin.xml` registering `managed-schema` as XML.

**Success criteria:**

- [x] No template code remains in `src/main`.
- [x] Detection identifies configset files and exposes a manual override.

Two template artefacts survive and are not covered by that criterion:
`src/test/testData/rename/` still holds the scaffold's `foo.xml` / `foo_after.xml`, which no test
reads. [Rename](#step-8-rename) replaces them.

[The activation gate overhaul](#step-2-overhaul-the-activation-gate-done) reworks this for the model the spec describes;
it is extended, not replaced.

**Acceptance:** No demo step of its own. It is what makes
[demo step 21 — *enable the plugin and reopen*](../../docs/demo/README.md#step-21-enable-the-plugin-and-reopen)
possible at all.

**Dependencies:** none

### Step 2: Overhaul the activation gate (done)

The existing detection code is correct for the feature set it was written for and insufficient for this one. Fix it
before building on it.

**Actions:**

1. Resolve the package namespace question and, if renaming, do it now while the code is four files. *Settled: the
   namespace stays `org.apache.solr.ide`, so there is no rename. The spec records the decision and what it leaves open —
   the `<vendor>` element still names the ASF, which is a presentation question this step did not touch.*
2. Establish package layout for the components the spec names: repository reader, field model, server client,
   recognizers, UI. Empty packages with a package-level doc comment each, so later work has an obvious home.
3. Extend detection from *is this a configset file* to *which configset does this file belong to*. A configset is a
   directory; the model is per-configset; a project may contain several. Keep the per-file check as the activation gate.
4. Cache detection per directory and invalidate on file-system change. `hasDirectoryEvidence`
   currently lists directory children on every call with no cache, which is affordable now and not once editor-path
   features depend on it.
5. Widen `SolrConfigsetFileKind` to the rest of a configset, in two classes that are not interchangeable. *Identifying
   files* — `params.json`, `elevate.xml`, `currency.xml`,
   `enumsConfig.xml` — corroborate a configset and may gate activation. *Resource files*
   — `stopwords.txt`, `synonyms.txt`, `protwords.txt`, `lang/` — are recognized only when reached from a filter's
   resource attribute inside an already-identified configset. Their names are far too common outside Solr to be
   activation evidence on their own, and the enum needs to express a directory as well as a file name.
6. Add a per-user settings surface for connections, separate from the existing shared project settings. Connection
   credentials go to PasswordSafe and must never reach a shared file. Extend `SolrConfigsetTestCase` to reset it, for
   the same reason it resets the existing settings.

**Success criteria:**

- [x] A file resolves to its owning configset; a project with two configsets keeps them distinct.
- [x] Detection results are cached and invalidate correctly on file change.
- [x] The widened file kinds are recognized.
- [x] Connection settings persist per-user; configset roots stay shared.

**What shipped:**

- `SolrConfigsetLocator` — a project service resolving a file to its owning configset by a bounded walk up the directory
  tree, memoized and dropped on VFS *structure* changes or a settings change. Content edits deliberately do not
  invalidate: every signal is a name or a directory listing, so typing inside `schema.xml` cannot change the answer.
- `SolrConfigset` — the configset as a value, named for its parent when the root is `conf`
  so that a multi-core project does not display several identical `conf` entries.
- `SolrConfigsetFileRole` — the identifying/resource split.
- `SolrConnectionSettings` in the new `org.apache.solr.ide.server` package — connections in the per-user workspace file,
  secrets in PasswordSafe and never in the persisted state.
- `SolrProjectDetector` — the outer gate, added after the rest of this step: the plugin activates only in a project
  whose dependencies include a Solr client, matched by artifact id so that no version appears in the rule. The spec
  argues it out under "How the plugin decides to activate".

**The directory heuristics were removed, not extended.** This step originally restated the
`conf/`-parent and second-recognized-file rules on the directory rather than the file, and they are now gone entirely.
Inside a project that passed the dependency gate a recognized file name is believed on its own, so the corroboration had
nothing left to add and only produced false negatives. Two consequences worth carrying: a manually marked root now
bypasses the outer gate — it is the *only* way a configset repository with no build file activates, so it is
load-bearing rather than a convenience — and the tests that asserted corroboration were rewritten rather than deleted,
so the old expectations are still visible as the behaviour they became.

Action 2 landed as documentation rather than as empty directories. Kotlin has no
`package-info`, and Dokka cannot document a package with no declarations, so the planned packages and their contents are
named in `docs/Module.md` instead; each becomes a real package with a `# Package` section when it gets its first file,
as
`org.apache.solr.ide.server` just did.

**Acceptance:**
[demo step 21 — *enable the plugin and reopen*](../../docs/demo/README.md#step-21-enable-the-plugin-and-reopen). Opening
the demo schema activates the plugin, and a project holding two configsets keeps them apart.

**Dependencies:** [the activation gate](#step-1-activation-gate-done)

### Step 3: Repository reader and field model (done)

The spine. Everything else reads this.

**Actions:**

1. Parse a configset into declared fields, dynamic fields, field types, analyzer chains, copy fields, and the
   request-handler parameters that name fields.
2. Build the field model: merge sources, record the origin of every fact, expose the four agreement states — repository
   only, server only, agreeing, disagreeing. The server half stays empty until
   [the server reader](#step-11-http-client-connections-and-the-server-reader) lands; build the seam now so it does not
   have to be retrofitted.
3. Cache per configset, invalidate on file change.
4. Enumerate every configset in the project, not just the one owning a given file.
   `SolrConfigsetLocator` answers per file, on demand, which is right for the editor path and insufficient here: a model
   of *the project's* configsets has to know what they are. Bound the scan so it does not walk `node_modules` and build
   output.
   [Settings and the detection escape hatch](#step-22-settings-and-the-detection-escape-hatch)
   consumes this to show the user what detection found; build it once, here.
5. Test the model directly, with no IDE fixtures where possible. This is the component that must be exhaustively
   correct.

**Success criteria:**

- [x] A configset parses to a complete field model, including dynamic fields.
- [x] The four agreement states are representable and tested with a synthetic server half.
- [x] Model rebuilds on file change and not otherwise.
- [x] Every configset in a project is enumerable, verified on a fixture with two of them and a directory tree the scan
  must decline to descend.

**What shipped:**

- `org.apache.solr.ide.model` — `SolrField`, `SolrDynamicField`, `SolrFieldType`,
  `SolrAnalyzerChain`, `SolrCopyField`, `SolrFieldReference`, and `SolrFieldModel` merging a repository half with a
  server half through `SolrFact` / `SolrAgreement`.
- `org.apache.solr.ide.repository` — `SolrSchemaParser` and `SolrConfigParser` (pure, string in and facts out),
  `SolrConfigsetReader` (per-configset cache), and
  `SolrConfigsetScanner` (project-wide enumeration).

**Parsing uses the JDK's DOM, not IntelliJ's XML PSI.** Criterion 5 is what forced it: a model bound to PSI can only be
tested inside an IDE fixture, and this is the component that has to be exhaustively correct. The later PSI features
resolve elements by name at the point of use, which they must do regardless. Doctypes and external entities are
refused — a cloned repository is not trusted input, and entity resolution would run while the user is merely opening a
file.

**The cache is keyed on the modification stamps of the files actually read**, and takes text from the in-memory document
when one exists. That gives both halves of criterion 3 — rebuild when the schema changes, and *not* when anything else
does — and means an unsaved edit is in the model before the file is written.

Ambiguous file names are still not evidence here: a directory holding only `schema.xml`
is not enumerated as a configset, matching
[the activation gate overhaul](#step-2-overhaul-the-activation-gate-done).

Match-capability analysis is deliberately absent — it is
[its own step](#step-4-match-analysis-done), and depends on nothing this one built beyond the analyzer chains.

**Acceptance:** No demo step of its own. Nothing from the navigation demos onward works without it, so it is verified
through the steps that consume it.

**Dependencies:** [the activation gate overhaul](#step-2-overhaul-the-activation-gate-done)

### Step 4: Match analysis (done)

A pure function from analyzer chain to match capability. Independent of everything; buildable in parallel with
[the repository reader](#step-3-repository-reader-and-field-model-done).

**Actions:**

1. Classify a field's index-time chain: whole value or tokenized, prefix-capable or not, case-sensitive or not.
2. Name the roughly fifteen factories that determine this in code, rather than reading them from the generated catalog.
   The spec's "The factory catalog" says why.
3. Test exhaustively against canonical types and against the orderings that change the answer.

**Success criteria:**

- [x] Correct classification for string, tokenized text, EdgeNGram and lowercased variants.
- [x] Filter ordering that changes the result is covered by tests.
- [x] Tested directly as a function, not through the annotator that displays it.

**What shipped:** `SolrMatchAnalysis`, a pure function from an index-time chain to a
`SolrMatchCapability`, in `org.apache.solr.ide.model`.

**The capability names the mechanism, not a boolean.** Demo step 32 pre-empts the objection that wildcards exist —
`wid*` works against any indexed field, slowly — so a hint reading
"supports prefix: true" would be simultaneously true and useless. `SolrPrefixSupport` is therefore `NONE`, `EDGE_NGRAM`,
`N_GRAM` or `PATH_HIERARCHY`, and the claim is about *efficient* index-time matching.

**Every conclusion records the factory behind it.** Demo step 30 invites the room to disagree, so "tokenized,
case-insensitive" is worth much less than the same statement able to name the filter that made it true.

**An unrecognized factory drops a `confident` flag rather than being assumed harmless.**
A wrong hint here is worse than no hint, so the display can decline to make a claim it cannot defend. Neutral factories
are therefore listed explicitly rather than being whatever is left over.

The ordering case that changes the answer, and is tested both ways: a word-delimiter filter after a
`KeywordTokenizerFactory` makes the field tokenized despite its tokenizer, and the evidence names the filter rather than
the tokenizer.

**Acceptance:** No demo step of its own. It is the correctness behind
[demo steps 28 to 31 — the match-capability hints](../../docs/demo/README.md#step-28-show-the-hint-on-a-string-field),
which are only ever as good as this.

**Dependencies:** none

### Step 22: Settings and the detection escape hatch

Numbered last because it was added last; it belongs *here*, in Foundation. Step numbers in this plan are stable anchors
that other steps link to, so renumbering to insert one costs more than the out-of-order number does. Read the section it
sits in, not the number.

The activation gate has no user-facing surface at all. `plugin.xml` registers one file-type mapping and nothing else —
no settings page, no action — so the escape hatch the spec promises exists only in code. `removeManualRoot` has no
caller outside tests, which means a marked root, once committed to the shared project file, cannot be undone through the
UI by the teammate who receives it.

**Actions:**

1. A project settings page under *Languages & Frameworks → Solr*: the detection switch, and the marked configset roots
   with add and remove. Pure wiring over
   `SolrConfigsetSettings`, which is already built and tested.
2. List the *detected* configsets on the same page alongside the marked ones, visibly distinguished. This is the half
   that carries the value — see the spec under "Seeing and correcting what activated" for why the silent failure is the
   one worth attacking. It needs a project-wide scan, which `SolrConfigsetLocator` does not do: it answers per file, on
   demand. Build that scan once, in
   [the repository reader](#step-3-repository-reader-and-field-model-done), which needs to enumerate configsets anyway,
   and consume it here rather than inventing a second sweep.
3. A *Mark Directory as Solr Configset Root* action in the Project View popup menu. The string
   `configset.action.markRoot` is already in the bundle, unused since
   [the activation gate](#step-1-activation-gate-done). Deliberately last of the three: it addresses false *negatives*,
   which the two-identifying-files rule from
   [the activation gate overhaul](#step-2-overhaul-the-activation-gate-done) already makes rare, whereas the list above
   addresses not knowing which failure you have.
4. A connections page as a sibling, once there are connections worth showing. Not before
   [the HTTP client and server reader](#step-11-http-client-connections-and-the-server-reader)
   — a page listing servers nothing can contact is a promise the plugin cannot keep.

**Success criteria:**

- [ ] Detection can be switched off, and a root marked and unmarked, without editing XML.
- [ ] The page lists detected configsets and marked ones, distinguishably.
- [ ] A root marked by one developer is visible and removable in another's checkout — tested by seeding the persisted
  state directly, as a teammate's commit would.

**Acceptance:**
[demo step 21 — *enable the plugin and reopen*](../../docs/demo/README.md#step-21-enable-the-plugin-and-reopen)
is the fallback if the demo configset does not activate on stage, which is the one failure the runbook has no other
recovery for.

**Dependencies:** [the activation gate overhaul](#step-2-overhaul-the-activation-gate-done) for actions 1 and
3; [the repository reader](#step-3-repository-reader-and-field-model-done) for the detected-configset list in action 2.

---

## Editor track

### Step 5: References, navigation and Find Usages (done)

**Actions:**

1. Reference providers for: a field's `type` to its field type; `copyField` source and destination to fields;
   request-handler parameters in `solrconfig.xml` to schema fields; a filter's resource attribute to the `stopwords.txt`
   or `synonyms.txt` it names.
2. Expose a reference-graph query surface that [inspections](#step-6-inspections-in-progress) and
   [rename](#step-8-rename) reuse.
3. Reference tests asserting resolve targets on representative configsets.

**What shipped:**

- `SolrConfigsetReferenceContributor` with the four providers, each soft: an unresolved name is the matching
  inspection's to report in Solr's vocabulary, so navigation never brings a second platform-worded warning with it.
  Field references resolve through the model's own resolution — declared beats dynamic, longest literal part wins — so a
  name only a pattern supplies navigates to that pattern's declaration: exactly the names the inspections accept,
  because both consult the same answer.
- The handler-parameter references cross the file boundary through the owning configset's schema, each name in a
  weighted value carrying its own range; the resource references ride the platform's `FileReferenceSet`, so rename and
  move refactorings already know how to update them.
- The query surface landed as two objects sized to their consumers rather than a graph API nothing asks for:
  `SolrConfigParameters` in `parsing` maps the parser's idea of a field reference onto PSI positions for both the
  inspection and the references, and
  `SolrSchemaPsi` answers where a name is declared for references, documentation and — when it
  arrives — [rename](#step-8-rename).

**Success criteria:**

- [x] All four reference kinds resolve.
- [x] Searching a declaration's references reaches every one of them, including across the file
  boundary — `ReferencesSearch` on a `<field>` returns the `qf` parameter naming it.

**One clause of the original criterion moved out, and has since been delivered elsewhere.** It read
*"All four reference kinds resolve; Find Usages returns every reference"*, and the second half was
ticked in error: the search half is real and asserted here, but nothing in this step made a
*declaration* into a target the platform will accept, so the Alt-F7 the clause describes answered
*Cannot search for usages from this location*. That half moved to
[declarations as targets](#step-28-declarations-as-targets) and landed there, which is where the
Alt-F7 criterion and its fixtures now live. Everything this step built is done and none of it
changed.

**Acceptance:** demo steps
[22 — *navigate to a field type*](../../docs/demo/README.md#step-22-navigate-to-a-field-type),
[23 — *navigate along a copyField*](../../docs/demo/README.md#step-23-navigate-along-a-copyfield),
and [24 — *cross the file boundary*](../../docs/demo/README.md#step-24-cross-the-file-boundary).
Demo step 27 moved to [declarations as targets](#step-28-declarations-as-targets) with the criterion
it belongs to: it now performs the search from the declaration, which is the gesture this step
refused.

**Dependencies:** [the repository reader and field model](#step-3-repository-reader-and-field-model-done)

### Step 6: Inspections (in progress)

Where the zero-false-positive requirement gets teeth.

**Actions:**

1. Implement: dangling `copyField` source or target; a field naming an undeclared field type; handler naming a
   nonexistent field; relevance parameters on non-indexed fields; unused field types; known-bad analyzer chain
   orderings; configuration elements removed in the targeted Solr line. **Seven, not the six an earlier revision
   listed** — the undeclared-field-type check shipped and was recorded in the success criteria without ever joining this
   list, so the two counts disagreed and the criteria were right.
2. A description file per inspection, written as user-facing prose — it is also the published catalog entry.
3. Test each on both flagged and clean fixtures.

Landing one inspection per pull request, each with its flagged and clean fixtures, rather than as one change. Taken
before [references and navigation](#step-5-references-navigation-and-find-usages-done), which this step nominally
depends on: that dependency holds only for inspections written as unresolved-reference checks, and these are driven off
the field model instead.

**What shipped so far:** six of the seven inspections action 1 names, each with its description file and its flagged
and clean fixtures — `SolrDanglingCopyFieldInspection`,
`SolrUnknownFieldTypeInspection`, `SolrUnknownFieldReferenceInspection`,
`SolrNonIndexedRelevanceFieldInspection`, `SolrAnalyzerChainOrderInspection` and
`SolrUnusedFieldTypeInspection`. `SolrAnalyzerChainOrderInspection` reports the defect in this list that a reader
cannot see at all: every class exists, every attribute is legal, Solr starts without complaint, and the filter never
runs. It carries two rules, both provable from the order of the chain alone — a `FlattenGraphFilterFactory` above every
filter that produces a graph, and a written `splitOnCaseChange` below a filter that has already folded the case away. An
ordering that is merely *unusual* is never reported: analyzer chains are where expert users deliberately do surprising
things, and this is the one inspection where a style opinion would fire constantly on schemas that work.
`SolrUnusedFieldTypeInspection` is the only one whose finding is not a defect — an unused type is dead weight and Solr
loads it without complaint — so it is drawn as an unused declaration rather than underlined, and offers no quick-fix:
whether the declaration is a leftover or a provision for fields not written yet is a judgement the editor cannot make.
It is also the first of these to refuse to answer at all in a case it cannot read, staying silent on a schema that
`xi:include`s its field declarations, where every type would otherwise look unused. Two more inspections exist in the
same package and belong to
[completion, validation and quick documentation](#step-10-completion-validation-and-quick-documentation-in-progress)
rather than here: `SolrUnknownAttributeInspection` and `SolrInvalidAttributeValueInspection`
are catalog-backed and validate an attribute rather than a reference.

**Four numbers describe this step, and reading one of them for another is the mistake this paragraph exists to
prevent.** Seven inspections are planned here and six are built. Eight inspection classes are registered in
`plugin.xml` — not the same number as the plan's seven, because two of the registered classes belong to another step
and one of the planned inspections does not exist yet. Eight of
[the manual suite's](../../docs/manual-test-suite.md) INSP checks exercise seven of those eight, since the dangling-
`copyField` inspection gets a second check for reacting to a live edit, a ninth restores the baseline, and the
non-indexed relevance check has no sandbox gesture yet. Read a count against what it counts; "eight inspections exist"
is true and says nothing about this step's progress.

**Success criteria:**

- [ ] Every inspection fires on crafted-bad fixtures and on nothing clean.
    - [x] Dangling `copyField` source or destination.
    - [x] A field naming an undeclared field type.
    - [x] A handler parameter naming a field the schema does not declare.
    - [x] A relevance parameter naming a non-indexed field.
    - [x] An unused field type.
    - [x] A known-bad analyzer chain ordering.
    - [ ] A configuration element removed in the targeted Solr line.

Of the one left, it has a dependency: it needs the catalog to know which line removed what, which is a fact
[the catalog generator](#step-9-factory-catalog-generator-in-progress) does not record today.

**Acceptance:** demo steps
[25 — *show the dangling reference*](../../docs/demo/README.md#step-25-show-the-dangling-reference)
and [26 — *break something live*](../../docs/demo/README.md#step-26-break-something-live). The planted dangling copy
rule is underlined, and deleting a referenced field flags its rule immediately.

**Dependencies:** [references and navigation](#step-5-references-navigation-and-find-usages-done)

### Step 7: Match hints and quick-fixes (done)

Taken out of order, ahead of the Editor track's earlier steps. Its two dependencies are both met, it needs no PSI
reference infrastructure, and it is the first step that produces anything a user can see — four steps of foundation had
shipped with no exit to the UI.

**Actions:**

1. Inlay hints surfacing each field's match capability from
   [match analysis](#step-4-match-analysis-done), inline rather than on hover, so the demo does not depend on the
   presenter's mouse.
2. Quick documentation on a field's `type`, covering what the type is, what its analyzer chain does, and what a field of
   it can match — plus a Reference Guide link for the version the configset targets. The spec argues this out under
   "What quick documentation covers"; the part that matters here is that this half needs the model and match analysis,
   not the factory catalog, so it does not wait for
   [the catalog](#step-9-factory-catalog-generator-in-progress).
3. Intentions adding a missing capability: an `_exact` companion plus `copyField`, an EdgeNGram-backed `_prefix` field.
   Phrased as efficient index-time support — the spec explains the wildcard caveat behind that wording.
4. Edit the file directly. No provenance check, no warning, no redirect.
5. Say nothing where match analysis is not confident. An unrecognized factory means the chain was not fully understood,
   and a wrong hint is worse than none.

**What shipped so far:**

- `SolrMatchInlayHintsProvider` in `org.apache.solr.ide.configset.hint`, registered as a
  `codeInsight.declarativeInlayProvider` — actions 1 and 5.
- `SolrConfigsetDocumentationProvider` and `SolrFieldPresentation` in
  `org.apache.solr.ide.configset.documentation` — action 2, including the Reference Guide link resolved against the
  configset's declared version.
- Property defaults resolved against the schema's own `version` attribute, which Solr 9.7 made load-bearing when it
  flipped `uninvertible` and `docValues` at schema version 1.7. A third version number, distinct from the Solr line and
  `luceneMatchVersion`.
- `omitNorms` and `docValues` resolved against the field type's class as well, from traits the catalog generator reads
  out of each type's ancestry. A class the catalog does not carry still reports undetermined, which is what keeps a
  custom plugin type from being given a default it does not have.
  [The design record](../../docs/design/archive/2026-08-02-schema-version-defaults/design.md)
  covers both.

- Both companion intentions in `org.apache.solr.ide.configset.intention` — action 3 entire, and with it action 4, which
  was a constraint on this work rather than separate work.
  `SolrAddPrefixCompanionIntention` writes the EdgeNGram-backed `_prefix` field;
  `SolrAddExactCompanionIntention` writes the `StrField`-backed `_exact` field. They share
  `SolrAddCompanionIntention` and `SolrCompanions`, differing only in what counts as already capable and which declared
  types may be reused.
  [The design record](../../docs/design/archive/2026-08-03-match-capability-intentions/design.md)
  covers the prefix half, including why its generated type's edge n-gram sits on the index side alone. The exact half's
  one non-obvious rule is that reuse matches the implementing *class*:
  every numeric and date type is unanalysed and so matches whole values exactly as a string type does, and borrowing one
  would fail at index time rather than in the editor.

**Success criteria:**

- [x] Fields annotated correctly for canonical types.
- [x] No hint is shown where match analysis is not confident.
- [x] Quick documentation on a field's type resolves, and its Reference Guide link names the version the configset
  targets.
- [x] Quick-fixes produce valid configset edits.
    - [x] The `_prefix` companion, its copy rule, and the field type when one has to be written.
    - [x] The `_exact` companion, on the same terms.

**Acceptance:** demo steps
[28 to 33 — the hints and the generated fix](../../docs/demo/README.md#step-28-show-the-hint-on-a-string-field). A
`string` field reads as whole-value and case-sensitive, a tokenised field does not offer prefix matching, an edge-n-gram
field does, and Alt-Enter generates the companion field with its copy rule.

**Dependencies:** [the repository reader and field model](#step-3-repository-reader-and-field-model-done),
[match analysis](#step-4-match-analysis-done)

### Step 23: Explaining and correcting what is already on screen (done)

Numbered last because it was added last; it belongs in the Editor track, after
[match hints](#step-7-match-hints-and-quick-fixes-done). Read the section it sits in, not the number.

Three gaps found by using the plugin rather than by reading the plan, and they share a shape: the plugin already knows
the answer and has nowhere to say it.

**Documentation answers only on attribute *values*.** Hovering `<schema>`, `<copyField>`,
`<uniqueKey>`, `<dynamicField>` or a `<fieldType>` tag gives nothing, because the provider was written to explain a
*value* under the caret. Everything the plugin knows is therefore reachable only by a gesture you make when you already
suspect something — including the property table, which answers "what is the complete configuration when the defaults
are not written down" and which a user looking straight at it could not find.

**Inspections report a bad name without offering the good ones.** Both reference inspections compute the valid set in
order to decide, and then discard it. An underline with no Alt-Enter is more frustrating than no underline.

**Completion does not say which value is the default.** The property table already knows.

**Actions:**

1. Documentation on the schema elements — what each is, in Solr's terms, plus what *this*
   one does where that is knowable: which fields a copy rule joins, which field is the unique key, how many types the
   schema declares. Reuse the version resolution and the Reference Guide links that already exist.
2. A quick-fix on the unknown-field-type inspection offering the declared types, and one on the dangling-`copyField`
   inspection offering the declared fields. Both sets are already computed by the inspection that reports the problem.
3. Mark the default in completion — `true (default)` — read from the property table rather than restated.
4. Nothing here needs [the factory catalog](#step-9-factory-catalog-generator-in-progress). The catalog-backed half of
   documentation and completion stays in
   [completion, validation and quick documentation](#step-10-completion-validation-and-quick-documentation-in-progress).

**What shipped:**

- `SolrSchemaElements` — a description per recognized tag plus the configset-specific sentence beside it: which fields a
  copy rule joins, which field is the unique key and of what type, how many fields use a type. Tested as a pure function
  in `SolrSchemaElementsTest`, which is JUnit 4 with backtick names rather than the `testSomething()` convention the
  fixture tests use — it needs no fixture, so it does not extend `BasePlatformTestCase`.
- `SolrReplaceNameQuickFix`, offered by both reference inspections, ordered by closest spelling and capped so a large
  schema does not produce an unusable list.
- The default-value marking in `SolrConfigsetCompletionContributor`, read from
  `SolrFieldProperties` and deliberately silent where the default depends on the field type.

**Success criteria:**

- [x] Every recognized schema element answers on hover, and says something specific to the configset where it can.
- [x] Both reference inspections offer a fix naming the valid alternatives, and applying one produces a configset that
  parses.
- [x] A completed value that Solr would have used anyway is marked as the default.

**Acceptance:** No demo step of its own. It is what makes
[demo step 25 — *show the dangling reference*](../../docs/demo/README.md#step-25-show-the-dangling-reference)
survive the obvious follow-up question, which is "so what do I put there instead".

**Dependencies:** [inspections](#step-6-inspections-in-progress) for the quick-fixes;
[match hints](#step-7-match-hints-and-quick-fixes-done) for the documentation provider they extend.

### Step 24: Completing the schema's own vocabulary (done)

Numbered last because it was added last; it belongs in the Editor track beside
[explaining and correcting what is already on screen](#step-23-explaining-and-correcting-what-is-already-on-screen-done).

Completion today answers *what value goes here* and never *what may I write at all*. Typing
`<` in a schema offers nothing; typing a space inside `<field ` offers nothing. Both are answerable from what the plugin
already holds, and both matter more than value completion for the reader who has not learned the vocabulary yet —
someone who knows `sortMissingLast`
exists can type it, and someone who does not will never meet it in a file that does not already use it.

**A mis-filing this corrects.** Field attribute completion sits in
[completion, validation and quick documentation](#step-10-completion-validation-and-quick-documentation-in-progress),
which waits on [the factory catalog](#step-9-factory-catalog-generator-in-progress). Only *factory*
attributes need the catalog; *field* attributes come from the property table, which exists. The dependency was wrong,
and a feature was parked behind something it never needed.

**Actions:**

1. Model the `fieldType` general properties — `positionIncrementGap`,
   `autoGeneratePhraseQueries`, `synonymQueryStyle`, `enableGraphQueries`,
   `docValuesFormat`, `postingsFormat`. The property table was built from the Reference Guide's *field* properties and
   skipped the table beside it, so documentation is missing them too; modelling them fixes both surfaces at once. Record
   which properties a
   `fieldType` accepts and which a `field` accepts, since the sets differ.
2. Element-name completion, from the descriptions
   [element documentation](#step-23-explaining-and-correcting-what-is-already-on-screen-done)
   already holds. Offer only elements legal in the enclosing element.
3. Attribute-name completion on `field`, `dynamicField` and `fieldType`, from the property table, showing each
   property's summary. Omit attributes already present on the tag — an attribute cannot be written twice, and offering
   it is offering an error.
4. Attribute-value completion where the set is closed and is not boolean: `analyzer`'s
   `type`, and `synonymQueryStyle`. Positions where any value is legal stay untouched, as
   [completion](#step-10-completion-validation-and-quick-documentation-in-progress) already requires.

**What shipped:** all four actions, in `SolrConfigsetCompletionContributor` against the widened `SolrFieldProperties`,
covered by `SolrSchemaVocabularyCompletionTest`.

**Class-name completion in the same contributor is not this step.** The tests reading
`solr.` implementations for a `fieldType`, a tokenizer and a filter, and asserting the offered set follows the declared
Solr line, belong to
[the factory catalog generator](#step-9-factory-catalog-generator-in-progress) and
[completion, validation and quick documentation](#step-10-completion-validation-and-quick-documentation-in-progress).
They arrived early and share a file with this step's work; read them against those steps, not this one, or this step
will look larger than it was.

**Success criteria:**

- [x] Typing `<` inside a schema offers the elements legal there and nothing else.
- [x] Attribute completion offers what the element accepts, minus what it already carries.
- [x] A `fieldType` general property completes, and documents, exactly as a field property does.
- [x] Non-boolean closed value sets complete; open-ended ones stay with the platform.

**Acceptance:** No demo step of its own. It is the difference between a reader who can only edit what is already written
and one who can discover what Solr allows.

**Dependencies:** [explaining and correcting what is already on screen](#step-23-explaining-and-correcting-what-is-already-on-screen-done)
for the element descriptions. Nothing from the catalog.

### Step 25: solrconfig.xml as a first-class surface

Numbered last because it was added last; it belongs in the Editor track after
[the factory catalog](#step-9-factory-catalog-generator-in-progress), which it depends on entirely.

The spec settled the scope question under "`solrconfig.xml` gets the same treatment as the schema". Until now the file
was read only for the field names it mentions, which gave the most-edited file in a configset the least support.

**This is the largest step in the configuration surface and should be split when it starts.**
It is written as one step because the pieces share a dependency and a shape, not because it is one pull request.

**Actions:**

1. Element and attribute completion for `solrconfig.xml`'s structure, from the catalog, with the shipped `_default` and
   `sample_techproducts_configs` files as ground truth for what nests inside what.
2. Parameter-name completion inside `defaults`, `appends` and `invariants`, and documentation on each parameter.
3. Validation of what the catalog positively knows to be wrong: a misspelling of a name it knows, a value outside a set
   it knows to be closed. **Never validation by absence** — a parameter the generator did not find is not thereby
   invalid, and `solrconfig.xml` accepts plugin classes from outside Solr. Flagging the unknown would produce a false
   positive on every project with a custom component.
4. Navigation from a `class` attribute to the plugin it names, where that class is on the project's classpath.

**Success criteria:**

- [ ] Both configsets Solr ships produce zero findings, which is the gate the spec already sets for inspections.
- [ ] A custom plugin class and its parameters produce no findings either.
- [ ] Completion and documentation answer inside a request handler.

**Acceptance:** No demo step of its own yet; the runbook predates this scope.

**Dependencies:** [the factory catalog generator](#step-9-factory-catalog-generator-in-progress), which grows to cover
this vocabulary.

### Step 26: Showing that an attribute restates the default

Numbered last because it was added last; it belongs in the Editor track beside
[explaining and correcting what is already on screen](#step-23-explaining-and-correcting-what-is-already-on-screen-done).
Read the section it sits in, not the number.

The distinction exists in one surface and is invisible in the other. The property table behind quick documentation marks
whether each value was declared or defaulted, but the editor renders `indexed="true"` identically whether deleting it
would change anything. A reader auditing a schema cannot tell the load-bearing attributes from the restated ones without
hovering each in turn.

**Not an inspection, deliberately.** A restated default is *correct*, and the standing rule is that inspections do not
fire on correct files — an underline here would be the plugin manufacturing a problem in order to have something to say.
The platform already has an idiom for "true but removable": the dimmed rendering it gives redundant code, paired with an
intention rather than a quick-fix, because an intention carries no claim that anything is wrong.

**Actions:**

1. Dim an attribute whose written value equals its effective default, as an annotator at information severity — no
   underline, no entry in the Problems view.
2. An intention on the dimmed attribute that removes it, leaving a file whose parsed model is identical.
3. Stay silent wherever the default is not knowable with confidence: properties whose default depends on the field type,
   and factory attributes until
   [the catalog](#step-9-factory-catalog-generator-in-progress) carries defaults — at which point factory attributes
   join with no new machinery here.

**Success criteria:**

- [ ] `indexed="true"` on a field dims and `indexed="false"` does not, and removing the dimmed attribute leaves the
  parsed model identical.
- [ ] A property whose default depends on the field type never dims.
- [ ] Nothing this step adds appears in the Problems view on a correct file.

**Acceptance:** No demo step of its own. It is the editor-side answer to the question the property table answers in the
popup — which of these lines could go.

**Dependencies:**
[completing the schema's own vocabulary](#step-24-completing-the-schemas-own-vocabulary-done)
for the property table it reads; the factory half additionally needs the defaults column in
[the factory catalog generator](#step-9-factory-catalog-generator-in-progress).

### Step 27: Saying what a property's value means (done)

Numbered last because it was added last; it belongs in the Editor track beside
[explaining and correcting what is already on screen](#step-23-explaining-and-correcting-what-is-already-on-screen-done).
Read the section it sits in, not the number.

The popup already resolved each property to a value and said where it came from, then described the property in the
neutral, value-independent terms the Reference Guide itself uses — *"Whether the original value can be returned in
results"* beside `stored: false`, never *"the original value is not returned"*. The inlay hint had the matching gap from
the other side: it said what a field could match and nothing about what happened to the value afterwards, so a field
that is searchable but not returnable — the most common cause of "my query works but the field is missing from the
response" — looked identical inline to one that is both.

**Actions:**

1. `SolrPropertyMeaning` on `SolrFieldProperty`: a `whenTrue`/`whenFalse` sentence pair for every boolean property legal
   on a field, plus a short `inlineWhenTrue`/`inlineWhenFalse`
   phrase pair for the four that decide a field's storage shape — `indexed`, `stored`,
   `docValues`, `multiValued`.
2. The popup's Meaning column renders the sentence for the resolved value, falling back to the neutral summary only
   where no value can be stated: `UNDETERMINED`, or a property with no `meaning` at all (`default`, which takes any
   value of the field's type).
3. `SolrMatchInlayHintsProvider` appends the four inline phrases after the match parts, in
   `SolrFieldProperties.FOR_FIELD` order — match capability first, storage shape second.
4. Two behaviour changes to the hint's silence rules, one loosened and one left alone: an unrecognised analysis factory
   now renders the storage-shape phrases with no match claim, where it previously suppressed the hint entirely —
   property values never depended on the analyser chain, only the match claim did. An undeclared field type still
   suppresses the hint completely, and the fall-through is the reason rather than an exception to it:
   resolution is three-tier — field, then field type, then Solr's default — so a missing middle tier still resolves
   every property, but resolves it by attributing each default to Solr when the type that might have overridden it does
   not exist. Property resolution survives an undeclared type; hint eligibility does not, because that silent
   misattribution is an inspection's finding, not a hint's.
5. `versionOf`/`traitsOf`, previously private helpers on `SolrConfigsetDocumentationProvider`, move onto
   `SolrFieldModel` as `solrVersion`/`traitsOf`, since the inlay provider needs them too and neither touches PSI.

**What shipped:** all five actions. `SolrFieldProperties` in `org.apache.solr.ide.model`
carries the meaning table; `SolrFieldPresentation.propertyTable` and
`SolrConfigsetDocumentationProvider` render it in the popup; `SolrMatchInlayHintsProvider`
renders it inline. [The design record](../../docs/design/archive/2026-08-02-field-property-explanations/design.md)
covers the phrasing and the silence rules in full.

**Success criteria:**

- [x] The popup's Meaning cell states the consequence of the resolved value, for every boolean property that carries
  one, and falls back to the neutral summary only where the value is `UNDETERMINED` or the property has no meaning to
  state.
- [x] The inlay hint carries `indexed`, `stored`, `docValues` and `multiValued` after the match parts, in that order,
  for a field whose type is declared.
- [x] An unrecognised analysis factory drops only the match claim; the storage-shape phrases still render.
- [x] An undeclared field type still suppresses the hint entirely.
- [x] A property that resolves to `UNDETERMINED` contributes no phrase, while the other three still render — the silence
  is per property, not per hint.

**Acceptance:** [the manual test suite's HINT-1 through HINT-5](../../docs/manual-test-suite.md),
and [screenshot catalog entry 1](../../docs/screenshots.md), both rendered against
`demo/solr/conf/managed-schema.xml`.

**Dependencies:** [match hints](#step-7-match-hints-and-quick-fixes-done) for the inlay provider it
extends; [explaining and correcting what is already on screen](#step-23-explaining-and-correcting-what-is-already-on-screen-done)
for the documentation provider it extends.

### Step 28: Declarations as targets

Numbered last because it was added last; it belongs in the Editor track immediately before
[rename](#step-8-rename), which cannot start until it lands. Read the section it sits in, not the
number.

Navigation runs one way. Ctrl-click from `type="text_general"` lands on the declaration, and Alt-F7
on that declaration answers *Cannot search for usages from this location*. The half that looks hard
is already built and asserted:
[references and navigation](#step-5-references-navigation-and-find-usages-done) anchored every
reference at a use site and resolved it to the declaration's `name` attribute value, and
`ReferencesSearch` traverses those edges backwards today — including across the file boundary. What
is missing is the step *before* the search: nothing turns a caret sitting on a declaration into
something the platform will search for. Reading `TargetElementUtilBase` off the 2026.2 platform, it
accepts a caret three ways — a reference at that offset, a `PsiNamedElement` whose text offset
coincides, or a `PomTarget` from `PomDeclarationSearcher` — and a schema declaration is none of them.
The platform's own `XmlFindUsagesProvider` would accept the element; it is never asked.

`SolrDeclarationTargetTest` pins that boundary against the platform call the action makes: three
declarations yield no target, two references yield one, and the reverse search still reaches across
the file boundary. This step inverts the three, which is how it proves itself.

**Actions:**

1. A `PomDeclarationSearcher` producing a named target for the `name` attribute value of
   `field`, `dynamicField` and `fieldType` in a schema file of a detected configset. The target
   delegates to the same `XmlAttributeValue` `SolrSchemaPsi` already returns, because that identity
   is what `isReferenceTo` compares — a target pointing anywhere else would make Find Usages and
   Ctrl-click disagree about which references exist.
2. A `referencesSearch` executor for dynamic field targets alone, walking the owning configset's own
   reference positions and resolving each through `SolrFieldModel.resolve`. Without it a
   `<dynamicField name="*_t">` reports only the references spelling `*_t` literally: the word index
   picks candidates before any reference confirms itself, and `body_t` shares no word with the
   pattern, so every name the pattern actually supplies is silently absent. It resolves through the
   same call the reference's own `resolve()` makes, so the two cannot disagree.
3. Correct what this step disproves — the clause moved off
   [references and navigation](#step-5-references-navigation-and-find-usages-done) above,
   [the manual suite's NAV-3 and NAV-4](../../docs/manual-test-suite.md), which currently describe
   the refusal as expected behaviour and will fail a correct plugin, and demo step 27's gesture.

Distinct from [Step 10's action 3](#step-10-completion-validation-and-quick-documentation-in-progress),
dynamic field pattern awareness, which is about completion and validation. Neither closes the other;
this one is the search direction only.

**Success criteria:**

- [x] Alt-F7 on a `<field>`, `<dynamicField>` or `<fieldType>` declaration lists every reference,
  including the ones in `solrconfig.xml`; `SolrDeclarationTargetTest`'s three `yieldsNoTarget`
  assertions invert, and its reference and search assertions still hold unchanged.
- [x] A dynamic field reports the names its pattern supplies as well as its literal spellings, each
  at the range of the name itself rather than the whole parameter value.
- [x] Nothing outside a configset yields a Solr target, and neither does a `name` attribute the
  plugin does not model — `<requestHandler name="/select">` among them.
- [x] A same-named field in a second configset in the same project is not reported; Solr resolves per
  configset and so does this.
- [x] Step 5's criterion, NAV-3, NAV-4 and demo step 27 describe what the plugin does. NAV-6 was
  added alongside them for the dynamic-field gesture, which no existing check covered.

**Two claims in the design record were wrong, and the build said so.** `RenameableDelegatePsiTarget`
was named as the ready-made target class; it requires a `PsiNamedElement`, which an
`XmlAttributeValue` is not — the very fact that made a declaration searcher necessary, met again one
layer down. The target is a `DelegatePsiTarget` carrying a name instead, and rename keeps its own
fixtures in [Step 8](#step-8-rename) rather than inheriting an untested capability here. The second:
a caret on the attribute *name* was said to yield no target at all, and it yields the enclosing tag —
the platform's own descriptor answer, present before this step and not this step's to remove. The
criterion that matters, and the one asserted, is that it yields no Solr *declaration* target.

**Acceptance:**
[demo step 27 — *Find Usages on a field type*](../../docs/demo/README.md#step-27-find-usages-on-a-field-type)
performed as written, with the caret on the `text_general` **declaration** rather than on a reference,
and [screenshot catalog entry 7](../../docs/screenshots.md) reshot from there.

The dynamic half is demonstrable on the committed fixture rather than only in tests: the demo schema
declares `<dynamicField name="*_t">` and the `/select` handler's `pf` names `body_t`, which nothing
declares. Alt-F7 on that pattern must reach the `pf` occurrence — the case that returns an empty list
today.

**Dependencies:** [references and navigation](#step-5-references-navigation-and-find-usages-done) for
the reference graph and `SolrSchemaPsi`; [the repository reader and field model](#step-3-repository-reader-and-field-model-done)
for the resolution the dynamic-field executor calls.

[The design record](../../docs/design/pending/2026-08-04-declaration-targets/design.md) carries the
route comparison — why the POM declaration searcher rather than the Symbol API — and the bounds on
the configset walk.

### Step 8: Rename

**Actions:**

1. Rename fields and field types, updating every reference through the graph built by
   [references and navigation](#step-5-references-navigation-and-find-usages-done).
2. Replace the plugin scaffold's leftover `src/test/testData/rename/` placeholders —
   `foo.xml` and `foo_after.xml`, which no test reads — with configset before/after pairs asserting no dangling
   references remain.

**Success criteria:**

- [ ] Every resolved reference updates; no dangling references after rename.
- [ ] No scaffold fixtures remain under `src/test/testData/`.

**Acceptance:**
[demo step 34 — *rename across files*](../../docs/demo/README.md#step-34-rename-across-files). Renaming a field updates
its copy rules *and* the `qf` line in `solrconfig.xml`.

**Dependencies:** [references and navigation](#step-5-references-navigation-and-find-usages-done) for the graph;
[declarations as targets](#step-28-declarations-as-targets) for the target itself, and it is a prerequisite rather than
a neighbour. `renameElementAtCaret` on a declaration throws *element not found in file*: it resolves the same null
target Alt-F7 does rather than reaching past it to the tag. So this step needs that one to *gain* a target, not to
*suppress* a wrong one, and the `<field>`-tag corruption
[the developer notes](../../docs/modern-intellij-plugin-development.md) warn about is not reachable from here.

### Step 9: Factory catalog generator (in progress)

**Actions:**

1. A Gradle task that reads the Solr and Lucene artifacts per supported line and emits a catalog of factories, their
   attributes and their documentation — **and, by the same three passes, of `solrconfig.xml`'s plugins and their
   parameters.** The spec argues this out under "`solrconfig.xml` gets the same treatment as the schema": the parameter
   names are unreflectable for exactly the reason factory attributes are, so the constructor-bytecode pass is the same
   technique pointed at a different set of classes. Runs in the build, where loading Solr classes is ordinary. It needs
   three sources, because no single one carries all three pieces:
    - **Factories** — reflection over the artifact jars, via the SPI service files Solr already ships to name them.
    - **Field type classes** — the concrete subclasses of `FieldType`, which a `<fieldType>`
      names in its `class` attribute. A separate population from the factories and found a different way, since Solr
      ships no SPI file for them; roughly forty classes. Cheap next to the rest of this step, and the reason it is
      called out rather than assumed is that an earlier revision said "factories" and meant it, leaving `class`
      uncovered while appearing not to be.
    - **Attributes** — bytecode analysis of each factory's constructor, collecting the string constants passed to `get`,
      `getInt`, `getBoolean` and friends. Reflection cannot see these: a factory reads its attributes out of a
      `Map<String, String>`, so the names are literals in the constructor body and appear as neither fields nor
      annotations. **The same pass distinguishes three shapes and harvests two more facts for free.** `requireInt` and
      its siblings mark an attribute *required*. A literal default sits as the argument beside the name in the same
      call — verified against the shipped bytecode: `WordDelimiterGraphFilterFactory` reads `generateWordParts` with
      `iconst_1` beside it, and javac's constant inlining puts even a named `DEFAULT_*`
      constant there as a literal. A default computed at runtime —
      `JapaneseTokenizerFactory`'s `mode` — is recorded as *absent, never guessed*, for the same reason match analysis
      carries a `confident` flag: a wrong default shown in the editor is worse than none.
    - **Documentation** — the `-sources` artifacts. Javadoc is not retained in bytecode, so a compiled jar cannot supply
      it at all.
2. Declare supported lines in one place so adding or dropping one is a single edit.
3. A loader selecting the entry by: connected server, then `<luceneMatchVersion>`
   translated through the Lucene-to-Solr table, then newest supported line. Record which source answered.
4. Only ALv2-compatible documentation content may be embedded. If the `-sources`
   artifacts turn out not to be resolvable for a line, ship that line's catalog without documentation rather than
   hand-writing it — the spec's reason for generating this at all is that the list is too large to maintain by hand.

**What shipped so far:**

- The catalog generator, now living in `buildSrc` and split into a scanner, a hierarchy builder, an attribute extractor
  and a documentation extractor, reading Solr and Lucene per line with ASM and emitting `solr-catalog/solr-<line>.tsv`
  onto the plugin classpath. Actions 1, 2 and 4.
- The documentation source: `-sources` artifacts resolved for both supported lines via Gradle's
  `ArtifactResolutionQuery`, and a class's own class-level Javadoc comment reduced to its first sentence — the same
  convention the `javadoc` tool's own overview tables use — with `{@link}` and `{@code}` resolved to plain text and HTML
  markup stripped. That query has no lazy, task-execution-time form the configuration cache accepts, so it runs eagerly
  in the build script at configuration time rather than through the task's own lazy file collections; a
  configuration-cache hit skips the configuration phase entirely, so this resolves once per cache entry rather than on
  every invocation. The TSV's fifth column carries the summary, absent where a line's `-sources` artifacts did not
  resolve or a class carries no class comment at all.
- What this settled, once real classes were checked against real bytecode: a factory's own class comment is typically
  one short sentence — `EdgeNGramFilterFactory`'s reads "Creates new instances of EdgeNGramTokenFilter." — never the
  argument-by-argument prose and worked examples the Reference Guide carries. This column upgrades the popup from no
  prose to one sentence; it does not and cannot reach guide parity, which is not a shortfall of the extraction but a
  ceiling set by what Solr's own source comments contain.
- The constructor-bytecode attribute pass, which is the part reflection cannot do: it walks each factory's `<init>`,
  takes the literal passed to every argument reader, and inherits what the superclasses read. It now covers the
  field-type classes too — read in their
  `init`/`setup` rather than a constructor — so a `<fieldType>`'s `class` is no longer a blank in the catalog.
- Each attribute now carries its value type as well as its name, inferred from the reader's JVM descriptor — and, where
  the bytecode proves them, its literal default and whether it is required. The TSV's fourth column reads `name:type`,
  with a trailing `!` for a required attribute and `=default` for a literal default. The types are what the
  typed-attribute inspections in
  [completion, validation and quick documentation](#step-10-completion-validation-and-quick-documentation-in-progress)
  validate against; the default and required marker are what the factory half of
  [quick documentation](#step-10-completion-validation-and-quick-documentation-in-progress) and
  [showing that an attribute restates the default](#step-26-showing-that-an-attribute-restates-the-default)
  will read.
- `solr-analysis-extras` resolved alongside `solr-core`. Without it the catalog had Japanese and Korean analysis and no
  Chinese at all, which is the kind of gap a count never shows.
- `SolrClassCatalog` and `SolrVersionSource`, which record whether the line was decided by the configset or by the
  fallback.

**What remains is the server arm of selection**, which is unrelated to the documentation source and always waited on
[the server reader](#step-11-http-client-connections-and-the-server-reader). The attribute pass records each attribute's
value type and, where the bytecode proves them, its literal default and required marker — the two facts the factory half
of
[quick documentation](#step-10-completion-validation-and-quick-documentation-in-progress) and
[showing that an attribute restates the default](#step-26-showing-that-an-attribute-restates-the-default)
consume. Selection reads the configset's declared version and then falls back to the newest line; the `SERVER` arm of
`SolrVersionSource` is unreachable until the server reader exists, so that criterion closes with the Server track rather
than here.

**Success criteria:**

- [x] Catalog generated at build time for both supported lines and present on the plugin classpath.
- [x] `WordDelimiterGraphFilterFactory` exposes `generateWordParts`, `catenateAll`,
  `splitOnCaseChange`, `stemEnglishPossessive`, `protected` and `types`, among others. This is the criterion that proves
  the constructor-bytecode pass works, because reflection over fields produces a plausible short list instead of failing
  outright. **Named rather than counted**: an earlier revision said "all twelve", and the real number moved with the
  Solr line, which makes a count a criterion that fails for the wrong reason.
- [x] `JapaneseTokenizerFactory` exposes `mode` and `userDictionary`. Both are read by paths a naive pass misses — one
  has a default computed by a method call, the other is taken with `args.remove` rather than a getter.
- [x] Defaults and requiredness are recorded where the bytecode proves them, and only there:
  `WordDelimiterGraphFilterFactory`'s `generateWordParts` carries its default,
  `EdgeNGramFilterFactory`'s `minGramSize` and `maxGramSize` are marked required with no default, and
  `JapaneseTokenizerFactory`'s `mode` carries neither. The trio is the criterion because it proves all three behaviours
  at once — taking the literal, reading `require*`, and declining the computed value rather than guessing it.
  `SolrClassCatalogTest` asserts the trio against the shipped catalog.
- [x] `solr.StrField` and `solr.TextField` are both present, which is what proves the field-type-class pass ran at all —
  the `class` attribute of a `<fieldType>` is the most-hovered thing in a schema after the field names. **`TextField`
  carries its documentation summary; `StrField` does not, and cannot.** `TextField.java` opens with a class-level
  Javadoc comment ("is the basic type for configurable text analysis"), which the generator reduces to a sentence
  exactly as designed. `StrField.java` carries no class-level Javadoc comment at all in Solr's own sources — only the
  ASF license header above it, a plain block comment rather than a Javadoc one. That is a fact about what Solr's
  maintainers wrote, not a gap in the extraction: recording no summary for a class with no comment is the same
  decline-rather-than-guess rule the attribute pass already follows for a computed default, so this criterion is met by
  both classes appearing with whatever documentation Solr's own source actually gives them.
- [ ] Selection order is correct and the answering source is recorded. The source is recorded and the
  configset-then-newest order works; the connected-server arm waits on
  [the server reader](#step-11-http-client-connections-and-the-server-reader).
- [ ] The catalog regenerates from a clean build.

**Acceptance:**
[demo step 69 — *attribute completion on a factory with many
options*](../../docs/demo/README.md#step-69-attribute-completion-on-a-factory-with-many-options)
is the one to run by hand, for the reason in the criterion above.

**Dependencies:** [the activation gate overhaul](#step-2-overhaul-the-activation-gate-done)

### Step 10: Completion, validation and quick documentation (in progress)

**In progress.** Completion for the schema positions whose valid set is closed — a field's
`type`, a `copyField`'s two ends, and the boolean properties — landed a capability per pull request ahead of this step,
because none of it needs the catalog. Positions where any value is legal are left to the platform: a partial list
implies the values not on it are wrong. Since then the catalog-backed half has largely landed too: completion offers the
`class`
classes and, inside an analysis tag, the factory's own attribute names, and the typed-attribute inspections validate an
attribute's value and name against the catalog — action 2. Quick documentation on `class` values shipped ahead of the
catalog's prose column and now carries the Javadoc summary where [Step 9](#step-9-factory-catalog-generator-in-progress)
resolved `-sources`. **Action 5 shipped:** hovering a factory attribute — `minGramSize` on an `EdgeNGramFilterFactory` —
answers with the class that reads it, its value type, and its default or required marker where the catalog carries them,
plus the guide link the class-value half already builds. The provider stays silent when the class or the attribute is
unknown rather than inventing a type or a default; Javadoc is per class, so there is no per-attribute prose to surface
and none is claimed. **Action 6 shipped next:** hovering a factory tag (`<filter>`, `<tokenizer>`, `<charFilter>`) shows
every attribute the catalog says the class accepts at its effective value — written values bold and labelled as on this
filter/tokenizer/char filter, and the attributes the tag leaves out plain: a *literal* catalog default is shown at that
value and labelled as Solr's, while an attribute the catalog cannot cite a value for shows an em dash, its origin column
separating the two reasons there is none — required and missing, or optional with no recorded default. Those rows still
appear, because a complete-configuration picture that dropped them would understate what the class accepts, and an em
dash is the honest cell where an invented number would be a claim. The element named is the one the file wrote rather
than the one the class belongs on, so a misplaced factory — a tokenizer's class on a `<filter>` — reads as the mistake
it is instead of being quietly rendered valid; flagging it remains the inspections' job. This is the factory sibling of
the field property table, and a class the catalog does not know stays silent rather than claiming an empty attribute
set.

The three surfaces divide the caret between them rather than competing for it: the `class` value answers what the class
is, an attribute answers for itself, and the tag answers for this instance once Solr has filled in the defaults — one
catalog entry read three ways, each position claiming only what it can support. Landing the tag surface also settled
where an *uncitable* attribute goes: it defers to its tag, the same fall-through a schema attribute has always had to its
element, since the tag's table is built from the attributes the catalog lists and therefore cannot name the unknown one
at any value. Silence is kept for the case where nothing can be cited at all — a class the catalog has never seen —
which is the distinction that matters, invention rather than absence. All four success criteria below are now met; the
heading stays *in progress* until action 3, dynamic field pattern awareness, is confirmed, which no criterion states and
no change so far has claimed.

**Actions:**

1. Completion for field types, factories, their attributes, and field attributes.
2. Structural validation flagging unknown factories and invalid attributes.
3. Dynamic field pattern awareness.
4. Documentation provider keyed by factory and attribute, surfacing which catalog source answered. The *field type* half
   of quick documentation does not belong here — it needs the model and match analysis rather than the catalog, so it
   ships with
   [match hints](#step-7-match-hints-and-quick-fixes-done) instead. Only the catalog-backed half waits for this step.
5. ~~Hover on a factory attribute — `minGramSize` on an `EdgeNGramFilterFactory` — answers with what the catalog can
   prove: the class that reads it, its value type, and its default or required marker once the catalog carries them,
   with the guide link for the rest. Javadoc is written per class, not per attribute, so full per-attribute prose has no
   source anywhere in this design; the provider states what it can cite and claims nothing beyond it.~~ **Done.**
6. ~~The factory sibling of the field property table: quick documentation on a factory tag shows every attribute the
   class accepts at its effective value, written or defaulted, distinguishably — the complete-configuration picture the
   field half already gives, and the second consumer of the defaults column beside
   [showing that an attribute restates the default](#step-26-showing-that-an-attribute-restates-the-default).~~
   **Done.**

**Success criteria:**

- [x] Completion and validation work against the catalog.
- [x] Quick documentation resolves for factories and attributes.
- [x] A factory attribute answers on hover with its owner, value type, and — where the catalog carries them — its
  default or required marker, and stays silent about meaning it cannot cite.
- [x] A factory tag's documentation shows its complete configuration — literal defaults at their values, and attributes
  with no citable value marked rather than invented — distinguishably from written ones.

**Acceptance:** demo steps
[68 — *completion inside an analyser chain*](../../docs/demo/README.md#step-68-completion-inside-an-analyser-chain),
[69 — *attribute completion on a factory with many
options*](../../docs/demo/README.md#step-69-attribute-completion-on-a-factory-with-many-options)
and [70 — *quick documentation on a factory*](../../docs/demo/README.md#step-70-quick-documentation-on-a-factory).

**Dependencies:** [the repository reader and field model](#step-3-repository-reader-and-field-model-done),
[the factory catalog generator](#step-9-factory-catalog-generator-in-progress)

---

## Server track

### Step 11: HTTP client, connections and the server reader

**Actions:**

1. A minimal HTTP and JSON client for the endpoints the plugin needs. No SolrJ dependency — see the spec.
2. Connection definitions in per-user settings, credentials in PasswordSafe. Basic auth and TLS.
3. Server reader: schema, collections, cores, and the fields actually present in the index.
4. Populate the server half of the field model.
5. **A fake HTTP layer for tests**, covering success, timeout, auth failure, malformed response, and unrecognized server
   version. This fixture is part of the step.
6. **A contract test per supported line against Testcontainers** — `solr:10.0.0` and
   `solr:9.10.1`, pinned by tag, never `latest`. The fake layer can only replay responses somebody imagined, which is
   the wrong instrument for the risk that a real server returns a shape nobody anticipated. This keeps the fake honest
   as Solr's wire format moves, and it still satisfies the standing rule: the container is started by the test, not by a
   developer.
7. Every call asynchronous and timeout-bounded; nothing on the editor path.

**Success criteria:**

- [ ] A connection can be created, stored and used; credentials never reach project files.
- [ ] The server half of the model populates.
- [ ] All five failure modes tested against the fake layer.
- [ ] The reader parses what a real Solr of each supported line actually returns.
- [ ] Server state refreshes only on request or connection change — never on a timer.

**Acceptance:**
[demo step 35 — *connect to a server*](../../docs/demo/README.md#step-35-connect-to-a-server).

**Dependencies:** [the repository reader and field model](#step-3-repository-reader-and-field-model-done)

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
Collections, cores and the server's actual fields render, and the selected connection stays visible.

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
[38 — *show why a document scored*](../../docs/demo/README.md#step-38-show-why-a-document-scored). Completion comes from
the live schema, results render as a table, and the scoring explanation expands as a tree.

**Dependencies:** [the server reader](#step-11-http-client-connections-and-the-server-reader)

### Step 14: Drift view, upload and reload

**Actions:**

1. Render the model's disagreement states: repository-only fields, server-only fields, differing definitions.
2. Upload a configset and reload a collection, each invoked by name and confirming its target.
3. Where a change maps onto the Schema API, offer to apply it — as an action from this view, not from the editor.

**Success criteria:**

- [ ] All three disagreement categories render correctly.
- [ ] Upload and reload confirm and name their target server.
- [ ] No write occurs without explicit invocation.

**Acceptance:** demo steps
[39 — *the drift demo*](../../docs/demo/README.md#step-39-the-drift-demo) and
[40 — *resolve it*](../../docs/demo/README.md#step-40-resolve-it). A field added to the repository and not deployed
shows as a difference; upload and reload clear it, naming the target server first.

**Dependencies:** [the repository reader and field model](#step-3-repository-reader-and-field-model-done),
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
   [Apache Camel](#step-19-apache-camel) depend on it being right. An endpoint is a URL *and* the credential that goes
   with it, since framework configuration resolves both from the same profile; a reported endpoint that cannot carry a
   username forces that step to bolt one on afterwards.
2. Make the interface declare the library each recognizer needs, and gate activation on the *module's* dependencies
   rather than on the file being edited — no Solr client on the classpath, no SolrJ recognizer. The spec argues this out
   under "Recognizing Solr usage"; what matters here is the ordering. It belongs in the interface on the first day,
   because a recognizer written without it assumes it may inspect anything, and retrofitting the gate afterwards means
   revisiting every recognizer built on top.
3. SolrJ recognizer: client construction supplies endpoints; `SolrQuery` builder calls, raw parameter strings,
   `SolrInputDocument` field names and `@Field` annotations supply field references.
4. Inspection flagging field references absent from the model, and completion for them.
5. **Silence where resolution fails.** Assert this in tests explicitly — precision matters more than recall.

**The two-module fixture needs more than the light test project.** `BasePlatformTestCase`
supplies one module with no real library dependencies, so the last criterion below cannot be written on it at all. The
cheap route is a `LightProjectDescriptor` adding a second module and putting a SolrJ artifact on one module's classpath
through `PsiTestUtil.addLibrary`; the expensive route is `HeavyPlatformTestCase`, which builds a real project per test.
Try the cheap one. This is recorded here because it is the sort of constraint that gets discovered halfway through the
step and misread as the test framework being broken — and because the gate in action 2 is worthless if the only fixture
that could disprove it is unwritable.

**Success criteria:**

- [ ] Field references resolve in builder calls, raw strings, document building and bean annotations.
- [ ] Unresolvable constructs produce no warning.
- [ ] A module with no Solr client on its classpath produces no findings at all, asserted on a fixture of two modules
  where only one depends on SolrJ.

**Acceptance:** demo steps
[41 to 44 — the field-name checks in Java](../../docs/demo/README.md#step-41-return-to-the-opening-bug), and [47 —
*volunteer the limitation*](../../docs/demo/README.md#step-47-volunteer-the-limitation). The planted typo in a filter
query is flagged, so is a field that never existed, so is the misspelled `@Field` annotation. Step 47 matters most:
constructs the plugin cannot resolve produce no warning at all.

**Dependencies:** [the repository reader and field model](#step-3-repository-reader-and-field-model-done)

### Step 17: Query syntax and the console bridge

**Actions:**

1. Treat the query string inside a literal as its own language, so it gets structure and highlighting.
2. Gutter action running that query in the console against a selected connection.
3. Navigation from a field name in code to its schema definition.

**Success criteria:**

- [ ] Query strings render structurally inside Java and Kotlin literals.
- [ ] The gutter action runs the query; navigation resolves when a configset is present.

**Acceptance:** demo steps
[45 — *show the query as a language*](../../docs/demo/README.md#step-45-show-the-query-as-a-language)
and [46 — *run it from where it lives*](../../docs/demo/README.md#step-46-run-it-from-where-it-lives).

**Dependencies:** [the recognizer interface and SolrJ](#step-16-recognizer-interface-and-solrj); the gutter action
additionally needs [the query console](#step-13-query-console), which is the Code track's one dependency on the Server
track. The language and navigation work does not.

### Step 18: Framework configuration

**Actions:**

1. Verify which platform framework-configuration APIs are available to plugins and in which editions. Prefer the
   platform's model over parsing configuration directly.
2. Declare optional dependencies so these features appear when the supporting functionality is present and the plugin
   loads normally when it is not.
3. Resolve a Solr URL **and its credentials** per profile with each framework's own precedence: Spring Boot profile
   files, **Quarkus inline `%profile.` prefixes in a single file**, Micronaut environments, MicroProfile ordinals.
4. Offer discovered endpoints as connection candidates. Never connect automatically.
5. Carry the credential with the endpoint. A username found beside the URL in a profile belongs to that profile's
   candidate, and on confirmation the secret is copied into PasswordSafe rather than re-read from the configuration file
   on each use. The spec sets the rules under "Recognizing Solr usage"; the consequence here is that a candidate is a
   URL *and* a credential, so the recognizer interface must be able to report both — which is
   why [the recognizer interface](#step-16-recognizer-interface-and-solrj) has to know about it before this step starts.
6. **Real project fixtures per framework**, not synthetic strings.

**Success criteria:**

- [ ] Boot profile files and Quarkus inline prefixes both resolve correctly.
- [ ] The plugin loads and functions with no framework support present.
- [ ] Discovered endpoints are offered, never adopted silently.
- [ ] Switching the active profile changes the offered username as well as the URL, asserted on the demo fixture, which
  carries a `dev` and a `staging` profile.
- [ ] A secret from a configuration file reaches PasswordSafe only after the user confirms, and never reaches the shared
  project file.

**Scope of the demo.** Only Spring gets a demo step. Each additional framework would need its own fixture project and
its own runtime on stage to show what the Spring fixture already shows, and the recognizer is the same code either way.
Quarkus, Micronaut and MicroProfile are accepted by fixture tests in this step instead. Quarkus is the one to get right,
for the reason the spec gives under "Recognizing Solr usage".

**Acceptance:**
[demo step 35 — *connect to a server*](../../docs/demo/README.md#step-35-connect-to-a-server), Spring only. The URL is
offered by following `${app.solr.url}` from the SolrJ client bean into the active profile, and is never connected to
automatically. The other three frameworks are accepted by their fixture tests here.

**Dependencies:** [the recognizer interface and SolrJ](#step-16-recognizer-interface-and-solrj)

### Step 19: Apache Camel

**Actions:**

1. Recognize Solr endpoint URIs in Camel routes as connection candidates.
2. Validate the URI's component options against the known set.
3. Check field references in route parameters and document construction.
4. Java and XML route definitions first. Where the IDE or an installed plugin models routes, read that model rather than
   writing another URI parser.

**Success criteria:**

- [ ] Endpoints recognized from Java and XML routes; options validated.

**Acceptance:** No demo step, for the same reason the other frameworks have none — a Camel demo needs its own fixture
project and a running route to show what a fixture test shows in a second. Accepted here by fixture tests: a Solr
endpoint in a Java route and in an XML route is offered as a connection candidate, and a misspelled URI option is
flagged.

**Dependencies:** [the recognizer interface and SolrJ](#step-16-recognizer-interface-and-solrj)

---

## Cross-cutting

### Step 20: CI gates

**Actions:**

1. Add `_default` and `sample_techproducts_configs` as fixtures.
2. Golden-file test running every inspection over both, asserting zero highlights. This gate must be in place before the
   release, not after.
3. Assert every registered inspection has a description file.
4. Assert documented versions match the compatibility matrix. This one only has teeth once
   [documentation](#step-21-documentation) has written the matrix; land the check here and expect it to pass vacuously
   until then.
5. Catalog tests per supported line.
6. **Adopt `verifyPlugin`.** It already runs in CI and no step claims it, which is exactly how a gate goes unattended
   until the day it fails and nobody knows whose it is. It earns a step of its own reasoning because no test can replace
   it: the suite compiles against the same platform version the plugin does, so an IntelliJ API removed under the plugin
   on a target bump is invisible to every test here and visible only to the Verifier. Pin the IDE builds it verifies
   against in the same place [documentation](#step-21-documentation)
   writes the compatibility matrix, so the two cannot drift apart — a matrix promising a build the Verifier never
   checked is worse than no matrix.

**Success criteria:**

- [ ] Zero false positives on both shipped configsets, enforced in CI.
- [ ] Missing description files and version drift both fail the build.
- [ ] `verifyPlugin` passes for every IDE build the compatibility matrix claims, and the set it checks is read from
  where the matrix is written rather than restated.

**Acceptance:** No demo step — this step *is* the automated gate. It is what stops the demo passing while the suite
quietly rots.

**Dependencies:** [inspections](#step-6-inspections-in-progress) for the golden-file gate;
[the factory catalog generator](#step-9-factory-catalog-generator-in-progress) for catalog tests

### Step 21: Documentation

**Actions:**

1. README and quick start; feature reference with screenshots and stated limits, including how match capability is
   derived and the wildcard caveat.
2. Inspection catalog assembled from the description files.
3. Contributor guide.
4. Compatibility matrix and changelog in keep-a-changelog format.
5. Marketplace listing: summary, screenshots, a recording of the headline features, tags, compatibility statement.

**Success criteria:**

- [ ] All release-blocking documentation exists and CI checks pass.

**Acceptance:** No demo step. The compatibility matrix written here is what makes the version-drift check
in [CI gates](#step-20-ci-gates) meaningful rather than vacuous.

**Dependencies:** [inspections](#step-6-inspections-in-progress) for the catalog content,
[CI gates](#step-20-ci-gates) for the checks that police it

---

## Validation checklist

The cross-track invariants no single step owns. Each step's own success criteria cover the rest.

- [x] Build, CI, coverage and documentation gates in place.
- [x] Package namespace decided — stays `org.apache.solr.ide`; see the activation gate overhaul.
- [ ] Editor features work with no connection.
- [ ] Server features work with no configset in the project.
- [ ] Code features stay silent where they cannot resolve.
- [ ] Zero false positives on both shipped configsets, CI-enforced.
- [ ] No write occurs without explicit human invocation naming its target.
- [ ] Release documentation published.

## Risks

Mitigations live in the steps; only the first entry states one, because it belongs to no step.

- **Scope exceeds what can be polished.** This plan is large and the quality bar is explicit. The track structure is the
  mitigation: Editor, Server and Code can each reach a shippable state independently, so if something must be cut, cut a
  whole track rather than leaving three half-built.
- **Coverage floor blocks UI work.** Six of the remaining steps land tool windows, annotators and PSI code that is
  awkward to unit-test, against an 80% Kover floor bound to `check`. Decide the response before it bites — package-level
  exclusions, or moving the floor to changed lines and letting SonarCloud's new-code gate be the real defence.
- **Code analysis produces false positives** —
  [the recognizer interface and SolrJ](#step-16-recognizer-interface-and-solrj).
- **Framework configuration works only on the author's machine** —
  [framework configuration](#step-18-framework-configuration).
- **A server version the plugin has never seen** —
  [the server reader](#step-11-http-client-connections-and-the-server-reader).
- **Reference resolution edge cases cause dangling renames** —
  [references and navigation](#step-5-references-navigation-and-find-usages-done), which is unit-tested
  before [rename](#step-8-rename) consumes it.

## References

- Spec: `specs/0002-solr-intellij-plugin.md`
- Demo runbook and acceptance harness: `docs/demo/README.md`
- Configuration file survey: `docs/solr-configuration-files.md`
- Plugin development tutorial: `docs/modern-intellij-plugin-development.md`
- IntelliJ Platform SDK: https://plugins.jetbrains.com/docs/intellij/
