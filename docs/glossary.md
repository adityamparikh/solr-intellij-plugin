# Glossary

> **Who this is for.** A Java engineer who is comfortable with Maven/Gradle, Spring and JUnit but
> new to Apache Solr internals and to IntelliJ Platform plugin development — this page names the
> vocabulary those two worlds assume and says what each term means specifically in this repository.
> **Read first:** [Code organization](code-organization.md) for how the terms below map onto packages.

Three groups: **Apache Solr**, **The IntelliJ Platform**, **This project's build and Kotlin**. Each
entry says what the term is, its closest Java analogue where one exists and is not misleading, and
what it means concretely in this codebase.

## Apache Solr

### analyzer chain

The pipeline a field's text passes through at index time and/or query time: zero or more
[char filters](#char-filter), one [tokenizer](#tokenizer), then zero or more
[filters](#filter). Order decides the answer — a `WordDelimiterFilterFactory` placed after a
`KeywordTokenizerFactory` splits the single token the tokenizer produced back into several, so the
field ends up tokenized despite naming a whole-value tokenizer
(`SolrAnalyzerChainOrderInspection`, one of two orderings it catches).

> **In Java terms.** Closest to a `Function<String, List<String>>` pipeline, each stage composed
> with the last — a `Stream` of character or token transformations rather than an OOP hierarchy.
> The analogy stops at execution: nothing here runs at request time in this plugin, because the
> plugin only ever reads the chain's *declaration*, never invokes Lucene's analysis machinery.

Modeled as `SolrAnalyzerChain`
(`src/main/kotlin/org/apache/solr/ide/model/schema/SolrSchemaTypes.kt:37`), holding `charFilters`,
`tokenizer` and `filters` in declaration order. `SolrMatchAnalysis`
(`src/main/kotlin/org/apache/solr/ide/model/schema/SolrMatchAnalysis.kt:21`) is the plugin's one
consumer that derives meaning from it: it classifies **only the index-time chain**, because that is
what decides whether a term can be found at all, and it names the deciding factories in code rather
than reading them from the generated [factory](#factory) catalog — see [factory](#factory) for why.

### char filter

An analyzer chain component that transforms raw text before [tokenizer](#tokenizer)ization —
`HTMLStripCharFilterFactory`, for instance. Declared with `<charFilter class="...">` inside an
`<analyzer>`. In `SolrAnalyzerChain`, char filters run first, in `charFilters`
(`src/main/kotlin/org/apache/solr/ide/model/schema/SolrSchemaTypes.kt:32`). `SolrMatchAnalysis`
treats most known char filters as neutral to what a field can match (`NEUTRAL_CHAR_FILTERS`,
`SolrMatchAnalysis.kt:231`) — an unrecognized one drops the hint's confidence rather than guessing.

### collection

A logical, potentially sharded index in SolrCloud (multi-node) deployments, distinguished from a
[core](#core), which is the single-node equivalent. This repository treats the two as
interchangeable for naming purposes: `SolrConfigset.name`
(`src/main/kotlin/org/apache/solr/ide/configset/activation/SolrConfigset.kt:24`) says a configset's
root directory "sits inside a directory named for the core or collection". The plugin's editor path
never talks to either — see [dumb mode](#dumb-mode)'s sibling rule, "nothing on the editor path
contacts a server" (`docs/code-organization.md:188`) — a collection only becomes relevant once
`server.connection` (not yet built beyond connection settings) reads one over HTTP.

### configset

The directory holding a schema (`managed-schema.xml` or `schema.xml`), a `solrconfig.xml` and their
companions (stopword lists, synonym files) — the unit Solr loads to define one core or collection's
behavior. `SolrConfigset`
(`src/main/kotlin/org/apache/solr/ide/configset/activation/SolrConfigset.kt:18`) models it as a
`VirtualFile` root, conventionally named `conf`.

> **In Java terms.** Roughly the schema plus the config of a search index — a Hibernate mapping file
> combined with `persistence.xml`, both hand-edited XML rather than annotations. The analogy is
> looser than it sounds: Solr also ships a Schema API that can write the schema half at runtime, and
> this plugin's position (see `docs/code-organization.md:202`) is that the files are edited directly
> regardless — there is no ORM-style "generate the schema from code" here.

Identity in this plugin is **per-configset, not per-file**: a field declared in the schema and
referenced from `solrconfig.xml`'s `qf` mean nothing without both files, so every feature resolves
the owning configset before it answers anything (`SolrConfigsetDetector.configsetFor`,
`src/main/kotlin/org/apache/solr/ide/configset/activation/SolrConfigsetDetector.kt:83`). A project
may hold several configsets, and they are never merged into one model.

### copyField

A schema directive, `<copyField source="..." dest="..."/>`, that duplicates one field's value into
another at index time — commonly used to build a catch-all search field. Modeled as `SolrCopyField`
(`src/main/kotlin/org/apache/solr/ide/model/schema/SolrSchemaTypes.kt:141`), with an optional
`maxChars` truncation limit. The demo configset's `manufacturer` → `text` copyField is a *deliberate*
dangling reference (`demo/solr/conf/managed-schema.xml:91`), left broken on purpose to exercise
`SolrDanglingCopyFieldInspection`.

### core

A single Solr index instance in a non-SolrCloud (standalone) deployment — the unit that owns exactly
one [configset](#configset) and answers one set of [request handler](#request-handler)s. Contrasted
with a [collection](#collection), SolrCloud's sharded equivalent; this repository treats naming for
either the same way (`SolrConfigset.kt:24`). Demo fixture paths in the test suite, such as
`core/conf/managed-schema.xml` (`docs/how-to/testing-and-the-build-gates.md:29`), spell out the
convention: a core's directory holds a `conf` subdirectory, which is the configset root.

### docValues

A per-field, column-oriented on-disk structure Solr can build alongside the normal inverted index,
used for sorting, faceting, grouping and function queries. See the [analyzer
chain](#analyzer-chain) entry for the ordering-sensitivity pattern this shares conceptually with
[uninvertible](#uninvertible).

> **In Java terms.** A column store sitting beside the inverted index, closer to a columnar cache
> than to anything in the JDK — think of it as a `Map<Field, long[]>` keyed by document ID versus the
> inverted index's `Map<Term, DocIdSet>`. Sorting by a field with doc values is a direct array read;
> without them Solr must "un-invert" the index at query time, or fail if
> [`uninvertible`](#uninvertible) is off.

Its default is one of the two properties in `SolrFieldProperties` that depends on the *field type's
class* rather than a flat value (`typeDefault = SolrTypeDefaultRule.DOC_VALUES`,
`src/main/kotlin/org/apache/solr/ide/model/schema/SolrFieldProperties.kt:235`) — `string` fields
default to having them, most others do not, and the resolution needs the [schema
version](#schema-version) too. `SolrPropertyOrigin.FIELD_TYPE_DEFAULT` is the origin the plugin
reports when it resolves this from the type's class rather than from an explicit attribute
(`SolrFieldProperties.kt:38`).

### dynamic field

A schema pattern, `<dynamicField name="*_t" type="text_general" .../>`, that supplies a field
declaration for any field name matching its glob rather than one exact name. Solr's globs are
deliberately impoverished — a single leading or trailing `*`, or the bare `*`, never a general regex
(`SolrGlob.matches`, `src/main/kotlin/org/apache/solr/ide/model/schema/SolrSchemaTypes.kt:184`).
Modeled as `SolrDynamicField` (`SolrSchemaTypes.kt:110`), whose `specificity` (count of non-`*`
characters) is what Solr uses to pick a winner when several patterns match the same name — the
longest literal part wins. The demo's `*_t` pattern (`demo/solr/conf/managed-schema.xml:84`) is the
one declaration in the demo that is a pattern rather than a literal name, and the reason
`SolrDynamicFieldSearcher` exists: without it, Find Usages on a dynamic field's declaration would
never find the names it supplies, because the word index can only offer candidates sharing a literal
word with the pattern (`src/main/resources/META-INF/plugin.xml:113`).

### factory

Solr's word for a class that implements a [tokenizer](#tokenizer), [filter](#filter), [char
filter](#char-filter) or field type — anything named in a `class` attribute, conventionally with a
`Factory` suffix and a `solr.` shorthand prefix (`solr.LowerCaseFilterFactory`). The set of factories
a given Solr line ships is roughly 170 entries and changes between major versions, which is why it is
**generated at build time** from Solr's own jars rather than hand-maintained — see
[ASM](#asm) and `SolrClassCatalog`
(`src/main/kotlin/org/apache/solr/ide/model/vocabulary/SolrClassCatalog.kt:1`). `SolrClassKind`
(`SolrClassCatalog.kt:12`) enumerates the eleven kinds a configset can name a class as, from
`FIELD_TYPE` and `TOKENIZER` through `REQUEST_HANDLER` and `DIRECTORY_FACTORY`.

**Deliberate exception:** the small set of factories `SolrMatchAnalysis` uses to decide what a field
can *match* (whole-value vs. tokenized, case-sensitive, prefix-capable) are named directly in code,
not read from the generated catalog — because that set *defines* Solr's matching semantics and has
stayed stable across majors, while the surrounding catalog has not (`SolrMatchAnalysis.kt:11`).

### field

A single named, typed value slot in a schema, declared `<field name="sku" type="string"
indexed="true" stored="true"/>`. Modeled as `SolrField`
(`src/main/kotlin/org/apache/solr/ide/model/schema/SolrSchemaTypes.kt:92`), whose boolean properties
(`indexed`, `stored`, `docValues`, `multiValued`, `required`) are nullable **on purpose**: Solr
treats an *unset* attribute differently from an explicit `false`, since unset inherits from the
[field type](#field-type) — collapsing the two would silently invent a value the file never stated.
`SolrFieldProperties.ALL`
(`src/main/kotlin/org/apache/solr/ide/model/schema/SolrFieldProperties.kt:215`) is the full table of
~24 properties a field or field type may carry, each with its summary, valid values and default rule.

### field type

A schema declaration, `<fieldType name="text_general" class="solr.TextField">`, naming the Java class
that implements a family of fields' behavior and, for analyzed types, their [analyzer
chain](#analyzer-chain)(s). Modeled as `SolrFieldType`
(`src/main/kotlin/org/apache/solr/ide/model/schema/SolrSchemaTypes.kt:57`); its `isAnalyzed`
property distinguishes types with no chain at all — `StrField` and the numeric/date types, which
match whole values only — from analyzed text types.

> **In Java terms.** Closer to declaring a strategy implementation than a Java `type` in the
> language sense — `class="solr.TextField"` is choosing an implementation class, and the
> `<analyzer>` children configure that instance, similar to wiring a Spring bean with constructor
> arguments. Where the analogy breaks: nothing here is dependency-injected or instantiated by this
> plugin — the class name is read and matched against the generated [factory](#factory) catalog,
> never loaded, so a custom, unrecognized `class` value degrades gracefully rather than throwing.

### filter

An analyzer chain component (Solr's word for what Lucene calls a `TokenFilter`) that transforms the
stream of terms a [tokenizer](#tokenizer) already produced — folding case, stripping stopwords,
stemming, generating n-grams. Declared `<filter class="solr.LowerCaseFilterFactory"/>` inside an
`<analyzer>`, and modeled as the `filters` list on `SolrAnalyzerChain`, applied in declaration order
after the tokenizer (`src/main/kotlin/org/apache/solr/ide/model/schema/SolrSchemaTypes.kt:40`,
`components`). `SolrMatchAnalysis` groups known filters into sets it can reason about — case-folding
filters, partial-match (n-gram) filters, and filters that split one term into several
(`TOKEN_SPLITTING_FILTERS`, `SolrMatchAnalysis.kt:195`) — because a splitting filter placed after a
`KeywordTokenizerFactory` is the one ordering defect this codebase's `SolrAnalyzerChainOrderInspection`
exists to catch: it undoes the tokenizer's whole-value guarantee. An unrecognized filter class drops
the [inlay hint](#inlay-hint)'s confidence rather than being assumed harmless.

### indexed

A field property controlling whether the field can be searched or sorted on at all
(`SolrFieldProperties.ALL`, entry `"indexed"`,
`src/main/kotlin/org/apache/solr/ide/model/schema/SolrFieldProperties.kt:216`). Defaults to `true`
when unset. `SolrNonIndexedRelevanceFieldInspection` uses this: a field referenced in a relevance
parameter (`qf`, `pf`) that resolves to `indexed="false"` is a request Solr will refuse.

### luceneMatchVersion

The `<luceneMatchVersion>` element in `solrconfig.xml` — a *Lucene* version string (`10.0.0` in the
demo, `demo/solr/conf/solrconfig.xml:11`), not a Solr one. It is what the plugin reads to decide
which Solr line's generated catalog and Reference Guide pages to use
(`SolrConfigsetFacts.luceneMatchVersion`,
`src/main/kotlin/org/apache/solr/ide/model/SolrConfigsetFacts.kt:24`; resolved by
`SolrVersionSelection.fromLuceneMatchVersion`, consumed throughout
`src/main/kotlin/org/apache/solr/ide/configset/schema/documentation/SolrFieldPresentation.kt`).
**Not the same version as [schema version](#schema-version)** — the two are independent numbers
answering different questions, and confusing them is an easy mistake this codebase's KDoc calls out
explicitly (`src/main/kotlin/org/apache/solr/ide/model/schema/SolrSchemaVersion.kt:7`).

### managed-schema

The default filename Solr's Schema API writes to when field changes come in over the wire — Solr's
own banner in this file says *"automatically generated - DO NOT EDIT"*
(`demo/solr/conf/managed-schema.xml:2`). This plugin's whole position is to edit it anyway: "the
plugin edits configuration files directly and never refuses a write" is a hard rule
(`docs/code-organization.md:202`), and the demo opens by confronting that banner directly
(`demo/solr/conf/managed-schema.xml:4-5`). Recognized as a configset file even without a `.xml`
extension (`<fileType name="XML" fileNames="managed-schema;managed-schema.xml"/>`,
`src/main/resources/META-INF/plugin.xml:33`) because Solr itself ships it extensionless.

### multiValued

A field property controlling whether one document may hold several values for the field
(`SolrFieldProperties.ALL`, entry `"multiValued"`, `SolrFieldProperties.kt:241`). Its default is
`true` only for [schema version](#schema-version)s below `1.1` — one of the properties whose default
is conditional on the declared schema version (`defaultTrueWithin = SolrVersionRange(below = 1.1f)`),
otherwise `false`.

### omitNorms

A field property discarding length-normalization and index-time boost data to save memory, at the
cost of short and long field values scoring identically (`SolrFieldProperties.ALL`, entry
`"omitNorms"`, `SolrFieldProperties.kt:257`). Its default depends on the [field type](#field-type)'s
class (`typeDefault = SolrTypeDefaultRule.OMIT_NORMS`) — `true` for primitive types like `StrField`,
`false` for `TextField` — which is exactly the kind of question "no external documentation can
answer for the field in front of the reader", per the KDoc on
`SolrPropertyOrigin.FIELD_TYPE_DEFAULT` (`SolrFieldProperties.kt:38`).

### request handler

The endpoint a query or update is sent to, declared `<requestHandler name="/select"
class="solr.SearchHandler">` in `solrconfig.xml`
(`demo/solr/conf/solrconfig.xml:24`). `SolrClassKind.REQUEST_HANDLER`
(`src/main/kotlin/org/apache/solr/ide/model/vocabulary/SolrClassCatalog.kt:33`) names it in the
generated catalog. Its `<lst name="defaults">` block is where field names cross from `solrconfig.xml`
into the schema's vocabulary without either file otherwise connecting them — the demo's `/select`
handler names `name`, `description` and `category` in `qf`
(`demo/solr/conf/solrconfig.xml:28`), which is the cross-file navigation this plugin's [reference](#reference)
and [Find Usages](#find-usages) machinery exists to make clickable.

### request parameter

A named value a [request handler](#request-handler) (or query) accepts — `qf`, `df`, `rows`,
`facet.range.other`. Generated at build time from the `public static final String` constants Solr
declares them as, into `SolrParameterCatalog`
(`src/main/kotlin/org/apache/solr/ide/model/vocabulary/SolrParameterCatalog.kt:44`), which is
explicitly **a completion and documentation source, not a membership test** — a `solrconfig.xml` may
name parameters a custom `SearchComponent` reads that no generator will ever see, so an unrecognized
name is never treated as wrong on its own (`SolrParameterCatalog.kt:37`). `SolrConfigParameters`
(`src/main/kotlin/org/apache/solr/ide/configset/solrconfig/SolrConfigParameters.kt:16`) decides which
parameters carry field names (`qf`, `fl`, `pf`, and similar) and maps their occurrences back onto PSI
positions, shared by the unknown-field inspection and the reference provider so both annotate exactly
the same text.

### schema version

The `version` attribute on a schema's root element (`<schema name="products" version="1.6">`,
`demo/solr/conf/managed-schema.xml:27`), ranging historically from `1.0` to `1.7`. It decides what
several field properties (`docValues`, `uninvertible`, `multiValued`, `useDocValuesAsStored`) default
to when a field or type does not declare them explicitly — Solr's mechanism for changing its defaults
without breaking schemas already deployed. Modeled as `SolrSchemaVersion`
(`src/main/kotlin/org/apache/solr/ide/model/schema/SolrSchemaVersion.kt:23`), which assumes `1.0`
when the schema declares nothing at all, matching `IndexSchema`'s own fallback — assuming the newest
version instead would silently report modern defaults for a file Solr is actually running under 2008
semantics (`SolrSchemaVersion.kt:39`).

**This project's hard-won subtlety:** schema version `1.7` (introduced in Solr 9.7) flipped
`docValues` on and `uninvertible` off by default, and upgrading the Solr *server* does not rewrite
this attribute — moving a schema up generally requires re-indexing, so both defaults are current and
long-lived in real deployments. The demo deliberately pins `version="1.6"`
(`demo/solr/conf/managed-schema.xml:11-16`) specifically so the demo shows a version-dependent default
being resolved against the file rather than an assumption. It is a **third, independent** version
number from [`luceneMatchVersion`](#lucenematchversion) — do not conflate the two
(`SolrSchemaVersion.kt:7`).

### solrconfig.xml

The other file every [configset](#configset) needs alongside the schema — request handlers, update
processors, cache and commit behavior, the [luceneMatchVersion](#lucenematchversion) declaration.
Unlike the schema, it has **no real API alternative**: Solr's Config API covers common properties and
writes into `configoverlay.json`, but request handler defaults, dismax relevance tuning, update
processor chains and commit behavior remain hand-edited XML in every deployment
(`specs/0002-solr-intellij-plugin.md:91-95`). Parsed by `SolrConfigParser`
(`src/main/kotlin/org/apache/solr/ide/configset/solrconfig/parsing/SolrConfigParser.kt`) into the
same `SolrConfigsetFacts` shape the schema parser produces, so both merge without either being
privileged (`SolrConfigsetFacts.kt:10`).

### SolrJ

Apache's reference Java client for Solr (`solr-solrj` artifact). This plugin uses SolrJ's presence,
not its API: `SolrProjectDetector`
(`src/main/kotlin/org/apache/solr/ide/configset/activation/SolrProjectDetector.kt:114`) checks
whether a project depends on `solr-solrj` — transitively present under every wrapper client (Spring
Data Solr, Camel, Quarkus) — as the fact that gates whether the plugin activates at all. Matched **by
artifact id, never by version**, because that is a fact about the dependency graph rather than an
inference from a directory listing.

### stored

A field property controlling whether the field's original value is kept and can be returned in
search results (`SolrFieldProperties.ALL`, entry `"stored"`,
`src/main/kotlin/org/apache/solr/ide/model/schema/SolrFieldProperties.kt:223`). Defaults to `true`
when unset. Independent of [indexed](#indexed): a field can be searchable without being retrievable,
or retrievable without being searchable, and the two properties' KDoc spells out that a field with
neither has [docValues](#docvalues) as its only way back into a result, if it has them at all
(`SolrFieldProperties.kt:224-230`). The demo's `name_prefix` field is `stored="false"`
(`demo/solr/conf/managed-schema.xml:69`) — it exists purely to support prefix matching, not to be
displayed.

### tokenizer

The single, mandatory analyzer-chain component that splits raw (or char-filtered) text into terms —
`<tokenizer class="solr.StandardTokenizerFactory"/>`. Exactly one per `<analyzer>`. Its class alone
decides a field's baseline [match capability](#analyzer-chain): `KeywordTokenizerFactory` emits the
whole value as one term, `EdgeNGramTokenizerFactory` and friends decide prefix support, and
`LowerCaseTokenizerFactory` folds case as it splits (`SolrMatchAnalysis.kt:57-74`). See [analyzer
chain](#analyzer-chain) for how a downstream [filter](#filter) can undo a tokenizer's shape.

### uninvertible

A field property controlling whether Solr may build an in-memory field cache to sort or facet on a
field that has no [docValues](#docvalues), at the cost of memory on a large index
(`SolrFieldProperties.ALL`, entry `"uninvertible"`, `SolrFieldProperties.kt:308`). Its default flips
at [schema version](#schema-version) `1.7`: `true` below it, `false` from it
(`defaultTrueWithin = SolrVersionRange(below = 1.7f)`) — see [schema version](#schema-version) for
why that flip matters and why the demo pins its schema below the threshold on purpose.

### uniqueKey

The schema element naming the field Solr uses as the document's primary key, `<uniqueKey>id</uniqueKey>`
(`demo/solr/conf/managed-schema.xml:86`). Modeled as a plain nullable `String` on
`SolrConfigsetFacts.uniqueKey`
(`src/main/kotlin/org/apache/solr/ide/model/SolrConfigsetFacts.kt:35`) rather than a richer type,
since the element carries nothing but the field name it points at.

## The IntelliJ Platform

### annotator

An extension point (`com.intellij.annotator`) that adds highlighting to PSI without claiming
something is *wrong* — the platform building block behind non-warning decoration such as dimming.
This repository's one annotator, `SolrRestatedDefaultAnnotator`
(`src/main/kotlin/org/apache/solr/ide/configset/schema/annotator/SolrRestatedDefaultAnnotator.kt:12`),
dims an attribute whose written value is exactly what the field would have inherited anyway. It
exists as an annotator rather than an [inspection](#inspection) specifically because a restated
default is *correct* Solr — the standing rule that inspections must not fire on a correct file (see
`docs/code-organization.md:341`) forced this into a different mechanism, one the platform already
uses for "true but removable" code, at information severity so nothing reaches the Problems view.

### BasePlatformTestCase

The platform's base class for tests that need a running (headless) IDE and PSI —
`myFixture.addFileToProject(...)`, `myFixture.checkHighlighting(...)`.

> **In Java terms.** Closer to `@SpringBootTest` than to a plain unit test: it boots a real
> container (a light in-memory IDE project) rather than mocking collaborators. The JUnit-version
> mismatch is a real trap, though — despite the JUnit 4 dependency, it is **JUnit 3-style**: test
> methods must be named `testSomething()` and are discovered by that prefix, not by `@Test`. An
> `@Test`-annotated method with a different name silently does not run
> (`docs/how-to/testing-and-the-build-gates.md:18-21`).

It reuses **one light project across test methods *and* test classes**, which is why anything
touching `SolrConfigsetSettings` or `SolrConnectionSettings` must instead extend
[`SolrConfigsetTestCase`](#fixture) — state set by one test otherwise leaks into the next
(`docs/how-to/testing-and-the-build-gates.md:65-69`).

### CachedValuesManager

The platform's caching service: give it a computation and a list of **dependencies**, and it owns
storage, thread-safety, eviction under memory pressure, and recomputing the value when any
dependency's modification count moves. Not something you call `evict` on — invalidation is declared
up front, not triggered.

> **In Java terms.** The nearest analogue is a `Caffeine` cache whose entries expire on a custom
> `Expiry`/`RemovalListener` you define once, rather than a bare `ConcurrentHashMap` you populate
> and clear by hand. The instinct a Java engineer brings here — "I'll just keep a
> `ConcurrentHashMap<Path, Model>` and invalidate it myself" — is exactly the wrong move in this
> codebase; see below for why.

`SolrConfigsetReader.modelFor`
(`src/main/kotlin/org/apache/solr/ide/configset/reading/SolrConfigsetReader.kt:74`) is the one place
this plugin uses it, and the choices there are deliberate rather than default: **the cache hangs on
the configset's `PsiDirectory`, not in a map this class owns** — a map keyed by path outlives the
thing it names, so a configset deleted and recreated at the same path (or, in tests, the next test
class sharing a light project) would find a stale entry pointing at files that no longer exist
(`docs/platform-mechanisms.md:132-139`). Its dependency list is **the source files' PSI, plus the VFS
structure-modification count** — not `PsiModificationTracker.MODIFICATION_COUNT`, the reflex choice,
which advances on *any* PSI change in the project and would reparse both configset files on an
unrelated keystroke elsewhere (`docs/platform-mechanisms.md:146-157`).

**This is a hard rule, not a suggestion:** `CLAUDE.md` states outright, "never build a cache in
front of `SolrConfigsetReader.modelFor`. It already caches through the platform's
`CachedValuesManager`. A fact read from a file the reader does not already read must join
`sourcesOf`, or the model goes stale after the first edit." Every editor feature — inspections,
completion, hints, documentation, references — reads through this one cached call rather than
memoizing anything of its own.

### completion contributor

An extension point (`com.intellij.completion.contributor`) supplying the popup on Ctrl+Space.
Registers `CompletionProvider`s against PSI patterns in an `init` block; each provider implements
`addCompletions`. This repository's rule that will get a change rejected in review: **only complete
closed sets** — where any value is legal, contribute nothing, because a partial list implies
everything absent from it is wrong (`docs/code-organization.md:360-364`,
`docs/how-to/add-an-editor-feature.md:255-260`). `SolrSchemaCompletionContributor`
(`src/main/kotlin/org/apache/solr/ide/configset/schema/completion/SolrSchemaCompletionContributor.kt`)
is registered with `order="first"` so it runs ahead of the platform's own XML completion and can drop
duplicate bare-name suggestions it already offered with a summary attached
(`src/main/resources/META-INF/plugin.xml:133-138`).

### documentation provider

An extension point (`com.intellij.lang.documentationProvider`) supplying the popup on F1 or hover.
`SolrSchemaDocumentationProvider`
(`src/main/kotlin/org/apache/solr/ide/configset/schema/documentation/SolrSchemaDocumentationProvider.kt`)
answers what an element, field, [field type](#field-type) or [factory](#factory) attribute means —
the resolved value *and where it came from* (field, type, or a Solr default), which is "the one thing
here no external documentation can supply" (`docs/code-organization.md:389`). It implements the
`DumbAware` marker interface rather than overriding a method — see [dumb mode](#dumb-mode) for why
that distinction matters and is easy to get silently wrong.

### dumb mode

The state IntelliJ is in while it builds its indexes after a project opens: the IDE runs, but
anything backed by an index cannot answer yet. A contribution that has not declared itself
`DumbAware` is **skipped entirely** while indexing runs — the platform's deliberately conservative
default, because answering from a half-built index produces confidently wrong results
(`docs/platform-mechanisms.md:15-34`).

> **In Java terms.** The index is a database that is still importing; queries against it fail (or
> here, are simply skipped) until the import finishes, rather than blocking or throwing. The plugin
> analogue of "does my query hit the DB or an unfinished replica".

**The hard-won rule this project learned by shipping a defect:** a contribution must **both** decline
the platform's declaration *and* guard any index access with `DumbService` — an earlier version of
this rule said *or*, and that is exactly how the defect shipped. Declining keeps a feature out of the
paths the flag gates, but its `resolve()` can still be called directly by something walking
references at a caret; unguarded, that throws during indexing and can take an unrelated popup down
with it (`CLAUDE.md`, "Rules no build gate enforces"). Nothing in this plugin currently reads an
index except class resolution through `JavaPsiFacade`
(`docs/platform-mechanisms.md:41-43`), which is the one place both halves of the rule are exercised.
How a contribution opts in differs by extension point and is **not uniform** — `LocalInspectionTool`
and `CompletionContributor` override `isDumbAware()`; documentation and inlay providers implement the
`DumbAware` marker interface instead, and a wrong guess there compiles cleanly and silently does
nothing (`docs/platform-mechanisms.md:62-78`).

### element descriptor

An extension point (`com.intellij.xml.elementDescriptorProvider`) that tells the platform's XML
support what elements and attributes a file may legally contain — without one, the platform *guesses*
by looking at same-named sibling tags, which is a bad guess for a file like `solrconfig.xml` made
mostly of same-named `<requestHandler>` tags: "a second `<requestHandler>` was offered whatever the
first one happened to carry" (`src/main/resources/META-INF/plugin.xml:61-65`). Two providers are
registered, one per file kind — `SolrSchemaElementDescriptorProvider` and
`SolrConfigElementDescriptorProvider` — because a shared provider would make one file's aspect own a
position the other also has. They are deliberately permissive: validation is left to the
[inspections](#inspection), not to the descriptor.

### extension point

The platform's term for a named slot a plugin can register an implementation into —
`com.intellij.completion.contributor`, `com.intellij.localInspection`, and so on, all declared in
`plugin.xml` (`src/main/resources/META-INF/plugin.xml`). Every package under `configset.schema` and
`configset.solrconfig` in this repository is named for the extension point it implements
(`docs/code-organization.md:101-116`).

> **In Java terms.** An extension point is an SPI. `plugin.xml` plays the role
> `META-INF/services/*` plays for `ServiceLoader` — it names an interface and the class that
> implements it — and the platform, not this plugin, decides when to call in. The one place the
> analogy really matters here: `plugin.xml` registrations are strings, so a class renamed without
> updating its registration compiles cleanly and silently loads nothing — exactly the failure
> `SolrPluginDescriptorTest` exists to catch by reflecting over every class name in the file
> (`docs/how-to/testing-and-the-build-gates.md:141-147`).

### Find Usages

The platform feature that lists every place something is referenced, built automatically once a
[reference](#reference) resolves to a target — implementing `reference` gets Ctrl+Click, Find Usages
*and* [rename](#rename-refactoring) together, "because the platform builds all three on the same
abstraction" (`docs/code-organization.md:124-126`). This repository needed two extra pieces beyond a
plain reference to make it work: `SolrDeclarationSearcher`
(`src/main/kotlin/org/apache/solr/ide/configset/navigation/SolrDeclarationSearcher.kt`) turns a
*declaration* caret into something searchable — without it, Find Usages started from a use site but
refused when started from the declaration itself
(`src/main/resources/META-INF/plugin.xml:105-111`) — and `SolrDynamicFieldSearcher`
(`src/main/kotlin/org/apache/solr/ide/configset/navigation/SolrDynamicFieldSearcher.kt`) makes a
[dynamic field](#dynamic-field)'s pattern find the concrete names it supplies, since the platform's
word index can only match candidates that share a literal word with the pattern.

### fixture

The `myFixture` object `BasePlatformTestCase` (and its subclass `SolrConfigsetTestCase`) exposes for
building an in-memory test project — `myFixture.addFileToProject(path, content)`,
`myFixture.configureByText(...)`, `myFixture.checkHighlighting(...)`. In this repository the *path*
passed to `addFileToProject` is part of the test's meaning, not incidental: it shapes the directory
structure the detector's heuristics read, so choosing a careless path is choosing a different test
(`docs/how-to/testing-and-the-build-gates.md:33-36`). Anything touching `SolrConfigsetSettings` or
connection settings must use `SolrConfigsetTestCase`
(`src/test/kotlin/org/apache/solr/ide/configset/activation/SolrConfigsetTestCase.kt`) rather than the
bare platform class, because that settings state is project-level and otherwise leaks across tests —
see [BasePlatformTestCase](#baseplatformtestcase).

### inlay hint

Grey, non-editable text the IDE draws inline in the editor, registered as a
`codeInsight.declarativeInlayProvider`. This repository's one inlay,
`SolrMatchInlayHintsProvider`
(`src/main/kotlin/org/apache/solr/ide/configset/schema/hint/SolrMatchInlayHintsProvider.kt`), shows
what each field can actually match beside its declaration — chosen over a hover tooltip because "a
user who does not already suspect their field cannot match a prefix will never hover over it to find
out" (`docs/code-organization.md:415-417`). It is the only extension point with a user-facing
on/off toggle in Settings (`providerId`, `nameKey` in the registration,
`src/main/resources/META-INF/plugin.xml:38-45`) and the output most likely to be quoted back at a
maintainer if wrong, which is why it says nothing rather than guess when the analysis is not
confident.

### inspection

An extension point (`com.intellij.localInspection`) that reports the editor's squiggly underline plus
a Problems-view entry, optionally with an Alt+Enter [quick fix](#quick-fix). **The rule this codebase
cares about most:** an inspection must not fire on a correct file — Solr configuration is full of
syntax that resembles a field name without being one (`fl` legitimately holds `score`, `*`,
`[docid]`, `max(price,0)`), and "a warning on a correct file is what gets a plugin uninstalled"
(`docs/code-organization.md:341-344`). `SolrInspections`
(`src/main/kotlin/org/apache/solr/ide/configset/editing/SolrInspections.kt`) is the shared helper
that gives that requirement teeth, and every registration in `plugin.xml` uses `level="WARNING"`
rather than `ERROR` — the plugin's model of a half-typed file is never treated as authoritative
enough to claim a hard error (`docs/how-to/add-an-editor-feature.md:181-185`).

**The distinction against [intention](#intention) that catches people:** an inspection first claims
something is *wrong*; an intention offers an improvement to a file that has nothing wrong with it. If
your change would draw an underline, it is an inspection.

### intention

An extension point (`intentionAction`) offering an Alt+Enter menu item on code that is already
*correct* — no underline, nothing reaching the Problems view. This repository's three intentions add
`_exact`/`_prefix` "companion" fields (`SolrAddExactCompanionIntention`,
`SolrAddPrefixCompanionIntention`) and offer removing a [restated default](#annotator)
(`SolrRemoveRestatedAttributeIntention`) — all registered under `<category>Solr</category>` in
`plugin.xml` (`src/main/resources/META-INF/plugin.xml:288-305`). See [inspection](#inspection) for
the boundary that makes this a separate package rather than a quick fix: a field that cannot match a
prefix is correct Solr, so underlining it to hang a fix off would be manufacturing a problem.

### plugin.xml

The plugin descriptor, `src/main/resources/META-INF/plugin.xml`, where every [extension
point](#extension-point) implementation, dependency and inspection description is registered by
fully qualified class name as a string. Nothing in this repository's fixture-based test suite
actually exercises this file — every test builds its subject directly
(`myFixture.enableInspections(SomeInspection())`), so a class rename that is not mirrored here
compiles, passes every test, and is silently dead in a real IDE
(`docs/how-to/testing-and-the-build-gates.md:129-135`). `SolrPluginDescriptorTest` is the plain-JUnit
test that closes that gap by reflecting over every class name the file lists.

### PSI

**P**rogram **S**tructure **I**nterface — the platform's parsed representation of a file's syntax
tree, the abstraction almost every editor feature here walks (`XmlTag`, `XmlAttributeValue`, and so
on).

> **In Java terms.** A live, mutable AST — closer to a DOM tree you can safely hold a reference into
> than to `javac`'s throwaway `CompilationUnitTree`. The limit of the analogy is exactly that
> liveness: PSI **stays live and mutable while the user types**, so a `PsiElement` reference can go
> stale mid-edit, which is why nothing in this plugin caches PSI directly (see [caching the field
> model](platform-mechanisms.md#caching-the-field-model)) and why the field model is deliberately a
> plain data structure holding **no** PSI at all (`SolrSchemaPsi`
> comment, `src/main/kotlin/org/apache/solr/ide/configset/navigation/SolrSchemaPsi.kt:16`).

The model can say a field type named `text_general` exists but not *where* it was written; navigation
features re-derive that from PSI on demand through helpers like `SolrSchemaPsi`.

### quick fix

A `LocalQuickFix` offered from an [inspection](#inspection)'s Alt+Enter menu, applying an edit. This
repository's shared one, `SolrReplaceNameQuickFix`
(`src/main/kotlin/org/apache/solr/ide/configset/editing/SolrReplaceNameQuickFix.kt:25`), substitutes
a valid name for one just reported as unknown — one fix instance per candidate rather than one fix
offering a dialog, "because Alt-Enter shows the candidates inline that way"
(`SolrReplaceNameQuickFix.kt:18-20`). Candidates are ranked by edit distance and capped at six,
because the inspection already computed the valid set in order to decide it was wrong, and discarding
that list is "the difference between an editor that helps and one that complains"
(`docs/code-organization.md:347-351`).

### read action

A platform requirement that any code reading PSI or the index run inside a read lock, so it cannot
observe a half-mutated state concurrently with a write. `SolrProjectDetector.hasSolrClientLibrary`
(`src/main/kotlin/org/apache/solr/ide/configset/activation/SolrProjectDetector.kt:78`) is this
repository's one explicit use, via `ReadAction.computeBlocking`. It is also the plugin's sole current
source of a platform *deprecation* warning caught by `verifyPlugin` — one
`ReadAction.compute(ThrowableComputable)` usage
(`docs/how-to/testing-and-the-build-gates.md:227-229`) — which is the class of finding no test in this
repository can otherwise produce.

### reference

An extension point (`com.intellij.psi.referenceContributor`) that turns a string in one file into
something Ctrl+Click can navigate, Find Usages can find, and rename can update — all three from one
implementation, because the platform builds them on a shared abstraction
(`docs/code-organization.md:124-126`). References here are deliberately **soft**: an unresolved one
draws no platform warning, because that would duplicate what the [inspections](#inspection) already
report, in the platform's vocabulary rather than Solr's
(`docs/how-to/add-an-editor-feature.md:275-278`). A [dynamic field](#dynamic-field)'s glob is followed
only as far as it is written — `copyField dest="*_t"` resolves to the `dynamicField` spelling the
same literal pattern, never to a concrete field the pattern might match at query time, since that set
depends on indexed documents rather than on the schema.

### rename refactoring

The platform feature that edits every [reference](#reference) to a renamed declaration across files,
built on the same abstraction Find Usages is. This repository's rename works across the file
boundary — renaming a field updates its declaration in the schema *and* its mention in a
`solrconfig.xml` handler's `qf` — which is exactly why that capability lives in
`configset.navigation` rather than under either file's own aspect package: "rename must update the
`qf` line", and filing it under `schema` would make the schema aspect import the solrconfig one
(`docs/code-organization.md:29-32`).

### sandbox

The disposable IDE instance the Gradle IntelliJ Platform plugin launches to run or test this plugin
in a real editor — `./gradlew runIde` launches one with the plugin installed, opening `demo/` rather
than whatever it had open last (`docs/contributing.md:61-62`). The equivalent for tests is a
persistent system directory at `.intellijPlatform/sandbox/<project>/<IDE>/system-test`, which
**survives `./gradlew clean`**: if every fixture test suddenly fails with the same
`FileDeletedException` while plain JUnit tests stay green, the sandbox VFS is corrupted, not the code
under test, and the fix is to delete that directory and re-run
(`docs/how-to/testing-and-the-build-gates.md:104-124`).

### XmlElementVisitor

The platform's typed visitor base class for walking XML [PSI](#psi) — `visitXmlTag`,
`visitXmlAttribute`, and so on — that every [inspection](#inspection) in this repository builds its
`buildVisitor` around. The shape is identical across all eleven: reach the model, bail to
`PsiElementVisitor.EMPTY_VISITOR` if it is null, then `return object : XmlElementVisitor() { override
fun visitXmlTag(tag: XmlTag) { ... } }`
(`src/main/kotlin/org/apache/solr/ide/configset/schema/inspection/SolrUnknownFieldTypeInspection.kt:39-40`,
and identically in `SolrDanglingCopyFieldInspection`, `SolrUnknownAttributeInspection`,
`SolrAnalyzerChainOrderInspection`, `SolrUnusedFieldTypeInspection`,
`SolrInvalidAttributeValueInspection`, and the four `solrconfig` inspections).

> **In Java terms.** The Gang-of-Four Visitor pattern, applied to PSI the same way it is applied to
> an ANTLR or javac AST — `accept`/`visit` double dispatch over a fixed node hierarchy. What is
> specific to the platform rather than to visitors generally: `PsiElementVisitor.EMPTY_VISITOR` is
> the platform's idiom for "decline to run at all", used here as the return value once
> `SolrConfigsetReader.modelFor` reports the file is not part of a configset — the null check *is*
> the activation gate, so the inspection writes no activation logic of its own
> (`docs/how-to/add-an-editor-feature.md:73-76`).

## This project's build and Kotlin

### ASM

The bytecode-reading library (`org.objectweb.asm`) `GenerateSolrCatalogTask` uses to read Solr's
class files directly rather than loading them
(`buildSrc/src/main/kotlin/org/apache/solr/ide/build/GenerateSolrCatalogTask.kt:33-35`). Loading
Solr's classes would run their static initializers; ASM reads only a class's name and ancestry from
its raw bytes, which is all the generated [factory](#factory) catalog needs.

> **In Java terms.** Comparable to using a bytecode-manipulation library (ASM itself, or Byte
> Buddy) to inspect a class file offline instead of `Class.forName(...).newInstance()` — the whole
> point being to avoid ever running the class. The build-time-only nature is what makes this safe:
> nothing in the shipped plugin touches ASM, only the Gradle task that runs before packaging.

### buildSrc

The Gradle convention directory (`buildSrc/`) for code the main build script depends on, compiled and
available before `build.gradle.kts` is evaluated.

> **In Java terms.** Gradle's own compile-time module — the closest Java-ecosystem parallel is an
> annotation processor module, built and put on the classpath of the *build itself* before the main
> compilation runs, rather than a module the shipped artifact depends on.

This repository's `buildSrc` holds `GenerateSolrCatalogTask`
(`buildSrc/src/main/kotlin/org/apache/solr/ide/build/GenerateSolrCatalogTask.kt`), which the root
`build.gradle.kts` wires in but does not implement — the task's own KDoc explicitly says the "how" of
bytecode extraction lives there, and the root script "declares only the policy" of which Solr lines
are supported (`build.gradle.kts:230-232`).

### data class

A Kotlin class that gets `equals`, `hashCode`, `toString`, `copy` and component destructuring
generated from its constructor properties — the mechanism behind almost every value type in
`org.apache.solr.ide.model`, such as `SolrField`, `SolrFieldType` and `SolrConfigsetFacts`
(`src/main/kotlin/org/apache/solr/ide/model/schema/SolrSchemaTypes.kt`,
`src/main/kotlin/org/apache/solr/ide/model/SolrConfigsetFacts.kt:30`).

> **In Java terms.** What a `record` gives you in modern Java, plus a generated `copy()` for
> with-style mutation that records lack without hand-written builders. The gap: Kotlin data classes
> are still ordinary mutable-by-default classes unless properties are declared `val`, so the
> immutability a Java `record` guarantees by construction is a convention here, not a compiler
> guarantee — every model type in this codebase follows that convention deliberately.

### Dokka

The Kotlin documentation generator this build runs as one of its two mandatory gates.

> **In Java terms.** Kotlin's Javadoc — it reads [KDoc](#kdoc) comments and renders HTML API docs.

Configured in `build.gradle.kts:187-207` with `reportUndocumented = true` and `failOnWarning = true`:
any public class, function or property in `src/main/kotlin` without KDoc fails `./gradlew build`,
naming the declaration (`docs/how-to/testing-and-the-build-gates.md:244-249`). Tests are exempt —
only the `main` source set is gated. `docs/Module.md` supplies the module and package overview pages
Dokka renders alongside the generated API reference, and because Dokka reads `[foo]` as a symbol
link, Markdown reference-style links do not work inside KDoc — only inline `[text](url)` does.

### KDoc

Kotlin's documentation-comment syntax (`/** ... */` with `@param`, `@property`, and `[SymbolLinks]`),
read by [Dokka](#dokka). The bar this repository holds KDoc to is usefulness, not presence — KDoc
that merely restates a signature passes the Dokka gate and fails review; the convention is to explain
the *decision*, not the mechanics (`docs/how-to/testing-and-the-build-gates.md:256-259`), which is
why so many KDoc comments quoted throughout this glossary read as short design notes rather than
parameter lists.

### Kover

The Kotlin code-coverage plugin (`kotlinx.kover`) this build runs as its second mandatory gate.

> **In Java terms.** Kotlin's JaCoCo — in fact it uses JaCoCo's engine under the hood.

Configured in `build.gradle.kts:147-183` to enforce an **80% line floor** via `koverVerify`, bound to
`check`. The floor sits deliberately below actual project coverage: it is a backstop against a sharp
regression, not a target, since SonarCloud's new-code gate is what is meant to catch gradual erosion
on the lines a given PR touches (`docs/how-to/testing-and-the-build-gates.md:278-284`). `SolrBundle`
is excluded from the report as thin resource-bundle plumbing with no branches of its own.

### sealed class

A Kotlin class (or `sealed interface`) whose subtypes are all known at compile time within the same
module, letting a `when` expression over it be exhaustive with no `else` branch required. Used
sparingly in this codebase — `SolrSchemaDocumentationProvider`'s private `Target` type
(`src/main/kotlin/org/apache/solr/ide/configset/schema/documentation/SolrSchemaDocumentationProvider.kt:382`)
is the one example, modeling the closed set of things a documentation-popup caret can land on.

> **In Java terms.** The direct ancestor of Java's `sealed` interfaces/classes (JEP 409) combined
> with pattern-matching `switch` — the same "closed hierarchy, exhaustive dispatch" idea Java added
> later. Kotlin's version predates Java's and does not require the `permits` clause to be written out
> when subtypes are nested inside the sealed type itself, which is the shape used here.
