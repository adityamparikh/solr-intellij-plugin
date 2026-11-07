# Glossary

The vocabulary Apache Solr and the IntelliJ Platform assume, and what each term means in this
repository. Three groups: **Apache Solr**, **The IntelliJ Platform**, **This project's build and
Kotlin**. Where a term has a rule attached, the entry links the page that owns the rule.

## Apache Solr

### analyzer chain

The pipeline a field's text passes through at index time and/or query time: zero or more
[char filters](#char-filter), one [tokenizer](#tokenizer), then zero or more
[filters](#filter). Order decides the answer — a `FlattenGraphFilterFactory` above the graph filter
it should flatten does nothing, and `SolrAnalyzerChainOrderInspection` reports that and one other
provably inert ordering.

> **In Java terms.** Closest to a `Function<String, List<String>>` pipeline, each stage composed
> with the last — a `Stream` of character or token transformations. The analogy stops at two
> places: a misordered chain does not throw, it silently changes what a query can match; and nothing
> here runs at request time in this plugin, which only reads the chain's *declaration* and never
> invokes Lucene's analysis machinery.

Modeled as `SolrAnalyzerChain`, holding `charFilters`, `tokenizer` and `filters` in declaration
order. `SolrMatchAnalysis` derives meaning from it: it classifies **only the index-time chain**,
because that is what decides whether a term can be found at all, and it names the deciding factories
in code rather than reading them from the generated [factory](#factory) catalog.

### char filter

An analyzer chain component that transforms raw text before [tokenizer](#tokenizer)ization —
`HTMLStripCharFilterFactory`, for instance. Declared with `<charFilter class="...">` inside an
`<analyzer>`, and held in `SolrAnalyzerChain.charFilters`. `SolrMatchAnalysis` treats most known char
filters as neutral to what a field can match; an unrecognized one drops the hint's confidence rather
than being guessed at.

### collection

A logical, potentially sharded index in SolrCloud, distinguished from a [core](#core), its
standalone equivalent. A configset's root directory conventionally sits inside a directory named for
the core or collection. The editor features never talk to either; a collection becomes relevant only
once a `server` package reads one over HTTP — a schema, a topology, what the index holds.

### configset

The directory holding a schema (`managed-schema.xml` or `schema.xml`), a `solrconfig.xml` and their
companions (stopword lists, synonym files) — the unit Solr loads to define one core or collection's
behavior. `SolrConfigset` models it as a `VirtualFile` root, conventionally named `conf`.
[Solr configuration files](solr-configuration-files.md) describes what each file holds.

> **In Java terms.** Roughly a Hibernate mapping plus `persistence.xml`: the schema declares the
> shape of your data, `solrconfig.xml` configures the engine that reads and writes it. Both are
> hand-edited files checked into the repository next to the code that depends on them. The analogy
> is looser than it sounds: Solr also ships a Schema API that can write the schema half at runtime,
> and this plugin edits the files directly regardless — there is no "generate the schema from code"
> here.

Identity in this plugin is **per-configset, not per-file**: a field declared in the schema and
referenced from `solrconfig.xml`'s `qf` means nothing without both files, so every feature resolves
the owning configset (`SolrConfigsetDetector.configsetFor`) before it answers anything. A project may
hold several configsets, and they are never merged into one model.

### copyField

A schema directive, `<copyField source="..." dest="..."/>`, that duplicates one field's value into
another at index time — commonly used to build a catch-all search field. Modeled as `SolrCopyField`,
with an optional `maxChars` truncation limit. The demo configset's `manufacturer` → `text` copyField
is a *deliberate* dangling reference, left broken to exercise `SolrDanglingCopyFieldInspection`.

### core

A single Solr index instance in a standalone (user-managed) deployment — the unit that owns exactly
one [configset](#configset) and answers one set of [request handlers](#request-handler). Contrasted
with a [collection](#collection), SolrCloud's sharded equivalent. Fixture paths such as
`core/conf/managed-schema.xml` spell out the convention: a core's directory holds a `conf`
subdirectory, which is the configset root.

### docValues

A per-field, column-oriented on-disk structure Solr can build alongside the inverted index, used for
sorting, faceting, grouping and function queries.

> **In Java terms.** A column store beside the inverted index — a `Map<Field, long[]>` keyed by
> document ID versus the inverted index's `Map<Term, DocIdSet>`. Sorting by a field with doc values
> is a direct array read; without them Solr must "un-invert" the index at query time, or fail if
> [`uninvertible`](#uninvertible) is off.

Its default depends on the *field type's class* and the [schema version](#schema-version) rather than
being a flat value (`SolrTypeDefaultRule.DOC_VALUES`). `SolrPropertyOrigin.FIELD_TYPE_DEFAULT` is
the origin the plugin reports when it resolves a property from the type's class rather than from an
explicit attribute.

### dynamic field

A schema pattern, `<dynamicField name="*_t" type="text_general" .../>`, that supplies a field
declaration for any field name matching its glob. Solr's globs are deliberately impoverished — a
single leading or trailing `*`, or the bare `*`, never a general regex (`SolrGlob.matches`). Modeled
as `SolrDynamicField`, whose `specificity` (count of non-`*` characters) is how Solr picks a winner
when several patterns match: the longest literal part wins.

`SolrDynamicFieldSearcher` exists because of patterns: without it, Find Usages on a dynamic field's
declaration would never find the names it supplies, since the word index only offers candidates
sharing a literal word with the pattern.

### factory

Solr's word for a class that implements a [tokenizer](#tokenizer), [filter](#filter),
[char filter](#char-filter) or field type — anything named in a `class` attribute, conventionally
with a `Factory` suffix and a `solr.` shorthand prefix (`solr.LowerCaseFilterFactory`). The set a
Solr line ships runs to hundreds of entries and changes between majors, so it is **generated at
build time** from Solr's own jars — see [ASM](#asm) and `SolrClassCatalog`. `SolrClassKind`
enumerates the kinds a configset can name a class as, from `FIELD_TYPE` and `TOKENIZER` through
`REQUEST_HANDLER` and `DIRECTORY_FACTORY`.

**Deliberate exception:** the small set of factories `SolrMatchAnalysis` uses to decide what a field
can *match* — whole-value or tokenized, case-sensitive, prefix-capable — is named directly in code,
because that set *defines* Solr's matching semantics and has stayed stable across majors.

### field

A single named, typed value slot in a schema, declared `<field name="sku" type="string"
indexed="true" stored="true"/>`. Modeled as `SolrField`, whose boolean properties (`indexed`,
`stored`, `docValues`, `multiValued`, `required`) are nullable **on purpose**: an *unset* attribute
inherits from the [field type](#field-type), and collapsing it to `false` would invent a value the
file never stated. `SolrFieldProperties.ALL` is the table of properties a field or field type may
carry, each with its summary, valid values and default rule.

### field type

A schema declaration, `<fieldType name="text_general" class="solr.TextField">`, naming the Java class
that implements a family of fields' behavior and, for analyzed types, their
[analyzer chains](#analyzer-chain). Modeled as `SolrFieldType`; its `isAnalyzed` property separates
types with no chain — `StrField` and the numeric and date types, which match whole values only —
from analyzed text types.

> **In Java terms.** Closer to choosing a strategy implementation than to a Java type:
> `class="solr.TextField"` picks an implementation class, and the `<analyzer>` children configure
> that instance, like wiring a Spring bean with constructor arguments. Nothing here is instantiated
> by this plugin — the class name is matched against the generated [factory](#factory) catalog,
> never loaded, so an unrecognized custom `class` degrades gracefully rather than throwing.

### filter

An analyzer chain component (Lucene's `TokenFilter`) that transforms the stream of terms a
[tokenizer](#tokenizer) produced — folding case, stripping stopwords, stemming, generating n-grams.
Declared `<filter class="solr.LowerCaseFilterFactory"/>` inside an `<analyzer>`, and held in
`SolrAnalyzerChain.filters`, applied in declaration order after the tokenizer. `SolrMatchAnalysis`
groups known filters into sets it can reason about — case-folding, partial-match (n-gram), and
filters that split one term into several. An unrecognized filter drops the [inlay hint](#inlay-hint)'s
confidence rather than being assumed harmless.

### indexed

A field property controlling whether the field's terms go into the inverted index. A field is
searchable when it is `indexed` *or* has [docValues](#docvalues), so
`SolrNonIndexedRelevanceFieldInspection` reports a relevance parameter (`qf`, `pf`) naming a field
with neither — a parameter Solr accepts silently and that contributes nothing to the query.

### luceneMatchVersion

The `<luceneMatchVersion>` element in `solrconfig.xml` — a *Lucene* version string (`10.0.0` in the
demo), not a Solr one. The plugin reads it (`SolrConfigsetFacts.luceneMatchVersion`, resolved by
`SolrVersionSelection.fromLuceneMatchVersion`) to decide which Solr line's generated catalog and
Reference Guide pages to use. **Not the same number as [schema version](#schema-version)** — the two
are independent and answer different questions.

### managed-schema

The default filename Solr's Schema API writes to — Solr's own banner in it says *"automatically
generated - DO NOT EDIT"*. The plugin edits it anyway: it
[never refuses a write](code-organization.md#rules-that-hold-across-every-package). Recognized as a
configset file even without a `.xml` extension, through a `fileType` registration in
[`plugin.xml`](#pluginxml), because Solr historically shipped it extensionless.

### multiValued

A field property controlling whether one document may hold several values for the field. Its default
is `true` only for [schema versions](#schema-version) below `1.1`, otherwise `false`.

### omitNorms

A field property discarding length-normalization and index-time boost data to save memory, at the
cost of short and long values scoring identically. Its default depends on the
[field type](#field-type)'s class — `true` for primitive types like `StrField`, `false` for
`TextField` — which is the kind of question quick documentation answers for the field in front of
the reader and no external documentation can.

### request handler

The endpoint a query or update is sent to, declared `<requestHandler name="/select"
class="solr.SearchHandler">` in `solrconfig.xml`. Its `<lst name="defaults">` block is where field
names cross from `solrconfig.xml` into the schema's vocabulary — the demo's `/select` handler names
`name`, `description` and `category` in `qf` — which is the cross-file navigation this plugin's
[reference](#reference) and [Find Usages](#find-usages) machinery makes clickable.

### request parameter

A named value a [request handler](#request-handler) or query accepts — `qf`, `df`, `rows`,
`facet.range.other`. Generated at build time from the constants Solr declares them as, into
`SolrParameterCatalog`, which is **a completion and documentation source, not a membership test**: a
`solrconfig.xml` may name parameters a custom `SearchComponent` reads that no generator will ever see.
Which parameters carry field names is `SolrParameters`; `SolrConfigParameters` maps their occurrences
back onto PSI, shared by the unknown-field inspection and the reference provider so both annotate
exactly the same text.

### schema version

The `version` attribute on a schema's root element (`<schema name="products" version="1.6">`). It
decides what several field properties (`docValues`, `uninvertible`, `multiValued`,
`useDocValuesAsStored`) default to when a field or type does not declare them — Solr's mechanism for
changing defaults without breaking deployed schemas. Modeled as `SolrSchemaVersion`, which assumes
`1.0` when the schema declares nothing, matching Solr's own fallback.

Schema version `1.7` (Solr 9.7) turned `docValues` on and `uninvertible` off by default, and
upgrading the Solr *server* does not rewrite this attribute, so both sets of defaults are current in
real deployments. The demo pins `version="1.6"` so it shows a version-dependent default being
resolved against the file. Independent of [`luceneMatchVersion`](#lucenematchversion).

### solrconfig.xml

The other file every [configset](#configset) needs — request handlers, update processors, cache and
commit behavior, the [luceneMatchVersion](#lucenematchversion) declaration. Unlike the schema, it has
**no real API alternative**: Solr's Config API writes into `configoverlay.json` rather than this file,
and handler defaults, relevance tuning, update chains and commit behavior remain hand-edited XML.
Parsed by `SolrConfigParser` into the same `SolrConfigsetFacts` shape the schema parser produces.

### SolrJ

Apache's reference Java client for Solr (`solr-solrj`). Its presence gates activation:
`SolrProjectDetector` checks whether a project depends on `solr-solrj` — transitively present under
every wrapper client (Spring Data Solr, Camel, Quarkus) — **by artifact id, never by version**. Its
API is what `code.solrj` reads in the project's own Java and Kotlin.

### stored

A field property controlling whether the field's original value is kept and can be returned in
results. Independent of [indexed](#indexed): a field can be searchable without being retrievable, or
the reverse, and a field with neither has [docValues](#docvalues) as its only way back into a result,
if it has them. The demo's `name_prefix` field is `stored="false"` — it exists purely for prefix
matching.

### tokenizer

The single, mandatory analyzer-chain component that splits raw (or char-filtered) text into terms —
`<tokenizer class="solr.StandardTokenizerFactory"/>`. Exactly one per `<analyzer>`. Its class sets a
field's baseline match capability: `KeywordTokenizerFactory` emits the whole value as one term,
`EdgeNGramTokenizerFactory` and friends decide prefix support, and `LowerCaseTokenizerFactory` folds
case as it splits. A downstream [filter](#filter) can change that shape.

### uninvertible

A field property controlling whether Solr may build an in-memory structure to sort or facet on a
field that has no [docValues](#docvalues), at a memory cost on a large index. Its default is `true`
below [schema version](#schema-version) `1.7` and `false` from it.

### uniqueKey

The schema element naming the field Solr uses as the document's primary key,
`<uniqueKey>id</uniqueKey>`. Held as a plain nullable `String` on `SolrConfigsetFacts.uniqueKey`,
since the element carries nothing but the field name it points at.

## The IntelliJ Platform

### annotator

An extension point (`com.intellij.annotator`) that adds highlighting to PSI without claiming
something is *wrong*. This repository registers two. `SolrRestatedDefaultAnnotator` dims an
attribute whose written value is what the field would have inherited anyway — an annotator rather
than an [inspection](#inspection) because a restated default is correct Solr, at information
severity so nothing reaches the Problems view. `SolrQueryHighlightAnnotator` colours the fields and
operators of a Solr query written in a Java or Kotlin string.

### BasePlatformTestCase

The platform's base class for tests that need a running (headless) IDE and PSI —
`myFixture.addFileToProject(...)`, `myFixture.checkHighlighting(...)`.

> **In Java terms.** Closer to `@SpringBootTest` than to a plain unit test: it boots a real
> container (a light in-memory IDE project) rather than mocking collaborators, and state a service
> holds in a field is context-scoped, not test-scoped. The JUnit-version mismatch is a trap: despite
> the JUnit 4 dependency it is **JUnit 3-style** — methods must be named `testSomething()` and are
> discovered by that prefix, not by `@Test`.

It reuses **one light project across test methods *and* test classes**, which is why anything
touching `SolrConfigsetSettings` or `SolrConnectionSettings` must extend
[`SolrConfigsetTestCase`](#fixture) instead.
[Testing and the build gates](how-to/testing-and-the-build-gates.md) owns the conventions.

### CachedValuesManager

The platform's caching service: give it a computation and a list of dependencies, and it owns
storage, eviction and recomputation when a dependency moves. `SolrConfigsetReader.modelFor` is the
one place this plugin uses it; the rules are in
[caching the field model](platform-mechanisms.md#caching-the-field-model).

### completion contributor

An extension point (`com.intellij.completion.contributor`) supplying the popup on Ctrl+Space.
Registers `CompletionProvider`s against PSI patterns; each implements `addCompletions`. The rule a
change will be rejected for: **only complete closed sets** — where any value is legal, contribute
nothing, because a partial list implies everything absent from it is wrong.
`SolrSchemaCompletionContributor` is registered with `order="first"` so it runs ahead of the
platform's own XML completion and can drop the bare-name duplicates of what it already offered with
a summary attached.

### documentation provider

An extension point (`com.intellij.lang.documentationProvider`) supplying the popup on F1 or hover.
`SolrSchemaDocumentationProvider` answers what an element, field, [field type](#field-type) or
[factory](#factory) attribute means — the resolved value *and where it came from*: the field, its
type, or a Solr default. It opts into [dumb mode](#dumb-mode) through the `DumbAware` marker
interface rather than an overridden method.

### dumb mode

The state IntelliJ is in while it builds its indexes: the IDE runs, but anything backed by an index
cannot answer, and a contribution that has not declared itself `DumbAware` is skipped entirely. The
rules — and the one contribution that must both decline and guard — are in
[platform mechanisms](platform-mechanisms.md#dumb-mode-and-dumbaware).

### element descriptor

An extension point (`com.intellij.xml.elementDescriptorProvider`) that tells the platform's XML
support what elements and attributes a file may contain. Without one the platform *guesses* from
same-named sibling tags — a bad guess for a file made mostly of same-named `<requestHandler>` tags.
Two are registered, one per file kind — `SolrSchemaElementDescriptorProvider` and
`SolrConfigElementDescriptorProvider` — and both are deliberately permissive: validation is left to
the [inspections](#inspection).

### extension point

The platform's term for a named slot a plugin registers an implementation into —
`com.intellij.completion.contributor`, `com.intellij.localInspection`, and so on, all declared in
[`plugin.xml`](#pluginxml). Every package under `configset.schema` and `configset.solrconfig` is
named for the extension point it implements.

> **In Java terms.** An extension point is an SPI. `plugin.xml` plays the role
> `META-INF/services/*` plays for `ServiceLoader` — it names an interface and the class that
> implements it — and the platform, not this plugin, decides when to call in. There is no classpath
> scanning: registrations are strings, so a class renamed without updating its registration compiles
> cleanly and silently loads nothing.

### Find Usages

The platform feature that lists every place something is referenced, built automatically once a
[reference](#reference) resolves to a target — implementing a reference gets Ctrl+Click, Find Usages
*and* [rename](#rename-refactoring) together. Two extra pieces make it work here:
`SolrDeclarationSearcher` turns a *declaration* caret into something searchable, so Find Usages
works when started from the declaration itself, and `SolrDynamicFieldSearcher` makes a
[dynamic field](#dynamic-field)'s pattern find the concrete names it supplies.

### fixture

The `myFixture` object `BasePlatformTestCase` (and its subclass `SolrConfigsetTestCase`) exposes for
building an in-memory test project — `addFileToProject(path, content)`, `configureByText(...)`,
`checkHighlighting(...)`. The *path* passed to `addFileToProject` is part of the test's meaning: it
shapes the directory structure the detector reads. Anything touching `SolrConfigsetSettings` or
connection settings must use `SolrConfigsetTestCase`, which also puts a Solr client on the fixture's
classpath — see [BasePlatformTestCase](#baseplatformtestcase).

### inlay hint

Grey, non-editable text the IDE draws inline, registered as a `codeInsight.declarativeInlayProvider`.
`SolrMatchInlayHintsProvider` shows what each field can match beside its declaration — chosen over a
hover tooltip because a user who does not already suspect their field cannot match a prefix will
never hover to find out. It says nothing where the analysis is not confident.

### inspection

An extension point (`com.intellij.localInspection`) that draws the editor's underline and a
Problems-view entry, optionally with an Alt+Enter [quick fix](#quick-fix). **An inspection must not
fire on a correct file** — Solr configuration is full of syntax that resembles a field name without
being one (`fl` legitimately holds `score`, `*`, `[docid]`, `max(price,0)`). `SolrInspections` is the
shared helper that gives that requirement teeth. Every registered inspection, with its severity and
what it stays silent on, is in [the inspection catalog](inspection-catalog.md#how-to-read-an-entry).

**The distinction against [intention](#intention):** an inspection claims something is *wrong*; an
intention offers an improvement to a file with nothing wrong with it. If your change would draw an
underline, it is an inspection.

### intention

An extension point (`intentionAction`) offering an Alt+Enter menu item on code that is already
*correct* — no underline, nothing in the Problems view. This repository's three add `_exact` and
`_prefix` companion fields (`SolrAddExactCompanionIntention`, `SolrAddPrefixCompanionIntention`) and
remove a [restated default](#annotator) (`SolrRemoveRestatedAttributeIntention`), all under the
**Solr** category. A field that cannot match a prefix is correct Solr, so underlining it to hang a
fix off would be manufacturing a problem.

### plugin.xml

The plugin descriptor, `src/main/resources/META-INF/plugin.xml`, where every
[extension point](#extension-point) implementation, action and inspection is registered by fully
qualified class name as a string. Fixture tests build their subject directly
(`myFixture.enableInspections(SomeInspection())`), so a class rename not mirrored here passes every
one of them and is dead in a real IDE. `SolrPluginDescriptorTest` closes that gap by reflecting over
every class name the file lists.

### PSI

**P**rogram **S**tructure **I**nterface — the platform's parsed representation of a file's syntax
tree, which almost every editor feature here walks (`XmlTag`, `XmlAttributeValue`, and so on).

> **In Java terms.** A live, mutable AST — closer to a DOM tree you can hold a reference into than
> to `javac`'s throwaway `CompilationUnitTree`. The limit is that liveness: PSI **changes while the
> user types**, so a `PsiElement` reference can go stale mid-edit, which is why the field model is a
> plain data structure holding **no** PSI at all.

The model can say a field type named `text_general` exists but not *where* it was written;
navigation re-derives that from PSI on demand through `SolrSchemaPsi`.

### quick fix

A `LocalQuickFix` offered from an [inspection](#inspection)'s Alt+Enter menu, applying an edit.
`SolrReplaceNameQuickFix` substitutes a valid name for one just reported as unknown — one fix per
candidate, so Alt+Enter shows the candidates inline. Candidates are ranked by edit distance and
capped at six: the inspection already computed the valid set in order to decide, and discarding it
would leave the reader to find it.

### read action

The platform requirement that code reading PSI or the index run under a read lock, so it cannot
observe a half-mutated state concurrently with a write. `SolrProjectDetector.hasSolrClientLibrary`
takes one explicitly, through `ReadAction.computeBlocking`.

### reference

An extension point (`com.intellij.psi.referenceContributor`) that turns a string in one file into
something Ctrl+Click navigates, Find Usages finds, and rename updates — all three from one
implementation. References here are **soft**: an unresolved one draws no platform warning, because
that would duplicate what the [inspections](#inspection) report, in the platform's vocabulary rather
than Solr's. A [dynamic field](#dynamic-field)'s glob is followed only as far as it is written —
`copyField dest="*_t"` resolves to the `dynamicField` spelling the same pattern, never to a concrete
field it might match.

### rename refactoring

The platform feature that edits every [reference](#reference) to a renamed declaration, built on the
same abstraction Find Usages is. Renaming a field here updates its schema declaration *and* its
mention in a `solrconfig.xml` handler's `qf`, which is why it lives in `configset.navigation` rather
than under either file's aspect — see
[the organising principle](code-organization.md#the-organising-principle).

### sandbox

The disposable IDE instance the Gradle IntelliJ Platform plugin launches to run or test this plugin —
`./gradlew runIde` launches one opening `demo/`. Tests use a persistent system directory at
`.intellijPlatform/sandbox/<project>/<IDE>/system-test`, which **survives `./gradlew clean`**: if
every fixture test fails with `FileDeletedException` while plain JUnit tests stay green, delete that
directory and re-run.

### XmlElementVisitor

The platform's typed visitor base class for walking XML [PSI](#psi) — `visitXmlTag`,
`visitXmlAttribute`, and so on — that every configset [inspection](#inspection) builds its
`buildVisitor` around. The shape is the same in each: reach the model, return
`PsiElementVisitor.EMPTY_VISITOR` if it is null, then `return object : XmlElementVisitor() { ... }`.

> **In Java terms.** The Gang-of-Four Visitor pattern, applied to PSI the way it is applied to an
> ANTLR or javac AST. What is specific to the platform: `PsiElementVisitor.EMPTY_VISITOR` is the
> idiom for "decline to run at all", returned once `SolrConfigsetReader.modelFor` reports the file is
> not part of a configset — the null check *is* the activation gate, so the inspection writes no
> activation logic of its own.

## This project's build and Kotlin

### ASM

The bytecode-reading library (`org.objectweb.asm`) `GenerateSolrCatalogTask` uses to read Solr's
class files rather than load them. Loading Solr's classes would run their static initializers; ASM
reads a class's name, ancestry and constructor bytecode from its raw bytes, which is all the
generated [factory](#factory) catalog needs.

> **In Java terms.** Inspecting a class file offline instead of `Class.forName(...)` — the point is
> never running the class. Nothing in the shipped plugin touches ASM, only the Gradle task that runs
> before packaging.

### buildSrc

The Gradle convention directory (`buildSrc/`) for code the main build script depends on, compiled
before `build.gradle.kts` is evaluated.

> **In Java terms.** Closest to an annotation-processor module: built and put on the classpath of
> the *build itself* before the main compilation runs, rather than a module the shipped artifact
> depends on.

This repository's `buildSrc` holds `GenerateSolrCatalogTask`, which implements the bytecode
extraction; the root `build.gradle.kts` declares only the policy of which Solr lines are supported
(`supportedSolrLines`).

### data class

A Kotlin class that gets `equals`, `hashCode`, `toString`, `copy` and destructuring generated from its
constructor properties — the mechanism behind almost every value type in `org.apache.solr.ide.model`,
such as `SolrField`, `SolrFieldType` and `SolrConfigsetFacts`.

> **In Java terms.** What a `record` gives you, plus a generated `copy()` for with-style
> modification. The gap: a data class is immutable only if its properties are declared `val`, which
> is a convention here rather than a compiler guarantee — and every model type follows it.

### Dokka

The Kotlin documentation generator, and one of the build's three gates alongside [Kover](#kover) and
Spotless formatting.

> **In Java terms.** Kotlin's Javadoc — it reads [KDoc](#kdoc) comments and renders HTML API docs.

Configured with `reportUndocumented` and `failOnWarning`: any public class, function or property in
`src/main/kotlin` without KDoc fails `./gradlew build`, naming the declaration. Tests are exempt.
`docs/Module.md` supplies the module and package overview pages Dokka renders beside the API
reference. Because Dokka reads `[foo]` as a symbol link, Markdown reference-style links do not work
in KDoc — only inline `[text](url)` does.

### KDoc

Kotlin's documentation-comment syntax (`/** ... */` with `@param`, `@property`, and `[SymbolLinks]`),
read by [Dokka](#dokka). The bar is usefulness, not presence: KDoc that restates a signature passes
the Dokka gate and fails review. The convention is to explain the *decision*, which is why many KDoc
comments here read as short design notes.

### Kover

The Kotlin code-coverage plugin (`kotlinx.kover`), and one of the build's three gates.

> **In Java terms.** Kotlin's JaCoCo. It measures with IntelliJ's own coverage agent, not JaCoCo's,
> and writes its XML report in JaCoCo's format, which is what SonarCloud reads.

Enforces an **80% line floor** via `koverVerify`, bound to `check`. The floor sits below actual
coverage on purpose: a backstop against a sharp drop, while SonarCloud's new-code gate catches
gradual erosion on the lines a pull request touches. `SolrBundle` is excluded from the report.

### sealed class

A Kotlin class or interface whose subtypes are all known at compile time, so a `when` over it can be
exhaustive with no `else` branch. `SolrResponse` is the clearest example: the five transport
outcomes a caller must handle, with nothing else possible. `SolrCollectionsView` and `SolrDriftView`
use the same shape for the states a view can be in.

> **In Java terms.** Java's `sealed` interfaces (JEP 409) with pattern-matching `switch` — the same
> "closed hierarchy, exhaustive dispatch" idea. Kotlin's version does not need a `permits` clause
> when the subtypes are nested inside the sealed type, which is the shape used here.
