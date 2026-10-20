# Query console, PR 3: query history — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each query console tab keeps the queries it ran: a **History** tab beside Results and Response lists them newest first, and choosing one puts it back into the form. The history belongs to the connection and collection rather than to the open tab, so it survives closing the tab and restarting the IDE.

**Architecture:** One pure class and one store. `SolrQueryHistory` decides what a history is — an entry is the form's state and when it was sent; newest first, no repeats, fifty at most — and how an entry goes back into a form whose definition may have changed since. `SolrQueryHistoryStore` is a project service persisting entries per connection and collection to the workspace file, pruning a removed connection's history as `SolrQueryScreens` closes its tab. The editor records on Execute; a thin Swing list in the results panel shows the tab's history and puts an entry back through `SolrQueryScreenFile.update`, which already redraws every editor of the tab.

**Tech Stack:** Kotlin, IntelliJ Platform 2026.2 (`SimplePersistentStateComponent`, `BaseState`, `XmlSerializer` for the round-trip test, `JBList`), JUnit 4, `SolrConfigsetTestCase`, the embedded `HttpServer` `SolrQueryScreenEditorTest` already uses.

**Spec:** [`design.md`](design.md) beside this file, *Query history* — the decisions recorded there when this plan was written; the specification's *Querying* section ("History is kept").

**Scope:** PR 3 of the design's delivery table. **Starts after PR 2 (#266) merges**: every class this plan modifies arrives with it. Facet, highlighting and spellcheck (PR 4+) are not here.

## Global Constraints

