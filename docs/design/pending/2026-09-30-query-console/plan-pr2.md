# Query console, PR 2: the console, and the setting that opens it — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `Query: <collection>` editor tab with Solr Admin's query form, run by the plugin's own transport, with the results in the same tab — opened from the Solr tool window's right-click menu when *Open queries in* says **Query console**, and nothing changed for anyone who leaves it at **HTTP Client**.

**Architecture:** A new package, `server.query.screen`. The decisions are pure and tested with plain JUnit: the form's definition (`SolrQueryScreenSpec`, read from a committed `solr-query-screen.json`), what is filled in and the parameters it produces (`SolrQueryScreenState`), the request those parameters make (`SolrQueryScreenRequest`), and what an answer amounts to (`SolrQueryScreenAnswer`). The IDE classes are `internal` and thin: a light file that is the tab's identity and holds the state (`SolrQueryScreenFile`), a project service that opens one per connection and collection and closes it when its connection goes (`SolrQueryScreens`), the editor and its provider, a Swing form drawn from the definition, and a results panel.

**Tech Stack:** Kotlin, IntelliJ Platform 2026.2 (`FileEditorProvider`, `LightVirtualFile`, `TextFieldWithAutoCompletion`, `EditorFactory` viewers), Jackson 3 tree model through `SolrJsonDocuments`, kotlinx.coroutines, JUnit 4, `BasePlatformTestCase` through `SolrConfigsetTestCase`, an embedded `com.sun.net.httpserver.HttpServer`.

**Spec:** [`design.md`](design.md) beside this file, as amended by #265; [FR-1, FR-16 and NFR-3](../../../../specs/0002-solr-server-integration.md#requirements).

**Scope:** PR 2 of the design's delivery table. History (PR 3) and the facet, highlighting and spellcheck sections (PR 4+) are not here.

## Global Constraints

- **Every public declaration in `src/main/kotlin` has KDoc**, or `dokkaGenerate` fails. The IDE classes are `internal`, so the documented surface is the pure model.
- **Kover's 80% line floor** holds: the Swing classes stay thin and are driven by platform tests.
- **Pure classes are plain JUnit 4** with `@Test` and backtick names; anything with PSI, settings or Swing extends `SolrConfigsetTestCase`, with `testSomething()` names.
- **Nothing on the editor path contacts a server.** Opening the tab reads nothing; only Execute and an explicit <kbd>Ctrl-Space</kbd> do.
- **No password is ever written** to a scratch file, and the credential is read off the EDT.
- **The default of *Open queries in* is HTTP Client**, and with it the right-click menu writes exactly the `.http` request it does today.
- **Commits** use conventional subjects and `git commit -s`, ending with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- **Build:** `./gradlew build` passes; check its exit status, not its output.

## Review Focus

1. **A choice left at the handler's default is not sent.** A handler whose `solrconfig.xml` defaults `defType=edismax` keeps edismax unless the user picks a parser. → Task 2, `a choice left at the handler default is not sent`.
2. **A checkbox on by default and turned off sends `false`** (`indent`, `stopwords`, `lowercaseOperators`), or turning it off would change nothing. → Task 2, `a checkbox on by default sends false when turned off`.
3. **An `&` in a raw line is part of its value.** One parameter per line, split on the first `=`. → Task 2, `an ampersand in a raw line is part of its value`.
4. **A body with `\r\n` line endings is shown**, not thrown at by a `Document` that refuses `\r`. → Task 6, `testABodyWithCarriageReturnsIsShown`.
5. **Two connections with the same collection name get a tab each**, and removing one connection closes only its tab. → Task 5, `testRemovingAConnectionClosesOnlyItsTabs`.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `src/main/resources/queryScreen/solr-query-screen.json` | Create | The form's definition, transcribed from Solr 10's query page |
| `server/query/screen/SolrQueryScreenSpec.kt` | Create | Reads the definition: sections, fields, kinds |
| `server/query/screen/SolrQueryScreenState.kt` | Create | What is filled in; visibility; the parameters it produces |
| `server/query/screen/SolrQueryScreenRequest.kt` | Create | Handler path, form body, shown GET URL, `.http` text; refusals |
| `server/query/screen/SolrQueryScreenAnswer.kt` | Create | What an answer amounts to, for the results panel |
| `server/query/SolrQueryResultReader.kt` | Modify | Read structured explanations as well as flat ones |
| `server/query/screen/SolrQueryScreenFile.kt` | Create | The tab's identity and its state |
| `server/query/screen/SolrQueryScreens.kt` | Create | One tab per connection and collection; closed with its connection |
| `server/query/screen/SolrQueryScreenEditor.kt`, `SolrQueryScreenEditorProvider.kt` | Create | The tab: toolbar, form above, results below; Execute; Open as .http |
| `server/query/screen/SolrQueryScreenForm.kt` | Create | The Swing form drawn from the definition |
| `server/query/screen/SolrQueryScreenResults.kt` | Create | Request line, status, summary table, raw response viewer |
| `server/query/screen/SolrQueryScreenFieldCompletion.kt` | Create | Field completion in `fl`, `sort`, `df`, `qf`, `pf`, `pf2`, `pf3` |
| `server/connection/SolrConnectionSettings.kt`, `SolrConnectionsEditorModel.kt`, `SolrConnectionsConfigurable.kt` | Modify | *Open queries in* |
| `server/topology/SolrTreeQueries.kt`, `SolrCollectionsPanel.kt` | Modify | The console's fill per query; the menu follows the setting |
| `plugin.xml`, `SolrBundle.properties` | Modify | Provider registration; strings |
| Specs, plan, user guide, code organization, `Module.md`, manual suite and checklist | Modify | As the design's *What changes* lists |

