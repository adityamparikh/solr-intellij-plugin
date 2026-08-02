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

## What is outstanding right now

Six images are missing and one needs re-shooting. **Save every file to `docs/images/`, named
exactly as the first column reads** — the README and the FAQ already reference those paths, so a
correctly named file lands in the prose with no further edit.

| Save as `docs/images/…` | State | Used by | Entry |
|---|---|---|---|
| `hints-match-capability.png` | **needed** | README lead image | [1](#1-match-capability-hints--hints-match-capabilitypng) |
| `quick-doc-field.png` | captured — **re-shoot, see entry** | README, FAQ | [2](#2-quick-documentation-on-a-field--quick-doc-fieldpng) |
| `quick-doc-field-annotated.png` | captured — re-shoot with the above | FAQ | [2](#2-quick-documentation-on-a-field--quick-doc-fieldpng) |
| `quick-doc-class.png` | captured — **current, no action** | README, FAQ | [3](#3-quick-documentation-on-a-class-value--quick-doc-classpng) |
| `quick-doc-class-annotated.png` | captured — current, no action | FAQ | [3](#3-quick-documentation-on-a-class-value--quick-doc-classpng) |
| `inspection-copyfield-quickfix.png` | **needed** | README | [4](#4-inspection-and-quick-fix--inspection-copyfield-quickfixpng) |
| `completion-field-properties.png` | **needed** | README | [5](#5-completion-over-the-schemas-own-vocabulary--completion-field-propertiespng) |
| `completion-factory-attributes.png` | **needed** | README | [6](#6-catalog-backed-factory-attributes--completion-factory-attributespng) |
| `find-usages-field-type.png` | **needed** | talk material | [7](#7-find-usages-on-a-field-type--find-usages-field-typepng) |
| `nav-solrconfig-field-reference.png` | **needed** | talk material | [8](#8-navigation-from-solrconfigxml-into-the-schema--nav-solrconfig-field-referencepng) |
| `nav-resource-file.png` | optional | — | [9](#9-navigation-to-a-resource-file--nav-resource-filepng-optional) |

**The annotated pair is a separate errand.** An annotated file carries numbered markers baked into
the pixels and is produced *from* the plain capture afterwards, not shot separately — hand over the
plain one, and the markers get added to match the key already written in [the FAQ](faq.md).

## Conventions

**Light theme, default font size.** GitHub renders these on white for most readers, and a dark-theme
capture inverts badly beside light-theme prose. Default font size keeps successive captures
comparable and readable when scaled down.

**Crop to the feature.** Exclude the IDE title bar, the project name, and the branch chip. They date
the image, leak whatever branch you happened to be on, and add nothing — the first capture in this
catalog leaked `feat/catalog-attribute-de` in its top-left corner before it was cropped out.

**Do not crop a table off mid-row.** A properties table that runs past the frame reads as the
complete answer to anyone who cannot scroll it, so the rows below the fold are invisible rather than
merely absent. The current `quick-doc-field.png` cuts `uninvertible` off the bottom, which is the
one row that shows the version resolution running at all.

**Names describe the capability, not the gesture.** `hints-match-capability.png`, not
`inlay-hover-demo.png`. Kebab-case, `.png`, in `docs/images/`.

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

**The line numbers below are current for the demo configset on `main`, and they move.** Every
capture instruction names both a line and what sits on it; when the two disagree, the content wins
and the number is stale. Adding the comment that explains the demo's `version="1.6"` shifted the
whole schema down by seven lines in a single commit, which is how the previous set went wrong.

---

## The catalog

Every capture starts the same way: `./gradlew runIde`, then open `demo/solr/conf/managed-schema.xml`
in the sandbox. The demo project is built for this — several defects in it are deliberate.

### 1. Match-capability hints — `hints-match-capability.png`

**Shows** the feature nothing else in the ecosystem does: what each field can *actually* match, inline
beside its declaration, without a hover. This is the lead image for the README.

**Capture** the field block at `managed-schema.xml:47-53` with no interaction at all — the hints
render themselves. Frame all seven fields so the contrast is visible in one shot: `id`/`sku`/`category`
read as whole-value and case-sensitive, `name`/`description`/`text` as tokenized and
case-insensitive, and `name_prefix` names EdgeNGram as the mechanism rather than claiming
"prefix: true".

**Redo when** the hint wording changes, a new analysis capability is recognised, or the demo schema's
field list changes.

**Verifies** HINT-1 through HINT-4.

### 2. Quick documentation on a field — `quick-doc-field.png`

**✅ Captured, and needs re-shooting.** *Annotated pair: `quick-doc-field-annotated.png`, used by
[the FAQ](faq.md) with a marker key. Both files need re-shooting together.*

**Shows** the question the Reference Guide cannot answer: each property's value *and where it came
from* — this field, its type, Solr's default, or Solr's default *at the schema version this file
declares*.

**Capture** caret inside `name="category"` at `managed-schema.xml:51`, press F1. Crop to the popup,
**including the `uninvertible` row** — the current image ends above it.

**Redo when** the field property vocabulary in `SolrFieldProperties` gains or loses entries, or the
`From` column's wording changes. **This one is outstanding now, on two counts.** The existing image
predates the schema-version resolution, so it shows neither `uninvertible` reading `true` nor an
origin naming *Solr default at schema version 1.6*. And its `omitNorms` and `docValues` rows read
"depends on the field type", which the field-type-class resolution replaces — that work is in
flight, so re-shoot after it lands rather than before, or the new image is stale on arrival.

**Verifies** DOC-1 and DOC-5.

### 3. Quick documentation on a class value — `quick-doc-class.png`

**✅ Captured, and current — nothing to do.** *Annotated pair: `quick-doc-class-annotated.png`, used
by [the FAQ](faq.md) with a marker key.*

**Shows** all four build-time sources in one popup: short name and kind, the fully-qualified Lucene
class, the one-sentence Javadoc summary, the attributes read from constructor bytecode, and the
constructed Reference Guide link.

**Capture** caret inside any `class="solr.…"` value — `solr.StandardTokenizerFactory` at
`managed-schema.xml:24` is the clearest — press F1.

**Redo when** the popup starts rendering a catalog fact it does not render today. **Not when the
catalog gains a column**, which is the distinction an earlier revision of this entry got wrong: the
catalog has carried each attribute's default and required marker since
`feat: record factory attribute defaults and required markers in the catalog`, and the `Accepts`
table still shows name and value type alone, because nothing renders the other two yet. The image is
correct for what the plugin displays. It goes stale when the per-attribute hover and the
complete-configuration popup land, which is what will put those facts on screen.

**Verifies** DOC-2 and DOC-4.

### 4. Inspection and quick-fix — `inspection-copyfield-quickfix.png`

**Shows** a dangling `copyField` underlined and the Alt-Enter menu offering the declared fields with
the closest spelling first — the plugin catching the failure that would otherwise surface only at
core reload.

**Capture** `managed-schema.xml:61` carries a **deliberate** dangling `manufacturer` copyField that
the demo's header comment says must not be fixed, so no editing is needed. Put the caret on
`manufacturer`, press Alt-Enter, and frame both the underline and the open menu. Undo nothing —
there is nothing to undo.

**Redo when** the quick-fix ordering or the inspection message changes.

**Verifies** INSP-1.

**The count is the claim.** `DemoConfigsetTest` pins that exactly one reference in the committed demo
configset is dangling, and which one — so a correct capture shows this underline and no other. A
second underline anywhere in frame is a false positive, and the screenshot should not be published
until it is gone.

### 5. Completion over the schema's own vocabulary — `completion-field-properties.png`

**Shows** attribute completion inside an opening `<field>` tag: the property table with each property's
one-line summary, attributes already on the tag omitted, and the value Solr would have used anyway
marked `(default)`.

**Capture** put the caret after `stored="true"` on `managed-schema.xml:51`, type a space, and let
completion open. Frame enough of the list to show the summaries and at least one `(default)` marker.
Undo the space afterwards.

**Redo when** the property vocabulary changes, or the `(default)` marking rule changes.

**Verifies** COMP-2, COMP-4 and COMP-6.

**`uninvertible` is the entry worth framing.** It marks `true` as the default on this schema and
would mark `false` on a 1.7 one, so it is the one item in the list showing the plugin answer from
the file rather than from a constant. The rest are true of Solr in general.

### 6. Catalog-backed factory attributes — `completion-factory-attributes.png`

**Shows** the end of the pipeline that starts in `buildSrc`: completion offering a factory's *own*
attributes, read from its constructor bytecode at build time.

**Capture** inside the `<filter class="solr.EdgeNGramFilterFactory" …>` tag at
`managed-schema.xml:39`, put the caret after the class attribute, type a space, and let completion
open — `minGramSize` and `maxGramSize` should appear.

**Redo when** the offered set or its rendering changes.

**Exercises the path CAT-2 checks, with a different factory.** CAT-2 names
`solr.WordDelimiterGraphFilterFactory`, which the demo configset does not declare — that check has
you type the class first. A screenshot should not require editing the fixture, so this captures
`EdgeNGramFilterFactory`, which the demo declares at line 39 and which reaches the editor by exactly
the same constructor-bytecode route. Capturing CAT-2 verbatim means adding the WordDelimiter filter
to the demo, which is a fixture change and belongs to CAT-2, not here.

### 7. Find Usages on a field type — `find-usages-field-type.png`

**Shows** the shared model doing cross-file work: every field declared with a given type, listed from
one gesture.

**Capture** caret on `text_general` in the `<fieldType name="text_general">` declaration
(`managed-schema.xml:22`), press ⌥F7, frame the Find Usages tool window with its results.

**Redo when** the demo schema's fields change, or the usage grouping changes.

**Verifies** NAV-3.

### 8. Navigation from `solrconfig.xml` into the schema — `nav-solrconfig-field-reference.png`

**Shows** the cross-file link that catches the silent failure the README opens with: a `qf` parameter
naming a field, resolving to that field's schema declaration.

**Capture** `solrconfig.xml:28` holds `<str name="qf">name^3 description category</str>`, written for
this purpose. Cmd+hover `name` to raise the navigation tooltip, or Cmd+Click it and capture the
landing in `managed-schema.xml`. The tooltip version reads better in one frame; each name in
`name^3 description` navigates on its own, which is the point worth showing.

**Redo when** the demo's handler parameters change, or boost syntax handling changes.

**Verifies** NAV-4.

### 9. Navigation to a resource file — `nav-resource-file.png` *(optional)*

**Shows** a filter's `words="stopwords.txt"` opening the file it names, including through `lang/`.

**Capture** Cmd+hover the resource path on a `<filter>` that declares one — `managed-schema.xml:25`
carries `words="stopwords.txt"` on a `StopFilterFactory`.

**Redo when** resource attribute coverage changes.

**Verifies** NAV-5. Lower priority — it demonstrates the same navigation machinery as image 8 and
earns a place only if the README wants a second navigation example.

---

## Not yet capturable

These have no gesture that does anything yet. An entry moves up into the catalog when the plan says
its feature shipped, mirroring the manual test suite's own "Not yet in the suite" list:

- Alt-Enter intentions generating an `_exact`/`_prefix` companion field
- Rename refactoring across fields and field types
- The settings page, and *Mark Directory as Solr Configset Root*
- Hover documentation on a factory attribute — owner, value type, default or required marker
- A factory's complete effective configuration, unwritten attributes shown at their defaults
- The dimmed rendering of an attribute that merely restates its default, with its remove intention
- `solrconfig.xml`'s own structure: element completion and validation
- Everything server-side: connections, the tool window, the query console, the drift view
- Everything in Java and Kotlin code: field-name checks, query language injection

The drift view is worth planning a capture for in advance — a side-by-side of repository and server
disagreeing is the single image that explains why the plugin edits a file Solr's banner says not to.

## What invalidates images across the board

Some triggers hit several at once. When one of these lands, re-shoot the images named:

| Change | Re-shoot |
|---|---|
| A popup or completion list starts rendering a catalog fact it did not before | 3, 6 |
| Supported Solr lines change | 2, 3 — the Reference Guide link names the version |
| Field property vocabulary, or how a default is resolved, changes | 2, 5 |
| Demo configset's fields or types change | 1, 2, 5, 7, 8 |
| Demo configset's schema `version` changes | 2, 5 |
| IntelliJ platform restyles popups, completion or inlays | all of them |

The last row is the one that goes unnoticed. A platform version bump can restyle every popup in this
catalog without a single line of plugin code changing, so a major IDE upgrade is a screenshot-review
trigger in its own right.

The schema `version` earns a row of its own rather than joining the one above it. It is a fixture
value in exactly the way the field list is — editing it changes what the images show, not merely
what the file says — but it reaches a narrower set: the property table and the marked default read
it, and the inlay hints, Find Usages and `solrconfig.xml` navigation do not. Folding it into the
fields-and-types row would call for three re-captures that nothing in them would change.
