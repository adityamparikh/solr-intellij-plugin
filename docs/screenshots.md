# Screenshot catalog

The images that document what this plugin does, and how to reproduce each one. This document owns
three things: **what each image must show**, **the gesture that produces it**, and **what change
invalidates it**.

It deliberately does not own two things. The gestures themselves belong to
[the manual test suite](manual-test-suite.md) — every capture below is a check that suite already
describes, and the wording there is the authority on what the feature is supposed to do. Which
features exist belongs to [the plan](../specs/plans/0002-solr-intellij-plugin-plan.md); an image
joins the catalog when its feature ships, the same rule the test suite follows.

**Capture them during a manual pass.** The gestures are identical, the sandbox is already open, and
a screenshot is evidence that the check passed rather than a separate errand. A pass that produces
no new images is fine; a pass that produces images without running the checks is how a stale picture
gets published.

## Conventions

**Light theme, default font size.** GitHub renders these on white for most readers, and a dark-theme
capture inverts badly beside light-theme prose. Default font size keeps successive captures
comparable and readable when scaled down.

**Crop to the feature.** Exclude the IDE title bar, the project name, and the branch chip. They date
the image, leak whatever branch you happened to be on, and add nothing — the first capture in this
catalog leaked `feat/catalog-attribute-de` in its top-left corner before it was cropped out.

**Names describe the capability, not the gesture.** `hints-match-capability`, not
`inlay-hover-demo`. Kebab-case, `.png`, in `docs/images/`.

**A two-digit prefix carries the catalog entry number**, so the directory lists in the order this
document reads and a file says which entry owns it: `01-hints-match-capability.png` for entry 1. An
annotated pair takes its entry's number rather than one of its own — `02-quick-doc-field.png` and
`02-quick-doc-field-annotated.png` sort together because they document the same popup. The prefix is
positional and the name is not: renumbering an entry renames its file, which is the cost of having
the order visible, and the reason the name still has to stand on its own.

**Annotate only where the prose needs it.** Bake numbered markers into the pixels and nothing else;
every word of explanation lives in the markdown beside the image. A re-shot screenshot then never
invalidates the text around it. `docs/faq.md` is the worked example.

**An annotated image is a second file, never a replacement.** `<name>.png` is the clean capture that
shows the capability; `<name>-annotated.png` carries the markers and belongs only where the prose
explains what they mean. Publishing a marked-up image somewhere without that explanation leaves a
reader with numbered circles and no key.

**Dokka needs absolute URLs.** `docs/Module.md` is a Dokka input, so a relative image path breaks in
the rendered API docs. Reference images there as
`https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/images/<name>.png`, matching
how that file already links. Relative paths are correct everywhere else.

**Every image carries alt text** describing what it shows, because the image is additive: a reader
who cannot see it should lose nothing but the illustration.

---

## The catalog

Every capture starts the same way: `./gradlew runIde`, then open `demo/solr/conf/managed-schema.xml`
in the sandbox. The demo project is built for this — several defects in it are deliberate.

### 1. Match-capability hints — `01-hints-match-capability.png`

**✅ Captured.**

**Shows** the feature nothing else in the ecosystem does — what each field can *actually* match —
and, beside it, the storage shape that decides whether a matched document can be returned at all,
including the two shapes that silence part or all of the hint. Inline, without a hover. This is
the lead image for the README.

**Capture** the field block at `managed-schema.xml:66-77` with no interaction at all — the hints
render themselves. Frame all nine fields so every contrast is visible in one shot: `id`/`sku`/
`category` read as whole-value and case-sensitive, `name`/`description`/`text` as tokenised and
case-insensitive, `name_prefix` as prefix-capable; `name_prefix`/`text` read as `not stored`
where the rest read `stored`, with `text` alone reading `multi-valued`; `notes` carries only the
storage-shape phrases, with no match claim, because its analyser names a factory the plugin does
not recognise; and `legacy` carries no hint at all, because its `type` is undeclared. `legacy`
will also show the undeclared-field-type inspection's underline in frame — that is correct and
not something to crop out; see BASE-1.

