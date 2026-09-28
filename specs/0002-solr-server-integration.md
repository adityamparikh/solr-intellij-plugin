---
specbuddy-type: spec
plan-file: specs/plans/0002-solr-intellij-plugin-plan.md
---

# Solr Server Integration

## Overview

The parent specification argues the Server track out as intent — plain HTTP rather than SolrJ, a
server half of the field model, drift between repository and server as the feature that justifies
building both — across five sections: "Talking to Solr: plain HTTP, not SolrJ", "Connecting",
"Browsing a server", "Querying", "Comparing the repository against the server", "Indexing test
documents". The plan carries that intent forward as five steps, 11 through 15, each a short list of
actions and a checklist of success criteria. Between the two sits nothing: no design record, and in the
code, one file —
[`SolrConnectionSettings.kt`](../src/main/kotlin/org/apache/solr/ide/server/connection/SolrConnectionSettings.kt).

`solrconfig.xml` was in the same position eight months ago — a paragraph of intent and a thirty-line
plan step — until
[`0002-solrconfig-xml-intelligence.md`](0002-solrconfig-xml-intelligence.md) settled what the step
meant concretely, and the work that followed shipped without the kind of rework a missing decision
usually costs. This document does the same job for the Server track. It does not restate the plan or
re-argue the parent specification's architecture; it answers the questions Step 11's seven-line action
list leaves open, because every one of them will otherwise be answered once, differently, by whoever
implements the collections window, the query console, the drift view and the document indexer — the
four steps that all sit on top of it.

**Scope.** This specification covers the HTTP and connection layer (Step 11) in full, and the parts of
Steps 12 through 15 that are actually shared infrastructure rather than that step's own UI: what the
server reader must produce, how a fetch fails, and what the drift model may and may not claim. It does
not design the collections tree, the query console's editor, or the document authoring form — those
are each one step's own work once the reader beneath them exists.

## Goals

- Settle the HTTP transport: which client, how a request times out, how it is cancelled, and how a
  failure is turned into something a view can show without inventing a network layer twice.
- Define exactly what "the server half of the field model populates" (Step 11's second success
  criterion) means in terms of the types that already exist —
  [`SolrConfigsetFacts`](../src/main/kotlin/org/apache/solr/ide/model/SolrConfigsetFacts.kt) and
  [`SolrFieldModel`](../src/main/kotlin/org/apache/solr/ide/model/SolrFieldModel.kt) — rather than as a
  new shape.
- Fix the version-selection gap this investigation found: the model already declares a `SERVER` source
  of authority and has no path that ever produces it.
- Settle which live collection a configset on disk is compared against, since nothing in the plugin
  knows today and a connection names a server rather than a collection — four steps need one answer
  and would otherwise reach four.
- Settle whether the model the editor reads ever carries server data, because the answer decides
  whether the boundary above is one rule or two, and whether an inspection can say something a
  reviewer cannot reproduce.
- Decide whose proxy and certificate configuration the client obeys — the IDE's or the JDK's — because
  the wrong answer fails silently for exactly the developers who cannot diagnose it, and because it
  cannot be retrofitted after a transport is written without rewriting the transport.
- State plainly what the plugin may claim when repository and server disagree, and what it must not —
  the same lesson the Editor track paid for four times, applied here before the code exists rather than
  after a defect report.
- Draw the boundary CLAUDE.md already requires — nothing on the editor path contacts a server — as
  something a test enforces, not only something a reviewer remembers.
- Specify the two testing tiers Step 11 names (fake HTTP layer, Testcontainers contract test) precisely
  enough that both can be written before the reader that they gate.

## Non-Goals

