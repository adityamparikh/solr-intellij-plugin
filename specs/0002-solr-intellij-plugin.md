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
  matters, and it belongs to the firewall, not to a checkbox in a plugin. What the plugin
  does instead is clarity: the selected connection is always visible, and a destructive
  action names its target in the confirmation.
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
| Configset repository — XML only, deployed by CI | Config files, no code, usually no build file | Full config intelligence, **after the user marks the configset root** — see "How the plugin decides to activate" |
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
Solr line, derived from the Solr and Lucene artifacts for that line. The generator runs in
the build, not in the IDE, which is what keeps it simple: loading Solr classes in a Gradle
task is ordinary, whereas loading them inside the IDE's classloader is not. A new Solr
line is a version bump and a regenerated catalog, not re-authoring.

A compiled jar does not volunteer all of this, and the plan says how each piece is
recovered. The part worth knowing at this level is that **attribute names are not
reflectable**: a factory takes a `Map<String, String>` and reads its attributes out of it
by string literal, so the names exist only inside the constructor body. Anything that
enumerates fields or annotations produces a plausible short list rather than an error,
which is the failure mode to test for.

**Match analysis is a deliberate exception to all of this.** The roughly fifteen factories
that determine whether a field matches whole values or tokens are named in code, not read
from the catalog, because that set *defines* the semantics rather than enumerating what
exists — and it has been stable across Solr majors while the surrounding list has not.

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

### Seeing and correcting what activated

Detection is a heuristic, so the user needs somewhere to see what it concluded and
overrule it. A settings page lists the configsets in the project — those found by
detection and those the user marked, distinguished from each other — with the marked ones
removable and a switch that turns detection off for the project entirely.

**Showing what was detected matters more than the ability to override it.** The two
failure modes are not symmetrical. A false positive announces itself: warnings appear on
a file that has nothing to do with Solr, and the user goes looking for the off switch. A
false negative is silent — nothing happens, which is indistinguishable from a plugin that
does nothing, and the user has no way to tell which they are looking at. A list that says
*these are the configsets I found* converts that silence into an answer, and it is the
first thing to reach for when someone reports that the plugin is not working.

Marked roots are shared through the project file, so one developer's marking changes
detection for everyone on the team. That is the intended behaviour — a configset is a fact
about the project — but it makes the list doubly necessary: shared state that cannot be
inspected or reverted from the UI is state nobody can be responsible for.

Connections get a page of their own rather than a section of this one, because their
storage differs — see "Connection definitions must never reach a shared project file"
below. Keeping one page per storage scope means the page itself tells the user whether
what they are editing will reach their teammates.

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

## How the plugin decides to activate

**The project depends on a Solr client, or the user says so. Nothing else.**

Activation is gated on the open project having a Solr client library among its
dependencies — `solr-solrj`, or one of the wrappers that carries it. Artifact ids are
matched, never versions, so the rule does not need revisiting when Solr releases. Every
wrapper depends on SolrJ transitively and the IDE resolves transitive dependencies, so a
project using Camel's Solr component or a Quarkus extension satisfies the gate whether or
not it names SolrJ directly.

Outside such a project, nothing activates however Solr-shaped the filenames are.

**Inside one, file names are still tiered by how much they prove.** The dependency
establishes that the *project* uses Solr; it cannot establish that a *particular file* is
Solr's, and a project that uses Solr may still contain an XSD called `schema.xml` that has
nothing to do with it. So:

- *Self-identifying* names — `solrconfig.xml`, `managed-schema`, `managed-schema.xml`,
  `elevate.xml`, `enumsConfig.xml` — carry Solr's own vocabulary and stand alone. One of
  them makes its directory a configset.
- *Ambiguous* names — `schema.xml`, `params.json`, `currency.xml` — are recognized only
  inside a directory a self-identifying name has already proven. In a real configset that
  costs nothing: the `solrconfig.xml` beside them does the proving.
- *Resources* — `stopwords.txt`, `synonyms.txt`, `protwords.txt`, `lang/` — are recognized
  only from inside an identified configset and never activate features themselves.

This is containment, not a heuristic: each tier asks whether a directory contains a name
Solr invented, which has an exact answer. It is the same mechanism the resource tier
already used, applied one level up.

