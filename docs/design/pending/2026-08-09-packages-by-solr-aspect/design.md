    # Packages by Solr aspect: making the tree say which file a change is about

Counts and line numbers below are taken from `main` at 73f4508. Two pull requests in flight add files
to this tree; both land inside the structure proposed here without changing its shape, which is noted
where it matters rather than folded into the counts.

## Problem

The package a class sits in says what the IDE does with it and nothing about what part of Solr it is
for. `configset.parsing` holds `SolrSchemaParser.kt` and `SolrConfigParser.kt` as peers; the two read
entirely different files, share no vocabulary, and the only thing distinguishing them is the class
name. Every one of the nine capability packages under `configset` has that shape.

That is tolerable while one aspect dominates, and it stops being tolerable at the exact point the
plan is now approaching.

**The boundary already exists — it is just enforced at runtime instead of by the tree.** Three
classes open by asking which file they are looking at and returning early if the answer is wrong:

- `SolrUnknownFieldReferenceInspection:49`
- `SolrNonIndexedRelevanceFieldInspection:73`
- `SolrConfigsetReferenceContributor:126`

all spelling the same line, `SolrConfigsetFileKind.forFileName(...)?.isSolrConfig != true`. A
distinction re-asserted three times inside method bodies, on every highlighting pass, is a package
boundary that has not been given a name. A fourth contributor written without the guard produces
findings on the wrong file, and no build gate says so.

**The counts make the mismatch concrete.** Of the ten files in `configset.inspection`, six are schema
checks, two are `solrconfig.xml` checks, and two are shared helpers. Nothing in the path
distinguishes them.

**Two files already carry both aspects inside one class**, and they are the largest in their
packages: `SolrConfigsetCompletionContributor` (341 lines) and `SolrConfigsetDocumentationProvider`
(363). `SolrConfigsetReferenceContributor` (288) makes three.

Against that, [Step 25](../../../../specs/plans/0002-solr-intellij-plugin-plan.md) — described in the
plan as "the largest step in the configuration surface", and one that "should be split when it
starts" — adds element completion, attribute completion, parameter completion, parameter
documentation, catalog-backed validation and class navigation, all of it for `solrconfig.xml`. With
the tree as it stands, most of that lands inside those same three files.

The same argument applies twice more, unprompted. `server` holds one file today and is the
destination for Steps 11 through 15: an HTTP and JSON client, connection storage, the server reader,
three tool windows and a document editor. `code` does not exist yet and is the destination for
Steps 16 through 19: a recognizer interface, SolrJ, four framework-configuration dialects and Camel.
Fixing `configset` alone would schedule this conversation twice more.

## Goals

- The path names the part of Solr a change is about, not only the IDE gesture it uses.
- The three runtime `isSolrConfig` guards become a compile-time fact about where code lives.
- Step 25 lands as new packages rather than as growth inside three existing files.
- One principle covers all three surfaces, so `server` and `code` are decided before they are built
  rather than after they have gone wrong.
- No behaviour changes. Every move is verifiable by a green `./gradlew build`.

## Non-goals

- **Not a namespace change.** It stays `org.apache.solr.ide`; the plan records that question as
  closed.
- **Not a rewrite.** The overwhelming majority is `git mv` plus package and import lines. Three files
  split; nothing is redesigned.
- **Not a `plugin.xml` reorganisation.** It stays grouped by extension point, which is how the
  platform reads it.
- **Not a new build gate.** The invariants below are prose, like every other rule in
  [code organization](../../../code-organization.md). ArchUnit was considered and rejected: it would
  add a dependency and a test tier to enforce rules that a reviewer reading an import list catches
  anyway.

## Design

### The principle: surface, then subject, then gesture

Three levels, and the third collapses when there is only one gesture.

1. **Surface** — where the knowledge comes from. `configset`, `server`, `code`. This is the
   specification's own framing under *the spine: one model, two sources, four views*, and it already
   holds.
2. **Subject** — which Solr thing this is about. For `configset` that is the file: `schema`,
   `solrconfig`. For `server` it is the concept: connections, collections, queries, drift,
   documents. For `code` it is the library: SolrJ, Spring, Camel.
3. **Gesture** — what the IDE does. `inspection`, `completion`, `reference`, `documentation`. Present
   under `configset.schema`, which has eight gestures. Absent under `server.collections`, which has
   one.

