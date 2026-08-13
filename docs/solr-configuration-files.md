# Solr Configuration Files, and What This Plugin Covers

This document maps the configuration surface of an Apache Solr deployment: which
files exist, what goes in each, whether they are hand-edited or written by an
API, and — in the final section — which of them this plugin targets and which it
deliberately leaves alone.

It exists because the plugin's value proposition depends on an empirical claim:
that a meaningful amount of Solr configuration is still authored as XML by hand.
That claim deserves to be laid out file by file rather than asserted. The
[specification](../specs/0002-solr-intellij-plugin.md) argues the case; this
document is the evidence behind it.

**A note on versions.** Solr's configuration surface has moved over the years —
files have been renamed, components removed, and features relocated into
optional modules. This document describes the Solr lines that are **not
end-of-life**, which is also the plugin's version-support policy: **Solr 10.x**
(10.0.0, released March 2026 — the current major) and **Solr 9.10.x** (9.10.1 —
the previous major, receiving critical fixes only). Solr 9.9 and earlier, and
all of 8.x, are EOL. Where 9.10 and 10 differ, the difference is called out
inline. The Solr Reference Guide for your specific version is the authority;
this is a map, not a spec.

**A note on deployment modes.** Solr runs either as a ZooKeeper-coordinated
**SolrCloud** cluster or in **user-managed** mode (called *standalone* before
Solr 9 renamed it). Solr 10 changed `bin/solr start` to default to SolrCloud;
user-managed mode is neither deprecated nor removed, and `--user-managed`
selects it. In 9.x, user-managed is still the default. Configset files are
identical in both modes — the difference is where the authoritative copy lives
and how it is deployed, not what it contains.

---

## 1. Where configuration lives

Three tiers, and confusing them is the source of most "why isn't my change
taking effect" questions.

| Tier | Scope | Where it lives (standalone) | Where it lives (SolrCloud) |
|---|---|---|---|
| **Server** | The Jetty process | `server/etc/`, `server/resources/` | Same — on each node's disk |
| **Node** | One Solr node | `$SOLR_HOME/solr.xml` | `$SOLR_HOME/solr.xml` — in 9.x it could also be loaded from `/solr.xml` in ZooKeeper; **Solr 10 removed that** |
| **Configset** | One collection or core | `$SOLR_HOME/<core>/conf/` | **ZooKeeper**, under `/configs/<configset-name>` |

The tier that matters for this plugin is the third. A **configset** is a
directory of files — `solrconfig.xml`, a schema, and their supporting
resources — that defines how one collection indexes and queries documents.

In SolrCloud the authoritative copy of a configset lives in ZooKeeper, not on
disk. The directory in your project is a *source* copy that gets uploaded, by
`bin/solr zk upconfig`, the Configset API, a CI job, or an init container. This
matters for tooling: the IDE edits the source copy, and something else has to
push it. It also means the round trip is real — `bin/solr zk downconfig` pulls
back whatever is actually running, including anything an API wrote.

---

## 2. The configset files

### 2.1 `solrconfig.xml` — required

The runtime configuration of a core: how it writes to the index, what it caches,
what endpoints it exposes, and what those endpoints do by default. It is the
larger and more intricate of the two main files, and the one most likely to be
tuned repeatedly over a project's life.

