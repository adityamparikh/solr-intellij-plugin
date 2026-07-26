---
specbuddy-type: spec
plan-file: specs/plans/0002-solr-intellij-plugin-plan.md
---

# Solr IntelliJ Platform Plugin

## Overview

An Apache-licensed (ALv2) IntelliJ Platform plugin that gives JVM developers working
with Apache Solr the tooling that Elasticsearch and Kafka developers already have, and
that Solr developers do not.

The plugin covers three surfaces that are disconnected today — the configuration files
in your repository, the running Solr you deploy to, and the Java or Kotlin code that
queries it — and connects them through a single shared model of what fields exist and
what they can do.

The comparable products are Big Data Tools, the Confluent plugin for Kafka, and JPA
Buddy. This plugin takes the config-and-code intelligence of JPA Buddy and the live
server tooling of the other two, because Solr development genuinely needs both: you
change a schema, you try a query, you look at what came back, you change it again.

**Quality bar.** This is specified as a product, not a proof of concept. A feature that
works on the maintainer's machine and produces false positives on a real project is
worse than an absent feature, because false positives are what get a plugin uninstalled.
Where this document has to choose between more features and fewer features that are
right, it chooses right.

## Goals

- Make Solr configuration files first-class in the IDE: navigable, validated, explained.
  Today IntelliJ treats a Solr schema as anonymous XML.
- Eliminate the silent failures that live at the boundaries between config, server and
  code — a typo'd field name that returns zero results instead of an error, a
  `copyField` pointing at a field that was deleted, a repository that quietly stopped
  matching the server it deploys to.
- Give the developer the whole loop in one place: edit config, run a query, read the
  results, see what the server actually has.
- Work for every shape of Solr project — a bare folder of XML, a Java service with no
  config in it, or both together — without requiring any of them.
- Ship on the JetBrains Marketplace as a maintained plugin under ALv2 with source
  linked.

## Non-Goals

- **Production operations.** This is a development tool. It is not a monitoring
  console, a cluster manager, or a bulk ingestion pipeline.
- **Replacing the Solr Admin UI.** The Admin UI is good at what it does. The plugin
  wins where it can see things the Admin UI cannot — most of all, your repository.
- **Guarding against production access.** Network reachability is the control that
  matters, and it belongs to the firewall, not to a checkbox in a plugin. See
  "Which server am I talking to" below for what the plugin does instead.
- **Spring Data Solr.** Unmaintained upstream. Its configuration properties may be
  read for connection detection; nothing else.
- **Other JetBrains IDEs at launch.** IntelliJ IDEA first. Others are possible later
  and nothing here precludes them.

## Background

### Why Solr development is worse than it needs to be

Solr work spans three surfaces that share a vocabulary — field names — and share
nothing else.

The configuration files are XML with no language support. Nothing tells you that a
`copyField` names a field that no longer exists, or that the `qf` parameter in a
request handler references a field that is never indexed, or that the field you are
about to search is tokenized and will never match a whole-value lookup.

Queries are iterated in the Admin UI or with `curl`, in a different window, against a
schema you have to hold in your head.

Client code passes field names as bare strings. The compiler cannot check them and
Solr will not complain: a query naming a field that does not exist returns zero results,
not an error. That is the worst failure mode a system can have — silent, plausible, and
discovered in production.

Elasticsearch has several maintained JetBrains plugins including an official one. Kafka
has Confluent's. Solr has none.

### What the Schema API does and does not displace

Solr's default configset opens with a banner: *this file is managed by the Schema API,
do not edit it by hand.* Any tooling for Solr configuration has to answer that.

The answer is that the API displaces a narrower slice of the work than the banner
implies:

- **`solrconfig.xml` has no real API alternative.** The Config API covers common
  properties and component registration, writing them into `configoverlay.json` — a
  format that is awkward to review and does not reach the parts that matter. Request
  handler defaults, dismax relevance tuning, update processor chains, cache sizing and
  commit behavior are hand-edited XML in every deployment topology.
- **Analyzer chains are not designed through an API.** A field type carrying a
  tokenizer chain *can* be posted, but designing one is holistic work: comparing the
  index chain against the query chain, deciding filter order, keeping an EdgeNGram on
  the index side only. That is editing.
- **Reading is workflow-independent.** Even when every field arrived through the API,
  the resulting file is what sits in ZooKeeper, what shows up in the pull request, and
  what someone opens when a search returns nothing.

### What this replaces