The middle level is the one missing today, and adding it is the whole change. The existing top and
bottom levels are correct and survive verbatim.

`model` remains outside all three and keeps its exception for the two reasons already recorded: both
surfaces read it, and it is the only package with no IntelliJ types anywhere in it. Three smaller
packages sit outside the surfaces for a related reason — they span two — and the rule that stops that
becoming a junk drawer is [below](#a-package-above-the-surfaces-must-name-the-two-it-spans).

### Why `model.schema` does not move under `configset.schema`

It is the obvious objection to the tree below — if `schema` is a subject, why does schema knowledge
sit outside the surface that reads it? — and answering it from today's imports gives the wrong
answer. Every consumer of `model` is currently under `configset`, because the server reader has not
landed. Measuring the code as it stands would prove the model belongs to the configuration surface,
and would be wrong for exactly one release.

The type settles it. `SolrFieldModel`'s atom is `SolrFact<T>(repository: T?, server: T?)`, with
`require(repository != null || server != null)` and an `effective` accessor that prefers the
repository. **It exists specifically to carry a server value.** Filing it under `configset` would put
the configuration-files surface in charge of a type whose purpose is to hold the other surface's
half, and `SolrConfigsetFacts` says the same thing in its own KDoc: one shape serves both sources so
that neither is privileged.

Three imports would go wrong the moment the Server track lands: Step 14's drift view rendering
`indexed=true` against the server's `false`, Step 11's reader parsing the schema API into the same
facts, and Step 16's SolrJ recognizer checking field references. Each would reach from one surface
into another surface's internals.

### `model` was doing two jobs, and one file was on the wrong side

The objection is still worth taking seriously, because it finds something real. `model` has been
serving as *the source-independent domain* and as *the pure, IntelliJ-free, plain-JUnit-testable
package*, and those are different jobs. `SolrAttributeVocabulary` qualifies for the second and not
the first.

Its two entry points take an XML tag name and an attribute name. The server has no tags and no
attributes — the schema API returns JSON — so it will never have a server half. It is not knowledge
about what a field *is*; it is knowledge about what the *file* may contain.

The test that separates them: **does the server reader need this to interpret what it fetched?** Yes
for everything in `model.schema` — the schema API returns `indexed`, `stored`, `omitNorms` and
analyzer chains. No for `SolrAttributeVocabulary`.

It joins `SolrClassCatalog` in `model.vocabulary` rather than getting a package of its own, because
they are one concept and already one call: `SolrAttributeVocabulary` delegates to the catalog for
analysis-factory attributes at lines 82 and 125, and Step 25 grows both together — "element and
attribute completion for `solrconfig.xml`'s structure, **from the catalog**." That also makes
`vocabulary` cross-*aspect* while staying configset-only, which is why it is a sibling of
`model.schema` rather than a child of either aspect.

**`model.vocabulary` could defensibly live at `configset.vocabulary`**, since no server or code
feature will read it. It stays in `model` because both files are pure, and `model` being *the*
IntelliJ-free tree is what keeps the test-tier rule a single sentence. Splitting purity across two
trees buys a truer boundary at the cost of the rule contributors actually have to remember.

### Which capability belongs to an aspect, and which does not

The tempting rule — *file by the file the code acts on* — is right for most of the tree and wrong in
one place that matters, and the plan is explicit about where.

[Step 28](../../../../specs/plans/0002-solr-intellij-plugin-plan.md) requires Alt-F7 on a schema
declaration to list "every reference, **including the ones in `solrconfig.xml`**", and its second
action walks "the owning **configset's** own reference positions".
[Step 8](../../../../specs/plans/0002-solr-intellij-plugin-plan.md) requires rename to update a
field's "copy rules **and** the `qf` line in `solrconfig.xml`". Those gestures start in one file and
finish in another, deliberately. Filing the machinery under `schema` would mean `configset.schema`
importing `configset.solrconfig` — which is the coupling this whole change exists to prevent.

So the rule is about anchoring rather than about knowledge:

> **A capability files under an aspect when the caret that triggers it is always in that aspect's
> file. A capability that traverses the configset by nature stays at the configset root.**

An inspection is anchored: it visits tags in one file and reports there. A completion provider is
anchored: the caret is in one file. A reference *contributor* is anchored: it decides which tags in
which file carry references. A declaration *searcher*, a rename processor and the reference *graph*
are not — they exist precisely to cross the boundary, and they belong to the configset as a whole.

That splits `configset.reference` in two, which is the one genuinely structural decision here. The
contributors go to the aspects. The targets, searchers, presentation and — when Step 8 lands —
rename go to `configset.navigation`.

### Cross-aspect knowledge flows down, never sideways

The invariant that makes the split worth having:

> **`configset.schema.*` must not import `configset.solrconfig.*`, and the reverse.** They share
> through `model`, `configset.reading`, `configset.editing` and `configset.navigation` — downward
> only.

This is the existing "capability packages never import each other" rule re-aimed at the axis where
the coupling actually lives. `SolrNonIndexedRelevanceFieldInspection` is the test case: it reads
schema field properties in order to judge a `qf` parameter, and it satisfies the rule because it
reaches that knowledge through `model`, never through `configset.schema`.

### The target tree

```
org.apache.solr.ide
├── SolrBundle.kt                    the only file that does not move
│
│   ── three cross-surface packages; each names the surfaces it spans ──
├── settings/                        configset + server: one settings group, two pages (Step 22)
├── query/                           code + server: the query language, shown in a Java literal
│                                    and in the console (Step 17, used by Step 13)
├── drift/                           configset + server: the comparison that is neither's (Step 14)
│
├── model/                           no IntelliJ types anywhere — rule unchanged
│   ├── SolrConfigsetFacts.kt          one shape for both sources, by design
│   ├── SolrFieldModel.kt              SolrFact / SolrAgreement — the merge spine
│   ├── SolrReferenceGuide.kt          documentation links by Solr version
│   ├── vocabulary/                    what a configuration file may legally contain
│   │   ├── SolrAttributeVocabulary.kt   which attributes each element accepts
│   │   └── SolrClassCatalog.kt          generated; Step 25 grows it to solrconfig plugins
│   └── schema/                        what a field *is*, whichever source said so —
│                                      SolrFieldProperties, SolrSchemaTypes, SolrSchemaVersion,
│                                      SolrTypeTrait, SolrValueType, SolrMatchAnalysis,
│                                      SolrMatchCapability
│                                      (`model/solrconfig/` is created by Step 25, not before)
│
├── configset/
│   ├── activation/                  unchanged — genuinely aspect-neutral
│   ├── reading/                     was `parsing`, minus the two aspect parsers
│   │   ├── SolrConfigsetReader.kt     the CachedValuesManager cache
│   │   ├── SolrConfigsetScanner.kt
│   │   └── SolrXmlDocuments.kt        DOM safety; both parsers depend on it
│   ├── editing/                     editor-path primitives both aspects share
│   │   ├── SolrInspections.kt         the do-not-fire-on-a-correct-file guard rails
│   │   └── SolrReplaceNameQuickFix.kt
│   ├── navigation/                  the gestures that cross the file boundary by design
│   │   ├── SolrDeclarationPresentation.kt
│   │   ├── SolrDeclarationSearcher.kt
│   │   ├── SolrDynamicFieldSearcher.kt
│   │   └── (Step 28's POM target; Step 8's rename)
│   │
│   ├── schema/                      managed-schema.xml, schema.xml
│   │   ├── SolrSchemaPsi.kt           what every gesture here shares
│   │   ├── parsing/        SolrSchemaParser
│   │   ├── inspection/     AnalyzerChainOrder, DanglingCopyField, InvalidAttributeValue,
│   │   │                   UnknownAttribute, UnknownFieldType, UnusedFieldType
│   │   ├── completion/     SolrSchemaCompletionContributor                     ⚑
│   │   ├── reference/      SolrSchemaReferenceContributor                      ⚑
│   │   ├── documentation/  SolrSchemaDocumentationProvider ⚑, SolrFieldPresentation,
│   │   │                   SolrSchemaElements, SolrFactoryAttribute
│   │   ├── descriptor/     SolrSchemaElementDescriptorProvider
│   │   ├── hint/           SolrMatchInlayHintsProvider
│   │   └── intention/      the six companion-field files
│   │
│   └── solrconfig/                  solrconfig.xml
│       ├── SolrConfigParameters.kt    parameter values → field-name PSI positions
│       ├── parsing/        SolrConfigParser
│       ├── inspection/     UnknownFieldReference, NonIndexedRelevanceField
│       ├── completion/     SolrConfigCompletionContributor                     ⚑
│       ├── reference/      SolrConfigReferenceContributor                      ⚑
│       └── documentation/  SolrConfigDocumentationProvider                     ⚑
│
├── server/                          shape decided now, built by Steps 11–15
│   ├── connection/                    SolrConnectionSettings — the one file that exists today
│   ├── http/                          the minimal HTTP and JSON client
│   ├── reading/                       server → SolrConfigsetFacts
│   ├── collections/                   Step 12
│   ├── console/                       Step 13 — the query console
│   └── documents/                     Step 15
│                                      (drift is not here; see below)
│
└── code/                            shape decided now, built by Steps 16–19
    │   ── the contract, and the shared downstream every recognizer feeds ──
    ├── recognizer/                    the interface, its two finding kinds — an endpoint and a
    │                                  field reference — and the module-dependency gate
    ├── inspection/                    one check over field-reference findings, whichever
    │                                  recognizer produced them
    ├── completion/                    field names at a recognized position
    ├── navigation/                    a field name in code → its schema declaration
    │
    │   ── recognizers over code: endpoints *and* field references ──
    ├── solrj/                         client construction, SolrQuery builders, raw parameter
    │                                  strings, SolrInputDocument, @Field
    ├── camel/                         route URIs, their option vocabulary, and field references
    │                                  in route parameters and document construction
    │
    │   ── recognizers over application configuration: endpoints only ──
    └── appconfig/                     a Solr URL *and its credentials*, resolved per profile
        ├── spring/                      profiles in separate files
        ├── quarkus/                     profiles inline as a `%dev.` key prefix
        ├── micronaut/                   environments
        └── microprofile/                ordinals
```

⚑ = the three files that are not pure moves. Each is one registered extension currently serving both
aspects, and each becomes two. IntelliJ accepts any number of registrations per extension point, so
splitting them costs the edit and nothing else.

**The two pull requests in flight land inside this shape without disturbing it**, which is the
cheapest available evidence that it holds: the field-operation work adds an inspection to
`configset.schema.inspection` and a model file to `model.schema`, and the parameter-completion work
adds a provider to `configset.solrconfig.completion`. Neither needs a package that is not already
here.

### `configset.reading` and `server.reading` are the same shape on purpose

The specification's central claim is one model fed by two sources. Today that symmetry is invisible
in the tree: one source is a package of six files and the other is a name in a diagram. Naming both
`reading`, each producing `SolrConfigsetFacts`, puts the architecture's load-bearing idea into the
directory listing. A reader who finds `configset/reading/` and `server/reading/` has been told
something true about how the plugin works before opening a file.

### A package above the surfaces must name the two it spans

`settings`, `query` and `drift` sit outside the three surfaces, which is the shape that decays into
`common`, `shared` and `util` if it is left unguarded. The guard is a naming obligation:

> **A top-level package outside the surfaces earns its place by naming the surfaces it spans, and
> the tree records which.** A package that cannot name two is a package that belongs inside one.

- **`settings` — configset + server.** Step 22 specifies a project settings page with the detection
  switch and the marked configset roots, and, in action 4, "a connections page as a sibling". One
  `Languages & Frameworks → Solr` group over both surfaces. Filing it under either would make that
  surface's package the owner of the other's UI.
- **`query` — code + server.** See below.
- **`drift` — configset + server.** See below.

`model` spans all three and is the older instance of the same rule.

### `drift` is not a server feature

The specification names it under *comparing the repository against the server* and calls it "the
feature that justifies having both halves". Filing it under `server` asserts the opposite — that it
is something the server surface does — when without a repository there is no drift to show at all.

The coupling argument does not save the placement either. A drift view under `server` would not
actually import `configset`, because `SolrFieldModel` already carries both halves and already exposes
`SolrAgreement`; it would read the merged model like everything else. So the objection is not that
the import would be wrong. It is that **the tree would say something false about what the feature
is**, and a tree whose only job is to say what things are cannot afford that.

It is the one view in the plan belonging to two sources and neither, so it gets its own package
naming both.

**The considered alternative was splitting source from view** — reducing `server` to `http`,
`connection` and `reading`, and giving the tool windows a sibling `toolwindow` package holding
collections, console, drift and documents. That is more coherent, and it is where this should go if
more cross-source views appear. It is rejected for now because it restructures five packages that do
not exist yet in order to fix one misfiling, and the repository's standing rule is that packages are
created when they have a file to hold.

### The `code` surface: a contract, a shared downstream, and one package per recognizer

The specification is unusually prescriptive here, and the first draft of this record did not follow
it. *"The plugin has a small set of recognizers. Each knows how to spot Solr usage in one place, and
each reports two kinds of finding: here is an endpoint, and here is a field reference. **Everything
downstream is shared.**"*

That sentence dictates the packages. Three things were wrong before:

**The contract had no package, only a "root".** `code.recognizer` holds the interface, the two
finding types and the module-dependency gate. It deserves a name because Steps 18 and 19 depend on
getting it right — the plan warns that a recognizer written without the gate "assumes it may inspect
anything, and retrofitting the gate afterwards means revisiting every recognizer built on top". The
gate belongs to the contract, not to each implementation.

**The shared downstream was missing entirely.** "Everything downstream is shared" means the
inspection over field-reference findings, the completion, and the navigation to the schema are one
implementation each, fed by every recognizer. Without packages for them, the second recognizer has
nowhere to put its half and the third copies the first.

**`framework` was one undifferentiated bucket, and the wrong name for the group.** The spec spends a
paragraph on how the four dialects "differ in ways that matter" — Spring's profile files, Quarkus's
inline `%dev.` prefixes with no profile-named file to find, Micronaut's environments, MicroProfile's
ordinals. Four precedence models, and four different classpaths for the gate to test, so four
recognizers, one package each.

The name had to change because Camel is a framework too, and putting it in a package called
`framework` would group it with the recognizers it least resembles. **What the four share is not
being frameworks — it is reading application configuration and reporting an endpoint.** Compare what
each reports:

| Recognizer | Endpoint | Field references | Own vocabulary |
|---|---|---|---|
| SolrJ | yes | yes | — |
| Camel | yes | yes | URI options |
| Spring, Quarkus, Micronaut, MicroProfile | yes (with credential) | **no** | — |

Step 18 lists no field-reference action at all; every one of its actions resolves a URL and its
credential. Step 19 checks "field references in route parameters and document construction" and
validates a URI option set. So Camel is a sibling of SolrJ — both read code, both report both kinds
of finding — and the four config dialects are the group that is different, which is why they are the
ones with a shared parent. `appconfig` names what they share.

That also makes the group's boundary testable rather than aesthetic: **a recognizer joins `appconfig`
if it reads application configuration and reports endpoints only.** Camel fails both halves.

**`code.query` was the real error, and it contradicted this record's own argument.** The query-string
language is not code's. Step 17 renders it inside a Java literal, Step 13's console completes with
it, and Step 17's gutter action runs the literal's query *in that console*. Leaving it under `code`
would make `server.console` import `code.query`. It is a cross-surface package by the rule above, so
it moves to the top level and names its two surfaces.

### What is deliberately not extracted yet

**`model.query`, the pure half of the query knowledge.** The top-level `query` package above is the
IntelliJ side — the `Language`, its lexer and highlighter, and the injection into a Java literal,
none of which exists before Step 17. Underneath it sits a different question: how a query-field value
splits into field names. Three consumers need that — `solrconfig.xml`'s `qf` today, Step 13's
console, and Step 17's literal — and it is pure, so it belongs in `model` alongside every other rule
that needs no IDE to test.

It is not extracted here, because the knowledge does not currently exist as a separable thing.
`SolrConfigParameters` does not own the rule — it reaches it by **building a synthetic `<config>`
document and handing it to `SolrConfigParser`** (`SolrConfigParameters:117`), which is a deliberate
choice recorded in its KDoc: the consumers and the model then cannot disagree about what counts as a
field reference.

That trick works while every caller has a `solrconfig.xml`. Step 13 requires completion to work
"with no configset present", which is where it stops working. **The prediction recorded here is that
Step 13 or Step 17 will extract the splitting rule into `model.query`, and that the synthetic
document is what has to give.** Building the package now would create an empty one against a guess;
the repository's standing rule is that packages are created when they have a file to hold.

### Aspects earn a package when they have a file

`schema` and `solrconfig` today. `elevate.xml`, `params.json`, `currency.xml` and `enumsConfig.xml`
are recognised by `SolrConfigsetFileKind` and supported by nothing, so they get no package. When one
does, it is additive: a sibling directory, no existing file moved.

## Testing strategy

The test tree mirrors the main tree exactly, which it already does. Three consequences worth stating:

- **`model.schema` stays plain JUnit 4**, and the "no IntelliJ types" rule now reads per subpackage
  rather than over one flat directory. The three test tiers are otherwise untouched.
- **`SolrConfigsetTestCase` moves to the test-side `configset` root.** It is currently under
  `configset.activation`, which was accurate when only activation tests needed it and is not the
  reason it exists — every test touching `SolrConfigsetSettings` or `SolrConnectionSettings` extends
  it.
- **The three splits are the only changes needing new test thought**, and they need less than it
  appears: the existing tests already divide cleanly along the aspect line, because they were written
  per behaviour. `SolrConfigFieldReferenceTest` and `SolrCopyFieldReferenceTest` are already the
  solrconfig and schema halves of `SolrConfigsetReferenceContributor`'s coverage.

**The gate is that nothing changes.** A behaviour-neutral refactor is verified by the suite passing
untouched except for package and import lines. Any test that needs its *assertions* edited is a
signal that something moved that should not have.

## Registration

`plugin.xml` carries seventeen fully-qualified implementation class names, all of which move. The
three splits turn three registrations into six: two `completion.contributor`, two
`psi.referenceContributor`, two `platform.backend.documentation.targetProvider`.

`inspectionDescriptions/*.html` and `intentionDescriptions/*/` are keyed by short name rather than by
package and do not move.

The file stays grouped by extension point. The aspect split is not visible there, and making it
visible would mean interleaving extension points, which reads worse for the platform's sake and
ours. Comment banners are the most that is warranted.

## Risks

**It conflicts with everything in flight, and there is a lot in flight.** All but one of the
fifty-four source files change their package line, and every importing file changes with them. Any
branch open across the move rebases badly. This is the strongest argument for doing it as a short
sequence of tightly-scoped pull requests, merged promptly, at a moment when the open-branch count is
low — and for agreeing that moment rather than assuming it.

**Doing it after Step 25 instead of before doubles it.** Step 25's own description warns it "should
be split when it starts". Splitting it into a tree that has nowhere to put the halves means splitting
it twice.

**`configset.editing` is the weakest call here.** Two files, and its claim to exist rests on Step 25's
validation also needing `SolrInspections`. If that turns out false, the honest correction is to move
both to the `configset` root package rather than to defend the name.

**Path length grows** by one segment on the deepest paths —
`org.apache.solr.ide.configset.schema.documentation`. Kotlin imports absorb it; the `# Package`
headings in `docs/Module.md` get longer, and there are about twice as many of them.

**`server` and `code` are decided on paper, and the first draft of this record got `code` wrong.**
It gave the recognizer contract no package, omitted the shared downstream the specification is
explicit about, collapsed four framework dialects into one bucket, and filed the query language under
`code` where the console could not reach it. All four were caught by re-reading the specification
rather than by writing any code. That is the honest measure of how much these two trees are worth:
enough to argue about now, not enough to trust without re-reading the step before building it.
Neither has files to move beyond `SolrConnectionSettings`, so revising them stays cheap.

## Delivery

Five pull requests, in dependency order, each independently green. Only the first four move code;
the rest of `server` and all of `code` are recorded shape, built when their steps arrive.

1. **`model` split** — `schema/` and `vocabulary/` subpackages. No IntelliJ types involved and no
   `plugin.xml` change, so it proves the mechanics at the lowest risk.
2. **Surface roots** — under `configset`: `parsing` → `reading`, new `editing`, new `navigation`,
   `activation` untouched. Under `server`: its one existing file, `SolrConnectionSettings`, to
   `server.connection`.
3. **`configset.schema.*`** — the largest by file count and almost entirely pure moves.
4. **`configset.solrconfig.*`** — small today, and the only one carrying real edits: all three ⚑
   splits happen here. Separate precisely because it is not just a move.
5. **Documentation** — [`docs/code-organization.md`](../../../code-organization.md)'s "where does my
   change go?" table becomes two-dimensional, its "the packages" section is rewritten,
   [`docs/Module.md`](../../../Module.md) grows from twelve `# Package` blocks to about two dozen,
   and [`docs/how-to/add-an-editor-feature.md`](../../../how-to/add-an-editor-feature.md) gains the
   aspect question ahead of the gesture question.

**Sequenced before Step 25**, which is the reason for the whole exercise.
