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

This module currently implements the **activation gate** and nothing else.
Recognizing which files are Solr configsets, and which configset each belongs to,
is a prerequisite for every feature, so it landed first:

- Configset detection and resolution ([org.apache.solr.ide.configset]) — deciding
  whether a file is part of a Solr configset and which configset owns it, with
  persistent per-project settings for the manual override.
- The field model ([org.apache.solr.ide.model]) and the reader that fills it
  ([org.apache.solr.ide.repository]) — what fields, types, analyzer chains and
  copy-field directives a configset declares, and which field names
  `solrconfig.xml` references.
- Connection settings ([org.apache.solr.ide.server]) — the per-user list of Solr
  servers, with credentials in the IDE's PasswordSafe. Storage only; nothing
  talks to a server yet.
- Registration of the extensionless `managed-schema` file name with the XML file
  type, so configsets parse as XML for the PSI-based features to come.

The features themselves — schema completion, cross-file references, rename,
inspections, match-capability hints and quick-fixes, inline component
documentation, the query console, and the SolrJ code integration — are **not yet
implemented**. Consult the
[specification](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/specs/0002-solr-intellij-plugin.md)
for their specified behavior rather than inferring status from this API
reference.

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

- `org.apache.solr.ide.configset` — the activation gate. **Exists.**
- `org.apache.solr.ide.server` — connection settings, the HTTP client and the
  server-side reader. **Partly exists**: settings only.
- `org.apache.solr.ide.repository` — reading a configset directory off disk into
  the field model. **Exists.**
- `org.apache.solr.ide.model` — the field model itself: fields, dynamic fields,
  field types, analyzer chains, and what each can do. **Exists.**
- `org.apache.solr.ide.editor` — what the user sees in the editor: inlay hints,
  quick documentation, and the inspections and quick-fixes to come. **Exists.**
- `org.apache.solr.ide.recognizer` — spotting field names and queries in Java and
  Kotlin code, per client library.
- `org.apache.solr.ide.ui` — tool windows, the query console and the drift view.

# Package org.apache.solr.ide

Plugin-wide infrastructure shared by all feature packages.

Currently holds the localization bundle. Feature code lives in subpackages.

# Package org.apache.solr.ide.configset

Recognition of Solr configset files — the gate that decides whether features
activate for a given file, and which configset they activate against.

The package answers two questions that are easy to conflate. *Should features
activate for this file?* is a property of one file. *Which configset does it
belong to?* is a property of a directory, and it is the one that matters to
everything downstream: fields, field types and analyzer chains belong to a
configset as a whole, and a project may hold several that must not be merged.

Activation is settled before either question is asked. [SolrProjectDetector]
checks whether the open project depends on a Solr client — `solr-solrj` or a
wrapper that carries it — matching artifact ids rather than versions. Outside such
a project nothing activates, however Solr-shaped a file name looks.

Inside one, names are tiered by how much they prove
([SolrConfigsetFileRole]). `solrconfig.xml` and `managed-schema.xml` carry
Solr's own vocabulary and stand alone, so one of them makes its directory a
configset. `schema.xml`, `params.json` and `currency.xml` are shared with too
many other things to prove anything, and are recognized only inside a directory
a self-identifying name has already proven — in a real configset the
`solrconfig.xml` beside them does that. Resources such as `stopwords.txt` are
recognized only from within an identified configset and never activate features
themselves. [SolrConfigsetLocator] resolves the owning directory on that basis,
with no `conf/` parent or sibling-count rule involved.

The gate replaced a set of directory heuristics. A dependency is a fact where the
surroundings of a file were only ever an inference, and an inference is wrong in
both directions and cannot be explained to a user who disagrees with it. The
tiering above is containment rather than inference for the same reason: it asks
whether a directory holds a name Solr invented, which has an exact answer. The
one project shape the gate cannot serve — a repository of configsets with no
build file, and so no dependencies — activates through a manually marked root
instead.

Recognized names are split by what they are allowed to prove
([SolrConfigsetFileRole]). `solrconfig.xml` is evidence a configset exists;
`stopwords.txt` is not, because that name belongs to too many things that have
nothing to do with Solr. Resource names are recognized only from inside a
configset already identified by other means.

Because detection runs on every file the user opens, resolution is cached and
invalidated on filesystem structure changes and settings changes
([SolrConfigsetLocator]). Because heuristics have both false positives and false
negatives, it is also backed by an escape hatch: users can mark directories as
configset roots explicitly, or disable detection entirely, via the persistent
project settings in [SolrConfigsetSettings]. When a user reports that features did
not activate on their configset, that override is the answer.

# Package org.apache.solr.ide.server

Talking to a live Solr server, and remembering how to reach one.

Currently holds connection settings only. The distinction that governs this
package is where its state may be written: a configset root is a fact about the
project and is shared, whereas a connection is a fact about one developer's
machine. Connection definitions therefore persist to the per-user workspace file
and their credentials to the IDE's PasswordSafe, never to a file that could be
committed ([SolrConnectionSettings]).