| Section | What it configures |
|---|---|
| `<luceneMatchVersion>` | Back-compat behavior target for Lucene components |
| `<lib>` | Extra JARs on the core's classpath — **removed in Solr 10**; use modules and the package manager instead |
| `<dataDir>`, `<directoryFactory>` | Where and how index files are stored |
| `<codecFactory>`, `<indexReaderFactory>` | Low-level Lucene codec and reader selection |
| `<schemaFactory>` | **Classic vs managed schema, and whether the schema is mutable** — see §4 |
| `<indexConfig>` | Merge policy, `ramBufferSizeMB`, lock type, merge scheduler, `infoStream` |
| `<updateHandler>` | `<autoCommit>` / `<autoSoftCommit>` (hard and soft commit cadence), `<updateLog>` (the transaction log — required for SolrCloud, NRT, real-time get, and atomic updates) |
| `<query>` | `filterCache`, `queryResultCache`, `documentCache`, `fieldValueCache`, `enableLazyFieldLoading`, `queryResultWindowSize`, `useColdSearcher`, `maxWarmingSearchers`, and `<listener>` warm-up queries for `newSearcher`/`firstSearcher` |
| `<circuitBreaker>` | CPU and memory load shedding — the legacy `CircuitBreakerManager` form was removed in Solr 10 |
| `<requestDispatcher>` | HTTP caching, multipart/formdata upload limits, remote streaming toggles (`addHttpRequestToContext` was removed in Solr 10) |
| `<requestHandler>` | The endpoints — `/select`, `/query`, `/update` and its variants, `/get`, `/spell`, `/suggest`, `/terms`, `/export`, `/stream`, `/sql`, `/analysis/*`, `/replication`, `/admin/ping`, `/config`, `/schema` — each with `<lst name="defaults">`, `appends`, and `invariants` |
| `<searchComponent>` | Query, facet, highlight, spellcheck, suggest, terms, elevator, more-like-this, stats, expand, debug |
| `<initParams>` | Parameters applied across handlers by path glob — the modern place to set `df` once |
| `<updateRequestProcessorChain>`, `<updateProcessor>` | Ingest-time document transformation: UUID generation, dedupe, language ID, tolerant updates, and `add-unknown-fields-to-the-schema` (the schemaless mechanism) |
| `<queryResponseWriter>` | Response formats — json, xml, csv, javabin, geojson, smile. Solr 10 removed the `python`, `ruby`, `php` and XLSX writers |
| `<queryParser>`, `<valueSourceParser>`, `<transformer>` | Custom plugin registration |

Two properties make this file the plugin's strongest target:

- **Relevance configuration lives here.** The dismax/edismax `qf`, `pf`, `bf`,
  `boost` and `mm` parameters that determine search quality are `<str>` elements
  inside a request handler's `defaults`. They name schema fields as bare
  strings, with no validation of any kind. A typo degrades relevance silently.
- **It is iterated on continuously.** Cache sizes, commit cadence, and handler
  defaults get tuned across a project's entire life, long after the schema has
  stabilized.

### 2.2 `managed-schema.xml` / `schema.xml` — required (one of them)

The index schema: the fields a document may have, how their values are analyzed
into indexed terms, and which field identifies a document.

| Element | What it declares |
|---|---|
| `<field>` | A named field: its `type`, and flags — `indexed`, `stored`, `docValues`, `multiValued`, `required`, `default`, `omitNorms`, `termVectors`, `useDocValuesAsStored`, `sortMissingLast` |
| `<dynamicField>` | A glob (`*_s`, `*_txt`) matching any field whose name fits the pattern |
| `<copyField>` | Duplicates values from `source` into `dest` at index time — the mechanism behind catch-all search fields and multi-field patterns |
| `<fieldType>` | A named analysis and storage strategy: a Lucene class, plus optional `<analyzer type="index">` and `<analyzer type="query">` chains of `<charFilter>` → `<tokenizer>` → `<filter>` |
| `<uniqueKey>` | The field identifying a document — required for updates, deletes, and SolrCloud routing |
| `<similarity>` | Scoring model, globally or per field type (BM25 by default) |

The `<fieldType>` analyzer chains are the conceptually hardest part of a Solr
configuration and the part with the least feedback. Whether a field supports
exact matching, case-insensitive matching, prefix matching, or phrase queries is
an *emergent property* of a chain of a dozen filter classes — and getting it
wrong produces no error, just queries that quietly do not match.

**On the filename.** The classic file is `schema.xml`. The managed file was
historically extensionless (`managed-schema`) and current Solr versions use
`managed-schema.xml` — the name is set by `managedSchemaResourceName` and is a
convention, not a mechanism. Tooling has to handle all three names, and the
extensionless variant is a genuine trap: IDEs key file type off the extension,
so `managed-schema` is treated as plain text and gets no XML support at all
unless a plugin explicitly registers the filename.

### 2.3 Supporting configset XML

| File | Purpose | Referenced from |
|---|---|---|
| `elevate.xml` | Forces specific documents to the top for specific queries (Query Elevation Component) | `solrconfig.xml` searchComponent config |
| `currency.xml` | Exchange rates for `CurrencyFieldType` | The field type's `currencyConfig` attribute |
| `enumsConfig.xml` | Ordered enumerations for `EnumFieldType` (e.g. severity levels that must sort non-alphabetically) | The field type's `enumsConfig` + `enumName` |

