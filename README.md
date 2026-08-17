# solr-intellij-plugin

![Build](https://github.com/adityamparikh/solr-intellij-plugin/workflows/Build/badge.svg)

> **Who this is for.** A Java or Kotlin engineer who wants IDE support for Apache Solr
> configuration files — no prior Solr or IntelliJ Platform plugin-development experience assumed —
> evaluating whether this plugin is worth trying today.
> **Read first:** [Glossary](docs/glossary.md) if Solr or IntelliJ Platform terms are new.

IDE tooling for Apache Solr development in IntelliJ IDEA.

Solr developers work across three disconnected surfaces: [configset](docs/glossary.md#configset) XML
edited with no language support, queries iterated in the Admin UI or curl, and client code that
references [field](docs/glossary.md#field) names as unchecked string literals. Every boundary between
them produces a class of silent runtime failure — a typo'd field name returns empty results rather
than an error, a [`copyField`](docs/glossary.md#copyfield) pointing at a removed field fails only at
core reload, a `qf` parameter on an unindexed field degrades relevance with no warning. This plugin
closes those gaps with language intelligence, cross-file navigation, and inspections.

> **In Java terms.** A configset is roughly a Hibernate mapping plus `persistence.xml`: one file
> (`managed-schema.xml`) declares the shape of your data, a second (`solrconfig.xml`) declares how
> the engine around it behaves, and — like a mapping file — nothing checks either one against how
> your code actually calls it. That gap is what this plugin closes on the configuration side.

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

![Nine field declarations in managed-schema.xml, each followed by an inline hint: id, sku and
category read as whole value, case-sensitive; name, description and text as tokenised,
case-insensitive; name_prefix adds prefix-capable; every hint then names the storage shape —
indexed, stored or not stored, doc values or no doc values, single- or multi-valued; notes carries
only the storage shape and text is the one multi-valued field, while legacy carries no hint at
all](docs/images/01-hints-match-capability.png)

The hints render beside each field declaration with no hover. Beside the match claim, each carries
the storage shape that decides whether a matched document can be returned at all. Two fields show
what the hint does when it cannot say everything: `notes` keeps only the storage shape, because its
analyser is unrecognised, and `legacy` carries no hint at all, because its `type` is undeclared.

> **In Java terms.** This is the same idea as an IDE showing the inferred type beside
> `var x = list.get(0)` — the information exists, but only after tracing something you did not write
> at that line. Here the trace runs from a field's `type` attribute into its
> [field type](docs/glossary.md#field-type), and from there into its
> [analyzer chain](docs/glossary.md#analyzer-chain) — nothing on the `<field>` declaration itself says
> "whole value" or "tokenised." The inlay hint exists because that trace is real work a reader would
> otherwise redo by hand for every field.

### Quick documentation on a field

![Quick documentation for the field category: a properties table giving each property's value, where
that value came from, what it accepts, and what it means](docs/images/02-quick-doc-field.png)

Every property's value **and where it came from** — this field, its type, or Solr's default. That
last column is the one the Reference Guide cannot have, because it is about your schema.

### Quick documentation on a class value

![Quick documentation for solr.StandardTokenizerFactory: short name and kind, fully-qualified class
name, one-sentence summary, accepted attributes, and a Reference Guide link](docs/images/03-quick-doc-class.png)

Read from the Solr artifacts themselves when the plugin was built — never fetched at edit time, and
never copied out of the Reference Guide it links to.

### An inspection catching what fails only at [core](docs/glossary.md#core) reload

![A copyField whose source names manufacturer, highlighted in the editor, with the Alt-Enter menu
open above it offering to change the name to *_t, category, description, legacy, name or
notes](docs/images/04-inspection-copyfield-quickfix.png)

A `copyField` pointing at a field no longer declared, flagged in the editor, with Alt-Enter offering
replacements. The six on offer are the six declared names closest in spelling — the schema declares
ten, and the other four do not appear. `*_t` is among them because a `copyField` may legitimately
name a dynamic pattern.

### Completion that knows the schema's vocabulary

![Attribute completion inside a field tag, listing docValues, sortMissingLast, default, large,
multiValued, omitNorms and more, each with a one-line summary and the values it accepts](docs/images/05-completion-field-properties.png)

Attribute completion inside a `<field>` tag: each property with its one-line summary and what it
accepts. [`indexed`](docs/glossary.md#indexed) and [`stored`](docs/glossary.md#stored) are missing
from the list because that tag already declares them.

The same rule reaches the factories, whose attributes are read from constructor bytecode when the
plugin is built:

![Attribute completion inside a filter tag declaring solr.EdgeNGramFilterFactory, offering
luceneMatchVersion and preserveOriginal, each labelled with the factory it comes
from](docs/images/06-completion-factory-attributes.png)

Cross-file navigation and Find Usages are not shown here — they are gestures rather than states, and
a resolved reference at rest looks like an ordinary editor. Both are captured in [the screenshot
catalog](docs/screenshots.md) as entries 7 to 9, held there for anyone assembling a talk or a
marketplace listing rather than repeated here.

### `solrconfig.xml` completes its own structure, not the schema's echo

![Element completion inside the config element in solrconfig.xml, offering Solr's own top-level
vocabulary — directoryFactory, dataDir, luceneMatchVersion, requestHandler, query — rather than an
echo of the sibling tags already written above the caret](docs/images/10-completion-solrconfig-structure.png)

Before this shipped, the platform ran schema-less and guessed from whatever same-named tag happened
to sit nearby — a guess that looks exactly like knowledge in a file made almost entirely of
same-named tags. Nesting is respected too: what completes inside `<query>` is not what completes
inside `<config>`.

### What can go, dimmed; what's missing, one keystroke away

![managed-schema.xml field block with indexed=true and stored=true rendered dimmed as restated
defaults, while stored=false on name_prefix stays at full strength](docs/images/11-dimmed-restated-default.png)

A restated default is correct Solr, so it is never underlined — it is rendered the way an IDE
renders any other redundant code, with an Alt-Enter intention that removes it and leaves the parsed
schema unchanged. The same Alt-Enter menu goes the other way on a field that lacks a capability
rather than restating one it already has:

![Alt-Enter menu on a tokenized text field offering 'Add exact-match companion field' and 'Add
prefix-capable companion field' intentions](docs/images/12-intention-companion-fields.png)

Both intentions generate the companion `<field>` and, where one does not already exist, its
`<fieldType>` — the first time this plugin edits a configset rather than only explaining or flagging
one.

## Planned scope

Three surfaces, connected by one shared model of what fields exist and what they can do —
and the comparison that only exists because two of them are connected.

| Surface | What it does | Needs a running Solr |
|---|---|---|
| **Configuration** | Navigation, Find Usages, inspections, match-capability hints and quick-fixes, completion, rename | No |
| **Server** | Browse collections, query console with structured results, index test documents, upload configsets and reload collections | Yes |
| **Code** | Field names in [SolrJ](docs/glossary.md#solrj) usage checked and completed, query syntax inside string literals, run a query from a gutter icon | No, better with one |
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
| [Implementation plan](specs/plans/0002-solr-intellij-plugin-plan.md) | Ordered steps, and which are done — the one file that owns status |
| [Project orientation](docs/project-orientation.md) | A reader's map of where the project stands, pointing into the plan rather than restating it |
| [User guide](docs/user-guide.md) | Every editor capability, organised by what you're trying to do, with the gesture and the outcome |
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

The build enforces two gates as part of `check`: an 80% line-coverage floor
([Kover](docs/glossary.md#kover)) and a documentation gate that fails on any undocumented public
declaration ([Dokka](docs/glossary.md#dokka)). Both are described in [CLAUDE.md](CLAUDE.md).

> **In Java terms.** Kover is this project's JaCoCo — a Kotlin-native line-coverage tool wired into
> the same `check` task JaCoCo usually occupies. Dokka is its Javadoc: it renders KDoc into API docs,
> and here it is configured to fail the build on an undocumented public declaration the way `javadoc
> -Xdoclint:all -Werror` would.

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