**This replaces a set of directory heuristics, and it is worth saying why.** The earlier
design corroborated file names against their surroundings — a `conf/` parent, or a second
recognized file in the same directory — because `schema.xml` on its own is a name many
things use. That worked, but it was an inference, and inferences are wrong in both
directions and cannot be explained to a user who disagrees with them. A dependency is a
fact. Conditioning on it removes the guesswork rather than tuning it, and it is the same
signal the ecosystem already uses for this job: JPA Buddy decides a module is a JPA module
the same way.

**What this trades away, deliberately.** A repository holding configsets and nothing else
has no build file and therefore no dependencies to find, so it cannot satisfy the gate.
That shape stays supported, through the manual configset root the user marks — which is
why that override exists independently of the heuristics it used to back up, and why the
settings surface that exposes it matters more under this design than under the old one.
The cost is a first-run step for that user; the benefit is that every other user gets an
activation rule with no false positives to explain.

## Recognizing Solr usage

Rather than treating each framework as a special case, the plugin has a small set of
**recognizers**. Each knows how to spot Solr usage in one place, and each reports two
kinds of finding: *here is an endpoint* and *here is a field reference*. Everything
downstream is shared.

This keeps the cost of "support framework X" visible and bounded: one recognizer, not a
new subsystem.

**A recognizer activates on the module's dependencies, not on the file in front of it.**
Each declares the library it recognizes, and runs only in modules that actually depend on
it: no `solr-solrj` on the classpath, no SolrJ recognizer. This is the same signal JPA
Buddy uses to decide whether a module is a JPA module, and it is the right one for code
because the question a code recognizer has to answer is not "what is this file" but "could
this module be talking to Solr at all."

The configuration surfaces cannot use this signal — a configset is a directory of XML with
no classpath to consult, which is why detection there rests on file names and their
surroundings instead. Code is the one surface where an exact, already-indexed answer is
available, and it should use it rather than inheriting a heuristic it does not need.

What this buys is precision in the layout the plugin will most often meet: a repository of
many modules in which one talks to Solr. Scanning all of them for field-shaped string
literals would produce false positives in the modules that have nothing to do with Solr —
exactly the modules whose authors would be least able to explain the warning. The cost is
that a module using Solr over raw HTTP, with no client library, gets nothing; that is
accepted, because there is nothing there to recognize with any confidence anyway.

**SolrJ** — client construction supplies endpoints; queries and document building supply
field references. The primary recognizer and the one that proves the interface.

**Framework configuration** — a Solr URL *and its credentials* in application
configuration, resolved per profile with the framework's own precedence rules. Credentials
are part of the finding, not an afterthought: a server that needs authentication is
useless as a discovered connection without them, and the username almost always sits
beside the URL in the same profile. A connection offered from `staging` should arrive with
staging's user, not with `dev`'s.

Two rules govern a secret found this way. It is **offered, never adopted silently** —the
same rule that governs discovered endpoints. And on confirmation the secret is copied into
PasswordSafe rather than read from the configuration file on each use, so that the
plugin's own storage is the single place a credential lives once the user has accepted it.
A plaintext password in a committed `application.yml` is the user's problem to fix, not
the plugin's to propagate.

The dialects differ in ways that matter:

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

The Solr code today is the activation gate and nothing else. The plan owns which files
change and in what order; what follows is only the constraints that outlive the current
code, because those are what the plan is not free to trade away.

**Identity is per-configset, not per-file.** Detection currently answers "is this file a
configset file." Fields, types and analyzer chains are properties of a configset
directory, and one project may contain several, so the model needs "which configset does
this file belong to." The per-file answer stays, as the activation gate.

**Detection sits on the editor path, so it must be cheap and cached.** It runs on every
file the user opens. Its signals stay local — file names and their surroundings — and its
results are cached and invalidated on file-system change. Nothing on this path may contact
a server.

