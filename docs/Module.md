# Module Solr IntelliJ Plugin

IDE tooling for Apache Solr development on the IntelliJ Platform: three surfaces over one field
model. The **configset editor** is static analysis of configset files (`managed-schema.xml` /
`schema.xml`, `solrconfig.xml`) — completion, cross-file references, rename, inspections and
match-capability hints, all working offline, with no Solr connection. The **server** surface connects to a running Solr: it
browses collections, runs queries, compares a configset with what is deployed, and indexes test
documents. The **code** surface reads the project's own Java and Kotlin for the fields it asks Solr
for.

Each package below says what it holds. Why the packages are split this way, what each boundary
forbids, and where a change belongs is in
[docs/code-organization.md](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md).
What is built is owned by the
[implementation plan](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/specs/plans/0002-solr-intellij-plugin-plan.md),
not by this reference.

# Package org.apache.solr.ide

Plugin-wide infrastructure: the localization bundle, `SolrBundle`, and the icon holder, `SolrIcons`.
Feature code lives in subpackages.

# Package org.apache.solr.ide.model

What the plugin knows about a configset's fields, as data. It is what both the repository and the
server surface read, and the only tree with no IntelliJ types in it.

This package holds what is independent of any one source: `SolrConfigsetFacts`, the shape a parser
produces; `SolrFieldModel` with `SolrFact` and `SolrAgreement`, which merge a repository half with a
server half; and `SolrReferenceGuide`.

A fact holds both halves and reports how they relate, rather than a value with the disagreement
already resolved away. Where only one can be shown the repository wins, because the editor's job is
to reason about the file in front of the user. Only a server surface fills the server half — the
drift view — because nothing on the editor path may contact a server.

# Package org.apache.solr.ide.model.schema

What a field *is*, whichever source described it — properties and their resolution, field types and
their traits, schema versions, and the match analysis that says what a chain can actually match.

Everything here is knowledge a server reader needs to interpret what it fetched, which is the test
that separates this package from `org.apache.solr.ide.model.vocabulary`.

`SolrMatchAnalysis` derives what a field can match from its index-time analyzer chain. The factories
that decide it are named in code rather than read from the generated catalog, because that set
defines the semantics rather than enumerating what exists. `SolrMatchCapability` names the
*mechanism* behind partial matching rather than asserting a boolean, and carries a confidence flag,
because a wrong claim about what a field matches is worse than no claim.

# Package org.apache.solr.ide.model.vocabulary

What a configuration file may legally contain: which attributes each element accepts, which elements
are legal under which parent, the request parameters Solr declares, and the generated catalog of
classes a `class` attribute may name.

`SolrClassCatalog`, `SolrElementCatalog` and `SolrParameterCatalog` read resources generated at
build time from every supported Solr line's artifacts — see the `generateSolrCatalog` block in
`build.gradle.kts`. Generated rather than written down because the lists run to hundreds of entries
per line and change between lines.

Distinct from `org.apache.solr.ide.model.schema` because a server never needs it: these answers are
about XML elements and attributes, and Solr's schema API returns JSON.

# Package org.apache.solr.ide.model.query

The grammar of a Solr query parameter's value: which parameters name fields (`SolrParameters`), and
which names a given value contains (`SolrQueryFields`).

Separate from `org.apache.solr.ide.model.vocabulary` because these are not XML. `solrconfig.xml`
writes these parameters inside `<lst name="defaults">`, Java or Kotlin writes the same strings into
`SolrQuery.addFilterQuery`, and the query console writes them directly — three readers of one
grammar, so it is stated once here rather than inside any one of their parsers. A second copy would
disagree with the first about how a value splits, and completion would offer names at a position no
reference resolves.

# Package org.apache.solr.ide.configset.activation

Deciding whether the plugin runs at all, and against which configset: the project detector, the file
roles, the locator, the manual-root settings, *Mark Directory as Solr Configset Root*, and the
**Solr Configsets** settings page.

