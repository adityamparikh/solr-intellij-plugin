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
3. Work top to bottom. The order matters: the zero-warning baseline comes first because
   every later "break it" check ends by restoring that baseline.
4. Record the pass in the log with the commit you ran it at.

While the sandbox is open, capture anything [the screenshot catalog](screenshots.md) lists as
outstanding — the gestures are the same ones, and a screenshot taken during a pass is evidence the
check passed rather than a picture of an unverified claim.

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

- [ ] **BASE-1** — `managed-schema.xml`, untouched, shows **zero** warnings or weak
      warnings from the plugin.
- [ ] **BASE-2** — `solrconfig.xml`, untouched, likewise shows zero.

This is the suite's most important check. Solr configuration is full of syntax that
resembles a field name (`fl` holds `score`, `*`, `max(price,0)`); an underline on a
correct file is a bug, never noise.

## 3. Match-capability inlay hints (HINT)

*Automated: `SolrMatchInlayHintsProviderTest`, `SolrMatchAnalysisTest`. Manual adds:
placement and readability of the rendered hint.*

- [ ] **HINT-1** — `string` fields (`id`, `sku`, `category`) read as whole-value,
      case-sensitive.
- [ ] **HINT-2** — `text_general` fields (`description`, `text`) read as tokenized,
      case-insensitive, with no efficient prefix support.
- [ ] **HINT-3** — `name_prefix` (type `text_prefix`) reads as prefix-capable, and the
      hint names EdgeNGram as the mechanism rather than saying "prefix: true".
- [ ] **HINT-4** — Hints sit inline beside the declaration (no hover needed), readable
      at presentation font size.

## 4. Navigation and Find Usages (NAV)

*Automated: `SolrFieldTypeReferenceTest`, `SolrCopyFieldReferenceTest`,
`SolrConfigFieldReferenceTest`, `SolrResourceFileReferenceTest`. Manual adds: the click
gesture, caret placement, the Find Usages tool window.*

- [ ] **NAV-1** — Cmd+Click a field's `type="text_general"` jumps to the
      `<fieldType name="text_general">` declaration.
- [ ] **NAV-2** — Cmd+Click a `<copyField>` `source` and `dest` jumps to each field's
      declaration.
- [ ] **NAV-3** — Find Usages (⌥F7) on the `text_general` field type lists every field
      declared with it.
- [ ] **NAV-4** — In `solrconfig.xml`, Cmd+Click a field name inside a handler parameter
      (`qf`, `df`, a `facet.field` array item) lands on the schema declaration; each name
      in `name^3 description` navigates on its own, and Find Usages on the field lists
      the parameter among its usages.
- [ ] **NAV-5** — Cmd+Click a resource path on a filter *or a char filter* —
      `words="stopwords.txt"`, `synonyms=`, `protected=`, a `<charFilter>`'s `mapping=` —
      opens the file, including through `lang/`; each entry in a comma-separated list
      navigates on its own.

## 5. Quick documentation (DOC)

*Automated: `SolrConfigsetDocumentationProviderTest`, `SolrFieldPresentationTest`,
`SolrSchemaElementsTest`, `SolrReferenceGuideTest`. Manual adds: popup rendering, link
clickability.*

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
      configset uses it, and a Reference Guide link. Javadoc prose is expected to be
      absent until the catalog carries a documentation column.

## 6. Inspections and quick-fixes (INSP)

*Automated: `SolrDanglingCopyFieldInspectionTest`, `SolrUnknownFieldTypeInspectionTest`,
`SolrUnknownFieldReferenceInspectionTest`, `SolrUnknownAttributeInspectionTest`,
`SolrInvalidAttributeValueInspectionTest`, `SolrReferenceQuickFixTest`. Manual adds:
live reaction to edits, fix application through the real Alt-Enter menu.*

Every check here ends with **undo until the baseline (BASE) is clean again**.

- [ ] **INSP-1** — Change a `<copyField>` dest to a name no field declares: underlined,
      and Alt-Enter offers the declared fields, closest spelling first.
- [ ] **INSP-2** — Change a field's `type` to a bogus value: underlined, fix offers the
      declared types; applying one produces a file that parses clean.
- [ ] **INSP-3** — Delete the `name` field entirely: its copy rule flags immediately,
      without saving or reopening.
- [ ] **INSP-4** — In `solrconfig.xml`, point a handler parameter (`qf` or `df`) at a
      field the schema does not declare: flagged.
- [ ] **INSP-5** — Add a made-up attribute to a `<field>` tag: the unknown-attribute
      inspection fires, naming the element that cannot accept it.
- [ ] **INSP-6** — Set `indexed="yes"`: the invalid-attribute-value inspection fires.
- [ ] **INSP-7** — Undo everything: both files return to zero warnings.

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
- [ ] **COMP-4** — The boolean value Solr would have used anyway is marked `(default)`,
      whichever it is — `indexed` marks `true`, `multiValued` marks `false`; properties
      whose default depends on the field type stay unmarked.
- [ ] **COMP-5** — A field's `type=` offers the declared types; a `copyField`'s two ends
      offer the declared fields; `<analyzer type=` offers `index`/`query`.

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

---

## Not yet in the suite

Checks join a section above when their feature ships; **which features those are is the
plan's to say**, not this file's. As of the last pass, the gestures below do nothing —
finding one alive means the suite is behind, not that something is wrong:

- Alt-Enter intentions generating an `_exact`/`_prefix` companion field
- Rename refactoring on fields and field types
- The settings page and *Mark Directory as Solr Configset Root*
- Everything server-side: connections, tool window, query console, drift view
- Everything in Java/Kotlin code: field-name checks, query language injection
- Javadoc prose inside the `class=` hover (DOC-4 shows structure and links today) — waits
  on the catalog's `-sources` documentation column
- Hover documentation on a factory attribute (`minGramSize`) — owner, value type,
  default or required marker
- A factory's complete effective configuration in its popup, unwritten attributes shown
  at their defaults
- The dimmed rendering of an attribute that merely restates its default, with a
  remove intention
- `solrconfig.xml`'s own structure: element completion and validation

## Pass log

One row per completed pass. Scope names what was skipped and why, if anything.

| Date | Commit | Ran by | Scope | Result | Notes |
|---|---|---|---|---|---|
| 2026-07-30 | e4a35ac | | full suite | *in progress* | first pass with this document |
| 2026-08-01 | a2e0bc5 | | full suite | *in progress* | sandbox relaunched to verify the catalog completion, typed-attribute inspections and resource/handler navigation merged since the previous pass |
