<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# solr-intellij-plugin Changelog

## [Unreleased]

**The plugin now reaches a live Solr, and the Java and Kotlin that call it.** The editor half
below is unchanged in one respect that matters: it still never contacts a server. Everything here
that does, does so only when asked.

### Added

- **Connections.** Settings → Tools → Solr Connections, or the `+` in the Solr tool window. The URL
  and username go to the workspace file; the password to the IDE's password safe, never a project
  file.
- **The Solr tool window.** Collections, shards and replicas for SolrCloud, cores for a standalone
  server — the mode read first rather than guessed — and, per collection, the fields the index
  actually holds, including those a dynamic pattern created.
- **Queries through the IDE's HTTP Client.** Starting requests under **Add Request → Solr**, written
  against `{{solrUrl}}` so a committed `.http` file works on a colleague's machine; a readable
  summary above each response, with the scoring explanation when asked; and field-name completion in
  a JSON request body.
- **The drift view.** A configset compared with a collection: fields not deployed, only on the
  server, or defined differently, with both sides shown. Upload and reload, and additive Schema API
  changes — each confirming its target first, and each followed by a fresh read of the server rather
  than trust in the write's success code. A changed definition is never applied for you, and the
  request is shown with the reason.
- **Indexing a test document** into a chosen collection, starting from a document built from its
  schema, with the two mistakes Solr answers `status: 0` to — a field the schema cannot place, a
  missing unique key — refused before sending.
- **Field names in Java and Kotlin using SolrJ.** A name no configset declares is reported, in query
  builder calls, raw parameter strings, document fields and `@Field` annotations; names complete from
  the configsets; <kbd>Ctrl-click</kbd> reaches the declaring `<field>`; fields and operators inside
  a query string are coloured; and a gutter icon runs the query against a chosen collection. Silent
  in any module without a Solr client, and wherever the name cannot be resolved.

## [0.1.0] - 2026-08-20

**The first release, and it is the editor half of the plugin.** Everything below works against the
files in your project with no Solr running and no connection configured — that is the whole design
rather than a limitation of a first release: a configset is text, and the plugin reads it the way the
IDE reads any other language.

**What it does not do yet:** talk to a server. No collection browsing, no query console, no comparing
your configset against a live one. That is the Server track, and it is 0.2.0.

Supported IDEs and Solr lines are in [the compatibility matrix](docs/compatibility.md). See
[the implementation plan](specs/plans/0002-solr-intellij-plugin-plan.md) for status by step,
[the user guide](docs/user-guide.md) for what each capability looks like in the editor, and
[the inspection catalog](docs/inspection-catalog.md) for what the plugin checks and what it
deliberately stays silent about.

### Added

**Detection and activation** — the plugin activates only inside a project that depends on a Solr
client (matched by artifact id, never version) and only for a configset's own files, resolved to the
specific configset that owns them so a project with more than one stays distinct.

**Match-capability hints** — an inline hint beside every field declaration stating what it can
actually match (whole-value or tokenised, case-sensitive or not, prefix-capable or not), derived from
its real analyzer chain rather than assumed from its type name, plus the storage shape that decides
whether a match can be returned at all (indexed, stored, doc-valued, single- or multi-valued). Silent
wherever the analysis is not confident, rather than risking a wrong claim.

**Quick documentation** on every position a reader tries: a field's resolved properties and where
each value came from (the field, its type, or Solr's own default at the schema's declared version);
a class value's kind, attributes, and a Reference Guide link; the schema elements themselves
(`<schema>`, `<field>`, `<fieldType>`, `<dynamicField>`, `<copyField>`, `<uniqueKey>`); an attribute's
own name; a factory attribute's owner, value type and default; a factory tag's complete effective
configuration, unwritten attributes shown at their Solr default; and, in `solrconfig.xml`, a request
parameter's purpose and a `defType` value's query parser.

**Boost syntax explained**, which is the first thing the plugin says about a parameter's *value*
grammar rather than its name. A caret on the `^3` of a `qf`'s `name^3` says what the boost scales,
names the field it applies to and whether Solr can actually search it, and says when a boost changes
nothing — `^1` is the default. Each parameter gets its own sentence, because the same `^n` means
different things: `qf` scales a term match, `pf` a phrase, `pf2` and `pf3` bigram and trigram
phrases, while `bf` and `boost` scale a function query whose value is added to or multiplied into the
score, and name no field at all.

**Completion** for the schema's own vocabulary (elements, attributes, and the boolean values Solr
would use by default), for the classes and factory attributes the generated catalog knows (following
whichever Solr line the configset targets), for `solrconfig.xml`'s own structure (element and
attribute names, respecting nesting, replacing the platform's schema-less sibling-echo guess), for
`solrconfig.xml`'s 340-plus request parameter names and the closed set `defType` accepts, and for
schema field names inside the sixteen `solrconfig.xml` parameters known to hold them.