[The two gates, the name tiers, and why the manual override is
load-bearing](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md#the-activation-decision).

# Package org.apache.solr.ide.configset.reading

Turning a configset directory into a model, and caching the result: the reader that both aspects go
through, the project-wide scan, the project's fields as code completion offers them, and the
hardened XML document loading both parsers share.

Cross-aspect by nature — a configset is read as a whole — so it sits at the configset root rather
than under `schema` or `solrconfig`.

[The parsers, the cache and its dependency
list](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/platform-mechanisms.md#caching-the-field-model).

# Package org.apache.solr.ide.configset.editing

The editor-path primitives both aspects share: `SolrInspections`, the guard rails that keep an
inspection off a correct file, and `SolrReplaceNameQuickFix`, which offers the valid alternatives
when a name does not resolve — ranked by edit distance, because the common cause is a typo, and
capped, because a schema with eighty fields must not answer one typo with eighty menu items.

# Package org.apache.solr.ide.configset.navigation

The gestures that cross the file boundary by design. A field is declared in the schema and
referenced from `solrconfig.xml`, so Find Usages, the declaration target and rename belong to the
configset as a whole rather than to either aspect.

`SolrSchemaPsi` is here for the same reason: it answers *where was this declared*, which is the
target end of every reference, including the ones a `solrconfig.xml` parameter makes. The model can
say a field type exists but not where it was written. `SolrClassReferenceContributor` is here because
a `class` attribute is written in both files; it is the one reference contribution that reads an
index, so it both declines dumb-awareness and guards with `DumbService`.

# Package org.apache.solr.ide.configset.schema

Everything anchored in a schema file — `managed-schema.xml` or `schema.xml` — one subpackage per
gesture. A capability belongs here when the caret that triggers it is always in a schema file.

# Package org.apache.solr.ide.configset.schema.parsing

Reading a schema file into facts. A pure function from text to `SolrConfigsetFacts`, using the JDK's
DOM rather than IntelliJ's XML PSI, which is what lets it be tested without an IDE.

# Package org.apache.solr.ide.configset.schema.inspection

Reporting what is wrong in a schema file: a dangling `copyField`, a field naming an undeclared type,
an attribute that does not exist or cannot hold the value written, an analyzer chain in an
impossible order, a field type nothing uses.

What each one reports and stays silent on is in the
[inspection catalog](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/inspection-catalog.md).

# Package org.apache.solr.ide.configset.schema.annotator

Dimming an attribute whose written value equals what Solr would have supplied anyway.

Not an inspection, because a restated default is correct Solr. `SolrRestatedAttribute` is the one
predicate both the dim and the removal intention read, so the two can never disagree.

# Package org.apache.solr.ide.configset.schema.documentation

What a hover over a schema element, a field, a field type, a factory attribute or the schema
`version` explains: the resolved property table, where each value came from — the field, its type,
or Solr's default at this schema's version — and what the field can actually match.

It answers what the Reference Guide cannot: not what `omitNorms` means in general, but what it is
*for this field in this schema*. Defaults that genuinely depend on the field type are reported as
such rather than given a plausible value. Links go to the Reference Guide at the version the
configset declares, page-level only, and nothing here fetches a URL.

# Package org.apache.solr.ide.configset.schema.descriptor

Owning the XML element descriptors for schema files, so the platform's answers come from the
plugin's knowledge instead of from schema-less guessing, which echoes one tag's attributes onto
every sibling of the same name.

Deliberately permissive: every unknown attribute and element resolves rather than being flagged,
because validation is the inspections' job and they know when not to fire.

# Package org.apache.solr.ide.configset.schema.hint

Showing what each field matches, inline beside its declaration.

An inlay rather than a tooltip, because a user who does not already suspect their field cannot match
a prefix will never hover over it to find out. Nothing is shown where the analysis is not confident,
or where a field names a type the configset does not declare.

# Package org.apache.solr.ide.configset.schema.intention

Offering to improve a file that is already correct: adding an exact-match or prefix-capable
companion field, and removing a restated default.

Separate from `inspection` on purpose: an inspection claims something is wrong, and a field without
prefix support is not wrong. The availability rules are pure functions over the model, so the cases
that decide *not* to offer can be tested without booting an IDE — an intention offered where it does
not apply is worse than one that is missing.

# Package org.apache.solr.ide.configset.schema.completion

Offering what may be written at the caret in a schema file: the elements legal there, the attributes
an element accepts minus those it already carries, and the values an attribute can take.

**Only closed sets are completed.** Where any value is legal, nothing is contributed, because a list
implies that the values not on it are wrong. `true`/`false` are marked with the value Solr would use
if the attribute were absent — except where that default depends on the field type.

# Package org.apache.solr.ide.configset.schema.reference

Turning the strings that hold a schema together into references the editor understands: a field's
`type`, both ends of a `copyField`, and an analyzer component's resource files.

References are **soft**: an unresolved hard reference draws a platform warning, which would
duplicate what the inspections report, in the platform's vocabulary rather than Solr's. A glob is
followed only as far as it is written — `copyField dest="*_t"` lands on the `dynamicField` spelling
the same pattern, never on a concrete field it might match.

# Package org.apache.solr.ide.configset.solrconfig

Everything anchored in `solrconfig.xml`, with the parameter-to-PSI mapping its gestures share
(`SolrConfigParameters`) at the root and one subpackage per gesture.

Its gestures consult schema knowledge constantly — a `qf` names fields the schema declares — but they
reach it through `model`, never through `configset.schema`, which is what keeps the two aspects
independent.

# Package org.apache.solr.ide.configset.solrconfig.parsing

Reading `solrconfig.xml` into facts: the field references its handler parameters make, the
operations those parameters imply, and the declared `luceneMatchVersion`.

# Package org.apache.solr.ide.configset.solrconfig.inspection

Reporting what is wrong in `solrconfig.xml`: a parameter naming a field the schema never declares, a
query field no query can search, a facet or sort naming a field that cannot serve it, an element Solr
no longer accepts, and a parameter name one edit from one Solr reads.

What each one reports and stays silent on is in the
[inspection catalog](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/inspection-catalog.md).

# Package org.apache.solr.ide.configset.solrconfig.completion

The three answerable positions in `solrconfig.xml` beyond its element structure: a parameter's name
inside a parameter list, the parser a `defType` selects, and the schema's field names inside a
parameter that holds them.

# Package org.apache.solr.ide.configset.solrconfig.descriptor

Owning the XML element descriptors for `solrconfig.xml`, from the generated element catalog, so
element and attribute completion offers Solr's own vocabulary at each position — filtered to what
Solr still accepts.

A provider of its own rather than a widened schema one, so neither aspect owns a position the other
also has. Permissive in the same way: an element the catalog has never heard of — a custom
component — resolves rather than being flagged.

# Package org.apache.solr.ide.configset.solrconfig.documentation

What a request parameter is for, and what a `defType` value selects, read from the generated
parameter catalog. Both positions are only ever in `solrconfig.xml`, so they belong to this aspect;
the provider declines outright unless its own resource carries the name under the caret.

# Package org.apache.solr.ide.configset.solrconfig.reference

Turning the field names inside a handler parameter into references to their schema declarations.
These are the references that cross the file boundary, and the reason Find Usages on a schema
declaration finds anything outside its own file.

# Package org.apache.solr.ide.code

The contract every code recognizer implements, and the two facts one reports: the Solr servers a
source file says it talks to, and the fields it asks them for.

`SolrUsageRecognizer` is what a recognizer implements; `SolrRecognizers` is what a consumer calls.
The split exists for the module gate: a recognizer declares the library coordinates it needs and
never checks for them, so the check cannot be the thing a later recognizer forgets.
`SolrEndpointUsage` carries a username beside its URL and no password, because what a source file
holds in that position is usually a placeholder — the IDE's password safe holds the real one.

# Package org.apache.solr.ide.code.solrj

Reading Solr usage out of Java and Kotlin, and the facts about SolrJ's API that reading it needs:
which `SolrQuery` builder calls carry field names, which Solr request parameter each writes, and what
shape its arguments are in.

`SolrJQueryMethods` is plain functions from strings, testable without a fixture. `SolrJRecognizer`
reads through UAST, so Java and Kotlin are one implementation, and confirms a call's receiver really
is SolrJ's `SolrQuery` before reading anything. `SolrJFieldPositions` is the one answer to "is this a
position that names a field", shared by every code surface.

**One implementation is not one behaviour, and only a test tells them apart.** UAST unifies the
*shape* of a call, not everything inside it: a Java string arrives as a literal and a Kotlin string
never does, because Kotlin models every string as a template that might interpolate. Anything
reading an argument therefore has to descend rather than cast, and the mirrored Java and Kotlin test
suites keep a construct from being read in one language and dropped in the other.

# Package org.apache.solr.ide.code.inspection

Reporting a field name written in Java or Kotlin that no configset in the project declares. Silent
in a project with no configset to check against.

# Package org.apache.solr.ide.code.completion

Offering field names where Java or Kotlin code is naming one, and opening the popup by itself as one
is typed. `SolrCodeFieldCompletionConfidence` and `SolrCodeFieldTypedHandler` ask the same position
question the contributor does, so the popup never opens where completion then declines.

# Package org.apache.solr.ide.code.navigation

Making a field name written in Java or Kotlin navigable to the schema `<field>` that declares it.

# Package org.apache.solr.ide.code.highlighting

Colouring the fields and operators inside a Solr query written in a Java or Kotlin string, and the
colour settings page that tunes them.

# Package org.apache.solr.ide.code.run

A gutter icon beside a Solr query written in Java or Kotlin, which runs it against a collection
chosen from the selected connection. The one code package that reaches a server.

# Package org.apache.solr.ide.server.connection

Where a server is, who we authenticate as, and the **Solr Connections** settings page that creates
one.

A configset root is a fact about the project and is shared; a connection is a fact about one
developer's machine, so definitions persist to the per-user workspace file and credentials to the
IDE's PasswordSafe, never to a file that could be committed. `SolrConnection` has no password field:
a secret that is never in the serialized object cannot leak into the serialized file.

# Package org.apache.solr.ide.server.transport

One request to Solr, and what came back, classified into outcomes a caller cannot confuse.

`SolrResponse` has five: success, partial, a Solr error, a transport failure, and an answer that
could not be understood. Nothing here completes exceptionally — a caller that had to catch to find
out what happened would eventually catch too much. **Partial** is the one that does not look like an
outcome: a response can arrive with HTTP 200 and `responseHeader.status` 0 and still be incomplete,
saying so only through a `partialResults` flag.

Mostly the plugin's own traffic — a schema fetch, a cluster status. The exception is `postForm`, for
the query console, which returns the answer as the server sent it (`SolrRawAnswer`) for the caller
to show and to classify. Asynchronous, with a timeout a caller may lengthen for one request, on the
JDK's `HttpClient` with no proxy and no SSL context set, so the ones the IDE configured are what
reach the wire.

# Package org.apache.solr.ide.server.reading

Turning what a Solr server returns into facts, and asking a connection for them.

`SolrServerSchemaReader` produces `SolrConfigsetFacts`, the same type the configset parser produces,
under the same contract — text in, facts out, nothing from IntelliJ in the signature — so it is
tested against captured response bodies, and `SolrFieldModel.of(repository, server)` merges two
instances of one shape. `SolrLukeReader` deliberately does **not**: what an index holds
(`SolrIndexContents`) is a third answer, and a field a dynamic pattern created at index time appears
in no configset.

`SolrTopologyReader` answers what a server holds, in whichever vocabulary it uses: collections and
shards on SolrCloud, cores on a standalone server. Which endpoint is asked is decided from the mode
the server reports rather than by trying one — a standalone Solr answers the whole Collections API
with a 400.

`SolrServerReader` is where the pieces compose: it resolves a connection's stored secret at the
point of use, asks the transport, and maps the answer into facts. It is the only place a credential
is read.

# Package org.apache.solr.ide.server.topology

The collections tool window: a server's collections, shards and cores, their health, and the
queries its right-click menu offers. `SolrTopologyNodes` and `SolrCollectionsView` decide what the
tree shows and `SolrTreeQueries` what each query opens, all pure; the Swing is thin.

# Package org.apache.solr.ide.server.query

Solr queries in the IDE's HTTP Client: the starting requests in *Add Request*, field completion
inside an `.http` request body, and a readable summary above a query's raw answer.

A contribution to somebody else's editor rather than an editor of its own. Field completion reads
**the collection the request line names, on the selected connection**, and contacts the server only
on an explicit <kbd>Ctrl-Space</kbd>; the read is remembered in `SolrCollectionFields` until the
connection list changes or the tool window refreshes. Whether a response is Solr's is decided by its
body, never by its URL (`SolrQueryResultReader`). `SolrQueryRunner` runs the query a code-surface
gutter icon names.

# Package org.apache.solr.ide.server.query.screen

The query console: Solr Admin's query form in an editor tab, run by the plugin's own transport. The
form is read from a definition transcribed from Solr's query page (`SolrQueryScreenSpec`); what it
holds decides what is sent (`SolrQueryScreenState`), which becomes a form POST or a refusal
(`SolrQueryScreenRequest`); and an answer is classified for display (`SolrQueryScreenAnswer`). All
four are pure. The tab, its form and its results are internal.

Beside the HTTP Client rather than instead of it: a query is worked on here and kept in an `.http`
file, and *Open as .http* hands one across.

# Package org.apache.solr.ide.server.drift

Comparing a configset against a collection, and the writes that close a gap: uploading the
configset, reloading the collection, and the additive Schema API changes the plugin will send.

`SolrDrift` compares two halves passed separately, never a model, and resolves factory spellings
before comparing. `SolrSchemaApi` maps each difference onto the Schema API request it would need,
and says which ones the plugin will send. `SolrConfigsetWriter` never reports a write from its own
answer; the view re-reads. Upload is SolrCloud-only, and the writer checks the mode before asking;
the Schema API changes work against a standalone server too.

# Package org.apache.solr.ide.server.indexing

A test document written for one named collection and sent there.

The document is edited in a real editor over an in-memory JSON file that carries the collection's
schema, which is how completion inside it offers that collection's fields. `SolrDocumentValidation`
refuses the two mistakes Solr answers `status: 0` to and then acts on: a field the schema cannot
place, which it would add to the deployed schema, and a missing unique key, which it would replace
with a generated one.