An earlier version of this specification answered the banner by refusing to edit files
Solr owns. It classified each schema by parsing a second configuration file, cached the
result, handled five fallback cases, and where Solr owned the file put a `curl` command
carrying a placeholder URL on the clipboard instead of applying the edit.

That existed because the plugin was offline — a fake URL was the only alternative it
could offer. A live connection dissolves it. **Files are edited, always.** Disagreement
between repository and server is shown rather than prevented, because that disagreement
was the actual risk. And applying a change through the API becomes a real button rather
than an apology.

So the plugin does not classify schema files at all. That model, its cache, its five
cases and the write gating built on them are deleted. They answered "am I allowed to
write here", which is no longer a question worth asking.

## Users and project shapes

One persona: **a JVM developer building on Solr in IntelliJ IDEA.** Their project is
one of three shapes, and the plugin does not get to choose which:

| Shape | What the plugin sees | What it can offer |
|---|---|---|
| Configset repository — XML only, deployed by CI | Config files, no code, usually no build file | Full config intelligence; connection if the developer adds one |
| Application with configset in-repo | Config, code, build file | Everything, including the links between code and schema |
| Application only — config owned elsewhere | Code and a connection | Server tooling and code checking against the live schema |

**This is the central architectural constraint.** No feature may require an input it
might not have. Each surface has to be useful alone and better when the others are
present. A user with only XML gets the editor. A user with only a connection gets the
console and the browser. A user with both gets everything, plus the comparison neither
half could produce on its own.

A secondary persona — the **contributor** extending the plugin — is addressed by the
documentation requirements.

## Architecture

### The spine: one model, two sources, four views

Everything the plugin does is a question about fields. *Does this `copyField` name a
real field? What can I type after `fq=`? Is `"categry"` a field? Does my repository
agree with the server?* One question, four askers.

So the plugin is built as **one model of the fields, fed by two sources, read by four
views.**

**Two sources.**

*The repository* — configuration files parsed into fields, field types, analyzer chains,
and the request handlers that reference them. Available whenever config files are in the
project. Describes what is **declared**.

*The server* — a live Solr, asked for its schema, its collections, and what is actually
in the index. Available only when connected. Describes what is **deployed**.

**One model.** It merges both and — critically — records where every fact came from. A
field known only from the repository, known only from the server, agreed by both, or
described differently by each are four distinct states. The fourth is drift. Drift
detection is therefore not a feature built later; it is what a source-tracking model
produces for free. The same tracking answers "why is the plugin telling me this," which
the quality bar requires.

**Four views**, each degrading independently:

| View | Needs | Without the other sources |
|---|---|---|
| Editor | Repository | Fully functional offline |
| Tool windows — collections, query console, drift | Server; drift needs both | Console works with no repository, completing from the live schema |
| Code inspection | Either source | Checks against whichever is available |
| Integration recognizers | Project code and config | Contribute endpoints and field references |

The payoff is that the hard correctness problems — what does this analyzer chain do,
does this field exist, what does the server really have — are solved once, in the model,
under tests that need neither an IDE nor a server. The views stay thin. That is the only
version of this scope in which "polished" is achievable, because the same question is
not being answered in four places with four sets of bugs.

### Components

Seven. The first four have no user interface, which is what makes them fast to test.

1. **Repository reader.** Configuration files to declared fields. Reads through the
   platform's XML PSI. Knows nothing of servers or UI.
2. **Server reader.** A live Solr to actual fields, collections and cores. Knows nothing
   of files or UI.
3. **Match analysis.** An analyzer chain to what a field can actually match: whole value
   or tokens, prefix or not, case-sensitive or not. A pure function, exhaustively
   testable, and the source of the plugin's most surprising output.
4. **Field model.** Merges 1 and 2, records the origin of every fact, exposes the four
   agreement states.
5. **Editor features.** Navigation, validation, hints, rename, documentation.
6. **Tool windows.** Collections browser, query console, drift view.
7. **Integration recognizers.** Spot Solr usage in code and framework configuration.

**Data flow.** Opening a file triggers configset detection, which fills the model from
the repository. Adding a connection fills the other half. Views observe the model.

**Server data refreshes on request and on connection change — never on a timer.** A
plugin that silently polls someone's Solr every thirty seconds shows up in their
monitoring and gets uninstalled.

### Talking to Solr: plain HTTP, not SolrJ

The plugin's own calls to Solr use plain HTTP and JSON. It does **not** embed SolrJ.

