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

### What exists today

What exists today is the configuration-files surface, minus rename and the
catalog-backed half of completion and documentation:

- Activation ([org.apache.solr.ide.configset.activation]) — the plugin runs only
  in a project that depends on a Solr client, and within one, only on files whose
  names identify a configset.
- The field model ([org.apache.solr.ide.model]) and the reader that fills it
  ([org.apache.solr.ide.configset.parsing]) — what fields, types, analyzer chains
  and copy-field directives a configset declares, which field names
  `solrconfig.xml` references, and what each field can actually match.
- Inspections ([org.apache.solr.ide.configset.inspection]), completion
  ([org.apache.solr.ide.configset.completion]), navigation
  ([org.apache.solr.ide.configset.reference]), quick documentation
  ([org.apache.solr.ide.configset.documentation]) and inline match hints
  ([org.apache.solr.ide.configset.hint]).
- Connection settings ([org.apache.solr.ide.server]) — the per-user list of Solr
  servers, with credentials in the IDE's PasswordSafe. Storage only; nothing
  talks to a server yet.

Rename, Find Usages, quick-fixes, the generated factory catalog, everything that
talks to a server, and all of the Java and Kotlin code support are **not yet
implemented**. Consult the
[specification](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/specs/0002-solr-intellij-plugin.md)
for their specified behavior, and the
[implementation plan](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/specs/plans/0002-solr-intellij-plugin-plan.md)
for what has landed, rather than inferring status from this API reference.

### Documentation conventions

Every public declaration in this module carries KDoc, enforced by the build:
Dokka runs with `reportUndocumented` and `failOnWarning`, and `dokkaGenerate` is
a dependency of `check`. An undocumented public class, function, or property
fails `./gradlew build` locally and in CI. Adding public API therefore means
documenting it in the same change.

### Where later work goes

The specification describes the plugin as one model of the fields a configset
declares, shared by three surfaces. That shape determines the package layout, and
the packages are named here so that landing work has an obvious home rather than
inventing one:

Packages are organised by **feature, and by feature again inside**. The three
surfaces the specification describes are the top level; within a surface, each
package is one capability rather than one layer.

- `org.apache.solr.ide.model` — the shared vocabulary, and the one package that
  is not a feature. It is what both surfaces read, and the only package with no
  IntelliJ types in it. **Exists.**
- `org.apache.solr.ide.configset.*` — the configuration-files surface, split by
  capability: `activation`, `parsing`, `inspection`, `completion`, `reference`,
  `documentation`, `hint`. **Exists.**
- `org.apache.solr.ide.server` — the live-server surface. **Partly exists**:
  connection settings only.
- Recognizers for Java and Kotlin code get their own surface when the first one
  is written. Packages are created when they have a file to hold, not before.

# Package org.apache.solr.ide

Plugin-wide infrastructure shared by all feature packages.

Currently holds the localization bundle. Feature code lives in subpackages.

# Package org.apache.solr.ide.model

What the plugin knows about a configset's fields, as data — and the one package
that is not a feature.

It is shared on purpose. [SolrFieldModel] exists to merge a repository half with
a server half, so it belongs to both surfaces and filing it under either would be
wrong. It is also the only package with no IntelliJ types anywhere in it, which is
what lets the component every feature reads be tested as a plain unit test over a
string rather than inside a running IDE.

A fact is a [SolrFact] holding both halves and reporting how they relate
([SolrAgreement]), rather than a value with the disagreement already resolved
away. Where only one can be shown the repository wins, because the editor's job is
to reason about the file in front of the user; the difference is surfaced rather
than hidden. The server half is empty until the server reader lands.

[SolrMatchAnalysis] derives what a field can actually match from its index-time
analyzer chain. The factories that decide it are named in code rather than read
from the generated catalog, because that set defines the semantics rather than
enumerating what exists. [SolrMatchCapability] names the *mechanism* behind
partial matching rather than asserting a boolean — a wildcard query works against
any indexed field, so the useful claim is about efficiency — and carries a
confidence flag, because a wrong claim about what a field matches is worse than
no claim.

# Package org.apache.solr.ide.configset.activation

Deciding whether the plugin runs at all, and against which configset.

[SolrProjectDetector] is the outer gate: the plugin activates only in a project
whose dependencies include a Solr client, matched by artifact id so that no
version appears in the rule. That is a fact rather than an inference, which is why
it replaced the directory heuristics an earlier revision used.

