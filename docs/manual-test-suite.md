# Manual test suite

A repeatable verification pass over the sandbox IDE, run against the demo project. This
document owns three things: the **gesture** to make, the **expected outcome**, and the
**record of the last pass**. It deliberately does not own what is built —
[the plan](../specs/plans/0002-solr-intellij-plugin-plan.md) does, and a feature's checks
join this suite only when its code has shipped and there is something to press.

**How to run a pass**

1. `./gradlew runIde` — the sandbox opens `demo/`. Open `solr/conf/managed-schema.xml`.
2. Uncheck every box from the previous pass (a pass is all-or-nothing; history lives in
   the [pass log](#pass-log), not in the boxes).
3. Work top to bottom. The order matters: the baseline pass comes first because
   every later "break it" check ends by restoring that baseline.
4. Record the pass in the log with the commit you ran it at.

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
a light fixture cannot see: whether the hint actually renders where a presenter can point
at it, whether the popup is readable, whether features stay alive during real indexing.
A check whose rendering risk disappears — because automation grows to cover it — should
be retired from here, not accumulated.

---

## 1. Activation (ACT)

*Automated: `SolrProjectDetectorTest`, `SolrConfigsetDetectorTest`,
`SolrConfigsetLocatorTest`. Manual adds: real project open, real indexing.*

- [ ] **ACT-1** — Opening `demo/` activates the plugin: `managed-schema.xml` shows inlay
      hints. The demo project passes the outer gate through its Solr client dependency.
- [ ] **ACT-2** — Features are alive **while the IDE is still indexing** (open the file
      immediately after launch, before the progress bar finishes). Everything is
      dumb-aware by design; a feature that waits for indexing is a regression.

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

## 3. Match-capability inlay hints (HINT)

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

## 4. Navigation and Find Usages (NAV)

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
      never spells, which the word index alone cannot reach. The result is highlighted at
      `body_t` itself, not across the whole parameter value.
- [ ] **NAV-7** — **How the results are labelled**, which is the half no fixture can see.
      In NAV-3's window the header reads **Field type** over `text_general`, and the results
      group under **Field declaring this type** — not *Solr Declaration Target*, which is the
      plugin's own class name leaking through the platform's fallback, and not
      *Unclassified*. In NAV-6's, the header reads **Dynamic field** and the group reads
      **Handler parameter in solrconfig.xml**. A correct result list under either of those
      two wrong labels reads as broken to everyone but its author.
- [ ] **NAV-5** — Cmd+Click a resource path on a filter *or a char filter* —
      `words="stopwords.txt"`, `synonyms=`, `protected=`, a `<charFilter>`'s `mapping=` —
      opens the file, including through `lang/`; each entry in a comma-separated list
      navigates on its own.
- [ ] **NAV-8** — **How a resource file's usages are labelled**, which is NAV-7's question asked
      of the fourth kind of reference. Find Usages on `stopwords.txt` itself — from the
      Project view, or from the caret on the path — lists both
      `<filter class="solr.StopFilterFactory">` occurrences under **Analyzer component
      reading this file**, not *Unclassified*. This one shipped wrong: the classification
      skipped file references on the assumption the platform grouped them, and nothing does.
- [ ] 📸 **Capture `docs/images/07-find-usages-field-type.png`** at NAV-3 — the Find Usages
      tool window with its results, invoked from the `<fieldType name="text_general">`
      declaration, which is the gesture the demo now performs.
      [Catalog entry 7](screenshots.md#7-find-usages-on-a-field-type--07-find-usages-field-typepng).
- [ ] 📸 **Capture `docs/images/08-nav-solrconfig-field-reference.png`** at NAV-4 — Cmd+hover
      `name` in `solrconfig.xml:28`, framing the navigation tooltip.
      [Catalog entry 8](screenshots.md#8-navigation-from-solrconfigxml-into-the-schema--08-nav-solrconfig-field-referencepng).
- [ ] 📸 *Optional:* **`docs/images/09-nav-resource-file.png`** at NAV-5 — caret on
      `words="stopwords.txt"` at `managed-schema.xml:34`, then **Quick Definition**. Check
      NAV-5 itself with Cmd+Click or Cmd+hover as usual, but do not publish the hover: its
      tooltip is an absolute path through your home directory.
      [Catalog entry 9](screenshots.md#9-navigation-to-a-resource-file--09-nav-resource-filepng-optional).

## 4a. Rename (REN)

*Renaming is the reference graph read backwards and then written to. Every check here ends
in an undo — these edit both files, and the demo has to come back clean for the sections
below.*

- [ ] **REN-1** — Caret on `category` in `<field name="category">` at
      `managed-schema.xml:70`, press Shift+F6. **Read the dialog before typing:** it must
      say *Rename **field** 'category'*, not *Rename Solr Declaration Target 'category'* —
      the same description NAV-7 checks, reached by a second refactoring.
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
- [ ] **REN-5** — Still in REN-4's state, look at that `pf`: `body_t` is now underlined by the
      unknown-field inspection, because nothing declares it any more. **That report is what
      makes REN-4 defensible** — the configset is broken, and the plugin says so, rather than
      leaving it silently wrong. **Undo REN-4 and confirm the underline goes.**

## 5. Quick documentation (DOC)

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
      nothing — a table hard-coding `true` passes DOC-5 — so it is the flip that is the
      check.
- [ ] **DOC-7** — Hover `minGramSize` on an `EdgeNGramFilterFactory` (or F1 with the caret
      on the attribute name). The popup names the owning class, the value type (*a whole
      number*), and the required marker — and does **not** invent a prose description of what
      the attribute means. Hover `preserveOriginal` on the same filter: the popup shows the
      catalog default `false` instead of a required marker. An attribute name the catalog does
      not list, or any attribute on a class the catalog does not know, stays silent.
- [ ] **DOC-8** — **The attribute's own name answers**, which is the caret position that used
      to say nothing while the element above it and the value beside it both did. Hover each
      of these attribute *names*: `name` and `type` on a `<field>`; `name` and `class` on the
      `<fieldType>`; `source` and `dest` on a `<copyField>`. Each explains itself, and the
      two `copyField` ends read differently from each other.
- [ ] **DOC-9** — Hover `version` on the demo's `<schema>` root. Two paragraphs: what the
      attribute decides anywhere, then **what `1.6` decides here** — `docValues` off,
      `uninvertible` on. The second paragraph is computed from the file, so DOC-6's edit to
      `1.7` should flip it; check that too while the file is already changed, and undo with
      the rest.
- [ ] **DOC-10** — **The absence, which is the one that can regress quietly.** Re-check DOC-7
      from here: `minGramSize` shows its owner, value type and required marker, the guide links —
      and **no prose row**. A hand-written *Does* row was drafted for this popup and withdrawn,
      and the popup already carries a per-attribute guide link, so prose here would duplicate a
      link that is always current.

      *The other half of this check has no fixture.* A `copyField`'s `name` must never gain the
      `<field>` description — but the committed demo declares no `<copyField name=…>`, so there
      is nothing to hover, the same shape of gap NAV-5 records. `SolrAttributeDocumentationTest`
      covers it headlessly; either the demo grows one or this stays a note.

- [ ] 📸 **Re-capture `docs/images/02-quick-doc-field.png`** at DOC-1 — caret inside
      `name="category"` at line 70, F1, cropped to the popup **including the
      `uninvertible` row**. **Check the catalog entry before shooting:** this one waits on
      the field-type-class resolution of `omitNorms` and `docValues`, and is stale on
      arrival if taken before that lands.
      [Catalog entry 2](screenshots.md#2-quick-documentation-on-a-field--02-quick-doc-fieldpng).
      DOC-4's `03-quick-doc-class.png` is current and needs nothing.

**DOC-4's `Accepts` table shows a name and a value type, and nothing more.** That is the
class-*value* popup on purpose, and Javadoc is per class, so it has no honest per-argument
prose to add beside a default either. Defaults and required markers live on the other two
positions: on one attribute as DOC-7's hover, and on the whole tag as DOC-4b's
complete-configuration table. A tester looking for `minGramSize` marked required on the
class value wants DOC-7 or DOC-4b, and is looking at the wrong element rather than at a
broken feature.

**DOC-5 and DOC-6 are one check in two halves, and the pair is the point.** Solr's field
defaults are not constants: `uninvertible` defaults true below schema version 1.7 and
false from it, which is how Solr changed a default without breaking deployed schemas. A
provider that ignores the version entirely still passes DOC-5, because 1.6 is where the
demo sits — only DOC-6's flip distinguishes reading the file from hard-coding its answer.
`SolrFieldPropertiesTest` and `SolrBooleanPropertyCompletionTest` already assert both
sides headlessly; what these two add is that the popup and the completion list render what
resolution decided, which no fixture can see.

DOC-6 edits the demo, so it belongs with the INSP checks in ending on an undo. The demo
stays at 1.6 permanently and says so in a comment — a *committed* bump to 1.7 would not
break these checks so much as delete DOC-5, leaving the suite testing one side of a
boundary again.

## 6. Inspections and quick-fixes (INSP)

*Automated: `SolrDanglingCopyFieldInspectionTest`, `SolrUnknownFieldTypeInspectionTest`,
`SolrUnknownFieldReferenceInspectionTest`, `SolrUnknownAttributeInspectionTest`,
`SolrInvalidAttributeValueInspectionTest`, `SolrAnalyzerChainOrderInspectionTest`,
`SolrUnusedFieldTypeInspectionTest`,
`SolrReferenceQuickFixTest`. Manual adds:
live reaction to edits, fix application through the real Alt-Enter menu.*

Every check here ends with **undo until the baseline (BASE) is clean again**.

- [ ] **INSP-1** — Change a `<copyField>` dest to a name no field declares: underlined,
      and Alt-Enter offers the declared fields, closest spelling first.
- [ ] 📸 **Capture `docs/images/04-inspection-copyfield-quickfix.png`** — use the *planted*
      `manufacturer` rule at line 92 rather than the edit INSP-1 makes, so the image needs
      no undo. Frame the underline and the open Alt-Enter menu, and dismiss it with Escape —
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
- [ ] **INSP-11** — With the schema still at `version="1.6"`, add
      `<arr name="facet.field"><str>category</str></arr>`. **Nothing fires** — `uninvertible`
      defaults *true* below 1.7, so an indexed field with no doc values can still be faceted by
      un-inverting it. Now change the schema root to `version="1.7"` as DOC-6 does: the same
      `category` is underlined, because that default flips. Undo the version edit.
- [ ] **INSP-12** — In the same handler, `<str name="qf">category</str>` stays clean while
      INSP-11's facet is underlined. **The same field, searchable and unfacetable at once** — the
      check that the two inspections ask different questions rather than one question twice.
- [ ] **INSP-9** — Undo everything, including DOC-6's version edit: both files return to
      their BASE counts — **two** warnings in `managed-schema.xml`, zero in
      `solrconfig.xml`. Not zero and zero; the planted `manufacturer` copyField and the
      planted `legacy` field's undeclared type are part of the baseline and stay underlined.
      None of INSP-7's three edits survives into the baseline: the demo's own chains are
      correctly ordered. No dimmed type is part of the baseline: every type the demo
      declares has a field behind it.

## 7. Completion — the schema's own vocabulary (COMP)

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
      claim as DOC-5 and DOC-6, in the surface where a reader meets it first, and the same
      reason for testing both sides.
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
      `splitOnCaseChange` among them. This is the check that proves the
      constructor-bytecode pass reached the editor.
- [ ] 📸 **Capture `docs/images/06-completion-factory-attributes.png`** — not from CAT-2,
      which needs a class the demo does not declare. Use the `EdgeNGramFilterFactory`
      filter at line 48, caret before the closing `/`, space, and frame
      `luceneMatchVersion` and `preserveOriginal` — not `minGramSize` and `maxGramSize`,
      which the filter already declares and completion therefore omits. Same bytecode
      route, no fixture edit.
      [Catalog entry 6](screenshots.md#6-catalog-backed-factory-attributes--06-completion-factory-attributespng).

---

## 9. Completion — field names inside `solrconfig.xml` parameters (PRM)

*Automated: `SolrParameterFieldCompletionTest`, `SolrConfigFieldReferenceTest`. Manual adds: the
popup where a reader actually meets it, inside a string the platform has no vocabulary for.*

This is the inverse of INSP-1's warning. The list that lets an inspection say `descriptoin` is not a
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
- [ ] **PRM-4** — In a `<str name="sort">`, completion offers fields at the start of a clause and
      nothing after `text ` — the second token of a sort clause is a direction, not a field.
- [ ] **PRM-5** — F1 on a field name inside the `qf` shows *the field's* documentation — its type
      and analyzer chain — not the `<str>` element's. **This works through reference resolution
      rather than a documentation branch written for it**, so it is the gesture most likely to
      disappear silently when either provider changes.

## Not yet in the suite

Checks join a section above when their feature ships; **which features those are is the
[plan's](../specs/plans/0002-solr-intellij-plugin-plan.md) to say**, not this file's. The
list below is what the suite does not yet cover, and says nothing about what is built —
finding one of these gestures alive means the suite is behind, not that something is wrong:

- Alt-Enter intentions generating an `_exact`/`_prefix` companion field
- The settings page and *Mark Directory as Solr Configset Root*
- Everything server-side: connections, tool window, query console, drift view
- Everything in Java/Kotlin code: field-name checks, query language injection
- The dimmed rendering of an attribute that merely restates its default, with a
  remove intention
- `solrconfig.xml`'s own *structure*: element and attribute completion, and navigation from a
  `class` attribute to the plugin it names. What the legal elements are is settled — Solr declares
  them in `SolrConfig.plugins` — but nothing reads them yet
- `omitNorms` and `docValues` resolved from the field type's class. Both report *see the
  guide* today, which is the honest answer while the catalog cannot say which traits a
  type carries — DOC-5's version resolution settles a different pair of properties
- The one inspection [the plan](../specs/plans/0002-solr-intellij-plugin-plan.md) lists
  and has not built: a configuration element removed in the targeted Solr line
- The relevance-parameter check on a non-indexed field, which is built and registered but
  has no sandbox gesture here yet. `SolrNonIndexedRelevanceFieldInspectionTest` covers it
  automatically; what manual would add is the live reaction the INSP checks exist for
- **Do not read INSP's length as an inspection count** — nine inspection classes exist, and the
  INSP checks above are scenarios over them rather than one apiece: INSP-1 and INSP-3 are both the
  dangling-`copyField` inspection, once on a written edit and once on a live deletion; INSP-10 to
  INSP-12 are the two `solrconfig.xml` field checks, including one scenario whose whole point is
  that the two disagree about the same field; and INSP-9 restores the baseline rather than testing
  anything

## Pass log

One row per pass, finished or not. Scope names what was skipped and why, if anything.

**A pass is all-or-nothing, and so is its row.** A row left at *in progress* is not a
partial result — it is a pass whose outcome nobody knows, which is the same evidence as no
pass at all. The two below are recorded as unfinished rather than deleted, because knowing
a pass was started and abandoned is worth more than a gap.

| Date | Commit | Ran by | Scope | Result | Notes |
|---|---|---|---|---|---|
| 2026-07-30 | e4a35ac | | full suite | **not completed** | first pass with this document; superseded before it closed |
| 2026-08-01 | a2e0bc5 | | full suite | **not completed** | sandbox relaunched to verify the catalog completion, typed-attribute inspections and resource/handler navigation merged since the previous pass |
| | 4b9cbf9 | | full suite | *pending* | the first pass that can close DOC-5 and COMP-6, and the one the outstanding screenshots come from |
| 2026-08-03 | fab0922 | Claude | full suite as it stood at that commit | **passed** | superseded by the row below, which covers the same checks plus the two that shipped after it |
| 2026-08-04 | c924f43 | Claude | full suite | **passed** | every check green, including DOC-7 and all three halves of the ordering INSP-7. Scope notes below |
| 2026-08-06 | e917978 | Claude | BASE-1, BASE-2, NAV-3, NAV-6, NAV-7, REN-1, REN-2, REN-4, REN-5, DOC-4, DOC-7, DOC-8, DOC-9 | **not completed** | thirteen checks pressed and green; the rest were not. Driven through macOS accessibility scripting rather than by hand — see below |

**The 2026-08-06 row is deliberately *not completed*, and the scope is the point.** Thirteen
checks were pressed, all thirteen green:

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

**What was not pressed:** ACT, HINT, INSP, COMP, CAT, DOC-1, DOC-2, DOC-3, DOC-5, DOC-6, DOC-10,
NAV-1, NAV-2, NAV-4, NAV-5 and REN-3. None of them is known to be broken; none was looked at. The
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