SolrJ brings a substantial dependency tree into a plugin that shares a classloader
environment with the IDE and everything else installed in it; the IDE already ships its
own Lucene, and this is a known source of conflict. It also couples the plugin to a
client version when the entire point is talking to whichever server the user has. Solr's
wire format is stable JSON over HTTP, and the plugin needs fewer than a dozen endpoints.

The trade is hand-written request and response handling instead of typed objects. At
this endpoint count that is the better side of the trade, and it degrades more gracefully
against a server version the plugin has never seen.

**This is unrelated to supporting the user's SolrJ code**, which is a first-class
feature. The rule is: *the plugin reads SolrJ, it does not call SolrJ.*

### The factory catalog

Completion and documentation need to know Solr's analysis factories — roughly 130
tokenizers, filters and character filters — and the attributes each one accepts. That
list is too large to hand-maintain and changes with Solr versions.

**It is generated at build time and shipped with the plugin**, one entry per supported
Solr line, produced by reflecting over the Solr and Lucene artifacts for that line. The
generator runs in the build, not in the IDE, which is what keeps it simple: loading Solr
classes in a Gradle task is ordinary, whereas loading them inside the IDE's classloader
is not. A new Solr line is a version bump and a regenerated catalog, not re-authoring.

Which entry applies is decided in this order:

1. **The connected server**, if there is one. It knows its own version, and it is the
   authority on what it will accept.
2. **`<luceneMatchVersion>` in the configset**, which conventionally declares what the
   configset targets. Note this names a *Lucene* version, not a Solr one — Solr 10.0
   pairs with Lucene 10.3, Solr 9.10 with Lucene 9.12 — so it needs translating through
   a small table rather than being used directly.
3. **The newest supported line**, when nothing declares anything.

Which source answered is recorded on the data, so the user can tell whether completion
came from their server, their configset, or a default.

An earlier draft proposed resolving this at runtime from the project's own dependency
jars, read as bytecode to avoid classloader conflicts, with per-module resolution. That
is deleted. It was substantial machinery to answer a question a connected server answers
directly and a declared version answers well enough — and empirically the two supported
lines differ by a single factory, so the precision it bought was close to zero.

### Deferring to the host IDE

The plugin follows the rules of the IntelliJ product it is installed into. It does not
reimplement platform capabilities, and it does not pretend to capabilities the host
edition lacks.

Concretely: where the IDE already models a framework's configuration — Spring Boot
profiles being the important case — the plugin asks the platform rather than parsing
YAML itself. The platform knows things a hand-rolled parser gets wrong, including which
profile a given run configuration activates. Those integrations are declared as
**optional dependencies**, so the features appear when the supporting functionality is
present and the plugin loads normally when it is not.

Where the platform offers nothing, the plugin falls back to a simple reader for obvious
cases and, failing that, to the user entering a URL once. Nobody is blocked; some users
get a better first run.

The exact platform APIs and their edition availability must be verified during
implementation rather than assumed here.

## What the plugin does

### Editing configuration

Available with no connection and no setup. This is the first-run experience and it must
be good, because value arriving before configuration is what keeps a plugin installed.

- **Completion** for field types, tokenizer and filter factories and their attributes,
  and field attributes.
- **Navigation** — a field to its type, a `copyField` to its target, a request handler
  parameter in `solrconfig.xml` to the schema field it names, and a filter's resource
  attribute to the actual `stopwords.txt` or `synonyms.txt` beside it.
- **Find Usages** for fields and field types.
- **Inspections** — dangling `copyField` sources and targets, handlers naming fields
  that do not exist, relevance parameters pointing at non-indexed fields, unused field
  types, known-bad analyzer chain orderings, and configuration elements removed in the
  Solr line the configset targets.
- **Match-capability hints.** Each field annotated with what it can actually match:
  whole value or tokens, prefix-capable or not, case-sensitive or not — derived from its
  index-time analyzer chain. This is the feature most likely to tell an experienced user
  something they did not know.
- **Quick-fixes** that add a missing capability using the standard patterns — an
  `_exact` companion field plus its `copyField`, or an EdgeNGram-backed `_prefix` field.
  Phrased as *efficient index-time* support, since wildcard queries already provide slow
  partial matching on any indexed field.
- **Quick documentation** on factories and attributes.
- **Rename** a field or field type, updating every reference across both files.

Files are edited directly and without warning. The plugin does not classify schema
files, refuse writes, or redirect them.