All three are hand-edited, all three are small, and all three are referenced *by
name* from another configset file — which makes them a natural target for
reference resolution even though they are not the headline files.

### 2.4 Non-XML files in the same directory

A configset is not purely XML, and the non-XML files are tightly coupled to the
XML around them.

| File | Format | Role |
|---|---|---|
| `synonyms.txt`, `stopwords.txt`, `protwords.txt`, `stemdict.txt` | Line-oriented text | Referenced by filename from analyzer chain filters (`<filter class="solr.SynonymGraphFilterFactory" synonyms="synonyms.txt"/>`) |
| `lang/*.txt` | Text | Per-language stopword and stemming resources |
| `mapping-*.txt` | Text | Character mappings for `MappingCharFilterFactory` |
| `configoverlay.json` | JSON | **Written by the Config API** — overlays `solrconfig.xml` (see §4) |
| `params.json` | JSON | **Written by the Request Parameters API** — named parameter sets, referenced by handlers via `useParams` |
| `_schema_analysis_synonyms_*.json`, `_schema_analysis_stopwords_*.json` | JSON | **Written by the Managed Resources API** — API-editable equivalents of the `.txt` files above |
| `core.properties` | Java properties | Marks a directory as a core; holds core name and basic settings |
| `solrcore.properties` | Java properties | Property substitution values for `solrconfig.xml` |

The `.txt` resources deserve attention: an analyzer chain names them as string
attributes, and a filter pointing at a missing `synonyms.txt` fails at core
load. That is exactly the dangling-reference class of error the plugin already
targets between XML files.

### 2.5 Legacy and removed

Old configsets in the wild still contain these; recent Solr versions do not ship
or support them.

| File | Status |
|---|---|
| `data-config.xml` / DIH config | DataImportHandler was deprecated and then removed from the Solr distribution; it continues as a community package |
| `velocity/*.vm` | The Velocity response writer and `/browse` UI were removed |
| `xslt/*.xsl` | The XSLT response writer was deprecated and relocated to an optional module |
| `<defaultSearchField>`, `<solrQueryParser defaultOperator>` in the schema | Removed — replaced by `df` and `q.op` request parameters |
| `CurrencyField`, `EnumField`, `ExternalFileField` field types | Removed in Solr 10 — `CurrencyFieldType` and `EnumFieldType` are the surviving forms; `ExternalFileField` has no replacement |
| `<lib>` directives | Removed in Solr 10 — replaced by modules and the package manager |
| `python`, `ruby`, `php` and XLSX response writers | Removed in Solr 10 |
| `BlobHandler` and the `.system` collection | Removed in Solr 10 in favor of the FileStore API |

Recognizing these and explaining what replaced them is a legitimate tooling
opportunity, and one the current specification does not claim.

---

## 3. Node and server XML

These are XML, they are hand-edited, and they are *not* part of a configset.

### `solr.xml` — node configuration

One per node (or one in ZooKeeper for the whole cluster). Configures the node
itself rather than any collection:

- `<solrcloud>` — `zkHost`, `host`, `hostPort`, `zkClientTimeout`, distributed
  update timeouts, ZooKeeper credential and ACL providers
- `<shardHandlerFactory>` — inter-node HTTP timeouts and connection pooling
- `<metrics>` — metrics reporters
- `<logging>` — the log watcher backing the Admin UI logging screen
- `<backup>` — named backup repository definitions
- Node-level settings: `coreRootDirectory`, `sharedLib`, `allowPaths`,
  `allowUrls`, `maxBooleanClauses`

There is **no API that edits `solr.xml`**. It is hand-authored XML, universally,
in every deployment topology. It is also comparatively small and stable — edited
at setup and then rarely.

### `log4j2.xml` — logging

Ships in `server/resources/`. Standard Log4j2 configuration: appenders, rolling
policies, per-package levels. Hand-edited. Log levels can be changed at runtime
via the Admin UI or the logging API, but those changes are transient and do not
write back to the file.

### `server/etc/jetty*.xml`, `webdefault.xml`, `web.xml`

Jetty container configuration — connectors, TLS, thread pools, request logging.
Hand-edited, and usually only when configuring TLS or tuning the HTTP layer.

---

## 4. Hand-edited versus API-managed

This is the crux. Solr offers several write APIs, and each covers a specific
slice of the configuration surface — with the rest left to file editing.

### 4.1 The APIs

