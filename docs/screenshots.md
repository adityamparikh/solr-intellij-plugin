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

**✅ Captured.**

**Shows** a dangling `copyField` underlined and the Alt-Enter menu offering the declared fields
closest in spelling — the plugin catching the failure that would otherwise surface only at core
reload.

**Closest-spelling ranking decides *which* fields are offered, not the order they appear in.**
`SolrInspections.replacementFixes` sorts by edit distance and keeps the nearest six, which is why
`sku`, `id` and `text` are absent from a nine-field schema. The IDE then renders the intention list
alphabetically, so the capture reads `category`, `description`, `legacy`, `name`, `name_prefix`,
`notes` — a correct image of a correct ranking, and not evidence that the ranking was ignored.

**Capture** `managed-schema.xml:85` carries a **deliberate** dangling `manufacturer` copyField that
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

**✅ Captured.**

**Shows** the shared model doing cross-file work: every field declared with a given type, listed from
one gesture — `name`, `description` and `text`, three results.

**Capture** caret on `text_general` in a field's `type="text_general"` **reference**
(`managed-schema.xml:68`), press ⌥F7, frame the Find Usages tool window with its results.

**Invoke it from a reference, not from the declaration.** With the caret inside
`<fieldType name="text_general">` at line 31 the IDE answers *Cannot search for usages from this
location*: the declaration is what references resolve *to*, and nothing registers it as a search
target. This entry named the declaration until a capture pass tried it.

**Redo when** the demo schema's fields change, or the usage grouping changes.

**Verifies** NAV-3.

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
| Catalog gains or loses a column (defaults, required markers, new facts) | 3, 6 |
| Supported Solr lines change | 2, 3 — the Reference Guide link names the version |
| Field property vocabulary changes | 2, 5 |
| Demo configset's fields or types change | 1, 7, 8 |
| IntelliJ platform restyles popups, completion or inlays | all of them |

The last row is the one that goes unnoticed. A platform version bump can restyle every popup in this
catalog without a single line of plugin code changing, so a major IDE upgrade is a screenshot-review
trigger in its own right.