- **The collections tree and the document-authoring form.** Steps 12 and 15 own how they render; this
  document specifies what they read from. **The query console is no longer wholly in this list**: since
  [FR-16](#requirements) puts saved queries in the IDE's HTTP Client, Step 13's editor is largely
  contributions to that tool rather than an editor of its own, and what remains its own is the
  interactive surface beside it.

  **With four exceptions, added deliberately after this was first written.** Where a decision is shared
  by several steps *and* a plugin bundled with the same IDE has already shipped an answer, leaving it
  to each step means four steps inventing four answers to a question JetBrains settled once. So the
  pairing gesture ([FR-12](#requirements)), the compare-and-apply split ([FR-14](#requirements)), where
  saved queries live and what runs them ([FR-16](#requirements), [FR-18](#requirements)) and where a
  connection is created and bound ([FR-17](#requirements)) are settled here. What each surface *looks like* is still its own step's
  work. The precedents are recorded under
  [the plugins that already solved these problems](#the-plugins-that-already-solved-these-problems-in-the-same-ide).
- **Discovering connection candidates from project code or framework configuration**, which is
  ultimately how most connections will arrive — a URL read from the code or configuration that already
  names a server, resolved against the active profile. That is the Code track's recognizer interface
  (Step 18), a different mechanism entirely — a recognizer *offers* a candidate, this document is about
  what happens once one is confirmed into a real
  [`SolrConnection`](../src/main/kotlin/org/apache/solr/ide/server/connection/SolrConnectionSettings.kt#L24-L29).
  Demo step 35 exercises both together; this specification is responsible for the second half only.

  **This is why a connection can also be typed in by hand, and why that path is built first.** Making
  discovery the only way in would put the Server track behind Step 18 and leave a user with no way to
  reach a server that nothing in their code names — a colleague's staging box, a container they just
  started. Database Tools makes the same choice: detection offers, and the `+` always works.
- **SolrCloud cluster administration** beyond what the plan names: browsing topology, uploading a
  configset, reloading a collection. Creating or deleting a collection, changing replication factor,
  and cluster-property management are out of scope until a step asks for them.
- **Query relevance grammar and the scoring-explanation tree's rendering.** Step 13 owns the console;
  this document specifies only that field completion in it reads a `SolrFieldModel` built through the
  same merge function the editor uses — **its own instance, built for the collection being queried**,
  not the editor's. See [FR-13](#requirements): the console is about a collection, and completing a
  field that collection does not have would be the console misdescribing the thing it is about to
  query.
- **Per-property drift diffing.** [FR-9](#requirements) below settles that a *field* is DISAGREEING as
  a whole, matching what `SolrFact` already computes; whether the drift view eventually diffs
  `indexed` from `docValues` inside a disagreeing field is Step 14's presentation question, not this
  document's.

## Background

### What exists today, verified against the code rather than the plan's prose

- **The per-user connections surface is real and already correct on the one question it answers.**
  `SolrConnectionSettings` persists to the workspace file
  (`SolrConnectionSettings.kt:51`), keeps only the non-secret fields of `SolrConnection` in that state
  (`SolrConnectionSettings.kt:56-68`), and routes the password to `PasswordSafe` under a key derived
  from the connection's id, filed under the *username* rather than the id so a later request sends the
  identity Solr will actually recognize (`SolrConnectionSettings.kt:136-143`). `getPassword` and
  `getStoredUsername` both read back through `PasswordSafe` rather than the persisted state
  (`SolrConnectionSettings.kt:151-164`), which is the right shape for anything this specification adds:
  a reader that wants a credential should call these two, never touch `ConnectionState`, and never log
  either return value.
- **Nothing constructs an HTTP request anywhere in the plugin.** There is no HTTP client dependency in
  `libs.versions.toml`, no package under `server` besides `connection`, and no code path that reads
  `SolrConnection.baseUrl` for anything but display.
- **The field model was built with a server-shaped hole and nothing has filled it.**
  `SolrConfigsetFacts` is deliberately "a flat bag of lists... the same shape serves both sources"
  (`SolrConfigsetFacts.kt:10-15`) precisely so `SolrFieldModel.of(repository, server)` can merge two
  instances of the same type without either being privileged (`SolrFieldModel.kt:186-213`). Today every
  call site passes `server = null`, so every fact in every model reports
  `SolrAgreement.REPOSITORY_ONLY` (`SolrFieldModel.kt:76-78`). The seam is not aspirational — it is
  exercised today with a synthetic server half in tests, per Step 3's success criteria — it is simply
  never fed a real one.
- **Version selection already has a `SERVER` case it can never reach.** `SolrVersionSource.SERVER` is
  documented as "a connected server reported it. The authority, when there is one."
  (`SolrReferenceGuide.kt:206-208`), and `SolrVersionSelection.describeSource()` already has user-facing
  text for it (`SolrReferenceGuide.kt:157`). But `SolrFieldModel.solrVersion` reads only
  `luceneMatchVersion?.let { fromLuceneMatchVersion(it) } ?: DEFAULT`
  (`SolrFieldModel.kt:118-120`) — there is no third arm, and there cannot be one without a server
  version reaching the model first. Worse: `SolrFieldModel.of` builds `luceneMatchVersion` from
  `repo.luceneMatchVersion` alone (`SolrFieldModel.kt:206-207`), so even a `server` argument populated
  by Step 11 would have nowhere to put a reported version if this specification did not say where it
  goes. [FR-6](#requirements) closes this — it is the single fact this investigation is most confident
  would otherwise ship silently wrong, because it is exactly the shape of the four defects the Editor
  track already found: a value the plugin states confidently, sourced from the wrong place, with every
  existing test passing because no test asked a connected server what it thought.

### The HTTP boundary CLAUDE.md already draws, and what is missing from it

CLAUDE.md states the rule in one sentence: **"Nothing on the editor path contacts a server."** What it
does not say — because the Server track did not exist yet when it was written — is what enforces that
once a package exists whose entire job is contacting a server. The Editor track's own precedent is
instructive: the `configset.schema` / `configset.solrconfig` one-directional-import rule is enforced by
nothing automatic either, and CLAUDE.md names that as a deliberate choice for a boundary two co-located
packages share. A network boundary is a different risk: an editor-path class importing
`org.apache.solr.ide.server` does not merely blur a package boundary, it makes a keystroke able to
open a socket. [NFR-1](#requirements) proposes the same instrument the dumb-mode rule already trusts —
`SolrDumbModeContractTest` holds *that* promise to more than a comment, and this document asks for its
sibling.

### The parameter-name precedent this document follows

`0002-solrconfig-xml-intelligence.md` is not cited here only for its register. Three of its decisions
generalize directly to the server track and are adopted rather than rediscovered:

- **"Where the catalog cannot answer, the plugin says nothing"** becomes, here, *where the server
  cannot be asked, the plugin says nothing* — silence over a false positive extends past the editor
  track's inspections into what a drift comparison is willing to assert. See
  [FR-9](#requirements), and [FR-12](#requirements) for the case where the plugin does not even know
  which collection to ask.
- **"A dead link is worse than no link"** — that document's rule for Reference Guide anchors —
  generalizes to *a stale server fact is worse than no server fact*, which is why
  [NFR-4](#requirements) requires stale data to be labelled, matching the parent specification's own
  "Stale data is labelled stale rather than silently refreshed or silently kept."
- **The clean-fixture-first testing discipline** — write what must stay silent before what must fire —
  applies to the drift view exactly as it applied to inspections: the test that a repository and a
  server *agreeing* configset shows no drift is worth writing before the test that a real difference
  is caught.

### The plugins that already solved these problems, in the same IDE

Four of this document's decisions are not novel. They are problems JetBrains has shipped answers to in
plugins bundled with the IDE this one targets, and the answers were read out of those plugins rather
than reasoned about. A user who has used any of them arrives with expectations, and meeting them costs
less than inventing something better.

| Plugin | What it does | Read from |
|---|---|---|
| **Database Tools** | **DDL Mapping** — SQL files in a project describing a schema, explicitly mapped to a live database, with a matching action to clear it | `DatabaseView.CreateDdlMapping`, `DatabaseView.DdlMapping.Actions`, `DatabaseView.LinkedDataSource.ClearMapping` |
| **Kubernetes** | Comparing against a cluster and applying to it are **two separate actions**, both explicit | `Kubernetes.CompareWithCluster` and `Kubernetes.Apply`, each with a floating-toolbar variant |
| **HTTP Client** | Requests committed to the repository; the environment they run against kept beside them, split into a shared file and a private one | `http-client.env.json`, `http-client.private.env.json` |
| **HTTP Client** | Publishes extension points so another plugin can contribute a body language, a response presentation, variables and templates — which is why [FR-16](#requirements) uses it rather than imitating it | `httpClient.injection.request.customBodyInjector`, `httpClient.responseCustomPresentation`, `httpClient.dynamicVariablesProvider`, `httpClient.addRequestTemplateProvider` |
| **Kubernetes**, **Database Tools** | A global target selector *and* a per-surface binding, rather than one selection for everything | `Kubernetes.ContextSwitcherAction`, `Kubernetes.AttachContext` |
| **Spring Boot** | Active profiles are modelled and queryable rather than parsed ad hoc | `SpringProfile`, `SpringProfileProvider`, `ActiveProfilesNode` |

**The DDL Mapping precedent is the one worth dwelling on, because it is not an analogy.** It is the
same problem this document calls a pairing: files on disk describing a schema, bound by a human to a
live server that may or may not match them. That it exists, is user-invoked, and ships with a
clear-mapping action beside it settles three questions at once.

**The Kubernetes precedent is the one that changed an answer.** "Offer to apply where the change maps
onto the Schema API" reads as one feature; two actions is what shipped, deliberately, so that looking
never implies writing. [FR-14](#requirements) follows it.

**The HTTP Client precedent changed an answer twice, and the second time it changed the question.**
First it supplied the shape for a committed query and its environment. Then its extension points showed
that the shape was not the point: the IDE already ships a tool that authors and runs HTTP requests from
files in a repository, and a Solr query is an HTTP request. Looking for a *precedent to imitate* found a
file format; asking whether anything already *did the work* found the work already done.
[FR-16](#requirements) and [FR-18](#requirements) are what came of that.

Precedent is not proof, and two of these plugins are commercial code whose reasoning is not published.
What is claimed here is only what the action ids and file names show: that these shapes exist and that
users of this IDE have met them.

## Requirements

### Functional

**FR-1 — HTTP transport.** The plugin uses `java.net.http.HttpClient` for every call to Solr. This adds
no dependency — the toolchain is already pinned to JDK 21
(`build.gradle.kts:22-26`) — and it natively supports what the non-functional requirements below need:
per-request timeouts (`HttpRequest.Builder.timeout`), asynchronous execution
(`HttpClient.sendAsync` returning a `CompletableFuture`), and TLS for a server behind HTTPS — which,
measured rather than assumed, is the IDE's TLS and the IDE's proxying, because the platform installs
both as JVM defaults an unconfigured client picks up. [NFR-7](#requirements) has the measurements and
the one case that does not work this way. **This is a recommendation to verify, not an assumed fact about IntelliJ's
runtime** — the parent specification's own precedent for platform-API uncertainty is "the exact
platform APIs... must be verified during implementation rather than assumed here," and the same
caution applies to a JDK API used inside a plugin classloader for the first time.

**This governs the plugin's own traffic, not the user's.** Fetching a schema, a cluster status or a
Luke response is nobody's authored request — it is a tool window and a drift view calling out on their
own initiative, which is why the rest of this section is about timeouts, cancellation and a result type
that separates a Solr error from a transport failure. A query somebody typed is a different thing
entirely and is run by the IDE's HTTP Client, per [FR-16](#requirements). The two are not competing
transports; they are traffic with different authors, and Database Tools makes the same split between
the introspection it issues itself and the statements a user runs in a console.

**FR-2 — JSON handling: Jackson 3's tree model, referenced from the platform and not declared.**
Solr's wire format is JSON, and the parent specification already commits to "hand-written request and
response handling instead of typed objects" over embedding a client library. That commitment says
nothing about which parser does the reading, and an earlier revision of this section left it open on
the grounds that a platform-classpath assumption should be verified before it is relied on. It has
been; the decision is `tools.jackson.databind`, IDEA 2026.2's Jackson 3.

**The verification is what makes this a decision rather than a preference.** A throwaway plugin
reading a captured Solr schema response three ways — Jackson 3, Jackson 2 and `com.google.gson`, none
of them declared as a dependency — compiles against all three, and `verifyPlugin` reports
`Compatible` against `IU-262.8665.258` with no mention of any of them in its report: no unresolved
reference, no internal-API warning, no experimental-API warning. Referencing any of the three
undeclared is therefore safe as of this IDE target, which removes the concern that kept this open and
leaves the choice to be made on the merits.

Two merits decide it, and neither is "it is newest":

- **`path()` cannot throw, and [FR-8](#requirements) needs exactly that.** Navigating
  `readTree(body).path("schema").path("fieldTypes").path(0).path("indexAnalyzer")` yields a
  `MissingNode` at the first absent link rather than a null or an exception, so a response that parses
  as JSON but not into the shape expected falls out as *unrecognized* — which is what FR-8 requires —
  instead of as a thrown internal error that has to be caught at every level. Gson's
  `getAsJsonObject(...)` returns null or raises `IllegalStateException` on a type mismatch, so the same
  discipline would be hand-written at each step, and the step somebody forgets is the defect.
- **Arbitrary keys read directly.** `node.propertyNames()` gives the analyzer component's factory
  arguments as the `Map<String, String>` [FR-5](#requirements)'s table requires, with no fixed schema
  to declare. This is the requirement that rules out a typed binding entirely and is why the tree model
  is the part of Jackson being used — the annotation and data-binding machinery is not.

**Jackson 3 rather than Jackson 2, which is also present.** The platform ships both
(`com.fasterxml.jackson` and `tools.jackson`), which reads as a migration in progress; a plugin
targeting the destination outlives one targeting the origin. Note the renamed accessors —
`asString()` for Jackson 2's `asText()`, `propertyNames()` for `fieldNames()` — confirmed by compiling
rather than remembered.

**The residual risk is real, and this project already gates it.** An undeclared platform library can
be dropped by a future IDE without deprecation, and nothing in a normal build would say so. What
catches it here is `verifyPlugin` running against every entry in `verifiedIdeBuilds` in CI: an IDE
bump that stops shipping Jackson 3 fails the verifier in the pull request that raises the target,
rather than in a user's editor. That gate is the condition under which this trade is acceptable — if
it is ever removed, this decision has to be revisited rather than inherited.

**What is still hand-written is the mapping, not the tokenizing.** Jackson reads bytes into a tree;
turning that tree into `SolrConfigsetFacts` is this plugin's own code, per [FR-5](#requirements). The
parent specification's objection was to typed objects generated from a client library's model of Solr,
and a tree model is not that.

**FR-3 — Connections feed the reader, not the other way around.** A server reader function takes a
`SolrConnection` (`SolrConnectionSettings.kt:24-29`) and reads its credential through
`SolrConnectionSettings.getPassword(connection.id)` and `.getStoredUsername(connection.id)` at the
point of use — never cached in a field, never logged, never included in an exception message. A
connection whose `username` is non-null but whose stored password is null is a broken connection, not
an anonymous one: the reader must report this distinctly rather than sending an empty-string password,
which most Solr Basic Auth configurations would reject as a *wrong* credential rather than *no*
credential, turning a configuration mistake into a spurious authentication failure.

**FR-4 — Authentication is sent, not negotiated.** Where a connection carries a username, every request
sends `Authorization: Basic <base64(username:password)>` from the first attempt rather than waiting for
a 401 challenge and retrying. Verified against the Reference Guide's Basic Authentication Plugin page:
credentials are exactly a base64-encoded `username:password` pair per RFC 7617, and Solr's default
`blockUnknown=true` rejects an unauthenticated request outright rather than issuing a negotiable
challenge — so a challenge-response round trip would cost a request for no protocol reason. TLS is used
whenever `baseUrl` is `https://`, and which certificates are trusted is [NFR-7](#requirements)'s
subject rather than this requirement's: it is the IDE's trust store, not the JDK's.

**FR-5 — The server reader produces a `SolrConfigsetFacts`, not a new type.** This is the specification's
central design decision, and it follows directly from `SolrConfigsetFacts`'s own KDoc: it is
deliberately "what a *parser* produces," shaped so the same type serves both sources
(`SolrConfigsetFacts.kt:10-15`). The server reader is therefore a second parser — of JSON rather than
XML — with the same contract `SolrSchemaParser` already has: pure input to `SolrConfigsetFacts` output,
testable with no IDE fixture. **`<collection>` is not something the reader works out** — it comes from
the pairing [FR-12](#requirements) defines, and a configset with no pairing is not read at all.

**One request, not several.** `GET /<collection>/schema` wraps everything under a top-level `"schema"`
key and is complete: `fields`, `dynamicFields`, `fieldTypes`, `copyFields`, `uniqueKey`, `name`, and a
numeric `version`. [The verification pass](#verification-provenance) confirmed the counts returned by
the full endpoint match the per-kind endpoints exactly on both supported lines (Solr 10.0.0:
33 fields / 22 dynamic fields / 17 copy fields / 69 field types, identical from `/schema` and from
`/schema/fields`, `/schema/dynamicfields`, `/schema/copyfields`, `/schema/fieldtypes`; Solr 9.10.1
identical in shape). An earlier draft of this section guessed the opposite — that `dynamicFields`
required a second request — and it is wrong. The reader issues one schema request per collection.

| `SolrConfigsetFacts` property | Server source | Note |
|---|---|---|
| `fields` | `schema.fields[]` | `indexed`/`stored`/`docValues`/`multiValued` map directly; an absent key means *unset*, matching the repository parser's own null-means-unset convention (`SolrSchemaTypes.kt:76-77`) — not `false`. **Verified: the server reports what the schema *declared*, not the type's effective defaults** — `<field name="price" type="pfloat" indexed="true" stored="true"/>` comes back as exactly those keys and no `multiValued`. That is what makes a field-by-field comparison against the repository parser meaningful at all; had the server materialized inherited defaults, every field would read as `DISAGREEING` on properties nobody wrote |
| | | **Values arrive as JSON booleans, and `SolrField` stores each one twice.** The five named flags are `Boolean?` properties that a JSON boolean populates directly — easier than the XML side, which parses attribute text. But `SolrField.attributes` is `Map<String, String>` built as `attributesExcept("name", "type")` (`SolrSchemaParser.kt:68`), so it *also* holds `indexed`, `stored` and the rest, as the strings `"true"`/`"false"`. The JSON reader must populate both representations, stringifying on the way into the map, or two facts that agree will compare as different in the map while agreeing in the properties. The same applies to `SolrAnalyzerComponent.attributes`, which holds every factory argument as text |
| `dynamicFields` | `schema.dynamicFields[]`, each `name` is the pattern | |
| `fieldTypes` | `schema.fieldTypes[]` | `class` → `className`. The chain arrives under `analyzer`, `indexAnalyzer` or `queryAnalyzer` — all three keys observed — each an object with `tokenizer` (single) plus `filters` and `charFilters` (arrays); every other key on a component is one of its factory arguments |
| | | **A component names its factory under `name` *or* `class`, and the server echoes back whichever spelling the schema used — it does not normalize.** Both supported lines returned `name` for all 236 components in the shipped techproducts configset and `class` for none; a field type added through the Schema API with `{"class": "solr.StandardTokenizerFactory"}` read back as `class` verbatim. The reader must accept both keys, and resolve them the way the repository side now does — through `SolrClassCatalog.classForSpiName` — rather than comparing the strings. See the note below the table for how that resolution came to exist |
| `copyFields` | `schema.copyFields[]` | `source`/`dest`, both plain strings. **Verified: `dest` is never an array.** Several destinations for one source arrive as several entries repeating the `source` (`author`, `manu` and `name` each appear twice in techproducts), which is already the repository parser's one-source-one-destination shape (`SolrSchemaTypes.kt:141-145`). No expansion step is needed |
| `uniqueKey` | `schema.uniqueKey` | |
| `schemaVersion` | **not populated by this parser** | The server reports one, and nothing would read it: `SolrFieldModel.of` takes `SolrSchemaVersion.of(repo.schemaVersion)` — the repository half, deliberately, because "the schema version is a property of the file the user is editing" (`SolrFieldModel.kt:207-209`). Populating it would create a value that looks consumed and is discarded on the next line, which is the mistake the element catalog's `valueType` column already made and paid for. If a future step wants to show a schema-version disagreement it must change `of` first, and that is a change to argue for rather than to arrive by accident. **Note also that `SolrConfigsetFacts.schemaVersion` is a `String?` "exactly as written" (`SolrConfigsetFacts.kt:26-28`), not a `Float`** — an earlier draft of this row claimed the opposite and inferred from it that no string round trip was needed |
| `fieldReferences` | always empty | Already documented on the type: "always empty for a server, which reports its configuration rather than the file that produced it" (`SolrConfigsetFacts.kt:22-23`) |
| `luceneMatchVersion` | **not populated by this parser** | See [FR-6](#requirements) — the server's own version is a different fact, carried differently |

**The two spellings were not a server-side quirk to absorb quietly — the repository parser did not read
one of them at all, and this pass found that by looking.** `SolrSchemaParser.readComponent` required a
`class` attribute and returned null without one, so every `<tokenizer name="standard"/>` and
`<filter name="lowercase"/>` was dropped on the floor while the chain around it still parsed. That
spelling is not exotic: it is what Solr's own `_default` and `sample_techproducts_configs` use
exclusively on both supported lines. Counted over the four copies this repository vendors under
`src/test/resources/shipped-configsets/`, every one carries between 247 and 263 `name`-spelled
components and **zero** spelled `class`. Measured rather than reasoned about, the plugin parsed
**zero** analyzer components from the whole techproducts configset.

The consequence ran downhill into match analysis: a chain whose components were all dropped has a null
`tokenizer`, so `SolrMatchAnalysis.of` took its no-tokenizer arm and returned
`UNANALYZED.copy(granularity = TOKENS, confident = false)` (`SolrMatchAnalysis.kt:62-66`). **The plugin
degraded to silence rather than to a false claim** — `confident = false` doing exactly the job this
project's "silence over a false positive" rule asks of it — but quick documentation rendered an empty
analyzer chain (`SolrFieldPresentation.kt:187-188`) and every match-capability surface went dark on the
configsets Solr itself ships. The suite was green because the parser's own unit tests used `class` in
all six of their fixtures and `name` in none, and because `SolrShippedConfigsetTest`, which asserts
nothing is reported over those very files, guarded itself against a fixture that "passes just as well
when it is looking at nothing" by counting fields and field types — both of which parse — without ever
reaching inside a field type to the chain.

**This was an Editor-track defect rather than a Server-track one, and it is recorded here because this
document is where the evidence landed.** It is fixed: the parser reads both spellings, the catalog
carries each factory's SPI name read from the `NAME` constant it declares, and
`SolrClassCatalog.classForSpiName` resolves one spelling to the other. `SolrShippedConfigsetTest` now
counts analyzer components, which is the assertion that would have caught this originally — it fails
with `got 0` against the old parser.

**What it leaves behind for this specification is a standing requirement on
[FR-9](#requirements): the drift comparison must not treat `name` and `class` as disagreeing when they
denote the same factory.** That is now satisfiable rather than aspirational, because both sides can
resolve a spelling to a class through the same catalog — but it has to be *done*, and the server reader
is where the second half lands. A comparison that skipped it would report every analyzed field as
`DISAGREEING` on any configset written the modern way: a false positive at scale, in the one view whose
entire purpose is being believed.

**FR-6 — The server's reported Solr version becomes a new, distinct fact, not a value for
`luceneMatchVersion`.** `luceneMatchVersion` names a *Lucene* back-compat target the configset
declares; a connected server reports its own running *Solr* version directly, with no Lucene-version
translation needed at all — `fromLuceneMatchVersion` exists specifically because a configset only ever
*implies* a line, where a server *is* one. The parent specification's
[factory catalog section](0002-solr-intellij-plugin.md#the-factory-catalog) is where the three-tier
order is stated; an earlier draft of this sentence linked an anchor that does not exist in that
document, which is the failure its own "a dead link is worse than no link" rule names.

**The key path is `lucene.solr-spec-version`, confirmed against both supported lines** — a top-level
`lucene` object on `GET /admin/info/system`, holding a bare version string (`"10.0.0"`, `"9.10.1"`) that
needs no parsing beyond taking its major segment.

**Its neighbour is a trap, and the trap is the whole reason this row was worth verifying rather than
inferring.** That same object also carries `lucene-spec-version`, which is the *Lucene* version and a
different number in both cases — Solr 10.0.0 reports Lucene 10.3.2, Solr 9.10.1 reports Lucene 9.12.3.
The two keys sit adjacent, differ by one word, and both look like the answer. Reading the wrong one
would hand `guideSegmentFor` a Lucene major that happens to match the Solr major on today's releases and
silently stops matching the day the lines diverge — a value the plugin states confidently, sourced from
the wrong place, which is the exact defect shape this document was written to prevent. The two
`-impl-version` keys beside them carry a build hash and commit date appended to the version and are not
what to read either.

Concretely, this requires a new field — proposed as `SolrFieldModel.serverVersion: String?` populated
only from the server half and consulted first — because `SolrConfigsetFacts` cannot carry it either:
that type has to stay symmetric between the two sources (per its own stated purpose), and a repository
never has a server version to report. `SolrFieldModel.solrVersion` becomes:

```
server version reported → SolrVersionSelection(guideSegmentFor(major of it) ?: "latest", SERVER)
else luceneMatchVersion  → SolrVersionSelection.fromLuceneMatchVersion(it)
else                      → SolrVersionSelection.DEFAULT
```

which is exactly the three-tier order the parent specification already states under "Which entry
applies is decided in this order," implemented for the first time rather than described.

**The first argument is a Reference Guide path segment, not a version, and an earlier draft of this
section got that wrong.** `SolrVersionSelection(guidePathSegment, source)` is what the type takes
(`SolrReferenceGuide.kt:151-154`), and `fromLuceneMatchVersion` never passes a version into it — it
reads the segment out of the catalog with `SolrClassCatalog.guideSegmentFor(major)`
(`SolrReferenceGuide.kt:157-162`). Handing it `"10.0.0"` would produce links to
`…/guide/solr/10.0.0/…`, which is a variant of a defect this plugin has already shipped once and
fixed: an earlier revision assembled the segment as `${major}_0`, sent every Solr 9 configset to the
Solr **9.0** documentation, and the links were live and about the wrong Solr — "harder to notice than
a 404," in that fix's own words. The server arm must translate through the same catalog lookup, for
the same reason.

**A server on a line this build ships no catalog for reports `latest` as its segment and keeps
`SERVER` as its source.** `guideSegmentFor` returns null there, and the two obvious alternatives are
both worse: falling back to `DEFAULT` would discard the fact that a server answered at all, and
constructing a segment would invent a guide URL for a release that may not be published. Naming the
newest guide while still saying the connected server decided it is the honest pair, and it satisfies
the parent specification's "an unrecognized server version is reported rather than refused" — the
version string itself stays available for display beside the connection, which is where a reader
looks to find out what they are actually talking to.

**FR-7 — Collections, cores, shards, replicas and aliases read from the Collections API — which exists
only in SolrCloud mode.** `CLUSTERSTATUS` (`/admin/collections?action=CLUSTERSTATUS`, v1, or
`GET /api/cluster`, v2) reports collections, shards, replicas, configset names and per-shard health in
one response, and collection listing has its own narrower action, `LIST`. Verified against a SolrCloud
Solr 10.0.0: `cluster` carries `live_nodes`, `collections`, `properties` and `roles`; a collection
carries `configName`, `replicationFactor`, `router`, `health`, the three replica-type counts and
`shards`; a shard carries `range`, `state`, `health` and `replicas`; a replica carries `base_url`,
`core`, `node_name`, `state`, `type` and `leader`.

**A standalone Solr refuses the whole API, and this is the requirement's real content.** Both supported
lines, started without `-c`, answer every `/admin/collections` action — `LIST` included — with HTTP 400
and `error.msg` "Solr instance is not running in SolrCloud mode." The same is true of
`/admin/configs`. A reader that assumes the Collections API therefore reports a hard failure against a
perfectly healthy server, and a developer running a single-node Solr on their laptop is not an edge
case worth failing on.

Two consequences the implementing step must honour:

- **Discriminate on `mode` from `/admin/info/system` before choosing an endpoint**, not by catching the
  400. That field reads `"std"` on a standalone server and `"solrcloud"` on a cloud one, verified on
  both lines, and it comes back from the same call [FR-6](#requirements) already makes for the version
  — so it costs nothing. Branching on a caught error would also swallow the genuinely different 400 that
  a malformed request produces.
- **Standalone servers have cores, not collections**, read from `/admin/cores?action=STATUS` — verified
  returning `status.<coreName>` with `responseHeader.status` 0 on the same server that refuses
  `CLUSTERSTATUS`. Step 12's tree must render one vocabulary or the other rather than showing an empty
  collections list to someone whose server is working. What a standalone server cannot do is upload a
  configset or list configsets, so [FR-10](#requirements)'s re-fetch and Step 14's upload are
  SolrCloud-only capabilities and should be offered as unavailable rather than as failing.

**`aliases` is absent from the response when none are defined, not present and empty.** The reader must
treat a missing key as "none" — the ordinary shape of this response, not an error.

**`configName` on each collection is the server's own answer to which configset a collection uses**, and
it is worth noting beside [FR-12](#requirements): it does not decide the pairing, because a name on the
server tells you nothing about which directory on this developer's disk holds that configset, but it is
exactly the right thing to *show* beside a pairing prompt so a human confirming one is choosing with the
server's own vocabulary in front of them.

This document takes no position on v1 versus v2 beyond noting that v1's query-parameter form is the one
this investigation verified content for; the v2 response shape should be pinned by the Testcontainers
contract test in the same pull request that reads it, rather than assumed to match.

**Solr's own Admin UI is a working reference for most of this, and worth reading before designing
Step 12.** It is an AngularJS application served from the same node, and it reaches Solr the same way
this plugin will — over the documented HTTP admin endpoints, with no privileged access. Each capability
is one `$resource` declaration mapping a named operation onto a query parameter, so
`js/angular/services.js` reads as a catalogue of which action serves which screen: `Collections`
(`LIST`, `LISTALIASES`, `CLUSTERSTATUS`, `CREATE`, `DELETE`, `RENAME`, the alias and replica actions),
`Cores` (`CREATE`, `UNLOAD`, `RENAME`, `SWAP`, `RELOAD`), and `ConfigSets`.

Two things it does **not** do are as informative as what it does, because both are places where a
reader might otherwise assume a precedent exists:

- **It never uploads a configset.** `ConfigSets` is declared with `action: "LIST"` and nothing else —
  enough to populate the configset dropdown when creating a collection. Its only file upload is for
  *documents*, posting `FormData` to a core's update handler, which is Step 15's concern and not
  Step 14's. The upload format in [FR-10](#requirements) had to be established by performing it
  precisely because no client shipped with Solr demonstrates it.
- **It reloads through the Cores API rather than the Collections API**, which is a UI aimed at a node
  rather than at a collection. [FR-10](#requirements) explains why this plugin chooses the other form.

The Admin UI also carries a `SchemaDesigner` service against `/api/schema-designer/`, a v2 API distinct
from the Config Sets API. It publishes configsets by its own route and is not a precedent for
[FR-10](#requirements)'s upload; it is worth knowing about only so that it is not mistaken for one.

**FR-8 — Failures surface as Solr's own message, matching the parent specification's promise that
"Solr's own error messages are shown rather than rewritten."** Verified pattern from a non-zero-status
response: `responseHeader.status` is non-zero and `error.msg` carries the human-readable text, `error.code`
the numeric code. A reader function therefore returns a result type distinguishing at least: success
with a value; a Solr-reported error, carrying `error.msg` verbatim for display; a transport failure
(timeout, connection refused, TLS failure), described in terms of what happened rather than Solr's
vocabulary, since Solr said nothing; and a response that parsed as JSON but not into the shape expected
— reported as "unrecognized," per the parent specification's version-degradation rule, rather than
thrown as an internal error.

**Solr mirrors its error code into the HTTP status line, so checking the HTTP status is sufficient to
detect failure** — verified across an unknown field in a query, a malformed Schema API request and an
invalid Collections API action, each returning HTTP 400 with `responseHeader.status` 400 and
`error.code` 400 in agreement, so the cheaper check is the correct one. Two findings from the same pass
qualify it, and both are requirements rather than notes:

- **An error response is not always JSON.** A request to a collection that does not exist returns HTTP
  404 with an **HTML** body — Solr's servlet-container 404 page, not a Solr error document. A reader
  that parses every response body as JSON throws a parse error on the single most likely mistake a user
  makes: a typo'd or since-deleted collection name. The transport must key on the HTTP status first and
  attempt a JSON parse only where the body claims to be JSON, falling back to the "unrecognized"
  outcome above rather than surfacing a parser exception. This is also the case a test can easily miss,
  because a fake HTTP layer returns whatever the test author wrote — the fake tier owed by
  [the testing strategy](#testing-strategy) must include a non-JSON error body precisely because no
  hand-written fixture would think to.
- **A successful response can still be incomplete, and it says so without any status being non-zero.**
  A query with `timeAllowed=1` returns HTTP 200 and `responseHeader.status` 0, alongside
  `"partialResults": true` and a `partialResultsDetails` string naming the limit that was hit. Success
  and completeness are therefore two different questions, and the result type owes a third state:
  **complete, partial, or failed**. This matters beyond the query console. A drift comparison built on
  a partial response would report fields as missing from the server when the server merely stopped
  early — inventing disagreement out of a truncation, which is the precise failure
  [FR-9](#requirements) exists to prevent. **Where a response is partial, the plugin says so and
  compares nothing**, per the same "where the server cannot be asked, the plugin says nothing" rule this
  document adopted from the solrconfig specification; a partial answer is a question that was not fully
  asked.

**FR-9 — Disagreement is rendered, not resolved.** The drift view (Step 14) reads
`SolrFieldModel.disagreements` (`SolrFieldModel.kt:167-170`) and the per-fact `agreement` property
(`SolrFieldModel.kt:53-60`) exactly as they exist today — this specification adds no new agreement
state and no per-property diff. A `DISAGREEING` fact is shown with both its `repository` and `server`
values visible; **the view must never call `.effective` when rendering drift**, because `effective`
exists for single-source consumers that need one answer to display inline (an inlay hint has one line
to fill) and silently prefers the repository — using it in the one view whose entire purpose is
showing that the two disagree would hide the disagreement in the exact place it is meant to be shown.

**FR-10 — Upload and reload re-fetch rather than assume.**

**The wire format, verified by performing it** rather than inferred from the action's name. A configset
upload is `POST /admin/configs?action=UPLOAD&name=<name>` with the zip as the request body under
`Content-Type: application/octet-stream`. A `multipart/form-data` body works identically — Solr's
request parsing unwraps multipart into a content stream before the handler sees it, and both forms were
run against Solr 10.0.0 producing byte-identical results — so **the raw body is what this plugin
sends**, because it is the one that needs no multipart encoder written to reach the same outcome.

**Success was confirmed past the status code, which is the part worth insisting on.** Both uploads
returned HTTP 200 with `responseHeader.status` 0, and both appeared in `action=LIST` — none of which
proves the bytes arrived intact. Reading `/configs/<name>` back through `/admin/zookeeper` showed the
correct file tree in both cases, and creating a collection against the uploaded configset produced a
working collection whose schema read back with its full complement of field types. A write to a live
server is exactly where "the request succeeded" is worth distrusting, which is the same instinct the
rest of this requirement is built on.

**Reload has two forms and both answer.** `GET /admin/cores?action=RELOAD&core=<core>` is what Solr's
own Admin UI issues, and `GET /admin/collections?action=RELOAD&name=<collection>` is the SolrCloud
form; both returned status 0 against the collection created above. The collection form is the one this
plugin uses, because a pairing ([FR-12](#requirements)) names a collection and not a core, and reaching
for the core underneath it would mean resolving replicas the plugin has no reason to know about.

After either write, the drift view must invalidate its held server facts and issue a fresh read
before reporting the comparison clean. **It must not clear the diff client-side on a successful HTTP
response to the write**, because a write returning success is not proof the server now reflects it —
reload timing, a multi-replica collection still propagating, or Solr accepting a request it cannot
fully apply are all real gaps between "the request succeeded" and "the server agrees now." This is the
same distinction FR-8 already draws between a transport success and a Solr-reported outcome, applied to
the read that follows a write instead of the write itself.

**FR-11 — Indexing a test document and the two write endpoints share one confirmation discipline.**
Uploading a configset, reloading a collection, and indexing a document are each, per the parent
specification's "Every write is initiated by a human," invoked by name, confirmed before acting, and
show which server they are about to touch — reusing whatever confirmation mechanism Step 14 builds
first rather than each step inventing its own dialog. Commit behavior (hard, soft, or none) for an
indexed document is stated explicitly in that confirmation per Step 15's own action 3, not defaulted
silently, because an uncommitted document that "isn't findable yet" is indistinguishable from a failed
index to a user who was not told which one to expect.

**The default is `commitWithin`, shown rather than assumed.** "Not defaulted silently" is not the same
as having no default, and a confirmation offering three equal options to someone indexing one test
document is a quiz. A hard commit on a shared server is somebody else's latency spike and is the wrong
thing to make easiest; `commitWithin` makes the document findable shortly without that cost. The
control is visible in the confirmation and changeable there, on the pattern Database Tools uses for
transaction mode — a per-connection default with a per-invocation override in front of the user, not a
preference buried in settings.

**FR-12 — A configset is compared against a collection only where a human has said which, and that
pairing is stored.** Every read in [FR-5](#requirements) is addressed to `<baseUrl>/<collection>/…`,
and `SolrConnection` carries no collection: `baseUrl` is documented as "the server root, such as
`http://localhost:8983/solr`" (`SolrConnectionSettings.kt:22`). Nothing else in the plugin knows which
live collection a directory of XML corresponds to, and **nothing may infer it**. A configset directory
named `techproducts` is not evidence that a collection called `techproducts` on this server was built
from it; it is evidence that somebody named two things the same way, which is how a drift view ends up
confidently comparing a schema against a collection it has nothing to do with.

**A pairing is the triple (configset root, connection id, collection name)**, and it is what every
server-side read and the whole of Step 14 is addressed by. Four consequences, each of which would
otherwise be decided differently by four steps:

- **It persists per-user, beside the connections in the workspace file — never in the shared
  `solr.xml`.** A pairing names a connection id, and connections are per-developer by construction:
  the URL may be a personal port-forward and the credential is personal by definition. Writing a
  pairing to the project-level settings would commit a reference to a connection nobody else can
  resolve. The configset root is stored macro-collapsed, exactly as
  `SolrConfigsetSettings.addManualRoot` already stores one (`SolrConfigsetSettings.kt:134`), so a
  pairing survives the project moving on disk.
- **A configset may have several pairings; a comparison has exactly one.** In SolrCloud a configset is
  uploaded once and used by many collections, so forcing one pairing would make the common case
  unrepresentable. What must not happen is a comparison that silently merges or picks among them: the
  drift view compares against one named collection at a time, and says which.
- **No pairing is the ordinary state, and the plugin says nothing in it.** A project with configsets
  and no connection, or with a connection nobody has paired, produces no drift content and no server
  half in any model — not an empty comparison, not a prompt, not a guess. This is the same rule the
  editor track holds everywhere: where the plugin cannot be sure, it is silent.
- **A pairing is created by a human and only by a human.** The chooser may *pre-select* a collection
  whose name matches the configset directory, because a default in a dialog somebody confirms is a
  convenience rather than a claim. Writing the pairing without asking would be the claim.

**Removing a connection removes its pairings**, since the triple no longer resolves. A pairing whose
collection is absent from the server is reported when it is used and left in place — a collection can
be down, renamed back, or not yet created, and deleting the user's stated intent because a server was
temporarily unreachable is the plugin discarding information it did not author.

**The gesture is a named action, from either end, and there is a second action that undoes it.** An
earlier revision left this to Step 12 or Step 14 as a UI question. It is settled here instead, because
Database Tools has shipped the same decision and the shape is worth inheriting rather than
rediscovering: *DDL Mapping* binds SQL files describing a schema to a live database through
`DatabaseView.CreateDdlMapping`, and clears it through `DatabaseView.LinkedDataSource.ClearMapping`.
Files on disk, a live server, a human saying which — the same problem under a different name.

So: **"Compare with configset…"** on a collection in the collections tool window, and the reverse from
a configset root, both reaching the same chooser. **A clear action ships in the same change as the
create action**, not after it, because a pairing pointing at the wrong collection is worse than none
and a user who cannot undo it has been given a defect rather than a feature.

**The pairing is per-user state, not shared project configuration.** It names a path in this
developer's checkout and a connection that lives in their own settings, and neither survives a
colleague cloning the repository. This is the same three-way split Database Tools makes and the same
one `SolrConnectionSettings` already makes for a connection's password.

An unpaired configset stays inert and says so where the affordance lives, rather than silently doing
nothing — the same way a SQL file with no data source attached is visibly unbound rather than merely
unhelpful.

**FR-13 — The editor's model never carries a server half. The two-source model is built where it is
asked for.** `SolrConfigsetReader.modelFor` — the entry point all twenty-five editor-path callers
reach — calls `SolrFieldModel.of(facts)` with no server argument, and continues to. Four reasons, of
which the first is mechanical and the rest are about what an editor is for:

- **The cache cannot express a server half.** `modelFor` caches on the `PsiDirectory` with its
  dependencies listed as the configset's source files plus `VFS_STRUCTURE_MODIFICATIONS`
  (`SolrConfigsetReader.kt:73-81`), and the KDoc there already rejects
  `PsiModificationTracker.MODIFICATION_COUNT` for being too broad. A completed fetch is neither a file
  edit nor a VFS structure change, so a server-aware editor model needs a dependency that advances on
  every fetch — invalidating every configset's model and re-running every inspection on every open
  file, for data the editor's own answers would barely use. CLAUDE.md's standing rule that a fact the
  reader does not already read must join `sourcesOf` is the same observation from the other side: a
  server is not a source it can read.
- **It would make editor answers irreproducible.** An inspection that fires only while connected
  cannot be reproduced in review, and cannot be reproduced at all by the golden-configset gate, which
  runs every inspection over Solr's own configsets with no server anywhere. The same file would
  highlight differently in two developers' IDEs and in CI, which is the property that makes a warning
  worth acting on.
- **It would make the editor describe fields the file does not contain.** `SolrFact.effective` prefers
  the repository, so a server half changes an editor answer only where the repository is *silent* —
  that is, only by adding facts about fields the user's own schema does not declare. An editor feature
  exists to explain the file in front of the reader.
- **It collapses "contacts" and "consumes" into one rule.** [NFR-1](#requirements) forbids the editor
  path from *contacting* a server. If the editor model could carry server data, that rule would need a
  second half about consuming it — and a boundary with two halves is one people cross by taking the
  other.

**The mechanism this needs already exists and has no production caller yet.**
`SolrConfigsetReader.factsFor(configset)` returns the repository half alone, documented as "exposed for
the drift comparison, which needs the two halves separately rather than the merged model"
(`SolrConfigsetReader.kt:86-91`). A server-side surface builds
`SolrFieldModel.of(factsFor(configset), serverFacts)` itself, once per pairing
([FR-12](#requirements)). Step 14 is that method's first caller; today only its tests reach it.

**So Step 11's second success criterion — "the server half of the field model populates" — is satisfied
by `of` being called with a real server half on the drift path, and not by `modelFor` changing.** The
seam was built to be fed from somewhere; this says where, and just as importantly where not.

**FR-14 — Comparing against a server and applying to it are two actions, and only additive changes get
the second one.** Step 14's third action reads as one feature — "where a change maps onto the Schema
API, offer to apply it" — and Kubernetes shipped it as two: `CompareWithCluster` and `Apply`, each
invoked by name. That separation is the requirement here, for the reason it presumably was there:
looking at what differs must never be the same gesture as changing it.

**Only additive changes are offered.** `add-field`, `add-dynamic-field` and `add-copy-field` are safe
in the sense that matters — an existing document simply lacks the new thing, and nothing already
indexed becomes wrong.

**A field type change is shown as drift and offered no action at all.** This is the requirement that
protects a user's index, and it protects them from Solr rather than from the plugin: the Schema API
*accepts* a `replace-field` changing a type, and reports success, while every document already indexed
keeps the encoding it was written with. The field is then declared one way and stored another, queries
return wrong results, and nothing anywhere reports an error. A plugin offering that button hands the
user a green checkmark on a corrupted index. Only a full reindex makes it true, and this plugin cannot
do that and must not imply it can.

**Every row shows the request it would send; only the additive ones offer to send it.** A disabled
button with a tooltip is a weak answer to "why can this row not be applied," and Database Tools gives a
better one: comparing two schemas there produces **reviewable DDL** rather than a silent mutation, so
what the tool would do is a thing you read before deciding.

The same shape here. Each drift row can show its Schema API payload —

```json
{ "add-field": { "name": "price", "type": "pfloat", "indexed": true, "stored": true } }
```

— and for an additive change, sending it is the next button along. A field type change shows the
`replace-field` payload it *would* need, next to the reason it is not offered: Solr will accept exactly
this and report success while every indexed document keeps its old encoding.

That turns the asymmetry from an omission into information. The user sees precisely what the plugin
declines to do, in the vocabulary Solr itself uses, and is left able to run it themselves against a
collection they are prepared to reindex — which is their decision to make and not this plugin's to
prevent.

**FR-15 — What the index actually holds is a third view, not a fourth half of the model.** Step 12
promises "the server's actual fields, which are not always the fields its schema declares," and that is
the Luke handler rather than the Schema API: fields a dynamic pattern created, what is genuinely
indexed, term counts. It is a real requirement and it does not belong in `SolrConfigsetFacts`.

The reason is [FR-5](#requirements)'s own: that type is deliberately symmetric because "the same shape
serves both sources," and a configset can declare every one of its fields. It cannot declare a field
that exists only because `*_s` matched something at index time. Merging Luke's answer into it would
make the symmetry false, and the first thing to break would be drift — a dynamic field's instances
would read as server-only fields the repository forgot to declare, on a configset that declared the
pattern correctly.

So the drift comparison stays schema against schema, and what the index holds is shown beside it in the
collections tree as its own thing. Two questions, two answers, neither pretending to be the other.

**FR-16 — A saved query is an `.http` file, run by the IDE's own HTTP Client.** Step 13 wants queries
"saveable into the project so they are version-controllable," which means a committed file, and a
committed file naming a connection is a file that does not work on a colleague's machine — the
connection lives in their per-user settings and their server is not this one.

An earlier revision of this requirement invented a file type to solve that, borrowing the HTTP
Client's environment split. **That was the wrong conclusion from the right evidence.** A Solr query
*is* an HTTP request, the IDE ships a tool whose entire purpose is authoring and running HTTP requests
from files in a repository, and it is bundled in the same distribution this plugin already targets. The
question was never what file format to invent; it was whether to do the work at all.

So: **`.http` files, and the HTTP Client runs them.** What that brings, without this plugin building
any of it — execution, request history, the response viewer, and the environment mechanism the earlier
revision was reaching for: `http-client.env.json` committed beside the requests, and
`http-client.private.env.json` git-ignored beside that. A colleague clones the repository, opens the
same queries, and selects their own environment.

This also settles how a discovered profile reaches a query. A Spring or Quarkus profile resolved by
[Step 18](../plans/0002-solr-intellij-plugin-plan.md#step-18-framework-configuration-the-shared-half-and-spring-boot)
becomes an environment rather than a separate mechanism, which is the point of environments existing.

**What this plugin adds is Solr's knowledge, through the extension points the HTTP Client publishes**
— see [FR-18](#requirements). Field completion inside the query, results rendered as a table rather
than raw JSON, the scoring explanation as a tree. Those are Step 13's actual value, and they are
contributions to somebody else's editor rather than an editor of our own.

**The interactive console remains its own surface, and that is not a contradiction.** Database Tools
ships both committed `.sql` files and a console, because a file you keep and a scratch you iterate in
are different things. The same holds here. What changes is that the console is no longer where saved
queries live, and no longer needs a file format of its own.

**FR-17 — Connections are created by the user, and each surface binds its own.** Nothing creates a
`SolrConnection` today; the type has persisted them since before this document existed. They are
created from a `+` in the collections tool window and from a Settings page, both reaching one editor.

**There is a selected connection and it is not the only one.** Kubernetes ships both a
`ContextSwitcherAction` and an `AttachContext`, and Database Tools binds each console to its own data
source rather than to a global choice. The same holds here for a good reason rather than by imitation:
a drift view is about the collection a configset is paired with, while a query console may
legitimately be pointed at staging while the drift view looks at dev. A single global selection makes
that impossible; a global default with a per-surface override makes it ordinary.

Storage follows the split `SolrConnectionSettings` already makes and Database Tools makes too:
shareable fields in workspace state, secrets in `PasswordSafe`, and anything naming a local path — a
pairing, per [FR-12](#requirements) — per-user rather than shared.

**FR-18 — The HTTP Client integration is a declared dependency on a JetBrains plugin, and rests on
extension points rather than on its internals.** [FR-16](#requirements) puts saved queries and their
execution inside `com.jetbrains.restClient`. That is a dependency, and it is written down here because
an unstated one is how a feature disappears on somebody's machine for reasons nobody can reconstruct.

**It is bundled, and the argument for depending on it is the one already made for Java PSI.** The
plugin descriptor takes `com.intellij.modules.java` as a hard dependency on the grounds that "IDEA has
been a single unified distribution since 2025.3 and bundles Java, so the condition was true in every
IDE that would ever run this." `restClient` ships in the same distribution and carries no
`<product-descriptor>`, so it is not separately licensed. The same reasoning applies, and it applies
*because it was written down* — if the distribution shape changes, both dependencies are revisited
together rather than one being found by a bug report.

**Four extension points carry the integration**, each contributing Solr's knowledge to the HTTP
Client's editor rather than reimplementing it:

| Extension point | What this plugin contributes |
|---|---|
| `com.intellij.httpClient.injection.request.customBodyInjector` | Solr query syntax inside a request body, which is what makes field completion possible there |
| `com.intellij.httpClient.responseCustomPresentation` | Results as a table and the scoring explanation as a tree, rather than raw JSON — Step 13's actual value |
| `com.intellij.httpClient.dynamicVariablesProvider` | Collection names from a configured connection, as variables a request can reference |
| `com.intellij.httpClient.addRequestTemplateProvider` | A starting request for querying a collection, so the first one need not be written from memory |

**These are another plugin's extension points, not platform API.** They are declared by a JetBrains
plugin whose reasoning is not published, they may be undocumented, and they may change between
releases. That is the same exposure [FR-2](#requirements) accepts for Jackson 3.

**The gate this requirement originally named does not close it, and that was checked rather than
assumed.** An earlier revision said the exposure was accepted "under the same gate: `verifyPlugin`
runs against every entry in `verifiedIdeBuilds` in CI, so an IDE that removed or changed one fails the
pull request raising the target." It does not. With `httpClient.addRequestTemplateProvider` renamed in
`plugin.xml` to `httpClient.thisExtensionPointDoesNotExist`, the Plugin Verifier's verdict is
**`Compatible`** and the task succeeds: it verifies API compatibility of *classes*, not that a
descriptor's extension point names resolve. This is the same gap `SolrPluginDescriptorTest` was
written to close for class names — a registration names its target as a string, so a wrong one
compiles, ships, and contributes nothing.

The failure that gap admits is the quiet kind. The plugin loads, nothing errors, and a menu entry
simply never appears — in a menu belonging to another plugin, which nobody checks after a change they
did not make to it.

**So the gate is `SolrHttpClientContractTest`**, which walks `plugin.xml` for every `httpClient.*`
registration, asserts the IDE actually declares each point, and asserts this plugin's contribution is
present in the point's extension list. Discovered by walking rather than listed, for the reason
[NFR-1](#requirements) gives about allowlists. `verifyPlugin` remains valuable for what it does check
and is not the answer to this. If *that* test is weakened, this decision is revisited rather than
inherited.

**What is not delegated is the reader.** [FR-1](#requirements) keeps the plugin's own traffic on
`java.net.http.HttpClient`, because a schema fetch is not a request anybody authored and the HTTP
Client is a UI for the ones that are.

### Non-functional

**NFR-1 — The editor-path boundary is enforced by a test, not only by CLAUDE.md's prose.** A contract
test — the same shape as `SolrDumbModeContractTest`, which already holds the dumb-mode promise to more
than a comment — asserts that **the only packages importing `org.apache.solr.ide.server` are
`org.apache.solr.ide.server` itself and a short, explicitly named allowlist**: the tool windows and
actions that exist to talk to a server, which Steps 12 through 15 add. Every other package in the
plugin, discovered by walking the source tree rather than listed, must not import it.

**Stated as an allowlist of server consumers rather than a denylist of editor packages, and that is
the whole of the design.** An earlier draft enumerated the editor-facing packages to forbid —
`configset.schema`, `configset.solrconfig`, `configset.hint`, `configset.intention`,
`configset.reading` — and two of those five do not exist; the packages under `configset` are
`activation`, `editing`, `navigation`, `reading`, `schema` and `solrconfig`. A hand-maintained list of
what to forbid was wrong on the day it was written, and would have gone quietly wronger every time
someone added a package, because a package missing from the list is a package the test permits. This
plugin has paid for a hand-maintained copy of a list before — two of them, for the supported Solr
lines, drifting silently in both directions until a test compared them against what the build
generated. Inverted, the failure mode inverts with it: a new package that reaches for the server fails
the test until someone adds it to the allowlist deliberately, in a diff a reviewer sees.

This is the concrete answer to "what enforces it": today nothing does, because nothing on the editor
path has ever had a server package to import. Once Step 11 creates one, the boundary stops being
self-evident and starts being something a future change could cross by accident — an inspection author
reaching for "just check the live server" to resolve an edge case the repository alone cannot answer,
which is precisely the shortcut this rule exists to close off before it is taken once.

**NFR-2 — Nothing here blocks the UI thread, and the reader is a suspending function.** This document
originally left the mechanism open — "the requirement is the outcome (async, off the EDT), not the
mechanism" — and named `HttpClient.sendAsync` returning a `CompletableFuture` as the likely shape.
Step 11 built that, and then replaced it. What follows is the decision and why it changed, because the
first shape was not wrong so much as foreign.

**A `CompletableFuture` is not cancellable by anything the IDE uses to cancel work.** A progress
indicator cannot stop one, a closing tool window cannot stop one, and a caller who has lost interest
has no way to say so — the only ways to consume it are `.get()` and `.join()`, both of which block,
and both of which freeze the UI when a caller reaches for the obvious thing from an EDT-dispatched
path. An API whose natural use is the bug it exists to prevent is the wrong API.

So the reader is a `suspend fun` returning its result directly, running the *blocking*
`HttpClient.send` on `Dispatchers.IO`. `kotlinx.coroutines` ships on the platform's own runtime
classpath and platform services use it throughout, so this is the idiom a reader of this codebase will
already know. The signature loses a wrapper — a caller writes `val result = transport.get(...)` — and
gains the thing the wrapper never had, which is [NFR-3](#requirements)'s cancellation.

**NFR-3 — Every request is timeout-bounded and cancellable.** A per-request timeout (proposed default:
ten seconds, overridable per call for the console's potentially slower queries) is set on every
`HttpRequest`. A view that no longer needs a pending request — the user closed the tool window,
navigated away, or issued a newer query that supersedes an older one — must be able to cancel it rather
than let it complete and be discarded.

**`runInterruptible(Dispatchers.IO)` is the mechanism, and the distinction it draws is the whole
requirement.** An earlier revision named `CompletableFuture.cancel()` and flagged that whether it
aborts the underlying socket exchange — rather than merely detaching the caller — needed verifying.
The question was right and it applies to coroutines too, which is worth stating because "it is
suspending, therefore it is cancellable" is false and was believed here for one commit.

**Coroutine cancellation is cooperative, and a blocking JDK call does not cooperate.** A blocking
`HttpClient.send` inside a plain `withContext(Dispatchers.IO)` runs to completion; the caller learns
it was cancelled only afterwards, having held a connection for the full duration. Measured against a
server that sleeps thirty seconds and a caller that cancels after three hundred milliseconds:
`withContext` returns in **30,203ms**, and `runInterruptible` in under **5,000ms**. The first is not
cancellation, it is bookkeeping.

`runInterruptible` interrupts the thread, and `HttpClient.send` answers an interrupt by throwing — so
a caller that goes away takes its request with it. The leak the old wording feared, "a future that
keeps running invisibly is a leak even when nothing is waiting on it", is a failure mode the wrong
coroutine shape has as readily as a future does.

**The test asserts the elapsed time, not merely the outcome.** A cancellation test that checks only
"the caller did not complete" passes against the slow shape, because the caller does not complete
either way — it simply finds out thirty seconds late.

The per-request timeout stays on the `HttpRequest` regardless, since a server that accepts a
connection and then says nothing is not cancellation's problem to solve.

**NFR-4 — Server data refreshes on request and on connection change, never on a timer, and stale data
says so.** This restates the parent specification's own rule rather than adding one: "Server data
refreshes on request and on connection change — never on a timer." Any cache the reader keeps in front
of a fetch — reasonable, so a tool window redraw does not refetch — is invalidated by exactly those two
events and nothing else, in particular never by elapsed time and never by a repository file being
saved. Where a view shows server data it knows may be older than the current connection state, it
labels it stale rather than silently reusing it or silently refetching without being asked.

**NFR-5 — Credentials never appear outside `PasswordSafe` and the in-flight request.** No log line, no
exception message, no test fixture, and no cache entry carries a password. `SolrConnection.toString()`
already cannot leak one, since the type has no password field (`SolrConnectionSettings.kt:24-29`); this
requirement extends the same guarantee to whatever request-building or error-formatting code this
specification adds — an error message built from a full request URL must not include Basic Auth
credentials that some HTTP client implementations embed in a `user:pass@host` authority component,
which this plugin's requests must never construct in the first place.

**NFR-6 — Version handling degrades exactly as the parent specification already states.** "Unknown
fields in a response are ignored, unknown values are shown rather than rejected, and an unrecognized
server version is reported rather than refused." Concretely: a field property present in a schema
response but absent from `SolrField`'s typed accessors is not an error — it is exactly the situation
`SolrField.attributes` exists for (`SolrSchemaTypes.kt:88-90`), and the server parser should populate it
the same way the schema parser does, rather than silently dropping anything it does not have a named
property for.

**NFR-7 — The IDE's proxy and certificates reach the client through the JVM defaults it already
installs; the one thing that does not is proxy authentication.** A developer who has told IntelliJ
about their corporate proxy, or accepted their employer's internal certificate authority once, has
told *the IDE*. What a plugin must do to honour that turns out to be almost nothing — and the almost
is the part worth specifying.

**Measured on the bundled JBR 25 this plugin runs on, not read from javadoc**, because two of the
three answers are not what the API's shape suggests:

| Concern | Reaches an unconfigured `HttpClient`? | Evidence |
|---|---|---|
| Which proxy | **Yes** | A client built either way contacted a fake proxy installed via `ProxySelector.setDefault` for a request to an unresolvable host. Note `client.proxy()` still reports `Optional.empty` — the selector is consulted per request, so the getter is not the answer |
| Which certificates | **Yes** | `HttpClient.newBuilder().build().sslContext() == SSLContext.getDefault()` is `true` |
| Proxy credentials | **No** | Against a 401 challenge, the client returned 401 and `java.net.Authenticator.getDefault()` was never consulted. `HttpClient` ignores the JVM default authenticator by design |

The IDE fills both defaults: `CertificateManager` calls `SSLContext.setDefault` and is the only class
in the distribution calling `HttpsURLConnection.setDefaultSSLSocketFactory`, and
`JdkProxyProvider$Companion` and `OverrideDefaultJdkProxy` call `ProxySelector.setDefault`.

**So the requirement is mostly a prohibition: do not set `proxy` or `sslContext` on the builder.**
Passing the IDE's selector or context explicitly would be a dependency taken for something already
true, and for the proxy it would mean an `@ApiStatus.Internal` class — `JdkProxyProvider` — reached
for no gain. That the IDE's own certificate flow comes with it is the point: an internal Solr behind a
self-signed certificate raises the same *accept this certificate?* dialog as everywhere else in the
IDE, through `ConfirmingTrustManager`, and the answer is remembered.

**One consequence looks like a bug and is not**: that trust manager blocks the calling thread while it
asks, so the first request to an untrusted host does not return until the developer answers. It is a
second reason [NFR-2](#requirements)'s off-the-EDT rule is load-bearing rather than stylistic.

**Proxy authentication is the one gap, and it takes the public API rather than the internal one.**
Where the configured proxy demands credentials, the client needs an explicit `authenticator`, since
the JDK will not consult the default. `ProxyAuthentication` carries no API-status annotation — it is
public platform API — and is the right source; `JdkProxyProvider.getAuthenticator()` would serve too
and is internal, so it is not used. A plugin that skips this works everywhere except behind an
authenticating proxy, which is the configuration most likely to be corporate and least likely to be
diagnosable from what the plugin reports.

**What the ecosystem does, which is how this was found.** Across the 1,352 bundled plugin jars in the
2026.2 distribution, **no plugin references `JdkProxyProvider`** — the class purpose-built for handing
JDK-shaped proxy objects to a JDK client. Twenty-nine use `com.intellij.util.io.HttpRequests`, the
platform's own HTTP facade, which itself references neither the proxy settings nor
`CertificateManager`: it does not need to, for the same reason this plugin does not. An earlier
revision of this requirement reasoned from the JDK's documented defaults, concluded the IDE's proxy
would be missed, and specified an internal API to avoid it. The conclusion was wrong, and the way it
was wrong is the argument for measuring: everything about the API's shape suggested otherwise, and
`proxy()` returning `Optional.empty` would have confirmed it to anyone who checked the getter instead
of the behaviour.

**NFR-8 — Progress is visible and failure is inline; neither is a modal.** Step 12 already forbids a
popup for an unreachable server and requires Solr's own message inline, once. That rule is extended to
every surface that touches a server rather than left to the tree, because a user who learns that a
failed fetch appears in the tree and then meets a modal dialog from the drift view has learned nothing
transferable.

Concretely, and following what Database Tools does while introspecting: work runs in the background
with progress in the status bar, the node or view being loaded shows it is loading, and a failure
becomes an inline error carrying [FR-8](#requirements)'s verbatim Solr message. A dialog is reserved
for the one thing that genuinely needs one — a confirmation before a write, per
[FR-11](#requirements) — so that a dialog appearing means something is about to change.

## Testing Strategy

Two tiers, matching Step 11's action 5 and action 6 exactly, plus the boundary contract test NFR-1
requires and was not itself named as a plan action.

| Tier | What it proves | Base class |
|---|---|---|
| Pure JSON → `SolrConfigsetFacts` mapping | The parser is correct in isolation, against crafted response bodies for every row in [FR-5](#requirements)'s table | Plain JUnit 4, no platform import — same convention as `SolrSchemaParser`'s own tests |
| Fake HTTP layer | Success, timeout, authentication failure, malformed response, and an unrecognized server version — the five states Step 11 names, none of which a real server produces reliably on demand. An embedded `com.sun.net.httpserver.HttpServer` needs no new dependency and can simulate all five | Plain JUnit 4 |
| Fake HTTP layer, the two states verification added | **A 404 carrying an HTML body**, which is what a mistyped collection name actually produces, and **an HTTP 200 whose `responseHeader` sets `partialResults`**. Both are called out separately from the row above because neither is a state a fixture author invents unprompted — the first looks like it should be JSON and the second looks like success, and [FR-8](#requirements) now requires distinct handling for each | Plain JUnit 4 |
| Analyzer component spelling | A field type whose chain names its factories under `name` and one that names them under `class` both produce the same `SolrAnalyzerComponent` list, on **both** sides — the JSON reader this document specifies and the XML parser, which reads both spellings since the finding below. The cross-spelling case is the assertion that matters: `name` on one side and `class` on the other, denoting the same factory, must not read as a disagreement | Plain JUnit 4 |
| Standalone versus SolrCloud | A server reporting `mode: "std"` is asked for cores and never for collections, and one reporting `"solrcloud"` the reverse. Worth a Testcontainers case per mode rather than a fake, since the thing being tested is that a real standalone Solr's refusal never reaches the user as an error | Testcontainers |
| Contract test per supported line | The reader parses what a real Solr of that line actually returns — the wire-format risk a fake cannot cover, per the parent specification's own reasoning for requiring this tier | Testcontainers, `solr:10.0.0` and `solr:9.10.1`, pinned by tag never `latest`; started and stopped by the test itself, satisfying the standing rule that no automated test needs a Solr a developer started by hand |
| `SolrConnectionSettings` | Persistence and PasswordSafe round-trip | `SolrConfigsetTestCase`, per the existing rule for anything touching persistent connection or configset settings |
| Pairing persistence | A pairing round-trips through the workspace state with its root path macro-collapsed; removing a connection removes its pairings; a configset with none produces no server read at all — the silence being the assertion worth having, per [FR-12](#requirements) | `SolrConfigsetTestCase`, since it touches the same persistent settings |
| Client construction | The builder sets **no** `proxy` and **no** `sslContext`, per [NFR-7](#requirements) — the JVM defaults the IDE installs are what should reach it, and setting either would take a dependency for something already true. Asserted as absence, which is the only form this can take: a test cannot conjure a proxy the IDE configured. An authenticator *is* set where one is available | Plain JUnit 4 if the builder is separable from the send, which is a reason to separate them |
| The editor model stays one-sided | `SolrConfigsetReader.modelFor` reports `REPOSITORY_ONLY` for every fact **with a connection configured and a pairing present** — the editor is unmoved by connection state, per [FR-13](#requirements). The assertion is worth more than it looks: it is the one that fails the day somebody wires a server half in "just for completion" | `SolrConfigsetTestCase` |
| Boundary contract | Only `org.apache.solr.ide.server` and the named allowlist import `org.apache.solr.ide.server`; every other package, discovered by walking the source tree, does not | Plain JUnit 4, or whatever `SolrDumbModeContractTest` itself uses, for consistency |
| `SolrFieldModel.of` with a real server half | The four agreement states populate correctly from two genuinely different `SolrConfigsetFacts`, not only the synthetic one-sided fixtures Step 3 already covers | Plain JUnit 4 |
| Version selection | `SERVER` outranks `CONFIGSET` outranks `DEFAULT`, and a model built with no server half still resolves exactly as it does today — a regression test for [FR-6](#requirements) that must not change existing behaviour when no connection exists | Plain JUnit 4 |

**Testcontainers is a new dependency**, absent from `libs.versions.toml` today. Adding it, and deciding
between a generic `GenericContainer("solr:<tag>")` with an HTTP wait strategy against
`/solr/admin/info/system` versus a purpose-built Solr module (none is officially maintained by the
Testcontainers project as of this investigation), is Step 11's first implementation action rather than
something this specification fixes further — the two supported image tags and the "started and stopped
by the test" constraint are what must not drift, not the exact container API used to satisfy them.

**The clean case is written first, matching the solrconfig specification's own testing discipline**: a
repository and a server that agree on every field must produce zero drift entries before the fixture
that disagrees is written, for the same reason a clean configset fixture is written before a flagged
one for an inspection — it is the assertion that the comparison stays silent when it should that a
suite reaching straight for the interesting case never makes.

## Risks

- **The version-selection fix (FR-6) touches `SolrFieldModel`, which every documentation and completion
  surface in the Editor track already reads.** Landing it as its own commit, gated by the regression
  test in the table above, follows the same argument the solrconfig specification made for widening the
  descriptor gate in its own commit: the schema suite (here, the whole Editor track's existing test
  suite) is what can catch a mistake, and it can only do that while nothing else in the same commit
  could also be the cause.
- **The repository parser's `name`/`class` gap (recorded at [FR-5](#requirements)) was the largest
  single risk this document carried, and it is closed on the repository side only.** The parser reads
  both spellings and the catalog resolves between them, so the half that was an Editor-track defect in
  shipped code is fixed and guarded. What is not yet built is the server half: the reader must resolve
  the spelling it receives through the same catalog, and Step 14's comparison must be written against
  resolved factories rather than against the strings. The failure this now guards against is no longer
  discovering the gap from the drift view's first screenshot; it is reintroducing it one layer over, by
  comparing what the two sides happened to write.
- **Every response shape and both write formats are now verified against real servers rather than
  inferred from Reference Guide prose**, which retires the risk the original draft carried here.
- **The undeclared platform dependency on Jackson 3 ([FR-2](#requirements)) is a standing risk with a
  standing gate.** A future IDE may stop shipping it, and no ordinary build would say so. What catches
  it is `verifyPlugin` running against every entry in `verifiedIdeBuilds` in CI — the failure lands in
  the pull request that raises the IDE target, not in a user's editor. If that gate is ever weakened,
  this decision is to be revisited rather than inherited.
- **The verification used the `sample_techproducts_configs` and `_default` configsets, which are what
  Solr ships rather than what users write.** Every shape claim in FR-5 held identically across two Solr
  lines and two configsets, which is good evidence for the shape and no evidence at all about the
  variety of real-world schemas — a configset using a factory this pass never exercised may still carry
  a key the mapping table does not mention. The `attributes` map absorbing unrecognized keys is what
  keeps that from being a correctness problem, and is a reason not to replace it with a fixed set of
  named properties later.
- **Cancellation (NFR-3) is asserted as a requirement before this investigation verified the JDK
  actually honours it end-to-end.** If `HttpClient`'s `CompletableFuture.cancel()` proves not to abort
  the socket exchange on the platform's bundled JDK, the requirement still holds and the mechanism
  changes — a manually interruptible wrapper around the blocking `HttpClient.send()` on a background
  thread pool is the fallback, at the cost of one thread per in-flight request instead of the
  reactor-style model `sendAsync` implies.

## Open Questions

**None.** All seven the first draft carried are closed, and their answers live where they are used
rather than in a list that would have to be read alongside the requirement it qualifies.

| Question | Where the answer is | How it was settled |
|---|---|---|
| Which JSON parser | [FR-2](#requirements) | A throwaway plugin compiled against Jackson 3, Jackson 2 and Gson undeclared, then run through `verifyPlugin` |
| Does the full-schema endpoint carry `dynamicFields` | [FR-5](#requirements) | Counts compared against the per-kind endpoints on both lines |
| The analyzer chain's JSON shape | [FR-5](#requirements) | Read from real responses; the `name`/`class` fork found here became a defect fix in the Editor track |
| Whether `copyFields` `dest` is ever an array | [FR-5](#requirements) | 17 entries inspected on both lines; repeated `source` is how several destinations arrive |
| The path to the running Solr version | [FR-6](#requirements) | `lucene.solr-spec-version`, confirmed on both lines |
| Whether HTTP 200 can carry a non-zero status | [FR-8](#requirements) | Error codes mirror; the states that *do* surprise are a non-JSON 404 body and `partialResults` on a 200 |
| Upload and reload's wire format | [FR-10](#requirements) | Performed against a live SolrCloud and verified past the status code |

**One question was opened and closed by the same pass, which is worth recording so nobody reopens it.**
After the analyzer-spelling defect was found in `SolrSchemaParser`, the obvious follow-up was whether
`SolrConfigParser` shares the assumption. It cannot. Solr resolves solrconfig.xml plugins through
`AbstractPluginLoader`, which reads `node.attrRequired("class", type)` and instantiates by class name
with no alternative spelling; `name` there is the registered name or request path — `name="/select"` —
and never a factory. The SPI fork exists in exactly one place in `solr-core`,
`FieldTypePluginLoader`, for the schema's char filters, tokenizers and filters, where it calls
`CharFilterFactory.forName(name, params)` when a `name` is present. Every other `forName` in that
module is `Class.forName` or `Charset.forName`. **There is no second copy of this defect to find.**

## Verification provenance

Every claim in this document marked *verified* comes from one of two passes, both against containers
started for the purpose and torn down after. It is recorded because a wire-format claim is only as good
as the thing it was read from, and a future reader deserves to know which Solr said so.

### The wire-format pass, 2026-08-19

| | |
|---|---|
| Images | `solr:10.0.0` and `solr:9.10.1` — the two lines `supportedSolrLines` declares (`build.gradle.kts:269-272`), pinned by tag |
| Standalone | both images, `solr-precreate techproducts /opt/solr/server/solr/configsets/sample_techproducts_configs` — chosen over `_default` because it is the shipped configset with copy fields, dynamic fields and analyzer chains in it, which is what three of the closed questions were about |
| SolrCloud | `solr:10.0.0` run as `solr -f -c`, with a two-shard `drift` collection created through `CREATE`, since the Collections and Config Sets APIs do not exist without it |
| Read | `/admin/info/system`, `/<c>/schema`, the four `/schema/<kind>` endpoints, `/admin/collections` (`LIST`, `CLUSTERSTATUS`, and an invalid action), `/admin/cores?action=STATUS`, `/admin/configs?action=LIST`, and `/select` with an unknown field, with `timeAllowed=1`, and against a collection that does not exist |
| Written | one `add-field-type` through the Schema API declaring its analyzer components with `class`, to establish whether Solr normalizes the spelling on read-back. It does not |

### The write-and-classpath pass, 2026-08-20

| | |
|---|---|
| Image | `solr:10.0.0`, started with `SOLR_MODE=solrcloud`, since the Config Sets API does not exist without it |
| Written | a configset uploaded twice — once as a raw `application/octet-stream` body, once as `multipart/form-data` — then read back through `/admin/zookeeper`, used to create a collection, and that collection's schema fetched. Reload issued through both `/admin/cores?action=RELOAD` and `/admin/collections?action=RELOAD` |
| Read | Solr's own Admin UI, from `/opt/solr/server/solr-webapp/webapp/js/angular/services.js` inside the image, for which admin actions a shipped client actually issues ([FR-7](#requirements)) |
| Read | `solr-core` 10.0.0 sources, already resolved by this build for Javadoc extraction, for how solrconfig.xml plugins are instantiated versus schema analysis components ([Open Questions](#open-questions)) |
| Compiled | a throwaway plugin referencing Jackson 3, Jackson 2 and `com.google.gson` with none of them declared, verified with `verifyPlugin` against `IU-262.8665.258` ([FR-2](#requirements)) |

### What neither pass established

Both exercised **no authenticated server**, so [FR-4](#requirements)'s preemptive-Basic requirement
remains Reference-Guide-sourced; and **no TLS**, so [NFR-7](#requirements)'s certificate reasoning is
unchanged by either. Both also ran against **Solr's own shipped configsets** rather than against
anything a user wrote, which is good evidence about response shape and no evidence at all about the
variety of real schemas — see the risk that says so.

## References

- [`specs/0002-solr-intellij-plugin.md`](0002-solr-intellij-plugin.md) — product intent; "Talking to
  Solr: plain HTTP, not SolrJ", "Connecting", "Browsing a server", "Querying", "Comparing the repository
  against the server", "Indexing test documents", and "Version handling degrades"
- [`specs/plans/0002-solr-intellij-plugin-plan.md`](plans/0002-solr-intellij-plugin-plan.md) — Steps 11
  through 15 own delivery status
- [`specs/0002-solrconfig-xml-intelligence.md`](0002-solrconfig-xml-intelligence.md) — the sibling slice
  this document's structure and several of its rules (silence over false positives, clean-fixture-first
  testing) are adopted from
- [`src/main/kotlin/org/apache/solr/ide/server/connection/SolrConnectionSettings.kt`](../src/main/kotlin/org/apache/solr/ide/server/connection/SolrConnectionSettings.kt)
- [`src/main/kotlin/org/apache/solr/ide/model/SolrFieldModel.kt`](../src/main/kotlin/org/apache/solr/ide/model/SolrFieldModel.kt),
  [`SolrConfigsetFacts.kt`](../src/main/kotlin/org/apache/solr/ide/model/SolrConfigsetFacts.kt),
  [`SolrReferenceGuide.kt`](../src/main/kotlin/org/apache/solr/ide/model/SolrReferenceGuide.kt)
- Apache Solr Reference Guide: [Schema
  API](https://solr.apache.org/guide/solr/latest/indexing-guide/schema-api.html), [Basic Authentication
  Plugin](https://solr.apache.org/guide/solr/latest/deployment-guide/basic-authentication-plugin.html),
  [Cluster and Node
  Management](https://solr.apache.org/guide/solr/latest/deployment-guide/cluster-node-management.html),
  [System Info
  Handler](https://solr.apache.org/guide/solr/latest/configuration-guide/system-info-handler.html)
