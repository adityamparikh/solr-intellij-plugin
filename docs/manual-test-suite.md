# Manual test suite

> **Who this is for.** Someone sitting in front of a running sandbox IDE, mid-gesture, checking
> whether a shipped feature behaves the way it claims to — not a newcomer reading ahead of the
> code, and not a substitute for what the automated suite's fixtures already prove.
> **Read first:** [Glossary](glossary.md) if Solr or IntelliJ Platform terms are new ·
> [the plan](../specs/plans/0002-solr-intellij-plugin-plan.md), which owns what is actually built

A repeatable verification pass over the [sandbox](glossary.md#sandbox) IDE, run against the demo project. This
document owns three things: the **gesture** to make, the **expected outcome**, and the
**record of the last pass**. It deliberately does not own what is built —
[the plan](../specs/plans/0002-solr-intellij-plugin-plan.md) does, and a feature's checks
join this suite only when its code has shipped and there is something to press.

**How to run a pass**

1. `./gradlew runIde` — the sandbox opens `demo/`. Open [`solr/conf/managed-schema.xml`](glossary.md#managed-schema).
2. Uncheck every box from the previous pass (a pass is all-or-nothing; history lives in
   the [pass log](#pass-log), not in the boxes).
3. Work top to bottom. The order matters: the baseline pass comes first because
   every later "break it" check ends by restoring that baseline.
4. Record the pass in the log with the commit you ran it at.

> **In Java terms.** `./gradlew runIde` does not run tests — it launches a second, disposable
> IntelliJ instance with the plugin installed, closer to starting your Spring Boot app on a
> scratch profile to click through by hand than to running its test suite. Nothing typed there
> touches your everyday IDE or its indexes, which is the whole reason this document calls it a
> sandbox.

**The 📸 items are screenshots, and they are checkboxes like everything else.** Each one sits
beside the check whose gesture produces it, names the file to save and where, and links to
[the screenshot catalog](screenshots.md) for the framing and the reason. A screenshot taken during
a pass is evidence the check passed; one taken outside a pass is a picture of an unverified claim.
Every image the catalog lists now exists, captured outside a pass at `26284b7`; each entry says what
its file must show, so a pass that disagrees with one of them is re-shooting it, not adding it.

**Save every image to `docs/images/`, named exactly as the check reads.** The README and the FAQ
already reference those paths, so a correctly named file lands in the prose with no further edit.
Hand over the plain captures only; the `-annotated` pairs are produced from them afterwards.

If every feature is dead at once, suspect the sandbox, not the plugin: delete
`.intellijPlatform/sandbox/<project>/<IDE>/system-test` and relaunch — the full signature
is in [the testing guide](how-to/testing-and-the-build-gates.md).

**Why these checks are manual at all.** Almost every claim below is also asserted
headlessly (each section names its automated coverage). The manual pass exists for what
a light [fixture](glossary.md#fixture) cannot see: whether the hint actually renders where a presenter can point
at it, whether the popup is readable, whether features stay alive during real indexing.
A check whose rendering risk disappears — because automation grows to cover it — should
be retired from here, not accumulated.

> **In Java terms.** A green `./gradlew test` run proves the fixtures it built are correct, not
> that a feature works when someone actually presses it. This suite's own [pass log](#pass-log)
> records two defects no automated assertion caught: a warning template that agreed with itself
> in every fixture while naming the wrong requirement in practice (2026-08-10), and an inspection
> confirmed green by tests that shared its own wrong assumption about where Solr reads a config
> element (2026-08-16). Both were visible only to someone reading the rendered message in a real
> editor.

---

## 1. Activation (ACT)

*Automated: `SolrProjectDetectorTest`, `SolrConfigsetDetectorTest`,
`SolrConfigsetLocatorTest`. Manual adds: real project open, real indexing.*

- [ ] **ACT-1** — Opening `demo/` activates the plugin: `managed-schema.xml` shows inlay
      hints. The demo project passes the outer gate through its Solr client dependency.
- [ ] **ACT-2** — Features are alive **while the IDE is still indexing** (open the file
      immediately after launch, before the progress bar finishes). Everything is
      [dumb-aware](glossary.md#dumb-mode) by design; a feature that waits for indexing is a
      regression.

## 2. Zero-false-positive baseline (BASE)

*Automated: every inspection's clean fixture; `DemoConfigsetTest`. Manual adds: the real
analysis pass over the real demo files, all inspections at once.*

- [ ] **BASE-1** — `managed-schema.xml`, untouched, shows **exactly two** warnings: the
      planted dangling `manufacturer` copyField near the bottom, and the planted undeclared
      `type="discontinued"` on the `legacy` field. Both are there to demonstrate an inspection,
      and neither may be fixed. Nothing else in the file is underlined.
- [ ] **BASE-2** — `solrconfig.xml`, untouched, shows **zero**.

**Count the rows that begin `Solr:`, and read the count in the Problems tool window rather
than off the underlines.** The demo's own comments are written in British English, which
trips IntelliJ's American-English locale inspection four times, and `configsets` trips its
spellchecker once. Five underlines in a clean file, none of them this plugin's, is the
shape of a false positive without being one — the tool window separates them by inspection
and settles it in a glance.

This is the suite's most important check, and the count is the whole of it: two reports,
on the two defects the fixture plants deliberately, and no more. Solr configuration is full
of syntax that resembles a field name (`fl` holds `score`, `*`, `max(price,0)`); a third
underline is a false positive, and a false positive on a correct file is a bug, never noise.
`DemoConfigsetTest` pins the same claim headlessly — that exactly one reference in the
committed demo configset is dangling and exactly one field names an undeclared type, and
which ones they are.

## 3. Match-capability [inlay hints](glossary.md#inlay-hint) (HINT)

*Automated: `SolrMatchInlayHintsProviderTest`, `SolrMatchAnalysisTest`. Manual adds:
placement and readability of the rendered hint.*

- [ ] **HINT-1** — `string` fields (`id`, `sku`, `category`) read as whole-value,
      case-sensitive, and carry `indexed, stored, doc values, single-valued`. The demo
      schema's `string` fieldType declares `docValues="true"` itself, so these three read
      `doc values` even though the schema version alone would default it off.
- [ ] **HINT-2** — `text_general` fields (`description`, `text`) read as tokenised,
      case-insensitive, with no efficient prefix support, and both carry `indexed` and
      `no doc values`. `description` alone also carries `stored, single-valued`; `text`
      alone carries `not stored, multi-valued`.
- [ ] **HINT-3** — `name_prefix` (type `text_prefix`) reads as prefix-capable and carries
      `indexed, not stored, no doc values, single-valued`.
- [ ] **HINT-4** — Hints sit inline beside the declaration (no hover needed), readable
      at presentation font size.
- [ ] **HINT-5** — `notes` (type `custom_text`, whose analyzer names the unrecognised
      `com.example.MyTokenizerFactory`) shows the storage-shape phrases and no match claim:
      `indexed, stored, no doc values, single-valued`. `legacy` (type `discontinued`, which
      the schema does not declare) shows no hint at all.
- [ ] 📸 **Capture `docs/images/01-hints-match-capability.png`** — the field block at lines
      66-77, all nine fields in one frame, no interaction.
      [Catalog entry 1](screenshots.md#1-match-capability-hints--01-hints-match-capabilitypng).

## 4. Navigation and [Find Usages](glossary.md#find-usages) (NAV)

*Automated: `SolrFieldTypeReferenceTest`, `SolrCopyFieldReferenceTest`,
`SolrConfigFieldReferenceTest`, `SolrResourceFileReferenceTest`. Manual adds: the click
gesture, caret placement, the Find Usages tool window.*

- [ ] **NAV-1** — Cmd+Click a field's `type="text_general"` jumps to the
      `<fieldType name="text_general">` declaration.
- [ ] **NAV-2** — Cmd+Click a `<copyField>` `source` and `dest` jumps to each field's
      declaration.
- [ ] **NAV-3** — Find Usages (⌥F7) on a field's `type="text_general"` lists every field
      declared with that type, and the search invoked on the
      `<fieldType name="text_general">` **declaration** returns the same set. Both
      directions, because the declaration is where a reader reaches for the gesture and it
      refused until declarations became targets.
- [ ] **NAV-4** — In `solrconfig.xml`, Cmd+Click a field name inside a handler parameter
      (`qf`, `df`, a `facet.field` array item) lands on the schema declaration; each name
      in `name^3 description` navigates on its own, and Find Usages lists the parameter
      among its usages — invoked from the parameter, from a `copyField` end, and from the
      `<field name="description">` declaration alike.
- [ ] **NAV-6** — Find Usages on `<dynamicField name="*_t">` in the schema reports the
      `pf` parameter naming `body_t` in `solrconfig.xml` — a name the pattern supplies and
      never spells. IntelliJ's default Find Usages narrows candidates through a **word index**
      first — a build-time index of every literal word in the project — and only then asks each
      candidate whether it truly refers to what is being searched for; `body_t` shares no word
      with `*_t`, so the default search never offers it as a candidate to ask in the first place.
      This is exactly the case a word index cannot reach: a usage that resolves to the
      declaration without ever spelling it. The result is highlighted at `body_t` itself, not
      across the whole parameter value.
- [ ] **NAV-7** — **How the results are labelled**, which is the half no fixture can see.
      In [the Find Usages check on a field type](#4-navigation-and-find-usages-nav)'s window the
      header reads **Field type** over `text_general`, and the results
      group under **Field declaring this type** — not *Solr Declaration Target*. That is what
      IntelliJ falls back to when nothing registers a description for a search target: it takes
      the underlying Java class's own name — `SolrDeclarationTarget`, this plugin's internal
      type — and de-camel-cases it into a phrase, which reads exactly like real information and
      is a bug leaking through rather than one. And not *Unclassified* either, the platform's
      default grouping label for a usage no registered classifier claims — the generic bucket
      every search result falls into until something says what kind of usage it is. In
      [the Find Usages check on a dynamic field](#4-navigation-and-find-usages-nav)'s, the
      header reads **Dynamic field** and the group reads **Handler parameter in
      solrconfig.xml**. A correct result list under either of those two wrong labels reads as
      broken to everyone but its author, because nothing distinguishes a mislabelled correct
      answer from a genuine defect.
- [ ] **NAV-5** — Cmd+Click a resource path on a filter *or a char filter* —
      `words="stopwords.txt"`, `synonyms=`, `protected=`, a `<charFilter>`'s `mapping=` —
      opens the file, including through `lang/`; each entry in a comma-separated list
      navigates on its own.
- [ ] **NAV-8** — **How a resource file's usages are labelled**, which is
      [the results-labelling question](#4-navigation-and-find-usages-nav) asked
      of the fourth kind of reference. Find Usages on `stopwords.txt` itself — from the
      Project view, or from the caret on the path — lists both
      `<filter class="solr.StopFilterFactory">` occurrences under **Analyzer component
      reading this file**, not *Unclassified*. This one shipped wrong: the classification
      skipped file references on the assumption the platform grouped them, and nothing does.
- [ ] 📸 **Capture `docs/images/07-find-usages-field-type.png`** at
      [the Find Usages check on a field type](#4-navigation-and-find-usages-nav) — the Find Usages
      tool window with its results, invoked from the `<fieldType name="text_general">`
      declaration, which is the gesture the demo now performs.
      [Catalog entry 7](screenshots.md#7-find-usages-on-a-field-type--07-find-usages-field-typepng).
- [ ] 📸 **Capture `docs/images/08-nav-solrconfig-field-reference.png`** at
      [the check that a solrconfig.xml parameter navigates into the schema](#4-navigation-and-find-usages-nav) —
      Cmd+hover `name` in `solrconfig.xml:28`, framing the navigation tooltip.
      [Catalog entry 8](screenshots.md#8-navigation-from-solrconfigxml-into-the-schema--08-nav-solrconfig-field-referencepng).
**The demo cannot exercise the landing half of this, and the reason is worth reading.** It depends on
`solr-solrj` alone, while `solr.SearchHandler` is `org.apache.solr.handler.component.SearchHandler`
in `solr-core` — not on the demo's classpath. Navigation needs the class *in the project*;
documentation needs only the generated catalog, which the plugin build resolves separately and which
does carry `SearchHandler`. So the two answers differ here on purpose.

- [ ] **NAV-9** — Cmd+Click the `/select` handler's `class="solr.SearchHandler"`: it resolves
      nowhere, and **draws no warning for failing to**. A configset naming a class this project does
      not carry is ordinary, not wrong. To press the landing half, add `org.apache.solr:solr-core` to
      the demo's dependencies first — then the same gesture opens the class, and **the `solr.` prefix
      is the part that matters**, since it is Solr's shorthand rather than a package and a literal
      resolution would find nothing.
- [ ] **NAV-10** — Quick documentation on that same value **while the project is still indexing** —
      start right after opening the sandbox, or *File → Invalidate Caches → Just Restart* first. It
      answers from the catalog rather than dying, with no class on the classpath and no index ready.
      This is the gesture whose collapse once took the whole popup down with it, including the parts
      that needed no index at all.
- [ ] 📸 *Optional:* **`docs/images/09-nav-resource-file.png`** at
      [the check that a filter's resource path navigates to the file](#4-navigation-and-find-usages-nav) —
      caret on `words="stopwords.txt"` at `managed-schema.xml:34`, then **Quick Definition**. Check
      that navigation itself with Cmd+Click or Cmd+hover as usual, but do not publish the hover: its
      tooltip is an absolute path through your home directory.
      [Catalog entry 9](screenshots.md#9-navigation-to-a-resource-file--09-nav-resource-filepng-optional).

## 4a. [Rename](glossary.md#rename-refactoring) (REN)

*Renaming is the reference graph read backwards and then written to. Every check here ends
in an undo — these edit both files, and the demo has to come back clean for the sections
below.*

- [ ] **REN-1** — Caret on `category` in `<field name="category">` at
      `managed-schema.xml:70`, press Shift+F6. **Read the dialog before typing:** it must
      say *Rename **field** 'category'*, not *Rename Solr Declaration Target 'category'* —
      the same description
      [the check on how Find Usages results are labelled](#4-navigation-and-find-usages-nav)
      checks, reached by a second refactoring.
- [ ] **REN-2** — Complete that rename to `product_category`. The declaration updates, and
      so does the `qf` line in `solrconfig.xml:28` — the cross-file half, and the one a
      hand-edit misses. **Undo, and confirm both files are clean.**
- [ ] **REN-3** — Rename `text_general` from its `<fieldType>` declaration at line 31: every
      field's `type=` follows, including the `*_t` dynamic field's. **Undo.**
- [ ] **REN-4** — **The one that is deliberately partial.** Rename `<dynamicField name="*_t">`
      at line 84 to `*_txt`. The declaration updates and any reference *spelling* the pattern
      updates with it — but the `pf` naming `body_t` in `solrconfig.xml` is left exactly as
      written, because `body_t` is a name the pattern supplied and rewriting it to `*_txt`
      would put a glob where a field name belongs.
- [ ] **REN-5** — Still in [the deliberately-partial dynamic-field rename](#4a-rename-ren)'s
      state, look at that `pf`: `body_t` is now underlined by the
      unknown-field inspection, because nothing declares it any more. **That report is what
      makes [that partial rename](#4a-rename-ren) defensible** — the configset is broken, and the
      plugin says so, rather than leaving it silently wrong. **Undo it and confirm the underline
      goes.**

## 5. [Quick documentation](glossary.md#documentation-provider) (DOC)

*Automated: `SolrConfigsetDocumentationProviderTest`, `SolrFieldPresentationTest`,
`SolrSchemaElementsTest`, `SolrSchemaVersionTest`, `SolrFieldPropertiesTest`,
`SolrReferenceGuideTest`. Manual adds: popup rendering, link clickability.*

- [ ] **DOC-1** — F1 on a field's `type` value shows the type, its analyzer chain, and
      what a field of it can match.
- [ ] **DOC-2** — The popup carries a Reference Guide link naming the Solr version this
      configset targets, and the link opens.
- [ ] **DOC-3** — The element *tags* answer on hover — `<schema>`, `<field>`,
      `<fieldType>`, `<dynamicField>`, `<copyField>`, `<uniqueKey>` — each with a
      configset-specific sentence where one exists (which fields the copy rule joins,
      which field is the unique key and of what type, how many fields use a type).
- [ ] **DOC-4** — A `class=` value answers on hover — `solr.WordDelimiterGraphFilterFactory`
      shows what kind of class it is, both spellings, the attributes it reads, how this
      configset uses it, and a Reference Guide link — plus the one-sentence Javadoc summary
      the catalog's documentation column carries, where the line's `-sources` artifacts
      supplied one. `solr.StandardTokenizerFactory` reads "Factory for StandardTokenizer."
- [ ] **DOC-4b** — Hover the *tag* of the demo's EdgeNGram filter (the word `filter`, not the
      `class` value): the popup shows a **Configuration** table of every attribute the class
      accepts. Written `minGramSize` / `maxGramSize` are bold and labelled *on this filter*;
      unwritten `preserveOriginal` appears as **false** labelled *Solr default*. An attribute
      the catalog records no literal default for — `luceneMatchVersion` — shows an em dash
      labelled *no default recorded*: the dash is the feature, not a missing value. A custom
      `class` the catalog does not know must offer nothing on the tag — not an empty table.
- [ ] **DOC-5** — On the demo's `version="1.6"` schema, a field's property table reports
      `uninvertible` as **true** and names its origin *Solr default at schema version 1.6*,
      and `useDocValuesAsStored` likewise.
- [ ] **DOC-6** — Change the root element to `version="1.7"`, re-open the same popup:
      `uninvertible` now reports **false**, `useDocValuesAsStored` stays true, and both
      origins name 1.7. **Undo, and confirm the values return.** One side alone proves
      nothing — a table hard-coding `true` passes
      [the check on schema version 1.6's property defaults](#5-quick-documentation-doc) —
      so it is the flip that is the check.
- [ ] **DOC-7** — Hover `minGramSize` on an `EdgeNGramFilterFactory` (or F1 with the caret
      on the attribute name). The popup names the owning class, the value type (*a whole
      number*), and the required marker, above them a **Does** row saying what the attribute
      does — *the shortest gram to index* — which is written by hand for the two dozen
      attributes a reader hovers rather than recovered from bytecode. Hover `preserveOriginal`
      on the same filter: the popup shows the catalog default `false` instead of a required
      marker. An attribute name the catalog does not list, or any attribute on a class the
      catalog does not know, stays silent.
- [ ] **DOC-8** — **The attribute's own name answers**, which is the caret position that used
      to say nothing while the element above it and the value beside it both did. Hover each
      of these attribute *names*: `name` and `type` on a `<field>`; `name` and `class` on the
      `<fieldType>`; `source` and `dest` on a `<copyField>`. Each explains itself, and the
      two `copyField` ends read differently from each other.
- [ ] **DOC-9** — Hover `version` on the demo's `<schema>` root. Two paragraphs: what the
      attribute decides anywhere, then **what `1.6` decides here** — `docValues` off,
      `uninvertible` on. The second paragraph is computed from the file, so
      [the version-flip check](#5-quick-documentation-doc)'s edit to
      `1.7` should flip it; check that too while the file is already changed, and undo with
      the rest.
- [ ] **DOC-10** — **The absence, which is the one that can regress quietly.** Hover an
      attribute the hand-written table does *not* describe — `maxShingleSize` on a
      `solr.ShingleFilterFactory`, or any catalog-backed name outside the two dozen — and the
      popup is **exactly what it was before the table existed**: owner, value type, default or
      required marker, guide link, and **no Does row**. The table is silent by default
      everywhere, and a popup appearing where none should is the failure this check is for.

      **This check previously described the opposite of what ships.** It read that the prose
      row had been drafted and withdrawn, on the argument that the popup already carries a
      per-attribute guide link and prose would duplicate something always current.
      That argument is recorded and still open in the design record, but it was never acted on:
      the table shipped with the step and the *Does* row is on screen, which is what
      [the check on a factory's attribute hover](#5-quick-documentation-doc) now
      asks for. A verification check stating the opposite of the shipped behaviour is worse than
      a missing one, because pressing it invites deleting a feature to make the document true.

      *The other half of this check has no fixture.* A `copyField`'s `name` must never gain the
      `<field>` description — but the committed demo declares no `<copyField name=…>`, so there
      is nothing to hover, the same shape of gap
      [the check on a filter's resource path](#4-navigation-and-find-usages-nav) records.
      `SolrAttributeDocumentationTest`
      covers it headlessly, along with the whole inventory of positions that answer; either the
      demo grows one or this stays a note.

- [ ] 📸 **Re-capture `docs/images/02-quick-doc-field.png`** at
      [the quick-documentation check on a field](#5-quick-documentation-doc) — caret inside
      `name="category"` at line 70, F1, cropped to the popup **including the
      `uninvertible` row**. **Check the catalog entry before shooting:** this one waits on
      the field-type-class resolution of `omitNorms` and `docValues`, and is stale on
      arrival if taken before that lands.
      [Catalog entry 2](screenshots.md#2-quick-documentation-on-a-field--02-quick-doc-fieldpng).
      [The quick-documentation check on a class value](#5-quick-documentation-doc)'s
      `03-quick-doc-class.png` is current and needs nothing.

**[The quick-documentation check on a class value](#5-quick-documentation-doc)'s `Accepts`
table shows a name and a value type, and nothing more.** That is the
class-*value* popup on purpose, and Javadoc is per class, so it has no honest per-argument
prose to add beside a default either. Defaults and required markers live on the other two
positions: on one attribute as
[the check on a factory's attribute hover](#5-quick-documentation-doc), and on the whole tag
as [the check on a factory's complete configuration](#5-quick-documentation-doc)'s
complete-configuration table. A tester looking for `minGramSize` marked required on the
class value wants one of those two, and is looking at the wrong element rather than at a
broken feature.

**[The check on schema version 1.6's property defaults and its version-flip
counterpart](#5-quick-documentation-doc) are one check in two halves, and the pair is the
point.** Solr's field
defaults are not constants: `uninvertible` defaults true below schema version 1.7 and
false from it, which is how Solr changed a default without breaking deployed schemas. A
provider that ignores the version entirely still passes the 1.6 half, because 1.6 is where the
demo sits — only the version-flip's result distinguishes reading the file from hard-coding its answer.
`SolrFieldPropertiesTest` and `SolrBooleanPropertyCompletionTest` already assert both
sides headlessly; what these two add is that the popup and the completion list render what
resolution decided, which no fixture can see.

The version-flip check edits the demo, so it belongs with the
[inspection and quick-fix checks](#6-inspections-and-quick-fixes-insp) in ending on an undo.
The demo stays at 1.6 permanently and says so in a comment — a *committed* bump to 1.7 would not
break these checks so much as delete
[the 1.6 property-defaults check](#5-quick-documentation-doc), leaving the suite testing one
side of a boundary again.

## 6. [Inspections](glossary.md#inspection) and [quick-fixes](glossary.md#quick-fix) (INSP)

*Automated: `SolrDanglingCopyFieldInspectionTest`, `SolrUnknownFieldTypeInspectionTest`,
`SolrUnknownFieldReferenceInspectionTest`, `SolrUnknownAttributeInspectionTest`,
`SolrInvalidAttributeValueInspectionTest`, `SolrAnalyzerChainOrderInspectionTest`,
`SolrUnusedFieldTypeInspectionTest`, `SolrNonIndexedRelevanceFieldInspectionTest`,
`SolrMisspelledParameterInspectionTest`, `SolrDiscontinuedElementInspectionTest`,
`SolrReferenceQuickFixTest`. Manual adds:
live reaction to edits, fix application through the real Alt-Enter menu.*

Every check here ends with **undo until
[the baseline](#2-zero-false-positive-baseline-base) is clean again**.

- [ ] **INSP-1** — Change a `<copyField>` dest to a name no field declares: underlined,
      and Alt-Enter offers the declared fields, closest spelling first.
- [ ] 📸 **Capture `docs/images/04-inspection-copyfield-quickfix.png`** — use the *planted*
      `manufacturer` rule at line 92 rather than the edit
      [the dangling-copyField check](#6-inspections-and-quick-fixes-insp) makes, so the image
      needs no undo. Frame the underline and the open Alt-Enter menu, and dismiss it with Escape —
      every item in it rewrites the file.
      [Catalog entry 4](screenshots.md#4-inspection-and-quick-fix--04-inspection-copyfield-quickfixpng).
- [ ] **INSP-2** — Change a field's `type` to a bogus value: underlined, fix offers the
      declared types; applying one produces a file that parses clean.
- [ ] **INSP-3** — Delete the `name` field entirely: its copy rule flags immediately,
      without saving or reopening.
- [ ] **INSP-4** — In `solrconfig.xml`, point a handler parameter (`qf` or `df`) at a
      field the schema does not declare: flagged.
- [ ] **INSP-5** — Add a made-up attribute to a `<field>` tag: the unknown-attribute
      inspection fires, naming the element that cannot accept it.
- [ ] **INSP-6** — Set `indexed="yes"`: the invalid-attribute-value inspection fires.
- [ ] **INSP-7** — The ordering check, in three edits to `text_prefix`'s **index** analyzer
      (lines 45–49), because a claim about order needs both sides:
      1. Add `<filter class="solr.WordDelimiterGraphFilterFactory" splitOnCaseChange="1"/>`
         *below* the `LowerCaseFilterFactory`: the `1` is underlined, and the message names
         the filter that already folded the case away.
      2. Move that same filter *above* the `LowerCaseFilterFactory`: the warning clears.
         Nothing was added or removed, so this is the half that proves the check is about
         order rather than presence.
      3. Add `<filter class="solr.FlattenGraphFilterFactory"/>` above the word-delimiter
         filter: its `class` is underlined, naming the graph filter below it.
- [ ] **INSP-8** — Change `name_prefix`'s `type` from `text_prefix` to `text_general`:
      the `<fieldType name="text_prefix">` declaration nothing now names goes **dim**,
      immediately and without saving. Dimmed, not underlined — this is the one finding in
      the section that is dead configuration rather than a defect, and the presentation is
      the claim being checked. The demo's other three types stay lit, so a rule that dims
      every type would fail here rather than pass silently.
- [ ] **INSP-10** — In `solrconfig.xml`, add `<str name="sort">text asc</str>` to the `/select`
      handler's `defaults`. `text` is underlined: it is `multiValued`, so several values have no
      defined order and Solr rejects a plain sort on it. **The message names what sorting needs
      rather than asserting which part is missing** — this field is indexed and, at the demo's
      `version="1.6"`, un-invertible, so a message blaming doc values would be false here.
- [ ] **INSP-11** — In `solrconfig.xml`, add `<arr name="facet.field"><str>category</str></arr>` to
      the `/select` handler's `defaults`. **Nothing fires**, and it keeps not firing when the schema
      root is changed to `version="1.7"`. That second half is the correction a sandbox pass made to
      this check: 1.7 is *also* the version at which `solr.StrField` gains doc values by default —
      the catalog records it as `primitive,docValuesByDefault` — so flipping the version makes
      `category` **more** facetable, not less. The version flip cannot demonstrate an unfacetable
      field on a `string`, and a check that expected it to was testing the wrong lever.
      To see the warning, declare the absence rather than defaulting it: add `docValues="false"` to
      `category` in the schema. `category` in the `facet.field` is then underlined, and at 1.6 it is
      not, because `uninvertible` defaults true below 1.7. Undo both edits.
- [ ] **INSP-12** — With
      [the unfacetable-field check](#6-inspections-and-quick-fixes-insp)'s `docValues="false"`
      still on `category`, `<str name="qf">category</str>`
      stays clean while the `facet.field` is underlined. **The same field, searchable and unfacetable at
      once** — the check that the two inspections ask different questions rather than one question twice.
      The completion side shows the same split with no edit at all:
      [the sort-clause completion check](#9-completion--field-names-inside-solrconfigxml-parameters-prm)'s
      `sort` list withholds `text` where the `qf` list offers it.
- [ ] **INSP-13** — In `solrconfig.xml`, add `<indexConfig><nrtMode>true</nrtMode></indexConfig>`
      inside `<config>`: the element name is underlined and the message is **Solr's own sentence**,
      naming the config as discontinued. Add `<indexDefaults>` directly inside `<config>` instead —
      that one really is top-level — and the message names `<indexConfig>` as the replacement.
      **The wording is the feature** — a paraphrase would drop the only part saying what to do next.
      Now move the same `<nrtMode>` inside a made-up `<acmeThing>` wrapper: the warning goes, because
      a retirement belongs to a position rather than to a word. Undo.
- [ ] **INSP-13b** — Move that same `<nrtMode>` up one level, directly inside `<config>`:
      **the warning goes**, and it should. Solr reads this element as
      `get(indexConfigPrefix).get("nrtMode")` and never looks for it at the root, so a `<nrtMode>`
      under `<config>` is inert — Solr neither honours nor complains about it. This is the position
      the rule used to fire on, and the check exists because the earlier wording of
      [the discontinued-element check](#6-inspections-and-quick-fixes-insp) asked for
      the underline *here* and was pressed green against it. See the pass-log note for
      2026-08-16 and `SolrConfigElements.UNPLACED` for how the catalog came to believe it.
- [ ] **INSP-14** — Inside the `/select` handler's `<lst name="defaults">`, rename `<str name="rows">`
      to `<str name="rwos">`: underlined, and Alt-Enter offers `rows`. Then write `<str name="pf2">`
      and `<str name="pf3">` side by side — **neither is flagged**, though they are one edit apart.
      That pair is the whole reason the rule checks knownness before distance. Undo.
- [ ] **INSP-15** — Change the `name` field's `indexed="true"` to `false` at `managed-schema.xml:68`, then look
      at the `/select` handler's `qf` at `solrconfig.xml:28`. `name` is underlined — **`name` alone, not the
      `^3` beside it and not the two field names after it** — reading *Solr: 'name' is not indexed and has no doc values, so 'qf'
      cannot search or boost it*, and `solrconfig.xml` goes from
      [the `solrconfig.xml` baseline check](#2-zero-false-positive-baseline-base)'s zero problems to
      exactly one.
      The finding is invisible in Solr: the core starts, the query runs, and the field simply never
      matches. **One of two such checks rather than the only one** —
      [the analyzer-chain ordering check](#6-inspections-and-quick-fixes-insp)'s finding is the
      other, and a chain whose filter never runs is just as silent. Undo.

      *What lets this fire is a catalog row rather than the edit.* Searchable is `indexed` **or**
      `docValues`, and the rule reports nothing unless it can say `docValues` is definitely true or
      definitely false — an *undetermined* `docValues` leaves it with no answer and this check with
      nothing to see. `docValues` is undetermined whenever the field type's class carries no entry in
      the generated catalog's **traits column**, the per-class list (`primitive`, `sortableText`,
      `docValuesByDefault`, and so on) the build records for every field-type class it recognises and
      uses to work out what a property defaults to. `name` is `text_general`, whose class is
      `solr.TextField` — a class the catalog *does* know, and knows to carry none of those traits, so
      its traits column is **empty** rather than missing. Empty is not the same claim as missing:
      missing means the catalog never heard of the class and the rule must stay silent; empty means
      the catalog looked and found nothing, which lets `docValues` resolve to a definite false at the
      demo's `version="1.6"`. **That distinction is the whole reason this check can fire at all** — had
      `text_general` named a class the catalog does not carry, `docValues` would stay undetermined,
      the same edit would underline nothing, and the check would read as correct forever without ever
      having proved anything.
- [ ] **INSP-9** — Undo everything, including
      [the version-flip check](#5-quick-documentation-doc)'s version edit: both files return to
      [their baseline](#2-zero-false-positive-baseline-base) counts — **two** warnings in
      `managed-schema.xml`, zero in
      `solrconfig.xml`. Not zero and zero; the planted `manufacturer` copyField and the
      planted `legacy` field's undeclared type are part of the baseline and stay underlined.
      None of [the analyzer-chain ordering check](#6-inspections-and-quick-fixes-insp)'s three
      edits survives into the baseline: the demo's own chains are
      correctly ordered. No dimmed type is part of the baseline: every type the demo
      declares has a field behind it.

## 7. [Completion](glossary.md#completion-contributor) — the schema's own vocabulary (COMP)

*Automated: `SolrSchemaVocabularyCompletionTest`, `SolrBooleanPropertyCompletionTest`,
`SolrCopyFieldCompletionTest`, `SolrFieldTypeCompletionTest`, plus the pure-model
`SolrFieldPropertiesTest` and `SolrAttributeVocabularyTest`. Manual adds: the popup as
the user meets it — ordering, summaries, what the platform mixes in.*

- [ ] **COMP-1** — Typing `<` inside `<schema>` offers the elements legal there and
      nothing else.
- [ ] **COMP-2** — Attribute completion inside `<field ` offers the property table with
      each property's summary, and **omits** attributes already present on the tag.
- [ ] **COMP-3** — `fieldType` general properties complete (`positionIncrementGap`,
      `synonymQueryStyle`, `enableGraphQueries`…), documented like field properties.
- [ ] **COMP-4** — The boolean value Solr would have used anyway is marked as the default,
      whichever it is — `indexed` marks `true`, `multiValued` marks `false`; properties
      whose default depends on the field type stay unmarked.
- [ ] **COMP-5** — A field's `type=` offers the declared types; a `copyField`'s two ends
      offer the declared fields; `<analyzer type=` offers `index`/`query`.
- [ ] **COMP-6** — On the demo's 1.6 schema, `uninvertible=` marks **true** as the default.
      Change the root element to `version="1.7"` and it marks **false** instead; undo. Same
      claim as [the schema-version property-default checks](#5-quick-documentation-doc), in the
      surface where a reader meets it first, and the same reason for testing both sides.
- [ ] 📸 **Capture `docs/images/05-completion-field-properties.png`** — caret after
      `stored="true"` on line 70 and before the `/`, type a space, frame the summaries and
      the accepted values. Undo the space afterwards.
      [Catalog entry 5](screenshots.md#5-completion-over-the-schemas-own-vocabulary--05-completion-field-propertiespng).

## 8. Completion — catalog-backed (CAT)

*Automated: `SolrClassCatalogTest`, `GenerateSolrCatalogTaskTest`,
`SolrFieldPropertyDriftTest`. Manual adds: the end-to-end path from generated TSV to
popup, against the demo configset's declared Solr line.*

- [ ] **CAT-1** — `class=` on a `<fieldType>` offers `solr.*` field type classes; on a
      `<tokenizer>` or `<filter>`, the factories — each set following the configset's
      declared Solr line.
- [ ] **CAT-2** — `<filter class="solr.WordDelimiterGraphFilterFactory" ` offers the
      factory's own attributes — `generateWordParts`, `catenateAll`,
      `splitOnCaseChange` among them. **This is the check that proves the constructor-bytecode
      pass reached the editor** — the build-time step, in `buildSrc`, that reads each analysis
      factory's compiled `.class` file with the ASM library and walks its constructor
      instruction by instruction to recover the names of the arguments it reads. A factory
      declares its own attributes only in that one constructor and nowhere else, which is why
      reading it is enough: nobody hand-maintains this list, and CAT-2 is what confirms the list
      the build extracted is the one the editor actually offers.
- [ ] 📸 **Capture `docs/images/06-completion-factory-attributes.png`** — not from
      [the constructor-bytecode attribute check](#8-completion--catalog-backed-cat),
      which needs a class the demo does not declare. Use the `EdgeNGramFilterFactory`
      filter at line 48, caret before the closing `/`, space, and frame
      `luceneMatchVersion` and `preserveOriginal` — not `minGramSize` and `maxGramSize`,
      which the filter already declares and completion therefore omits. Same bytecode
      route, no fixture edit.
      [Catalog entry 6](screenshots.md#6-catalog-backed-factory-attributes--06-completion-factory-attributespng).
- [ ] **CAT-3** — `class=` on a `<requestHandler>` in `solrconfig.xml` offers
      `solr.SearchHandler` and `solr.UpdateRequestHandler`, and **does not** offer
      `solr.SchemaCodecFactory` or any field type. Repeat on the demo's
      `<directoryFactory>` and `<codecFactory>`, which each offer their own handful.
- [ ] **CAT-4** — F1 on `solr.SearchHandler` says *request handler* and offers a Reference
      Guide link that lands on a page describing request handlers. **The link is the half
      worth checking by hand**: nothing in the build establishes that a constructed URL
      resolves, so a page the guide renames fails here and nowhere else.

*Why [the request-handler class completion check and the class-value documentation
check](#8-completion--catalog-backed-cat) are manual at all, given fixtures cover both.*
The three surfaces they
exercise — completion, documentation, navigation — were never written for `solrconfig.xml`;
they were already general, and gained the whole file when the catalog learned Solr's plugin
kinds. Nothing in that diff looks like a feature, which is exactly why a gesture belongs here.

---

## 9. Completion — field names inside [`solrconfig.xml`](glossary.md#solrconfigxml) parameters (PRM)

*Automated: `SolrParameterFieldCompletionTest`, `SolrConfigFieldReferenceTest`. Manual adds: the
popup where a reader actually meets it, inside a string the platform has no vocabulary for.*

This is the inverse of
[the dangling-copyField check](#6-inspections-and-quick-fixes-insp)'s warning. The list that lets
an inspection say `descriptoin` is not a
field is the list that offers `description` first, and until it shipped the plugin only ever
corrected.

- [ ] **PRM-1** — In the `/select` handler, put the caret inside `<str name="qf">` after the
      existing value and invoke completion. The schema's fields are offered, and `*_t` appears
      italicised as a dynamic pattern rather than a field that exists.
- [ ] **PRM-2** — Completion inside `<str name="rows">` offers no field names. `rows` holds a
      number, and every parameter in the file looks alike — this is the check that the offer is
      scoped to the parameters known to hold field names.
- [ ] **PRM-3** — With the caret immediately after the `^` in `name^3`, completion offers no
      field. A boost is not a field name, and completing there would produce `name^name`.
- [ ] **PRM-10** — F1 at that same position — inside the `^3` — **does** answer: what the boost
      scales, `name` and its searchability, and nothing in the Problems view. **PRM-3 and this are
      one check in two halves**, and the pair is the point: completion's silence there is correct
      and was mistaken for the position being settled, so documentation was never asked until
      [Step 30](../specs/plans/0002-solr-intellij-plugin-plan.md#step-30-explaining-a-boost-done).
      Two characters left, on `name` itself, PRM-5's popup must be unchanged.
- [ ] **PRM-11** — F1 inside the `^2.5` of the `/select` handler's `bf` explains an *additive*
      function-query boost and names **no** field, where the `qf` beside it names one. The same
      `^n`, two different meanings — and `bf` is the parameter the relevance inspection deliberately
      stays silent on, so this is also the check that explaining and reporting are separate
      decisions.
- [ ] **PRM-4** — In a `<str name="sort">`, completion offers fields at the start of a clause and
      nothing after `text ` — the second token of a sort clause is a direction, not a field.
- [ ] **PRM-6** — the `name` of a `<str>` inside a `<lst name="defaults">` offers Solr's request
      parameters, each with what it is for beside it: `qf` reads *query and init param for query
      fields*. **This is the position the vocabulary is learnable from** — field completion inside a
      `qf` presumes the reader already knew to write `qf`.
- [ ] **PRM-7** — a parameter already set on the same list is *not* offered again, and `defType` **is**
      offered even though Solr declares it outside the package every other parameter comes from.
- [ ] **PRM-8** — `<str name="defType">` offers `edismax`, `dismax`, `lucene`, `func` and no class
      name; a `<queryParser class=…>` offers the classes and no registry key. **The two populations
      describe the same plugins and must not cross**: a class name in a `defType` is a configset Solr
      cannot load.
- [ ] **PRM-9** — F1 on `qf` says what `qf` is and names `DisMaxParams`; F1 on `edismax` names
      `ExtendedDismaxQParserPlugin`. F1 on a parameter Solr does not declare — `my.own.param` — shows
      **nothing at all**, which is the contract rather than a gap: a custom component reads parameters
      no generator will ever see.
- [ ] **PRM-5** — F1 on a field name inside the `qf` shows *the field's* documentation — its type
      and analyzer chain — not the `<str>` element's. **This works through reference resolution
      rather than a documentation branch written for it**, so it is the gesture most likely to
      disappear silently when either provider changes.

## 10. Completion — `solrconfig.xml`'s own structure (STR)

*Automated: `SolrConfigElementDescriptorTest`, `SolrConfigDescriptorContractTest`,
`SolrElementCatalogTest`, `SolrConfigAttributeCompletionTest`. Manual adds: the platform's own
sibling echo is what these replace, and only a real editor shows which one answered.*

**What makes this section worth pressing is the failure it replaces.** Before the plugin owned these
descriptors, the platform ran schema-less and offered whatever a sibling tag happened to be named —
a guess that looks exactly like knowledge, in a file made almost entirely of same-named tags.

- [ ] **STR-1** — Type `<` on a blank line directly inside `<config>`: the offer is Solr's
      top-level vocabulary — `requestHandler`, `updateHandler`, `luceneMatchVersion`, `dataDir` —
      and **not** a copy of whatever sibling tags already exist above the caret.
- [ ] **STR-2** — Do the same inside `<query>`: `filterCache` and its siblings are offered, and
      `dataDir` is **not**. Nesting is the point — what belongs under `<query>` is not what belongs
      under `<config>`. **The demo declares no `<query>`**, so type one first; the catalog keys
      `filterCache` to that parent and `dataDir` to the root, which is what the check reads.
- [ ] **STR-3** — `nrtMode` is **not** offered anywhere, though the catalog carries it. An element
      Solr rejects must never be completed;
      [the discontinued-element inspection check](#6-inspections-and-quick-fixes-insp) is the same
      fact from the other side, reporting one already written.
- [ ] **STR-4** — Inside a made-up `<acmeThing>`, completion offers nothing rather than echoing its
      siblings, and typing any element inside it draws no warning. **Silence and permissiveness
      together** — the plugin has nothing to say about a custom component and must not pretend either
      way.
- [ ] **STR-5** — Inside a `<filterCache>` under `<query>`, invoke attribute completion: Solr's cache
      attributes are offered — `autowarmCount`, `class`, `enabled`, `initialSize`, `maxRamMB`,
      `regenerator`, `size`. Add an attribute of your own invention and it is **not** underlined:
      completion offers what a source describes, while judgement about an unknown attribute belongs
      to the inspections and they decline it here.
- [ ] **STR-6** — In a `<requestHandler>`, the same gesture offers **nothing**, and that is correct
      rather than a gap. Attribute completion has exactly one source to draw from:
      `EditableSolrConfigAttributes.json`, a resource shipped inside Solr's own jar that types the
      handful of subtrees Solr's Config API can rewrite at runtime. That resource covers seven paths
      — the four caches, `requestParsers`, and the two commit elements — and `<requestHandler>` is not
      one of them, so completion there has nothing to read from. `name` and `class` are real,
      required attributes on a `<requestHandler>`, but Solr does not declare them through that
      resource either: they come from `SolrConfig.plugins`, the list in Solr's own source that
      registers every pluggable element, where each entry carries `REQUIRE_NAME` and `REQUIRE_CLASS`
      flags saying so. This build's generator already reads that same list — it is where
      `<requestHandler>` itself, and the classes it may name, come from — but only for the plugin's
      tag name and class root, not for those two requirement flags. **Silence here is the honest
      answer**; an offer would mean the plugin had started guessing at attributes it has never
      actually read. The day the generator also decodes those flags, this check inverts, and it
      should be rewritten rather than deleted.

## 11. An attribute that restates its default (DIM)

*Automated: `SolrRestatedDefaultAnnotatorTest`, `SolrRemoveRestatedAttributeIntentionTest`.
Manual adds: dimming is a rendering claim, and only a real editor shows whether it reads as
"redundant" rather than as "broken".*

**The one surface here that must never reach the Problems view.** A restated default is correct
Solr; the file is right and merely says something twice.

**The untouched demo is already full of restated defaults, so
[the untouched-schema dim check](#11-an-attribute-that-restates-its-default-dim) needs no edit.**
Every field
declares `indexed="true"` and most declare `stored="true"`, and Solr defaults both to true — so the
dim is part of the baseline rather than something a gesture produces. That is worth knowing before
[the zero-false-positive baseline check on the schema](#2-zero-false-positive-baseline-base): a
dozen greyed attributes in a clean schema is this feature working, not a defect.

- [ ] **DIM-1** — Open `managed-schema.xml` untouched. The `indexed="true"` and `stored="true"` on
      the field block render greyed, whole-attribute, and **nothing about them appears in the
      Problems view**. `stored="false"` on `name_prefix` and `text` is **not** greyed.
- [ ] **DIM-2** — Alt-Enter on one of the greyed attributes offers to remove it, and removing leaves
      a schema that still parses and a field whose documentation reports the same effective values as
      before. Undo.
- [ ] **DIM-3** — Change `name`'s `indexed="true"` to `indexed="false"`: the dim goes. The attribute
      now decides something, and the difference between deciding and restating is the whole feature.
      Undo.
- [ ] **DIM-4** — Add `enableGraphQueries="true"` to a `<field>`: **not** dimmed, since Solr ignores
      it there and calling it removable would be right for the wrong reason. Add the same attribute
      to a `<fieldType>`: dimmed, because there it is legal and does default to true. Undo both.

*The inherited-default half of DIM-4 has no fixture.* It wants a `<dynamicField>` repeating a value
its `<fieldType>` declares, and the demo's `*_t` is `text_general`, which declares no `omitNorms`.
`SolrRestatedDefaultAnnotatorTest` covers that path headlessly; either the demo grows a type that
declares one, or this stays automated-only.

## 12. [Intentions](glossary.md#intention) — companion fields (INT)

*Automated: `SolrAddPrefixCompanionIntentionTest`, `SolrAddExactCompanionIntentionTest`.
Manual adds: these are the only gestures that write a new declaration, so the result has to be read
as a schema rather than as a diff.*

- [ ] **INT-1** — Alt-Enter on a tokenised text field offers to add an exact-match companion; the
      generated `<field>` and its `<fieldType>` land in the schema and the file still parses.
- [ ] **INT-2** — The same field no longer offers the intention once its companion exists, and a
      field that is already whole-value — a `string` — never offers it at all. **An intention that
      keeps offering itself after it has been applied is the failure mode here**, since nothing
      underlines to tell a reader the work is done.

## 13. Connections and the collections tool window (SRV)

*Automated: `SolrConnectionsConfigurableTest`, `SolrConnectionDialogTest`, `SolrTopologyNodesTest`,
`SolrCollectionsViewTest`, `SolrCollectionsPanelTest`. Manual adds: **that any of it appears at
all**. The headless test environment registers no tool windows — not this plugin's, not any — so
whether the registration takes effect is a question only a running IDE can answer. Everything below
needs a Solr; `docker run -p 8983:8983 solr:10.0.0 solr-precreate books` is enough for all of them.*

- [ ] **SRV-1** — Settings → Tools → **Solr Connections** exists, and `+` opens a form. Saving
      `http://localhost:8983/solr` adds a row showing that URL.
- [ ] **SRV-2** — The form refuses to save a URL with no scheme, naming the field, and accepts one
      with a path that is not `/solr`. **Accepting the second matters more than rejecting the
      first**: Solr behind a reverse proxy is ordinary, and a form that demanded `/solr` would
      reject working deployments.
- [ ] **SRV-3** — Re-open a saved connection with a password stored: the password field is **empty**
      and says a password is stored. Change only the display name and press OK. Re-open: the
      password is still stored. **This is the regression the overload exists to prevent** — the
      field being empty must not be read as "no password".
- [ ] **SRV-4** — The **Solr** tool window opens on the right and lists what the server holds:
      `Cores` → `books` for a standalone server, `Collections` → shards → replicas for a cloud one.
- [ ] **SRV-5** — Stop Solr and press Refresh. The failure appears **inline above the tree, once**,
      and the tree empties. No popup, no dialog, and no stale topology left under the banner.
- [ ] **SRV-6** — Start Solr again and press Refresh: the banner clears and the tree repopulates.
      Then leave it alone for a few minutes and confirm **nothing refetches on its own** — server
      data moves on request and on connection change, and on nothing else.
- [ ] **SRV-7** — With two connections configured, switching the selector re-reads the other server
      and the selection survives closing and reopening the project.
- [ ] **SRV-8** — Index a document with an undeclared field that a dynamic pattern matches —
      `curl 'http://localhost:8983/solr/books/update?commit=true' -H 'Content-Type: application/json'
      -d '[{"id":"1","author_s":"Frank Herbert","price_f":9.99}]'`. Expand the collection's
      **Fields** row: `author_s` appears, marked `← *_s`. **This is the check the whole view exists
      for** — that field is in no configset and the schema view cannot show it.
- [ ] **SRV-9** — In the same list, `price_f` shows its type and **no document count**, while
      `author_s` shows one. A point field has no inverted index to count from, and "0 docs" there
      would be false. `_root_` shows `(unstored field)` rather than decoded flags.
- [ ] **SRV-10** — Collapsing and re-expanding a **Fields** row does not re-read the server; only
      Refresh does. Watch Solr's request log to confirm rather than inferring it from the screen.
- [ ] **SRV-11** — Create a file called `queries.http` in the project. Press **Add Request** (the `+`
      in the gutter, or Alt-Insert): a **Solr** group offers five starting requests. Insert *Query a
      Solr collection*. **The menu labels must read as labels** — a whole request body appearing
      where a label belongs is what a swapped `TemplateDescriptor` argument looks like, and it
      compiles and ships perfectly.
- [ ] **SRV-12** — Beside it create `http-client.env.json` holding
      `{"local": {"solrUrl": "http://localhost:8983/solr", "collection": "books"}}`, pick **local**
      from the environment chooser, and run the request. Results render in the HTTP Client's own
      response viewer. **The point of the check is that the committed file names no host** — the
      environment does, which is what lets a colleague clone the repository and use their own server.

## Not yet in the suite

Checks join a section above when their feature ships; **which features those are is the
[plan's](../specs/plans/0002-solr-intellij-plugin-plan.md) to say**, not this file's. The
list below is what the suite does not yet cover, and says nothing about what is built —
finding one of these gestures alive means the suite is behind, not that something is wrong:

- The settings page and *Mark Directory as Solr Configset Root*
- The server-side surfaces that have not shipped: query console, drift view, indexing test
  documents. Connections and the collections tool window are covered in section 13 above
- Everything in Java/Kotlin code: field-name checks, query language injection
- `omitNorms` and `docValues` resolved from the field type's class. Both report *see the
  guide* today, which is the honest answer while the catalog cannot say which traits a
  type carries — [the check that a field's property table reports a version-derived
  default](#5-quick-documentation-doc)'s version resolution settles a different pair of properties
- A configuration element **removed between two supported Solr lines**, which is a different check
  from [the discontinued-element check](#6-inspections-and-quick-fixes-insp) and has nothing to
  press: the two lines differ by one element, `featureVectorCache`,
  *added* in 10. Nothing was removed, so there is no gesture until a future line drops something
- **Do not read INSP's length as an inspection count** — eleven inspection classes exist, and the
  [INSP checks above](#6-inspections-and-quick-fixes-insp) are scenarios over them rather than one
  apiece: [INSP-1 and INSP-3](#6-inspections-and-quick-fixes-insp) are both the
  dangling-`copyField` inspection, once on a written edit and once on a live deletion;
  [INSP-10 to INSP-12](#6-inspections-and-quick-fixes-insp) are the two `solrconfig.xml` field
  checks, including one scenario whose whole point is
  that the two disagree about the same field; and [INSP-9](#6-inspections-and-quick-fixes-insp)
  restores the baseline rather than testing
  anything. **Four of the eleven belong to plan steps other than the inspections one**, and their
  checks sit here all the same: the two attribute checks at
  [INSP-5 and INSP-6](#6-inspections-and-quick-fixes-insp), the misspelled
  parameter at [INSP-14](#6-inspections-and-quick-fixes-insp), and the unsupported field operation at
  [INSP-10 to INSP-12](#6-inspections-and-quick-fixes-insp). The seven the
  inspections step plans have a gesture apiece at
  [INSP-1 to INSP-4, INSP-7, INSP-8, INSP-13 and INSP-15](#6-inspections-and-quick-fixes-insp) —
  all seven, since the last two of those were written. **A gesture is not a pass**; which
  of them anyone has pressed is the [pass log](#pass-log)'s to say and no other line's

### 2026-08-10 — the `solrconfig.xml` checks, and what pressing them settled

Seven checks, all green, and two of them told me more than they were written to.

- **PRM-1** — completion inside the `/select` handler's `qf` offers every declared field with its type
  as tail text, and `*_t` appears as the dynamic pattern. Ten entries for nine fields and one pattern.
- **PRM-2** — `<str name="rows">` reports **No suggestions**. Positive evidence rather than an absent
  popup, which matters: an empty crop of the screen looks the same as a correct refusal.
- **PRM-3** — a caret immediately after the `^` in `name^3` reports **No suggestions**. Completing
  there would have produced `name^name`.
- **PRM-4** — both halves. Fields are offered at the start of a `sort` clause, and **No suggestions**
  inside the direction. **The offered list withheld `text`, which the `qf` list had included** — `text`
  is `multiValued`, so it is searchable and unsortable, and completion said so without being asked to.
  That is INSP-12's argument arriving from the completion side.
- **PRM-5** — Quick Documentation on `description` inside the `qf` shows *the field's* popup: its type,
  *Matches: tokenised, case-insensitive*, and the full property table. Reached through reference
  resolution rather than a documentation branch written for it, and the **Meaning** column is populated
  — the hand-written attribute meanings rendering where no fixture had shown them. `uninvertible`
  reads *Solr default at schema version 1.6*, which is the version derivation doing its job in the
  same table.
- **INSP-10** — `<str name="sort">text asc</str>` underlines `text` with, verbatim: *Solr: 'sort' will
  fail on 'text' — it needs doc values or an un-invertible index, and for sorting a single value per
  document*. **This is the message that was reworded because the original was false for a multiValued
  field**, and this is the field it was false about.
- **INSP-11, clean half** — a `facet.field` on `category` fires nothing at the demo's `version="1.6"`,
  because `uninvertible` defaults true below 1.7.

**INSP-11's version flip was pressed on the second attempt, and it found that the check was wrong.**
With the schema at `version="1.7"` and the `facet.field` in place, the file was **completely clean** —
no warning at all. That is correct behaviour: 1.7 is *also* the version at which `solr.StrField` gains
doc values by default, which the generated catalog records for it as `primitive,docValuesByDefault`,
so flipping the version makes `category` **more** facetable rather than less. A version flip cannot
demonstrate an unfacetable `string` field, and the check as written was testing the wrong lever. Both
INSP-11 and INSP-12 are rewritten around a declared `docValues="false"`, which is an absence the schema
states rather than one a default supplies.

**INSP-12 was then pressed against the rewritten setup, and passes.** With `category` carrying
`docValues="false"` and the schema at `version="1.7"`, exactly one warning appears in the file, on
`category` inside the `facet.field`, reading *Solr: 'facet.field' will fail on 'category' — it needs doc
values or an un-invertible index, and for sorting a single value per document*. The same `category` in
the `qf` two lines above is unmarked. One field, searchable and unfacetable, and the two inspections
disagreeing about it exactly as they should.

**One wart, seen only by reading the rendered message — since fixed.** That warning mentioned *sorting*
while reporting a *facet*. The text had been made cause-neutral on purpose, listing what the operation
needs rather than asserting which part is missing, and the cost was a clause about single values in a
faceting warning — a requirement faceting does not have, on the kind of field one usually facets. The
two operations now carry separate messages, and a test asserts they name only their own requirements.

**Why nothing caught it, which is the transferable part.** Every fixture interpolated the same template
it verified, so the message agreed with itself for both operations everywhere it was asserted. A test can
check that a warning *says what this code produces*; only a reader can check that it *says something
true about the thing being reported*. That is the class of defect this suite exists for.

**The mechanical lesson, which cost two attempts.** An edit written to disk is reverted by the IDE
whenever it holds that file in an open editor — the first attempt read 1.6 back within milliseconds and
looked like a failed write. Closing all tabs first makes disk edits stick, and the same write then
persisted indefinitely. Also: **Go to File is ⇧⌘N in this keymap, not ⇧⌘O**; the wrong shortcut typed a
file name into `solrconfig.xml` and left it malformed. The IDE's own empty-editor screen lists the
correct shortcuts, which is the cheapest place to check.

**On driving the sandbox at all.** Two hazards are worth writing down. The sandbox runs as a process
named `java`, while a developer's own IDE is `idea` — sending keystrokes to the wrong one edits real
files, and the window title (`solr-plugin-demo – …`) is what tells them apart. And typing XML through
System Events fights auto-close: a partially typed element left the file malformed and produced two
XML *errors* that could not be told apart from the plugin's warnings. Editing the file on disk and
letting the IDE reload was reliable for `solrconfig.xml`; it is exactly what failed for the schema.

## Pass log

One row per pass, finished or not. Scope names what was skipped and why, if anything.

**A pass is all-or-nothing, and so is its row.** A row left at *in progress* is not a
partial result — it is a pass whose outcome nobody knows, which is the same evidence as no
pass at all. The two below are recorded as unfinished rather than deleted, because knowing
a pass was started and abandoned is worth more than a gap.

**Of the sixteen checks added at `2d393fc`, five have been pressed** — INSP-13, STR-1, STR-3, STR-5
and DIM-1, of which STR-5 failed and was rewritten. Sections 11 (DIM) and 12 (INT), STR-2 and STR-4,
checks INSP-14 and INSP-15, and NAV-9 and NAV-10 were written from the shipped behaviour and its
automated coverage, not from a sandbox. They are gestures with an expected
outcome and no evidence — which is what every check here is until a pass row says otherwise, and
the reason this list is worth as little as its last row.

| Date | Commit | Ran by | Scope | Result | Notes |
|---|---|---|---|---|---|
| 2026-07-30 | e4a35ac | | full suite | **not completed** | first pass with this document; superseded before it closed |
| 2026-08-01 | a2e0bc5 | | full suite | **not completed** | sandbox relaunched to verify the catalog completion, typed-attribute inspections and resource/handler navigation merged since the previous pass |
| | 4b9cbf9 | | full suite | *pending* | the first pass that can close DOC-5 and COMP-6, and the one the outstanding screenshots come from |
| 2026-08-03 | fab0922 | Claude | full suite as it stood at that commit | **passed** | superseded by the row below, which covers the same checks plus the two that shipped after it |
| 2026-08-04 | c924f43 | Claude | full suite | **passed** | every check green, including DOC-7 and all three halves of the ordering INSP-7. Scope notes below |
| 2026-08-10 | 029fb18 | Claude | PRM-1…5, INSP-10, INSP-11, INSP-12 | **not completed** | every `solrconfig.xml` check pressed and green — the nine added for the parameter work. INSP-11's version flip found the *check* wrong rather than the plugin, so it and INSP-12 were rewritten and then pressed against a declared `docValues="false"`. Driven through macOS accessibility scripting against the sandbox — see the notes below |
| 2026-08-06 | 88a9679 | Claude | ACT-1, HINT-1…5, BASE-1, BASE-2, NAV-1, NAV-2, NAV-3, NAV-4, NAV-5, NAV-6, NAV-7, REN-1, REN-2, REN-4, REN-5, DOC-1, DOC-4, DOC-7, DOC-8, DOC-9, COMP-5, CAT-1, INSP-1 | **not completed** | twenty-seven checks pressed and green; the rest were not. Driven through macOS accessibility scripting rather than by hand — see below |
| 2026-08-15 | c95df07 | Claude | BASE-2, INSP-13 | **superseded** | both halves of INSP-13 green, and BASE-2 observed either side of them. Two checks only — the rest of the sections added at `2d393fc` were not pressed. The method notes below matter more than the result: the first attempt at this pass typed into the wrong application. **INSP-13's green is no longer evidence**: it was measured against a position Solr does not read — see the 2026-08-16 note |
| 2026-08-15 | c95df07 | Claude | STR-1, STR-3, STR-5 | **not completed** | STR-1 and STR-3 green. **STR-5 failed and the check was wrong, not the plugin** — rewritten, and split, as STR-5 and STR-6. See the notes below |
| 2026-08-15 | 3c69baf | Claude | BASE-1, DIM-1 | **not completed** | both green, and BASE-1 exact: seven problems of which precisely two begin `Solr:`, the other five being the platform's spellchecker and locale inspection, as that section's note predicts. DIM-1 confirms the audit — the dim was already in the baseline and needed no edit |
| 2026-08-16 | 9a47fc1 | Claude | STR-1, STR-2, STR-3 | **not completed** | pressed against the corrected element catalog, and the reason they were pressed is that a generator fix moved ten element names. All three green. STR-2 is the one worth having: eighteen elements offered inside `<query>` — `filterCache`, `cache`, `featureVectorCache`, `HashDocSet`, `listener`, `maxWarmingSearchers` and the rest — with `dataDir` absent, which is the *arrival* half nothing had ever observed. STR-3 green with a caveat below. Two captures taken: entries 17 and 18 |

**The 2026-08-06 row is deliberately *not completed*, and the scope is the point.** Twenty-seven
checks were pressed, all twenty-seven green:

- **ACT-1 and HINT-1…5** — the plugin is alive on open and every hint renders inline. `string`
  fields read *whole value, case-sensitive*; `text_general` ones *tokenised, case-insensitive*;
  `name_prefix` adds *prefix-capable*. Both of HINT-5's silences are visible together: `notes`
  keeps its storage-shape phrases and drops the match claim, while `legacy` — whose type nothing
  declares — shows no hint at all.
- **NAV-1, NAV-2, NAV-4, NAV-5** — Go to Declaration lands where it should from every reference
  kind: a field's `type` reaches the `fieldType` at 31:20, a `copyField`'s two ends reach 68:16
  and 72:16, a `qf` name in `solrconfig.xml` crosses the file boundary to 71:16, and a filter's
  `words=` opens `stopwords.txt`.
- **INSP-1** — the planted `manufacturer` copy rule offers exactly the six fixes catalog entry 4
  predicts: `*_t`, `category`, `description`, `legacy`, `name`, `notes`. `sku`, `id`, `text` and
  `name_prefix` are absent, which is the closest-spelling ranking doing the choosing rather than
  the list being arbitrary — and `*_t` leading it is the alphabetical tiebreak the entry already
  explains. Pressed on the planted rule, so no edit and no undo. Image 4 re-shot from it.
- **COMP-5 and CAT-1** — completion carries what the catalog and the model know. A field's `type=`
  offers the four declared types *with what each one matches attached* — and `custom_text`, whose
  chain names an unrecognised factory, falls back to naming its class rather than claiming a match,
  the same silence HINT-5 shows. On a `<fieldType>`, `class=` offers field type classes; on a
  `<filter>`, factories — including the Japanese and Korean ones that only appear because
  `solr-analysis-extras` is resolved. The two class kinds never mix.
- **DOC-1** — the field popup carries what no external documentation can: *Matches: whole value,
  case-sensitive*, and a property table whose **From** column separates *on this field*, *from the
  field type* and *Solr default*.
- **BASE-1 / BASE-2** — the schema reports exactly two findings and `solrconfig.xml` reports none,
  which is the baseline every later check restores to.
- **REN-4 / REN-5** — renaming `*_t` to `*_txt` left the `pf` naming `body_t` exactly as written,
  and `solrconfig.xml` went from zero findings to one: the unknown-field inspection reporting the
  name the rename orphaned. That pair is the decision made visible. Undone afterwards.
- **DOC-4 / DOC-7 / DOC-8** — the class *value* answers with its Javadoc summary and *Used by 3
  field types*; the `class` attribute *name* now answers for itself rather than deferring to its
  element; and `minGramSize` shows owner, type and required marker with no prose row.

- **NAV-3** — the `text_general` declaration returns four usages: `name`, `description`, `text`
  and the `*_t` dynamic field. Four, not the three an older screenshot caption promised.
- **NAV-6** — `<dynamicField name="*_t">` returns one usage, the `pf` at `solrconfig.xml:31`,
  highlighted at `body_t` alone. That gesture returned an empty list before declarations became
  targets.
- **NAV-7** — the labels. The header reads **Field type** over `text_general` and **Dynamic
  field** over `*_t`; the groups read **Field declaring this type** and **Handler parameter in
  solrconfig.xml**. Neither *Solr Declaration Target* nor *Unclassified* appears anywhere.
- **REN-1** — the rename dialog reads *Rename field 'category' and its usages to:*, which is the
  same description NAV-7 checks, reached by a second refactoring.
- **REN-2** — renaming to `product_category` updated `solrconfig.xml:28`'s `qf` across the file
  boundary; undo restored both files, and the demo is clean on disk.
- **DOC-9** — `version` answers with the general rule and then *At 1.6, `docValues` defaults
  off; `uninvertible` defaults on; `autoGeneratePhraseQueries` defaults off.*

**DOC-9 also found a defect, which is the argument for pressing things.** The popup rendered its
opening sentence twice — the structural table's entry for `version` restated what the paragraph
beneath it already said. Every headless assertion passed either way, because each checked for a
substring rather than for the whole. Fixed in the same change that records this row.

**A second finding, from DOC-7.** The factory attribute popup already carries a *per-attribute*
Reference Guide link — `minGramSize` on solr.apache.org — alongside the per-class one. That was not
known when the hand-written prose table was drafted, and it settles the question the withdrawal
turned on: the prose would have duplicated a link that is already there, already per-attribute, and
always current.

**What was not pressed:** ACT-2, INSP-2…9, COMP-1, COMP-2, COMP-3, COMP-4, COMP-6, CAT-2, DOC-2, DOC-3, DOC-5, DOC-6,
DOC-10, REN-3, and the screenshot items other than image 7. None of them is known to be broken; none was looked at. The
boxes above stay unticked for that reason — a partial tick reads as a pass to everyone who did not
run it.

**About the two rows above this note.** The first covers the suite as it stood before
`SolrAnalyzerChainOrderInspection` and the per-attribute hover landed; the second re-ran BASE
against them and closed the checks they brought. Three things still qualify the second row.

*NAV-5 was exercised on `words=` alone.* The demo declares no `synonyms=`, no `protected=`,
no `<charFilter>` `mapping=` and no `lang/` path, so the rest of that check has no fixture to
press. Either the demo grows one or the check should say what it can cover.

*The ordering inspection has nothing to find in the committed demo, by construction.* Neither
rule can match a chain that declares no graph filter and no case-splitting one, which is why
BASE still counts two — INSP-7 plants what it needs and takes it away again, and that is the
only reason the rule ever fires here.

*Undo is the fragile step when a pass is scripted rather than typed.* Unwinding a long chain
of synthetic keystrokes twice left the buffer mid-edit, once badly enough that the editor
saved a broken line over a `git checkout`. Verify the fixture with `git diff` after every
check rather than trusting the undo count, and reload from disk when the two disagree.

**Two shipped features still have no checks here**, and this pass confirmed both alive: the
exact-match and prefix-capable companion intentions offer themselves on Alt-Enter, and the
prefix one correctly withholds itself on `name`, which already has `name_prefix` beside it.
They remain in *Not yet in the suite* below, which is that list working as intended — the
suite is behind the plan, not wrong.

### 2026-08-15 — INSP-13, and what nearly went wrong before it

Two checks pressed, both green, and the method is the part worth reading.

- **INSP-13** — `<nrtMode>true</nrtMode>` typed directly inside `<config>` took `solrconfig.xml`
  from zero problems to one, reading, verbatim: *Solr: The `<nrtMode>` config has been discontinued
  and NRT mode is always used by Solr. This config will be removed in future versions.* Replacing it
  with `<indexDefaults>` gave *Solr: `<indexDefaults>` and `<mainIndex>` configuration sections are
  discontinued. Use `<indexConfig>` instead.* — the replacement named, which is the whole argument
  for carrying Solr's sentence rather than a paraphrase of it.
- **BASE-2** — observed either side: zero problems before the edit, and *No problems in
  solrconfig.xml* again after the undo, with `git status` clean.

**The platform fires its own inspection alongside ours, and a future pass should not be surprised.**
`<indexDefaults></indexDefaults>` is an empty tag, so IntelliJ's *XML tag has empty body* appears
next to the Solr finding. Two problems on that line is correct; only the second one is this plugin's.

**The first attempt at this pass typed into a different application entirely.** Coordinates were
computed from a screenshot and passed to `click at`, which sent the click somewhere other than the
editor; focus moved to another app and the keystrokes followed it. Nothing in the demo was touched,
but nothing in the sandbox was tested either, and the damage was outside this project.

Two rules came out of it, and a scripted pass should not start without both:

1. **Never click at computed coordinates.** Drive the menu bar by item name —
   *Navigate → Line:Column…* places a caret exactly, with no pixel arithmetic anywhere.
2. **Check the focused element, not the focused application.** Frontmost-app is not enough: opening
   the Problems tool window moved keyboard focus to its search box, and the next edit was typed
   there. The accessibility layer answers this directly — `AXFocusedUIElement` reads
   *Editor for solrconfig.xml* when, and only when, it is safe to type.

Both attempts also confirm what the suite says about itself: a check is worth nothing until a row
records it, and this row records two out of the fourteen added at `2d393fc`.

### 2026-08-15 — the completion checks, and one that asserted a thing that was never true

Three pressed. Two green, and the third found a defect in this document.

- **STR-1** — typing `<` on a blank line inside `<config>` offered Solr's vocabulary:
  `requestHandler`, `query`, `cache`, `circuitBreaker`, `codecFactory`, `dataDir`,
  `directoryFactory`, `expressible`, `featureVectorCache`, `HashDocSet`, `indexConfig`,
  `indexReaderFactory` and on. **Most of those appear nowhere in the demo file**, which is what
  separates a catalog from the sibling echo it replaced — an echo could only ever have offered the
  five elements already written above the caret.
- **STR-3** — narrowing that list to `nrt` leaves a fuzzy match on `minPrefixQueryTermLength` and
  **no `nrtMode`**. The catalog demonstrably knows the element, since INSP-13 reports it from the
  same resource minutes earlier; completion withholds it anyway, which is the whole of the check.

**STR-5 claimed `name` and `class` are offered on a `<requestHandler>`. They are not, and never
were.** The gesture returns *No suggestions*, first on an unclosed tag — where the failure could
fairly be blamed on incomplete PSI — and then again on the complete, valid handler at line 24 with
the file otherwise clean. The catalog settles it: no row carries `requestHandler` as a parent at all.

`EditableSolrConfigAttributes.json` is the only source that describes attributes, and it covers
seven paths — the four caches, `requestParsers`, `autoCommit` and `autoSoftCommit`. `name` and
`class` come from `SolrConfig.plugins`' `REQUIRE_NAME` and `REQUIRE_CLASS`, which the generator does
not read, exactly as it does not read `MULTI_OK`. Attribute completion works: pressed inside a
`<filterCache>` it offers `autowarmCount`, `class`, `enabled`, `initialSize`, `maxRamMB`,
`regenerator` and `size` — Solr's own seven.

So the check has been rewritten to press where the data is, and a second one added for the silence,
because that silence is a decision rather than an absence. **The check was written from a
description of the feature rather than from the data behind it**, which is a failure mode a document
of gestures is unusually prone to — a plausible sentence about what completion "should" offer
survives review in a way a wrong assertion in code does not.

*Restored from `git checkout -- demo/` rather than by undo.* The earlier note warning that scripted
undo leaves the buffer mid-edit held again: unwinding this one left a stray `<requestHandler` on the
line. Trust `git status`, not the undo count.

### 2026-08-15 — auditing the unpressed checks against the data behind them

STR-5 failed because it was written from a description of a feature rather than from the data the
feature reads. That is a failure mode a document of gestures invites, so the twelve checks still
unpressed were read against the generated catalogs and the demo fixture before any of them were
pressed. **Five were wrong or unfixturable.** None of the five would have been caught by review;
each needed the data.

**Sound, and safe to press as written**

- **STR-2** — the catalog keys `filterCache` to parent `query` and `dataDir` to the root, which is
  exactly what the check reads. Its only gap is that the demo declares no `<query>`, so one has to be
  typed; the check now says so.
- **STR-4** — holds by construction: an unknown path has no children in the catalog, and the
  descriptor is permissive by design.
- **INSP-14** — `rows`, `pf2` and `pf3` are all in the parameter catalog, and `rwos` has **exactly
  one** near miss in the whole of it, `rows`, at distance two. The check's *"Alt-Enter offers `rows`"*
  is precisely right rather than approximately.
- **INT-1 and INT-2** — the 2026-08-04 pass already recorded both intentions alive, including the
  prefix one correctly withholding itself on `name`. Numbered checks now exist for what was observed
  then.

**Wrong, and rewritten before pressing**

- **DIM-1** said *add* `indexed="true"` to the `name` field. It is already there — as it is on every
  field in the demo, alongside `stored="true"` on most, and Solr defaults both to true. **The
  untouched schema is already full of dimmed attributes**, so the gesture produced nothing that was
  not there, and the baseline sections never said so. DIM-1 now observes what is there and DIM-3
  changes it.
- **DIM-4** wanted a `<dynamicField>` repeating a value its `<fieldType>` declares. The demo's `*_t`
  is `text_general`, which declares no `omitNorms`. That half has no fixture and is recorded as
  automated-only until the demo grows one.
- **NAV-9** asserted Cmd+Click lands on the class. **The demo depends on `solr-solrj` alone**, and
  `solr.SearchHandler` lives in `solr-core` — not on its classpath, so nothing can be landed on. The
  check now presses what the demo can actually show, which is the more interesting half anyway: a
  class the project does not carry resolves nowhere **and draws no warning for it**. The landing half
  needs `solr-core` added to the demo first, and says so.
- **NAV-10** inherited that, and separates cleanly once stated: navigation needs the class *in the
  project*, documentation needs only the generated catalog, and the catalog does carry
  `SearchHandler`. The check is now about documentation answering during indexing with no class on
  the classpath at all — which is the regression it was written for.

**The pattern in all three failures — STR-5, DIM-1, NAV-9 — is a check asserting a positive outcome
the fixture cannot produce.** A gesture with a plausible expected outcome reads as correct forever
until someone presses it, and the automated suites do not catch it because they build their own
fixtures. Writing a check against the catalog row or the demo line it depends on is cheap; four of
the five above were settled by one `awk` over a generated TSV.

### 2026-08-15 — the audit's first prediction, confirmed

Two checks, both green, and the interesting one is DIM-1 because the audit had already rewritten it.

- **BASE-1** — seven problems in `managed-schema.xml`, of which **exactly two begin `Solr:`**: the
  planted `discontinued` field type at :77 and the planted `manufacturer` copy rule at :92. The other
  five are the platform's — `configsets` at :14 and four *American English uses…* on the demo's
  British spelling. That is this section's own note, borne out to the number, and it is the argument
  for counting `Solr:` rows in the tool window rather than underlines in the gutter.
- **DIM-1** — `indexed="true"` and `stored="true"` render greyed across the field block, while
  `stored="false"`, `multiValued="true"` and `required="true"` render at full strength. Nothing about
  any of them reaches the Problems view.

**DIM-1 passed without a single edit, which is the whole point of having audited it.** As written it
asked for `indexed="true"` to be *added* to the `name` field, where it has always been — so the
gesture would have changed nothing, the dim would have appeared anyway, and the check would have
been recorded green while testing nothing at all. That is a worse outcome than a failure: a check
that cannot fail reads exactly like one that passes.

The negative half needs no separate gesture either. `stored="false"` sitting undimmed two lines
below a dimmed `stored="true"` is DIM-3's claim already on screen, in the committed fixture, with the
two states side by side.

*The pass also cost one avoidable mistake.* `Cmd+Shift+O` was assumed to be Go to File; in this
keymap it is not, and the file name was typed into the editor instead. The rule from the previous
pass extends: drive **navigation** by menu item too, not just caret placement —
*Navigate → File…* exists and does exactly what the shortcut was assumed to. Guessing a keystroke is
the same class of error as guessing a coordinate.

### 2026-08-16 — the check the audit missed, and the one row it turns on

The 2026-08-15 audit read twelve unpressed checks against the data behind them and **INSP-15 was not
among them.** It is the last INSP check written from a description of a feature and never read
against a fixture, which is exactly the shape that produced STR-5. Read now, against the demo, the
generated catalog and the rule itself. **Not pressed** — nothing below is evidence, only a gesture
that can now produce the outcome it claims.

- **The two lines the gesture names exist and say what it needs.** `managed-schema.xml:68` declares
  `name` as `type="text_general" indexed="true"`, and `solrconfig.xml:28` is the `/select` handler's
  `<str name="qf">name^3 description category</str>`.
- **`qf` is a searching parameter and `^3` is not part of the name.** The parser maps `qf`, `pf`,
  `pf2` and `pf3` to the search operation and splits a boost off the token before resolving it, so
  the underline falls on `name` and stops at the caret. `bf` and `boost` are deliberately not in that
  map, which is why nothing else in the file joins in.
- **The row the check actually turns on is `solr.TextField`'s.** Searchable is `indexed` *or*
  `docValues`, and the disjunction returns "don't know" — and reports nothing — unless both sides are
  definite. `docValues` has no flat default; it is decided by the field type's class, and the
  generated catalog carries `solr.TextField` with an **empty** traits column. Empty is a different
  answer from absent: the class is known and carries no trait, so `docValues` resolves to a definite
  false and the rule to a definite no. Had `text_general` named a class the catalog does not carry,
  the edit would have underlined nothing and the check would have read as correct forever.

**One claim in it was wrong and is corrected**: it called itself the only INSP check whose finding is
invisible in Solr. The analyzer-chain ordering at INSP-7 is the other — every class exists, every
attribute is legal, the core starts, and the filter never runs. Two, not one.

**INSP-13 needed no audit and got a confirmation instead.** It was pressed on 2026-08-15, and the two
sentences it quotes are literals in the generated element vocabulary, both keyed to parent `config`:
`nrtMode` carries *The `<nrtMode>` config has been discontinued and NRT mode is always used by Solr…*
and `indexDefaults` carries *…discontinued. Use `<indexConfig>` instead.* The parent key is also why
the check's third half works — move the element inside `<acmeThing>` and no row matches it.

> **Reading the entry below if bytecode is unfamiliar.** It talks about a "local variable" the
> generator's scanner could not follow, and that is worth unpacking before the log itself does the
> talking — this note is commentary, not part of the record. Solr's source reaches `<nrtMode>`
> through a chain: it computes an `indexConfigPrefix` naming the `<indexConfig>` node, then calls
> `.get("nrtMode")` on whatever that lookup returns — ordinary Java, no different in shape from
> `String prefix = ...; node.get(prefix)`. A tool reading compiled bytecode instruction by
> instruction can follow a chained call when its argument is a literal string sitting right there in
> the instruction — `get("query")`, say. It cannot follow one whose argument is a **local
> variable**: a value computed earlier, stored into a JVM local-variable slot, then loaded back out
> several instructions later at the call site. Without reversing course to ask "what was stored in
> that slot", a straight pass over the bytecode sees only the load, not where the value came from —
> so `indexConfigPrefix` arrived at the call as an opaque value rather than a name, and the read
> looked exactly like one with no parent at all. That is why the discontinued-element rule could
> confidently underline `<nrtMode>` written directly under `<config>`, a position Solr never reads,
> while staying silent on the same element under `<indexConfig>`, the one position that actually
> stops a core starting.

### 2026-08-16 — INSP-13's green was measured against the wrong position

**The paragraph directly above is the defect, written down and not noticed.** "Both keyed to parent
`config`" was true of the catalog and false of Solr. `SolrConfig` reads `<nrtMode>` and
`<unlockOnStartup>` as `get(indexConfigPrefix).get("…")`, under `<indexConfig>`; only
`<indexDefaults>`, `<mainIndex>` and `<jmx>` are read at the root. The generator's pass could follow
a parent written as a literal but not one arriving through a local variable, recorded no parent for
those two, and an absent parent already meant *top level* — so the catalog placed them under
`<config>` and the rule inherited a confident claim about a position Solr ignores.

**INSP-13 was written to match, and pressing it confirmed the check rather than the behaviour.** The
underline appeared exactly where the check said it would, which is why nothing looked wrong. What the
gesture could not show is the two things that follow from the same error: a `<nrtMode>` under
`<config>` — inert to Solr — was reported, and one under `<indexConfig>`, which stops a core
starting, was not.

**Status: superseded, not re-pressed.** INSP-13 now asks for the underline under `<indexConfig>`, and
INSP-13b pins the position that must stay clean. The 2026-08-15 green stands as a record of what was
pressed and no longer stands as evidence about this rule. Both need a fresh sandbox press.

The generator now follows the chain, and says [`UNPLACED`](../buildSrc/src/main/kotlin/org/apache/solr/ide/build/SolrConfigElements.kt)
where it still cannot — an element whose position was never observed is no longer reported as a child
of the root. **Eight further element names moved with the fix, and they are listed rather than counted** — a
first draft of this paragraph said twelve, which is the same mistake as reading a status out of a
number instead of out of the thing it counts. The list below is *measured*: the generator was reverted,
the catalog regenerated, and the two resources diffed in full for both supported lines. That matters
because the obvious way to check — comparing the children of `<config>` before and after — is blind to
anything that moved between two non-root parents. Nothing did, and only a whole-catalog diff can say so:

| Element | Now read under | Line |
|---|---|---|
| `HashDocSet`, `cache`, `maxWarmingSearchers`, `minPrefixQueryTermLength`, `slowQueryThresholdMillis`, `useColdSearcher` | `<query>` | both |
| `featureVectorCache` | `<query>` | Solr 10 only |
| `listener` | `<query>` **and** `<updateHandler>` — one name, two rows, because Solr reads it twice | both |

`lib` and `luceneMatchVersion` were checked and did **not** move: they are read off the document root
and belong at the top level, which is what `ROOT_FIELD` exists to establish.

**Both halves have since been pressed, and the check for the second one already existed.** An earlier
draft of this paragraph said a `<` typed inside `<query>` was "a gesture no check currently
describes". That was wrong: **STR-2 describes exactly it**, and has since the section was written.
Claiming a gap without reading the section it would sit in is the same error as reading a status out
of the code — the answer was two screens up.

STR-1 shows the elements leaving `<config>`; STR-2 shows them arriving under `<query>`. Both were
pressed on 2026-08-16 and both are green — `filterCache` and its siblings offered inside `<query>`,
`dataDir` absent from that list, and the eighteen offered there sharing almost nothing with what
`<config>` offers. That second observation is the one that matters: everything else verifying this
fix — the tests, the whole-catalog diff, STR-1 — only shows what *left* the root. Had the elements
left without arriving, all of it would still have read green while the plugin was strictly worse than
before.

**`<indexConfig>` offers `deletionPolicy` and nothing else, and that is STR-3 seen from a third
side.** The two elements this whole fix was about — `nrtMode` and `unlockOnStartup` — are *not*
offered there, and must not be: `SolrElementCatalog.offerableChildrenOf` filters to elements Solr
still accepts, and both carry a retirement notice. So the catalog knows exactly where Solr reads them,
the inspection reports them where Solr reads them, and completion refuses to suggest them anywhere.
Those are three different questions with three different right answers, and the instruction that sent
someone to press this expected all three at that caret — a reminder that an expectation written from
the fix rather than from the contract is as wrong as a check written from the plugin rather than from
Solr.

Two elements' worth of the known gap shows here too: `lockType`, `httpCaching` and `infoStream` sit in
all four shipped configsets and in neither catalog, because `SolrConfig` does not read them. Nothing
about this fix changed that, and a reader whose own file contains `<lockType>` will see it offered
nowhere.

**The standing lesson is the one this suite has now paid for twice.** A check written from the
plugin's behaviour asserts that the plugin is self-consistent. Only a check written from *Solr's*
behaviour can catch the plugin being confidently wrong, and the earlier note that this rule "asserts
nothing of its own and quotes Solr instead" was true of the sentence and not of the position it was
attached to.
