# Module Solr IntelliJ Plugin

> **Who this is for.** A Java engineer new to Apache Solr and the IntelliJ Platform, looking for an
> overview of what is built before reading the per-package KDoc below.
> **Read first:** [Glossary](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/glossary.md)
> if Solr or IntelliJ Platform terms are new ·
> [docs/contributing.md](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/contributing.md)

IDE tooling for Apache Solr development on the IntelliJ Platform.

Developers building on Solr work across three disconnected surfaces: configset
XML edited with no language support, queries iterated in the Admin UI or curl,
and client code that references field names as unchecked string literals. Every
boundary between them produces a class of silent runtime failure — a typo'd field
name returns empty results rather than an error, a `copyField` pointing at a
removed field fails only at core reload, a `qf` parameter referencing an
unindexed field degrades relevance with no warning. Comparable ecosystems closed
this gap years ago; Elasticsearch and Kafka both have maintained JetBrains
plugins, and Solr has none.

The plugin is delivered in phases. **Phase 1**, the committed first release, is
pure static analysis of configset files (`managed-schema` / `schema.xml`,
`solrconfig.xml`) — completion, cross-file reference resolution, rename
refactoring, inspections, and match-capability hints, all working offline with no
Solr connection. Later phases add live connections and a query console, SolrJ
code integration, and collection/dev-loop operations. The full program is
specified in
[specs/0002-solr-intellij-plugin.md](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/specs/0002-solr-intellij-plugin.md).

### What is built

**This API reference is not a status report.** It documents what has been written,
which is not the same as what is finished, and a package appearing here says
nothing about whether the feature it serves is complete. The
[implementation plan](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/specs/plans/0002-solr-intellij-plugin-plan.md)
is the only file that owns that answer; the
[specification](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/specs/0002-solr-intellij-plugin.md)
describes intent, much of which is unbuilt.

### Where to read about structure

Each package below carries a one-line statement of what it is. The reasoning —
why the packages are split this way, what each boundary forbids, and where a
given change belongs — lives in
[docs/code-organization.md](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md),
so that it is written once rather than maintained here and there.

Contributors should start with
[docs/contributing.md](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/contributing.md).

### Documentation conventions

Every public declaration in this module carries KDoc, enforced by the build:
Dokka runs with `reportUndocumented` and `failOnWarning`, and `dokkaGenerate` is
a dependency of `check`. An undocumented public class, function, or property
fails `./gradlew build` locally and in CI. Adding public API therefore means
documenting it in the same change.

# Package org.apache.solr.ide

Plugin-wide infrastructure shared by all feature packages. Currently the
localization bundle; feature code lives in subpackages.

# Package org.apache.solr.ide.model

What the plugin knows about a configset's fields, as data — and the one package
that is not a feature. It is what both surfaces read, and the only tree with no
IntelliJ types in it, which is what lets the correctness-critical code be tested
without a running IDE.

This package itself holds what is independent of any one source: `SolrConfigsetFacts`,
the shape a parser produces; `SolrFieldModel` with `SolrFact` and `SolrAgreement`,
which merge a repository half with a server half; and `SolrReferenceGuide`.