| API | Endpoint | What it writes | What it can change |
|---|---|---|---|
| **Schema API** | `/solr/<coll>/schema` | Rewrites the managed schema file | Add/delete/replace fields, dynamic fields, copy fields, field types |
| **Config API** | `/solr/<coll>/config` | `configoverlay.json` | Registers and modifies plugins — request handlers, search components, response writers, update processors, listeners, initParams — plus an allow-list of tunable properties (cache sizes, autoCommit settings, request-parser limits) and arbitrary user properties |
| **Request Parameters API** | `/solr/<coll>/config/params` | `params.json` | Named parameter sets referenced by handlers via `useParams` |
| **Managed Resources API** | `/solr/<coll>/schema/analysis/{stopwords,synonyms}/<name>` | `_schema_analysis_*.json` | Stopword and synonym lists, for analyzer chains configured to use managed resources |
| **Configset API** | `/solr/admin/configs` | Whole configsets in ZooKeeper | Upload, create-from-existing, delete — file-level, not element-level |
| **Security API** | `/solr/admin/authentication`, `/authorization` | `security.json` in ZooKeeper | Auth plugins, users, permission rules |

### 4.2 The Schema API and schema mutability

The Schema API only works if `solrconfig.xml` declares a *mutable managed
schema*:

```xml
<schemaFactory class="ManagedIndexSchemaFactory">
  <bool name="mutable">true</bool>
  <str name="managedSchemaResourceName">managed-schema.xml</str>
</schemaFactory>
```

Four configurations, four different realities:

| `<schemaFactory>` | Schema API | Hand editing |
|---|---|---|
| `ClassicIndexSchemaFactory` | Rejected | The only option |
| `ManagedIndexSchemaFactory`, `mutable="false"` | Rejected | The only option |
| `ManagedIndexSchemaFactory`, `mutable="true"` | Works | Possible, but Solr owns the file |
| **Absent** | Works | **Solr defaults to `ManagedIndexSchemaFactory`** |

That last row is a trap worth stating plainly: *no `<schemaFactory>` means
managed*, not classic. And if such a configset contains a `schema.xml` with no
managed schema file beside it, Solr converts on first load — it renames your
file to `schema.xml.bak`, writes the content out as `managed-schema.xml`, and
takes ownership. A file named `schema.xml` is therefore not by itself evidence
of a classic schema; it may simply be a configset that has not been loaded yet.

Converting back is manual and supported: rename the file, declare
`ClassicIndexSchemaFactory`, reload the core.

That last row is where the "do not edit by hand" banner comes from, and the
warning is real: Solr rewrites the file on any API call and does not preserve
your comments or formatting. An edit made in the IDE and not reloaded can be
silently overwritten.

But `mutable="false"` and `ClassicIndexSchemaFactory` are not exotic. Pinning
the schema is what makes a deployment reproducible — the configset lives in Git,
gets code-reviewed, and is uploaded by CI. Teams that care about *knowing* what
schema is running choose this deliberately.

### 4.3 What no API reaches

The Config API is more capable than it is usually given credit for — it can
define request handlers and search components outright. But it cannot express:

- `<lib>` directives and `<luceneMatchVersion>`
- `<directoryFactory>`, `<codecFactory>`, `<indexReaderFactory>`
- `<schemaFactory>` itself
- `<indexConfig>` — merge policy, buffer sizes, merge scheduling
- `<updateLog>` configuration
- Classic `<updateRequestProcessorChain>` definitions
- `<circuitBreaker>` configuration
- Anything at all in `solr.xml`, `log4j2.xml`, or the Jetty files
- `elevate.xml`, `currency.xml`, `enumsConfig.xml`

And where the Config API *does* apply, its output lands in `configoverlay.json`
as JSON. The XML you read in your editor is no longer the whole truth, which
makes the overlay a frequent source of "the config says X but Solr is doing Y."

### 4.4 Summary

