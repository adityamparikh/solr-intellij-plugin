# Manual test suite

A repeatable verification pass over the [sandbox](glossary.md#sandbox) IDE, run against the demo
project. This page owns every check: its **ID**, the **gesture**, the **expected outcome**, and one line
of why it is pressed by hand. [The plan](../specs/plans/0002-solr-intellij-plugin-plan.md) owns what is
built; a feature's checks join here when its code has shipped and there is something to press.

**[The checklist](manual-test-checklist.md) is this page with one line per check**, for a pass in one
sitting. Change a check here and its line there. Its
[Before you start](manual-test-checklist.md#before-you-start) is the setup for both: launching the
sandbox, licensing it, function keys on a Mac, starting a Solr, and closing rather than killing the
sandbox.

**Why manual.** Most claims below are also asserted headlessly, but a light
[fixture](glossary.md#fixture) cannot see whether a hint renders, a popup reads, a message is true of
what it reports, or features survive real indexing. Retire a check once automation covers its risk.

## How to run a pass

1. `./gradlew runIde` — the sandbox opens `demo/`. Open
   [`solr/conf/managed-schema.xml`](glossary.md#managed-schema).
2. Uncheck every box from the previous pass. A pass is all-or-nothing; history lives in the
   [pass log](#pass-log), not in the boxes.
3. Work top to bottom. The baseline comes first because every later "break it" check ends by
   restoring it.
4. After each check that edits the demo, `git status demo/` should be clean. Trust that over the undo
   count, and restore with `git checkout -- demo/` when they disagree.
5. Record the pass in the [pass log](#pass-log).

**Screenshots (📸) are checkboxes too.** Each sits beside the check whose gesture produces it, names the
file to save in `docs/images/`, and links to [the screenshot catalog](screenshots.md) for the framing.
One taken during a pass is evidence the check passed. Hand over plain captures only; the `-annotated`
pairs are made from them afterwards.

If every feature is dead at once, suspect the sandbox: delete `.intellijPlatform/sandbox/<project>/<IDE>/system-test`
and relaunch — [the testing guide](how-to/testing-and-the-build-gates.md) has the signature.

---

## 1. Activation (ACT)

- [ ] **ACT-1** — Open `demo/`. `managed-schema.xml` shows inlay hints. *The demo passes the outer gate
      through its Solr client dependency.*
- [ ] **ACT-2** — Open the file immediately after launch, before indexing finishes. Hints and hovers
      already work. *Everything is [dumb-aware](glossary.md#dumb-mode); a feature that waits for
      indexing is a regression.*
- [ ] **ACT-3** — Settings → Languages & Frameworks → **Solr Configsets** lists `solr  detected
      solr/conf`, with the remove button disabled on it. *A detected configset is found by rule and has
      nothing to remove.*
- [ ] **ACT-4** — Create a directory holding a lone `schema.xml` outside any `conf/`, right-click it and
      choose *Mark Directory as Solr Configset Root*. The file lights up — warnings, dimming and hints
      together — without a restart, and the settings page lists it as *marked*. Right-click again:
      *Unmark Solr Configset Root* returns it to plain XML, hints included.
- [ ] **ACT-5** — Untick *Recognise Solr configsets in this project* and apply. **Every** Solr feature
      goes quiet, including in the ACT-4 directory. Tick it again. *The switch is the plugin's kill
      switch, marked roots included.*

## 2. Zero-false-positive baseline (BASE)

**Count the rows that begin `Solr:` in the Problems tool window, not the underlines.** The demo's
British-English comments trip the platform's American-English locale inspection four times and
`configsets` trips its spellchecker once, so the schema shows seven problems of which two are this
plugin's.

- [ ] **BASE-1** — `managed-schema.xml`, untouched: **exactly two** errors, each a red wave — the planted
      dangling `manufacturer` copyField near the bottom, and the planted undeclared
      `type="discontinued"` on `legacy`. Neither may be fixed. The greyed attributes are
      [restated defaults](#11-an-attribute-that-restates-its-default-dim), not findings.
- [ ] **BASE-2** — `solrconfig.xml`, untouched: **zero**.

*The suite's most important check.* Solr configuration is full of syntax that resembles a field name
(`fl` holds `score`, `*`, `max(price,0)`); a third report is a false positive, and a false positive on a
correct file is a bug.

## 3. Match-capability [inlay hints](glossary.md#inlay-hint) (HINT)

- [ ] **HINT-1** — `string` fields (`id`, `sku`, `category`) read whole-value, case-sensitive, and
      `indexed, stored, doc values, single-valued`. *The demo's `string` type declares
      `docValues="true"` itself, though schema version 1.6 alone would default it off.*
- [ ] **HINT-2** — `text_general` fields (`description`, `text`) read tokenised, case-insensitive, no
      efficient prefix support, `indexed` and `no doc values`; `description` adds `stored,
      single-valued`, `text` adds `not stored, multi-valued`.
- [ ] **HINT-3** — `name_prefix` (type `text_prefix`) reads prefix-capable and `indexed, not stored, no
      doc values, single-valued`.
- [ ] **HINT-4** — Hints sit inline beside the declaration with no hover, readable at presentation font
      size.
- [ ] **HINT-5** — `notes` (type `custom_text`, whose analyzer names the unrecognised
      `com.example.MyTokenizerFactory`) shows `indexed, stored, no doc values, single-valued` and no
      match claim. `legacy` (undeclared type `discontinued`) shows no hint at all.
- [ ] 📸 **`docs/images/01-hints-match-capability.png`** — the field block at lines 66–77, all nine
      fields, no interaction.
      [Catalog entry 1](screenshots.md#1-match-capability-hints--01-hints-match-capabilitypng).

## 4. Navigation and [Find Usages](glossary.md#find-usages) (NAV)

- [ ] **NAV-1** — Cmd+Click a field's `type="text_general"`: lands on
      `<fieldType name="text_general">`.
- [ ] **NAV-2** — Cmd+Click a `<copyField>`'s `source` and `dest`: each lands on its field.
- [ ] **NAV-3** — Find Usages (⌥F7) on a field's `type="text_general"`, and again on the
      `<fieldType name="text_general">` declaration: both list the same four — `name`, `description`,
      `text` and the `*_t` dynamic field. *The declaration is where a reader reaches for the gesture.*
- [ ] **NAV-4** — In `solrconfig.xml`, Cmd+Click a field name inside a handler parameter (`qf`, `df`, a
      `facet.field` item): lands on the schema declaration, and each name in `name^3 description`
      navigates on its own. Find Usages from the parameter, from a `copyField` end, and from
      `<field name="description">` all list the parameter.
- [ ] **NAV-5** — Cmd+Click `words="stopwords.txt"` on a filter: opens the file. *The demo declares no
      `synonyms=`, `protected=`, `<charFilter>` `mapping=` or `lang/` path; fixtures cover those, and
      each entry of a comma-separated list navigating on its own.*
- [ ] **NAV-6** — Find Usages on `<dynamicField name="*_t">`: reports the `pf` naming `body_t` in
      `solrconfig.xml`, highlighted at `body_t` alone. *A name the pattern supplies and never spells,
      which IntelliJ's default word-index search can never find.*
- [ ] **NAV-7** — Read the labels in NAV-3's and NAV-6's windows. The headers read **Field type** over
      `text_general` and **Dynamic field** over `*_t`; the groups read **Field declaring this type** and
      **Handler parameter in solrconfig.xml**. Never *Solr Declaration Target* (the platform
      de-camel-casing an internal class name) or *Unclassified*. *A correct answer under a wrong label
      reads as broken.*
- [ ] **NAV-8** — Find Usages on `stopwords.txt` itself, from the Project view or the path: both
      `solr.StopFilterFactory` occurrences group under **Analyzer component reading this file**, not
      *Unclassified*.
- [ ] **NAV-9** — Cmd+Click the `/select` handler's `class="solr.SearchHandler"`: it resolves nowhere
      and **draws no warning**. *The demo carries `solr-solrj` only, and `SearchHandler` lives in
      `solr-core`; a configset naming a class the project lacks is ordinary. Add `solr-core` to the
      demo to press the landing half, where the `solr.` shorthand must resolve.*
- [ ] **NAV-10** — Quick documentation on that same value **while the project is still indexing**
      (right after opening, or after *File → Invalidate Caches → Just Restart*): it answers from the
      catalog. *This gesture's failure once took the whole popup down, including what needed no
      index.*
- [ ] 📸 **`docs/images/07-find-usages-field-type.png`** — NAV-3's window, invoked from the
      `<fieldType name="text_general">` declaration.
      [Catalog entry 7](screenshots.md#7-find-usages-on-a-field-type--07-find-usages-field-typepng).
- [ ] 📸 **`docs/images/08-nav-solrconfig-field-reference.png`** — Cmd+hover `name` at
      `solrconfig.xml:28`, framing the tooltip.
      [Catalog entry 8](screenshots.md#8-navigation-from-solrconfigxml-into-the-schema--08-nav-solrconfig-field-referencepng).
- [ ] 📸 *Optional:* **`docs/images/09-nav-resource-file.png`** — caret on `words="stopwords.txt"` at
      `managed-schema.xml:34`, then **Quick Definition**. Do not publish the hover: its tooltip is an
      absolute path through your home directory.
      [Catalog entry 9](screenshots.md#9-navigation-to-a-resource-file--09-nav-resource-filepng-optional).

## 4a. [Rename](glossary.md#rename-refactoring) (REN)

Every check here ends in an undo — renames edit both files.

- [ ] **REN-1** — Shift+F6 on `category` in `<field name="category">` at `managed-schema.xml:70`. The
      dialog says *Rename **field** 'category'*, not *Rename Solr Declaration Target*. *The same
      description NAV-7 checks, reached by a second refactoring.*
- [ ] **REN-2** — Complete the rename to `product_category`: the declaration and the `qf` at
      `solrconfig.xml:28` both change. **Undo; both files are clean.** *The cross-file half is the one a
      hand-edit misses.*
- [ ] **REN-3** — Rename `text_general` from its declaration at line 31: every `type=` follows, the
      `*_t` dynamic field's included. **Undo.**
- [ ] **REN-4** — Rename `<dynamicField name="*_t">` at line 84 to `*_txt`. The declaration updates, but
      the `pf` naming `body_t` is left exactly as written. *Rewriting `body_t` to `*_txt` would put a
      glob where a field name belongs.*
- [ ] **REN-5** — In REN-4's state, `body_t` in that `pf` is underlined as an unknown field. **Undo; the
      underline goes.** *That report is what makes the partial rename defensible.*

## 5. [Quick documentation](glossary.md#documentation-provider) (DOC)

Quick documentation is F1, ⌃J or hover.

- [ ] **DOC-1** — On a field's `type` value: the type, its analyzer chain, and what a field of it can
      match.
- [ ] **DOC-2** — The popup's Reference Guide link names the Solr version the configset targets, and
      opens.
- [ ] **DOC-3** — Hover the tags `<schema>`, `<field>`, `<fieldType>`, `<dynamicField>`, `<copyField>`,
      `<uniqueKey>`: each answers, with a configset-specific sentence where one exists (which fields a
      copy rule joins, which field is the unique key, how many fields use a type).
- [ ] **DOC-4** — Hover `class="solr.WordDelimiterGraphFilterFactory"`: its kind, both spellings, the
      attributes it reads, how this configset uses it, a guide link, and a one-line Javadoc summary —
      `solr.StandardTokenizerFactory` reads "Factory for StandardTokenizer." *Its `Accepts` table shows
      name and value type only; defaults live in DOC-4b and DOC-7.*
- [ ] **DOC-4b** — Hover the EdgeNGram filter's *tag*: a **Configuration** table of every attribute the
      class accepts. `minGramSize` / `maxGramSize` bold, *on this filter*; `preserveOriginal` **false**,
      *Solr default*; `luceneMatchVersion` an em dash, *no default recorded*. A custom `class` the
      catalog does not know offers nothing on the tag, not an empty table.
- [ ] **DOC-5** — On the demo's `version="1.6"` schema, a field's property table reports `uninvertible`
      **true**, origin *Solr default at schema version 1.6*, and `useDocValuesAsStored` likewise.
- [ ] **DOC-6** — Change the root to `version="1.7"` and reopen: `uninvertible` reads **false**,
      `useDocValuesAsStored` stays true, both origins name 1.7. **Undo.** *A table hard-coding `true`
      passes DOC-5; only the flip proves the version is read. The demo stays at 1.6 so both sides stay
      testable.*
- [ ] **DOC-7** — Hover `minGramSize` on the `EdgeNGramFilterFactory`: owner class, *a whole number*, the
      required marker, and a **Does** row — *the shortest gram to index*. `preserveOriginal` shows
      default `false` instead of a required marker. An attribute the catalog does not list, or any on an
      unknown class, stays silent.
- [ ] **DOC-8** — Hover attribute *names*: `name` and `type` on a `<field>`, `name` and `class` on the
      `<fieldType>`, `source` and `dest` on a `<copyField>`. Each explains itself; the two `copyField`
      ends read differently.
- [ ] **DOC-9** — Hover `version` on `<schema>`: what the attribute decides anywhere, then what `1.6`
      decides here — `docValues` off, `uninvertible` on. It flips under DOC-6's edit; check it then.
- [ ] **DOC-10** — Hover an attribute the hand-written table does not describe — `maxShingleSize` on a
      `solr.ShingleFilterFactory`: owner, value type, default or required marker, guide link, and **no
      Does row**. *The table is silent by default; a row where none belongs is the failure.* A
      `copyField`'s `name` never gaining the `<field>` description has no demo fixture and is covered
      headlessly.
- [ ] 📸 **Re-capture `docs/images/02-quick-doc-field.png`** — caret in `name="category"` at line 70,
      F1, cropped to the popup including the `uninvertible` row. Check the catalog entry first: it waits
      on `omitNorms` and `docValues` resolving from the field type's class.
      [Catalog entry 2](screenshots.md#2-quick-documentation-on-a-field--02-quick-doc-fieldpng).

## 6. [Inspections](glossary.md#inspection) and [quick-fixes](glossary.md#quick-fix) (INSP)

**"Underlined" depends on the scheme.** A name that resolves to nothing (INSP-1 to INSP-5) is an error,
a red wave everywhere. Every other check here is a warning, which *Dark* and *Islands Dark* draw as a
yellow wave and *Default*, *Darcula* and *Light* (behind *Islands Light*) draw as a tinted background.
Read "underlined" in a warning check as *marked*, and confirm by hovering or in the Problems view.

Every check ends with **undo until [the baseline](#2-zero-false-positive-baseline-base) is clean**.

- [ ] **INSP-1** — Change a `<copyField>` `dest` to a name no field declares: a red wave, and Alt-Enter
      offers the declared fields, closest spelling first.
- [ ] 📸 **`docs/images/04-inspection-copyfield-quickfix.png`** — use the *planted* `manufacturer` rule at
      line 92, so no undo is needed. Frame the underline and the open Alt-Enter menu, then Escape —
      every item rewrites the file.
      [Catalog entry 4](screenshots.md#4-inspection-and-quick-fix--04-inspection-copyfield-quickfixpng).
- [ ] **INSP-2** — Change a field's `type` to a bogus value: a red wave; the fix offers the declared
      types, and applying one leaves a file that parses clean.
- [ ] **INSP-3** — Delete the `name` field: its copy rule is flagged at once, without saving.
- [ ] **INSP-4** — In `solrconfig.xml`, point `qf` or `df` at an undeclared field: flagged.
- [ ] **INSP-5** — Add a made-up attribute to a `<field>`: flagged, naming the element that cannot
      accept it.
- [ ] **INSP-6** — Set `indexed="yes"`: the invalid-attribute-value inspection fires.
- [ ] **INSP-7** — Three edits to `text_prefix`'s **index** analyzer (lines 45–49):
      1. Add `<filter class="solr.WordDelimiterGraphFilterFactory" splitOnCaseChange="1"/>` *below*
         `LowerCaseFilterFactory`: the `1` is marked, naming the filter that already folded case away.
      2. Move it *above* `LowerCaseFilterFactory`: the warning clears. *Nothing added or removed — this
         proves the check is about order.*
      3. Add `<filter class="solr.FlattenGraphFilterFactory"/>` above the word-delimiter filter: its
         `class` is marked, naming the graph filter below it.
- [ ] **INSP-8** — Change `name_prefix`'s `type` to `text_general`: `<fieldType name="text_prefix">` goes
      **dim**, at once, not underlined; the other three types stay lit. *Dead configuration, not a
      defect, and the presentation is the claim.*
- [ ] **INSP-10** — In `solrconfig.xml`, add `<str name="sort">text asc</str>` to `/select`'s `defaults`:
      `text` is marked, and the message names what sorting needs rather than blaming doc values. *`text`
      is `multiValued`; at 1.6 it is un-invertible, so a doc-values message would be false.*
- [ ] **INSP-11** — Add `<arr name="facet.field"><str>category</str></arr>` to the same `defaults`:
      nothing fires, at `version="1.6"` or `1.7`. Add `docValues="false"` to `category`: the
      `facet.field` entry is marked. Undo both. *1.7 also gives `solr.StrField` doc values by default,
      so a version flip cannot make a `string` unfacetable; only a declared absence can.*
- [ ] **INSP-12** — In INSP-11's marked state, `<str name="qf">category</str>` stays clean while the
      `facet.field` is marked. *One field, searchable and unfacetable: the two inspections ask different
      questions.* Completion shows the same split — PRM-4's `sort` list withholds `text`.
- [ ] **INSP-13** — In `solrconfig.xml`, add `<indexConfig><nrtMode>true</nrtMode></indexConfig>` inside
      `<config>`: the element is marked with **Solr's own sentence** naming it discontinued. Add
      `<indexDefaults>` directly inside `<config>` instead: the message names `<indexConfig>` as the
      replacement, and the platform's own *XML tag has empty body* sits beside it. Move `<nrtMode>` into
      a made-up `<acmeThing>`: the warning goes. Undo. *A retirement belongs to a position, and Solr's
      wording is the only part that says what to do next.*
- [ ] **INSP-13b** — Move `<nrtMode>` directly under `<config>`: **no** warning. *Solr reads it as
      `get(indexConfigPrefix).get("nrtMode")` and never at the root, so it is inert there.*
- [ ] **INSP-14** — In `/select`'s `<lst name="defaults">`, rename `<str name="rows">` to `rwos`: marked,
      and Alt-Enter offers `rows`. Write `<str name="pf2">` and `<str name="pf3">` side by side: neither
      is flagged. Undo. *That pair is why the rule checks knownness before distance.*
- [ ] **INSP-15** — Change `name`'s `indexed="true"` to `false` at `managed-schema.xml:68`. In `/select`'s
      `qf` at `solrconfig.xml:28`, `name` alone is marked — not `^3`, not the names after it — reading
      *Solr: 'name' is not indexed and has no doc values, so 'qf' cannot search or boost it*, and
      `solrconfig.xml` has exactly one problem. Undo. *Invisible in Solr: the field simply never
      matches. It fires because the catalog knows `solr.TextField` carries no traits, so `docValues`
      resolves to a definite false.*
- [ ] **INSP-9** — Undo everything, DOC-6's version edit included: back to **two** errors in
      `managed-schema.xml` and zero in `solrconfig.xml`. None of INSP-7's edits survives, and no type is
      dimmed.

## 7. [Completion](glossary.md#completion-contributor) — the schema's own vocabulary (COMP)

- [ ] **COMP-1** — `<` inside `<schema>` offers the elements legal there and nothing else.
- [ ] **COMP-2** — Attribute completion inside `<field ` offers the property table with each summary, and
      omits attributes already on the tag.
- [ ] **COMP-3** — `<fieldType>`'s general properties (`positionIncrementGap`, `synonymQueryStyle`,
      `enableGraphQueries`…) complete, documented like field properties.
- [ ] **COMP-4** — The boolean Solr would use anyway is marked as default — `indexed` marks `true`,
      `multiValued` marks `false`; properties whose default depends on the field type stay unmarked.
- [ ] **COMP-5** — A field's `type=` offers the declared types, each with what it matches (`custom_text`
      falls back to its class); a `copyField`'s ends offer the declared fields; `<analyzer type=` offers
      `index` and `query`.
- [ ] **COMP-6** — At 1.6, `uninvertible=` marks **true** as default; at `version="1.7"` it marks
      **false**. Undo. *DOC-5 and DOC-6's claim, where a reader meets it first.*
- [ ] 📸 **`docs/images/05-completion-field-properties.png`** — caret after `stored="true"` on line 70,
      before the `/`, type a space; frame the summaries and accepted values. Undo the space.
      [Catalog entry 5](screenshots.md#5-completion-over-the-schemas-own-vocabulary--05-completion-field-propertiespng).

## 8. Completion — catalog-backed (CAT)

- [ ] **CAT-1** — `class=` on a `<fieldType>` offers `solr.*` field type classes; on a `<tokenizer>` or
      `<filter>`, factories — each following the configset's declared Solr line, and never mixed.
- [ ] **CAT-2** — `<filter class="solr.WordDelimiterGraphFilterFactory" ` offers `generateWordParts`,
      `catenateAll`, `splitOnCaseChange` among others. *Proves the build's constructor-bytecode pass —
      the only place a factory declares its attributes — reached the editor.*
- [ ] 📸 **`docs/images/06-completion-factory-attributes.png`** — the `EdgeNGramFilterFactory` at line
      48, caret before the closing `/`, space; frame `luceneMatchVersion` and `preserveOriginal`, which
      it does not already declare.
      [Catalog entry 6](screenshots.md#6-catalog-backed-factory-attributes--06-completion-factory-attributespng).
- [ ] **CAT-3** — `class=` on a `<requestHandler>` in `solrconfig.xml` offers `solr.SearchHandler` and
      `solr.UpdateRequestHandler`, and no `solr.SchemaCodecFactory` or field type. `<directoryFactory>`
      and `<codecFactory>` each offer their own handful.
- [ ] **CAT-4** — F1 on `solr.SearchHandler` says *request handler*, and its Reference Guide link lands on
      a page about request handlers. *Nothing in the build checks a constructed URL resolves.*

## 9. Completion — field names inside [`solrconfig.xml`](glossary.md#solrconfigxml) parameters (PRM)

- [ ] **PRM-1** — Caret inside `/select`'s `<str name="qf">` after the value, invoke completion: the
      schema's fields with their types, and `*_t` italicised as a dynamic pattern.
- [ ] **PRM-2** — Inside `<str name="rows">`: no field names. *Offers are scoped to parameters known to
      hold field names.*
- [ ] **PRM-3** — Immediately after the `^` in `name^3`: no field. *Completing would produce
      `name^name`.*
- [ ] **PRM-10** — F1 inside that `^3`: explains what the boost scales and `name`'s searchability, and
      nothing reaches the Problems view. Two characters left, on `name`, PRM-5's popup is unchanged.
      *PRM-3's silence is correct; documentation there still answers.*
- [ ] **PRM-11** — F1 inside `bf`'s `^2.5`: an *additive* function-query boost, naming no field. *Same
      `^n`, different meaning; `bf` is also where the relevance inspection deliberately stays silent.*
- [ ] **PRM-4** — In `<str name="sort">`: fields at the start of a clause, not `text` (multi-valued, so
      unsortable), and nothing after `text ` — the second token is a direction.
- [ ] **PRM-6** — The `name` of a `<str>` inside `<lst name="defaults">` offers Solr's request
      parameters, each described — `qf` reads *query and init param for query fields*. *Where the
      vocabulary is learnable from.*
- [ ] **PRM-7** — A parameter already set on the list is not offered again; `defType` **is** offered,
      though Solr declares it outside the package the rest come from.
- [ ] **PRM-8** — `<str name="defType">` offers `edismax`, `dismax`, `lucene`, `func` and no class name;
      `<queryParser class=…>` offers classes and no registry key. *A class name in `defType` is a
      configset Solr cannot load.*
- [ ] **PRM-9** — F1 on `qf` names `DisMaxParams`; on `edismax`, `ExtendedDismaxQParserPlugin`; on
      `my.own.param`, **nothing**. *Custom components read parameters no generator will see.*
- [ ] **PRM-5** — F1 on a field name inside `qf` shows *the field's* documentation — type, analyzer
      chain, property table — not the `<str>` element's. *It works through reference resolution, so it
      is the gesture likeliest to vanish when either provider changes.*

## 10. Completion — `solrconfig.xml`'s own structure (STR)

*Before the plugin owned these descriptors, the platform offered whatever a sibling tag was named — a
guess that looks like knowledge in a file of same-named tags.*

- [ ] **STR-1** — `<` on a blank line directly inside `<config>`: Solr's top-level vocabulary —
      `requestHandler`, `updateHandler`, `luceneMatchVersion`, `dataDir`, and many the demo never
      writes — not a copy of the siblings above the caret.
- [ ] **STR-2** — Type a `<query>` (the demo declares none) and `<` inside it: `filterCache` and its
      siblings, and **not** `dataDir`. *What belongs under `<query>` is not what belongs under
      `<config>`.*
- [ ] **STR-3** — `nrtMode` is offered nowhere — not under `<config>`, and not under `<indexConfig>`,
      which offers `deletionPolicy`. *An element Solr rejects is never completed; INSP-13 reports one
      already written.*
- [ ] **STR-4** — Inside a made-up `<acmeThing>`: nothing offered, and any element typed there draws no
      warning. *The plugin has nothing to say about a custom component.*
- [ ] **STR-5** — Attribute completion in a `<filterCache>` under `<query>`: `autowarmCount`, `class`,
      `enabled`, `initialSize`, `maxRamMB`, `regenerator`, `size`. A made-up attribute is **not**
      underlined.
- [ ] **STR-6** — The same gesture in a `<requestHandler>` offers **nothing**, correctly. *Attribute
      completion reads only `EditableSolrConfigAttributes.json`, which covers the four caches,
      `requestParsers` and the two commit elements. When the generator decodes `SolrConfig.plugins`'
      `REQUIRE_NAME`/`REQUIRE_CLASS`, this check inverts and should be rewritten.*

## 11. An attribute that restates its default (DIM)

**The untouched demo is already full of restated defaults** — every field declares `indexed="true"`
and most `stored="true"`, both Solr's defaults — so a dozen greyed attributes in BASE-1's clean schema
is this feature working. None may ever reach the Problems view.

- [ ] **DIM-1** — Untouched `managed-schema.xml`: `indexed="true"` and `stored="true"` render greyed,
      whole-attribute, **with no underline, wave or strikeout**, and nothing in the Problems view.
      `stored="false"` on `name_prefix` and `text` is not greyed. **Check High Contrast too** — the dim
      has its own key there, grey text only.
- [ ] **DIM-2** — Alt-Enter on a greyed attribute offers to remove it; afterwards the schema parses and
      the field's documentation reports the same effective values. Undo.
- [ ] **DIM-3** — Change `name`'s `indexed="true"` to `false`: the dim goes. Undo. *Deciding versus
      restating is the whole feature.*
- [ ] **DIM-4** — `enableGraphQueries="true"` on a `<field>`: not dimmed — Solr ignores it there. On a
      `<fieldType>`: dimmed. Undo both. *The inherited-default half has no demo fixture and is covered
      headlessly.*

## 12. [Intentions](glossary.md#intention) — companion fields (INT)

- [ ] **INT-1** — Alt-Enter on a tokenised text field offers an exact-match companion; the new `<field>`
      and `<fieldType>` land in the schema and the file still parses.
- [ ] **INT-2** — That field no longer offers it once the companion exists, and a `string` field never
      does; the prefix companion withholds itself on `name`, which has `name_prefix`. Undo. *An
      intention that keeps offering itself after it was applied is the failure here.*

## 13. Connections and the collections tool window (SRV)

*Only a running IDE shows whether a tool window registers at all.* Every check needs a Solr:
`docker compose -f demo/compose.yaml up -d` starts SolrCloud with a `products` collection that drifts
from `solr/conf` by one row of each kind; *Index the sample products* in `queries.http` fills it.
SRV-8, SRV-9 and SRV-23 need `docker run -p 8983:8983 solr:10.0.0 solr-precreate books` instead —
`_default`'s `*_s` and `*_f` patterns, and a standalone server — with the demo's stopped.

### Connections and the tool window

- [ ] **SRV-1** — Settings → Tools → **Solr Connections** exists; `+` opens a form, and saving
      `http://localhost:8983/solr` adds a row showing that URL.
- [ ] **SRV-2** — The form refuses a URL with no scheme, naming the field, and accepts a path other than
      `/solr`. *Solr behind a reverse proxy is ordinary.*
- [ ] **SRV-3** — Reopen a connection with a stored password: the field is empty and says one is stored.
      Change only the display name, OK, reopen: still stored. *An empty field must not read as "no
      password".*
- [ ] **SRV-4** — The **Solr** tool window opens on the right listing `Cores` → `books` (standalone) or
      `Collections` → shards → replicas (cloud). Its stripe icon is the plugin's grey sunburst in light
      and dark themes. On cloud, `books` and `shard1` start with a **green dot and no word `GREEN`**;
      hovering shows `Health: GREEN`.
- [ ] **SRV-5** — Stop Solr, Refresh: the failure appears **inline above the tree, once**, the tree
      empties, and there is no popup or stale topology.
- [ ] **SRV-6** — Start Solr, Refresh: the banner clears and the tree repopulates. Leave it a few minutes:
      **nothing refetches on its own**.
- [ ] **SRV-7** — With two connections, switching re-reads the other server, and the choice survives
      reopening the project.
- [ ] **SRV-8** — `curl 'http://localhost:8983/solr/books/update?commit=true' -H 'Content-Type:
      application/json' -d '[{"id":"1","author_s":"Frank Herbert","price_f":9.99}]'`, then expand
      **Fields**: `author_s` appears marked `← *_s`. *That field is in no configset; this is what the
      view exists for.*
- [ ] **SRV-9** — `price_f` shows its type and **no document count**; `author_s` shows one; `_root_`
      shows `(unstored field)`. *A point field has no inverted index to count from.*
- [ ] **SRV-10** — Collapsing and re-expanding **Fields** does not re-read the server; only Refresh does.
      Confirm in Solr's request log.
- [ ] **SRV-10a** — Right-click `products`: it is selected first, and the menu offers *Query products*
      and *Explain Scoring in products*. *Query products* opens a scratch `solr-products.http` holding a
      `GET`, and **nothing runs until you press run**, then SRV-13's summary. On **Fields**,
      `category` offers *Find Documents with* and *Count Values of*; `description` only *Find
      Documents with* (counting a tokenised field counts words). A shard offers nothing. A connection
      with a user puts a comment naming the `Authorization` header in the file, and **no password**.

### Queries in the HTTP Client

- [ ] **SRV-11** — In `queries.http`, **Add Request** (gutter `+` or Alt-Insert): a **Solr** group offers
      five requests; insert *Query a Solr collection*. *The menu must show labels — a request body in
      a label's place is a swapped `TemplateDescriptor` argument, which compiles fine.*
- [ ] **SRV-12** — `http-client.env.json` defines **local** as `http://localhost:8983/solr` and the
      `products` collection. Pick **local** and run: results in the HTTP Client's viewer. *The committed
      file names no host, so a colleague can use their own server.*
- [ ] **SRV-13** — Run *Query a Solr collection* on a collection with documents: above the raw JSON, the
      match count, the time, and an aligned table. `_version_` is not a column, and the line above says
      which internal fields were left out.
- [ ] **SRV-14** — *Explain why documents scored*: each document's explanation keeps Solr's indentation.
      *The nesting is the information.*
- [ ] **SRV-15** — Run a request to something not Solr (`GET https://api.github.com/repos/apache/solr`):
      **nothing from this plugin appears.**
- [ ] **SRV-16** — **With no connections at all** (before SRV-1, or remove them), insert *Query with a
      JSON body* and complete inside `"fields"`: the project's configset fields with type and configset;
      `*_i`-style patterns in italics.
- [ ] **SRV-16b** — Select a connection, with `local` naming an existing collection, and press
      <kbd>Ctrl-Space</kbd> there: **that collection's** fields, each `<collection> · <connection>`, and
      nothing it lacks. Again: the list returns at once (schema read once). Add a second environment
      naming another collection: completion falls back to the configsets. *The plugin cannot know which
      environment is selected and will not guess.*
- [ ] **SRV-16c** — Delete the name and **type** one letter so the popup opens by itself. With a fresh
      connection it offers configset fields and **sends nothing** — no `/schema` in the server's log.
      *A keystroke never opens a socket; only <kbd>Ctrl-Space</kbd> may.*
- [ ] **SRV-17** — The same completion works in `"sort"` and a facet's `"field"`, and offers nothing at
      `"limit"` or inside `"query"`.
- [ ] **SRV-18** — Complete inside a string in another JSON file (`package.json`): **no Solr field
      names.** *The contributor is registered against all JSON.*

### The query console

Against the demo's `products`, with *Index the sample products* run. From SRV-33 on, Settings → Tools →
**Solr Connections** → *Open queries from the Solr tool window in* is set to **Query console**; SRV-40
sets it back.

- [ ] **SRV-32** — On a fresh sandbox the setting reads **HTTP Client (a scratch .http file)**, and
      *Query products* opens the scratch file as in SRV-10a. *The default must change nothing.*
- [ ] **SRV-33** — Choose **Query console**, OK, *Query products*: a **Query: products** tab with the
      Admin's form — `common`, `debugQuery`, `defType`, *Raw Query Parameters* — `q` `*:*`, `rows` `10`,
      **nothing run**. Choosing it again brings the same tab forward.
- [ ] **SRV-34** — **Execute** (<kbd>⌘Enter</kbd>): the request line shows
      `…/products/select?q=*%3A*&rows=10&indent=true`, **Results** SRV-13's summary and table,
      **Response** the JSON. Clicking the request line opens a scratch `.http` with
      `POST {{solrUrl}}/{{collection}}/select`, the form body and no host; run through `local`, it gives
      the same numbers.
- [ ] **SRV-35** — Set `q` to `name:"usb & cable"` (any value with `&` and `+`) and execute: Solr receives
      it whole — a real match count, not a 400. *This is why the console exists.*
- [ ] **SRV-36** — `wt` **xml**: no table, **Response** in front with highlighted XML. Handler `/nosuch`:
      *Solr answered 405* and the error page — 405 because Jetty refuses a POST to an unserved path.
      Handler `dismax`: **nothing sent**, and the status names `handleSelect`.
- [ ] **SRV-37** — `defType` starts at *(handler default)*. **dismax** shows `q.alt`, `qf` … `bf`, not
      `pf2`; **edismax** adds `uf` … `lowercaseOperators`. Type `name^2` in `qf`, return to *(handler
      default)*, execute: no `qf` and no `defType` on the request line.
- [ ] **SRV-38** — In `fl`, type `na` and <kbd>⌃Space</kbd>: the collection's fields as
      `products · local`, plus `score`, `*`, `[docid]`. Type a letter in `sort` and let the popup open:
      fields, **with no request reaching Solr** (`docker compose -f demo/compose.yaml logs -f solr`).
- [ ] **SRV-39** — Right-click `category` → *Find Documents with category*: the open tab holds `q`
      `category:*`, `fl` `id,category`. *Count Values of category*: `rows` `0` and three facet lines;
      execute, and **Response** holds `facet_counts`.
- [ ] **SRV-40** — Remove the `local` connection with the tab open: the tab closes. Add it back, set the
      choice back to **HTTP Client**, and SRV-32's scratch file returns.

### Drift, upload and apply

Writing checks — SRV-22, 24 and 26 — go to a collection you can spare. **The demo's `solr/conf` cannot
be deployed**: its planted defects stop a core starting. Where a check needs a deployable configset, use
a copy with `custom_text`, `notes` and `legacy` removed; `demo/README.md` says why.

- [ ] **SRV-19a** — Close the IDE with the **Drift** tab showing and reopen: once indexing ends, the
      configset chooser lists the project's configsets. Copy a configset directory to a new name and open
      the chooser: the copy is offered, and the current choice survives. **Click the chooser with the
      mouse**: it opens, and `idea.log` gains no *Read access is allowed from inside read-action only*
      from `SolrDriftPanel`. *It throws in a running IDE and never in a test.*
- [ ] **SRV-19b** — Open the **Collection** chooser in the **Drift** tab: the server's collections, each
      followed by *built from configset …* as the server reports it. A typed name the list lacks
      survives reopening the chooser. With the server down, the chooser lists nothing and raises no
      error — Compare reports the failure.
- [ ] **SRV-19** — Choose a configset and an existing collection, Compare. A field only in the configset
      reads *Not deployed*; one added through the Schema API, *Only on server*; one defined differently,
      *Differs*, **with both definitions side by side**. The summary names the Solr line, e.g.
      *Solr 10.0*. The demo's `solr` against `products` shows all three unedited: `sku` *Not deployed*;
      `legacy` and `custom_text` *Differs*; `manufacturer`, `_version_` and `plong` *Only on server*.
- [ ] **SRV-20** — Compare a deployable configset against a collection created from it: an empty table,
      and the summary says they agree **and across how many declarations**. *The count is all that
      distinguishes this from a comparison that never ran.*
- [ ] **SRV-21** — Stop Solr, Compare: the table stays empty and the failure is reported. **No field may
      read *Not deployed*.** *An unreachable server is not an empty one.*
- [ ] **SRV-22** — With drift on screen, **Upload and Reload**: a confirmation names the configset, the
      collection **and the server**. Cancel: nothing uploaded. Confirm (a deployable configset): the
      table is re-read and the difference clears. Uploading the demo's own `solr/conf` fails at the
      reload, correctly; `docker compose -f demo/compose.yaml down -v` and `up -d` rebuild `products`.
- [ ] **SRV-23** — Against a **standalone** Solr, Upload and Reload refuses, saying the server is not in
      SolrCloud mode, and **sends no upload**. *`/admin/configs` answers HTTP 400 there.*
- [ ] **SRV-24** — Delete `_version_` from the configset, Upload and Reload: the upload succeeds and **the
      table still shows drift**. *The view reports what the re-read found, not what the write returned.*
- [ ] **SRV-25** — Select a **Differs** row: the pane shows **the reason first**, the `replace-field`
      request under it, and **Apply Additive Changes** disabled. *A reader who meets the JSON first may
      copy it.*
- [ ] **SRV-26** — Select a **Not deployed** row (`sku` on `products`): its `add-field` payload shows.
      **Apply Additive Changes**: the confirmation names the count, collection and server. Afterwards
      only that row is gone — *1 not deployed, 3 only on the server, 2 differing; 16 agree* becomes
      *0 not deployed, …; 17 agree*.
- [ ] **SRV-27** — With only **Differs** rows, **Apply Additive Changes is disabled**.

### Indexing a test document

- [ ] **SRV-28** — Select a collection, **Index a Test Document**: the title names the collection **and
      the server**; the document carries the unique key first, then every other declared field —
      numbers unquoted, multi-valued fields as arrays — with no `_version_`, `_root_`, `_text_` or
      copy-field destination, and no problems reported. **OK works without an edit.**
- [ ] **SRV-29** — Misspell a field name: reported, and **OK is refused**. *Against `_default`, Solr would
      add the field to the schema and report success.*
- [ ] **SRV-30** — Delete the `id` line: refused. Restore it, add `"author_s": "x"`: accepted (`*_s`
      matches). Add `"_version_": 1`: a warning, and OK still works.
- [ ] **SRV-31** — Index with the default commit and query at once: **not there yet**, correctly —
      `commitWithin`, as the dialog said. A second later it appears. With *findable immediately*, it is
      there at once.

## 14. Field names and queries in Java and Kotlin (CODE)

The subject is `demo/src/main/java/com/example/demo/ProductSearch.java`: one correct query and two
planted defects. *A headless test can assert a gutter icon, a colour or a popup exists, not that it is
visible.*

- [ ] **CODE-1** — In `findBooks`, `categry` in `addFilterQuery("categry:books")` is marked, **the name
      only**, not the whole string.
- [ ] 📸 **`docs/images/26-code-field-warning.png`** — lines 43–47, holding CODE-1 and CODE-3 with `id` and
      `name` unmarked; see [entry 26](screenshots.md).
- [ ] **CODE-2** — The fix offers `category`, closest first, and leaves the rest of the string alone.
      Undo.
- [ ] **CODE-3** — `price` in `setFields("id,name,price")` is marked. *A field that never existed, where
      `categry` is a typo.*
- [ ] **CODE-4** — Nothing in `findSolrBooks` is marked. *A check that cannot stay quiet on correct code
      is worse than none.*
- [ ] **CODE-5** — In that query, `category` and `name` are coloured as fields and `AND` as an operator —
      three colours, none the string's green nor plain grey — in the default light and dark themes.
      Lowercase `and` loses its colour (Solr reads it as a term). *Settings → Editor → Color Scheme →
      Solr Query* previews both, and changing either repaints the editor.
- [ ] 📸 **`docs/images/27-code-query-colour.png`** — lines 37–39, `AND` uppercase, default light theme,
      caret out of frame; see [entry 27](screenshots.md).
- [ ] **CODE-6** — Caret inside `addFilterQuery("`, **type `c`**: the list opens by itself with the
      configset's fields starting `c`, each with type and configset — no `*_t` pattern, words or file
      names. After `category:`, typing `b` opens nothing (a value). In `setFields("id,name,price")`, a
      comma after `id` opens it again. An ordinary string elsewhere opens nothing.
- [ ] **CODE-7** — <kbd>Ctrl-click</kbd> `category` in `findSolrBooks`: lands on `<field name="category">`
      in `solr/conf/managed-schema.xml`. *The Java file belongs to no configset.*
- [ ] 📸 **`docs/images/28-code-navigate-to-schema.png`** — both files side by side via *Window → Editor
      Tabs → Split and Move Right*; see [entry 28](screenshots.md).
- [ ] **CODE-8** — <kbd>Ctrl-click</kbd> `categry` in `findBooks`: nothing resolves. *The check and the
      navigation must agree.*
- [ ] **CODE-9** — A gutter icon beside `setQuery` in `findSolrBooks`, and beside nothing else.
- [ ] **CODE-10** — Click it with a connection selected: the chooser opens **at the editor's top-right
      corner** listing the server's collections; choosing one runs the query and renders the answer as
      the console does.
- [ ] 📸 **`docs/images/29-code-run-from-gutter.png`** — the chooser open, framed to hold the gutter
      icon; see [entry 29](screenshots.md).
- [ ] **CODE-11** — Click it with no connection configured: it says so and sends nothing.
- [ ] **CODE-12** — A Kotlin file using SolrJ behaves identically. *One UAST recognizer serves both.*
- [ ] **CODE-13** — A Java file in a module with no Solr client: **no colour, icon, warning or
      completion.** *The registrations name no language, so every file reaches them.*

## Pass log

One row per pass, finished or not, oldest first. A pass is all-or-nothing: a partial pass is *not
completed*, which is the same evidence as no pass for the checks it skipped.

| Date | Scope | Result |
|---|---|---|
| 2026-07-30 | full suite | not completed |
| 2026-08-01 | full suite | not completed |
| 2026-08-03 | full suite as it then stood | passed |
| 2026-08-04 | full suite as it then stood | **passed** — the last full pass |
| 2026-08-06 | 27 editor checks | not completed — all pressed green |
| 2026-08-10 | PRM-1–5, INSP-10–12 | not completed — green; INSP-11 and 12 rewritten |
| 2026-08-15 | BASE-1, BASE-2, DIM-1, STR-1, STR-3, STR-5, INSP-13 | not completed — STR-5 rewritten and split; INSP-13 later superseded |
| 2026-08-16 | STR-1–3 | not completed — green |
| 2026-09-26 | server track, 12 checks in full and 3 in part | not completed — green |
| 2026-09-28 | owner's pass by the checklist, fixes re-pressed | not completed — nine findings fixed |
| 2026-09-28 | ACT-3–5 | not completed — green after fixes |
| 2026-10-20 | SRV-10a, SRV-32–40 | not completed — green; SRV-36 rewritten |