[Why it is shared, and what `SolrFact`, `SolrMatchAnalysis` and `SolrClassCatalog`
are for](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#orgapachesolridemodel).

# Package org.apache.solr.ide.model.schema

What a field *is*, whichever source described it — properties and their
resolution, field types and their traits, schema versions, and the match analysis
that says what a chain can actually match.

Everything here is knowledge a server reader needs in order to interpret what it
fetched, which is the test that separates this package from
`org.apache.solr.ide.model.vocabulary`: Solr's schema API returns `indexed`,
`stored`, `omitNorms` and analyzer chains, so all of it applies to a collection
just as it applies to a file.

# Package org.apache.solr.ide.model.vocabulary

What a configuration file may legally contain: which attributes each element
accepts, and the generated catalog of classes a `class` attribute may name.

Distinct from `org.apache.solr.ide.model.schema` because a server never needs it.
These answers are about XML elements and attributes, and Solr's schema API returns
JSON — so nothing here will ever have a server half.

# Package org.apache.solr.ide.configset.activation

Deciding whether the plugin runs at all, and against which configset.

[The two gates, the name tiers, and why the manual override is
load-bearing](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#the-activation-decision).

# Package org.apache.solr.ide.configset.reading

Turning a configset directory into a model, and caching the result: the reader
that both aspects go through, the project-wide scan, and the hardened XML
document loading both parsers share.

Cross-aspect by nature — a configset is read as a whole — so it sits at the
configset root rather than under `schema` or `solrconfig`.

[The parsers, the cache and its dependency
list](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#from-files-to-the-model).

# Package org.apache.solr.ide.configset.editing

The editor-path primitives both aspects share: the guard rails that keep an
inspection off a correct file, and the quick-fix that offers the valid
alternatives when a name does not resolve.

# Package org.apache.solr.ide.configset.navigation

The gestures that cross the file boundary by design. A field is declared in the
schema and referenced from `solrconfig.xml`, so Find Usages, the declaration
target and rename belong to the configset as a whole rather than to either
aspect — filing them under one would make that aspect import the other.

`SolrSchemaPsi` is here for the same reason. It answers *where was this
declared*, which is the target end of every reference — including the ones a
`solrconfig.xml` parameter makes — so it is graph machinery rather than
schema-private.

# Package org.apache.solr.ide.configset.schema

Everything anchored in a schema file — `managed-schema.xml` or `schema.xml` —
one subpackage per gesture.

A capability belongs here when the caret that triggers it is always in a schema
file. Anything that traverses the configset instead lives at the configset root:
that is why Find Usages and rename are in `configset.navigation` and not here.

# Package org.apache.solr.ide.configset.schema.parsing

Reading a schema file into facts. A pure function from text to
`SolrConfigsetFacts`, using the JDK's DOM rather than IntelliJ's XML PSI, which
is what lets it be tested without an IDE.

# Package org.apache.solr.ide.configset.schema.inspection

Reporting what is wrong in a schema file: a dangling `copyField`, a field naming
an undeclared type, an attribute that does not exist or cannot hold the value
written, an analyzer chain in an impossible order, a field type nothing uses.

[Why the clean fixtures matter more than the flagged
ones](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#orgapachesolrideconfigsetschemainspection-and-orgapachesolrideconfigsetsolrconfiginspection).

# Package org.apache.solr.ide.configset.schema.documentation

What a hover over a schema element, a field, or a field type explains: the
resolved property table, where each value came from, and what the field can
actually match.

[What it answers that the Reference Guide
cannot](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#orgapachesolrideconfigsetschemadocumentation).

# Package org.apache.solr.ide.configset.schema.descriptor

Owning the XML element descriptors for schema files, so the platform's answers
come from the plugin's knowledge instead of from schema-less guessing — which
echoed one filter's attributes onto every other.

Deliberately permissive: every unknown attribute and element resolves rather
than being flagged, because validation is the inspections' job and they know
when not to fire.

# Package org.apache.solr.ide.configset.schema.hint

Showing what each field matches, inline beside its declaration.

[Why an inlay rather than a
tooltip](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#orgapachesolrideconfigsetschemahint).

# Package org.apache.solr.ide.configset.schema.intention

Offering to improve a file that is already correct.

Separate from `inspection` on purpose: an inspection claims something is wrong, and a
field without prefix support is not wrong. What belongs here is anything the user might
want and the plugin can write, where staying silent is also a valid answer.

[Why the boundary against
inspections](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#orgapachesolrideconfigsetschemaintention).

# Package org.apache.solr.ide.configset.schema.completion

Offering the values a schema attribute can legally take, and the vocabulary legal
at the caret.

[Why only closed sets are
completed](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#orgapachesolrideconfigsetschemacompletion-and-orgapachesolrideconfigsetsolrconfigcompletion).

# Package org.apache.solr.ide.configset.schema.reference

Turning the strings that hold a schema together into references the editor
understands: a field's `type`, both ends of a `copyField`, and an analyzer
component's resource files.

[Why they are soft, and how far a glob is
followed](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#orgapachesolrideconfigsetschemareference-orgapachesolrideconfigsetsolrconfigreference-and-orgapachesolrideconfigsetnavigation).

# Package org.apache.solr.ide.configset.solrconfig

Everything anchored in `solrconfig.xml`, with the parameter-to-PSI mapping its
gestures share at the root and one subpackage per gesture.

The file the plugin's users edit most, and the one with no Schema API
alternative. Its gestures consult schema knowledge constantly — a `qf` names
fields the schema declares — but they reach it through `model`, never through
`configset.schema`, which is what keeps the two aspects independent.

# Package org.apache.solr.ide.configset.solrconfig.parsing

Reading `solrconfig.xml` into facts: the field references its handler parameters
make, and the operations those parameters imply.

# Package org.apache.solr.ide.configset.solrconfig.inspection

Reporting what is wrong in `solrconfig.xml`: a parameter naming a field the
schema never declares, a query field no query can search, and a parameter asking
of a field an operation its type does not support.

All three fire only on `solrconfig.xml`. That used to be a guard repeated in each
visitor; it is now the package they live in.

# Package org.apache.solr.ide.configset.solrconfig.completion

Offering the schema's field names inside a handler parameter's text — the one
answerable position in `solrconfig.xml` that is neither an attribute value nor a
tag name.

# Package org.apache.solr.ide.configset.solrconfig.reference

Turning the field names inside a handler parameter into references to their
schema declarations. These are the references that cross the file boundary, and
the reason Find Usages on a schema declaration finds anything outside its own
file.

# Package org.apache.solr.ide.server.connection

Remembering how to reach a live Solr, and where that memory may be written. A
configset root is a fact about the project and is shared; a connection is a fact
about one developer's machine, so definitions persist to the per-user workspace
file and credentials to the IDE's PasswordSafe.

Unreachable from the editor path.

[Where its state may be
written](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#orgapachesolrideserverconnection).
