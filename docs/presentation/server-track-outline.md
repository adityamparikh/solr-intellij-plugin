# Act 4 — the Server track: a speaker's outline

> **Who this is for.** Someone building the server-track slides in PowerPoint or Keynote. This is
> content and structure, not a deck — the existing `solr-intellij-plugin.pptx` is a designed
> artifact with embedded screenshots, and new slides need building by hand in the same template.
>
> **What is already in the deck.** All of it, as Act 4 — slides 44 to 55: a divider carrying S1's
> framing, one deep dive per slide from S2 to S11 in the deck's own four-part layout, and a recap
> carrying S12. Every screenshot is embedded as the catalog file itself, byte for byte, so a reshoot
> replaces one file rather than a crop someone has to remake. Where a slide shows part of a capture,
> the crop is PowerPoint's own, made in the slide: S6 crops `25-query-and-results.png` to the answer
> while S5 shows it whole, S9 shows `20-drift-refused-payload.png` as two slices with the empty band
> between table and payload cut out, and S10 stacks a slice of each `21-drift-apply-*` frame. Change a
> slide here and rebuild the slide there; the two are kept in step by hand.
>
> **Screenshots.** All of them exist — entries 19 to 25, 30 and 31 of
> [the screenshot catalog](../screenshots.md). Most were shot during the 2026-09-26
> [manual pass](../manual-test-suite.md#pass-log) against a SolrCloud 10.0.0 container; 19, 23 and 31
> were reshot on 2026-09-28 against a real SolrCloud 9.10.1 on the final 0.2.0 build, in the light
> theme. Each slide below names its file; the catalog says what each must show, so re-shoot from there
> rather than re-framing by eye.
>
> **Status.** Release 0.2.0 ships this track complete: connections, the collections tool window,
> queries in the IDE's HTTP Client, the drift view with upload, reload and additive apply, and indexing
> a test document.

The Editor act works because each slide makes **one claim** and shows **one screenshot** proving it.
Keep that shape. What changes for this act is where the claims come from: the editor track's claims
are about what the plugin displays, and the server track's are mostly about **what it declines to
do**, and why — which is harder to photograph and needs the words to carry more.

---

## S1 · The framing — the second of three surfaces

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

**Screenshot.** `22-connections-settings.png` — the page with a connection open for editing, its password field empty and saying one is stored.

---

## S3 · Browsing a server — never guessing which vocabulary

**Claim.** Collections, shards and replicas for a SolrCloud server; cores for a standalone one. The
mode is read first and decides which endpoint may be asked at all.

**Why not just try.** A standalone Solr answers *every* `/admin/collections` request with HTTP 400
and "Solr instance is not running in SolrCloud mode". A reader that assumed the cloud vocabulary
would report a hard failure against a server that is working perfectly — and catching that 400 would
swallow the genuinely different one a malformed request produces.

**Screenshot.** `23-collections-topology.png` — a SolrCloud collection down to its leader replica,
each level's health as a coloured dot, and the Solr mark on the tool-window stripe that opens it.

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

**Screenshot.** `24-luke-fields.png` — `author_s ← *_s`, and `price_f` with no count.

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

**Screenshot.** `25-query-and-results.png`, whole — the `.http` file, the Services pane and the answer
in one frame, which is the claim: the plugin's contribution lives inside somebody else's tool.

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

**Screenshot.** `25-query-and-results.png` again, cropped in the slide to the answer — one capture holds both slides.

---

## S7 · Live fields — the collection a request is about to reach

**Claim.** <kbd>Ctrl-Space</kbd> inside a JSON request body offers the fields of the collection the
request line names, read from the selected connection, each labelled with where it came from —
`books · local`.

**The rule that decides when.** Only a completion the user asked for may contact the server. An
explicit invocation reads the collection's schema once; every popup after it, including the ones that
open while typing, reuses that read. A popup that opened by itself never sends a request — typing is
not asking. Where the collection or the server is unknown, the configsets answer as they always did.

**Why the collection outranks the configsets rather than joining them.** A field the collection does
not hold is one this query cannot use. `subtitle` is in the frame because it had just been added to
the server through the drift view's Apply and appears in no file in the project — the plainest
evidence the list came from the server.

**Capture it with Ctrl-Space, not a letter.** The popup that opens while typing shows the configsets
instead, which is correct and photographs a different claim.

**Screenshot.** `30-completion-live-schema.png` (SRV-16b).

---

## S8 · Drift — showing disagreement without resolving it

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

**Screenshot.** `19-drift-three-states.png`, reshot against a real SolrCloud 9.10.1 collection. It holds
all three states at once, the summary line resolving against "Solr 9.10", and under the table the
selected *Differs* row's refusal, reason first — the next slide's subject, already on screen. At the
captured width both `description` cells truncate to the same prefix, which is what the catalog warns
against; say so rather than letting the room read them as equal.

---

## S9 · The button that must not exist

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

**Screenshot.** `20-drift-refused-payload.png` (SRV-25 — an earlier revision of this line named SRV-20,
which is a different check), shown as two slices of the one file with the empty band cut. It is a
first-pass frame, so its summary line still carries the version label from before that was fixed;
S8's fresh capture shows the label as it now reads.

---

## S10 · A 2xx is not agreement

**Claim.** After any write, the plugin reads the collection back and reports *that*.

**Not a hypothetical.** A configset upload lacking `_version_` returns `responseHeader.status` 0,
appears in `action=LIST`, and Solr then refuses to build a collection from it. "The request was
accepted" and "the server now agrees" are different facts.

**Screenshot.** `21-drift-apply-before.png` and `21-drift-apply-after.png`, a slice of each stacked
before-over-after (SRV-26). Both are first-pass frames, with the same old version label as S9's.

---

## S11 · A test document, checked before Solr is asked

**Claim.** *Index a Test Document* opens on every field a user would fill, and refuses to send a
document Solr would accept and then regret.

**This exists because Solr will not tell you.** Against the `_default` configset, which is what a
collection created without one gets, a document naming a field the schema lacks returns `status: 0` —
the default update chain adds unknown fields to the schema, so one typo produced a field, a `_str`
companion and a copy-field directive between them, which the drift view then reported as three
server-only declarations the repository forgot. A document with no unique key also returns
`status: 0`, with a generated id nobody knows to look for. Both answer "did that work" with yes.

**What the document starts from.** The unique key and the required fields first, then every other
field a user would fill, in declared order, with placeholders that look like placeholders. Solr's
internal fields and copy-field destinations are left out, because a value supplied for either is not
what anyone meant. A document of only an id tests nothing about a schema.

**Screenshot.** `31-index-test-document.png` (SRV-28 and SRV-29), reshot on the final build: the
dialog prefilled with every field, and the line under the editor saying the document matches. Add
`"categry": "scifi"` and OK greys, with the reason in the same place.

---

## S12 · What the track cost, and what it taught

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
