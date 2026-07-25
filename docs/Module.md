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

This module currently implements the **activation gate** for Phase 1 and little
else. Recognizing which files are Solr configsets is a prerequisite for every
Phase 1 feature, so it landed first:

- Configset detection ([org.apache.solr.ide.configset]) — deciding whether a file
  is part of a Solr configset, with persistent per-project settings for the
  manual override.
- Registration of the extensionless `managed-schema` file name with the XML file
  type, so configsets parse as XML for the PSI-based features to come.

The Phase 1 features themselves — schema completion (S1), cross-file references
(S2), rename (S3), inspections (S4), match-capability hints and quick-fixes
(S5/S6), and inline component documentation (S7) — are **not yet implemented**.
Consult the
[specification](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/specs/0002-solr-intellij-plugin.md)
for their specified behavior rather than inferring status from this API
reference.

### Documentation conventions

Every public declaration in this module carries KDoc, enforced by the build:
Dokka runs with `reportUndocumented` and `failOnWarning`, and `dokkaGenerate` is
a dependency of `check`. An undocumented public class, function, or property
fails `./gradlew build` locally and in CI. Adding public API therefore means
documenting it in the same change.

# Package org.apache.solr.ide

Plugin-wide infrastructure shared by all feature packages.

Currently holds the localization bundle. Feature code lives in subpackages.

# Package org.apache.solr.ide.configset

Recognition of Solr configset files — the gate that decides whether Phase 1
features activate for a given file.

Detection cannot be exact. A Solr configset is a directory convention, not a
declared project structure, and the file names involved (`schema.xml` in
particular) are common enough to appear in unrelated projects. The package
therefore combines two signals: file-name matching against the names Solr
recognizes ([SolrConfigsetFileKind]), refined by directory heuristics that look
for corroborating evidence — a conventional `conf/` parent, or a sibling
configset file ([SolrConfigsetDetector]).

Because heuristics have both false positives and false negatives, detection is
backed by an escape hatch: users can mark directories as configset roots
explicitly, or disable detection entirely, via the persistent project settings in
[SolrConfigsetSettings]. When a user reports that features did not activate on
their configset, that override is the answer, and the behavior is documented for
end users under requirement D5 of the
[specification](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/specs/0002-solr-intellij-plugin.md).
