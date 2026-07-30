# Module Solr IntelliJ Plugin

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
that is not a feature. It is what both surfaces read, and the only package with
no IntelliJ types in it, which is what lets the correctness-critical code be
tested without a running IDE.

[Why it is shared, and what `SolrFact`, `SolrMatchAnalysis` and `SolrClassCatalog`
are for](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#orgapachesolridemodel).

# Package org.apache.solr.ide.configset.activation

Deciding whether the plugin runs at all, and against which configset.

[The two gates, the name tiers, and why the manual override is
load-bearing](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#the-activation-decision).

# Package org.apache.solr.ide.configset.parsing

Reading a configset off disk into the model, and caching the result.

[The parsers, the cache and its dependency
list](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#from-files-to-the-model).

# Package org.apache.solr.ide.configset.inspection

Reporting references that go nowhere — a dangling `copyField`, a field naming an
undeclared type, and a handler parameter naming a field the schema never
declares.

[Why the clean fixtures matter more than the flagged
ones](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#orgapachesolrideconfigsetinspection).

# Package org.apache.solr.ide.configset.completion

Offering the values an attribute can legally take, and the vocabulary legal at
the caret.

[Why only closed sets are
completed](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#orgapachesolrideconfigsetcompletion).

# Package org.apache.solr.ide.configset.descriptor

Owning the XML element descriptors for schema files, so the platform's answers
come from the plugin's knowledge instead of from schema-less guessing — which
echoed one filter's attributes onto every other.

Deliberately permissive: every unknown attribute and element resolves rather
than being flagged, because validation is the inspections' job and they know
when not to fire.

# Package org.apache.solr.ide.configset.reference

Turning the strings that hold a configset together into references the editor
understands.

[Why they are soft, and how far a glob is
followed](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#orgapachesolrideconfigsetreference).

# Package org.apache.solr.ide.configset.documentation

Quick documentation on a schema element, on a field, and on its type.

[What it answers that the Reference Guide
cannot](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#orgapachesolrideconfigsetdocumentation).

# Package org.apache.solr.ide.configset.hint

Showing what each field matches, inline beside its declaration.

[Why an inlay rather than a
tooltip](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#orgapachesolrideconfigsethint).

# Package org.apache.solr.ide.server

Talking to a live Solr server, and remembering how to reach one. Currently
connection settings only, and unreachable from the editor path.

[Where its state may be
written](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#orgapachesolrideserver).
