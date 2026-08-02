# Field property explanations: saying what the value means, not what the switch is

## Problem

The plugin resolves `stored` to `false` for `description`, attributes it correctly to the field, and
then describes it as *"Whether the original value can be returned in results."*

Every part of that is true and the reader still has to do the last step themselves. The value and its
meaning are rendered in adjacent columns of the same table and never joined:

| Property | Value | From | Accepts | Meaning |
|---|---|---|---|---|
| `stored` | **false** | on this field | true or false | Whether the original value can be returned in results. |

Nowhere does the plugin say *the original value is not returned in results*. It says what the switch
is for and, separately, which way it is flipped. That is a reference table, and the Reference Guide
already has one.

The inlay hint has the same gap from the other side. It says what a field *matches* and nothing about
what happens to the value afterwards, so a field that is searchable but not returnable — the single
most common cause of "my query works but the field is missing from the response" — looks identical
inline to one that is both.

### Why `summary` cannot simply be rewritten

`SolrFieldProperty.summary` is value-neutral because one of its three consumers needs it to be.
`SolrConfigsetCompletionContributor` renders it as tail text when offering `stored` as a completion,
before any value has been typed:

```kotlin
.withTailText("  ${firstSentence(property.summary)}", true)
```

At that moment there is no value to be specific about. Rewriting `summary` to *"The original value is
not returned in results"* would make completion assert a value the user has not chosen. The
value-specific prose is therefore **additive** — a second phrasing beside the first, not a
replacement for it.

## Goals

- Say what each property's resolved value *means for this field*, in both surfaces.
- Show the four properties that decide a field's storage shape inline, without a hover.
- Keep one source of phrasing, so the inlay and the popup cannot drift apart.
- Say nothing where the value is genuinely undetermined, per property rather than per hint.

## Non-goals

- **Changing which value is resolved.** This is presentation only; `SolrFieldProperties.resolve` and
  its version and trait rules are untouched.
- **Explaining non-boolean properties.** `default` takes any value of the field's type, and there is
  no true/false consequence to state. It keeps its neutral summary.
- **Inspecting for suspicious combinations.** `indexed=false docValues=false` makes a field inert, and
  saying so is an inspection's job, not a hint's. Additive later.

## Design

### The phrases live in the model, beside the property

`SolrFieldProperty` gains one optional field:

```kotlin
/**
 * What this property's value means for a field, in both registers, or null where the property
 * takes something other than a boolean and has no two consequences to state.
 */
val meaning: SolrPropertyMeaning? = null
```

```kotlin
data class SolrPropertyMeaning(
    val whenTrue: String,
    val whenFalse: String,
    val inlineWhenTrue: String? = null,
    val inlineWhenFalse: String? = null,
)
```

`whenTrue`/`whenFalse` are the popup sentences and are populated for every boolean property legal on
a field — seventeen of the eighteen in `FOR_FIELD`. The inline pair is populated for four of them,
and a null inline pair is what marks a property as popup-only. One table, one lookup, no second list
of "which properties are inline" to keep in step with the first.

This is the same argument `SolrFieldProperties` already makes for itself: the set is small, it defines
semantics rather than enumerating what exists, and it is hand-maintained on purpose.

### The four that appear inline

| Property | inline, true | inline, false |
|---|---|---|
| `indexed` | indexed | not indexed |
| `stored` | stored | not stored |
| `multiValued` | multi-valued | single-valued |
| `docValues` | doc values | no doc values |

Longest phrase is fourteen characters. The declarative inlay renderer truncates any single text
segment past thirty, which is why `SolrMatchInlayHintsProvider` already emits one segment per part;
each property phrase becomes its own segment for the same reason.

### The sentences

The four above, which carry both registers:

| Property | `whenTrue` | `whenFalse` |
|---|---|---|
| `indexed` | Can be searched, filtered and sorted on. | Cannot be searched or filtered — the value is carried but never queryable. |
| `stored` | The original value is returned in results and available to highlighting. | The original value is not returned in results; it can be searched but not displayed. |
| `multiValued` | One document may hold several values for this field. | One document may hold at most one value; a second causes an indexing error. |
| `docValues` | A column store is built, so sorting, faceting, grouping and function queries are efficient. | No column store; sorting and faceting must un-invert the index at query time, or fail outright. |

The remaining thirteen booleans carry sentences only. Written in the same register — a consequence in
the indicative, not a restatement of the attribute name:

