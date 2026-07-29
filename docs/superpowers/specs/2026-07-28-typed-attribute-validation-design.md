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
- Extend the catalog to field type classes, so `<fieldType>` is covered rather than exempt.
- Produce zero findings on every configset Solr ships.

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

PR 52 recognizes an argument read by matching the called method against a hardcoded set of seventeen
names — `get`, `getInt`, `getBoolean`, and so on. **That set goes away.** It is replaced by a test on
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
`args.remove("userDictionary")` working — the case PR 52 found in `JapaneseTokenizerFactory` — and it
is also the whole mechanism by which field type classes are read in section 3.

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
way.** Every expectation PR 52 established — `WordDelimiterGraphFilterFactory`'s attributes,
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

The consequence for section 5 is worth stating plainly: `<fieldType>` gains a **name** check and never
a **value** check. `vectorDimenson="768"` is caught as an unknown attribute; `vectorDimension="abc"`
is not caught at all.

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
`indexd="true"`, `maxGramSiz="15"`, `vectorDimenson="768"`.

**The valid set per element, stated rather than left to the implementation.** An earlier revision of
this section named the two inspections and never said what either compares against, which is the
kind of omission that gets decided differently in three places:

| Element | Valid attributes |
|---|---|
| `<field>`, `<dynamicField>` | `SolrFieldProperties.FOR_FIELD` ∪ `{name, type}` |
| `<fieldType>` | `FOR_FIELD_TYPE` ∪ catalog attributes of the named class ∪ `{name, class}` |
| `<tokenizer>`, `<filter>`, `<charFilter>` | catalog attributes of the named class ∪ `{class}` |
| `<analyzer>` | `{type, class}` |
| anything else | unchecked |

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
| `valueType` is `FREE` | Silent on the value. The *name* is still checked. |
| Element carries a `class` naming a class the catalog does not know | Silent on every attribute of that element. This is how custom plugin classes stay unflagged. |
| Element carries **no** `class` and is not `field` or `dynamicField` | Silent. Covers `<copyField>`, `<uniqueKey>`, `<similarity>` and anything else a schema holds. |
| `<field default="…">` | Silent. Its legal values are whatever the field's type accepts — an instance of the `FREE` rule above, restated because it is the one open-valued property a reader will expect to be checked. |
| `class` or `name` on any element | Silent. Structural, and deliberately absent from the catalog. |
| Class *is* known, attribute is *not* in its list | **Flagged.** |

The last row is a deliberate acceptance of risk, taken with the alternative understood. Treating a
known class's attribute list as complete is what makes `maxGramSiz` and `vectorDimenson` catchable,
and those are the typos that actually happen. It also means that if the bytecode pass ever misses a
real attribute, a correct file gets underlined. The defence is the clean fixtures below and the CI
gate in step 20 — a missed attribute fails a test loudly rather than reaching a user quietly.

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
| `solr.CurrencyFieldType` | `defaultCurrency:free`, `currencyConfig:free` |
| `solr.EnumFieldType` | `enumsConfig:free`, `enumName:free` |
| `solr.DenseVectorField` | `vectorDimension:free`, `similarityFunction:free` |
| `solr.SpatialRecursivePrefixTreeFieldType` | `geo:free`, `distErrPct:free` |
| `solr.PointType` | `dimension:free`, `subFieldSuffix:free` |

The five field type entries are the acceptance test for section 3. They are the classes whose
attributes are invisible today, and if the `Map.get` admission does not work they will be empty. Their
`:free` types are not a gap to close later — the erased `Map.get` signature carries no type, as
section 3 records.

The two analysis entries are the acceptance test for section 2. `minGramSize:int` proves the
descriptor test reads the return type, and every expectation PR 52 established must still hold: a
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

**It waits for PR 52 to merge.** That PR is verified and reviewable as it stands; folding a second
change to the same ASM pass into it would put the attribute extraction and the field type extension in
one diff, and the extraction was verified by checking named expectations precisely because a reviewer
cannot eyeball a bytecode pass.

Landing order, each step independently green:

1. `SolrValueType` and `valueType` on `SolrFieldProperty`; retire the `BOOLEAN_VALUES` comparison. No
   behaviour change, no new inspection.
2. Generator switches from the seventeen-name set to the descriptor test and takes types from return
   descriptors; catalog column 4 becomes `name:type`; `SolrClassCatalog` reads it. Every named
   expectation from PR 52 re-asserted, now with types. **On its own**, so a shortened list is
   attributable to this change and not to the field type work in step 3.
3. Generator covers field type classes, with the five named expectations.
4. `SolrInvalidAttributeValue` with its clean fixtures first.
5. `SolrUnknownAttribute` with its clean fixtures first.

Steps 4 and 5 are one inspection per pull request, matching how the three existing inspections landed.

## Risks

- **The descriptor test matches fewer methods than the name set did.** This replaces a pass that was
  verified by named expectation, and the way it fails is by producing a shorter plausible list rather
  than an error. Every expectation from PR 52 is re-asserted, now with types, and the landing order
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