**Redo when** the hint wording changes, a property's inline phrase changes, a new analysis
capability is recognised, or the demo schema's field list changes.

**Verifies** HINT-1 through HINT-5.

### 2. Quick documentation on a field — `02-quick-doc-field.png`

**✅ Captured.**

*Annotated pair: `02-quick-doc-field-annotated.png`, marked from this capture and used by
[the FAQ](faq.md) with a four-marker key — the header, the match summary, the **Value**/**From**
pair, and the hand-maintained columns.*

**Shows** the question the Reference Guide cannot answer: each property's value *and where it came
from* — this field, its type, or Solr's default.

**Capture** caret inside `name="category"` at `managed-schema.xml:70`, then Quick Documentation. Crop
to the popup, **including the `uninvertible` row**.

**The popup will not hold the table at its default size.** At the size it opens, the property names
wrap mid-word and the table ends around `termVectors`, which is the crop this entry has warned about
twice. Drag its right edge out until every row is one line, then its bottom edge down; the popup
remembers the size, so this is a once-per-machine adjustment rather than a step in the gesture.

**Redo when** the field property vocabulary in `SolrFieldProperties` gains or loses entries, or the
`From` column's wording changes.

**Verifies** DOC-1.

### 3. Quick documentation on a class value — `03-quick-doc-class.png`

**✅ Captured.**

*Annotated pair: `03-quick-doc-class-annotated.png`, marked from this capture and used by
[the FAQ](faq.md) with a five-marker key, one per build-time source. It is framed wider than the
plain file, because its markers sit in the editor margin to the right of the popup.*

**Shows** all four build-time sources in one popup: short name and kind, the fully-qualified Lucene
class, the one-sentence Javadoc summary, the attributes read from constructor bytecode, and the
constructed Reference Guide link.

**Capture** caret inside any `class="solr.…"` value — `solr.StandardTokenizerFactory` at
`managed-schema.xml:33` is the clearest — then Quick Documentation.

**Redo when** the catalog's columns change. **Not when the catalog gains a column.** The catalog has
carried each attribute's default and required marker since
`feat: record factory attribute defaults and required markers in the catalog`, and the `Accepts`
table still shows name and value type alone, because that is all the popup renders. The image is
correct for what the plugin displays; it goes stale when the per-attribute hover and the
complete-configuration popup put those facts on screen.

**Verifies** DOC-2 and DOC-4.

### 4. Inspection and quick-fix — `04-inspection-copyfield-quickfix.png`

**✅ Captured.** Reshot after the `*_t` dynamic field joined the demo schema, which changed which
six names this menu offers.

**Shows** a dangling `copyField` underlined and the Alt-Enter menu offering the declared fields
closest in spelling — the plugin catching the failure that would otherwise surface only at core
reload.

**Closest-spelling ranking decides *which* names are offered, not the order they appear in.**
`SolrInspections.replacementFixes` sorts by edit distance to `manufacturer` and keeps the nearest
six, which is why `sku`, `id` and `text` are absent. The IDE then renders the intention list
alphabetically — a correct image of a correct ranking, and not evidence that the ranking was
ignored.

**`*_t` is in that six, and `name_prefix` is not.** Dynamic patterns are candidates alongside
fields, because `copyField` may legitimately name a glob. `*_t` and `name_prefix` tie at edit
distance 11, and the tie breaks alphabetically, where `*` sorts ahead of every letter. So the
capture reads `*_t`, `category`, `description`, `legacy`, `name`, `notes`. A pattern leading a
list of replacements for `manufacturer` looks arbitrary and is: at that distance the ranking has
no real signal left, and the tiebreak is doing the choosing.

**Capture** `managed-schema.xml:92` carries a **deliberate** dangling `manufacturer` copyField that
the demo's header comment says must not be fixed, so no editing is needed. Put the caret on
`manufacturer`, press Alt-Enter, and frame both the underline and the open menu. Undo nothing —
there is nothing to undo.

**Redo when** the quick-fix ordering or the inspection message changes.

**Verifies** INSP-1.

**The count is the claim, and the demo schema now plants two.** `DemoConfigsetTest` pins that
exactly one reference in the committed demo configset is dangling and exactly one field names an
undeclared type — the dangling `manufacturer` copyField this entry captures, and the `legacy`
field's undeclared type, which is `legacy`'s own defect and belongs to image 1, not this one. A
correct capture of this entry frames the `manufacturer` underline and, if `legacy` falls in the
same frame, its underline too — but no underline this pass did not already know about. A third
kind of underline anywhere in frame is a false positive, and the screenshot should not be
published until it is gone.

### 5. Completion over the schema's own vocabulary — `05-completion-field-properties.png`

**✅ Captured.**

**Shows** attribute completion inside an opening `<field>` tag: every property with its one-line
summary and what it accepts, and the attributes already on the tag omitted — `indexed` and `stored`
are absent from the list precisely because line 70 already declares them.

**Capture** put the caret after `stored="true"` on `managed-schema.xml:70` and **before the `/`**,
type a space, and let completion open. A space typed between `/` and `>` opens element completion
instead — `fieldType`, `field`, `copyField` — which is the wrong popup and an easy mistake to
publish. Undo the space afterwards; the demo configset is a committed fixture.

**The `(default)` marking is not in this popup, and this entry used to ask for it.** The attribute
*name* list carries `Property`, summary and accepted values; COMP-4's default marking belongs to
value completion, which is a different gesture and, if it is wanted as an image, a different entry.

**Redo when** the property vocabulary changes, or the `(default)` marking rule changes.

**Verifies** COMP-2 and COMP-4.

### 6. Catalog-backed factory attributes — `06-completion-factory-attributes.png`

**✅ Captured.**

**Shows** the end of the pipeline that starts in `buildSrc`: completion offering a factory's *own*
attributes, read from its constructor bytecode at build time, each labelled with the factory it came
from.

**Capture** inside the `<filter class="solr.EdgeNGramFilterFactory" …>` tag at
`managed-schema.xml:48`, put the caret before the closing `/`, type a space, and let completion
open. What appears is `luceneMatchVersion` and `preserveOriginal`, both attributed to
`solr.EdgeNGramFilterFactory`. Undo the space afterwards.

**Not `minGramSize` and `maxGramSize`, which this entry used to promise.** The demo declares both on
that filter, and completion omits what the tag already carries — the same rule that makes image 5
show no `indexed` or `stored`. The two rules cannot both be demonstrated on one tag: an attribute
already written is the proof of one and invisible to the other. `preserveOriginal` reaches the popup
by the identical constructor-bytecode route, so the claim this image makes is unchanged.

**Redo when** the catalog gains columns — the same trigger as image 3, and for the same reason.

**Exercises the path CAT-2 checks, with a different factory.** CAT-2 names
`solr.WordDelimiterGraphFilterFactory`, which the demo configset does not declare — that check has
you type the class first. A screenshot should not require editing the fixture, so this captures
`EdgeNGramFilterFactory`, which the demo declares at line 48 and which reaches the editor by exactly
the same constructor-bytecode route. Capturing CAT-2 verbatim means adding the WordDelimiter filter
to the demo, which is a fixture change and belongs to CAT-2, not here.

### 7. Find Usages on a field type — `07-find-usages-field-type.png`

**✅ Captured, current.** Reshot with both corrections below already made: invoked from the
declaration, and framing four results rather than the three an earlier caption promised.

**Shows** the shared model doing cross-file work: every field declared with a given type, listed from
one gesture — `name`, `description`, `text` and `*_t`, four results.

**Capture** caret inside the `<fieldType name="text_general">` **declaration**
(`managed-schema.xml:31`), press ⌥F7, frame the Find Usages tool window with its results.

**Invoke it from the declaration.** An earlier revision of this entry said to invoke from a
reference instead, because the declaration answered *Cannot search for usages from this location*.
[Declarations as targets](../specs/plans/0002-solr-intellij-plugin-plan.md#step-28-declarations-as-targets-done)
closed that: the declaration is a search target now, and demo step 27 performs the gesture from there.
The reference position (`managed-schema.xml:68`) still works and lists the same four — the
declaration is simply the one worth showing, being the one that used to refuse.

**The count moved for an unrelated reason.** This entry once promised three results; the answer is
four from either caret, because the `*_t` dynamic field joined the demo schema declaring
`type="text_general"` and references the type like any other field.

**Redo when** the demo schema's fields change, or the usage grouping changes.

**Verifies** NAV-3. NAV-6 exercises the same declaration-target machinery from a *different*
declaration — a `dynamicField` rather than this `fieldType` — and has no image of its own;
the usage list this one frames is the same machinery answering the easier question.

### 8. Navigation from `solrconfig.xml` into the schema — `08-nav-solrconfig-field-reference.png`

**✅ Captured.**

**Shows** the cross-file link that catches the silent failure the README opens with: a `qf` parameter
naming a field, resolving to that field's schema declaration.

**Capture** `solrconfig.xml:28` holds `<str name="qf">name^3 description category</str>`, written for
this purpose. Cmd+hover `name` to raise the navigation tooltip, or Cmd+Click it and capture the
landing in `managed-schema.xml`. The tooltip version reads better in one frame; each name in
`name^3 description` navigates on its own, which is the point worth showing.

**Redo when** the demo's handler parameters change, or boost syntax handling changes.

**Verifies** NAV-4.

### 9. Navigation to a resource file — `09-nav-resource-file.png` *(optional)*

**✅ Captured.**

**Shows** a filter's `words="stopwords.txt"` opening the file it names, including through `lang/`.

**Capture** caret on the resource path of a `<filter>` that declares one —
`managed-schema.xml:34` — then Quick Definition, which frames the resolved file's own contents
beside the reference.

**Cmd+hover raises a tooltip that is the absolute path, and that path is somebody's home
directory.** It is the faster gesture and the right one for checking NAV-5 by hand; it is the wrong
one to publish, for the same reason this catalog crops out the branch chip. Quick Definition shows
the same resolution as the file it opens, named `stopwords.txt` and nothing more.

**Redo when** resource attribute coverage changes.

**Verifies** NAV-5. Lower priority — it demonstrates the same navigation machinery as image 8 and
earns a place only if the README wants a second navigation example.

### 10. `solrconfig.xml`'s own structure — `10-completion-solrconfig-structure.png`

**✅ Captured, and reshot against the fixed catalog.** This image has now gone stale twice for two
different reasons, which is worth recording because they are the two ways a screenshot rots. First the
*crop* leaked `settings.gradle.kts (solr-plugin-demo)` from the tab bar. Then the *contents* went
stale within the hour: the catalog generator's element-placement fix moved `cache`,
`featureVectorCache` and `HashDocSet` out from under `<config>` and under `<query>`, where Solr
actually reads them — so the capture began demonstrating the bug the fix removed rather than the
feature. The current image is from a sandbox rebuilt after that fix, and all three are absent from it.

**The reshoot needed a fresh sandbox, not just a rebuild.** A running `runIde` does not reload a
regenerated catalog resource, so the first attempt reproduced the old popup perfectly and looked like
the fix had failed. Anyone redoing this capture after a generator change must relaunch the sandbox,
and should check `build/generated/solr-catalog-resources/solr-catalog/elements-10.tsv` on disk before
concluding anything from what the popup shows.

**Shows** the failure the platform's schema-less mode produces, replaced: typing `<` inside `<config>`
offers Solr's own top-level vocabulary — `directoryFactory`, `dataDir`, `luceneMatchVersion`,
`requestHandler`, `query`, `circuitBreaker`, `codecFactory`, `expressible`, `indexConfig` among them —
not an echo of the handful of sibling tags actually written above the caret, and **not** `cache`,
`featureVectorCache` or `HashDocSet`, which now complete only inside `<query>`. This is the capture
that settled [the plan's stale claim](../specs/plans/0002-solr-intellij-plugin-plan.md#overview) that
`solrconfig.xml` structure completion was still gated on the schema; Build order and step 25 were
correct, and the opening paragraph has since been corrected to match.

**Capture** put the caret on a blank line directly inside `<config>` in `solr/conf/solrconfig.xml`
and type `<`. Frame the completion popup.

**Redo when** the generated element vocabulary changes, or the demo's `solrconfig.xml` gains or loses
top-level children.

**Verifies** STR-1.

### 11. An attribute that restates its default — `11-dimmed-restated-default.png`

**✅ Captured.**

**Shows** the dimmed rendering DIM-1 describes, needing no edit: `indexed="true"` and
`stored="true"` render fully greyed — name and value both — on the field block, while
`stored="false"` on `name_prefix` renders at full strength. A restated default is correct Solr, so
nothing here reaches the Problems view; the dim is the only claim being made.

**Capture** open `managed-schema.xml` untouched and frame the field block at lines 66-73. No
interaction — the dim renders itself, exactly like image 1's hints.

**Redo when** the demo schema's declared attributes change, or the dimming rule's set of properties
changes.

**Verifies** DIM-1.

### 12. Intentions — companion fields — `12-intention-companion-fields.png`

**✅ Captured.**

**Shows** Alt-Enter on a tokenised field offering to generate the two companions the field lacks:
**Add exact-match companion field (string)** and **Add prefix-capable companion field
(text_prefix)**, both above the platform's own generic items. Captured with the documentation
preview pane open, which shows the exact-match intention's explanation alongside the menu.

**Capture** caret inside `description` (`managed-schema.xml:71`, a `text_general` field), press
Alt-Enter (⌥⏎), and optionally open the doc-preview pane (⌃J) before framing. Dismiss with Escape —
either item in the menu writes a new `<field>` and `<fieldType>` into the schema.

**Redo when** the intentions' wording changes, or the demo's `description` field stops being
tokenised.

**Verifies** INT-1.

### 13. Field-name completion inside a `solrconfig.xml` parameter — `13-completion-parameter-fields.png`

**✅ Captured.**

**Shows** the inverse of INSP-4's warning: the schema's own fields offered inside a handler parameter
known to hold them, each labelled with its type, and the dynamic pattern `*_t` offered alongside the
literal fields rather than only literal spellings.

**Capture** caret immediately after `category` in `solrconfig.xml:28`'s
`<str name="qf">name^3 description category</str>`, **type a space**, then trigger completion. **The
space is not optional** — completion did not fire with the caret directly adjacent to existing text
and no trailing whitespace, the same rule [image 5](#5-completion-over-the-schemas-own-vocabulary--05-completion-field-propertiespng)
already documents for attribute completion, confirmed true here too. Undo the space afterwards.

**Redo when** the demo schema's field list changes.

**Verifies** PRM-1.

### 14. Rename's cross-file update — `14-rename-cross-file-before.png` / `14-rename-cross-file-after.png`

**✅ Captured.** A before/after pair sharing one entry number, on the same rule an annotated pair
follows: they document one gesture and belong together.

**Shows** the half a hand-edit misses: renaming a field from its schema declaration updates the
`qf` line in `solrconfig.xml` with no separate action, and the platform's own rename-confirmation
dialog names the refactoring as one operation across both files — a single Cmd+Z undoes both.

**Capture** caret on `category` in `<field name="category">` at `managed-schema.xml:70`, Shift+F6,
apply directly (no Preview step) to rename to `product_category`. `-before` frames
`solrconfig.xml:28` reading `...description category`; `-after` frames the same line reading
`...description product_category`, highlighted. Undo with one Cmd+Z, confirm `git status --short
demo/` is clean.

**Redo when** the demo's `qf` value or the `category` field changes.

**Verifies** REN-2.

### 15. Parameter-name completion — `15-completion-parameter-names.png`

**✅ Captured.**

**Shows** the position the request-parameter vocabulary is learnable from in the first place: the
`name` of a new `<str>` inside `<lst name="defaults">` offers Solr's own parameter names, each
labelled with the Params class that declares it and, where the name is part of a family, a one-line
description distinguishing it from its siblings.

**Capture** add a new `<str name="` line inside the `/select` handler's `<lst name="defaults">`
(`solrconfig.xml`, after line 32), type `sort`, trigger completion: `sort` (*sort order*,
`CommonParams`) heads the list, followed by `expand.sort`, `facet.sort`, `group.sort` and
`terms.sort`. Undo the added line afterwards.

**A parameter already set on the same list is withheld, and that is PRM-7 rather than a broken
popup.** Typing `qf` at the same position offers only `mlt.qf` and `hl.queryFieldPattern` — plain
`qf` is absent because line 28 already declares it in the same `<lst name="defaults">`. Worth
knowing before reusing this gesture: an apparently generic, sparse list at this position is more
often PRM-7 firing correctly than a capture gone wrong.

**Redo when** the generated parameter catalog changes.

**Verifies** PRM-6.

### 16. Quick documentation on the schema's `version` — `16-hover-schema-version.png`

**✅ Captured.**

**Shows** the two-paragraph structure DOC-9 describes: what `version` decides in general, then what
the demo's declared `1.6` decides here — `docValues` off, `uninvertible` on, `autoGeneratePhraseQueries`
off.

**Capture** hover `version` on `<schema version="1.6" …>` at `managed-schema.xml:27`.
**F1 does not open Quick Documentation on the default macOS keymap** — it opens a browser tab to the
platform's own Help page instead, which has nothing to do with this plugin. **Ctrl+J** is what raises
the popup reliably.

**On this sandbox, the popup rendered as a full-width docked panel rather than a floating box** —
cosmetic, and this image is framed to that shape rather than to a floating popup's. If a reshoot
produces a floating popup instead, that is an IDE-version difference in presentation, not a
regression to chase.

**Redo when** the demo schema's declared version changes, or the version-dependent property list
changes.

**Verifies** DOC-9.

### 17. Nesting — what completes inside `<query>` — `17-completion-query-nesting.png`

**✅ Captured.**

**Shows** the half of structure completion that entry 10 cannot: not what the plugin *declines* to
offer at the root, but what it offers in the right place instead. Typing `<` inside `<query>` offers
eighteen elements — `boolTofilterOptimizer`, `cache`, `documentCache`, `enableLazyFieldLoading`,
`featureVectorCache`, `fieldValueCache`, `filterCache`, `HashDocSet`, `listener`, `maxBooleanClauses`,
`maxWarmingSearchers`, `minPrefixQueryTermLength`, `queryResultCache`, `queryResultMaxDocsCached`,
`queryResultWindowSize`, `slowQueryThresholdMillis`, `useColdSearcher`, `useFilterForSortedQuery` —
and `dataDir` is not among them. Set beside entry 10, the two images are the whole claim: the
vocabularies differ by position, which is what a schema-less sibling echo can never do.

**This is the capture that closes a real gap in the evidence.** Every other artifact verifying the
element-placement fix — the tests, the whole-catalog diff, entry 10 — observes only what *left*
`<config>`. Had those elements left without arriving anywhere, all of it would still have read green.
This is the only thing that shows they arrived.

**Capture** the demo declares no `<query>`, so type `<query></query>` inside `<config>` first, put the
caret between the tags and type `<`. Frame the popup. **Revert the file afterwards** — `git checkout
-- demo/solr/conf/solrconfig.xml` — and confirm `git status --short demo/` is empty. IntelliJ's
autosave can put an intermediate typing state on disk even when the editor looks clean; refocusing the
sandbox after the revert makes it reload from disk.

**A running sandbox does not reload a regenerated catalog.** After any generator change, relaunch
`runIde` before capturing, and use entry 10's `<config>` popup as the canary: if `cache`,
`featureVectorCache` or `HashDocSet` appear there, the sandbox predates the fix and nothing seen in it
means anything.

**Redo when** the generated element vocabulary changes, or `SolrConfig` changes which elements it
reads off the query node.

**Verifies** STR-2.

### 18. Nesting — `<indexConfig>`, and what is never offered — `18-completion-indexconfig-nesting.png`

**✅ Captured.**

**Shows** `<indexConfig>` offering exactly one element, `deletionPolicy`, beside entry 17's eighteen.
The contrast is the point twice over. It is nesting again at a parent with almost no live children —
and it is the visible half of STR-3: `nrtMode` and `unlockOnStartup` are read by Solr at this exact
position, are keyed to it in the catalog, are reported by the discontinued-element inspection when
written here, and are **still not offered**, because `SolrElementCatalog.offerableChildrenOf` filters
to what Solr still accepts. Knowing where an element belongs and recommending it are different
questions, and this image is the one place both answers are visible at once.

**Not a regression, and do not report it as one:** `lockType`, `httpCaching` and `infoStream` appear
in all four shipped configsets and in neither catalog, because `SolrConfig` does not read them. A
reader whose own file contains `<lockType>` sees it offered nowhere. That gap predates the placement
fix and is recorded in the manual suite's 2026-08-16 note.

**Capture** as entry 17, with `<indexConfig></indexConfig>` in place of `<query></query>`. Same revert
discipline.

**Redo when** entry 17 is redone.

**Verifies** STR-3.

---

## Not yet capturable

These have no gesture that does anything yet, or shipped but has not been captured. An entry moves up
into the catalog when its feature ships **and** someone shoots it, mirroring the manual test suite's
own "Not yet in the suite" list:

- The settings page, and *Mark Directory as Solr Configset Root* — not yet built
- Hover documentation on a factory attribute — owner, value type, default or required marker (DOC-7)
- A factory's complete effective configuration, unwritten attributes shown at their defaults (DOC-4b)
- An attribute's own name answering on hover — `name`, `type`, `source`, `dest` (DOC-8)
- `solrconfig.xml` attribute completion inside a cache tag such as `<filterCache>` (STR-5)
- The analyzer-chain ordering inspection (INSP-7), the discontinued-element and misspelled-parameter
  inspections (INSP-13, INSP-14), and the field-operation split (INSP-10 through INSP-12)
- Class navigation landing in a resolvable class (NAV-9) — needs `solr-core` added to the demo's
  dependencies first, a fixture change out of scope for a screenshot pass
- Everything server-side: connections, the tool window, the query console, the drift view
- Everything in Java and Kotlin code: field-name checks, query language injection

The drift view is worth planning a capture for in advance — a side-by-side of repository and server
disagreeing is the single image that explains why the plugin edits a file Solr's banner says not to.

## What invalidates images across the board

Some triggers hit several at once. When one of these lands, re-shoot the images named:

| Change | Re-shoot |
|---|---|
| Catalog gains or loses a column (defaults, required markers, new facts) | 3, 6 |
| Supported Solr lines change | 2, 3 — the Reference Guide link names the version |
| Field property vocabulary changes | 2, 5, 11 |
| Demo configset's fields or types change | 1, 7, 8, 11, 12, 13, 14 |
| Demo's `solrconfig.xml` handler parameters change | 8, 13, 14 |
| Demo schema's declared `version` changes | 16 |
| Generated `solrconfig.xml` element vocabulary changes | 10 |
| Generated `solrconfig.xml` parameter catalog changes | 15 |
| IntelliJ platform restyles popups, completion or inlays | all of them |

The last row is the one that goes unnoticed. A platform version bump can restyle every popup in this
catalog without a single line of plugin code changing, so a major IDE upgrade is a screenshot-review
trigger in its own right.
