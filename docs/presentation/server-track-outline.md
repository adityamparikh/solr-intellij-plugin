# Act 4 — the Server track: a speaker's outline

> **Who this is for.** Someone building the server-track slides in PowerPoint or Keynote. This is
> content and structure, not a deck — the existing `solr-intellij-plugin.pptx` is a designed
> artifact with embedded screenshots, and new slides need building by hand in the same template.
>
> **What is already in the deck.** Slides 36 and 37 were corrected when the server track shipped:
> the package layout, and the closing "in one sentence". Everything below is new.
>
> **Screenshots.** Every slide below wants one, and none exists yet. The captures are specified in
> [the screenshot catalog](../screenshots.md) and produced during a
> [manual pass](../manual-test-suite.md); an image joins a slide when someone shoots it.

The Editor act works because each slide makes **one claim** and shows **one screenshot** proving it.
Keep that shape. What changes for this act is where the claims come from: the editor track's claims
are about what the plugin displays, and the server track's are mostly about **what it declines to
do**, and why — which is harder to photograph and needs the words to carry more.

---

## S1 · The framing — three surfaces, and now two of them

**Claim.** The plugin read configuration files into one model. It now reads a live collection into
the *same* model, and shows where the two disagree.

**What it costs.** One rule, enforced by a test: nothing on the editor path may reach a server.
Configset editing still works with no connection configured, and never waits on one.

**Source.** `docs/code-organization.md` "The server surface"; `SolrServerBoundaryContractTest`.

---

## S2 · Connections — the state that must never be committed

**Claim.** A connection is a fact about one developer's machine, so it goes to the workspace file
and its password to the IDE's password safe, never to anything committable.

**The detail worth a slide.** `SolrConnection` has no password field at all. A secret that is never
in the serialized object cannot leak into the serialized file.

**The defect this caught.** `addConnection(connection, password = null)` wrote the password
unconditionally, so *save this connection* and *forget its password* were the same call. Every
caller was a test that either passed a password or worked on a connection that had none — nothing
could tell them apart until a settings page saved an existing connection. The argument's *presence*
now decides whether the secret is touched.

**Screenshot.** The connections settings page (SRV-1).

---

## S3 · Browsing a server — never guessing which vocabulary

**Claim.** Collections, shards and replicas for a SolrCloud server; cores for a standalone one. The
mode is read first and decides which endpoint may be asked at all.

**Why not just try.** A standalone Solr answers *every* `/admin/collections` request with HTTP 400
and "Solr instance is not running in SolrCloud mode". A reader that assumed the cloud vocabulary
would report a hard failure against a server that is working perfectly — and catching that 400 would
swallow the genuinely different one a malformed request produces.

**Screenshot.** The topology tree (SRV-4).

---

## S4 · What the index actually holds — the third view

**Claim.** A collection's **Fields** row shows what the index has, which is not what the schema
declares. `author_s` appears in no configset anywhere — the configset declares `*_s`, and the index
holds what matched it.

**Why it is not merged into the model.** The two-source model is symmetric because a configset and a
schema response describe the same thing. Folding Luke's answer in would make that false, and drift
would break first: every dynamic field's instances would read as server-only fields the repository
forgot to declare.

**Two shapes found by reading real responses**, either of which a hand-written fixture would have
got wrong:
- `"index": "(unstored field)"` — prose where flags belong. Decoded as flags, its punctuation
  manufactures properties out of nothing.
- A point field reports **no** document count even holding documents, having no inverted index to
  count from. "0 documents" would be false about exactly the field types Solr recommends.

**Screenshot.** An expanded Fields row (SRV-8).

---

## S5 · Queries — contributing to somebody else's editor

**Claim.** There is no query console of this plugin's own. A Solr query is an HTTP request, the IDE
ships a tool for authoring and running those from files in a repository, and what this plugin adds
is Solr's knowledge to it.

**The portability point.** Every template addresses `{{solrUrl}}`, never a host. A committed file
naming `localhost:8983` works only for whoever wrote it; the HTTP Client's environments are what let
a colleague clone the repository and point at their own server.

