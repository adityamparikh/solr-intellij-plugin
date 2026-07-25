# solr-intellij-plugin

![Build](https://github.com/adityamparikh/solr-intellij-plugin/workflows/Build/badge.svg)

IDE tooling for Apache Solr development in IntelliJ IDEA.

Solr developers work across three disconnected surfaces: configset XML edited with no language
support, queries iterated in the Admin UI or curl, and client code that references field names as
unchecked string literals. Every boundary between them produces a class of silent runtime failure —
a typo'd field name returns empty results rather than an error, a `copyField` pointing at a removed
field fails only at core reload, a `qf` parameter on an unindexed field degrades relevance with no
warning. This plugin closes those gaps with language intelligence, cross-file navigation, and
inspections.

Solr has no maintained plugin on the JetBrains Marketplace, unlike Elasticsearch and Kafka.

## Status

**Pre-release. Not yet published to the JetBrains Marketplace, and not yet usable for its intended
purpose.**

What exists today is the Phase 1 *activation gate* — the code that decides whether a file belongs to
a Solr configset, which every later feature is built on top of:

- Configset detection by file name (`schema.xml`, `managed-schema`, `managed-schema.xml`,
  `solrconfig.xml`) corroborated by directory heuristics
- A per-project manual override for layouts the heuristics miss
- Registration of the extensionless `managed-schema` as XML, so configsets parse for the PSI
  features to come

The Phase 1 feature set proper — schema completion, cross-file references, rename, inspections,
match-capability analysis, quick documentation — is specified but unbuilt. Treat
[the specification](specs/0002-solr-intellij-plugin.md) as the authority on intent, and this section
as the authority on status.

## Planned scope

The work is structured as five phases; only Phase 1 is committed.

| Phase | Theme | Needs a running Solr |
|---|---|---|
| **1** | Schema and config intelligence — completion, navigation, rename, inspections, match-capability hints | No |
| 2 | Connections and query console; development-oriented indexing | Yes |
| 3 | SolrJ code integration — field validation inside query strings in Java/Kotlin | Yes |
| 4 | Collection explorer and dev-loop operations — configset upload, config diff, analysis debugger | Yes |
| 5 | Ecosystem — MCP routing, additional JetBrains IDEs | Optional |

Phase 1 is deliberately **offline**: pure static analysis of the configset files in your project,
with no Solr connection and no network access.

### Where this fits with the Schema API

Solr's default configset carries a banner reading *"this file is managed by the Schema API, do not
edit it by hand."* The plugin does not work around that guidance — it reinforces it. Where a schema
is managed and mutable, the plugin's default answer to a write is to render the edit as a Schema API
request rather than modify the file. Where a schema is hand-authored (`ClassicIndexSchemaFactory`,
or managed with `mutable="false"`), no API is available and editing the file is correct.

Read-side features — navigation, inspections, hints, documentation — behave identically either way.

[`docs/solr-configuration-files.md`](docs/solr-configuration-files.md) is the full file-by-file
account of which Solr configuration is hand-edited, which is API-written, and what this plugin
covers.

## Supported Solr versions

The plugin supports the Solr release lines that Apache Solr has **not** declared end-of-life —
currently **10.x** and **9.10.x**. When Solr declares a line EOL, the plugin drops it in its next
release. See the version-support policy in the specification.

## Documentation

| Document | Purpose |
|---|---|
| [Specification](specs/0002-solr-intellij-plugin.md) | What the plugin is for, phased scope, and Phase 1 requirements in implementation depth |
| [Implementation plan](specs/plans/0002-solr-intellij-plugin-plan.md) | Ordered steps delivering Phase 1 |
| [Solr configuration files](docs/solr-configuration-files.md) | Which Solr config is hand-edited vs API-written, and what the plugin covers |
| [Plugin development tutorial](docs/modern-intellij-plugin-development.md) | Building this kind of plugin from scratch, using this project as the worked example |
| [CLAUDE.md](CLAUDE.md) | Repository orientation — build gates, architecture, conventions |

## Building from source

Requires **JDK 21 or later** (Solr 10 requires Java 21, which sets the toolchain floor).

```bash
./gradlew build       # compile, test, coverage floor, and documentation gate
./gradlew runIde      # launch a sandbox IDE with the plugin installed
./gradlew buildPlugin # produce the distributable ZIP in build/distributions/
```

The build enforces two gates as part of `check`: an 80% line-coverage floor (Kover) and a
documentation gate that fails on any undocumented public declaration (Dokka). Both are described in
[CLAUDE.md](CLAUDE.md).

## Installing

Not yet available from the JetBrains Marketplace. To try the activation gate, build the plugin from
source and install the ZIP from `build/distributions/` via
<kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk…</kbd>.

## Contributing

Commits use conventional-commit subjects and must carry a sign-off (`git commit -s`). Commit bodies
carry real weight in this repository — they record *why* a constraint exists. See the Conventions
section of [CLAUDE.md](CLAUDE.md).

---

Plugin scaffolding based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