| File | Hand-edited | API-writable | Notes |
|---|---|---|---|
| `solrconfig.xml` | **Yes — always** | Partially, via overlay | The API never writes this file; it writes a JSON overlay beside it |
| `managed-schema.xml` (mutable) | Possible, discouraged | **Yes** | Solr owns and rewrites the file |
| `managed-schema.xml` (`mutable="false"`) | **Yes — only option** | No | |
| `schema.xml` (classic) | **Yes — only option** | No | |
| `solr.xml` | **Yes — only option** | No | No API exists |
| `log4j2.xml` | **Yes — only option** | No | Runtime level changes are transient |
| `jetty*.xml`, `web.xml` | **Yes — only option** | No | |
| `elevate.xml` | **Yes** | No | |
| `currency.xml`, `enumsConfig.xml` | **Yes** | No | |
| `synonyms.txt`, `stopwords.txt` | **Yes** | Only if configured as managed resources | |
| `configoverlay.json`, `params.json` | Discouraged | **Yes** | API-owned by design |
| `security.json` | Possible (upload to ZK) | **Yes** | |

**The pattern:** the Schema API displaces hand editing for *one file*, in *one
of its three configurations*. Everything else in the list is hand-authored XML
in every deployment.

---

## 5. What this plugin covers

Mapped to the requirements in the
[specification](../specs/0002-solr-intellij-plugin.md). Phase 1 is pure static
analysis — no Solr connection, no network.

### 5.1 Scope, per file