Inside such a project, names are tiered by what they prove
([SolrConfigsetFileRole]). `solrconfig.xml` and `managed-schema.xml` carry Solr's
own vocabulary and stand alone; `schema.xml` and `params.json` are shared with too
many other things and count only inside a directory a self-identifying name has
already proven; resources such as `stopwords.txt` never activate anything.

[SolrConfigsetLocator] resolves which configset owns a file and caches the answer,
since this runs on every file the user opens. A repository of configsets with no
build file has no dependencies to find, so it activates through a manually marked
root in [SolrConfigsetSettings] — which makes that override load-bearing rather
than a convenience.

# Package org.apache.solr.ide.configset.parsing

Reading a configset off disk into the model.

Parsing goes through the JDK's DOM rather than IntelliJ's XML PSI, so the parsers
are pure functions from text to facts and can be tested without an IDE. External
entities and doctypes are refused: a cloned repository is not trusted input, and
entity resolution would run while the user is merely opening a file.

[SolrConfigsetReader] caches a model per configset, keyed on the modification
stamps of the files it actually read — so the model rebuilds when the schema
changes and, the half that costs performance, not when anything else does. Text
comes from the in-memory document when one exists, so a field added in the editor
is in the model before the file is saved. Its `modelFor(PsiFile)` is the question
every editor feature asks, and lives here rather than beside any one of them.

[SolrConfigsetScanner] answers what the per-file locator cannot: which configsets
does this *project* contain. It walks content roots and prunes build output and
dependency trees, so it is not an editor-path operation.

# Package org.apache.solr.ide.configset.inspection

Reporting references that go nowhere.

A dangling `copyField`, a field naming an undeclared type, and — crossing the file
boundary nothing else checks — a handler parameter in `solrconfig.xml` naming a
field the schema never declares.

The clean fixtures matter more than the flagged ones. Solr configuration is full
of syntax that resembles a field name: `fl` legitimately holds `score`, `*`,
`[docid]`, `max(price,0)` and `alias:name`, and a glob `copyField` source is a
pattern whose matches the schema alone cannot determine. A warning on a correct
file is what gets a plugin uninstalled.

# Package org.apache.solr.ide.configset.completion

Offering the values an attribute can legally take.

Only closed sets are completed — the declared field types, the declared fields,
and `true`/`false`. Where any value is legal, nothing is contributed and the
platform's own behaviour is left alone: a list implies that the values not on it
are wrong, so a partial list in an open-ended position is worse than none.

# Package org.apache.solr.ide.configset.reference

Turning the strings that hold a configset together into references the editor
understands.

References are *soft*. An unresolved hard reference draws a warning from the
platform, which would duplicate what the inspections already report and say less
while doing it, in the platform's vocabulary rather than Solr's.

[SolrSchemaPsi] exists because the model holds no PSI: it can say a field type
exists but not where it was written, and navigation needs the second answer.

# Package org.apache.solr.ide.configset.documentation

Quick documentation on a schema element, on a field, and on its type.

Hovering an element answers, not only hovering a value inside one. That matters
more than it sounds: before it, everything the plugin knew was reachable only by
putting the caret inside an attribute value — a gesture a reader makes once they
already suspect something. [SolrSchemaElements] holds what each element is, and
what *this* one does where the model can say: which fields a copy rule joins and
whether both ends exist, which field is the unique key, how many fields use a
type.

Answers what the Reference Guide cannot: not what `omitNorms` means in general,
but what it is *for this field in this schema*, and whether that value came from
the field, from its type, or from Solr's default. Some defaults genuinely depend
on the field type, and those are reported as such rather than given a plausible
value.

Documentation links to the Reference Guide rather than copying it, at the version
the configset declares. Links are page-level — anchors drift between releases and
field types have no per-class anchor at all — and nothing on the editor path
fetches a URL.

# Package org.apache.solr.ide.configset.hint

Showing what each field matches, inline beside its declaration.

An inlay rather than a tooltip on purpose: a user who does not already suspect
their field cannot match a prefix will never hover over it to find out. Nothing is
shown where the analysis is not confident, or where a field names a type the
configset does not declare — a wrong claim here is worse than a missing one, and
this is the output most likely to be quoted back.

# Package org.apache.solr.ide.server

Talking to a live Solr server, and remembering how to reach one.

Currently holds connection settings only. The distinction that governs this
package is where its state may be written: a configset root is a fact about the
project and is shared, whereas a connection is a fact about one developer's
machine. Connection definitions therefore persist to the per-user workspace file
and their credentials to the IDE's PasswordSafe, never to a file that could be
committed ([SolrConnectionSettings]).

Nothing in this package may be reached from the editor path. Configset editing
works with no connection configured at all.
