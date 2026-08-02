# Typed attribute validation: catching `positionIncrementGap="foo"` and `maxGramSiz="15"`

## Problem

The plugin knows what every attribute in a schema means and never once checks what is written in it.

`positionIncrementGap="foo"` is accepted in silence. So is `indexed="yes"`, `minGramSize="2.5"` and
`maxGramSiz="15"`. Solr will reject or ignore all four, and the reader finds out from a stack trace at
core-load time — which is exactly the loop this plugin exists to shorten. The information needed to
catch every one of them is already in the process; nothing consults it.

**The property table records types as prose.** `SolrFieldProperty.validValues` holds sentences written
for a human reading documentation: `"true or false"`, `"an integer"`, `"a format name registered with
the codec"`. There is no machine-readable type. The one place behaviour already depends on a type,
`offerableValues`, gets it by comparing that prose against a constant:

```kotlin
val offerableValues: List<String>
    get() = if (validValues == BOOLEAN_VALUES) BOOLEAN_OFFERS else closedValues
```

That string comparison works, but it is a type check wearing a disguise, and validation built beside
it would add a second one.

**The catalog generator extracts types and then throws them away.** The constructor-bytecode pass
added in `feat: recover each factory's attributes from its constructor bytecode` matches on the reader
method a factory calls — `get`, `getInt`, `getBoolean`, `getFloat` — and stores only the attribute
*name*. The method that was matched is the type, available for free at the point it is already being
inspected, and discarded there.

**Field type classes are missing from the extraction entirely.** The pass is guarded by
`if (reader.className.endsWith("Factory"))` at `build.gradle.kts:247`. `StrField`, `CurrencyFieldType`
and `DenseVectorField` do not end in "Factory", so all 33 `fieldType` rows in `solr-10.tsv` carry an
empty attribute list while 165 of 165 analysis classes are populated. The plugin therefore has no
vocabulary at all for the attributes a `<fieldType>` actually accepts.

## Goals

- Flag an attribute value that cannot satisfy a type the plugin positively knows.
- Flag an attribute name that is not recognized where the vocabulary is genuinely closed.
- Offer the valid alternatives as a quick-fix wherever the valid set is enumerable.
- Extend the catalog to field type classes, which the `endsWith("Factory")` guard excluded entirely.
- Produce zero findings on every configset Solr ships. **This is the constraint, not an aspiration** —
  it is what decided, against this document's first draft, that a `<fieldType>` cannot be checked for
  unknown attributes.

## Non-goals

- **Semantic validation.** `positionIncrementGap="-1"` parses as an integer and is left alone. The
  claim this makes is "that is not an integer", not "that is not a sensible integer".
- **Resolving substitutions.** A value containing `${` is left alone entirely; its real value is
  supplied by Solr's resource loader and may come from outside the repository.
- **Validating custom classes.** A `class` the catalog does not know produces no findings on any of
  its attributes.
- **`solrconfig.xml` structure.** That is step 25 and stays there. This work covers the schema.

## Design

### 1. A value type in the model

```kotlin
enum class SolrValueType { BOOLEAN, INTEGER, FLOAT, ENUM, FREE }
```

`SolrFieldProperty` gains `valueType`. `validValues` stays exactly as it is — it is the sentence
quick documentation shows a reader, and it should keep reading like prose. What changes is that
`offerableValues` reads `valueType` instead of comparing `validValues` against `BOOLEAN_VALUES`,
which retires the string comparison rather than growing a second one beside it.

`SolrClassEntry.attributes` changes from `List<String>` to `List<SolrClassAttribute>`, a name and a
type.

`FREE` is not "unknown, so guess". It is a positive statement that any value is legal, and it is what
every attribute read through plain `get` or `args.remove` gets. Nothing with a `FREE` type is ever
value-checked.

### 2. The generator infers the type from the reader's descriptor

The shipped pass recognizes an argument read by matching the called method against a hardcoded set
of seventeen names — `get`, `getInt`, `getBoolean`, and so on. **That set goes away.** It is
replaced by a test on
the method's JVM descriptor, for two reasons: it is self-maintaining across Solr releases, and its
failure mode is the safe direction.

Every reader that takes *an attribute name* has the same shape — the argument map, then the name,
then an optional default. **Not every method on the class does**, and an earlier revision of this
section wrongly said otherwise. `getLines`, `getWordSet` and `getSnowballWordSet` take a
`ResourceLoader` instead, and `getClassArg` takes nothing. All four are excluded by the descriptor
test, and three of them were in the seventeen-name list.