**The finding.** The extension point the specification named for field completion —
`customBodyInjector` — turned out to be about request *bodies*, while every shipped template was a
GET with parameters in the URL. And for a JSON body the HTTP Client already injects JSON, so what
was needed was a completion contributor over an injection that was already there.

**Screenshot.** A query and its rendered summary (SRV-13).

---

## S6 · Reading an answer, not a wall of JSON

**Claim.** Above the raw response: how many matched, how long it took, which window came back, a
table of documents, and — with `debugQuery` — each document's scoring explanation.

**One number that matters.** Matches and returned rows are stated separately, because conflating
them is how someone concludes their query found three documents when it found nine thousand and
showed three.

**Passed through, not rebuilt.** Solr returns the scoring explanation already indented. The nesting
*is* the information; re-parsing it to re-render would be work whose best possible outcome is what
Solr already wrote.

**Screenshot.** A `debugQuery` response (SRV-14).

---

## S7 · Drift — showing disagreement without resolving it

**Claim.** Three states: not deployed, only on the server, and differs — the last showing **both**
definitions side by side.

**The accessor the view must never call.** `SolrFact.effective` silently prefers the repository. It
exists for inline surfaces with one line to fill. Using it in the one view whose entire purpose is
showing that two sources disagree would hide the disagreement in exactly the place it is meant to be
shown — and would look completely correct doing so. Six tests fail if it is used.

**The trap underneath.** A model built with no server half reports *every* fact as repository-only,
which is indistinguishable from a server that genuinely has none of them. So the comparison takes two
halves as separate arguments rather than a model, which makes the mistake unspeakable rather than
merely discouraged.

**Screenshot.** All three states at once (SRV-19).

---

## S8 · The button that must not exist

**Claim.** Every drift row shows the Schema API request that would close it. Only additive rows
offer to send it.

**This is the slide the act is for.** Solr *accepts* a `replace-field` changing a field's type and
reports success. Verified against 10.0.0, on a `string` field holding `"abc"`, changed to `pint`:

| Request | Result |
|---|---|
| `q=code:42` — a document holds exactly that | `numFound: 0`, silently wrong |
| `q=code:abc` | `400 Invalid Number` |
| `q=*:*&fl=id,code` | **`HTTP 500`** — for every document, including ones that never had the field |

Nothing in Solr's answer to the write hints at any of it. Only a reindex makes the schema true
again, which this plugin cannot do and must not imply it can.

**Why show the payload anyway.** A greyed-out button with a tooltip is a weak answer to "why not".
Database Tools produces reviewable DDL rather than a silent mutation — what the tool would do is
something you read before deciding. So a refused row shows the reason first and the request under
it, and the user is left able to run it themselves against a collection they are prepared to
reindex. That is their call, not the plugin's to prevent.

**Screenshot.** A refused row with its payload pane (SRV-20).

---

## S9 · A 2xx is not agreement

**Claim.** After any write, the plugin reads the collection back and reports *that*.

**Not a hypothetical.** A configset upload lacking `_version_` returns `responseHeader.status` 0,
appears in `action=LIST`, and Solr then refuses to build a collection from it. "The request was
accepted" and "the server now agrees" are different facts.

**Screenshot.** The before/after pair (SRV-21).

---

## S10 · What the track cost, and what it taught

Three claims in the specification did not survive contact with a running Solr or a real IDE, and
each was found by checking rather than by a bug report:

- `verifyPlugin` was named as the gate protecting the HTTP Client integration. It reports
  **`Compatible`** with a fabricated extension point name — it verifies class API compatibility, not
  that a descriptor's extension points resolve. A contract test now does that.
- `customBodyInjector` was named as what makes field completion possible. It is about request
  bodies, and the templates were all URL-parameter GETs.
- The coverage gate is line **and branch** coverage of **changed lines**. Three pull requests were
  reported locally in the high eighties and arrived at the gate in the low seventies.

**The shape they share.** Each was a signal that looked like information and carried none. That is
the same failure the plugin itself is built to avoid — an inspection that fires on correct files, a
drift view that reports a schema as undeployed because nobody was asked, a green tick that cannot
distinguish running from skipping.