### Connecting

A connections list, pre-populated with candidates discovered in the project — see
"Recognizing Solr usage" below — each of which the user confirms rather than the plugin
adopting silently. Credentials go to the IDE's PasswordSafe, never to project files or
plain settings.

### Browsing a server

Collections, cores, shards, replicas and aliases. The fields the server actually has,
which are not always the fields its schema declares.

### Querying

A console where field names complete from the model, results render as a table rather
than raw JSON, and scoring explanations are an expandable tree rather than a wall of
text. History is kept. Queries can be saved into the project so they are reviewable in
version control and shared across a team.

### Working in Java and Kotlin

Full support for SolrJ usage:

- **Field names checked and completed** in query strings wherever they appear — `q`,
  `fq`, `fl`, `sort`, facet and highlight fields, and the dismax parameters.
- **Builder calls understood**, not only raw strings: `setQuery`, `addFilterQuery`,
  `addField`, `addFacetField`, `setSort`.
- **The indexing side too** — `SolrInputDocument.addField` carries the same silent bug.
- **Bean mapping** — SolrJ's `@Field` annotations map POJOs to documents, and an
  annotation naming a field the schema does not have is a real defect nobody catches.
- **Query syntax inside the string literal**, so a query gets structure and highlighting
  rather than being one grey blob.
- **Run this query** from a gutter icon beside it, in the console, against a selected
  connection.
- **Navigate** from a field name in code to its definition in the schema.

This is best-effort by nature: finding which strings are Solr queries means following
values through variables and constants, and it will never be complete. The plugin
handles the direct and near-direct cases and stays silent where it cannot tell. Silence
is the correct failure mode; a false positive on someone's working code is not.

### Comparing the repository against the server

The feature that justifies having both halves. Connected, with configuration in the
project, the plugin shows where they disagree: fields declared in your repository that
the server does not have, fields the server has that your repository never declared, and
fields whose definitions differ.

From there: upload the configset, reload the collection. Solr's own Admin UI cannot do
this, because it has no idea your repository exists.

### Indexing test documents

Push sample documents into a collection to try something out, with schema-aware
completion while writing them and sample generation from the schema.

## Recognizing Solr usage

Rather than treating each framework as a special case, the plugin has a small set of
**recognizers**. Each knows how to spot Solr usage in one place, and each reports two
kinds of finding: *here is an endpoint* and *here is a field reference*. Everything
downstream is shared.

This keeps the cost of "support framework X" visible and bounded: one recognizer, not a
new subsystem.

**SolrJ** — client construction supplies endpoints; queries and document building supply
field references. The primary recognizer and the one that proves the interface.

**Framework configuration** — a Solr URL in application configuration, resolved per
profile with the framework's own precedence rules. The dialects differ in ways that
matter:

- *Spring Boot* keeps profiles in separate files (`application-dev.yml` beside
  `application.yml`), with the active profile coming from a property, an environment
  variable, or the run configuration.
- *Quarkus* keeps them **inline in one file** as a key prefix — `%dev.quarkus.solr.url`
  — with no profile-named file to find. Anything looking for `application-*.properties`
  finds nothing. Quarkus also activates `dev`, `test` and `prod` automatically depending
  on how the application is launched.
- *Micronaut* uses separate files like Boot but calls them environments, several of
  which activate by context.
- *MicroProfile* implementations use ordinals rather than profile names.

There is no standard property name for a Solr URL — the Spring Data Solr keys are
effectively dead and real projects invent their own. Detection therefore matches on
values that look like Solr endpoints and keys that mention Solr, and always presents the
result as a suggestion the user confirms.

**Apache Camel** — routes name Solr endpoints as URIs (`solr://host:8983/solr/products`).
Three things, in value order: recognize the endpoint as a connection candidate; validate
the URI's options, which are a defined set where typos fail only at route startup; and
check field references in route parameters and document construction. Camel routes are
defined in Java, XML, YAML and Kotlin, which is more surface than it first appears —
Java and XML first. Where the IDE or an installed plugin already models Camel routes,
read that model rather than writing another URI parser.

Camel is a smaller population than plain SolrJ and multiplies the code-analysis surface.
It is specified fully and built after SolrJ is solid, because the recognizer interface
should be proven by its first implementation before its second depends on it.

## Behavior when things are missing or wrong

The quality bar is decided here, not in the feature list.

