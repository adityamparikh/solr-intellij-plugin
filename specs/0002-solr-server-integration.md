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
- State plainly what the plugin may claim when repository and server disagree, and what it must not —
  the same lesson the Editor track paid for four times, applied here before the code exists rather than
  after a defect report.
- Draw the boundary CLAUDE.md already requires — nothing on the editor path contacts a server — as
  something a test enforces, not only something a reviewer remembers.
- Specify the two testing tiers Step 11 names (fake HTTP layer, Testcontainers contract test) precisely
  enough that both can be written before the reader that they gate.

## Non-Goals

- **The collections tree, query console editor, and document-authoring form.** Steps 12, 13 and 15 own
  their own UI design; this document specifies what they read from, not how they render it.
- **Discovering connection candidates from project code or framework configuration.** That is the Code
  track's recognizer interface (Step 18), a different mechanism entirely — a recognizer *offers* a
  candidate, this document is about what happens once one is confirmed into a real
  [`SolrConnection`](../src/main/kotlin/org/apache/solr/ide/server/connection/SolrConnectionSettings.kt#L24-L29).
  Demo step 35 exercises both together; this specification is responsible for the second half only.
- **SolrCloud cluster administration** beyond what the plan names: browsing topology, uploading a
  configset, reloading a collection. Creating or deleting a collection, changing replication factor,
  and cluster-property management are out of scope until a step asks for them.
- **Query relevance grammar and the scoring-explanation tree's rendering.** Step 13 owns the console;
  this document specifies only that field completion in it reads the same model as the editor.
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

## Requirements

### Functional

**FR-1 — HTTP transport.** The plugin uses `java.net.http.HttpClient` for every call to Solr. This adds
no dependency — the toolchain is already pinned to JDK 21
(`build.gradle.kts:22-26`) — and it natively supports what the non-functional requirements below need:
per-request timeouts (`HttpRequest.Builder.timeout`), asynchronous execution
(`HttpClient.sendAsync` returning a `CompletableFuture`), and TLS with no extra configuration for a
server behind HTTPS. **This is a recommendation to verify, not an assumed fact about IntelliJ's
runtime** — the parent specification's own precedent for platform-API uncertainty is "the exact
platform APIs... must be verified during implementation rather than assumed here," and the same
caution applies to a JDK API used inside a plugin classloader for the first time.

**FR-2 — JSON handling.** Solr's wire format is JSON, and the parent specification already commits to
"hand-written request and response handling instead of typed objects" over embedding a client library.
This document does not resolve which JSON *parser* backs that hand-written handling — whether a
library already present on the IntelliJ Platform's runtime classpath (several plugins use
`com.google.gson` without declaring it, because the platform itself depends on it) is safe to reference
undeclared, or whether the shallow, uniformly-shaped responses this plugin actually reads (verified
below) are cheap enough to walk with a small hand-written reader the way `SolrSchemaParser` hand-parses
XML with the JDK's DOM rather than a binding library. **Recorded as [open question 1](#open-questions)
rather than decided here**, because it is exactly the kind of platform-dependency assumption the
solrconfig specification's own precedent says to verify before relying on.

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
whenever `baseUrl` is `https://`; nothing in this specification adds certificate-pinning or a custom
trust store, which is left to the JDK's platform trust store unless a future step asks otherwise.