**Their exclusion is a fix, not a loss.** They consume a filename that a real reader already
resolved — `getWordSet(loader, get(args, "words"), ignoreCase)` — so matching them harvested
whatever literal happened to be pending. The proof is a name-level diff of both generated catalogs
against the previous output, which must show no attribute lost.

**Rule A — typed reads.** A call whose owner descends from `AbstractAnalysisFactory` and whose
descriptor begins `(Ljava/util/Map;Ljava/lang/String;` is an argument read. The attribute name is the
first pending literal, exactly as now. **The value type comes from the JVM return type**, which is
more reliable than any name-to-type table because it is the compiler's own answer:

| Return descriptor | Type |
|---|---|
| `I` | `INTEGER` |
| `Z` | `BOOLEAN` |
| `F`, `D` | `FLOAT` |
| everything else — `Ljava/lang/String;`, `C`, `Ljava/util/Set;`, `Ljava/util/regex/Pattern;` | `FREE` |

**Rule B — untyped reads.** `get`, `remove` and `containsKey` on `java/util/Map`. These are erased to
`(Ljava/lang/Object;)…`, so they carry no type and always yield `FREE`. This rule is what keeps
`args.remove("userDictionary")` working — the case the constructor-bytecode work found in
`JapaneseTokenizerFactory` — and it is also the whole mechanism by which field type classes are read
in section 3.

Rule B names three methods explicitly, which is a small hardcoded set. That is acceptable where the
seventeen-name set was not: `java.util.Map` is JDK API that has been stable for decades, whereas
Solr's factory API is precisely the thing that moves between releases.

**A reader Solr adds in a future release is picked up with no edit**, and gets the right type from its
return descriptor. Under section 7's flag-unknown rule a *missed* attribute becomes a false positive
on a correct file, so removing the way attributes go missing is worth more here than it would be in a
catalog used only for completion.

**Conflicting types resolve to `FREE`.** Attributes are inherited through `attributesOf`, which walks
the superclass chain, so one name can be collected from two classes that read it differently. The
plugin already drops its confidence flag when an analyzer chain contains a factory it does not
recognize rather than assuming the factory is harmless; this is the same rule applied to types. A
conflict is a signal the extraction did not fully understand the class, and a wrong type produces a
wrong underline on a correct file.

**This is a rewrite of a pass that was verified by named expectation, so it is re-verified the same
way.** Every expectation the constructor-bytecode work established —
`WordDelimiterGraphFilterFactory`'s attributes,
`JapaneseTokenizerFactory`'s `mode` and `userDictionary` — must still hold, and now with types
attached. A descriptor test that quietly matches fewer methods than the name set did would produce a
shorter plausible list, which is the exact failure this project has already been bitten by three
times.

**It found a fourth.** The name list did not contain `getCharacter`, and an unmatched reader neither
harvests its own attribute nor clears the pending literal. So in `ConcatenateGraphFilterFactory` the
`getCharacter` call left `tokenSeparator` pending, the following `getBoolean` harvested *that*
instead of its own name, and `preservePositionIncrements` vanished from the catalog entirely. Solr 9
lost `preserveSep` the same way. This is the strongest argument for the change: the failure is not
that a list needs maintaining, it is that one unlisted reader silently corrupts its neighbour, and
under section 7's rule a lost attribute becomes a warning on a correct file.

**The types are what Solr does, not what the names suggest.**
`WordDelimiterGraphFilterFactory` reads `generateWordParts` and its neighbours with `getInt` as
`0`/`1` flags, so they are `int` here and `generateWordParts="true"` really does fail in Solr. A
name-to-type table would never have discovered that; the return descriptor states it.

### 3. The generator covers field type classes

Replace the `endsWith("Factory")` name test with a hierarchy test. `superclasses` is already collected
in the same pass, so "does this class descend from `org.apache.solr.schema.FieldType`" is answerable
without a second read of the jar.

For field type classes only, three things differ from the factory case:

- **The method to visit is `init`, not `<init>`.** A factory reads its arguments in its constructor. A
  field type reads them in `init(IndexSchema, Map<String, String>)`, which Solr calls from `setArgs`.
- **Rule A never applies; Rule B is the whole mechanism.** A field type does not descend from
  `AbstractAnalysisFactory` and calls no typed reader. It calls `args.get("defaultCurrency")` or
  `args.remove("geo")` on the plain map. So **every field type attribute is `FREE`** — the plugin will
  know that `defaultCurrency` is a legal attribute of `CurrencyFieldType` and will never make a claim
  about its value. That is the correct outcome and not a limitation to fix later: the erased signature
  genuinely carries no type.
