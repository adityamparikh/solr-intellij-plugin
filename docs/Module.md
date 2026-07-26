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
  the field model.
- `org.apache.solr.ide.model` — the field model itself: fields, dynamic fields,
  field types, analyzer chains, and what each can do.
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
a project nothing activates, however Solr-shaped a file name looks; inside one, a
recognized name ([SolrConfigsetFileKind]) is believed on its own, and
[SolrConfigsetLocator] resolves the owning directory without needing a `conf/`
parent or a corroborating sibling.

That gate replaced a set of directory heuristics. A dependency is a fact where the
surroundings of a file were only ever an inference, and an inference is wrong in
both directions and cannot be explained to a user who disagrees with it. The one
project shape it cannot serve — a repository of configsets with no build file, and
so no dependencies — activates through a manually marked root instead.

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
