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

What exists today is the *activation gate* — the code that decides whether a file belongs to a Solr
configset, which every feature is built on top of:

- Activation gated on the project depending on a Solr client (`solr-solrj` or a wrapper that carries
  it — Spring Data Solr, Camel, the Quarkus extensions). Matched by artifact id, so any version
  counts. Outside such a project the plugin stays silent
- Configset detection by file name (`schema.xml`, `managed-schema`, `managed-schema.xml`,
  `solrconfig.xml`, `params.json`, `elevate.xml`, `currency.xml`, `enumsConfig.xml`) within a
  project that passed that gate
- Resolution of a file to the configset that owns it, so a project holding several keeps them
  apart — cached, since this runs every time you open a file
- Recognition of analyzer resources (`stopwords.txt`, `synonyms.txt`, `protwords.txt`, `lang/`)
  from inside a known configset only; those names are too common to activate anything on their own
- A per-project manual override, which is also how a repository of configsets with no build file —
  and so no dependencies to detect — switches the plugin on
- A per-user list of Solr connections, with credentials in the IDE's password store — storage only,
  nothing talks to a server yet
- Registration of the extensionless `managed-schema` as XML, so configsets parse for the PSI
  features to come

Everything else — configuration intelligence, the server connection, and the Java/Kotlin support —
is specified but unbuilt. Treat [the specification](specs/0002-solr-intellij-plugin.md) as the
authority on intent, and this section as the authority on status.

## Planned scope

Three surfaces, connected by one shared model of what fields exist and what they can do —
and the comparison that only exists because two of them are connected.

| Surface | What it does | Needs a running Solr |
|---|---|---|
| **Configuration** | Navigation, Find Usages, inspections, match-capability hints and quick-fixes, completion, rename | No |
| **Server** | Browse collections, query console with structured results, index test documents, upload configsets and reload collections | Yes |
| **Code** | Field names in SolrJ usage checked and completed, query syntax inside string literals, run a query from a gutter icon | No, better with one |
| **Repo vs. server** | Show where your configuration and the deployed server disagree | Both |

No feature requires an input it might not have. A repository of bare XML gets the configuration
half. An application with no configset in it gets the server and code halves. A project with both
gets everything, plus the comparison neither half could produce alone.

### Where this fits with the Schema API

Solr's default configset carries a banner reading *"this file is managed by the Schema API, do not
edit it by hand."*

The plugin edits the file anyway, because it is a source file in your repository and editing source
files is what an IDE does. What the banner is really warning about is **drift** — your repository
and the running server quietly diverging — and the plugin addresses that directly: connect one, and
it shows you exactly where the two disagree. Where a change maps onto the Schema API, applying it to
the server is an action you invoke from that comparison.

[`docs/solr-configuration-files.md`](docs/solr-configuration-files.md) is the full file-by-file
account of which Solr configuration is hand-edited, which is API-written, and what this plugin
covers.

## Supported Solr versions

The plugin supports the Solr release lines that Apache Solr has **not** declared end-of-life; when
Solr declares a line EOL, the plugin drops it in its next release. The
[specification](specs/0002-solr-intellij-plugin.md) names the current lines, and is the one place
they are written down — a compatibility matrix ships with the first release.

## Documentation

| Document | Purpose |
|---|---|
| [Specification](specs/0002-solr-intellij-plugin.md) | What the plugin is for, how it is structured, and what it does |
| [Implementation plan](specs/plans/0002-solr-intellij-plugin-plan.md) | Ordered steps, and which are done |
| [Demo runbook](docs/demo/README.md) | End-to-end acceptance criteria in user terms, doubling as a talk runbook |
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