**FR-5 — The server reader produces a `SolrConfigsetFacts`, not a new type.** This is the specification's
central design decision, and it follows directly from `SolrConfigsetFacts`'s own KDoc: it is
deliberately "what a *parser* produces," shaped so the same type serves both sources
(`SolrConfigsetFacts.kt:10-15`). The server reader is therefore a second parser — of JSON rather than
XML — with the same contract `SolrSchemaParser` already has: pure input to `SolrConfigsetFacts` output,
testable with no IDE fixture. **`<collection>` is not something the reader works out** — it comes from
the pairing [FR-12](#requirements) defines, and a configset with no pairing is not read at all.
Concretely, against the Schema API's full-schema endpoint (`GET
/<collection>/schema`, verified against the Reference Guide's Schema API page, wrapping the schema
under a `"schema"` key with `fields`, `dynamicFields` *(via `/schema/dynamicfields`, not present on the
full-schema response's top level per the same page — verify per [open question
2](#open-questions))*, `fieldTypes`, `copyFields`, `uniqueKey` and a numeric `version`):

| `SolrConfigsetFacts` property | Server source | Note |
|---|---|---|
| `fields` | `schema.fields[]` | `indexed`/`stored`/`docValues`/`multiValued` map directly; an absent key means *unset*, matching the repository parser's own null-means-unset convention (`SolrSchemaTypes.kt:76-77`) — not `false` |
| `dynamicFields` | `schema.dynamicFields[]`, each `name` is the pattern | |
| `fieldTypes` | `schema.fieldTypes[]` | `class` → `className`; the analyzer chain (`analyzer`/`indexAnalyzer`/`queryAnalyzer`) is present but its exact JSON shape is unverified — see [open question 3](#open-questions) |
| `copyFields` | `schema.copyFields[]` | `source`/`dest` — **whether `dest` is ever an array bundling several destinations under one entry, requiring expansion into several `SolrCopyField` facts, is unverified** — see [open question 4](#open-questions) |
| `uniqueKey` | `schema.uniqueKey` | |
| `schemaVersion` | **not populated by this parser** | The server reports one, and nothing would read it: `SolrFieldModel.of` takes `SolrSchemaVersion.of(repo.schemaVersion)` — the repository half, deliberately, because "the schema version is a property of the file the user is editing" (`SolrFieldModel.kt:207-209`). Populating it would create a value that looks consumed and is discarded on the next line, which is the mistake the element catalog's `valueType` column already made and paid for. If a future step wants to show a schema-version disagreement it must change `of` first, and that is a change to argue for rather than to arrive by accident. **Note also that `SolrConfigsetFacts.schemaVersion` is a `String?` "exactly as written" (`SolrConfigsetFacts.kt:26-28`), not a `Float`** — an earlier draft of this row claimed the opposite and inferred from it that no string round trip was needed |
| `fieldReferences` | always empty | Already documented on the type: "always empty for a server, which reports its configuration rather than the file that produced it" (`SolrConfigsetFacts.kt:22-23`) |
| `luceneMatchVersion` | **not populated by this parser** | See [FR-6](#requirements) — the server's own version is a different fact, carried differently |

**FR-6 — The server's reported Solr version becomes a new, distinct fact, not a value for
`luceneMatchVersion`.** `luceneMatchVersion` names a *Lucene* back-compat target the configset
declares; a connected server reports its own running *Solr* version directly, with no Lucene-version
translation needed at all — `fromLuceneMatchVersion` exists specifically because a configset only ever
*implies* a line, where a server *is* one. The parent specification's
[factory catalog section](0002-solr-intellij-plugin.md#the-factory-catalog) is where the three-tier
order is stated; an earlier draft of this sentence linked an anchor that does not exist in that
document, which is the failure its own "a dead link is worse than no link" rule names. Verified
against the Reference Guide's System Info Handler page: the endpoint (`/admin/info/system`, called out
by that page's title even though the exact JSON key path was not confirmed by this investigation — see
[open question 5](#open-questions)) reports a `lucene.solr-spec-version` field.

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

**FR-7 — Collections, cores, shards, replicas and aliases read from the Collections API.** Verified
against the Reference Guide's Cluster and Node Management page: `CLUSTERSTATUS`
(`/admin/collections?action=CLUSTERSTATUS`, v1, or `GET /api/cluster`, v2) reports collections, shards,
replicas, configset names and per-shard health in one response, and collection listing has its own
narrower action, `LIST` (`/admin/collections?action=LIST`, v1, or `GET /api/collections`, v2). This
document takes no position on v1 versus v2 beyond noting that v1's query-parameter form is the one this
investigation could verify content for; the exact response shape for either should be pinned by the
Testcontainers contract test in the same pull request that reads it, rather than assumed from the
handler's name.

**FR-8 — Failures surface as Solr's own message, matching the parent specification's promise that
"Solr's own error messages are shown rather than rewritten."** Verified pattern from a non-zero-status
response: `responseHeader.status` is non-zero and `error.msg` carries the human-readable text, `error.code`
the numeric code. A reader function therefore returns a result type distinguishing at least: success
with a value; a Solr-reported error, carrying `error.msg` verbatim for display; a transport failure
(timeout, connection refused, TLS failure), described in terms of what happened rather than Solr's
vocabulary, since Solr said nothing; and a response that parsed as JSON but not into the shape expected
— reported as "unrecognized," per the parent specification's version-degradation rule, rather than
thrown as an internal error. **Whether a request can ever receive HTTP 200 with a non-zero
`responseHeader.status` is unverified** and affects whether status must be read from the body even on a
successful-looking transport response — see [open question 6](#open-questions).

**FR-9 — Disagreement is rendered, not resolved.** The drift view (Step 14) reads
`SolrFieldModel.disagreements` (`SolrFieldModel.kt:167-170`) and the per-fact `agreement` property
(`SolrFieldModel.kt:53-60`) exactly as they exist today — this specification adds no new agreement
state and no per-property diff. A `DISAGREEING` fact is shown with both its `repository` and `server`
values visible; **the view must never call `.effective` when rendering drift**, because `effective`
exists for single-source consumers that need one answer to display inline (an inlay hint has one line
to fill) and silently prefers the repository — using it in the one view whose entire purpose is
showing that the two disagree would hide the disagreement in the exact place it is meant to be shown.

**FR-10 — Upload and reload re-fetch rather than assume.** After a configset upload or a collection
reload (Step 14, action 2), the drift view must invalidate its held server facts and issue a fresh read
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

**What this does not settle** is where the chooser lives — a dialog from the drift view, a field on the
connection, an action on the configset root in the project tree. That is Step 12's or Step 14's UI
question, per this document's non-goals. The triple, its persistence, and the prohibition on inferring
it are what four steps need agreed before any of them starts.

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

**NFR-2 — Nothing here blocks the UI thread; every call is asynchronous.** `HttpClient.sendAsync`
already returns a `CompletableFuture` rather than blocking the calling thread; the requirement is that
nothing in the reader or its consumers calls `.get()` or `.join()` on that future from an
EDT-dispatched code path. Whether the surrounding coroutine or callback plumbing uses
`kotlinx.coroutines` (already present on the IntelliJ Platform's own runtime classpath, used
extensively by platform services) or the platform's own background-task APIs is an implementation
choice to verify during Step 11 rather than one this document fixes — the requirement is the outcome
(async, off the EDT), not the mechanism.

**NFR-3 — Every request is timeout-bounded and cancellable.** A per-request timeout (proposed default:
ten seconds, overridable per call for the console's potentially slower queries) is set on every
`HttpRequest`. A view that no longer needs a pending request — the user closed the tool window,
navigated away, or issued a newer query that supersedes an older one — must be able to cancel it rather
than let it complete and be discarded; `CompletableFuture.cancel()` is the mechanism `sendAsync`
exposes, and whether cancellation actually aborts the underlying socket exchange on the JDK version this
plugin targets, rather than merely detaching the caller, needs verifying against a real slow-response
fixture before this requirement is considered closed — a future that keeps running invisibly is a leak
even when nothing is waiting on it.

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

## Testing Strategy

Two tiers, matching Step 11's action 5 and action 6 exactly, plus the boundary contract test NFR-1
requires and was not itself named as a plan action.

| Tier | What it proves | Base class |
|---|---|---|
| Pure JSON → `SolrConfigsetFacts` mapping | The parser is correct in isolation, against crafted response bodies for every row in [FR-5](#requirements)'s table | Plain JUnit 4, no platform import — same convention as `SolrSchemaParser`'s own tests |
| Fake HTTP layer | Success, timeout, authentication failure, malformed response, and an unrecognized server version — the five states Step 11 names, none of which a real server produces reliably on demand. An embedded `com.sun.net.httpserver.HttpServer` needs no new dependency and can simulate all five | Plain JUnit 4 |
| Contract test per supported line | The reader parses what a real Solr of that line actually returns — the wire-format risk a fake cannot cover, per the parent specification's own reasoning for requiring this tier | Testcontainers, `solr:10.0.0` and `solr:9.10.1`, pinned by tag never `latest`; started and stopped by the test itself, satisfying the standing rule that no automated test needs a Solr a developer started by hand |
| `SolrConnectionSettings` | Persistence and PasswordSafe round-trip | `SolrConfigsetTestCase`, per the existing rule for anything touching persistent connection or configset settings |
| Pairing persistence | A pairing round-trips through the workspace state with its root path macro-collapsed; removing a connection removes its pairings; a configset with none produces no server read at all — the silence being the assertion worth having, per [FR-12](#requirements) | `SolrConfigsetTestCase`, since it touches the same persistent settings |
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
- **Open questions 2 through 6 are all response-shape details this investigation could not verify from
  the Reference Guide's prose alone.** None of them block Step 11 from starting — the transport,
  credential handling, and collections/cluster reading do not depend on any of them — but FR-5's field,
  dynamic-field and copy-field mapping should not be considered final until a real response (ideally the
  Testcontainers fixture, reached early rather than saved for last) has been read.
- **Cancellation (NFR-3) is asserted as a requirement before this investigation verified the JDK
  actually honours it end-to-end.** If `HttpClient`'s `CompletableFuture.cancel()` proves not to abort
  the socket exchange on the platform's bundled JDK, the requirement still holds and the mechanism
  changes — a manually interruptible wrapper around the blocking `HttpClient.send()` on a background
  thread pool is the fallback, at the cost of one thread per in-flight request instead of the
  reactor-style model `sendAsync` implies.

## Open Questions

1. **Which JSON parser.** Whether `com.google.gson` (or another library already on the IntelliJ
   Platform's runtime classpath) is safe to reference without a declared dependency, or whether a small
   hand-written reader over the shallow, uniformly-shaped responses this plugin actually needs is the
   better match for the "hand-written request and response handling" trade the parent specification
   already commits to. Settle in Step 11's first pull request, verified against a compiled build rather
   than assumed.
2. **Whether the full-schema endpoint's response actually nests `dynamicFields` at the top level**, or
   whether reading it requires the separate `/schema/dynamicfields` endpoint as a second request. The
   Reference Guide's Schema API page documents both a full-schema endpoint and per-kind partial
   endpoints; whether the full one is complete or a convenience summary was not resolved by this
   investigation and changes whether the server reader is one request or several.
3. **The exact JSON shape of an analyzer chain (`analyzer`/`indexAnalyzer`/`queryAnalyzer`) inside a
   `fieldTypes` entry**, needed to populate `SolrFieldType.indexAnalyzer` / `queryAnalyzer`
   (`SolrSchemaTypes.kt:57-62`) — specifically, whether tokenizer and filter factory class names arrive
   under the same `class` key the schema XML uses, or under a differently-cased or differently-nested
   key in the JSON representation.
4. **Whether a `copyFields` entry's `dest` is ever a JSON array bundling multiple destinations under one
   `source`**, which would require expanding one server-side entry into several `SolrCopyField` facts to
   match the repository parser's one-source-one-destination shape (`SolrSchemaTypes.kt:141-145`).
5. **The exact JSON path to the running Solr version inside the System Info Handler's response** — the
   search evidence found points at a `lucene.solr-spec-version` field but this investigation did not
   fetch and read the endpoint's actual documented response body to confirm the key's nesting, which
   [FR-6](#requirements) depends on directly.
6. **Whether Solr ever returns HTTP 200 with a non-zero `responseHeader.status`** — if so, [FR-8](#requirements)'s
   result type must check the body's status on every response regardless of the HTTP status code; if
   Solr always mirrors `error.code` into the HTTP status line, checking the HTTP status is sufficient
   and cheaper.
7. **Upload and reload's exact wire format** — the Config Sets API's upload action and the Collections
   API's `RELOAD` action were not independently verified by this investigation beyond the general
   `/admin/collections?action=X` and `/admin/configs?action=X` shape [FR-7](#requirements) confirms for
   reading; the zip-upload multipart format in particular should be pinned by the Testcontainers
   contract test before Step 14 is considered done, not assumed from the action name.

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
