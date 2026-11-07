# Acts 1 to 3 — the opening, the editor features and the codebase: a speaker's outline

Slides 1 to 43 of `solr-intellij-plugin.pptx`: the opening, Act 1's feature deep dives, Act 2's
platform mechanisms and Act 3's code layout. Change a slide here and rebuild it there by hand; every
image is the [screenshot catalog](../screenshots.md)'s own file.

---

## O1 · Where it stands

**Claim.** What ships and what is next, stated from
[the plan](../../specs/plans/0002-solr-intellij-plugin-plan.md) when the deck is built — never from
this file.

**Where it appears.** Slide 2, under the three surfaces, and again on the closing slide, so the scope
is stated at both ends. The plugin is *Solr Support*; the project it supports is still Apache Solr.

---

## E2a · The field popup, annotated

**Claim.** Four parts of the quick-documentation popup, and only one of them hand-maintained.

**What to show.** `02-quick-doc-field-annotated.png`, beside the plain `02-quick-doc-field.png` on the
slide before it. The key is [the FAQ's](../faq.md): the header and the **Value**/**From** pair are read
live from the schema; the match summary is computed from the analyzer chain; **Property**, **Accepts**
and **Meaning** are hand-maintained, the one deliberate exception, argued in `SolrFieldProperties`'
own KDoc.

**Why a companion rather than a replacement.** The numbered markers mean nothing without their key,
and the key needs the column the plain slide's four pointers already occupy. The plain capture keeps
the claim; the annotated one answers the question it always draws.

---

## E3a · The class popup, annotated

**Claim.** Five parts, five build-time sources, and nothing fetched at edit time.

**What to show.** `03-quick-doc-class-annotated.png`, beside the plain `03-quick-doc-class.png`. The
five markers: the short name and kind from the Lucene SPI service files; the class from the scanned
`.class` file — a Lucene class despite the `solr.` spelling; the summary from the sources jar's
Javadoc; the accepted attributes from constructor bytecode; and the Reference Guide link, constructed
for the version the configset declares. It is framed wider than the plain capture because the markers
sit in the editor margin.

---

## A1 · One keystroke — completion, as a sequence

**Claim.** One keystroke, one model read, and only names that resolve are offered.

**What to show.** `seq-02-completion.png`, after `seq-01-quick-documentation.png` in Act 3. The
beats: dumb-aware, so the list appears while indexing; the model from `SolrConfigsetReader.modelFor`,
null outside a configset; the prefix taken from Solr's own token rather than the platform's matcher,
which does not know Solr's separators; and names filtered by `SolrFieldOperations` before they are
offered, not annotated after.

**Source.** [Adding an editor feature](../how-to/add-an-editor-feature.md), "Completion".

---

## A2 · One file open — an inspection, as a sequence

**Claim.** An inspection outside a configset is silent before it visits a single tag.

**What to show.** `seq-03-inspection.png`. The walkthrough inspection end to end: the empty visitor
outside a configset; `isCheckableFieldName` keeping globs, function queries and transformers out;
resolve, then a replacement fix per near miss; and the fix editing the file directly, never asking
whether a write is allowed. One label in the diagram predates a change — step 13 says a warning, and
the dangling-copyField finding is now drawn as an error, because the light schemes draw a warning with
no underline.

**Source.** [Adding an editor feature](../how-to/add-an-editor-feature.md), "The walkthrough: an
inspection".

---

## Claims the rest of Acts 1 to 3 must keep true

- **Inspections.** Eleven configset inspections; the four whose finding is a name that resolves to
  nothing are errors, the rest warnings, and ten are held to a zero-findings gate against Solr's own
  shipped configsets. The code track's check is a twelfth registration and stays a warning.
- **The tree.** `model/` holds schema, vocabulary and query; `server/` is seven packages — connection,
  transport, reading, topology, query, drift, indexing; `code/` is six — solrj, inspection,
  completion, navigation, highlighting, run. Slide 36 quotes a file count; recount before changing it.
- **The tests.** More than a third of the test files are plain JUnit 4, against the rest extending a
  platform base class or being shared fixtures. Slides 32 and 37 both quote the count; recount by
  whether a file extends a platform test case or imports `org.junit.Test`, and change both together.
- **The boundary.** `SolrServerBoundaryContractTest` allowlists one consumer outside `server/`:
  `code.run`, the gutter action, which runs when a user presses it.
