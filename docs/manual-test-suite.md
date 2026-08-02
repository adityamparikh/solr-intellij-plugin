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
check passed rather than a picture of an unverified claim. **Six images are missing and one needs
re-shooting**; the catalog's opening table names them and where each file goes.

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

- [ ] **BASE-1** — `managed-schema.xml`, untouched, shows **exactly one** warning: the
      planted dangling `manufacturer` copyField at the bottom, which is the inspection
      demo and must not be fixed. Nothing else in the file is underlined.
- [ ] **BASE-2** — `solrconfig.xml`, untouched, shows **zero**.

This is the suite's most important check, and the count is the whole of it: one report,
on the one defect the fixture plants deliberately. Solr configuration is full of syntax
that resembles a field name (`fl` holds `score`, `*`, `max(price,0)`); a second underline
is a false positive, and a false positive on a correct file is a bug, never noise.
`DemoConfigsetTest` pins the same claim headlessly — that exactly one reference in the
committed demo configset is dangling, and which one it is.

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
`SolrSchemaElementsTest`, `SolrSchemaVersionTest`, `SolrReferenceGuideTest`. Manual adds:
popup rendering, link clickability.*

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
- [ ] **DOC-5** — On the demo's `version="1.6"` schema, a field's property table reports
      `uninvertible` as **true** and names its origin *Solr default at schema version 1.6*,
      and `useDocValuesAsStored` likewise. Both flip at a different version, so a table
      that reports either as a flat Solr default has stopped reading the file.

**DOC-4's `Accepts` table shows a name and a value type, and nothing more.** The catalog
also carries each attribute's default and whether it is required, and no surface renders
them yet — the per-attribute hover that will is in *Not yet in the suite* below. A tester
looking for `minGramSize` marked required is looking for something unbuilt, not something
broken.

**DOC-5 is the check that a version number in the file is being read.** Solr's field
defaults are not constants: `uninvertible` defaults true below schema version 1.7 and
false from it, which is how Solr changed a default without breaking deployed schemas. The
demo pins 1.6 deliberately and says so in a comment, so this check fails the moment the
plugin starts answering from a constant instead. Bumping the demo to 1.7 removes the check
rather than passing it.

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
- [ ] **COMP-4** — The boolean value Solr would have used anyway is marked as the default,
      whichever it is — `indexed` marks `true`, `multiValued` marks `false`; properties
      whose default depends on the field type stay unmarked.
- [ ] **COMP-5** — A field's `type=` offers the declared types; a `copyField`'s two ends
      offer the declared fields; `<analyzer type=` offers `index`/`query`.
- [ ] **COMP-6** — On the demo's 1.6 schema, `uninvertible=` marks **true** as the default
      and `useDocValuesAsStored=` does too. Both are the opposite on a 1.7 schema, so this
      is the same claim DOC-5 makes, in the surface where a reader meets it first.

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
- Hover documentation on a factory attribute (`minGramSize`) — owner, value type,
  default or required marker
- A factory's complete effective configuration in its popup, unwritten attributes shown
  at their defaults
- The dimmed rendering of an attribute that merely restates its default, with a
  remove intention
- `solrconfig.xml`'s own structure: element completion and validation
- `omitNorms` and `docValues` resolved from the field type's class. Both report *see the
  guide* today, which is the honest answer while the catalog cannot say which traits a
  type carries — DOC-5's version resolution settles a different pair of properties
- The four inspections the plan lists and has not built: a relevance parameter naming a
  non-indexed field, an unused field type, a known-bad analyzer chain ordering, and a
  configuration element removed in the targeted Solr line. The five that exist are
  INSP-1 through INSP-6 above

## Pass log

One row per completed pass. Scope names what was skipped and why, if anything.

**A pass is all-or-nothing, and so is its row.** A row left at *in progress* is not a
partial result — it is a pass whose outcome nobody knows, which is the same evidence as no
pass at all. The two below are recorded as unfinished rather than deleted, because knowing
a pass was started and abandoned is worth more than a gap.

| Date | Commit | Ran by | Scope | Result | Notes |
|---|---|---|---|---|---|
| 2026-07-30 | e4a35ac | | full suite | **not completed** | first pass with this document; superseded before it closed |
| 2026-08-01 | a2e0bc5 | | full suite | **not completed** | sandbox relaunched to verify the catalog completion, typed-attribute inspections and resource/handler navigation merged since the previous pass |
| | 4b9cbf9 | | full suite | *pending* | the first pass that can close DOC-5 and COMP-6, and the one the outstanding screenshots come from |