**What is built and in what order is the
[plan's](../specs/plans/0002-solr-intellij-plugin-plan.md) to say, and this table
does not repeat it.** Scope does not change from step to step and position does,
so a copy of the status here would be wrong within a release while the shape
below stays correct.

| File | Scope |
|---|---|
| `managed-schema.xml` / `managed-schema` / `schema.xml` | **Full.** Completion for field types, factory classes and their valid attributes; structural validation; navigation and Find Usages across `copyField` and `field type=`; rename refactoring; inspections for dangling references and unused field types; match-capability hints and quick-fixes derived from analyzer chains; Ctrl-Q documentation on factories and attributes |
| `solrconfig.xml` | **The parameters that name schema fields, in both directions.** Handler parameters (`qf`, `df`, `fl`, `sort`, facet, highlight and grouping fields) resolve to schema fields and offer them in completion; hovering one shows the field it names. Inspections flag a parameter naming a field the schema does not declare, a query field that cannot be searched, and a facet or sort naming a field that cannot serve it. **The plugin classes and the request parameters are modelled too**: a `class` attribute completes, explains itself and navigates to the class, and a parameter name inside `defaults`, `appends` or `invariants` completes and explains itself. The file's own *elements and attributes* are not yet modelled |

The asymmetry is deliberate. In `solrconfig.xml` the plugin targets the
*cross-file boundary* — the string parameters that name schema fields — because
that boundary is where silent failures cluster. **Both directions of that boundary
are now covered**: the same list that lets an inspection say `descriptoin` is not a
field is the list that offers `description` before it is mistyped, and a field's
capabilities decide both what a parameter may name and what completion offers there.

Full language support for the *structure* of `solrconfig.xml` — its elements and their
attributes — remains a later concern. Navigation from a `class` attribute is no longer
part of that remainder; it ships. What the file's legal elements are is settled for the
plugin elements: Solr declares 23 of them in `SolrConfig.plugins`, and the generator
already reads that list for the class catalog. What is not yet built is the completion
that would offer them, and `<config>`, `luceneMatchVersion` and `dataDir` are in no
generated source at all.

**Phase 1 is deployment-mode agnostic.** Configset files are identical in
SolrCloud and user-managed mode, and Phase 1 never contacts a server, so the
distinction does not arise. The only mode-flavored content inside a configset is
`<updateLog>` (required for SolrCloud), the `/replication` handler
(leader-follower replication, user-managed only), and the `<solrcloud>` section
of `solr.xml` — none of which changes how completion, references, rename or
inspections behave. Scoping Phase 1 to one mode would cost users and save no
implementation work.

### 5.2 Behavior against a managed schema — the file is edited, always

**The plugin does not classify schema files, and no write is ever refused.** A
rename, a quick-fix and an intention all edit the file in front of the user,
whether the schema is classic, pinned, or opens with Solr's *do not edit it by
hand* banner.

An earlier version of this document described the opposite: a classification
read out of `<schemaFactory>`, cached, with five fallback cases, and write
gating built on top of it that answered a managed schema by putting a `curl`
command carrying a placeholder URL on the clipboard instead of applying the
edit. That existed because the plugin was offline, and a fake URL was the only
alternative it could offer. It is deleted. The question it answered — *am I
allowed to write here* — is not one worth asking.

**Reading was never affected either way.** Completion, navigation, Find Usages,
inspections, match-capability hints and documentation behave identically for
every schema. The file is still what sits in ZooKeeper, still what appears in
the pull request, and still what someone opens when a query returns nothing.

**What replaces the gate is disagreement, shown rather than prevented — and only
the deletion is Phase 1.** Files are edited directly now; Phase 1 never contacts a
server, so it neither compares against one nor applies anything to it. The
comparison this argument leans on arrives with the connected surfaces in §5.3,
where Phase 4 closes the file-versus-ZooKeeper gap. Drift between Git and
ZooKeeper was the actual risk the gating was reaching for — but refusing writes in
the meantime would not have prevented that drift, only hidden the edit that caused it.

The [specification](../specs/0002-solr-intellij-plugin.md) argues this out under
"What this replaces".

**The stance stops at the Schema API.** It does not extend to `solrconfig.xml`
and the Config API. The Config API's coverage is partial, and what it does cover
it writes to `configoverlay.json` rather than to the XML — moving configuration
out of the file you read and into a JSON overlay beside it. That is a trade-off,
not a clear improvement, so `solrconfig.xml` and every supporting configset file
in §2.3 remain hand-edited by design.

### 5.3 Later phases — scoped, not committed

| Capability | Phase |
|---|---|
| Live connections, query console, schema-aware document editing and indexing | 2 |
| Field-name validation inside SolrJ query strings in Java/Kotlin | 3 |
| Collection explorer, configset upload to ZooKeeper, deployed-vs-repo config diff, classification of schema edits as safe-live / requires-reload / requires-reindex, analysis-chain debugger | 4 |

Deployment mode starts to matter from Phase 2 and matters most in Phase 4.
Connections are cheap to support both ways — SolrJ abstracts the client — so
Phase 2 covers both. Phase 4 operations do not abstract: "upload configset to
ZooKeeper and reload the collection" is inherently SolrCloud, while the
user-managed equivalent is a file copy plus a CoreAdmin `RELOAD`, with deployed
config read back through the `/admin/file` handler rather than from ZooKeeper.
Phase 4 should therefore be **SolrCloud-first**, with user-managed support
treated as a follow-on rather than implied parity.

Phase 4 is where the file-versus-ZooKeeper gap from §1 gets closed properly: the
deployed-vs-repo diff is what tells you whether an API write has drifted from
the configset in your project.

### 5.4 Not covered

| File | Why not |
|---|---|
| `solr.xml` | Node-level, not a configset. Hand-edited XML with no tooling — a real gap, but outside the configset scope Phase 1 draws. See §6 |
| `log4j2.xml`, `jetty*.xml`, `web.xml` | Not Solr-specific schemas; standard tooling and XSD-based validation already apply |
| `configoverlay.json`, `params.json`, `security.json` | JSON, API-owned. Reading them to explain config drift is a plausible Phase 4 feature, not a Phase 1 one |
| `core.properties`, `solrcore.properties` | Properties files with minimal structure |
| `data-config.xml` (DIH), Velocity templates, XSLT | Removed from modern Solr |
| Bulk and production ingestion pipelines | Explicit non-goal — Phase 2 indexing is scoped to development workflows |

---

## 6. Open scope questions

Two gaps this survey surfaces that the specification does not currently answer.

**`solr.xml`.** It is hand-edited XML, it has no API, and it has no tooling
anywhere — squarely in the plugin's stated sweet spot. It is excluded only
because Phase 1 draws its boundary at "configset," which is a scoping decision
rather than a value judgment. It is also small and infrequently edited, which
argues the other way.

**Resource file references.** Analyzer chains name `.txt` resources as string
attributes (`synonyms="synonyms.txt"`), and a filter pointing at a missing file
fails at core load. That is the same dangling-reference class of error the
handler-parameter inspections already target between XML files, and the same
reference infrastructure would resolve it. The three supporting XML files in
§2.3 are referenced the same way. Neither is in scope today.

---

## References

- Apache Solr Reference Guide — the authority for any version-specific detail
  here: <https://solr.apache.org/guide/>
- Plugin specification:
  [`specs/0002-solr-intellij-plugin.md`](../specs/0002-solr-intellij-plugin.md)
- Implementation plan:
  [`specs/plans/0002-solr-intellij-plugin-plan.md`](../specs/plans/0002-solr-intellij-plugin-plan.md)