---

### Task 1: The form's definition

**Files:** create the JSON and `SolrQueryScreenSpec.kt`; test `SolrQueryScreenSpecTest.kt` (plain JUnit).

**Produces:** `SolrQueryScreenFieldKind { HANDLER, TEXT, CHOICE, CHECKBOX, REPEATED, RAW }`; `SolrQueryScreenField(name, label, kind, tooltip, default, choices, showWhen: Map<String, List<String>>, completesFields, alsoComplete)`; `SolrQueryScreenSection(id, label, toggle, fields)`; `SolrQueryScreenSpec(source, sections)` with `fields`, `field(name)`, `sectionOf(name)`, `parse(json)`, `bundled`.

- [ ] Read Solr 10.0.0's page: `docker run --rm solr:10.0.0 cat /opt/solr/server/solr-webapp/webapp/partials/query.html` and `…/js/angular/controllers/query.js`. Transcribe common, debugQuery, defType (dismax and edismax) and raw parameters, each with its `title` as the tooltip.
- [ ] Two deliberate departures, written into the file's `source`: `q.op` and `defType` default to *the handler's default* (an empty choice), never to a value, so a handler's own `solrconfig.xml` defaults survive; `qt` is a handler **path**.
- [ ] Tests: the four sections in order; common in the Admin's order; edismax-only parameters show only for edismax; the fields that complete; an unknown kind, a choice with no choices, a duplicate name and a `showWhen` naming nothing are refused naming the entry.
- [ ] Commit `feat: describe the query console's form in a definition read from Solr's own page`.

### Task 2: What is filled in, and the parameters it makes

**Files:** `SolrQueryScreenState.kt`; test `SolrQueryScreenStateTest.kt`.

**Produces:** `data class SolrQueryScreenState(spec, values, lines)` with `value`, `lines`, `isOn`, `with`, `withLines`, `isVisible(field)`, `handler`, `parameters(): List<Pair<String, String>>`, `rawProblems(): List<String>`.

Rules: hidden fields keep their value but are not sent; a section's toggle sends `name=true` and opens its fields; blank text is not sent; a checkbox sends `true` when on, and `false` when off only if its default is on; repeated values are sent one per line, in order; raw lines split on the first `=`, blank lines skipped, a line with no name reported by `rawProblems`.

- [ ] Tests for each rule, including the three in Review Focus.
- [ ] Commit `feat: turn what the query console holds into the parameters it sends`.

### Task 3: The request

**Files:** `SolrQueryScreenRequest.kt`; test `SolrQueryScreenRequestTest.kt`.

**Produces:** `SolrQueryScreenRequest(collection, handler, parameters)` with `path`, `sentParameters` (`echoParams=all` last, so a raw `echoParams` wins), `shownUrl(baseUrl)` (no `user:pass@`, no `echoParams`), `httpRequestText()` (a form POST to `{{solrUrl}}/{{collection}}<handler>`, never a host or a credential); `SolrQueryScreenRequest.of(state, collection): Built`, where `Built` is `Ready(request)` or `Refused(reason, detail)` with `Reason { HANDLER_NOT_A_PATH, RAW_WITHOUT_NAME }`.

- [ ] Tests: path and an encoded collection; a blank handler is `/select`; a bare `qt` such as `dismax` is refused; `&`, `+`, `#`, `%` and non-ASCII survive the body; the shown URL hides user info; the `.http` text holds the variables and no host.
- [ ] Commit `feat: build the query console's request, refusing a handler that is not a path`.