| Property | `whenTrue` | `whenFalse` |
|---|---|---|
| `required` | A document lacking this field is rejected at index time. | A document may omit this field. |
| `omitNorms` | Length normalisation and index-time boosts are discarded, saving memory; short and long values score alike. | Norms are kept, so shorter values score higher for the same match. |
| `omitTermFreqAndPositions` | Term frequency and position data are discarded. Phrase and proximity queries stop working on this field. | Frequencies and positions are kept, so phrase and proximity queries work. |
| `omitPositions` | Positions are discarded while frequencies are kept, so scoring still reflects repetition but phrase queries stop working. | Positions are kept, so phrase queries work. |
| `termVectors` | Term vectors are stored, which highlighting and more-like-this can use instead of re-analysing the value. | No term vectors; highlighting re-analyses the stored value instead. |
| `termPositions` | Positions are stored in the term vector. | The term vector carries no positions. |
| `termOffsets` | Offsets are stored in the term vector, which is what fast vector highlighting needs. | The term vector carries no offsets. |
| `termPayloads` | Payloads are stored in the term vector. | The term vector carries no payloads. |
| `sortMissingFirst` | Documents lacking this field sort before all others, in either direction. | Documents lacking this field sort as though the value were lowest. |
| `sortMissingLast` | Documents lacking this field sort after all others, in either direction. | Documents lacking this field sort as though the value were lowest. |
| `uninvertible` | The field may be un-inverted at query time when it has no doc values — correct, but memory-hungry on a large index. | Sorting or faceting without doc values fails rather than silently building a field cache. |
| `useDocValuesAsStored` | Doc values are returned in results as though the field were stored, so a `fl` naming it gets a value back. | Doc values are not returned; only a stored field appears in results. |
| `large` | The value is loaded lazily and not held in the document cache above 512KB. | The value is loaded and cached like any other. |

`sortMissingFirst` and `sortMissingLast` share a `whenFalse` because Solr's fallback is the same
absent either flag, and inventing a distinction to make the two rows differ would be inventing one
Solr does not make.

### The inlay hint

`hintFor` returns the match parts, then the property parts, in the table order above. Two behaviour
changes fall out, both intended:

**The hint no longer suppresses itself on an unconfident chain.** Today `hintFor` returns null when
`SolrMatchAnalysis` does not recognise a factory, which is right for the match claim and wrong for
everything else: property values are read from attributes and version and class defaults, and an
unrecognised token filter says nothing about `stored`. Such a field gets the property half alone. The
existing rule — a wrong claim is worse than no claim — is preserved exactly where it applies and
stops applying where it never did.

**A property resolving to `UNDETERMINED` contributes no phrase.** That is the case the trait
resolution already models: the catalog does not carry the class the type names, so `docValues` has no
answer. The field still gets the other three phrases. This moves the silence rule from per-hint to
per-property, which is the granularity the underlying facts actually have.

**An undeclared field type keeps silencing the hint entirely**, and that is not the same case. The
existing test names the two refusals together, but only one of them survives:

| Refusal | Was | Becomes | Why |
|---|---|---|---|
| chain contains an unrecognised factory | no hint | property half only | property values never depended on the chain |
| the field's `type` is not declared | no hint | **no hint** | the middle tier of resolution is missing |

Property resolution is three-tier — field, then field type, then Solr's default — and an undeclared
type removes the middle tier without removing the fall-through. `stored` on such a field would
resolve to `true` and be attributed to "Solr default", when the truth is that the type which would
have decided it does not exist. That is a confident answer assembled from an incomplete chain, which
is the one thing this feature must not start doing. A missing type is already an inspection's
finding; the hint stays out of its way.

Rendered against `demo/solr/conf/managed-schema.xml`, which declares `version="1.6"`:

```
<field name="id"          .../>   whole value, case-sensitive, indexed, stored, doc values, single-valued
<field name="sku"         .../>   whole value, case-sensitive, indexed, stored, doc values, single-valued
<field name="name"        .../>   tokenised, case-insensitive, indexed, stored, no doc values, single-valued
<field name="name_prefix" .../>   tokenised, case-insensitive, prefix-capable, indexed, not stored, no doc values, single-valued
<field name="category"    .../>   whole value, case-sensitive, indexed, stored, doc values, single-valued
<field name="description" .../>   tokenised, case-insensitive, indexed, stored, no doc values, single-valued
<field name="text"        .../>   tokenised, case-insensitive, indexed, not stored, no doc values, multi-valued
```

**This is recorded rather than argued with.** Showing all four unconditionally means `indexed`
appears on all seven lines and `single-valued` on six of them, so the match half — the output
nothing else in the ecosystem produces — is often under half of each hint. That was the explicit
call: full values inline, not only the surprising ones. Ordering match first is the mitigation, and
it is the only one taken.