**Missing inputs are ordinary states, not errors.** No configuration files, no
connection, no code — each is normal. The plugin never shows an error for something
being absent, never displays a banner asking the user to configure it, and never nags.
Features whose inputs are missing are simply not present.

**The server is unreliable and that is expected.** It will be down, slow, behind a VPN
that just dropped, or return something unanticipated. Every call has a timeout. No call
blocks the UI. Failure is reported once, where the user asked for the thing, not as a
popup. Stale data is labelled stale rather than silently refreshed or silently kept.
Solr's own error messages are shown rather than rewritten, because they are usually good
and rewriting loses information.

**Uncertainty is never presented as fact.** Where the repository and the server
disagree, both are shown rather than one being chosen. A field the plugin cannot resolve
is marked unknown, not flagged as wrong. **Where the plugin is unsure, it says nothing.**
False positives are the failure mode that gets a plugin turned off.

**Every write is initiated by a human.** Uploading a configset, reloading a collection,
indexing a document, applying a schema change — each is invoked by name, confirms before
acting, and states which server it is about to touch. There is no background write, no
automatic synchronization, and no timer.

**Which server am I talking to.** The realistic accident is not reaching production —
firewalls handle that — but clobbering a shared development or staging collection that
is reachable by design. The mitigation is clarity, not gatekeeping: the selected
connection is always visible, and destructive actions name their target in the
confirmation.

**Performance is a correctness property.** Editor-path work runs on every keystroke.
Parsing files is acceptable there; contacting a server is not. Server data is held
separately and fetched asynchronously precisely so that a slow or unreachable Solr can
never make typing stutter.

## What changes in the existing code

The build, CI and documentation tooling are sound and stay: the Kover coverage floor
wired into SonarCloud, the Dokka documentation gate, SHA-pinned workflows, the JDK 21
toolchain, the changelog plugin, and `docs/modern-intellij-plugin-development.md`.

The Solr code is four files and needs the following work.

**`SolrConfigsetDetector` — extend from file recognition to configset identity.** It
currently answers "is this file a configset file." The model needs "which configset does
this file belong to," because fields, types and analyzer chains are properties of a
configset directory, not of a single file, and one project may contain several. The
existing per-file answer stays as the activation gate.

**Add caching on the detection path.** `hasDirectoryEvidence` lists a directory's
children on every call, and the object holds no cache. That is affordable for the
current feature set and not affordable once detection gates editor-path work that runs
per keystroke. Results must be cached per directory and invalidated on file-system
change.

**`SolrConfigsetFileKind` — widen beyond two kinds.** It recognizes the schema and
`solrconfig.xml`. A configset also contains `params.json`, `elevate.xml`,
`currency.xml`, `enumsConfig.xml`, and the resource files analyzer chains reference by
name — `stopwords.txt`, `synonyms.txt`, `protwords.txt`, and the `lang/` directory.
Navigating from a filter's `words=` attribute to the file it names is a feature this
enum currently cannot express.

**`SolrConfigsetSettings` — split by audience.** It persists manual configset roots and
a detection switch to the shared project file, which is right: a marked root is a fact
about the project. Connections are not. Connection definitions belong in per-user
storage with credentials in PasswordSafe, and must never land in a shared file. This is
a new settings surface beside the existing one, not a change to it.

**`plugin.xml` — everything beyond file-type registration is new.** Reference
contributors, inspections, annotators, intentions, documentation providers, rename
processors, tool windows, and the optional dependencies that gate framework integration.

**Package layout — the new components need homes.** `org.apache.solr.ide.configset`
holds the repository reader appropriately. The model, server client, code recognizers
and UI need sibling packages, established before code lands in the wrong place.

**Test infrastructure — two gaps.** `SolrConfigsetTestCase` exists because
`BasePlatformTestCase` leaks project-level persistent state between tests; the same
hazard applies to any new persistent settings. Separately, testing the server reader
requires a fake HTTP layer so the suite never depends on a running Solr — that fixture
is part of the work, not incidental to it.

**One thing to decide before more code is written: the `org.apache.solr` package
namespace.** The plugin lives in a personal repository, is published under a personal
vendor account, and is not an Apache Software Foundation project — donation is
explicitly not being pursued. Occupying the `org.apache.solr.*` namespace implies ASF
ownership the project does not have, and carries a trademark question for a Marketplace
listing. Renaming is cheap now and expensive after the code grows.

## Version support