- **The `owner == "java/util/Map"` condition already in the pass is widened**, from `remove` alone to
  `get`, `remove` and `containsKey`. `build.gradle.kts:282` currently keeps `Map.get` out on purpose;
  admitting it is what this section costs.

That admission is the single riskiest change in this design. Any `Map.get` with a string literal
inside `init` will be collected, whether or not the map is the argument map. The mitigation is not a
cleverer heuristic; it is the named expectations in the testing strategy below.

**The consequence for section 5 is the opposite of what this section first claimed.** An earlier
revision said a `<fieldType>` would gain a name check and never a value check. Implementing it proved
that backwards, and the reason is worth keeping rather than quietly correcting.

A field type delegates to classes its own configuration names. `providerClass` selects the
`ExchangeRateProvider` that reads `currencyConfig`, and no walk — up the superclass chain, sideways
into nested classes, anywhere — reaches a collaborator chosen at runtime. Solr's own
`sample_techproducts_configs` writes `currencyConfig="currency.xml"`, and that configset is the
fixture the golden-file gate in [CI gates](../../../../specs/plans/0002-solr-intellij-plugin-plan.md)
is built on. Treating a field type's attribute list as complete would have underlined a file Solr
ships, and failed the gate this work has to pass.

So a `<fieldType>` gets its **values** checked — `positionIncrementGap` and `synonymQueryStyle` come
from the hand-maintained table, which is closed — and never a **completeness** claim.
`vectorDimension="abc"` is not caught, because every field type attribute is `FREE`;
`vectorDimenson="768"` is not caught either, because the list it would be missing from is open.

That is a smaller feature than this document originally promised, and it is the honest one.

### 4. Catalog format: one column, `name:type`

Column 4 changes from `minGramSize,maxGramSize` to `minGramSize:int,maxGramSize:int`. Untyped
attributes are written `:free` so every entry has the same shape and the reader needs no special case.

The token spellings are `bool`, `int`, `float` and `free`, written the way `SolrClassKind` already
carries a `token` for the catalog's first column. **`enum` never appears in a generated catalog** —
neither Rule A nor Rule B can produce it, because a closed member set is not recoverable from a return
descriptor. `SolrValueType.ENUM` exists for `SolrFieldProperties`, whose one enum property
(`synonymQueryStyle`) is hand-declared with its members. A reader looking for `enum` in the TSV and
not finding it is seeing correct behaviour.

A fifth column holding the types beside a fourth holding the names was considered and rejected. The
same name would live in two places, a generator bug could put them out of step, and nothing in the
build would notice — where a single token cannot disagree with itself. The catalog's stated reason for
being tab-separated is that a regenerated file diffs reviewably, and two parallel lists are worse to
review than one.

`SolrClassCatalog.read` splits each attribute on `:` instead of taking it whole. An attribute with no
`:` reads as `FREE`, so a stale catalog degrades to "no value checking" rather than to an exception.

### 5. Two inspections

Two problems, two messages, two description files, two things a user might want to disable
independently. Step 20 asserts a description file per registered inspection, so the split is also the
shape the CI gate expects.

**`SolrInvalidAttributeValue`** — the value cannot satisfy a type the plugin knows.
`indexed="yes"`, `positionIncrementGap="foo"`, `minGramSize="2.5"`.

**`SolrUnknownAttribute`** — the name is not recognized where the vocabulary is closed.
`indexd="true"`, `maxGramSiz="15"`.

**Two questions, kept apart.** *What does this attribute accept* is answerable far more often than
*is this the complete set of attributes*, and conflating them is how a checker starts underlining
correct files. They are separate functions on `SolrAttributeVocabulary`, and only the second one
decides whether an unknown name may be reported.

| Element | Values checked against | Complete set of names? |
|---|---|---|
| `<field>`, `<dynamicField>` | `SolrFieldProperties.FOR_FIELD` | **yes** — `FOR_FIELD` ∪ structural |
| `<fieldType>` | `FOR_FIELD_TYPE` | **no** — delegates to classes it names |
| `<tokenizer>`, `<filter>`, `<charFilter>` | catalog attributes of the named class | **yes**, when the catalog knows the class |
| a class the catalog does not know | nothing | **no** |
| anything else | nothing | **no** |

**"No" is not "none".** It means an attribute absent from every list the plugin holds is not thereby
wrong, so nothing may be reported. Three cases answer that way and each has its own reason: a field
type delegates, a class outside Solr is a custom plugin, and everything else is simply not modelled.
A known analysis class whose recovered attribute list is *empty* answers "no" as well — every
analysis class inherits at least `luceneMatchVersion` from the root of the hierarchy, so an empty
list means the extraction failed rather than that the class accepts nothing.

