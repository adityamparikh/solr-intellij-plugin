# Solr Configuration Files, and What This Plugin Covers

The configuration surface of an Apache Solr deployment: which files exist, what goes in each, and
whether they are hand-edited or written by an API. The
[specification](../specs/0002-solr-intellij-plugin.md) argues why that matters for an IDE plugin;
the [last section](#what-the-plugin-reads) says which files this plugin reads.

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
selects it. In 9.x, user-managed is still the default. [Configset](glossary.md#configset) files are
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
| **Configset** | One [collection](glossary.md#collection) or [core](glossary.md#core) | `$SOLR_HOME/<core>/conf/` | **ZooKeeper**, under `/configs/<configset-name>` |

The tier that matters for this plugin is the third. A **configset** is a
directory of files — [`solrconfig.xml`](glossary.md#solrconfigxml), a schema, and their supporting
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
| [`<luceneMatchVersion>`](glossary.md#lucenematchversion) | Back-compat behavior target for Lucene components |
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
  inside a [request handler](glossary.md#request-handler)'s `defaults`. They name schema
  [fields](glossary.md#field) as bare strings, with no validation of any kind. A typo degrades
  relevance silently.
- **It is iterated on continuously.** Cache sizes, commit cadence, and handler
  defaults get tuned across a project's entire life, long after the schema has
  stabilized.

### 2.2 `managed-schema.xml` / `schema.xml` — required (one of them)

The index schema: the fields a document may have, how their values are analyzed
into indexed terms, and which field identifies a document.

| Element | What it declares |
|---|---|
| `<field>` | A named field: its `type`, and flags — [`indexed`](glossary.md#indexed), [`stored`](glossary.md#stored), [`docValues`](glossary.md#docvalues), [`multiValued`](glossary.md#multivalued), `required`, `default`, [`omitNorms`](glossary.md#omitnorms), `termVectors`, `useDocValuesAsStored`, `sortMissingLast` |
| [`<dynamicField>`](glossary.md#dynamic-field) | A glob (`*_s`, `*_txt`) matching any field whose name fits the pattern |
| [`<copyField>`](glossary.md#copyfield) | Duplicates values from `source` into `dest` at index time — the mechanism behind catch-all search fields and multi-field patterns |
| [`<fieldType>`](glossary.md#field-type) | A named analysis and storage strategy: a Lucene class, plus optional `<analyzer type="index">` and `<analyzer type="query">` [chains](glossary.md#analyzer-chain) of [`<charFilter>`](glossary.md#char-filter) → [`<tokenizer>`](glossary.md#tokenizer) → [`<filter>`](glossary.md#filter) |
| [`<uniqueKey>`](glossary.md#uniquekey) | The field identifying a document — required for updates, deletes, and SolrCloud routing |
| `<similarity>` | Scoring model, globally or per field type (BM25 by default) |

The `<fieldType>` analyzer chains are the conceptually hardest part of a Solr
configuration and the part with the least feedback. Whether a field supports
exact matching, case-insensitive matching, prefix matching, or phrase queries is
an *emergent property* of a chain of a dozen filter classes — and getting it
wrong produces no error, just queries that quietly do not match.

**On the filename.** The classic file is `schema.xml`. The managed file was
historically extensionless ([`managed-schema`](glossary.md#managed-schema)) and current Solr versions use
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
name* from another configset file.

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
load.

### 2.5 Legacy and removed

Old configsets in the wild still contain these; recent Solr versions do not ship
or support them.

| File | Status |
|---|---|
| `data-config.xml` / DIH config | DataImportHandler was deprecated and then removed from the Solr distribution; it continues as a community package |
| `velocity/*.vm` | The Velocity response writer and `/browse` UI were removed |
| `xslt/*.xsl` | The XSLT response writer was deprecated and relocated to an optional module |
| `<defaultSearchField>`, `<solrQueryParser defaultOperator>` in the schema | Removed — replaced by `df` and `q.op` [request parameters](glossary.md#request-parameter) |
| `CurrencyField`, `EnumField`, `ExternalFileField` field types | Removed in Solr 10 — `CurrencyFieldType` and `EnumFieldType` are the surviving forms; `ExternalFileField` has no replacement |
| `<lib>` directives | Removed in Solr 10 — replaced by modules and the package manager |
| `python`, `ruby`, `php` and XLSX response writers | Removed in Solr 10 |
| `BlobHandler` and the `.system` collection | Removed in Solr 10 in favor of the FileStore API |

The retired `solrconfig.xml` elements are a different list — `indexDefaults`,
`mainIndex`, `jmx`, `nrtMode`, `unlockOnStartup` — and Solr refuses most of them
at core load; the [inspection catalog](inspection-catalog.md#element-solr-no-longer-accepts--solrdiscontinuedelement)
covers them.

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

## What the plugin reads

Scope is the [specification's](../specs/0002-solr-intellij-plugin.md) to set and status is the
[plan's](../specs/plans/0002-solr-intellij-plugin-plan.md); this is the shape, per file. The editor
features never contact a server, so they behave the same in SolrCloud and user-managed mode, and for
every schema configuration in §4 — the plugin edits the file in front of the user and never refuses
a write.

| File | What the plugin does with it |
|---|---|
| `managed-schema.xml` / `managed-schema` / `schema.xml` | Element, attribute and value completion; references, Find Usages and rename across `field type=` and `copyField`; navigation from a filter's resource attribute to its `.txt` file; inspections; match-capability hints; companion-field intentions; quick documentation |
| `solrconfig.xml` | Element and attribute completion from Solr's own vocabulary; parameter-name, `defType` and field-name completion; handler parameters resolve to schema fields; `class` attributes navigate; inspections for unknown, unsearchable and unservable fields, retired elements and misspelled parameters; quick documentation |
| `elevate.xml`, `currency.xml`, `enumsConfig.xml`, `params.json` | Recognised as configset files, which activates the plugin for the directory; not otherwise modelled |
| `.txt` resources | Navigable from the schema; recognised inside a known configset; never activate anything. A missing file is not reported |
| A collection on a running server | Compared against the configset, which closes the file-versus-ZooKeeper gap of §1 — see the [user guide](user-guide.md) |
| `solr.xml`, `log4j2.xml`, Jetty files, `core.properties`, other JSON | Not read. `solr.xml` is node-level, not a configset; the rest are not Solr-specific or are API-owned |

---

## References

- Apache Solr Reference Guide — the authority for any version-specific detail
  here: <https://solr.apache.org/guide/>
- Plugin specification:
  [`specs/0002-solr-intellij-plugin.md`](../specs/0002-solr-intellij-plugin.md)
- Implementation plan:
  [`specs/plans/0002-solr-intellij-plugin-plan.md`](../specs/plans/0002-solr-intellij-plugin-plan.md)