### Task 4: Structured explanations, and what an answer amounts to

**Files:** modify `SolrQueryResultReader.kt`; create `SolrQueryScreenAnswer.kt`; tests in `SolrQueryResultReaderTest.kt` and `SolrQueryScreenAnswerTest.kt`.

**Produces:** an object explanation renders as Solr's flat text (`value = description`, details indented two spaces). `SolrQueryScreenAnswer` is `Table(raw, result, partial, partialDetail)`, `Raw(raw)`, `Refused(raw, code, message)` or `NotSent(description)`, from `SolrResponse<SolrRawAnswer>` through `SolrHttpTransport.classify`; `extensionFor(contentType)` picks the viewer's highlighting.

- [ ] Commit `feat: read structured scoring explanations, and say what a console answer amounts to`.

### Task 5: The tab

**Files:** `SolrQueryScreenFile.kt`, `SolrQueryScreens.kt`, `SolrQueryScreenEditorProvider.kt`, `SolrQueryScreenEditor.kt`, `SolrQueryScreenForm.kt`, `plugin.xml`; tests `SolrQueryScreensTest.kt`, `SolrQueryScreenFormTest.kt`, and a case in `SolrDumbModeContractTest`.

The file holds the state and tells its listeners who changed it, so a form ignores its own writes and redraws for anyone else's — a second editor, or a fill from the menu. The service opens one tab per connection and collection, applies a fill to an open one, and closes a tab whose connection is removed. The provider is `DumbAware`, its `accept` a bare class check, its policy `HIDE_OTHER_EDITORS`. The form redraws when a choice or a checkbox changes what is visible.

- [ ] Commit `feat: open a query console tab per connection and collection, drawn from the form's definition`.

### Task 6: Execute and the results

**Files:** `SolrQueryScreenResults.kt`, `SolrQueryScreenEditor.kt`; test `SolrQueryScreenEditorTest.kt` with an embedded server on a thread pool.

Execute builds the request, refuses with a message, cancels a run in flight, and sends a form POST with a 60-second timeout from the editor's own scope; the credential is read there, off the EDT. Closing the tab cancels. <kbd>Ctrl+Enter</kbd> runs. The results show the request line (a link that opens the request as `.http`), a status line, the summary table, and the raw response in a read-only viewer highlighted by its content type.

- [ ] Tests: a JSON answer summarised and shown as sent; a form POST with `echoParams=all`; an XML answer raw, with the Response tab chosen; an HTML 404 reported with its status; carriage returns shown; a second Execute cancels the first; closing cancels; a bare `qt` sends nothing; a removed connection sends nothing; Open as .http writes the templated request.
- [ ] Commit `feat: run the query console's query and show what came back`.

### Task 7: Field completion

**Files:** `SolrQueryScreenFieldCompletion.kt`, `SolrQueryScreenForm.kt`; tests `SolrQueryScreenFieldCompletionTest.kt`.

The collection's fields, as `SolrCollectionFields` remembers them; a popup that opened by itself answers from that memory or the project's configsets; only an explicit <kbd>Ctrl-Space</kbd> reads the server. Each field's `also` list (`score`, `*`, `[docid]`) is offered beside the fields. The prefix is what follows the last `,`, space or parenthesis.

- [ ] Commit `feat: complete field names in the query console's field inputs`.

### Task 8: *Open queries in*, and the menu following it

**Files:** the three `server.connection` classes, `SolrTreeQueries.kt`, `SolrCollectionsPanel.kt`; tests in `SolrConnectionSettingsTest`, `SolrConnectionsEditorModelTest`, `SolrConnectionsConfigurableTest`, `SolrTreeQueriesTest`, `SolrCollectionsPanelTest`.

`SolrQueryDestination { HTTP_CLIENT, CONSOLE }` persisted with the connections; the default is not written. `SolrTreeQueries.consoleState(query, spec)` fills the form by the same rules as `requestText`, which a test holds them to.

- [ ] Commit `feat: open the Solr tool window's queries in the console when the setting says so`.

### Task 9: The documents

FR-1 and FR-16, the plan's Step 13 and build order, the user guide's *Running a query*, code organization, `Module.md`, and new SRV checks in the manual suite and checklist.

- [ ] Commit `docs: describe the query console beside the HTTP Client`.

## After the last task

`./gradlew build`; then `./gradlew runIde` against the demo's `products` and press the new SRV checks. Push and open one PR, saying it is PR 2 of the query console design.