**`class` and `name` must be allowed structurally, because the catalog deliberately omits them.**
The generator strips both — `?.takeIf { it != "class" && it != "name" }` — since the base class
consumes them with `args.remove`, which reads identically to a real attribute. A check that trusted
the catalog alone would therefore flag `class="solr.EdgeNGramFilterFactory"`: the very attribute
naming the class it just looked up. `SolrClassCatalogTest` asserts the omission so this stays a
known contract rather than a surprise.

Both are `WARNING`, matching the three existing inspections and for the reason `plugin.xml` already
records: the plugin's model of a half-typed file is not authoritative enough to claim an error.

### 6. Quick-fixes

Where the valid set is enumerable, offer it, ranked by closest spelling and capped — the pattern
`SolrReplaceNameQuickFix` already established for the two reference inspections.

| Situation | Fix |
|---|---|
| Bad boolean | `true`, `false` |
| Bad enum | the declared members |
| Unknown attribute name | the attribute names the element accepts |
| Bad integer or float | **none** — there is nothing to guess |

### 7. The silence boundary

This is the part that decides whether the feature is shippable, so it is stated as rules rather than
left to the implementation.

| Condition | Behaviour |
|---|---|
| Value contains `${` | Silent. Solr substitutes it, possibly from outside the repository. Enforced in `SolrValueType.accepts` rather than at each call site, so no caller can forget it. |
| `valueType` is `FREE` | Silent on the value. `typeOf` returns null rather than a `FREE` type, so a caller cannot mistake "any value is legal" for "this value was verified". |
| Element carries a `class` naming a class the catalog does not know | Silent on every attribute of that element. This is how custom plugin classes stay unflagged. |
| Element carries **no** `class` and is not `field` or `dynamicField` | Silent. Covers `<copyField>`, `<uniqueKey>`, `<similarity>` and anything else a schema holds. |
| `<field default="…">` | Silent. Its legal values are whatever the field's type accepts — an instance of the `FREE` rule above, restated because it is the one open-valued property a reader will expect to be checked. |
| `class` or `name` on any element | Silent. Structural, and deliberately absent from the catalog. |
| **Any** attribute name on a `<fieldType>` | Silent. Its vocabulary is open; see section 3. |
| An analysis class *is* known, attribute is *not* in its list | **Flagged.** |

The last row is a deliberate acceptance of risk, taken with the alternative understood. Treating a
known analysis class's attribute list as complete is what makes `maxGramSiz` catchable, and that is
the typo that actually happens. It also means that if the bytecode pass ever misses a real attribute,
a correct file gets underlined. The defence is the clean fixtures below and the CI gate in step 20 —
a missed attribute fails a test loudly rather than reaching a user quietly.

**The row above it is where that reasoning ran out.** The same argument was originally applied to
`<fieldType>`, and it does not survive contact with `CurrencyFieldType`: the risk there is not
hypothetical but demonstrated, on a file Solr itself ships. The difference between the two rows is
that an analysis factory reads all of its own arguments, while a field type hands some of them to a
class its configuration names.

## Testing strategy

**The type check is a pure function, tested as one.** Plain JUnit 4 with backtick names, no fixture.
Booting an IDE to establish that `"foo"` is not an integer costs a second of wall-clock for nothing.

**Catalog assertions are named, never counted.** A bytecode pass does not fail; it produces a
plausible short list. A count passes for the wrong reason and moves with the Solr line. Every
expectation names what it wants:

| Class | Must expose |
|---|---|
| `solr.EdgeNGramFilterFactory` | `minGramSize:int`, `maxGramSize:int`, `preserveOriginal:bool` |
| `solr.WordDelimiterGraphFilterFactory` | `generateWordParts:bool`, `types:free` |
| `solr.CurrencyFieldType` | `defaultCurrency:free`, `providerClass:free` — **not** `currencyConfig` |
| `solr.CollationField` | `language:free`, `strength:free` — read in `setup`, not `init` |
| `solr.EnumFieldType` | `enumsConfig:free`, `enumName:free` |
| `solr.DenseVectorField` | `vectorDimension:free`, `similarityFunction:free` |
| `solr.SpatialRecursivePrefixTreeFieldType` | `geo:free`, `distErrPct:free` |
| `solr.PointType` | `dimension:free`, `subFieldSuffix:free` |