Nothing in this package may be reached from the editor path. Configset editing
works with no connection configured at all, and a server call on the path that
runs when a user opens a file would make typing depend on a network.

# Package org.apache.solr.ide.model

What the plugin knows about a configset's fields, as data.

Pure Kotlin with no IntelliJ types anywhere in it, which is deliberate:
[SolrFieldModel] is the component every feature reads, so it has to be the
component that is most exhaustively tested, and that is only affordable when its
tests are plain unit tests over a string rather than fixtures around a running
IDE.

The model has **two sources that can disagree** — the configset in the
repository, and the collection running on a server. Drift between them is a
real failure mode: a field added to the schema but never deployed, or added
through the Schema API and never committed. So a fact is not a value but a
[SolrFact], holding both halves and reporting how they relate
([SolrAgreement]). Where only one can be shown the repository wins, because the
editor's job is to reason about the file in front of the user; the difference
is surfaced rather than resolved.

The server half is empty until the server reader lands. The seam exists now
because retrofitting a second source into a model shaped around one means
revisiting everything built on it.

[SolrMatchAnalysis] is the other half of this package: a pure function from an
index-time analyzer chain to what a field can actually match — whole value or
tokens, prefix-capable or not, case-sensitive or not. It is the plugin's most
surprising output, and the factories that decide it are named in code rather than
read from the generated factory catalog, because that set defines the semantics
rather than enumerating what exists.

Two things shape [SolrMatchCapability]. It names the *mechanism* behind partial
matching rather than asserting a boolean, because a wildcard query works against
any indexed field and the useful claim is about efficiency. And it carries a
confidence flag: an unrecognized factory makes the analysis decline rather than
guess, since a wrong claim about what a field matches is worse than no claim.

# Package org.apache.solr.ide.repository

Reading a configset off disk into the model.

Parsing goes through the JDK's DOM rather than IntelliJ's XML PSI. The parsers
are pure functions from text to [org.apache.solr.ide.model.SolrConfigsetFacts],
so they can be tested without an IDE; the PSI-based features that come later
resolve elements by name at the point of use, which they must do anyway.
External entities and doctypes are refused, because a cloned repository is not
trusted input and entity resolution would run while the user is merely opening
a file.

[SolrConfigsetReader] caches a model per configset, keyed on the modification
stamps of the files it actually read. That is what makes the model rebuild when
the schema changes and — the half that costs performance if it is wrong — not
rebuild when anything else does. Text comes from the in-memory document when one
exists, so a field added in the editor is in the model before the file is saved.

[SolrConfigsetScanner] answers the question the per-file locator cannot: which
configsets does this *project* contain. It walks the content roots and is
therefore not an editor-path operation; it prunes build output and dependency
trees so that staying off that path remains affordable.

# Package org.apache.solr.ide.editor

What the user actually sees.

[SolrDanglingCopyFieldInspection] reports a copy rule naming a field that does not
exist — the failure the plugin exists for, since Solr accepts the file and
rejects it only at core reload. Its clean fixtures matter more than its flagged
ones: a warning on a correct file is what gets a plugin uninstalled.

[SolrUnknownFieldTypeInspection] reports a `type` the configset never declares —
the mistake most easily made when something offers a word from elsewhere in the
file as a value.

[SolrUnknownFieldReferenceInspection] crosses the file boundary: a `qf` in
`solrconfig.xml` naming a field the schema does not declare is not an error to
Solr, and a query using it simply returns fewer results. Its precision rules
matter most — `fl` legitimately contains `score`, `[docid]` and
`max(price,0)`, none of which is a field.

[SolrConfigsetReferenceContributor] turns the strings that hold a configset
together into references the editor understands, starting with a field's `type`.
Its references are *soft*: an unresolved hard reference draws a warning from the
platform, which would duplicate what the inspections already report and say less
while doing it.

[SolrConfigsetCompletionContributor] covers the attribute values whose valid set
is closed, and deliberately nothing else. A list implies the values not on it are
wrong, so offering a partial one where any value is legal would be worse than
offering none.

[SolrMatchInlayHintsProvider] shows what each field matches inline beside its
declaration — an inlay rather than a tooltip because a user who does not already
suspect their field cannot match a prefix will never hover over it to find out.
[SolrConfigsetDocumentationProvider] answers the follow-up question on the type
and on the field itself.

Both render through [SolrFieldPresentation], which exists so the two cannot
disagree about what a field matches: the surest way to keep a hint and a popup
consistent is for both to render the same computed value rather than each
phrasing it independently.

Two rules govern what is said. **Nothing is claimed that was not determined** —
where match analysis is not confident, or a field's type is undeclared, the hint
is absent rather than approximate. And **the match claim states that it is about
efficient matching**, because a wildcard query works against any indexed field
and a reader who knows that would otherwise conclude the hint is simply wrong.

Documentation resolves each property through field, then field type, then Solr's
default, and says which of the three answered. That resolution is the half no
external documentation can supply; the Reference Guide is linked for the prose,
at the version the configset declares.