**The plugin supports the Solr release lines Apache Solr has not declared end-of-life.**
Deriving the policy from upstream means it cannot drift from what the project actually
maintains. At the time of writing that is **10.x** and **9.10.x**; 9.9 and earlier are
EOL and unsupported.

When a line goes EOL the plugin drops it in its next release, recorded in the
compatibility matrix and the changelog.

The plugin also talks to servers it was not built against. Version handling degrades:
unknown fields in a response are ignored, unknown values are shown rather than rejected,
and an unrecognized server version is reported rather than refused.

**Toolchain floor.** Solr 10 requires Java 21, which sets the build's floor and rises
when Solr's does.

## Testing

- **Zero false positives on Solr's own configsets, enforced in CI.** Every inspection
  runs against `_default` and `sample_techproducts_configs` and must produce nothing.
  This is the gate that makes the quality bar real.
- **Match analysis** tested exhaustively against canonical field types — string,
  tokenized text, EdgeNGram, and the filter orderings that change the answer.
- **Reference resolution and rename** verified on representative configsets, asserting
  no dangling references remain.
- **The server reader against a fake HTTP layer**, covering success, timeout,
  authentication failure, malformed responses, and an unrecognized server version. No
  test requires a running Solr.
- **The field model's agreement states** — repository-only, server-only, agreeing,
  disagreeing — tested directly, since drift correctness reduces to these.
- **Recognizers against real project fixtures**, not synthetic strings: a Spring Boot
  project with profile files, a Quarkus project with inline profile prefixes, a
  multi-module project. This is exactly the category of feature that works on the
  author's machine and fails everywhere else.
- **Code inspection precision** — asserting the plugin stays silent on constructs it
  cannot resolve, which matters more than its hit rate.
- **Every inspection has a description file**, checked in CI, which doubles as the
  published inspection catalog.

## Documentation

Required before the Marketplace release:

- **README and quick start** — what it does, and from install to a working feature in
  five minutes.
- **Marketplace listing** — summary, annotated screenshots, a short recording of the
  headline features, tags, compatibility statement.
- **Feature reference** — each feature with a screenshot, what it does and its limits.
  Explicitly including how match capability is derived, and the caveat that wildcard
  queries provide slow partial matching regardless.
- **Inspection catalog** — assembled from the per-inspection description files.
- **Contributor guide** — environment setup, sandbox IDE, running the tests.
- **Compatibility matrix and changelog** — plugin against IDE against Solr line, in
  keep-a-changelog format, with EOL-driven drops recorded.

Following later: a troubleshooting guide covering why features did not activate and the
manual override; an architecture document recording the decisions here; and a
cross-link from the Solr Reference Guide's community tools section.

## Acceptance criteria

The plugin is ready to publish when:

- It installs on IntelliJ IDEA and activates on recognized configsets, with a manual
  override for layouts the heuristics miss.
- Editing features work with no connection; server features work with no configuration
  files in the project; each of the three project shapes is usable.
- Inspections produce zero false positives on both configsets Solr ships, enforced in
  CI.
- A connection can be created from a discovered candidate or entered by hand, with
  credentials in PasswordSafe.
- Queries run from the console and from a gutter icon in Java or Kotlin, with results
  and scoring rendered structurally.
- Field names in SolrJ usage are checked against the model, with no false positives on a
  real project.
- Repository and server can be compared, and a configset uploaded and a collection
  reloaded from the IDE.
- No write happens without a human invoking it and confirming its target.
- The documentation above is published.

## Open questions

- **Marketplace compatibility cadence.** How quickly the plugin must follow a new
  IntelliJ Platform release, given the Solr-derived support policy is pinned to a
  different upstream than JetBrains'.
- **Package namespace.** See the overhaul section. Needs deciding before the code grows.
- **Which platform framework-configuration APIs are available to plugins, and in which
  IDE editions.** Determines how much of the framework-configuration recognizer the
  plugin implements itself. Verify before committing to specifics.

## References

- Solr configuration file survey — which files are hand-edited, which are API-written,
  and what the plugin covers: [`docs/solr-configuration-files.md`](../docs/solr-configuration-files.md)
- Plugin development tutorial, using this project as the worked example:
  [`docs/modern-intellij-plugin-development.md`](../docs/modern-intellij-plugin-development.md)
- Precedents: Big Data Tools; Confluent for Apache Kafka; JPA Buddy; `elasticsearch4idea`
- IntelliJ Platform SDK: https://plugins.jetbrains.com/docs/intellij/