`doc values` on `id`/`sku`/`category` is the `string` fieldType's own doing, not the schema
version's: it declares `docValues="true"` directly on the `fieldType` element, which resolution
reads before it ever reaches the version-conditional default. `name`, `name_prefix`, `description`
and `text` carry no such declaration on `text_general`/`text_prefix`, so those four fall through to
the version rule, and the demo's `version="1.6"` — deliberately below the `1.7` the general rule
needs — is why they read `no doc values`. It is a fair advertisement for the popup, which names
whichever of the fieldType or the schema version actually decided it.

### The popup

`SolrFieldPresentation.propertyTable` keeps its five columns. The **Meaning** cell renders
`meaning.whenTrue` or `meaning.whenFalse` for the resolved value, and falls back to the neutral
`property.summary` in the two cases where no consequence can be stated: the value is `UNDETERMINED`,
or the property has no `meaning` at all. No new column — the table is already as wide as the popup
comfortably takes.

`propertyDocumentation`, which answers a hover on `stored="false"` directly, appends the same sentence
to its **Here** row, after the origin:

```
Here    false — on this field
        The original value is not returned in results; it can be searched but not displayed.
```

`elementDocumentation` and `fieldDocumentation` inherit the change through `propertyTable` and need no
edit of their own.

### Two private helpers become model API

The inlay provider needs the schema version and the field type's traits to resolve `docValues`, and
both currently exist as private helpers in `SolrConfigsetDocumentationProvider` — `versionOf(model)`
and `traitsOf(fieldType, version)`. Neither touches PSI; both are model logic that happens to live in
a PSI class. Copying them into the hint package would be two copies of the distinction the whole trait
resolution rests on: *no traits* and *unknown class* are different answers.

They move onto `SolrFieldModel`, which already carries `luceneMatchVersion` and `schemaVersion`:

```kotlin
/** The Solr line this configset targets, derived from its `luceneMatchVersion`. */
val solrVersion: SolrVersionSelection

/** The catalog's traits for the class [fieldType] names, or null when nothing can be said. */
fun traitsOf(fieldType: SolrFieldType?): Set<SolrTypeTrait>?
```

Both are public and both need KDoc, which the Dokka gate will enforce. `traitsOf` carries over the
existing KDoc verbatim — it is the whole reason the function cannot be inlined at either call site.
`SolrConfigsetDocumentationProvider` loses both private helpers and calls the model instead.

The model imports nothing new from the platform: `SolrClassCatalog`, `SolrClassKind`,
`SolrVersionSelection` and `SolrTypeTrait` are all already in `org.apache.solr.ide.model`.

## Testing

Three of the four suites are plain JUnit 4, because nothing under test imports the platform.

**`SolrFieldPropertiesTest`** — the table's own invariants, which is where a hand-maintained table
earns its tests:

- Every property in `FOR_FIELD` whose `valueType` is `BOOLEAN` has a non-null `meaning`.
- Exactly `indexed`, `stored`, `multiValued` and `docValues` have inline phrases.
- No inline phrase exceeds thirty characters, the renderer's segment budget.
- A property with an inline phrase for one value has one for the other; a half-populated pair would
  render an asymmetric hint that reads as a missing fact.

**`SolrFieldPresentationTest`**:

- The table's Meaning cell states the consequence of the resolved value, and the opposite sentence for
  the opposite value.
- It falls back to the neutral summary when the value is `UNDETERMINED`.
- `propertyDocumentation` carries the sentence under the Here row, and omits it on a `fieldType`,
  where `effective` is null and "the value for this field" has no meaning.

**`SolrFieldModelTest`** — the two moved helpers, including the case the move exists to protect:
a type naming a class the catalog does not carry returns null, not an empty set.

**`SolrMatchInlayHintsProviderTest`** — `SolrConfigsetTestCase`, since this one needs a fixture:

- A confident field renders match parts followed by all four property phrases, in that order.
- A field whose chain contains an unrecognised factory renders the property half and no match half —
  the behaviour change, asserted directly.
- A field whose type names a class the catalog does not know renders three phrases, without a
  `docValues` claim.
- A `dynamicField` gets the same treatment, which is the existing contract and easy to lose.

## Documentation to update

- **`docs/screenshots.md` entry 1** — the capture now shows storage shape as well as match capability,
  so its **Shows** and **Capture** paragraphs both change, and **Redo when** gains "a property's
  inline phrase changes".
- **The stale line numbers in the same file.** Entry 1 cites `managed-schema.xml:40-46` for the field
  block, which is at 47-53; entry 2 cites line 44 for `category`, which is at 51. Both predate a
  change to the demo schema and are wrong today, independently of this feature.
- **`docs/manual-test-suite.md`** — HINT-1 through HINT-3 describe a hint that is match-only and need
  a property clause each. HINT-4 needs no rewording but becomes the check that matters most: it asks
  that the hint be *"readable at presentation font size"*, and this change roughly doubles its length.
  A new HINT-5 covers the unconfident chain, which the suite does not currently exercise at all.