**`currencyConfig` is asserted absent, not present.** It is a real attribute — Solr's
`sample_techproducts_configs` writes it — read by `FileExchangeRateProvider`, a collaborator named at
runtime through `providerClass`. Pinning its absence is what stops a later change quietly closing the
field type vocabulary and taking the unknown-attribute warning with it.

The field type entries are the acceptance test for section 3. They are the classes whose
attributes are invisible today, and if the `Map.get` admission does not work they will be empty. Their
`:free` types are not a gap to close later — the erased `Map.get` signature carries no type, as
section 3 records.

The two analysis entries are the acceptance test for section 2. `minGramSize:int` proves the
descriptor test reads the return type, and every expectation it established must still hold: a
descriptor rewrite that silently matched fewer methods would shorten the lists without failing.

**The clean fixtures are written before the flagged ones.** A `<fieldType>` declaring each of the five
classes above, with their real attributes, asserting zero highlights. This is the direct regression
test for section 7's accepted risk: it is the fixture that fails if the extraction misses something.

`checkHighlighting` fails on highlights the fixture did not mark as well as ones it did, which is what
makes the zero-false-positive bar enforceable per test rather than only in CI.

## Delivery

**This is step 10 action 2** — *structural validation flagging unknown factories and invalid
attributes* — plus an extension to step 9's generator. It is not a new step, and step 10's criterion
*"Completion and validation work against the catalog"* is what it closes.

**Where it landed.** This was written expecting to wait for PR 52, which turned out to duplicate
PR 47 — the same step implemented twice in two worktrees. PR 52 was closed, the descriptor change went
onto PR 47 where the reader list it replaces lives, and the rest onto PR 54 stacked on top. The
reason for keeping them apart is unchanged: the extraction is verified by named expectation precisely
because a reviewer cannot eyeball a bytecode pass, so a shortened list has to be attributable to one
diff.

Landing order, each step independently green:

1. `SolrValueType` and `valueType` on `SolrFieldProperty`; retire the `BOOLEAN_VALUES` comparison. No
   behaviour change, no new inspection.
2. Generator switches from the seventeen-name set to the descriptor test and takes types from return
   descriptors; catalog column 4 becomes `name:type`; `SolrClassCatalog` reads it. Every named
   expectation from that work re-asserted, now with types. **On its own**, so a shortened list is
   attributable to this change and not to the field type work in step 3.
3. Generator covers field type classes, with the named expectations.
4. `SolrInvalidAttributeValue` with its clean fixtures first.
5. `SolrUnknownAttribute` with its clean fixtures first.

**Step 3 needed three changes this document did not anticipate**, each found by a named expectation
coming back empty rather than by reading the code. Field types are selected by *package*, because
`superclasses` is filled by the same loop that does the extraction and an ancestry test during it
would depend on jar entry order. *Every* method is visited rather than `init` alone, because
`CollationField` reads in `setup(ResourceLoader, Map)` — naming the methods is the same losing game
that naming the readers was. And attributes read by a *nested* class reach the enclosing one, because
`EnumFieldType` delegates to `EnumFieldType$EnumMapping`, a walk sideways rather than up.

Steps 4 and 5 landed together rather than one per pull request. They share the vocabulary function
and the same fixture file, and splitting them would have put the clean fixtures in one PR and half
the reasons they are clean in the other.

## Risks

- **The descriptor test matches fewer methods than the name set did.** This replaces a pass that was
  verified by named expectation, and the way it fails is by producing a shorter plausible list rather
  than an error. Every expectation from that work is re-asserted, now with types, and the landing order
  puts this change on its own so a regression is attributable.
- **The `Map.get` admission collects unrelated literals.** A field type's `init` may read some other
  map. Mitigated by the named expectations, and bounded by the fact that a wrongly collected name
  makes the plugin *more* permissive about unknown attributes, not less — a spurious entry silences a
  warning rather than inventing one.
- **A missed attribute underlines a correct file.** Accepted in section 7, defended by clean fixtures
  and the step 20 CI gate over both configsets Solr ships.
- **`FieldType` base-class properties overlap the hand-maintained table.** `FieldType` reads `indexed`,
  `stored` and their neighbours from the same argument map, so the extraction may rediscover what
  `SolrFieldProperties` already declares. The table wins where both have an opinion: it is written to
  *define* the semantics, and it carries the summaries and defaults documentation needs.
- **Both catalogs must be regenerated and reviewed.** The format change touches every row of both
  files. The diff is large but mechanical, and it is exactly the diff the tab-separated format exists
  to make reviewable.
