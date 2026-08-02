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

The configuration-files surface is largely built: the plugin detects configsets, parses them into a
field model, and uses it for inspections with quick-fixes, completion, cross-file navigation, quick
documentation, and inline hints saying what each field can actually match. The server surface exists
only as stored connection settings — nothing talks to a Solr server yet — and the Java/Kotlin code
surface is unbuilt.

**The [implementation plan](specs/plans/0002-solr-intellij-plugin-plan.md) is the authority on what
is done**, step by step. The [specification](specs/0002-solr-intellij-plugin.md) describes intent,
much of which is still ahead.

## What it looks like today

Captures come from the sandbox IDE running against `demo/`.
[`docs/screenshots.md`](docs/screenshots.md) says how each one is taken and what invalidates it.

### What each field can actually match, inline

<!-- SCREENSHOT PENDING: hints-match-capability.png — screenshot catalog entry 1.
     Frame managed-schema.xml:47-53 so all seven fields show at once. This is the lead image. -->

> *Screenshot pending.* The hints render beside each field declaration with no hover: `string` fields
> read as whole-value and case-sensitive, `text_general` as tokenized and case-insensitive, and
> `name_prefix` names EdgeNGram as the mechanism rather than claiming "prefix: true".

### Quick documentation on a field

![Quick documentation for the field category: a properties table giving each property's value, where
that value came from, what it accepts, and what it means](docs/images/quick-doc-field.png)

Every property's value **and where it came from** — this field, its type, Solr's default, or Solr's
default *at the schema version this file declares*, since several of them changed with it. That
column is the one the Reference Guide cannot have, because it is about your schema.

<!-- RE-SHOOT: the image above predates the schema-version resolution and crops `uninvertible` off
     the bottom. Screenshot catalog entry 2 has the framing; wait for the field-type-class
     resolution of omitNorms/docValues to land first. -->

### Quick documentation on a class value

![Quick documentation for solr.StandardTokenizerFactory: short name and kind, fully-qualified class
name, one-sentence summary, accepted attributes, and a Reference Guide link](docs/images/quick-doc-class.png)

Read from the Solr artifacts themselves when the plugin was built — never fetched at edit time, and
never copied out of the Reference Guide it links to.

### An inspection catching what fails only at core reload

<!-- SCREENSHOT PENDING: inspection-copyfield-quickfix.png — screenshot catalog entry 4.
     managed-schema.xml:61 ships a deliberate dangling copyField; Alt-Enter on it. -->

> *Screenshot pending.* A `copyField` pointing at a field no longer declared, underlined in the
> editor, with Alt-Enter offering the declared fields closest-spelling-first.

### Completion that knows the schema's vocabulary

<!-- SCREENSHOT PENDING: completion-field-properties.png — screenshot catalog entry 5.
     Also worth a second slot for completion-factory-attributes.png (entry 6). -->

> *Screenshot pending.* Attribute completion inside a `<field>` tag, each property carrying its
> one-line summary, attributes already on the tag omitted, and the value Solr would have used anyway
> marked `(default)`.

Cross-file navigation and Find Usages are not shown here — they are gestures rather than states, and
a still frame of a resolved reference looks like an ordinary editor. [The screenshot
catalog](docs/screenshots.md) carries capture instructions for those too, for anyone assembling a
talk or a marketplace listing.

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
| [Contributing](docs/contributing.md) | Setup, first run, where work comes from, and how a change gets merged |
| [Code organization](docs/code-organization.md) | Where a change goes, and what each package boundary forbids |
| [How-to guides](docs/how-to/) | Adding an editor feature, extending the field model, testing against the gates |
| [Demo runbook](docs/demo/README.md) | End-to-end acceptance criteria in user terms, doubling as a talk runbook |
| [Screenshot catalog](docs/screenshots.md) | What each documentation image must show, how to capture it, and what invalidates it |
| [Solr configuration files](docs/solr-configuration-files.md) | Which Solr config is hand-edited vs API-written, and what the plugin covers |
| [Platform mechanisms](docs/platform-mechanisms.md) | Dumb mode and model caching — what they are, and what this plugin decided |
| [Plugin development tutorial](docs/modern-intellij-plugin-development.md) | Building this kind of plugin from scratch, using this project as the worked example |
| [FAQ](docs/faq.md) | Why quick documentation links rather than copies, and why it can't rely on a live sources-jar lookup |
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

Start with [`docs/contributing.md`](docs/contributing.md) — setup, first run, where work comes from,
and what will reject a pull request. [`docs/code-organization.md`](docs/code-organization.md) covers
where a change goes, and the [how-to guides](docs/how-to/) walk through adding a feature.

Commits use conventional-commit subjects and must carry a sign-off (`git commit -s`). Commit bodies
carry real weight in this repository — they record *why* a constraint exists.

---

Plugin scaffolding based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
