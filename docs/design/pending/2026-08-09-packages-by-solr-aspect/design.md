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
surfaces read it, and it is the only package with no IntelliJ types anywhere in it.

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
├── settings/                        ← new; the plugin's own configuration UI
│                                      Step 22 puts configset roots and connections on sibling
│                                      pages, so this spans two surfaces and belongs above both
│
├── model/                           no IntelliJ types anywhere — rule unchanged
│   ├── SolrConfigsetFacts.kt          one shape for both sources, by design
│   ├── SolrFieldModel.kt              SolrFact / SolrAgreement — the merge spine
│   ├── SolrReferenceGuide.kt          documentation links by Solr version
│   ├── catalog/
│   │   └── SolrClassCatalog.kt        generated; Step 25 grows it to solrconfig plugins
│   └── schema/                        SolrAttributeVocabulary, SolrFieldProperties,
│                                      SolrSchemaTypes, SolrSchemaVersion, SolrTypeTrait,
│                                      SolrValueType, SolrMatchAnalysis, SolrMatchCapability
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
│   ├── query/                         Step 13
│   ├── drift/                         Step 14
│   └── documents/                     Step 15
│
└── code/                            shape decided now, built by Steps 16–19
    ├── (root)                         the recognizer interface and the module-dependency gate
    ├── solrj/
    ├── framework/                     Spring, Quarkus, Micronaut, MicroProfile
    ├── camel/
    └── query/                         Step 17's query-string language
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

### `settings` is above the surfaces, not inside one

Step 22 specifies a project settings page carrying the detection switch and the marked configset
roots, and — action 4 — "a connections page as a sibling". One `Languages & Frameworks → Solr` group
spanning the configuration surface and the server surface. It reads `configset.activation` and later
`server.connection`, and is imported by neither.

Filing it under either surface would make that surface's package the owner of the other's UI. It is
the plugin's own configuration, so it sits beside `model` rather than inside a surface.

### What is deliberately not extracted yet

**A query-syntax package.** Three coming consumers need to know how a query-field value splits into
field names: `solrconfig.xml`'s `qf` today, Step 13's query console, and Step 17's query string
inside a Java literal. That is the signature of a fourth cross-cutting concern.

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

**`server` and `code` are decided on paper.** Their shape is inferred from step descriptions rather
than from code, so it may be wrong in detail. It costs nothing to record and is cheap to revise,
because neither has files to move beyond `SolrConnectionSettings` — but it should not be read as more
settled than it is.

## Delivery

Five pull requests, in dependency order, each independently green. Only the first four move code;
the rest of `server` and all of `code` are recorded shape, built when their steps arrive.

1. **`model` split** — `schema/` and `catalog/` subpackages. No IntelliJ types involved and no
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
