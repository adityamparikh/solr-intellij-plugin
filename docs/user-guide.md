# User guide

> **Who this is for.** A Solr developer using this plugin day to day, looking up what a specific
> gesture (hover, completion, Alt-Enter) does and what it should show. Solr schema vocabulary is
> linked to the glossary throughout; IntelliJ Platform vocabulary is assumed only where a Java
> engineer would already recognise the UI gesture (hover, Alt-Enter) rather than its internal name.
> **Read first:** [Glossary](glossary.md) if Solr terms are new · [Project orientation](project-orientation.md)

Everything the plugin does today, organised by what you are trying to do rather than by which
IntelliJ [extension point](glossary.md#extension-point) implements it. This is the
configuration-files surface only — the
[server](../specs/plans/0002-solr-intellij-plugin-plan.md#server-track) and
[code](../specs/plans/0002-solr-intellij-plugin-plan.md#code-track) surfaces the specification
describes are not built yet, and this guide does not pretend otherwise. See
[the project orientation](project-orientation.md) for how the three fit together, and
[the implementation plan](../specs/plans/0002-solr-intellij-plugin-plan.md) for what "done" means for
each capability below.

## Following along

Every gesture here is reproducible against the committed demo project, which is also what the
[manual test suite](manual-test-suite.md) presses and what the [screenshots](screenshots.md) are
captured from:

```bash
./gradlew runIde
```

The [sandbox](glossary.md#sandbox) opens `demo/` and activates the plugin immediately — it declares
`org.apache.solr:solr-solrj`, which passes the outer activation gate, and carries a real
[configset](glossary.md#configset) under `demo/solr/conf/`. Open
`demo/solr/conf/managed-schema.xml` first; most of what follows lives there, and
`demo/solr/conf/solrconfig.xml` is the other file this guide reaches into — see the glossary for
[managed-schema](glossary.md#managed-schema) and [solrconfig.xml](glossary.md#solrconfigxml) if
either is unfamiliar. If nothing below happens when you try it, see
[when nothing activates](contributing.md#when-nothing-activates) in the contributing guide before
assuming a feature is broken.

**Line numbers below name the demo [fixture](glossary.md#fixture) as committed and will drift as the
demo changes.** Where a
gesture edits the file, it says so, and every edit here is meant to be undone afterwards — the demo is
a committed fixture, not a scratchpad. `git status --short demo/` should read empty when you are done.

**Links in parentheses** point at the specific checks in the
[manual test suite](manual-test-suite.md), which owns the exact wording of each gesture and its
expected outcome, plus whether it has actually been pressed against a running sandbox and when. This
guide explains *why* a capability exists and roughly what you will see; the manual suite is the
authority on the precise result.

---

## What can this field actually match?

**What it does.** Beside every [field](glossary.md#field) declaration, an inline hint states what a
search against that field can actually do — whole-value or tokenised, case-sensitive or not,
prefix-capable or not — and, beside it, the storage shape that decides whether a matched document can
be returned at all: [indexed](glossary.md#indexed), [stored](glossary.md#stored), doc-valued, single-
or [multi-valued](glossary.md#multivalued). This is read from the field's own
[analyzer chain](glossary.md#analyzer-chain) — the [tokenizer](glossary.md#tokenizer) and
[filters](glossary.md#filter) Solr would run at index time — rather than asserted from the type's
class name.
A `TextField` using `KeywordTokenizerFactory` reads as **whole-value** despite the class name, because
that tokenizer emits the entire input as one term, so the field matches exactly as a `StrField` does.

**Order decides it, not just which [factories](glossary.md#factory) are present.** A
`WordDelimiterFilterFactory` placed
*after* that keyword tokenizer splits the single term again, and the field reads as tokenised despite
its tokenizer — with the hint's evidence naming the filter that made it true rather than the tokenizer
it overrode.

Two silences matter as much as the hints themselves. A field whose analyzer chain names a factory the
plugin does not recognise still shows its storage shape, with no match claim — a wrong hint is worse
than none. A field whose `type` names nothing the schema declares gets no hint at all, because nothing
here is knowable.

**Try it.** Open `managed-schema.xml` and look at the field block starting around line 66 — no
gesture needed, the hints render themselves. `id`, `sku` and `category` read whole-value and
case-sensitive; `description` and `text` (type `text_general`) read tokenised and case-insensitive;
`name_prefix` additionally reads prefix-capable. `notes` (type `custom_text`, whose analyzer names an
unrecognised factory) keeps only the storage-shape phrases; `legacy` (type `discontinued`, undeclared
in the schema) shows nothing at all. (Verified by
[the match-capability hint checks](manual-test-suite.md#3-match-capability-inlay-hints-hint).)

![Nine field declarations in managed-schema.xml, each followed by an inline hint naming what it
matches and how it is stored](images/01-hints-match-capability.png)

---

## Getting documentation without leaving the file

Quick documentation (**F1**, or hover) answers a different question depending on exactly where the
caret sits, and every one of those positions now answers something — a gap the plugin closed later
than the rest, because the obvious first version only explained a *value* under the caret.

### On a field

**What it does.** Hovering (or F1 inside) a field's `name` shows every one of its resolved
properties, **and where each came from** — the field itself, its [field type](glossary.md#field-type),
or Solr's own default at
the schema's declared version. That third column is what no external reference can supply, because it
is about *this* schema. A **Meaning** column states the consequence of the resolved value in plain
terms — *"Whether the original value can be returned in results"* beside `stored` — rather than only
restating the property name.

**Try it.** Caret inside `name="category"` at `managed-schema.xml:70`, press F1. (Verified by
[the quick-documentation checks on a field](manual-test-suite.md#5-quick-documentation-doc).)

![Quick documentation for the field category: a properties table giving each property's value, where
that value came from, what it accepts, and what it means](images/02-quick-doc-field.png)

### On a class value

**What it does.** Hovering any `class="solr.…"` value — a field type, a tokenizer, a filter, a
[char filter](glossary.md#char-filter), or a `solrconfig.xml` plugin class — answers what the class
is: short name and kind, the
fully-qualified name, a one-sentence summary read from Solr's own Javadoc at build time, the
attributes it accepts, and a Reference Guide link at the version this configset declares. None of it
is fetched at edit time or copied out of the guide it links to — see [the FAQ](faq.md) for why.

**Try it.** Caret inside `class="solr.StandardTokenizerFactory"` at `managed-schema.xml:33`, F1.
(Verified by [the quick-documentation check on a class value](manual-test-suite.md#5-quick-documentation-doc).)

![Quick documentation for solr.StandardTokenizerFactory: short name and kind, fully-qualified class
name, one-sentence summary, accepted attributes, and a Reference Guide link](images/03-quick-doc-class.png)

### On the schema's own elements

**What it does.** Hovering the *tag* itself — `<schema>`, `<field>`, `<fieldType>`,
[`<dynamicField>`](glossary.md#dynamic-field), [`<copyField>`](glossary.md#copyfield),
[`<uniqueKey>`](glossary.md#uniquekey) — explains what the element is in Solr's terms, plus a
configset-specific sentence where one is knowable: which two fields a `<copyField>` joins, which
field is the unique key and of what type, how many fields use a given type.

**Try it.** Hover `<copyField>` or `<uniqueKey>` anywhere in `managed-schema.xml`. (Verified by
[the quick-documentation check on the schema's own elements](manual-test-suite.md#5-quick-documentation-doc).)

### On an attribute's own name

**What it does.** Hovering the attribute *name* — `name` or `type` on a `<field>`; `name` or `class`
on a `<fieldType>`; `source` or `dest` on a `<copyField>` — explains that attribute in general terms.
This is distinct from hovering the *value*, which resolves what this configset actually did with it;
the two `copyField` ends read differently from each other, since `source` and `dest` mean different
things.

**Try it.** Hover the word `source` or `dest` inside any `<copyField>` tag. (Verified by
[the quick-documentation check on an attribute's own name](manual-test-suite.md#5-quick-documentation-doc).)

### On a factory's individual attribute

**What it does.** Hovering an attribute written on a `<filter>`, `<tokenizer>` or `<charFilter>` — for
example `minGramSize` on an `EdgeNGramFilterFactory` — names the owning class, the value type, and
whether it is required; a hand-written **Does** row states what the attribute is for, for the couple
of dozen attributes a reader is likely to hover. An attribute the catalog does not carry, or a class
the catalog does not know, answers with nothing rather than a guess.

**Try it.** Hover `minGramSize` on the `EdgeNGramFilterFactory` filter at `managed-schema.xml:48`.
(Verified by [the quick-documentation check on a factory's attribute](manual-test-suite.md#5-quick-documentation-doc).)
*No image yet — see [the screenshot catalog](screenshots.md#not-yet-capturable).*

### On a factory's complete configuration

**What it does.** Hovering the *tag* of a factory (the word `filter`, not its `class` value) shows
every attribute that class accepts, not only the ones written: written attributes are bold and
labelled *on this filter*; unwritten ones show the catalog's recorded literal default, labelled *Solr
default*; an attribute the catalog cannot supply a default for —
[`luceneMatchVersion`](glossary.md#lucenematchversion) is the recurring example — shows an em dash
labelled *no default recorded*, which is the feature rather than
a gap. A custom `class` the catalog does not know offers nothing on the tag at all.

**Try it.** Hover the word `filter` (not the `class=` value) on the `EdgeNGramFilterFactory` filter at
`managed-schema.xml:48`. (Verified by
[the quick-documentation check on a factory's complete configuration](manual-test-suite.md#5-quick-documentation-doc).)
*No image yet.*

### On the schema's own [version](glossary.md#schema-version)

**What it does.** Hovering `version` on the `<schema>` root explains what the attribute decides in
general, then what *this configset's* declared value decides here — the demo's `version="1.6"` puts
[`docValues`](glossary.md#docvalues) off and [`uninvertible`](glossary.md#uninvertible) on by
default, both of which flip at 1.7. The `uninvertible` default is the one worth pausing on: with it
true, sorting or faceting on a field that has no doc values still *works*, but only because Solr
builds an in-memory field cache by un-inverting the index at query time — correct, silent, and
expensive on a large index, rather than the explicit failure the same query would get once
`uninvertible` defaults to false at 1.7. Solr changed several field defaults at that boundary without
breaking already-deployed schemas, and this is the popup that makes the boundary visible rather than
silent.

**Try it.** Hover `version` on `<schema version="1.6" …>` at `managed-schema.xml:27`. **F1 does not
raise Quick Documentation on the default macOS keymap** — it opens the platform's own Help page
instead, which has nothing to do with this plugin; Ctrl+J is the reliable gesture. (Verified by
[the quick-documentation check on the schema's version attribute](manual-test-suite.md#5-quick-documentation-doc).)

![Quick documentation on the schema's version attribute: the general rule, then what this
configset's declared 1.6 decides — docValues off, uninvertible on, autoGeneratePhraseQueries
off](images/16-hover-schema-version.png)

### On a `solrconfig.xml` parameter or parser name

**What it does.** `solrconfig.xml` gets its own two documentation positions the schema provider
declines. Hovering a [request parameter's](glossary.md#request-parameter) name — `qf`, `mm`, `defType`
and 337 more — explains what
that parameter is for and names the Java constant that declares it, read from Solr's own Javadoc.
Hovering a `defType` value such as `edismax` names the query parser it selects. A parameter or name
Solr itself does not declare — your own custom component's — answers with nothing, which is the
honest response: a generator built from Solr's own source can never see a plugin outside it.

**Try it.** F1 on `qf` inside `solrconfig.xml`'s `<str name="qf">` (line 28), or on `class=` inside a
`<requestHandler>` such as the `/select` handler at line 24. (Verified by
[the documentation check on a request parameter's name](manual-test-suite.md#9-completion--field-names-inside-solrconfigxml-parameters-prm)
and [the documentation check on a catalog-backed class](manual-test-suite.md#8-completion--catalog-backed-cat).)

---

## Discovering what you're allowed to write

Completion answers two different questions, and the plugin only answers the second one as of a
relatively late step: *what value goes here* has always worked to some degree, but *what may I write
at all* — the vocabulary itself — matters more to a reader who has not yet learned it.

### The schema's own vocabulary

**What it does.** Typing `<` inside `<schema>` (or any schema element) offers the elements legal
there and nothing else. Typing a space inside an opening tag offers the attributes that element
accepts, each with a one-line summary and the values it takes — and **omits** whatever the tag
already carries, since an attribute cannot legally appear twice. Where a boolean's default is
knowable, the default value is marked; where it depends on the field's type, neither is marked, rather
than asserting one.

**Try it.** Caret after `stored="true"` on `managed-schema.xml:70`, **before the closing `/`**, type a
space. `indexed` and `stored` are absent from the popup because line 70 already declares them.
Undo the space afterwards. (Verified by
[the completion checks over the schema's own vocabulary](manual-test-suite.md#7-completion--the-schemas-own-vocabulary-comp).)

![Attribute completion inside a field tag, listing docValues, sortMissingLast, default, large,
multiValued, omitNorms and more, each with a one-line summary and the values it
accepts](images/05-completion-field-properties.png)

### Catalog-backed classes and factory attributes

**What it does.** `class=` on a `<fieldType>` offers `solr.*` field type classes; on a `<tokenizer>`
or `<filter>`, the analysis factories — each set following the Solr line this configset declares.
Once a factory class is written, attribute completion inside that tag offers *that factory's own*
attributes, read from its constructor bytecode when the plugin itself was built rather than hand
maintained.

**Try it.** Caret before the closing `/` on the `EdgeNGramFilterFactory` filter at
`managed-schema.xml:48`, type a space: `luceneMatchVersion` and `preserveOriginal` appear, each
labelled with the factory they belong to (`minGramSize` and `maxGramSize` are absent because the tag
already declares them). Undo the space afterwards. (Verified by
[the catalog-backed completion checks](manual-test-suite.md#8-completion--catalog-backed-cat).)

![Attribute completion inside a filter tag declaring solr.EdgeNGramFilterFactory, offering
luceneMatchVersion and preserveOriginal, each labelled with the factory it comes
from](images/06-completion-factory-attributes.png)

### `solrconfig.xml`'s own structure

**What it does.** Before this shipped, `solrconfig.xml` ran through the platform's schema-less XML
mode, which guesses completion from whatever same-named sibling tag happens to be nearby — a bad
guess in a file made almost entirely of same-named tags. Element and attribute completion here now
come from the same generated vocabulary the documentation and [inspections](glossary.md#inspection)
read, and nesting is
respected: what completes inside `<query>` is not what completes inside `<config>`, and an element
Solr no longer accepts (`nrtMode`, `mainIndex`, and three more) is never offered, anywhere.

**Try it.** Put the caret on a blank line directly inside `<config>` and type `<`: the offer is Solr's
own top-level vocabulary, not a copy of the handful of tags already written above the caret. Undo the
typed character afterwards. (Verified by
[the checks on solrconfig.xml's own structure completion](manual-test-suite.md#10-completion--solrconfigxmls-own-structure-str).)

![Element completion inside the config element in solrconfig.xml offering Solr's own top-level
vocabulary — query, requestHandler, directoryFactory, luceneMatchVersion — not an echo of sibling
tags](images/10-completion-solrconfig-structure.png)

### Field names inside `solrconfig.xml` parameters

**What it does.** Sixteen [request-handler](glossary.md#request-handler) parameters are known to hold
field names — `qf`, `pf`,
`fl`, `sort` among them — and completion inside one of them offers the schema's own fields, with a
dynamic pattern such as `*_t` shown italicised to mark it as a pattern rather than a literal field.
Scoped narrowly on purpose: `rows` and `defType` hold no field names, a caret immediately after a
boost (`name^`) offers nothing, because completing there would produce `name^name`, and the second
token of a `sort` clause is a direction rather than a field. The `name` attribute of a `<str>` inside
`<lst name="defaults">` offers Solr's own parameter names instead — the position the vocabulary is
learnable from in the first place, since offering fields inside `qf` presumes the reader already knew
to write `qf`.

**Try it.** Caret inside `<str name="qf">` after the existing text (`solrconfig.xml:28`), **type a
space**, then invoke completion: the schema's fields are offered, `*_t` italicised. The space matters —
completion did not fire with the caret directly against existing text and no trailing whitespace, the
same rule the schema-vocabulary completion above documents. (Verified by
[the checks on field-name completion inside solrconfig.xml parameters](manual-test-suite.md#9-completion--field-names-inside-solrconfigxml-parameters-prm).)

![Field-name completion inside a solrconfig.xml qf parameter, offering the schema's fields with
their types, including the *_t dynamic pattern](images/13-completion-parameter-fields.png)

**Try the parameter-name half too** — add a new `<str name="` line inside `<lst name="defaults">`
and invoke completion there instead of inside a value: Solr's own parameter names are offered, each
labelled with the class that declares it and, for a family of related names, a short description
telling them apart. A parameter already set earlier in the same list is withheld — typing `qf` here
when line 28 already declares one offers only `mlt.qf` and `hl.queryFieldPattern`, not plain `qf`,
which is this behaving correctly rather than a sparse or broken popup. (Verified by
[the checks on parameter-name completion](manual-test-suite.md#9-completion--field-names-inside-solrconfigxml-parameters-prm).)

![solrconfig.xml parameter-name completion offering sort, expand.sort, facet.sort, group.sort and
terms.sort, each labelled with its declaring Params class](images/15-completion-parameter-names.png)

---

## Navigating and finding usages

**What it does.** Every string that names something elsewhere in the configset is a real
[reference](glossary.md#reference): Ctrl/Cmd-click (or Cmd-hover for the tooltip) jumps to the
declaration, [Find Usages](glossary.md#find-usages) (⌥F7) lists every
place a declaration is used — including across the file boundary, schema to `solrconfig.xml` — and
the result list is labelled in Solr's own vocabulary (*Field type*, *Field declaring this type*), not
the platform's generic fallback. Four reference kinds resolve: a field's `type`, a `copyField`'s
`source` and `dest`, a `solrconfig.xml` handler parameter naming a field, and a filter's resource
attribute (`words=`, `synonyms=`, `protected=`, a char filter's `mapping=`) opening the file it names.
A dynamic field such as `<dynamicField name="*_t">` reports every name its pattern actually supplies —
`body_t` in a `solrconfig.xml` parameter, for instance — not only literal spellings of the pattern
itself, which the platform's own word index cannot find on its own.

**Try it.**

- Cmd-click a field's `type="text_general"` at `managed-schema.xml:68` to jump to the
  `<fieldType name="text_general">` declaration. (Verified by
  [the check that a field's type navigates to its declaration](manual-test-suite.md#4-navigation-and-find-usages-nav).)
- Find Usages (⌥F7) invoked from the `<fieldType name="text_general">` declaration itself
  (`managed-schema.xml:31`) lists four usages — `name`, `description`, `text`, and the `*_t` dynamic
  field. (Verified by
  [the Find Usages checks on a field type, including how the results are labelled](manual-test-suite.md#4-navigation-and-find-usages-nav).)
- Cmd-hover `name` inside `solrconfig.xml:28`'s `<str name="qf">name^3 description category</str>`
  raises a navigation tooltip landing in the schema — each name in the string navigates on its own.
  (Verified by
  [the check that a solrconfig.xml parameter navigates into the schema](manual-test-suite.md#4-navigation-and-find-usages-nav).)
- Find Usages on `<dynamicField name="*_t">` (`managed-schema.xml:84`) reports the `pf` parameter
  naming `body_t` in `solrconfig.xml`, highlighted at `body_t` alone. (Verified by
  [the check that Find Usages on a dynamic field reaches a name its pattern only supplies](manual-test-suite.md#4-navigation-and-find-usages-nav).)
- Cmd-click `words="stopwords.txt"` at `managed-schema.xml:34` opens the resource file, including
  through `lang/`. (Verified by
  [the check that a filter's resource path navigates to the file](manual-test-suite.md#4-navigation-and-find-usages-nav).)

![Find Usages on the text_general fieldType declaration, showing four fields grouped under 'Field
declaring this type'](images/07-find-usages-field-type.png)

![Navigation tooltip from a solrconfig.xml qf parameter into the schema field it names](images/08-nav-solrconfig-field-reference.png)

**What does not navigate, and why that is correct.** `class="solr.SearchHandler"` in the demo's
`/select` handler resolves nowhere, and draws no warning for failing to — the demo depends only on
`solr-solrj`, and the class lives in `solr-core`, which is not on its classpath. A configset naming a
class outside the current project is ordinary, not wrong; documentation still answers from the
generated catalog regardless, because that needs no class on the classpath at all. (Verified by
[the checks on navigating to a class outside the project](manual-test-suite.md#4-navigation-and-find-usages-nav).)

---

## Renaming safely across a configset

**What it does.** Shift+F6 on a field, dynamic field or field type declaration
[renames](glossary.md#rename-refactoring) it everywhere a reference resolves to it — including across
the file boundary into `solrconfig.xml` — through the
same reference graph Find Usages reads. The rename dialog itself is labelled correctly (*Rename field
'category' and its usages to:*, not the platform's fallback class name), which matters because a
correct rename under a wrong-looking dialog reads as broken to everyone but its author.

**What stays deliberately partial.** Renaming a dynamic field's pattern (`<dynamicField
name="*_t">` to `*_txt`) updates the declaration and any reference that spells the pattern literally,
but leaves alone a reference the pattern only *supplies* — `body_t` in a `solrconfig.xml` parameter —
because rewriting it to `*_txt` would put a glob where a field name belongs. The unknown-field
inspection then underlines that now-orphaned name immediately, which is what makes the partial rename
defensible rather than silently wrong.

**Try it.** Caret on `category` in `<field name="category">` at `managed-schema.xml:70`, press
Shift+F6, apply directly (no Preview step needed) to rename to `product_category`: the declaration
updates and so does the `qf` line in `solrconfig.xml:28`, with no separate action. A single Cmd+Z
undoes both files as one refactoring — confirm with `git status --short demo/` afterwards. (Verified by
[the rename checks that the dialog is labelled correctly and the cross-file update follows](manual-test-suite.md#4a-rename-ren).)

![solrconfig.xml's qf parameter before a rename, reading name^3 description
category](images/14-rename-cross-file-before.png)

![The same qf parameter immediately after renaming category to product_category from the schema
declaration — updated with no separate edit](images/14-rename-cross-file-after.png)

---

## Catching mistakes before Solr does

**What it does.** Eleven inspections watch for the ways a configset fails silently — at
[core](glossary.md#core) reload, at query time, or not at all until a reader notices a query "just
doesn't work." Ten of the eleven are
held to a zero-false-positive bar: the shipped `_default` and `sample_techproducts_configs` configsets
Solr itself ships produce no findings from them, on either supported line, and a custom plugin class
with its own parameters produces none either. **The eleventh, the unused-field-type check, is held out
of that gate by name** — Solr's own shipped configsets declare dozens of language and spatial field
types for fields nobody has written yet, and the check correctly reports every one of them as unused.
That is a true fact about the file rather than a defect in it, which is why it is the one rule a
*zero findings* gate cannot hold, and why it is drawn dimmed rather than underlined below.

What they catch, briefly:

- **A `copyField` naming a field nothing declares** — flagged, with Alt-Enter offering the declared
  fields closest in spelling, dynamic patterns included as candidates.
- **A field naming an undeclared field type**, and the reverse — **a declared field type nothing
  uses** — the one finding here that is not a defect, so it is drawn dimmed rather than underlined,
  with no [quick fix](glossary.md#quick-fix): whether it is dead weight or provision for a field not
  yet written is a judgement the editor cannot make.
- **A `solrconfig.xml` handler parameter naming a field the schema does not declare.**
- **A relevance or faceting parameter naming a field that cannot serve it** — `qf` on a non-indexed
  field, `facet.field` or `sort` on a field with neither `indexed` nor `docValues`. The same field can
  be searchable and unfacetable at once, and the two checks disagree about it on purpose.
- **An analyzer chain ordering that silently defeats itself.** Every class named is legal, every
  attribute is legal, and Solr starts without complaint — this is the one finding in this list a
  reader cannot see by looking at the file alone, because the problem is not any single tag but how
  two tags relate to each other. A case-folding filter placed after a filter that already flattened
  case away runs on input with nothing left to fold, so it does nothing. A filter such as
  `SynonymGraphFilterFactory` can emit more than one token at the same position — `laptop` and
  `notebook` occupying one slot rather than a flat sequence, what Lucene calls a *token graph* — and
  `FlattenGraphFilterFactory` exists to collapse that graph back into a flat sequence for whatever
  runs after it; placed *before* the filter that produces the graph instead of after it, it flattens
  nothing, and the graph reaches a downstream filter that cannot represent alternatives at all.
- **A configuration element Solr no longer accepts** — reported in Solr's own retirement sentence,
  naming its replacement where Solr names one.
- **A parameter name that is almost one Solr reads** — an edit-distance check that knows the
  difference between a typo and a genuinely different but similar parameter (`pf2` beside `pf3`),
  because it checks knownness before distance.
- **An unknown attribute, or a value outside a closed set** — `indexed="yes"` instead of `"true"`.

**Try it.** `managed-schema.xml`, untouched, shows exactly two warnings — a planted dangling
`manufacturer` copyField and a planted undeclared field type — and nothing else; `solrconfig.xml`
shows none. Both are deliberate fixtures and should never be "fixed." (Verified by
[the zero-false-positive baseline checks](manual-test-suite.md#2-zero-false-positive-baseline-base).)

![A copyField whose source names manufacturer, highlighted in the editor, with the Alt-Enter menu
open above it offering to change the name to *_t, category, description, legacy, name or
notes](images/04-inspection-copyfield-quickfix.png)

---

## Seeing what's already redundant

**What it does.** An attribute whose written value equals what Solr would have supplied anyway
renders dimmed — the same idiom an IDE uses for any other redundant code, at information severity,
never reaching the Problems view, because a restated default is correct rather than wrong. An
Alt-Enter [intention](glossary.md#intention) on a dimmed attribute removes it, leaving a schema whose
resolved properties are
identical. The comparison covers both field properties (`indexed`, `stored`, and the rest, resolved
through three tiers — field, field type, Solr's own default) and analysis-factory attributes, judged
against the literal default the catalog read out of that factory's own bytecode. Wherever the default
cannot be determined with confidence, nothing dims — a custom field type class the catalog does not
know, for instance.

**Try it.** Open `managed-schema.xml` untouched — no edit needed. `indexed="true"` and
`stored="true"` render dimmed across the field block; `stored="false"` on `name_prefix` renders at
full strength. (Verified by
[the restated-default dimming checks](manual-test-suite.md#11-an-attribute-that-restates-its-default-dim).)

![managed-schema.xml field block with indexed=true and stored=true rendered dimmed as restated
defaults, while stored=false on name_prefix stays normal](images/11-dimmed-restated-default.png)

---

## Fixing a missing capability automatically

**What it does.** Two Alt-Enter intentions write a new field into the schema, generated from a field
that lacks a capability it might want: a `_exact` companion (a `StrField`-backed whole-value field,
useful for sorting and faceting a tokenised field's content) and a `_prefix` companion (an
EdgeNGram-backed field supporting efficient prefix queries). Both generate the companion `<field>`,
its `<fieldType>` where one does not already exist, and the `<copyField>` rule joining the two — and
both stop offering themselves once the companion exists, or if the field is already whole-value and
so never needed the `_exact` half. This is the plugin's first capability that edits a configset rather
than only explaining or flagging one, and it edits directly with no confirmation dialog, the same
policy [every write in this plugin follows](code-organization.md#rules-that-hold-across-every-package).

**Try it.** Caret inside `description` (`managed-schema.xml:71`, a `text_general` field), press
Alt-Enter: **Add exact-match companion field (string)** and **Add prefix-capable companion field
(text_prefix)** appear above the platform's own generic items. Applying either produces a schema that
still parses. (Verified by
[the companion-field intention checks](manual-test-suite.md#12-intentions--companion-fields-int).)

![Alt-Enter menu on a tokenized text field offering 'Add exact-match companion field' and 'Add
prefix-capable companion field' intentions, with the exact-match explanation shown
alongside](images/12-intention-companion-fields.png)

---

## Pointing the plugin at a Solr server

*Everything below needs a connection. Nothing above it does — configset editing works with no server
configured at all, and never waits on one.*

**Settings → Tools → Solr Connections**, or the `+` in the Solr tool window. A connection is a name,
a URL and optionally a username; the password goes to the IDE's password safe and never to a project
file, so a workspace that leaks is an inventory of hostnames rather than of credentials.

Two things worth knowing before you wonder whether something is broken:

- **The password field opens empty even when a password is stored**, and says so underneath. Leaving
  it blank keeps what is stored. Pre-filling it would put your credential into a live dialog for as
  long as it stays open, which is one more place than it needs to be.
- **The URL only has to be an `http` or `https` address of some host.** No `/solr` suffix is
  required, because a reverse proxy or a servlet context path can put Solr anywhere and rejecting
  those would be the plugin having an opinion about your deployment.

## Browsing what a server holds

The **Solr** tool window, docked right, on the **Collections** tab. It shows collections, shards and
replicas for a SolrCloud server, or cores for a standalone one.

**Which vocabulary you see comes from the mode the server reports**, never from a guess. That matters
because a standalone Solr answers every `/admin/collections` request with HTTP 400 — a plugin that
assumed the cloud vocabulary would report a hard failure against a server that is working perfectly.

Every collection and core carries a **Fields** row, and it starts empty. Expanding it asks the
server; nothing else does. That is deliberate — reading an index costs one request per collection, so
a server holding thirty of them would turn opening this window into thirty requests.

**What that row shows is not the schema.** It is what the index actually holds, which includes every
field a dynamic pattern created at index time:

```
Fields   9 fields · 5 from dynamic patterns · 3 documents
  id           string    3 docs
  author_s     string    ← *_s   3 docs
  price_f      pfloat    ← *_f
```

`author_s` appears in no configset anywhere — the configset declares `*_s`, and the index holds what
matched it. `price_f` shows no document count because Solr reports none for a point field, having no
inverted index to count from; that is not the same as holding nothing.

**Nothing refreshes on a timer.** Server data moves when you ask — Refresh, or changing the selected
connection — and at no other time.

## Running a query

Solr queries are HTTP requests, and the IDE already ships a tool for authoring and running those from
files in a repository. So there is no query console of this plugin's own: what it adds is Solr's
knowledge to the HTTP Client's.

In any `.http` file, **Add Request** offers a **Solr** group — query a collection, query with a field
list and sort, explain why documents scored, read the schema, list what the index holds, and a POST
using Solr's JSON Request API.

**Every template addresses `{{solrUrl}}` rather than a host**, which is the whole reason saved
queries live in `.http` files. Define the variables in `http-client.env.json` beside the requests and
the file works for everyone who clones the repository; a committed file naming `localhost:8983` works
only for whoever wrote it.

Run one and the response gains a readable summary above its raw JSON:

```
2 documents matched, in 32 ms.
Solr's internal fields are not shown: _version_, _root_

id  title         author_s       price_f  tags_ss
--  ------------  -------------  -------  --------------
1   Dune          Frank Herbert  9.99     scifi, classic
```

**Matches and returned rows are stated separately** because they routinely differ — conflating them
is how someone concludes their query found three documents when it found nine thousand and showed
three. With `debugQuery=true` the scoring explanation appears below, with Solr's own indentation
intact: the nesting is the information, and flattening it would leave a list of numbers with nothing
saying which produced which.

Inside a JSON request body, **field names complete** in `fields`, `sort` and a facet's `field`, from
the configsets in this project. They come from the repository rather than the server so that
completion never waits on a network — the trade is that a field only the deployed server has will not
be offered.

## Closing a difference between the repository and a server

*Needs a connection and a collection. The **Solr** tool window's **Drift** tab compares a configset
on disk against a collection on the selected server, and shows what the two do not agree about.*

Three kinds of difference, and the plugin treats them very differently:

| The row says | It means | Offered? |
|---|---|---|
| **Not deployed** | The configset declares it; the server does not have it | **Yes** — an addition |
| **Only on server** | Added through the Schema API and never committed | No |
| **Differs** | Both have it, defined differently | **No, deliberately** |

**Select any row to see the exact Schema API request that would close it.** For an addition, sending
it is the next button along. For the other two the request is still shown, under the reason it is not
offered — because "why can this not be applied" deserves a better answer than a greyed-out button,
and because reading what a tool would do before deciding is the point.

**Why a changed field is never applied for you.** Solr accepts a `replace-field` that changes a
field's type, and reports success. Every document already indexed keeps the encoding it was written
with. Against Solr 10.0.0, changing a `string` field holding `"abc"` to `pint` gave

- a query for a value that *is* there matching nothing,
- a query for the old text answering `400 Invalid Number`, and
- **any** query merely naming the field in `fl` failing with `HTTP 500` — for every document,
  including ones that never had it.

Nothing in Solr's answer to the write hints at any of that. Only a reindex makes the schema true
again, which this plugin cannot do and will not pretend to. You are left able to run the request
yourself, against a collection you are prepared to reindex — that is your call, not the plugin's to
prevent.

**Spelling does not count as a difference.** A single analyzer factory is nameable three ways —
`lowercase`, `solr.LowerCaseFilterFactory`, and its fully qualified Lucene class — and Solr's own
configsets and a server reading them back do not always choose the same one, or even the same case.
The comparison resolves all of them to the factory they mean before comparing, so a difference shown
here is a difference in what the two sources *say*, not in how they spell it.

**Applying re-reads rather than assuming.** After the request is sent, the collection's schema is
read back and compared again, and what you see is the result of that read. A `2xx` proves Solr
accepted the request, not that the server now agrees — the two really do come apart, which is why
this is worth saying twice.

**Try it.** Add a field to `demo/books/conf/managed-schema.xml`, open **Drift**, choose that
configset, type your collection, press **Compare**: the new field reads *Not deployed*. Select it to
see its `add-field` payload, then press **Apply Additive Changes**. (Verified by
[the drift and apply checks](manual-test-suite.md#13-connections-and-the-collections-tool-window-srv).)

---

## What is not here yet

**Indexing test documents** is the one server-side step not built. Nor is anything in Java or Kotlin
code — field-name checks against [SolrJ](glossary.md#solrj) calls, query-syntax injection.

[The specification](../specs/0002-solr-intellij-plugin.md) describes the intent for both;
[the implementation plan](../specs/plans/0002-solr-intellij-plugin-plan.md) is the only place that
says what is actually built, today, and it is worth reading directly rather than trusting a summary —
including this one.
