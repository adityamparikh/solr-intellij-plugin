# A query console with the Admin UI's form, run by the plugin

## Problem

Querying a collection from the IDE means writing an HTTP request by hand. The HTTP Client URL-encodes
a request line, but it cannot know that an `&`, `+`, `#` or `%` belongs to a value rather than
separating two, so a query holding one of those has to be escaped by hand. The right-click menu in
the collections tree (#259) makes a starting request, but the result is still a hand-edited `.http`
file.

Solr's Admin UI has a query screen that does not have this problem: every parameter has its own
input, and each is encoded on its own. The owner asked for that screen in the IDE.

The specification already plans for it. Its *Querying* section wants "a console where field names
complete from the model, results render as a table … History is kept", and the plan's Step 13 records
that "the interactive console FR-16 keeps as its own surface does not exist". What the specification
does not yet allow is the console running a query itself (see *What changes in the specification*).

## What the Admin screen is

Read from Solr 10.0.0's own `partials/query.html` and `js/angular/controllers/query.js`, taken from
the `solr:10.0.0` image:

| Section | Parameters |
|---|---|
| common | Request-handler (`qt`), `q` (default `*:*`), `q.op` (AND/OR, default OR), `fq` (repeated), `sort`, `start`, `rows`, `fl`, `df`, paramsets (`useParams`), `wt` (json, xml, python, ruby, php, csv), `indent` (default on) |
| debugQuery | `debug.explain.structured` |
| defType (lucene / dismax / edismax) | `q.alt`, `qf`, `mm`, `pf`, `ps`, `qs`, `tie`, `bq`, `bf`; edismax adds `uf`, `pf2`, `pf3`, `ps2`, `ps3`, `boost`, `stopwords`, `lowercaseOperators` |
| hl | `hl.fl`, `hl.q`, `hl.qparser`, `hl.snippets`, `hl.fragsize`, `hl.encoder`, `hl.maxAnalyzedChars`, `hl.method` (unified / original / fastVector), and about 45 more whose visibility depends on `hl.method` |
| facet | `facet.query`, `facet.field`, `facet.prefix`, `facet.contains`, `facet.contains.ignoreCase`, `facet.limit`, `facet.matches`, `facet.mincount`, `facet.missing`, `facet.sort`, `json.facet` |
| spatial | `pt`, `sfield`, `d` |
| spellcheck | `spellcheck.q`, `.dictionary`, `.count`, `.build`, `.reload`, `.onlyMorePopular`, `.extendedResults`, `.collate`, `.maxCollations`, `.maxCollationTries`, `.accuracy` |
| raw query parameters | repeated `key=value` |

After a run it shows the request URL, which can be opened, and the response as the server sent it.

## Where the form's definition comes from

Like IntelliJ's Spring Initializr wizard, which draws a native form from a metadata document the
service publishes, the console draws its form from **a metadata file committed with the plugin**:
`solr-query-screen.json`, one entry per parameter, with its label, tooltip, kind, choices, default,
section, and the condition under which it shows.

Two other sources were checked and rejected:

- **Build time, from a Solr artifact.** Solr publishes no Admin UI to Maven: `solr-webapp`, `solr-ui`
  and `solr-admin-ui` all return 404 for 10.0.0. Generating from it would mean downloading the whole
  distribution.
- **Runtime, from the connected server.** A running Solr 10 serves `/solr/partials/query.html` (200,
  28 KB), but it is Angular markup, not an interface meant for tools, and Solr 10's Admin home
  already links a new UI. It is also absent wherever the Admin UI is turned off.

The file is transcribed once from Solr 10's page, checked against it while writing, and reviewed like
any other file. No test compares it with a committed copy of the page: two committed files cannot
detect a change upstream. Everything in the first release's scope (below) is identical on the 9.10
line, which the plugin also supports; the file says which line it was taken from.

## Decision

A **query console in an editor tab**, `Query: <collection>`, whose form is drawn from the metadata
file and whose queries are run by the plugin's own transport, with the results in the same tab.

It is FR-16's console, not a replacement for the Admin UI. The specification's non-goal ("Replacing
the Solr Admin UI … the plugin wins where it can see things the Admin UI cannot") still holds,
because the console earns its place through what the Admin UI cannot do: complete field names from
the collection, open in the editor where the code is, and hand a query to the HTTP Client to be saved
in the repository.

### Components

A new package, `server.query.screen`. The decisions are pure and tested with plain JUnit; the IDE
classes draw them.

| Piece | Job | IntelliJ types |
|---|---|---|
| `solr-query-screen.json` | The form's definition | — |
| `SolrQueryScreenSpec` | Reads it: sections (with an optional toggle parameter) and fields (`text`, `checkbox`, `choice`, `repeated`; `choices`, `default`, `tooltip`, `showWhen`, `completes: fields`) | No |
| `SolrQueryScreenState` | What is filled in, and the parameter list it produces | No |
| `SolrQueryScreenRequest` | Parameters to a request: the handler path, the form-encoded body, the GET URL shown | No |
| `SolrQueryScreenFile` | The tab's non-file identity: connection and collection, and the form state | Yes |
| `SolrQueryScreenEditor`, its `FileEditorProvider` | The form above, the results below | Yes |

### Execute

1. **Only visible fields are sent.** The Admin UI sends a hidden field's leftover value, for example
   `hl.tag.pre` after `hl.method` changed to `original`. The console does not.
2. **The handler must be a path** (`/select`, `/query`, or any handler `solrconfig.xml` declares).
   The Admin UI's other form, `/select` plus a `qt` parameter, is ignored unless the server sets
   `handleSelect=true`, which is off by default; the console refuses it with a message, not a request
   that silently runs the wrong handler.
3. **The request is a form-encoded POST.** Many `fq`, a `json.facet` or a large `bq` can pass Jetty's
   8 KB request-line limit as a GET. The GET URL is still shown, for reading and copying.
4. **`echoParams=all` is added**, not shown in the URL, so the response says what the server ran
   with, including what a paramset (`useParams`) or the handler's defaults added.
5. **The credential is read off the UI thread**, from `PasswordSafe`, like every other request.
6. **One run per tab.** A new Execute cancels the one still in flight, and closing the tab cancels it,
   as NFR-3 requires. The console's timeout is longer than the plugin's default ten seconds.

### What the transport gains first

`SolrHttpTransport` answers JSON only: a body that is not JSON becomes `Unrecognized`, and a Solr
error keeps its message but not its body. A console must show what the server sent, so the
transport gains:

- **a raw answer** (status, content type, body), with today's JSON classification built on top of it,
  so the drift view, the collections tree and the indexer behave exactly as before;
- **a per-call timeout**, which NFR-3 already asks for ("overridable per call for the console's
  potentially slower queries");
- **cancellation from the caller**, through the coroutine the request runs in.

### Results

- **The request line**, the GET URL with any `user:pass@` in the connection's address removed.
  Clicking it opens the same request as a scratch `.http` file.
- **The summary** that `SolrQueryResultRenderer` already writes above an HTTP Client response:
  matches, time, and the documents as a table. Only for a JSON answer.
- **The raw response**, exactly as sent, in a read-only viewer: JSON, XML, CSV or an error page.
- **Structured explanations** (`debug.explain.structured`) are read as well as flat ones.
  `SolrQueryResultReader` keeps only string explanations today and would show nothing.

**A section's inputs arrive with its output.** Facet counts, highlighting and spellcheck
suggestions have no display yet. Until each has one, its parameters go through raw parameters and
its answer is read in the raw response. Adding dozens of inputs whose effect nobody can see would be
the Admin UI's breadth without the reason for it.

### The tab

- **One per connection and collection.** Opening it again, from the tree or anywhere, focuses the
  open tab rather than adding another.
- **State lives on the file**, not in the editor, so *Split Right*, which creates a second editor for
  the same file, shows the same form.
- **Not restored after a restart.** A light file is not reopened by the platform. Query history,
  below, is how a query comes back.
- **Closed when its connection is removed**, on `CONNECTIONS_CHANGED`.
- **Dumb-aware.** The provider implements `DumbAware`, its `accept` is a bare check of the file's
  class (it runs for every file the IDE opens), and it joins `SolrDumbModeContractTest`.
- **Ctrl+Enter** runs.

### Field completion

`fl`, `sort`, `df`, `qf`, `pf`, `hl.fl`, `facet.field` and `sfield` complete from the collection's
fields, through `SolrCollectionFields`. **Only an explicit Ctrl-Space reads the server**, which is the
rule `code-organization.md` records for the HTTP Client's completion: opening the tab reads nothing,
and a popup that opens while typing answers from what was already read or from the project's
configsets. `score`, `*`, `[docid]` and function queries are completed where they apply and never
reported.

### Saving a query

**Open as .http** writes the request into a scratch file as the committed templates do:
`{{solrUrl}}` and `{{collection}}`, never a host, and never an `Authorization` header or a
password. Moving that file into the project is how it becomes a saved query, which is FR-16
unchanged.

### The right-click menu

#259's items open the console instead of a scratch file, filled in by the same rules:
*Query products* fills `q=*:*`; *Explain Scoring* ticks `debugQuery`; *Find Documents with category*
fills `q=category:*` and `fl=id,category`; *Count Values of category* sends `facet=true` and
`facet.field=category` through raw parameters until the facet section exists. `SolrTreeQueries`
already decides which items a row offers, from the field's Luke flags.

### Query history

The specification asks for it. Each tab keeps the queries it ran, and a query can be put back into
the form. Per project, in the workspace file: history is one developer's, like connections.

## What changes in the specification and the docs

- **Integration spec, FR-1.** "A query somebody typed … is run by the IDE's HTTP Client" becomes: a
  *saved* query is run by the HTTP Client (FR-16); a query run from the console is the plugin's own
  traffic, bounded and cancellable under NFR-3.
- **Integration spec, FR-16.** Unchanged in substance: a saved query is still an `.http` file. It gains
  the console as the interactive surface its text already anticipates.
- **Plan, Step 13.** The console becomes the step's remaining work, delivered in the order below.
- **User guide.** *Running a query* no longer says there is "no query console of this plugin's own"
  (line 616), and the link at line 912 to a query console that does not exist becomes true.
- **Code organization.** `server.query` is described as "queries in `.http` files" (lines 124 and
  351); it gains the console.
- **Manual suite.** New checks for the tab, Execute, the raw response, cancellation, completion, and
  the right-click switch.

## Delivery

| PR | Contents | Visible |
|---|---|---|
| 1 | Transport: raw answer, per-call timeout, caller cancellation | Nothing; every existing view behaves the same |
| 2 | The console: metadata (common, debugQuery, defType with dismax/edismax, raw parameters), pure model, editor, Execute, results, completion, Open as .http; the specification and docs changes above | The `Query: <collection>` tab |
| 3 | The right-click menu opens the console | Right-click → the tab, filled in |
| 4 | Query history | A history list in the tab |
| 5+ | Facet, then highlighting, then spellcheck, each with its display | One section each |

Spatial and the method-specific highlighting parameters wait until someone needs them; raw
parameters carry them until then.

## Testing

- **Plain JUnit:** metadata parsing; visibility rules; the parameter list a state produces (hidden
  fields left out, `fq` repeated, raw parameters split on the first `=`); the handler path and the
  refusal of a bare `qt`; the form-encoded body and the GET URL, with escaping; `user:pass@`
  removed; `{{solrUrl}}` export with no credential; structured explanations read.
- **Platform tests**, in `SolrConfigsetTestCase` because the tab reads connection settings: the tab
  opens once per connection and collection; state survives a second editor; Execute renders a
  JSON answer as a table and an XML answer raw, through a fake transport; a second Execute cancels
  the first; removing the connection closes the tab; the provider in the dumb-mode contract.
- **Manual:** the new checks above, in a sandbox against the demo's `products`.
- **Gates:** the IDE classes are `internal`, so the documentation gate's surface stays the public
  model; the Swing class stays thin so coverage holds.

## Consequences

- The plugin runs queries a user typed, which it deliberately did not before. The transport work in
  PR 1 is what makes that honest: raw answers, a timeout the user can wait out, and cancellation.
- Two ways to query exist: the console for working interactively, `.http` files for what is saved
  and shared. **Open as .http** is the bridge, and the guide says which is for what.
- The metadata file is one more thing a new Solr line may change. Adopting a line means reading its
  query page against the file, the way the class catalog is regenerated.
