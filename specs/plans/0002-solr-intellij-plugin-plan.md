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

**`solrconfig.xml` stopped being a file the plugin merely read for field names.** Six merged pull requests under
[step 25](#step-25-solrconfigxml-as-a-first-class-surface-done) gave it parameter-name completion and
documentation over 340 generated parameter names, the closed set `defType` accepts, completion and quick
documentation and Ctrl-click on the classes it names, schema field names offered inside the sixteen parameters
known to hold them, and two inspections that fire where a *broken* configuration would previously have passed
silently — a `facet.field` or `sort` naming a field that cannot serve it. Eleven inspection classes are now
registered in total. **This paragraph used to end here by saying the file still lacked its own structure, and
that sentence outlived what it described.** Action 1 of [step 25](#step-25-solrconfigxml-as-a-first-class-surface-done)
shipped in `#141`: typing `<` inside `<config>` offers Solr's own top-level vocabulary —
`requestHandler`, `query`, `directoryFactory`, `luceneMatchVersion` and the rest the generator read out
of `SolrConfig`'s own config-reading calls — not an echo of whatever sibling tags already sit above the caret,
and not a copy of the schema's vocabulary either. **Nesting is respected, and this is no longer merely the
design intent — a defect closed after this paragraph was first corrected proves it.** `cache`,
`featureVectorCache` and `HashDocSet` were being offered inside `<config>` as well as inside `<query>`,
where all three actually belong; the generator now follows the bytecode call chain that reads a nested
element off its parent node rather than stopping at calls made directly on `SolrConfig`, so an element
that chain reaches is placed under its real parent instead of defaulting to the root when the chain went
unfollowed. What completes inside `<query>` is not what completes inside `<config>`. [The manual suite's
STR-1 through STR-6](../../docs/manual-test-suite.md#10-completion--solrconfigxmls-own-structure-str) press this,
STR-1 and STR-3 with a recorded pass, and [screenshot catalog entry 10](../../docs/screenshots.md#10-solrconfigxmls-own-structure--10-completion-solrconfig-structurepng)
captures it against the sandbox.

**Two facts were recorded before anything showed them, and now two surfaces do.** The catalog's attribute defaults and
required markers went further than the class popup's `Accepts` table, which renders a name and a value type and stops;
[the per-attribute hover and the complete-configuration popup](#step-10-completion-validation-and-quick-documentation-done)
now render the rest, the first for the attribute under the caret and the second for every attribute a factory tag
accepts. [The dimmed restated default](#step-26-showing-that-an-attribute-restates-the-default-done) now reads the
same facts to make a different claim: not what Solr will supply, but that a written value need not have been written at
all. A field attribute that decides nothing is dimmed where it stands, with an intention that removes it — the first
time the plugin says something about a line without being asked, and it says it only where the model can prove the
field would be unchanged.

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
- [Step 6 — Inspections](#step-6-inspections-done) — **done**; all seven inspections shipped, and the sandbox gesture
  the last two lacked is written, so nothing is left to hold the heading open
- [Step 7 — Match hints and quick-fixes](#step-7-match-hints-and-quick-fixes-done) — **done**
- [Step 23 — Explaining and correcting what is already on screen](#step-23-explaining-and-correcting-what-is-already-on-screen-done) —
  **done**
  — out of numerical order deliberately: added after the rest, belongs here. Needs nothing the catalog provides.
- [Step 24 — Completing the schema's own vocabulary](#step-24-completing-the-schemas-own-vocabulary-done) — **done**
  — likewise. Corrects a dependency that parked field attribute completion behind the catalog, which it never needed.
- [Step 26 — Showing that an attribute restates the default](#step-26-showing-that-an-attribute-restates-the-default-done)
  — **done**; likewise added late, belongs beside the two above. Both halves ship: the dim and the
  intention that removes it, on every element that declares field properties — `<field>`, `<dynamicField>` and
  `<fieldType>` — and on the three that name an analysis factory, `<filter>`, `<tokenizer>` and `<charFilter>`,
  where the value is judged against the literal default the catalog read out of that factory's own code.
- [Step 27 — Saying what a property's value means](#step-27-saying-what-a-propertys-value-means-done) — **done**
  — likewise added late; belongs beside the three above. Extends the match-hint provider and the documentation provider
  both, so it needs the property table plus the two steps that already extend them.
- [Step 25 — solrconfig.xml as a first-class surface](#step-25-solrconfigxml-as-a-first-class-surface-done)
  — **done**; the largest step here. It was split when it started, and every pull request in that split has
  merged: structure completion and the near-miss inspection were the last two. Its final criterion — the
  shipped configsets producing zero findings — was closed by [CI gates](#step-20-ci-gates-in-progress),
  which vendored the fixtures and asserted it, holding one rule out by name.
- [Step 28 — Declarations as targets](#step-28-declarations-as-targets-done) — **done**
  — likewise added late, and it belongs *before* rename rather than beside the popup work above. It
  closes a criterion [references and navigation](#step-5-references-navigation-and-find-usages-done)
  claimed and does not have, and it builds the target rename would otherwise have to build first.
- [Step 8 — Rename](#step-8-rename-done) — **done**
- [Step 29 — What an attribute means](#step-29-what-an-attribute-means-done) — **done**; added late
  and listed here because it was reachable from the step bodies and from nowhere a reader scans for
  status, which is also how it stayed marked *not started* for as long as it has been built.
- [Step 9 — Factory catalog generator](#step-9-factory-catalog-generator-done) — **done**; the generator is built,
  every fact it emits is asserted, and selection now reads the line from whichever arm answered. The server arm owes
  this step nothing further: the catalogs already honour a selection a server produced, so what the
  [server reader](#step-11-http-client-connections-and-the-server-reader) still has to do is construct one
- [Step 10 — Completion, validation and quick documentation](#step-10-completion-validation-and-quick-documentation-done) —
  **done**; completion, validation, the class-value popup, the per-attribute hover and the factory's
  complete-configuration popup have all shipped. Action 3, dynamic field pattern awareness, turned out to be shipped
  across every surface that judges a field name and short in exactly one — a field type's usage sentence counted
  declared fields only — which is now fixed

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

- [Step 20 — CI gates](#step-20-ci-gates-in-progress)
- [Step 21 — Documentation](#step-21-documentation)

## Prerequisites

- [x] JDK 21 toolchain, green build, CI verified.
- [x] Solr and Lucene artifacts resolvable from Maven Central for both supported lines — verified: Solr 10.0.0 with
  Lucene 10.3.2, Solr 9.10.1 with Lucene 9.12.3. Needed by
  [the factory catalog generator](#step-9-factory-catalog-generator-done).
- [x] Solr's `-sources` artifacts resolvable for both lines, or a decision to ship the catalog without documentation
  text. Needed by
  [the factory catalog generator](#step-9-factory-catalog-generator-done), which cannot recover documentation
  from a compiled jar. **Verified resolvable for both lines**, and wired in: see Step 9 for what a resolved `-sources`
  jar can and cannot supply.
- [x] Local copies of the `_default` and `sample_techproducts_configs` configsets Solr ships, vendored verbatim and
  recording the Solr release they came from. The gate asserts *clean against what Solr itself ships*, which means
  nothing without naming which Solr. They are the clean fixtures
  for [inspections](#step-6-inspections-done) and the subject of the golden-file gate
  in [CI gates](#step-20-ci-gates-in-progress).

  **Four rather than two, and under `src/test/resources/shipped-configsets/<line>/<name>/` rather than the
  `testData` this line first named.** Each supported line ships its own pair and they are not the same files, so two
  would have left one line's catalog untested. `testData` was the wrong home twice over: that directory is gone —
  [rename](#step-8-rename-done) removed the scaffold it held — and a fixture read through the classpath needs no test-data
  path and no assumption about the working directory. Only the two files the plugin parses are vendored; `conf/` holds
  stopword lists and mapping tables nothing here reads, and a feature that comes to read one brings its file with it.
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
reads. [Rename](#step-8-rename-done) replaces them.

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
2. Name the factories that determine this in code, rather than reading them from the generated catalog.
   The spec's "The factory catalog" says why. *This line said "roughly fifteen" when it was written and
   the sets have since outgrown it; the count is dropped rather than corrected, because it drifted into
   the spec, the user guide and the code comment before anyone counted.*
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

**Every step in this track is done.** Thirteen steps: the six it was planned with — 5 through 10 —
plus seven added late, 23 through 29, as sandbox passes and design work found gaps. That is why the
numbering here runs out of order, and why several of the late ones argue for their own placement in
their opening lines. Count them from the headings rather than from this sentence; a first draft of it
said fourteen.

Two things are worth carrying forward rather than reading step by step. **Most of the last stretch was
not building but discovering what had already been built**: four steps were closed by auditing them
against the code and finding the work shipped while the plan still said otherwise, and only one of the
final four pull requests contained a new feature. And **the defects that closed them were found by
gestures rather than by tests** — a documentation provider that declined three of a tag's caret
positions, a field type's usage sentence that counted declared fields and not the patterns naming it.
A suite that asserts what a surface answers cannot see what it silently refuses; that is the standing
lesson this track paid for twice.

What the track does *not* close is the manual suite. Several checks are written and unpressed, and one
inspection's presentation — the unused field type, which reports something true rather than something
wrong — is recorded as an open question beside
[showing that an attribute restates the default](#step-26-showing-that-an-attribute-restates-the-default-done)
rather than settled here.

### Step 5: References, navigation and Find Usages (done)

**Actions:**

1. Reference providers for: a field's `type` to its field type; `copyField` source and destination to fields;
   request-handler parameters in `solrconfig.xml` to schema fields; a filter's resource attribute to the `stopwords.txt`
   or `synonyms.txt` it names.
2. Expose a reference-graph query surface that [inspections](#step-6-inspections-done) and
   [rename](#step-8-rename-done) reuse.
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
  arrives — [rename](#step-8-rename-done).

**Success criteria:**

- [x] All four reference kinds resolve.
- [x] Searching a declaration's references reaches every one of them, including across the file
  boundary — `ReferencesSearch` on a `<field>` returns the `qf` parameter naming it.

**One clause of the original criterion moved out, and has since been delivered elsewhere.** It read
*"All four reference kinds resolve; Find Usages returns every reference"*, and the second half was
ticked in error: the search half is real and asserted here, but nothing in this step made a
*declaration* into a target the platform will accept, so the Alt-F7 the clause describes answered
*Cannot search for usages from this location*. That half moved to
[declarations as targets](#step-28-declarations-as-targets-done) and landed there, which is where the
Alt-F7 criterion and its fixtures now live. Everything this step built is done and none of it
changed.

**Acceptance:** demo steps
[22 — *navigate to a field type*](../../docs/demo/README.md#step-22-navigate-to-a-field-type),
[23 — *navigate along a copyField*](../../docs/demo/README.md#step-23-navigate-along-a-copyfield),
and [24 — *cross the file boundary*](../../docs/demo/README.md#step-24-cross-the-file-boundary).
Demo step 27 moved to [declarations as targets](#step-28-declarations-as-targets-done) with the criterion
it belongs to: it now performs the search from the declaration, which is the gesture this step
refused.

**Dependencies:** [the repository reader and field model](#step-3-repository-reader-and-field-model-done)

### Step 6: Inspections (done)

Where the zero-false-positive requirement gets teeth.

**Actions:**

1. Implement: dangling `copyField` source or target; a field naming an undeclared field type; handler naming a
   nonexistent field; relevance parameters on non-indexed fields; unused field types; known-bad analyzer chain
   orderings; configuration elements the targeted Solr no longer accepts. **Seven, not the six an earlier revision
   listed** — the undeclared-field-type check shipped and was recorded in the success criteria without ever joining this
   list, so the two counts disagreed and the criteria were right.

   **The seventh was written as "removed in the targeted line" and shipped as something else, because the measurement
   that unblocked it also showed the original had nothing to report — for *elements*.** The two supported lines'
   *elements* differ by exactly one, `featureVectorCache`, *added* in 10, so an element-comparison rule would never
   fire until a future line drops one. **That is narrower than "nothing was removed", which this paragraph originally
   claimed and which is at least partly false: two `requestDispatcher` *attributes* are confirmed removed by name** —
   `handleSelect` ([SOLR-17742](https://issues.apache.org/jira/browse/SOLR-17742)) and
   `requestParsers/addHttpRequestToContext` ([SOLR-17741](https://issues.apache.org/jira/browse/SOLR-17741)).
   **Two more, `requestParsers/enableRemoteStreaming` and `requestParsers/enableStreamBody`, are absent from Solr
   10's `EditableSolrConfigAttributes.json` rather than confirmed removed** — that resource enumerates
   *runtime-editable* attributes, so absence from it is evidence the Config API can no longer touch them, not evidence
   Solr no longer reads the element at all, and the two are not asserted as removed on that evidence alone. Either way
   the two lines' attribute counts read 36 and 32 a few paragraphs below rather than matching, so something genuinely
   shrank.** The claim was wrong; the decision it was given to justify was not, because the rule this step needed
   compares *elements*, and elements alone really did not shrink. A configset carrying `handleSelect` and targeting
   Solr 10 is exactly the finding an *attribute*-comparison rule would report, and nothing here builds one — this
   correction is to the stated reason, not to what shipped. What the same pass did find is five elements Solr still
   reads and no
   longer accepts, four of which stop the core starting. That is the rule that shipped: an element carrying a retirement
   notice, reported in Solr's own words. The line-comparison version is worth writing the day a line removes something,
   and costs nothing to add then, since the per-line vocabularies are already generated.
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
[completion, validation and quick documentation](#step-10-completion-validation-and-quick-documentation-done)
rather than here: `SolrUnknownAttributeInspection` and `SolrInvalidAttributeValueInspection`
are catalog-backed and validate an attribute rather than a reference.

**Three numbers describe this step, and reading one of them for another is the mistake this paragraph exists to
prevent.** Seven inspections are planned here and all seven are built. Eleven inspection classes are registered in
`plugin.xml`, which is not this step's seven: `SolrUnknownAttributeInspection` and
`SolrInvalidAttributeValueInspection` belong to
[completion, validation and quick documentation](#step-10-completion-validation-and-quick-documentation-done),
`SolrMisspelledParameterInspection` to
[solrconfig.xml as a first-class surface](#step-25-solrconfigxml-as-a-first-class-surface-done), and
`SolrUnsupportedFieldOperationInspection` to the field-capability work that motivated it. There are fifteen
[manual suite](../../docs/manual-test-suite.md) INSP checks, which is a third count again and breaks down as neither
of the other two: **eight** of them press this step's seven, the dangling-`copyField` inspection getting a second
check for reacting to a live edit; **six** press the four classes belonging to the three steps named above, the
field-capability rules taking three of those between them; and **one** restores the baseline rather than testing
anything. Read a count against what it counts; "eleven inspections exist" is true and says nothing about this step's
progress.

**Success criteria:**

- [x] Every inspection fires on crafted-bad fixtures and on nothing clean.
    - [x] Dangling `copyField` source or destination.
    - [x] A field naming an undeclared field type.
    - [x] A handler parameter naming a field the schema does not declare.
    - [x] A relevance parameter naming a non-indexed field.
    - [x] An unused field type.
    - [x] A known-bad analyzer chain ordering.
    - [x] A configuration element the targeted Solr no longer accepts, in Solr's own words.

All seven are built. The last one's dependency closed when the generator learned to read the `solrconfig.xml` element
vocabulary, which carries each retirement notice as the literal Solr ships beside the code that rejects it — so the
inspection asserts nothing of its own and quotes Solr instead.

**What held the heading open after that was a gesture rather than a rule, and both now exist.** Every one of the
seven is reachable by hand in the sandbox: the last two to get one are the non-indexed relevance check at INSP-15 and
the discontinued element at INSP-13. INSP-13 has been pressed; INSP-15 has been audited against the demo lines it
names and the catalog row its rule turns on, and not pressed. **That distinction is the manual suite's to record and
not this step's to wait on** — a step ships a gesture with an outcome it can produce, and whether anyone has pressed
it is what [the pass log](../../docs/manual-test-suite.md) exists to say. Reading a pass out of this heading is the
same mistake as reading a status out of the code.

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
   [the catalog](#step-9-factory-catalog-generator-done).
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
4. Nothing here needs [the factory catalog](#step-9-factory-catalog-generator-done). The catalog-backed half of
   documentation and completion stays in
   [completion, validation and quick documentation](#step-10-completion-validation-and-quick-documentation-done).

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

**Dependencies:** [inspections](#step-6-inspections-done) for the quick-fixes;
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
[completion, validation and quick documentation](#step-10-completion-validation-and-quick-documentation-done),
which waits on [the factory catalog](#step-9-factory-catalog-generator-done). Only *factory*
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
   [completion](#step-10-completion-validation-and-quick-documentation-done) already requires.

**What shipped:** all four actions, in `SolrConfigsetCompletionContributor` against the widened `SolrFieldProperties`,
covered by `SolrSchemaVocabularyCompletionTest`.

**Class-name completion in the same contributor is not this step.** The tests reading
`solr.` implementations for a `fieldType`, a tokenizer and a filter, and asserting the offered set follows the declared
Solr line, belong to
[the factory catalog generator](#step-9-factory-catalog-generator-done) and
[completion, validation and quick documentation](#step-10-completion-validation-and-quick-documentation-done).
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

### Step 25: solrconfig.xml as a first-class surface (done)

Numbered last because it was added last; it belongs in the Editor track after
[the factory catalog](#step-9-factory-catalog-generator-done), which it depends on entirely.

The spec settled the scope question under "`solrconfig.xml` gets the same treatment as the schema". Until now the file
was read only for the field names it mentions, which gave the most-edited file in a configset the least support.

**This is the largest step in the configuration surface and should be split when it starts.**
It is written as one step because the pieces share a dependency and a shape, not because it is one pull request.

[The design record](../../docs/design/archive/2026-08-07-solrconfig-intelligence/design.md) supplies the design this step
was written without, [`specs/0002-solrconfig-xml-intelligence.md`](../0002-solrconfig-xml-intelligence.md) specifies the
requirements, and [the plan beside the design](../../docs/design/archive/2026-08-07-solrconfig-intelligence/plan.md) is
the split this step asked for: five pull requests, of which only the first is startable today.

**Nothing here is startable until the groundwork lands, and that is a property of the dependencies rather than of the
work.** Action 1 waits on where the element vocabulary comes from, actions 2 and 3 wait on the catalog, action 4 waits on
whether Java PSI arrives as an optional dependency. The unblocked pieces are the shared foundations the other four sit
on — pinning what attribute completion does in this file today, collapsing the duplicate "schema and `solrconfig.xml`"
file-kind predicate, and widening the parameter reader beyond `<str>` — and they ship first and alone, while the schema
suite is the only thing that can fail.

**That paragraph is now history rather than status.** Every dependency it names has since been answered: the catalog
grew, Java PSI arrived optionally, and the element vocabulary was measured out of the shipped jars — see
[the open questions below](#step-25-solrconfigxml-as-a-first-class-surface-done), which now record answers.
Actions 1 and 3 are the two that remain, and both are startable.

**Actions:**

1. Element and attribute completion for `solrconfig.xml`'s structure, from the catalog, replacing the platform's
   schema-less sibling echo. **Where the vocabulary for what nests inside what comes from is the first open question
   below** — this step was written naming the shipped `_default` and `sample_techproducts_configs` files as ground truth,
   and they remain the zero-findings fixture either way, but they are not currently a reachable data source.
2. Parameter-name completion inside `defaults`, `appends` and `invariants`, and documentation on each parameter.
3. Validation of what the catalog positively knows to be wrong, which is one rule: a misspelling of a parameter name it
   knows, corrected by a quick fix. **Never validation by absence** — a parameter the generator did not find is not
   thereby invalid, and `solrconfig.xml` accepts plugin classes from outside Solr. Flagging the unknown would produce a
   false positive on every project with a custom component.

   **This action originally carried a second rule — a value outside a set the catalog knows to be closed — and the
   specification declines it.** Closedness is knowable for only a minority of parameters: `defType` has a closed set of
   parsers, `rows` does not, and `bf` holds a function query with its own grammar. An inspection firing on the knowable
   minority while silent on the rest teaches the reader that an unflagged value was checked, which is the same failure as
   validating by absence wearing different clothes. Worth reopening as its own step once the catalog can prove closedness
   from bytecode rather than from a curated list.
4. Navigation from a `class` attribute to the plugin it names, where that class is on the project's classpath.
5. **Completion of schema field names inside the parameters already known to hold them** — `qf`, `pf`, `fl`, `sort` and
   the rest of the sixteen — and quick documentation on a field name written in one. **A fifth action, which this step
   did not ask for and should have.** It is the inverse of a capability that already ships: the unknown-field inspection
   tells a reader that `descriptoin` is not a field, and the list that lets it say so is the list that would have offered
   `description` before it was mistyped. It also depends on nothing — both the sixteen names and the schema's field list
   exist today — so unlike actions 1 through 4 it waits on no catalog and no open question. The completion contributor
   currently has no `solrconfig.xml` awareness whatsoever, so a reader gets the correction and never the suggestion.

   Scoped to the sixteen and no wider, or completion starts offering field names inside `rows` and `defType`. Boost and
   sort syntax stay untouched: `qf` offers `name` rather than `name^3`, a caret after `name^` is inside a boost, and a
   `sort`'s second token is a direction rather than a field.

**Which operations a field supports has to be settled first, and it is not an Editor-track concern.** Action 5 cannot
offer a field the inspections then underline, so the rule deciding whether a field is searchable, filterable, facetable or
sortable has to exist before it — and that rule has a consumer in every track. The configuration surface asks whether a
`qf` or `facet.field` names a field that can serve it; the code track asks whether an `addFacetField("x")` will be
rejected; the query console asks which fields completion should *offer* while a reader composes a query against a live
core. The specification promises "a single shared model of what fields exist and what they can do", and this is the second
half of that sentence, currently living inside one Editor-track inspection.

It also corrects that inspection. Solr answers a query against a doc-values-only field — `FieldType.getFieldQuery`
branches on `hasDocValues() && !indexed()` and reaches `SortedSetDocValuesField.newSlowRangeQuery`, byte-identically on
both supported lines — so the present warning is a false positive there, while `facet.field` and `sort` naming a field
with *neither* `indexed` nor `docValues` are accepted silently and rejected by Solr. Every one of these rules is a
disjunction, and the plugin has never expressed one: every property check today resolves a single property and compares
it. **This may deserve its own step in the model area rather than a slot under an Editor-track step**, which is a
placement decision this plan owns.

**Success criteria:**

- [x] Both configsets Solr ships produce zero findings from every inspection but one, which is the gate the spec
      already sets for inspections.
      [CI gates](#step-20-ci-gates-in-progress) vendored the fixtures and holds the assertion — all four of them,
      since each line ships its own pair. **It found one defect on the first run, and it was this step's rule**: the
      near-miss inspection reported `<str name="spellcheck">on</str>`, which all four configsets write, as a
      misspelling of `spellcheck.q`. A name the catalog knows members *below* is a family root and is now declined.
      One rule is held out of the gate by name — see that step for which and why.
- [x] A custom plugin class and its parameters produce no findings either. This is now a claim rather than a vacancy:
      the near-miss inspection exists, and a fixture puts a class and three parameter names Solr has never heard of in
      front of it.
- [x] Completion and documentation answer inside a request handler. Parameter names inside `defaults`, `appends` and
      `invariants`, the closed value set `defType` accepts, the `class` attribute's own classes, and the schema field
      names inside the sixteen field-holding parameters — each with quick documentation. The element and attribute
      structure around them was the last piece and is action 1's, which `#141` closed.
- [x] `pf2` and `pf3` in the same parameter list, neither flagged. Solr's parameter families genuinely contain distinct
      names one edit apart, so an edit-distance rule that fires on a name the catalog *knows* would report `pf3` as a
      misspelling of `pf2`. The rule fires only on a name the catalog does not know, and knownness is checked first.
- [x] What attribute completion offers in `solrconfig.xml` today is pinned by a fixture before the descriptor gate moves.
      The claim that the platform's schema-less mode echoes sibling attributes here was inferred from this plugin's own
      account of the platform rather than measured, and this file is made of same-named tags — so it was the worst case
      for that echo and the most important one to have measured. Pinned first, then the gate moved.

The last two are the criteria a split loses, because both catch silent wrongness rather than visible failure: the guard
never fires in a passing suite, and the fixture only matters before a change that would overwrite what it records.

**What shipped — all five actions, and the groundwork under them.** Ten pull requests, merged in
dependency order rather than in the order the actions are numbered:

- `#111` reads every parameter value tag — `<int>`, `<bool>`, `<long>`, `<float>`, `<double>` and not only
  `<str>` — and gives every editor feature one predicate for declaring which configset file it serves.
  No user-visible change, deliberately: it is the change that would otherwise have been made *underneath* a
  feature whose own tests would mask the regression.
- `#112` moves *which operations a field supports* into `model`, correcting the relevance warning on a
  doc-values-only field and adding the faceting and sorting checks that never existed. Every rule in that
  table is a disjunction, and it is the first one the plugin expresses.
- `#113` completes schema field names inside a handler's parameters — **action 5**.
- `#131` reads the plugin roots Solr declares in `SolrConfig.plugins` and catalogs their classes, which is
  the catalog extension the rest sits on.
- `#132` completes, explains and navigates the classes `solrconfig.xml` names — **action 4**, and with it the
  `class`-attribute half of action 1. The three surfaces were already general, so they gained the file by
  learning eighteen kind tokens rather than by growing a new provider.
- `#133` completes and explains the request parameters the file carries — **action 2** — from 340 parameter
  names and 44 query parser names per line, each with the first sentence of Solr's own Javadoc on the
  declaring constant.
- `#137` and `#138` answer where the element vocabulary comes from and then generate it: a pass over
  `SolrConfig`'s own config-reading calls, which is the groundwork the last two actions sat on.
- `#141` completes the elements and attributes `solrconfig.xml` accepts — **action 1**, and with it the
  present-day-behaviour fixture, pinned before the descriptor gate moved rather than after.
- `#140` reports a parameter that is almost one Solr reads — **action 3** — and `#144` reports an element
  Solr no longer accepts, which is the seventh inspection
  [that step](#step-6-inspections-done) was blocked on and which this vocabulary unblocked.

**What the last two actions turned out to need, recorded because the shape was the hard part.** Action 1
needed a generator pass over `SolrConfig`'s own
config-reading calls, producing the element names with their arity, joined to the 23 plugin elements and the
40 typed leaves the other two sources already give — and a rule that marks the five names Solr no longer
accepts. Action 3 needed nothing new once the parameter catalog landed.

**The arity half of that shape was built, proved correct, and then deleted**, which is worth recording because
the reasoning below still describes it. Every consumer of the column only ever asked whether an entry was an
attribute, so `SolrElementArity` became a boolean — behaviour-preservation shown by diffing the attribute sets
either way, 36 on Solr 9 and 32 on Solr 10, identical before and after. A `valueType` column went the same way
in the same change, having never had a reader at all.

**What "joined" means is the one part of that shape a reader cannot guess, so it is stated here rather than
left to the code.** The canonical key is the pair *(parent path, element name)*, not the name alone: the same
name legitimately appears under more than one parent, and a map keyed by name would silently keep whichever
source ran last. Where sources disagreed about arity the most specific answer won — `required` over
`repeated` over `single` — because `single` is also what a source says when it has nothing to add, so
treating it as an observation would let a source that knows nothing overrule one that watched Solr call
`getAll`. A name read without a parent is absorbed into the parented reading of the same name rather than
kept as a second row, since `SolrConfig` reads a nested element off its parent node and a pass reading
literals sees only the child. Every source that named an element is listed on the merged row, so a consumer
never has to know which declaration answered.

**Acceptance:** No demo step of its own yet; the runbook predates this scope.

**Dependencies:** [the factory catalog generator](#step-9-factory-catalog-generator-done), which grows to cover
this vocabulary.

**Where the element vocabulary comes from is half settled, and a sandbox pass showed which half.** The
plugin elements are declared: `SolrConfig` carries `public static final List<SolrPluginInfo> plugins`,
pairing each element `tag` with the `Class<?>` its `class` attribute must implement, both as plain
constants — so one generator pass yields 23 element names and the superclass each requires, identically
on both supported lines, three of them carrying a parent path so nesting comes with them. That same list
also generates the plugin-class roots the catalog step was going to hand-write, and declares sixteen more
than that table had.

**The configuration elements are a second source, and three of them are neither.**
`EditableSolrConfigAttributes.json`, shipped inside `solr-core` on both lines, describes `updateHandler`,
`query` and `requestDispatcher` as a nested tree of 40 leaf attributes, each typed and marked as attribute
or child element. But `<config>`, `luceneMatchVersion` and `dataDir` appear in neither list — they are
plain fields read through `get("…")`, and not runtime-editable so the JSON omits them. Hovering exactly
those three is the first thing a reader tries, and it is what showed the earlier claim that this was
settled to be too strong.

**A third source turned up, and it is the one the generator already reads.** Measured against both
shipped jars rather than reasoned about: `solr-core` carries no further descriptive resource — the only
non-class files are `EditableSolrConfigAttributes.json`, `ImplicitPlugins.json` and `security.json`, and
no configset ships inside the jar. What it does carry is the reading code, and `SolrConfig` reads its own
tree through a handful of named methods with the element name as a **literal argument**:
`get("dataDir")`, `childRequired("luceneMatchVersion", …)`, `getAll("deletionPolicy")`. That is the same
shape [the catalog generator](#step-9-factory-catalog-generator-done) already extracts for factory
attributes, where `getInt(args, "generateWordParts", 1)` yields a name, a type and a default. Pointing it
at `SolrConfig`'s config-reading calls yields **33 element names on 9.10.1 and 34 on 10.0.0**, with the
call itself carrying whether the element is single, repeated or required — `luceneMatchVersion` arrives
marked required because Solr reads it with `childRequired`. So no hand-written set is needed for the two
that mattered, and the general rule this step was written on holds: the vocabulary is generated.

`<config>` is the third and needs no source at all. It is the document root rather than something read
out of the tree, which is why it appears in no list of children — the same status `<schema>` has in the
other file.

**Two things the measurement found that the question did not ask.** The extraction is *noisy* and needs a
rule, not just a filter — and 33 and 34 are the raw counts, before that rule runs. `SolrConfig` also
reads five elements it no longer accepts: `mainIndex`, `indexDefaults`, `nrtMode`, `unlockOnStartup` and
`jmx`. Each sits beside a literal saying so, which is what makes "read to use" and "read to warn"
separable, but a generator that skipped the distinction would complete five elements Solr rejects.

**Four of those five are fatal rather than advisory, which is worth stating precisely because the
generated `discontinued` column is the only thing a reader will see.** `<indexDefaults>` and
`<mainIndex>` raise `SolrException(FORBIDDEN)` — the core does not start. `<nrtMode>` and
`<unlockOnStartup>` reach `XmlConfigFile.assertWarnOrFail` with its `fail` argument set, so they fail
too. Only `<jmx>` is what the word warning suggests: a `log.warn` pointing at `solr.xml`, with startup
continuing. So the plugin is not being helpful by flagging them — it is reporting a file that will not
load. And the per-line sets differ by exactly one entry,
`featureVectorCache`, added in 10 — which means the element data
[the seventh inspection](#step-6-inspections-done) is blocked on becomes derivable from this same
pass, while also showing that **no element was removed between the two supported lines**, so an
inspection comparing *elements* line to line would have nothing to report until a future line drops
one. **Attributes are a different question, and the answer there is not nothing**: two
`requestDispatcher` attributes are confirmed removed between 9 and 10 by name — `handleSelect`
(SOLR-17742) and `addHttpRequestToContext` (SOLR-17741) — and two more are absent from Solr 10's
runtime-editable resource without being confirmed removed outright; see
[the correction under action 1 above](#step-6-inspections-done) for both, and for why the weaker claim
is the one this document makes. Worth having on record even though it does not change what this
section decided, since the rule this step needed was about elements.

The distribution tarball and the vendored-files fallback are both retired regardless, and the shipped
configsets stay what this step's criteria want them for: the zero-findings fixture.

**Both questions are now closed, the first by measurement above and the second in the affirmative.**
`com.intellij.modules.java` arrived early rather than being deferred to Phase 3, which is the product decision this
plan owned; `#132` is where it landed. It came in *optional*, with its own `solr-withJava.xml`, so that class
navigation would be present in IDEA and absent elsewhere with the plugin loading either way — **and it has since been
made a hard dependency**, because the plugin targets IDEA and nothing else, so "elsewhere" was never a place. See
[CI gates](#step-20-ci-gates-in-progress), where the one-IDE scope is recorded.

### Step 26: Showing that an attribute restates the default (done)

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
3. Stay silent wherever the default is not knowable with confidence — **which is a narrower set than this action
   originally named, and the correction is the design decision this step made.** It said properties whose default
   depends on the field type, written when such a default could not be resolved at all; the catalog now carries the
   traits that resolve them, so `omitNorms` on a `solr.StrField` is knowable and dims. What stays silent is what the
   model reports as `UNDETERMINED` — a type naming a class outside the catalog, which is the ordinary case for a custom
   plugin. **Factory attributes have joined, and did so with no new machinery**, exactly as this line
   predicted: an attribute on a `<filter>`, `<tokenizer>` or `<charFilter>` is judged against the literal default
   the catalog read out of that factory's own constructor, and what stays silent there is a class the catalog does
   not carry, an attribute the class does not read, and — the one that matters — an attribute whose default Solr
   computes at runtime, which the catalog records as absent rather than guessing at.

**Success criteria:**

- [x] `indexed="true"` on a field dims and `indexed="false"` does not, and removing the dimmed attribute leaves the
  parsed model identical. The intention test compares every effective property before and after rather than the text,
  because a deletion that changed one would still produce plausible XML.
- [x] A property whose default cannot be *determined* never dims — the amended criterion. It previously read "whose
  default depends on the field type", which the catalog's traits made both too strict and no longer the real question;
  [the design record](../../docs/design/archive/2026-08-13-restated-defaults/design.md) carries the argument.
- [x] Nothing this step adds appears in the Problems view on a correct file.
- [x] An attribute on an analysis factory restating that factory's recorded default dims, and the same intention
  removes it. `ignoreCase="false"` on a `solr.StopFilterFactory` and `maxTokenLength="255"` on a
  `solr.StandardTokenizerFactory` both dim; the intention test compares every setting the filter resolves to —
  each written value folded together with the default it falls back to — before and after, not the text.
- [x] A factory attribute whose default the catalog does not carry never dims. Four separate silences, each
  proved by a test that was watched to fail: a class outside the catalog, an attribute the class does not read,
  an attribute Solr requires and therefore has no default for, and a class of the wrong kind — a tokenizer's
  attribute written on a `<filter>` finds the tokenizer's entry unless the kind is matched too.

**What shipped:** actions 1 and 2, on every element that declares field properties — `<field>`, `<dynamicField>` and
`<fieldType>`, which is the same set
[attribute-name completion](#step-24-completing-the-schemas-own-vocabulary-done) already answers for.
`SolrFieldProperties.restatesDefault` in `model` answers whether deleting an attribute would leave the same element —
the existing resolution with the element's own declaration set aside, which turns out to need no field at all, so
`resolve` and the new function share one tail and the dim cannot drift from the popup reporting the same defaults.
`SolrRestatedDefaultAnnotator` renders it and `SolrRemoveRestatedAttributeIntention` acts on it, both through one
predicate so the offer cannot disagree with the dim.

A field type needed no new rule — it *is* the layer a field resolves through, so it answers to Solr's defaults and its
own class's traits directly, which the same function expresses by being passed no type at all. **What the second half
did find is a defect in the first:** every property is legal on a `<fieldType>` and only some on a `<field>`, and the
field half compared any property it knew — so `enableGraphQueries`, which is type-only and defaults to true, dimmed on
a `<field>`. The conclusion was accidentally right and the reason wrong; Solr ignores that attribute there outright,
which is a different thing to tell the reader. Scope is now checked.

**`<dynamicField>` is included**, on the argument that the pattern is the only thing that makes one different: it names
a type exactly as a concrete field does, and none of these properties is about the pattern. The model had already
settled it — `FOR_FIELD` is the properties legal on a field *or* a dynamic field — so excluding it would have been the
editor disagreeing with the table it reads.

**The factory half needed one new function and one new branch, and that is the whole of it.**
`SolrClassEntry.restatesDefault` in `model` compares a written value against the literal the catalog recorded, and
`SolrRestatedAttribute` — the one predicate the dim and the offer both read — learned a third kind of element. The
intention needed no change at all, which is the property the sharing was built to buy rather than a happy accident.

**No layering, and that is what makes this half a different question rather than a wider one.** A field property
resolves through three tiers and can come out `UNDETERMINED`; a `<filter>` inherits nothing, so the only thing that
can make one of its attributes removable is that factory's own recorded default, and every uncertainty is a plain
absence. The comparison is on the literal text — `maxTokenLength="0255"` does not dim — which misses a real
restatement rather than inventing one, and is cheaper than a coercion table that would have to be right for every
value type before it could be trusted for any.

**Acceptance:** No demo step of its own. It is the editor-side answer to the question the property table answers in the
popup — which of these lines could go.

**Dependencies:**
[completing the schema's own vocabulary](#step-24-completing-the-schemas-own-vocabulary-done)
for the property table it reads. The factory half additionally needed the defaults column in
[the factory catalog generator](#step-9-factory-catalog-generator-done), **and that landed** —
each factory attribute carries its literal default and required marker where the bytecode proves
them, which is what this half now reads.

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

### Step 28: Declarations as targets (done)

Numbered last because it was added last; it belongs in the Editor track immediately before
[rename](#step-8-rename-done), which cannot start until it lands. Read the section it sits in, not the
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

Distinct from [Step 10's action 3](#step-10-completion-validation-and-quick-documentation-done),
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
fixtures in [Step 8](#step-8-rename-done) rather than inheriting an untested capability here. The second:
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

[The design record](../../docs/design/archive/2026-08-04-declaration-targets/design.md) carries the
route comparison — why the POM declaration searcher rather than the Symbol API — and the bounds on
the configset walk.

### Step 29: What an attribute means (done)

Numbered last because it was added last; it belongs in the Editor track beside
[explaining what is already on screen](#step-23-explaining-and-correcting-what-is-already-on-screen-done),
whose gesture it completes.

A tag offers a caret three positions and two of them answer. Hovering `<fieldType>` explains the
element; hovering `solr.TextField` explains the value; hovering `class` — the attribute's own name —
says nothing, and neither does `name`, `type`, `source`, `dest`, or either attribute on the schema
root. **Found in a sandbox pass rather than by reading the code**, which is now the second defect in
this provider that no test could see: a suite that asserts what a provider answers cannot notice what
it declines.

`SolrSchemaDocumentationProvider.documentedProperty` holds two of the three causes in four lines —
a tag check that excludes `<schema>` outright, and a `byName` lookup that knows only *field
properties*, so `name` and `class` fall through even on the tags that pass the check. The third is
different in kind: a factory attribute does answer, and answers *a whole number*, which is a type
rather than a meaning.

**Actions:**

1. A meaning for the structural attributes — `name`, `class`, `type`, `source`, `dest` — keyed by
   element and attribute together, because `name` on a `copyField` is not a field name and must not
   be described as one.
2. The schema root's `name` and `version`, with `version` answering specifically: what the attribute
   decides in general, and what *this* value decides here. `SolrSchemaVersion` already computes that
   for every field-property popup; this surfaces it at the attribute that causes it.
3. A hand-written table of what the common factory attributes do, shown beside the type and default
   the catalog already proves. Silent for anything not on it.

**Action 3 reverses [Step 10](#step-10-completion-validation-and-quick-documentation-done), and
the reversal is deliberate.** That step declined per-attribute prose because the catalog is generated
from bytecode and Javadoc is per class, so no generated source carries it — all true, and it stopped
one inference short. The rule this plugin holds is *do not invent facts*, not *do not record ones
that are known*; a written table of what `minGramSize` does invents nothing. Nor does the
maintenance argument transfer: the catalog had to be generated because it is thousands of attributes
that move every Solr line, and this is the two dozen a reader hovers, unchanged since 2012.

**Success criteria:**

- [x] `name`, `class`, `type`, `source` and `dest` each explain themselves, and say something
  different where the element makes them different.
- [x] `<schema version="1.6">` states what the version decides and what it defaults `docValues` to
  here; `<schema name=…>` says it carries no behaviour.
- [x] `minGramSize` and `maxGramSize` on an `EdgeNGramFilterFactory` say what they do, alongside the
  required marker and value type already shown.
- [x] **An attribute the table does not list keeps exactly the popup it has today.** The regression
  that matters is a popup appearing where none should, or an existing one losing what it proved.
- [x] Nothing outside a configset, and no element the plugin does not model, answers at all.

**Acceptance:** hovering `minGramSize` in the demo's `text_prefix` chain
(`managed-schema.xml:48`) and `version` on the demo schema root.

**Every criterion was met by the shipping change and none of them was ticked, which is worth more
than the tick.** The behaviour landed in one change and the sandbox notes that followed it, and this
section then said *not started* for as long as the feature has existed — so anyone reading the plan
for what to build next would have rebuilt it. The fourth criterion is the one that needed more than
a tick: two named positions asserted their own silence, and nothing asserted the *set* — which is
the exact shape of coverage that let this provider ship the same class of defect to a sandbox twice,
since a suite of individual positions cannot notice a new one appearing beside them.
`SolrAttributeDocumentationTest` now walks *every* attribute of a schema carrying the
unmodelled elements alongside the modelled ones and pins the whole set of positions that answer, and
does the same over a `solrconfig.xml`, where the table must stay silent because its vocabulary has a
different source. Keying the lookup by attribute name alone — the mistake the pairing exists to
prevent — fails both, naming the positions that gained a popup.

**Dependencies:** [match hints and quick documentation](#step-7-match-hints-and-quick-fixes-done) for
the provider, [the factory catalog generator](#step-9-factory-catalog-generator-done) for the
type and default this sits beside, and
[the repository reader and field model](#step-3-repository-reader-and-field-model-done) for
`SolrSchemaVersion`.

[The design record](../../docs/design/archive/2026-08-06-attribute-meanings/design.md) carries the
bounds on the hand-written table and the argument for why it is not the invention this plugin
refuses.

### Step 8: Rename (done)

**Actions:**

1. Rename fields and field types, updating every reference through the graph built by
   [references and navigation](#step-5-references-navigation-and-find-usages-done).
2. Replace the plugin scaffold's leftover `src/test/testData/rename/` placeholders —
   `foo.xml` and `foo_after.xml`, which no test reads — with configset before/after pairs asserting no dangling
   references remain.

**Success criteria:**

- [x] Every resolved reference updates; no dangling references after rename.
- [x] No scaffold fixtures remain under `src/test/testData/`. The directory is gone; nothing read
  `foo.xml` or `foo_after.xml`, which is why they survived this long.

**A dynamic field rewrites only the references that spell it, and that is a decision rather than a
limitation.** [Declarations as targets](#step-28-declarations-as-targets-done) made a name the pattern
*supplies* — `body_t` under `<dynamicField name="*_t">` — a reported usage, which is correct and is
the point of it. Rename conflates two relations that part company exactly there: *is a usage of* and
*should become the new name*. Left alone, `PsiReferenceBase` substitutes the declaration's new name
into the reference's range and writes `qf">name^3 *_txt` — a pattern where a field name belongs,
which Solr rejects. So the rewrite is confined to references spelling the declaration literally.

The name left behind then matches nothing, and the alternative considered was carrying it across —
`body_t` to `body_txt`, mechanical rather than invented, since the model already matches the two.
That was not taken: the consequence of leaving it is *reported* rather than silent, because
`SolrUnknownFieldReferenceInspection` fires on the orphaned name from the same position, in Solr's
vocabulary. `SolrRenameTest` asserts the report as well as the omission, since the omission alone
would be the silent breakage this plugin's posture forbids.

**Acceptance:**
[demo step 34 — *rename across files*](../../docs/demo/README.md#step-34-rename-across-files). Renaming a field updates
its copy rules *and* the `qf` line in `solrconfig.xml`.

**Dependencies:** [references and navigation](#step-5-references-navigation-and-find-usages-done) for the graph;
[declarations as targets](#step-28-declarations-as-targets-done) for the target itself, and it is a prerequisite rather than
a neighbour. `renameElementAtCaret` on a declaration throws *element not found in file*: it resolves the same null
target Alt-F7 does rather than reaching past it to the tag. So this step needs that one to *gain* a target, not to
*suppress* a wrong one, and the `<field>`-tag corruption
[the developer notes](../../docs/modern-intellij-plugin-development.md) warn about is not reachable from here.

### Step 9: Factory catalog generator (done)

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
  [completion, validation and quick documentation](#step-10-completion-validation-and-quick-documentation-done)
  validate against; the default and required marker are what the factory half of
  [quick documentation](#step-10-completion-validation-and-quick-documentation-done) and
  [showing that an attribute restates the default](#step-26-showing-that-an-attribute-restates-the-default-done)
  will read.
- `solr-analysis-extras` resolved alongside `solr-core`. Without it the catalog had Japanese and Korean analysis and no
  Chinese at all, which is the kind of gap a count never shows.
- `SolrClassCatalog` and `SolrVersionSource`, which record whether the line was decided by the configset or by the
  fallback.

**The server arm turned out to owe this step nothing**, which is what closed it. The reasoning that kept the selection
criterion open was that a three-armed rule cannot be correct while one arm is unreachable — but the arms are not three
implementations. Every catalog decides from the selection's *line* and never reads its `SolrVersionSource`, so a
selection a connected server produced is honoured today exactly as a configset's declaration is; `SolrCatalogSelectionTest`
asserts that by asking all three catalogs about a `SERVER`-sourced selection. What
[the server reader](#step-11-http-client-connections-and-the-server-reader) still has to do is *construct* one, which is
its own work rather than an unfinished rule here.

**What the same test found genuinely open was the fallback.** "Then the newest supported line" is implemented as
`SUPPORTED_LINES.first()`, in three objects, two of which keep their own copy of the list — and nothing said the list was
newest-first. Reversing `SolrElementCatalog`'s copy sent every unsupported and undeclared configset to Solr 9's element
vocabulary with the entire suite still green. The catalogs are now asked with named witnesses that separate the lines —
a class Solr 10 added, one it removed, a parameter it dropped, the cache element only 10 has — so a reversed copy fails
rather than answers.

The attribute pass records each attribute's value type and, where the bytecode proves them, its literal default and
required marker — the two facts the factory half of
[quick documentation](#step-10-completion-validation-and-quick-documentation-done) and
[showing that an attribute restates the default](#step-26-showing-that-an-attribute-restates-the-default-done)
consume.

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
- [x] Selection order is correct and the answering source is recorded. A declared `<luceneMatchVersion>` answers from
  its own line and the selection says the configset decided it; nothing declared, or a line this build does not ship,
  falls back to the **newest** supported line and says so. `SolrCatalogSelectionTest` asks all three catalogs each
  question with witnesses that separate the lines, rather than comparing one catalog read against another — the shape
  that agrees with itself. **The connected-server arm is covered too, on the only half this step owns**: the catalogs
  read a selection's line and never its source, so a `SERVER`-sourced selection already answers from the line it names.
  Producing one is [the server reader](#step-11-http-client-connections-and-the-server-reader)'s work.
- [x] The catalog regenerates from a clean build. Measured: `./gradlew clean build` on a worktree with no build
  directory regenerates all four resources per line and passes. What holds it afterwards is an assertion rather than a
  memory of one run — `SolrCatalogResourceTest` reads the generator's `outputDirectory` and the build directory off the
  build itself, and requires that each shipped resource is byte-identical to the file the generator wrote *and* that
  the generator writes inside the directory `clean` removes. Both ways of losing this are silent: catalogs checked into
  `src/main/resources` would keep every other assertion green while the generated copy went stale, and an output
  directory moved out from under `build/` would survive `clean` and ship whatever the last run happened to write.

**Acceptance:**
[demo step 69 — *attribute completion on a factory with many
options*](../../docs/demo/README.md#step-69-attribute-completion-on-a-factory-with-many-options)
is the one to run by hand, for the reason in the criterion above.

**Dependencies:** [the activation gate overhaul](#step-2-overhaul-the-activation-gate-done)

### Step 10: Completion, validation and quick documentation (done)

**Done.** Completion for the schema positions whose valid set is closed — a field's
`type`, a `copyField`'s two ends, and the boolean properties — landed a capability per pull request ahead of this step,
because none of it needs the catalog. Positions where any value is legal are left to the platform: a partial list
implies the values not on it are wrong. Since then the catalog-backed half has largely landed too: completion offers the
`class`
classes and, inside an analysis tag, the factory's own attribute names, and the typed-attribute inspections validate an
attribute's value and name against the catalog — action 2. Quick documentation on `class` values shipped ahead of the
catalog's prose column and now carries the Javadoc summary where [Step 9](#step-9-factory-catalog-generator-done)
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
which is the distinction that matters, invention rather than absence.

**Action 3 was the last thing holding the heading, and auditing it changed what it meant.** Dynamic field pattern
awareness was never a capability waiting to be built here: every surface that *judges* a field name already goes through
`SolrFieldModel.resolve`, which applies Solr's own rule — a declared field beats a pattern, and among patterns the
longest literal part wins. Nothing flags a name a `<dynamicField>` supplies, completion offers the patterns themselves
italicised, hover and the match hints treat a pattern as the declaration it is, and `SolrShippedConfigsetTest` —
every registered inspection over the configsets Solr itself ships, on both lines — exercises all of it on schemas that
are mostly patterns.

**One surface was short, and it disagreed with an inspection about the same fact in the same file.** A `<fieldType>`
hover counted the *declared* fields naming the type and stopped there, so a type used only by `<dynamicField
name="*_s" type="string">` read as used by no fields — while `SolrUnusedFieldTypeInspection`, reading the same model,
counted that declaration as a user and correctly stayed quiet. The count now includes patterns, reported separately
rather than summed for the reason the `<schema>` sentence already separates them: a pattern is not a field, and one
wrong number would have replaced another. Its test had pinned the old behaviour, which is why nothing caught it.

**Actions:**

1. Completion for field types, factories, their attributes, and field attributes.
2. Structural validation flagging unknown factories and invalid attributes.
3. ~~Dynamic field pattern awareness.~~ **Done** — and mostly already true when it was audited. Every surface that
   judges a field name resolves through the model's own longest-pattern rule; what was outstanding was a `<fieldType>`
   hover that counted declared fields only, which is fixed.
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
   [showing that an attribute restates the default](#step-26-showing-that-an-attribute-restates-the-default-done).~~
   **Done.**

**Success criteria:**

- [x] Completion and validation work against the catalog.
- [x] Quick documentation resolves for factories and attributes.
- [x] A factory attribute answers on hover with its owner, value type, and — where the catalog carries them — its
  default or required marker, and stays silent about meaning it cannot cite.
- [x] A factory tag's documentation shows its complete configuration — literal defaults at their values, and attributes
  with no citable value marked rather than invented — distinguishably from written ones.
- [x] A field a `<dynamicField>` supplies is not reported as missing, and a pattern counts as a user of its type.
  The first half was already so on every judging surface; the second is the disagreement the audit found, where a hover
  and an inspection read one model and answered differently about one type.

**Acceptance:** demo steps
[68 — *completion inside an analyser chain*](../../docs/demo/README.md#step-68-completion-inside-an-analyser-chain),
[69 — *attribute completion on a factory with many
options*](../../docs/demo/README.md#step-69-attribute-completion-on-a-factory-with-many-options)
and [70 — *quick documentation on a factory*](../../docs/demo/README.md#step-70-quick-documentation-on-a-factory).

**Dependencies:** [the repository reader and field model](#step-3-repository-reader-and-field-model-done),
[the factory catalog generator](#step-9-factory-catalog-generator-done)

### Found by use on 2026-08-12, not yet placed

Three gaps found by opening the sandbox on the demo configset rather than by reading the code. They
are recorded here rather than folded into a step because each needs a placement decision this entry
deliberately does not make. **The first and third were defects in shipped behaviour and are fixed; the
second is an absence and is still open.**

**The Reference Guide line was wrong for every Solr 9 configset, and the page name was wrong for every
line but one — fixed.** `SolrVersionSelection.fromLuceneMatchVersion` kept only the major, so a configset
declaring any Lucene 9 version links into `guide/solr/9_0` — the Solr **9.0** guide, while the line
this plugin supports and generates its catalog from is 9.10.1. The pages resolve, which is why nothing
noticed: `9_0` returns 200 for all nineteen URLs the plugin builds. So the links are live and describe
a Solr nobody here runs, which is the failure mode
[`SolrReferenceGuide`](../../src/main/kotlin/org/apache/solr/ide/model/SolrReferenceGuide.kt)'s own
"a dead link is worse than no link" rule was written to avoid, arriving from the direction it did not
anticipate — not dead, merely about something else.

The two halves are one change, and fixing either alone makes things worse. `charfilterfactories.html`
was renamed to `charfilters.html` somewhere between 9.0 and 9.7: measured, `charfilters.html` returns
200 on 9_7, 9_8, 9_10, 9_11 and `latest`, and 404 on 9_0; `charfilterfactories.html` is the exact
inverse except that `latest` serves both. So the current page name is correct **only because** the
version segment is wrong — correcting the segment to `9_10` without correcting the page name turns the
char-filter link into the first real 404 the plugin has shipped.

**The line the link names is now the line the catalog answered from**, which is the invariant the fix is
built around: every other fact in that popup — the attributes, the defaults, the Javadoc sentence —
comes from `solr-9.tsv`, generated from 9.10.1. The catalog already recorded it, in a
`# Solr line 9, read from 9.10.1.` header the entry parser skips, so `SolrClassCatalog.guideSegmentFor`
reads the same fact for a second purpose rather than declaring a supported release twice —
`supportedSolrLines` in the build stays the only place one is named. A major with no shipped catalog
now falls back to `latest` instead of a constructed segment, so a configset from Solr 11 gets the
undated guide rather than a confident URL to nothing. All nineteen pages were re-measured on `9_10`
and `10_0` after the change: no non-200 responses.

Solr 10 was the same bug with no symptom — `10_0` 302-redirects to `latest`, so it worked and would
have stopped working the day 10.1 shipped.

**Nothing explains boost syntax.** A caret on the `^3` of `qf`'s `name^3` answers nothing, and
[the parameter completion work](#step-25-solrconfigxml-as-a-first-class-surface-done) made that
more conspicuous rather than less: PRM-3 asserts completion is *silent* after a `^`, which is right —
completing there would write `name^name` — but silence in completion was taken to settle the position,
and documentation was never asked. A reader who has just been told what `qf` is meets `^3` in the same
value and gets nothing. This is a new capability rather than a fix, and it is the first thing the
plugin would say about a parameter's *value* grammar rather than its name.

**A `<directoryFactory>` and a `<codecFactory>` were reported as explaining nothing** while
`SolrConfigClassValueTest` asserted both were explained and was green. **Both were true, and the
sandbox was right** — `#135` fixes it. The suite was not stale and the catalog was not short: the
answer had been assembled and nobody was asked for it.

`SolrClassReference.resolve` read the stub index through `JavaPsiFacade.findClass` with no dumb-mode
guard, so during indexing it raised `IndexNotReadyException`. **The throw did not stay local**, which
is the part worth keeping. Quick documentation does not begin at a documentation provider — the
platform first collects target symbols at the caret, which walks the references there — so the
exception escaped through `TargetElementUtil` and killed the whole popup, taking with it everything
this plugin could have answered from a configset it had already parsed and which needs no index at
all. Navigation is genuinely unavailable while indexing; the explanation beside it never was. The
schema's `class` values were affected identically, since it is one contributor for both files.

**The suite could not have found it, and that is a fact about the tests rather than about this bug.**
Every fixture test runs in smart mode, and every assertion in `SolrConfigClassValueTest` reached the
provider through a helper of its own — so it was testing whether the code produces the right answer
while the failure was whether anyone asks the code. The regression test therefore enters through
`IdeDocumentationTargetProvider`, where a reader enters; asking the provider would pass with the
defect present. **The general lesson: where a platform decides who answers, at least one test has to
start where the user starts.** That is unenforced elsewhere — the dumb-awareness rule in `CLAUDE.md`
now has a test behind it for exactly one contribution, and the others are still declarations.

`SolrDemoConfigsetProbeTest` was the throwaway probe that separated the two explanations; it did its
job and is gone, its cases promoted into the reference and class-value suites.

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

### Step 20: CI gates (in progress)

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

- [x] Zero false positives on both shipped configsets, enforced in CI. Four configsets rather than two — each line
      ships its own — and each selects its catalog from its own `<luceneMatchVersion>`, so both generated catalogs are
      exercised without this test naming a version. One rule is held out by name, below.
- [ ] Missing description files and version drift both fail the build.
    - [x] A registered inspection with no description file fails the build. Keyed on `shortName`, which is what the
      platform resolves the file by; proven by deleting one.
    - [x] **Solr version drift fails the build**, which turned out to have teeth today rather than waiting on the
      matrix. See below — the drift that was reachable is not the one this line was written about.
    - [ ] *IDE* version drift, which is what action 4 originally meant, and still waits on
      [documentation](#step-21-documentation) writing the matrix.
- [x] `verifyPlugin` passes for every IDE build the compatibility matrix claims, and the set it checks is read from
  where the matrix is written rather than restated. **Half of that sentence is deferred and the deferral is the
  point:** the set is now declared once, in `verifiedIdeBuilds` in `build.gradle.kts`, and Step 21 is to render the
  matrix from it rather than restate it beside it. Left unconfigured, the task verified against whatever the build
  happened to target — the same hole the pinned verifier version closed one shape larger.

**Actions 4 and 5 met in the middle, and what they found is that `supportedSolrLines` is not the single place it says
it is.** The build declares the lines; `SolrClassCatalog.SUPPORTED_LINES` and a private twin inside
`SolrElementCatalog` are hand-written copies of that list in another language, each carrying a KDoc promising it is
"kept in step with the `supportedSolrLines` the build declares" — a promise with nothing behind it. **Both directions
of drift fail silently.** A line declared with no generated resource reads as *empty*, deliberately, so a missing
catalog cannot take the editor down: completion and documentation would simply stop answering for that line with
nothing in the log. A line generated but never declared is quieter still — the resource ships inside the plugin and
every configset targeting it falls back to the newest line's answers.

`SolrCatalogResourceTest` closes it in both directions, and adds the three questions a per-line catalog test should
have been asking anyway: that all three resources for a line were read from one release, that the release is of that
line, and that the two lines are not one catalog shipped twice. The last is proven on `featureVectorCache`, the single
element that separates them — named in the test rather than left implicit, because one witness is a fragile thing to
rest on and the day a line drops something is the day it gains a second.

**What the Verifier reports, now that it runs against something named.** Compatible against `IU-262.8665.258`, with one
deprecated API usage: `ReadAction.compute(ThrowableComputable)` in `SolrProjectDetector.hasSolrClientLibrary`. Not a
failure and not scheduled for removal, but it is the class of finding no test here can produce, which is the argument
this step made for adopting the task at all.

**One IDE, and the list is complete rather than a first entry — the plugin targets IntelliJ IDEA and nothing else.**
Recorded here because a one-item list otherwise reads as one somebody forgot to extend.

**The descriptor implied something wider, and it no longer does.** `com.intellij.modules.java` was an *optional*
dependency, with `solr-withJava.xml` holding the one registration that needs Java PSI, so that an IDE without Java
would load the plugin and simply lack class navigation. That condition was true wherever it was ever evaluated — IDEA
has been a single unified distribution since 2025.3 and bundles Java — so what the split bought was not portability
but a claim, and one nothing verified. It is now a hard `<depends>`, the registration sits beside the others, and
`solr-withJava.xml` is gone. Phase 3 needs Java PSI unconditionally in any case, so this was due regardless of scope.

**What the first run found, which is the argument for having built it.** Nine of the eleven registered inspections were
silent on all four configsets. Of the two that were not, one was a defect: `<str name="spellcheck">on</str>` — the
idiom every one of the four uses to switch the spell checker on — was reported as a misspelling of `spellcheck.q`.
The rule now declines a name the catalog knows *members below*, since Solr's convention is that `X` enables a component
and `X.*` configures it. That is a gap in the vocabulary rather than in the file: the generator drops a constant ending
in a dot, correctly, and `spellcheck.` is the only form `SpellingParams` declares — every other component's toggle
survives because its interface happens to name it twice.

**The other is held out of the gate by name, and the reason is a question this step does not own.**
`SolrUnusedFieldTypeInspection` reports 45 types in `sample_techproducts_configs` and two in `_default`, all of them
true — Solr ships a palette of language and spatial types for fields the copier has not written yet. A *zero findings*
gate cannot hold the one rule here whose finding is a fact about the file rather than a defect in it. Both sides are
pinned: everything else must still report nothing, and a separate test asserts the held-out rule does fire, so silencing
it is not a way to pass. **What remains open is its presentation**, which belongs beside
[showing that an attribute restates the default](#step-26-showing-that-an-attribute-restates-the-default-done)
rather than here: 45 Problems-view entries on a configset Apache Solr ships and supports is the same complaint that step
answered for a restated default. Lowering the severity to `INFORMATION` was measured and does not work — the platform
drops `INFORMATION` inspections from the daemon and the grey-out goes with them — so the shape that would do it is an
annotator, which is what the restated-default dim already is. Recorded, not decided.

**Acceptance:** No demo step — this step *is* the automated gate. It is what stops the demo passing while the suite
quietly rots.

**Dependencies:** [inspections](#step-6-inspections-done) for the golden-file gate;
[the factory catalog generator](#step-9-factory-catalog-generator-done) for catalog tests

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
in [CI gates](#step-20-ci-gates-in-progress) meaningful rather than vacuous.

**Dependencies:** [inspections](#step-6-inspections-done) for the catalog content,
[CI gates](#step-20-ci-gates-in-progress) for the checks that police it

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
  before [rename](#step-8-rename-done) consumes it.

## References

- Spec: `specs/0002-solr-intellij-plugin.md`
- Demo runbook and acceptance harness: `docs/demo/README.md`
- Configuration file survey: `docs/solr-configuration-files.md`
- Plugin development tutorial: `docs/modern-intellij-plugin-development.md`
- IntelliJ Platform SDK: https://plugins.jetbrains.com/docs/intellij/