**Recognizing a configset and recognizing its resources are different jobs.** Beyond the
schema and `solrconfig.xml`, a configset contains `params.json`, `elevate.xml`,
`currency.xml` and `enumsConfig.xml`, which are evidence a configset exists. It also
contains the files analyzer chains name — `stopwords.txt`, `synonyms.txt`,
`protwords.txt`, `lang/` — which are not: those names are common enough outside Solr that
treating them as evidence would activate the plugin on projects that have none. They are
recognized only from inside a configset already identified, which is what makes navigating
a filter's `words=` attribute to the file it names possible.

**Connection definitions must never reach a shared project file.** A marked configset root
is a fact about the project and belongs in shared settings, where it already lives. A
connection is a fact about one developer's machine; its definition belongs in per-user
storage and its credentials in PasswordSafe. This is a second settings surface, not a
change to the existing one.

**Persistent settings leak between tests.** `SolrConfigsetTestCase` exists because the
platform's test base class shares one project across test classes; the same hazard applies
to every new persistent setting.

**The package namespace stays `org.apache.solr.ide`.** The question was live: the plugin
lives in a personal repository, is published under a personal vendor account, and is not
an Apache Software Foundation project — donation is explicitly not being pursued — so
occupying `org.apache.solr.*` implies an ownership the project does not have. It was
settled in favour of keeping the namespace, so no rename is pending and none of the code
below should be read as provisional on one.

What the decision does *not* settle is the presentation of the plugin to users, which is
where an ownership claim would actually mislead: the `<vendor>` element, the plugin name
and the Marketplace listing. Those are the trademark surface, and they are separate from
a package name that only appears in a stack trace.

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
  authentication failure, malformed responses, and an unrecognized server version — the
  states a real server will not produce on demand.
- **A contract test per supported line against a real Solr in a container**, pinned to an
  exact image tag. A fake can only replay responses somebody imagined, which is the wrong
  instrument for the risk that a server returns a shape nobody anticipated; this is what
  keeps the fake honest as Solr's wire format moves. No test requires a Solr that a
  developer started by hand.
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
- Editing features work with no connection, and server features work with no
  configuration files in the project — each demonstrated on a fixture project of that
  shape: a bare configset repository, an application with its configset in-repo, and an
  application whose configset lives elsewhere.
- Inspections produce zero false positives on both configsets Solr ships, enforced in
  CI.
- A connection can be created from a discovered candidate or entered by hand, with
  credentials in PasswordSafe.
- Queries run from the console and from the Java or Kotlin code that contains them, with
  results and scoring rendered structurally.
- Field names in SolrJ usage are checked against the model, and the Spring, Quarkus and
  multi-module fixture projects produce no warning the plugin cannot justify.
- Repository and server can be compared, and a configset uploaded and a collection
  reloaded from the IDE.
- No write happens without a human invoking it and confirming its target.
- The documentation above is published.

## Open questions

- **Marketplace compatibility cadence.** How quickly the plugin must follow a new
  IntelliJ Platform release, given the Solr-derived support policy is pinned to a
  different upstream than JetBrains'.
- **Package namespace.** `org.apache.solr.*` implies ASF ownership the project does not
  have. Renaming is cheap now and expensive after the code grows, so this is the one open
  question with a deadline — see "What changes in the existing code".
- **Which platform framework-configuration APIs are available to plugins, and in which
  IDE editions.** Determines how much of the framework-configuration recognizer the
  plugin implements itself. Verify before committing to specifics.

## References

- Implementation plan — the ordered path to this intent, and the authority on which steps
  are done: [`specs/plans/0002-solr-intellij-plugin-plan.md`](plans/0002-solr-intellij-plugin-plan.md)
- Demo runbook — these features as acceptance criteria in the user's own terms:
  [`docs/demo/README.md`](../docs/demo/README.md)
- Solr configuration file survey — which files are hand-edited, which are API-written,
  and what the plugin covers: [`docs/solr-configuration-files.md`](../docs/solr-configuration-files.md)
- Plugin development tutorial, using this project as the worked example:
  [`docs/modern-intellij-plugin-development.md`](../docs/modern-intellij-plugin-development.md)
- Precedents: Big Data Tools; Confluent for Apache Kafka; JPA Buddy; `elasticsearch4idea`
- IntelliJ Platform SDK: https://plugins.jetbrains.com/docs/intellij/