**Cross-file navigation and Find Usages** from a field's `type` to its field type, a `copyField`'s
`source`/`dest` to their fields, a `solrconfig.xml` handler parameter to the schema field it names,
and a filter or char filter's resource attribute to the `stopwords.txt`/`synonyms.txt`/`lang/` file it
opens — including from the declaration itself, and including the names a dynamic field's pattern
supplies but never spells literally. Results are grouped and labelled in Solr's own vocabulary rather
than the platform's generic fallback.

**Rename** for a field or field type, updating every reference across the file boundary into
`solrconfig.xml`; a dynamic field's pattern renames literal spellings and leaves names the pattern
only supplies untouched, so the unknown-field inspection can say so rather than the rename silently
rewriting a field name into a glob.

**Eleven inspections**, each held to a zero-false-positive bar against the configsets Solr itself
ships: a dangling `copyField`; a field or `solrconfig.xml` parameter naming an undeclared field type
or field; an unknown attribute or an attribute value outside what it accepts; a declared field type
nothing uses (dimmed, not underlined — it is dead weight, not a defect); an analyzer chain ordered so
a filter cannot do anything (a case-folding rule after case is already gone, or a graph-producing
filter feeding one that cannot read a graph); a relevance, faceting or sorting parameter naming a
field that cannot serve it, judged from a real disjunction over `indexed`/`docValues` rather than one
property in isolation; a `solrconfig.xml` element Solr no longer accepts, reported in Solr's own
retirement sentence; and a `solrconfig.xml` parameter name that is almost one Solr reads, without ever
flagging a name the catalog simply does not know — this file accepts plugin classes and parameters
from outside Solr. Every reference-based inspection offers the valid names as a quick-fix, ranked by
edit distance.

**Two Alt-Enter intentions that write new configuration**: an `_exact` companion field
(`StrField`-backed, for exact-match/sort/facet on a tokenised field's content) and a `_prefix`
companion field (EdgeNGram-backed, for efficient prefix queries), each generating the companion
`<field>`, its `<fieldType>` where one does not already exist, and the `<copyField>` joining them —
carrying the source field's `multiValued` across, since a multi-valued source copying into a
single-valued companion fails at index time. A third intention removes an attribute that only
restates the value Solr would have supplied anyway (dimmed at information severity, never a Problems
view entry, on both field properties and analysis-factory attributes).

**The generated Solr catalog**, built from every supported Solr line's own `solr-core` and `-sources`
artifacts at plugin build time: every `solr.*` field type, tokenizer, token filter and char filter
class with the attributes its constructor reads, their value types, and — where the bytecode proves
them — literal defaults and required markers; a one-sentence Javadoc summary per class; the element
and attribute vocabulary `solrconfig.xml` accepts, keyed by parent element so nesting is respected,
including which elements Solr has discontinued and in whose own words; and 340-plus request parameter
names and 44 query parser names with the first sentence of Solr's declaring Javadoc.

### Fixed

- Boolean attribute values (`indexed`, `stored`, `multiValued`, and the checks behind the
  searchable/facetable/sortable inspections) are read case-insensitively, matching how Solr itself
  reads them with `Boolean.parseBoolean` — `indexed="TRUE"` no longer silently read as unset or false,
  which previously either produced a wrong property value or underlined a field that works.
- `fl`-family parameters (`fl`, `qf`, `pf`, `pf2`, `pf3`, `bf`) split on commas and whitespace both,
  so a multi-line or comma-joined parameter value no longer produces a bogus field name out of the
  line break.
- The Alt-Enter quick-fix on an unknown-attribute-name warning previously listed the correct
  spellings but applying one did nothing; it now actually renames the attribute.
- `_docid_` in a `sort` clause was flagged as a missing field; it is a sort term Solr answers itself
  and is no longer reported.
- The analysis-factory attribute `splitOnCaseChange` is now read the way Solr itself reads it, fixing
  a false positive in the analyzer-chain-ordering inspection.
- The facet- and sort-requirement inspection messages now name only what the operation in question
  actually needs, rather than a shared message that named a requirement (single-valuedness) only
  sorting has.
- A field's `omitNorms` and `docValues` resolve from its field type's class traits where the schema
  version alone cannot decide them, rather than deferring to "see the guide."
- Quick documentation on a `class` value no longer throws while the project is still indexing —
  previously a crash here could take down the rest of the popup along with it, including facts that
  needed no index at all.
- Find Usages on a resource file (`stopwords.txt` and the like) now groups its results under
  "Analyzer component reading this file" instead of leaving them unclassified.
- Reference Guide links now point at the Solr version the catalog actually answered from, and at
  page names that still resolve — including the un-hyphenated `charfilterfactories.html` the guide
  actually serves.
- The inline match-capability hint renders as one segment per summary part rather than one run-on
  string.