- **Every public declaration in `src/main/kotlin` has KDoc**, or `dokkaGenerate` fails. The store and the Swing list are `internal`; the documented surface is `SolrQueryHistory` and its entry.
- **Kover's 80% line floor** holds: the list is thin and driven by platform tests.
- **Pure classes are plain JUnit 4** with `@Test` and backtick names; anything with settings, persistence or Swing extends `SolrConfigsetTestCase`, with `testSomething()` names.
- **Nothing on the editor path contacts a server.** Recording and putting back are local; only Execute sends.
- **No credential is recorded.** The form never holds one — the credential is read from PasswordSafe at send time — and the store writes only what the form holds.
- **Formatting:** if the Google-style formatting PR (#275) has merged, run `./gradlew spotlessApply` before each commit.
- **Commits** use conventional subjects and `git commit -s`, ending with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- **Build:** `./gradlew build` passes; check its exit status, not its output.

## Review Focus

1. **The stored history round-trips through the platform's serializer.** A `BaseState` collection without `@XCollection` is skipped in silence — the connections list shipped that way once, surviving exactly as long as the IDE stayed open. → Task 2, `testHistoryRoundTripsThroughTheWorkspaceSerializer`.
2. **A query is recorded when it is sent, not when it succeeds.** A cancelled or failed run is kept; a refused one, which sent nothing, is not. → Task 3, `testASecondExecuteKeepsTheCancelledQuery` and `testARefusedQueryIsNotRecorded`.
3. **An entry goes back into the form as far as the form allows.** A parameter the definition no longer has is left out, not thrown at — the facet section of PR 4 makes this ordinary in both directions. → Task 1, `a parameter the form no longer has is left out when put back`.
4. **Two connections with the same collection keep two histories**, and removing one connection removes only its history. → Task 2, `testRemovingAConnectionRemovesOnlyItsHistory`.
5. **The cap holds on load as well as on record.** The workspace file is hand-editable and outlives any version of this code. → Task 2, `testAnOverlongStoredHistoryIsCappedOnLoad`.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `server/query/screen/SolrQueryHistory.kt` | Create | What a history is: entries, recording, the cap, no repeats; an entry's label; putting an entry back |
| `server/query/screen/SolrQueryHistoryStore.kt` | Create | Per connection and collection, in the workspace file; pruning removed connections |
| `server/query/screen/SolrQueryScreenHistoryList.kt` | Create | The History tab's list, and the Clear action |
| `server/query/screen/SolrQueryScreenEditor.kt` | Modify | Record on Execute; wire the list to the store and the file |
| `server/query/screen/SolrQueryScreenResults.kt` | Modify | A third tab, History |
| `plugin.xml`, `SolrBundle.properties` | Modify | The store's registration; strings |
| Plan, design, user guide, code organization, manual suite and checklist | Modify | Task 5 |

---

### Task 1: What a history is

**Files:** create `SolrQueryHistory.kt`; test `SolrQueryHistoryTest.kt` (plain JUnit).

**Produces:**
- `data class SolrQueryHistoryEntry(val values: Map<String, String>, val lines: Map<String, List<String>>, val sentAt: Instant)` with `companion fun of(state: SolrQueryScreenState, sentAt: Instant)` (the state minus its spec), `fun stateFor(spec: SolrQueryScreenSpec): SolrQueryScreenState` (names the spec does not have are dropped), and `fun label(spec: SolrQueryScreenSpec): String` (the `q`, or its default, then `fq ×n` where there are any, then `+n more` for every other parameter the state would send).
- `data class SolrQueryHistory(val entries: List<SolrQueryHistoryEntry>)` with `fun recording(entry): SolrQueryHistory` and `companion val LIMIT = 50`.

Rules: newest first. Recording a form already in the history — same values and lines, whatever the time — removes the older entry and adds the new one at the top. More than `LIMIT` drops the oldest. Two forms that send the same parameters but differ in a hidden field are different entries, because putting one back would show a different form.

- [ ] Tests: newest first; a repeat moves to the top and is not doubled; the fifty-first drops the oldest; a hidden field's difference keeps two entries; `stateFor` round-trips a state through an entry; **a parameter the form no longer has is left out when put back**; the label for `*:*` with nothing else, for a `q` with two `fq`, and for a form with three other parameters set.
- [ ] Commit `feat: decide what a query console's history is, and how an entry goes back into the form`.

### Task 2: Kept per connection and collection, in the workspace file

**Files:** create `SolrQueryHistoryStore.kt`; register it in `plugin.xml`; test `SolrQueryHistoryStoreTest.kt` (`SolrConfigsetTestCase`).

**Produces:** `@Service(PROJECT) @State(name = "SolrQueryHistory", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]) internal class SolrQueryHistoryStore : SimplePersistentStateComponent<State>, Disposable` with `fun historyOf(connectionId: String, collection: String): SolrQueryHistory`, `fun record(connectionId, collection, entry)`, `fun clear(connectionId, collection)`, and a `HISTORY_CHANGED` topic carrying the connection and collection.

Persisted form: `State { @get:XCollection val entries: MutableList<EntryState> }`, where `EntryState` holds `connectionId`, `collection`, `sentAt` (epoch milliseconds) and `@get:XCollection val parameters: MutableList<ParameterState>`, and `ParameterState` is `name`, `value` and `line` (true for one line of a repeated or raw parameter, so a `fq` with three lines is three `ParameterState`s in order). A flat list rather than a map of maps, because it is what `BaseState` serializes without a custom converter.

**A separate component from the connection settings**, though both live in the workspace file: a history is not a setting, it grows, and keeping it apart keeps `SolrConnectionSettings`' state the size it is. It subscribes to `CONNECTIONS_CHANGED` and drops the entries of every connection id no longer configured.

- [ ] Tests: **`testHistoryRoundTripsThroughTheWorkspaceSerializer`** (`XmlSerializer.serialize(state)` then `deserialize`, then `loadState`, and the history reads back equal — including a repeated parameter's line order); `testTwoConnectionsWithOneCollectionKeepTwoHistories`; **`testRemovingAConnectionRemovesOnlyItsHistory`**; **`testAnOverlongStoredHistoryIsCappedOnLoad`**; `testClearEmptiesOnlyThatTab`; `testAnEmptyHistoryWritesNothing` (the state is the default, so a workspace file that never ran a query says nothing).
- [ ] Commit `feat: keep the query console's history per connection and collection, in the workspace file`.

### Task 3: Recording on Execute

**Files:** modify `SolrQueryScreenEditor.kt`; tests added to `SolrQueryScreenEditorTest.kt`.

In `execute()`, once the request is `Ready` and the connection exists, and before the run is launched, record `SolrQueryHistoryEntry.of(file.state, Instant.now())` under the file's connection id and collection. Recording before the send is what keeps a cancelled run: a second Execute cancels the first, and the first is still in the history.

- [ ] Tests: `testASentQueryIsRecorded`; **`testARefusedQueryIsNotRecorded`** (a bare `qt`); `testARemovedConnectionRecordsNothing`; **`testASecondExecuteKeepsTheCancelledQuery`** (two different forms, the first cancelled — both recorded, the second on top).
- [ ] Commit `feat: record each query the console sends`.

### Task 4: The History tab, and putting a query back

**Files:** create `SolrQueryScreenHistoryList.kt`; modify `SolrQueryScreenResults.kt` and `SolrQueryScreenEditor.kt`; tests `SolrQueryScreenHistoryListTest.kt` (`SolrConfigsetTestCase`).

A `JBList` of the tab's entries, each row the entry's label and its time (today's as a time, older as a date and time). **Double-click or Enter puts the entry back**: `file.update(entry.stateFor(spec), source = list)`, which redraws every editor of the tab through the listener the form already has, and runs nothing. A **Clear** link above the list empties this tab's history. The list refreshes on `HISTORY_CHANGED` for its own connection and collection only, and the editor fills it when the tab opens — which is what makes the history come back after a restart.

- [ ] Tests: `testTheListShowsTheTabsHistoryNewestFirst`; `testAReopenedTabShowsItsHistory` (close the tab, open it again through `SolrQueryScreens.open`); `testChoosingAnEntryPutsItBackAndRunsNothing` (the file's state changes, the embedded server receives nothing); `testAnotherTabsQueryDoesNotAppear`; `testClearEmptiesTheList`.
- [ ] Commit `feat: show the console's history in its own tab, and put a query back from it`.

### Task 5: The documents

- **Plan, Step 13.** Action 3's history is built; the criterion it serves is ticked. What still holds the step open is said in its body — the facet, highlighting and spellcheck sections, and the scoring explanation as an expandable tree — and its build-order line agrees.
- **Design.** The delivery table's PR 3 row says it shipped.
- **User guide**, *Running a query*: the History tab, what an entry is, that it outlives the tab, and that choosing one runs nothing.
- **Code organization**: the two new files under `server.query.screen`.
- **Manual suite and checklist**: new SRV checks after the console's — a sent query appears in History; a refused one does not; choosing an entry fills the form and sends nothing; the history is there after closing the tab and after restarting the sandbox; removing the connection removes it; Clear empties only that tab's history.
- [ ] Commit `docs: describe the query console's history`.

## After the last task

`./gradlew build`; then `./gradlew runIde` against the demo's `products` and press the new SRV checks, including the restart one, which no fixture test can press. Push and open one PR, saying it is PR 3 of the query console design.
