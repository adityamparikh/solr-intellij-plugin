# Field Property Explanations Implementation Plan

> **Shipped. This is a historical record, not a plan to execute.** Several of the code
> snippets below were wrong and were corrected during implementation — the phrase order,
> a constructor argument, and a version-conditional default among them. **Read
> [Corrections found during implementation](#corrections-found-during-implementation) at
> the end before quoting anything above it.** Where this document and the code disagree,
> the code is right.

> **For agentic workers (historical):** this plan was executed with
> superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Say what each field property's resolved value *means for this field* — as four terse phrases inline beside the declaration, and as full sentences in the documentation popup.

**Architecture:** One hand-maintained phrase table on `SolrFieldProperty` in the model, carrying both registers. `SolrFieldPresentation` renders the sentences; `SolrMatchInlayHintsProvider` renders the terse phrases. Both read the same table, which is why they cannot drift. Two private helpers move from the documentation provider onto `SolrFieldModel` so the hint can resolve type-dependent defaults without duplicating the null-versus-empty-set rule.

**Tech Stack:** Kotlin, IntelliJ Platform Gradle Plugin, JUnit 4, `BasePlatformTestCase` for anything touching PSI.

**Design record:** [`design.md`](design.md) beside this file. It argues the decisions this plan only executes, and it was kept correct as the corrections below accumulated — prefer it over this document wherever the two describe the same behaviour.

## Global Constraints

- **Nothing in `org.apache.solr.ide.model` imports an IntelliJ type.** `SolrPropertyMeaning` and the moved `SolrFieldModel` members must import only from `org.apache.solr.ide.model`.
- **Every public class, function and property in `src/main/kotlin` needs KDoc.** Dokka runs with `reportUndocumented` and `failOnWarning`, bound to `check`. This includes every new public property, the new data class and all four of its constructor parameters.
- **Tests importing nothing from the platform are plain JUnit 4** — `@Test` and backtick names. Tests touching PSI extend `BasePlatformTestCase`, are JUnit 3-style, and their methods must be named `testSomething()`.
- **Anything touching `SolrConfigsetSettings` or `SolrConnectionSettings` extends `SolrConfigsetTestCase`.** `SolrMatchInlayHintsProviderTest` already does; keep it that way.
- **No inline phrase may exceed 30 characters** — `PresentationTreeBuilderImpl.MAX_SEGMENT_TEXT_LENGTH`. A longer segment renders truncated with an ellipsis.
- **British spellings**, matching the existing model text: `tokenised`, `normalisation`, `recognise`.
- **Conventional-commit subjects and mandatory sign-off** — `git commit -s`, subject `feat:` or `docs:`.
- **Run `./gradlew build` before the final commit of each task.** It compiles, tests, and runs both the Dokka and Kover gates.

---

### Task 1: The phrase table

**Files:**
- Modify: `src/main/kotlin/org/apache/solr/ide/model/SolrFieldProperties.kt`
- Test: `src/test/kotlin/org/apache/solr/ide/model/SolrFieldPropertiesTest.kt`

**Interfaces:**
- Consumes: nothing — this is the first task.
- Produces: `SolrPropertyMeaning(whenTrue: String, whenFalse: String, inlineWhenTrue: String?, inlineWhenFalse: String?)` and `SolrFieldProperty.meaning: SolrPropertyMeaning?`. Tasks 3 and 4 read both.

- [ ] **Step 1: Write the failing tests**

Append to `SolrFieldPropertiesTest`:

```kotlin
/** The four whose values are terse enough to state beside the declaration. */
private val inlineProperties = setOf("indexed", "stored", "multiValued", "docValues")

@Test
fun `every boolean property legal on a field states what its value means`() {
    val missing = SolrFieldProperties.FOR_FIELD
        .filter { it.valueType == SolrValueType.BOOLEAN && it.meaning == null }
        .map { it.name }
    assertEquals("every boolean needs a meaning", emptyList<String>(), missing)
}

/**
 * A non-boolean has two values to state nothing about. `default` takes any value of the field's
 * type, so a true/false consequence would be inventing a distinction Solr does not make.
 */
@Test
fun `a property that is not a boolean carries no meaning`() {
    assertNull(SolrFieldProperties.byName("default")!!.meaning)
}

@Test
fun `exactly the four storage-shape properties speak inline`() {
    val inline = SolrFieldProperties.ALL
        .filter { it.meaning?.inlineWhenTrue != null }
        .map { it.name }
        .toSet()
    assertEquals(inlineProperties, inline)
}

/**
 * The declarative renderer truncates any single segment past 30 characters, and each phrase
 * arrives as its own segment. A phrase over budget renders as "no doc value…".
 */
@Test
fun `no inline phrase exceeds the renderers segment budget`() {
    val overBudget = SolrFieldProperties.ALL
        .mapNotNull { it.meaning }
        .flatMap { listOfNotNull(it.inlineWhenTrue, it.inlineWhenFalse) }
        .filter { it.length > 30 }
    assertEquals(emptyList<String>(), overBudget)
}

/**
 * A half-populated pair renders a hint that states one value and says nothing for the other,
 * which reads as a missing fact rather than as a deliberate silence.
 */
@Test
fun `an inline phrase for one value implies one for the other`() {
    val asymmetric = SolrFieldProperties.ALL
        .filter { (it.meaning?.inlineWhenTrue == null) != (it.meaning?.inlineWhenFalse == null) }
        .map { it.name }
    assertEquals(emptyList<String>(), asymmetric)
}

/** Sentences are prose for a reader; a bare attribute name is a restatement, not an explanation. */
@Test
fun `every sentence is a sentence`() {
    val malformed = SolrFieldProperties.ALL
        .mapNotNull { p -> p.meaning?.let { p.name to it } }
        .filter { (_, m) -> !m.whenTrue.endsWith(".") || !m.whenFalse.endsWith(".") }
        .map { it.first }
    assertEquals(emptyList<String>(), malformed)
}
```

Add `import org.apache.solr.ide.model.SolrValueType` only if the test file is outside the `model` package — it is not, so no import is needed. `assertNull` and `assertEquals` are already imported.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew test --tests "*.SolrFieldPropertiesTest"
```

Expected: FAIL to **compile**, with `Unresolved reference: meaning`. A compile failure is the correct first failure here — the property does not exist yet.

- [ ] **Step 3: Add the type**

In `SolrFieldProperties.kt`, above `data class SolrFieldProperty`:

```kotlin
/**
 * What a boolean property's value means for a field, in the two registers the plugin renders.
 *
 * Two lengths rather than one because the two surfaces have incompatible budgets. The popup has
 * room for a consequence in a full sentence; the inlay is beside the declaration and each phrase
 * is its own segment against a 30-character renderer limit. Deriving one from the other would mean
 * truncating a sentence, which is how a hint ends up saying "The original value is not retu…".
 *
 * Held here rather than in either feature's presentation code for the reason
 * [org.apache.solr.ide.model.SolrMatchCapability.summary] already gives: the same field described
 * two ways is two chances to be doubted.
 *
 * @property whenTrue the consequence of the value being `true`, as a sentence for the popup
 * @property whenFalse the consequence of the value being `false`, as a sentence for the popup
 * @property inlineWhenTrue the terse phrase shown inline when the value is `true`, or null for a
 *   property that is explained on request but does not earn space beside every declaration
 * @property inlineWhenFalse the terse phrase shown inline when the value is `false`, null on the
 *   same terms — never null alone, since a property that speaks for one value must speak for both
 */
data class SolrPropertyMeaning(
    val whenTrue: String,
    val whenFalse: String,
    val inlineWhenTrue: String? = null,
    val inlineWhenFalse: String? = null,
)
```

- [ ] **Step 4: Add the property to `SolrFieldProperty`**

Add a parameter after `typeDefault`, and a matching `@property` line in the class KDoc:

```kotlin
 * @property meaning what each of its two values means for a field, or null where the property
 *   takes something other than a boolean and so has no two consequences to state
 */
data class SolrFieldProperty(
    // … existing parameters unchanged …
    val typeDefault: SolrTypeDefaultRule? = null,
    val meaning: SolrPropertyMeaning? = null,
) {
```

- [ ] **Step 5: Populate the four inline entries**

Replace the first four entries of `ALL`. Note `indexed` and `stored` are currently written positionally — keep that and append `meaning =` as a named argument.

```kotlin
SolrFieldProperty(
    "indexed", "Whether the field can be searched or sorted on.", "true or false", "true",
    meaning = SolrPropertyMeaning(
        whenTrue = "Can be searched, filtered and sorted on.",
        whenFalse = "Cannot be searched or filtered — the value is carried but never queryable.",
        inlineWhenTrue = "indexed",
        inlineWhenFalse = "not indexed",
    ),
),
SolrFieldProperty(
    "stored", "Whether the original value can be returned in results.", "true or false", "true",
    meaning = SolrPropertyMeaning(
        whenTrue = "The original value is returned in results and available to highlighting.",
        whenFalse = "The original value is not returned in results; it can be searched but not displayed.",
        inlineWhenTrue = "stored",
        inlineWhenFalse = "not stored",
    ),
),
SolrFieldProperty(
    "docValues",
    "Whether a column-oriented structure is built, used for sorting, faceting and grouping.",
    "true or false",
    null,
    typeDefault = SolrTypeDefaultRule.DOC_VALUES,
    meaning = SolrPropertyMeaning(
        whenTrue = "A column store is built, so sorting, faceting, grouping and function queries are efficient.",
        whenFalse = "No column store; sorting and faceting must un-invert the index at query time, or fail outright.",
        inlineWhenTrue = "doc values",
        inlineWhenFalse = "no doc values",
    ),
),
SolrFieldProperty(
    "multiValued",
    "Whether one document may hold several values.",
    "true or false",
    null,
    defaultTrueWithin = SolrVersionRange(below = 1.1f),
    meaning = SolrPropertyMeaning(
        whenTrue = "One document may hold several values for this field.",
        whenFalse = "One document may hold at most one value; a second causes an indexing error.",
        inlineWhenTrue = "multi-valued",
        inlineWhenFalse = "single-valued",
    ),
),
```

- [ ] **Step 6: Populate the thirteen popup-only entries**

Add `meaning = SolrPropertyMeaning(whenTrue = …, whenFalse = …)` — **no inline pair** — to each of these existing entries, leaving every other argument exactly as it is:

| Entry | `whenTrue` | `whenFalse` |
|---|---|---|
| `required` | `A document lacking this field is rejected at index time.` | `A document may omit this field.` |
| `omitNorms` | `Length normalisation and index-time boosts are discarded, saving memory; short and long values score alike.` | `Norms are kept, so shorter values score higher for the same match.` |
| `omitTermFreqAndPositions` | `Term frequency and position data are discarded. Phrase and proximity queries stop working on this field.` | `Frequencies and positions are kept, so phrase and proximity queries work.` |
| `omitPositions` | `Positions are discarded while frequencies are kept, so scoring still reflects repetition but phrase queries stop working.` | `Positions are kept, so phrase queries work.` |
| `termVectors` | `Term vectors are stored, which highlighting and more-like-this can use instead of re-analysing the value.` | `No term vectors; highlighting re-analyses the stored value instead.` |
| `termPositions` | `Positions are stored in the term vector.` | `The term vector carries no positions.` |
| `termOffsets` | `Offsets are stored in the term vector, which is what fast vector highlighting needs.` | `The term vector carries no offsets.` |
| `termPayloads` | `Payloads are stored in the term vector.` | `The term vector carries no payloads.` |
| `sortMissingFirst` | `Documents lacking this field sort before all others, in either direction.` | `Documents lacking this field sort as though the value were lowest.` |
| `sortMissingLast` | `Documents lacking this field sort after all others, in either direction.` | `Documents lacking this field sort as though the value were lowest.` |
| `uninvertible` | `The field may be un-inverted at query time when it has no doc values — correct, but memory-hungry on a large index.` | `Sorting or faceting without doc values fails rather than silently building a field cache.` |
| `useDocValuesAsStored` | `Doc values are returned in results as though the field were stored, so an fl naming it gets a value back.` | `Doc values are not returned; only a stored field appears in results.` |
| `large` | `The value is loaded lazily and not held in the document cache above 512KB.` | `The value is loaded and cached like any other.` |

Leave `default` alone — it is the one `FOR_FIELD` entry that is not a boolean, and Task 1 Step 1 tests that it stays null.

Leave the six `TYPE_ONLY` entries alone (`positionIncrementGap`, `autoGeneratePhraseQueries`, `synonymQueryStyle`, `enableGraphQueries`, `docValuesFormat`, `postingsFormat`). They never resolve against a field, so no consequence can be stated.

- [ ] **Step 7: Run the tests to verify they pass**

```bash
./gradlew test --tests "*.SolrFieldPropertiesTest"
```

Expected: PASS, including the pre-existing resolution tests, which this task does not touch.

- [ ] **Step 8: Run the full build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL. If Dokka fails naming `SolrPropertyMeaning` or a parameter, a `@property` line is missing.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/org/apache/solr/ide/model/SolrFieldProperties.kt \
        src/test/kotlin/org/apache/solr/ide/model/SolrFieldPropertiesTest.kt
git commit -s -m "feat: state what each field property's value means, not just what the switch is

SolrFieldProperty.summary is value-neutral because completion needs it to
be — it offers `stored` before any value is typed, so it cannot assert one.
The consequence prose is therefore additive rather than a rewrite.

Two registers because the two surfaces have incompatible budgets: the popup
takes a sentence, the inlay is beside the declaration against a 30-character
per-segment renderer limit. Deriving one from the other means truncating a
sentence mid-word.

Four properties carry the terse pair; the other thirteen booleans are
explained on request only."
```

---

### Task 2: Version and traits become model API

**Files:**
- Modify: `src/main/kotlin/org/apache/solr/ide/model/SolrFieldModel.kt`
- Modify: `src/main/kotlin/org/apache/solr/ide/configset/documentation/SolrConfigsetDocumentationProvider.kt`
- Test: `src/test/kotlin/org/apache/solr/ide/model/SolrFieldModelTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `SolrFieldModel.solrVersion: SolrVersionSelection` and `SolrFieldModel.traitsOf(fieldType: SolrFieldType?): Set<SolrTypeTrait>?`. Task 4 calls both.

**Why this is its own task:** the hint needs both to resolve `docValues`, and both are currently private in a PSI class. A reviewer could reject this refactor while approving the phrase table, and vice versa.

- [ ] **Step 1: Write the failing tests**

Append to `SolrFieldModelTest`:

```kotlin
@Test
fun `the solr line is derived from the configsets luceneMatchVersion`() {
    val model = SolrFieldModel(luceneMatchVersion = "9.8.0")
    assertEquals(SolrVersionSelection.fromLuceneMatchVersion("9.8.0"), model.solrVersion)
}

@Test
fun `a configset declaring no luceneMatchVersion falls back to the default line`() {
    assertEquals(SolrVersionSelection.DEFAULT, SolrFieldModel().solrVersion)
}

@Test
fun `a known field type class yields the catalogs traits for it`() {
    val model = SolrFieldModel(luceneMatchVersion = "10.0.0")
    val traits = model.traitsOf(SolrFieldType("string", "solr.StrField"))
    assertNotNull("solr.StrField is in the catalog", traits)
}

/**
 * The distinction the whole trait resolution rests on. An empty set says "known class, no traits",
 * which makes a type-dependent default a definite false. Null says nothing is known. Collapsing
 * the two makes every custom plugin type report a confident wrong omitNorms.
 */
@Test
fun `a class the catalog does not carry yields null rather than no traits`() {
    val model = SolrFieldModel(luceneMatchVersion = "10.0.0")
    assertNull(model.traitsOf(SolrFieldType("mystery", "com.example.MysteryFieldType")))
}

@Test
fun `a type naming no class at all yields null`() {
    assertNull(SolrFieldModel().traitsOf(SolrFieldType("nameless", "")))
}

@Test
fun `no type at all yields null`() {
    assertNull(SolrFieldModel().traitsOf(null))
}

/**
 * A tokenizer factory is in the catalog but is not a field type, and its traits are meaningless
 * as a field's. Reading them would attribute a tokenizer's properties to a field.
 */
@Test
fun `a class that is in the catalog but is not a field type yields null`() {
    val model = SolrFieldModel(luceneMatchVersion = "10.0.0")
    assertNull(model.traitsOf(SolrFieldType("wrong", "solr.StandardTokenizerFactory")))
}
```

Add whatever of `assertNotNull` / `assertNull` / `assertEquals` the file does not already import from `org.junit.Assert`.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew test --tests "*.SolrFieldModelTest"
```

Expected: FAIL to compile — `Unresolved reference: solrVersion` and `Unresolved reference: traitsOf`.

- [ ] **Step 3: Add both members to `SolrFieldModel`**

Inside the class body, beside `typeOf`. Move the KDoc for `traitsOf` across verbatim from `SolrConfigsetDocumentationProvider` — it is the reason the function cannot be inlined at either call site.

```kotlin
/**
 * The Solr line this configset targets, for reading the generated class catalog.
 *
 * Only two of the spec's three sources are available here: the configset's own declaration, and
 * the default. A connected server would outrank both, and will once the server reader lands.
 */
val solrVersion: SolrVersionSelection
    get() = luceneMatchVersion?.let { SolrVersionSelection.fromLuceneMatchVersion(it) }
        ?: SolrVersionSelection.DEFAULT

/**
 * The catalog's traits for the class [fieldType] names, or null when nothing can be said.
 *
 * Null covers three cases that are all the same answer: no type was resolved, the type names no
 * class, or the class is one the catalog does not carry — a custom plugin type. Returning an
 * empty set for any of them would claim the class is known and carries no trait, which is what
 * turns `omitNorms` into a confident `false` for a type nobody here has ever seen.
 *
 * @param fieldType the type whose class to look up, or null
 * @return its traits, or null when the class is not one the catalog carries
 */
fun traitsOf(fieldType: SolrFieldType?): Set<SolrTypeTrait>? {
    val className = fieldType?.className?.takeIf { it.isNotEmpty() } ?: return null
    return SolrClassCatalog.find(className, solrVersion)
        ?.takeIf { it.kind == SolrClassKind.FIELD_TYPE }
        ?.traits
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew test --tests "*.SolrFieldModelTest"
```

Expected: PASS.

- [ ] **Step 5: Delete the private helpers and redirect their call sites**

In `SolrConfigsetDocumentationProvider`, delete `private fun traitsOf(...)` and `private fun versionOf(...)` entirely, then replace every call:

| Was | Becomes |
|---|---|
| `versionOf(model)` | `model.solrVersion` |
| `traitsOf(type, version)` | `model.traitsOf(type)` |
| `traitsOf(field?.let { model.typeOf(it) }, versionOf(model))` | `model.traitsOf(field?.let { model.typeOf(it) })` |
| `traitsOf(type, versionOf(model))` | `model.traitsOf(type)` |

There are call sites in `generateDoc` (the `Target.Field` branch), `getUrlFor`, `elementDocumentation` and `propertyDocumentation`. In `generateDoc` the local `val version = versionOf(model)` becomes `val version = model.solrVersion`; keep the local, since it is used four times in that function.

Drop any import left unused — likely `SolrClassKind` and `SolrTypeTrait`. The compiler warns rather than fails, so check the build output.

- [ ] **Step 6: Run the full build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL, with `SolrConfigsetDocumentationProviderTest` and `SolrFieldPresentationTest` passing unchanged. This refactor is behaviour-preserving; if either fails, a call site was redirected wrongly.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/org/apache/solr/ide/model/SolrFieldModel.kt \
        src/main/kotlin/org/apache/solr/ide/configset/documentation/SolrConfigsetDocumentationProvider.kt \
        src/test/kotlin/org/apache/solr/ide/model/SolrFieldModelTest.kt
git commit -s -m "refactor: move version and trait resolution onto the model

Both were private helpers in the documentation provider and neither touches
PSI. The inlay hint needs both to resolve docValues, and copying them would
be two copies of the distinction the trait resolution rests on: an empty
trait set means the class is known and carries none, null means nothing is
known at all. One custom plugin type and the two answers diverge.

Behaviour-preserving. The model imports nothing new — SolrClassCatalog,
SolrClassKind and SolrVersionSelection are already model types."
```

---

### Task 3: The popup states the consequence

**Files:**
- Modify: `src/main/kotlin/org/apache/solr/ide/configset/documentation/SolrFieldPresentation.kt`
- Test: `src/test/kotlin/org/apache/solr/ide/configset/documentation/SolrFieldPresentationTest.kt`

**Interfaces:**
- Consumes: `SolrFieldProperty.meaning` and `SolrPropertyMeaning.whenTrue` / `.whenFalse` from Task 1.
- Produces: nothing Task 4 depends on. Tasks 3 and 4 can be done in either order once Tasks 1 and 2 are in.

- [ ] **Step 1: Write the failing tests**

Append to `SolrFieldPresentationTest`. The existing fixtures `stringType` and `modernSchema` are already in the file.

```kotlin
@Test
fun `the property table states the consequence of the value that is in effect`() {
    val field = SolrField("sku", "string", mapOf("stored" to "false"))
    val html = SolrFieldPresentation.fieldDocumentation(
        field, stringType, SolrVersionSelection.DEFAULT, modernSchema,
    )
    assertTrue(
        "the false consequence must be stated",
        "The original value is not returned in results" in html,
    )
    assertFalse(
        "the true consequence must not appear for a false value",
        "The original value is returned in results" in html,
    )
}

@Test
fun `the opposite value gets the opposite sentence`() {
    val field = SolrField("sku", "string", mapOf("stored" to "true"))
    val html = SolrFieldPresentation.fieldDocumentation(
        field, stringType, SolrVersionSelection.DEFAULT, modernSchema,
    )
    assertTrue("The original value is returned in results" in html)
}

/**
 * An undetermined value has no consequence to state, and picking one of the two would be exactly
 * the confident wrong answer the null value exists to avoid. The neutral summary is the fallback.
 */
@Test
fun `an undetermined value falls back to the value-neutral summary`() {
    val unknownType = SolrFieldType("mystery", "com.example.MysteryFieldType")
    val field = SolrField("odd", "mystery")
    val html = SolrFieldPresentation.fieldDocumentation(
        field, unknownType, SolrVersionSelection.DEFAULT, modernSchema, typeTraits = null,
    )
    assertTrue(
        "the neutral summary must stand in",
        "Whether a column-oriented structure is built" in html,
    )
    assertFalse("no column store claim either way", "A column store is built" in html)
    assertFalse("no column store claim either way", "No column store;" in html)
}

@Test
fun `hovering a property attribute states the consequence under its resolved value`() {
    val field = SolrField("sku", "string", mapOf("stored" to "false"))
    val property = SolrFieldProperties.byName("stored")!!
    val html = SolrFieldPresentation.propertyDocumentation(
        property = property,
        effective = SolrFieldProperties.resolve(property, field, stringType, modernSchema),
        version = SolrVersionSelection.DEFAULT,
        schemaVersion = modernSchema,
    )
    assertTrue("The original value is not returned in results" in html)
}

/**
 * On a fieldType there is no field to resolve against, so there is no value and no consequence.
 * The general half — summary, accepted values, Solr's default — is all a type can be told.
 */
@Test
fun `hovering a property on a field type states no consequence`() {
    val html = SolrFieldPresentation.propertyDocumentation(
        property = SolrFieldProperties.byName("stored")!!,
        effective = null,
        version = SolrVersionSelection.DEFAULT,
        schemaVersion = modernSchema,
    )
    assertFalse("The original value is" in html)
}
```

Add imports for `SolrFieldProperties` and `SolrPropertyMeaning` as needed — `SolrField`, `SolrFieldType`, `SolrVersionSelection` and `SolrSchemaVersion` are already imported.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew test --tests "*.SolrFieldPresentationTest"
```

Expected: FAIL — four assertion failures, since the sentences are not rendered anywhere yet. `hovering a property on a field type states no consequence` will pass already; that is fine, it is a regression guard.

- [ ] **Step 3: Add the selector**

In `SolrFieldPresentation`, beside `valueText`:

```kotlin
/**
 * The consequence of a property's resolved value, or its value-neutral summary where there is none.
 *
 * Falls back in the two cases where no consequence can be stated: the value is undetermined, so
 * choosing one of the two sentences would assert what the null value exists to avoid; or the
 * property takes something other than a boolean and has no two consequences to choose between.
 */
private fun meaningText(effective: SolrEffectiveProperty): String {
    val meaning = effective.property.meaning ?: return effective.property.summary
    return when (effective.value) {
        "true" -> meaning.whenTrue
        "false" -> meaning.whenFalse
        else -> effective.property.summary
    }
}
```

- [ ] **Step 4: Render it in the table**

In `propertyTable`, replace the Meaning cell:

```kotlin
append("<td>${escape(effective.property.summary)}</td></tr>")
```

with:

```kotlin
append("<td>${escape(meaningText(effective))}</td></tr>")
```

The column header stays `Meaning` — it is now a better description of the cell than it was.

- [ ] **Step 5: Render it under the Here row**

In `propertyDocumentation`, replace the `effective?.let { ... }` block:

```kotlin
effective?.let {
    append(
        "<tr><td>Here</td><td><b>${escape(valueText(it))}</b> — " +
            "${escape(originText(it.origin, schemaVersion, typeClassName))}</td></tr>",
    )
    val meaning = meaningText(it)
    if (meaning != property.summary) {
        append("<tr><td></td><td>${escape(meaning)}</td></tr>")
    }
}
```

The guard keeps the popup from printing the same sentence twice: the summary is already rendered as the paragraph above the table, so repeating it when no consequence is available adds a row that says nothing new.

- [ ] **Step 6: Run the tests to verify they pass**

```bash
./gradlew test --tests "*.SolrFieldPresentationTest"
```

Expected: PASS, all 24. If `an undetermined value falls back to the value-neutral summary` fails, check that the test passes `typeTraits = null` — an omitted argument defaults to null, but being explicit is what makes the test's intent readable.

- [ ] **Step 7: Run the full build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/org/apache/solr/ide/configset/documentation/SolrFieldPresentation.kt \
        src/test/kotlin/org/apache/solr/ide/configset/documentation/SolrFieldPresentationTest.kt
git commit -s -m "feat: the popup states what a property's value means for the field

The value and its meaning were rendered in adjacent columns of the same
table and never joined: stored=false beside 'Whether the original value can
be returned in results'. Both true, and the reader did the last step.

Falls back to the neutral summary where no consequence can be stated — an
undetermined value, or a property that is not a boolean. Choosing one of two
sentences for a value the plugin does not know is the confident wrong answer
the null value exists to avoid."
```

---

### Task 4: The hint carries the storage shape

**Files:**
- Modify: `src/main/kotlin/org/apache/solr/ide/configset/hint/SolrMatchInlayHintsProvider.kt`
- Test: `src/test/kotlin/org/apache/solr/ide/configset/hint/SolrMatchInlayHintsProviderTest.kt`

**Interfaces:**
- Consumes: `SolrFieldProperty.meaning` from Task 1; `SolrFieldModel.solrVersion` and `SolrFieldModel.traitsOf` from Task 2.
- Produces: nothing downstream.

- [ ] **Step 1: Extend the test fixture and add a segment-reading helper**

In `SolrMatchInlayHintsProviderTest`, add one field type and one field to the `schema` string, inside `<schema>`:

```kotlin
  <fieldType name="unknown_class" class="com.example.MysteryFieldType"/>
  <field name="opaque" type="unknown_class"/>
```

Then add a helper beside `hintedFields`, which returns the joined segments for one field rather than only which fields were hinted:

```kotlin
/** The hint text for one field, reassembled from its segments, or null if it got no hint. */
private fun hintFor(name: String, text: String = schema): String? {
    myFixture.addFileToProject("core/conf/solrconfig.xml", "<config/>")
    myFixture.configureByText("managed-schema.xml", text)
    val collector = SolrMatchInlayHintsProvider().createCollector(myFixture.file, myFixture.editor)
        ?: return null
    for (tag in PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java)) {
        if (tag.getAttributeValue("name") != name) continue
        val sink = RecordingSink()
        (collector as SharedBypassCollector).collectFromElement(tag, sink)
        if (sink.presentations.isEmpty()) return null
        return sink.trees.single().joinToString("")
    }
    return null
}
```

- [ ] **Step 2: Write the failing tests**

```kotlin
/**
 * Match first, then storage shape: the match half is the output nothing else produces.
 *
 * `no doc values` because this fixture's `<schema>` declares no `version`, so
 * [SolrSchemaVersion.ASSUMED] — 1.0 — applies, and the `docValues` default turns on 1.7. That is
 * Solr's own reading of a versionless schema, and the fixture is left that way deliberately.
 */
fun testHintStatesMatchCapabilityThenStorageShape() {
    assertEquals(
        "whole value, case-sensitive, indexed, stored, single-valued, no doc values",
        hintFor("sku"),
    )
}

/**
 * The behaviour change. An unrecognised factory means the analyser chain was not understood; it
 * says nothing about stored or multiValued, which are read from attributes and defaults. Withholding
 * them was withholding a fact the plugin is certain of.
 */
fun testAnUnrecognisedFactorySilencesOnlyTheMatchHalf() {
    assertEquals("indexed, stored, single-valued, no doc values", hintFor("mystery"))
}

/**
 * The refusal that survives. Property resolution is three-tier — field, then field type, then
 * Solr's default — and an undeclared type removes the middle tier without removing the
 * fall-through, so `stored` would resolve to true and be attributed to a Solr default that the
 * missing type might have overridden. A missing type is an inspection's finding.
 */
fun testAnUndeclaredTypeStillSilencesTheHintEntirely() {
    assertNull(hintFor("orphan"))
}

/**
 * Per-property silence. The catalog does not carry this class, so docValues has no answer — but
 * indexed, stored and multiValued never depended on the class at all.
 */
fun testAPropertyWithNoAnswerContributesNoPhrase() {
    val hint = hintFor("opaque")
    assertNotNull(hint)
    assertTrue("indexed, stored, single-valued" in hint!!)
    assertFalse("no doc values claim either way", "doc values" in hint)
}
```

Then **update the existing test** `testNoHintWhereTheAnalysisIsNotConfidentOrTheTypeIsUndeclared`, which asserts the behaviour this task deliberately changes. Replace it entirely with:

```kotlin
/**
 * The refusal that survives, at the level of which fields get a hint at all. The unconfident case
 * has moved to testAnUnrecognisedFactorySilencesOnlyTheMatchHalf.
 */
fun testNoHintWhereTheTypeIsUndeclared() {
    assertFalse("an undeclared type must silence the hint", "orphan" in hintedFields())
}
```

And update `testEveryClassifiableFieldGetsExactlyOneHint`, whose expected list grows by the two fields that now qualify:

```kotlin
fun testEveryClassifiableFieldGetsExactlyOneHint() {
    assertEquals(listOf("sku", "name", "mystery", "opaque", "*_s"), hintedFields())
}
```

The order is document order — `hintedFields` walks the PSI tree — so place `unknown_class`/`opaque` after `mystery` in the schema string and before `orphan`.

Add `assertNotNull` and `assertNull` imports if absent. `BasePlatformTestCase` inherits JUnit 3 assertions, so these resolve without an `org.junit.Assert` import; follow whatever the file already does.

- [ ] **Step 3: Run the tests to verify they fail**

```bash
./gradlew test --tests "*.SolrMatchInlayHintsProviderTest"
```

Expected: FAIL. `testHintStatesMatchCapabilityThenStorageShape` fails on the missing property half; `testAnUnrecognisedFactorySilencesOnlyTheMatchHalf` and `testAPropertyWithNoAnswerContributesNoPhrase` fail with a null hint.

- [ ] **Step 4: Rewrite `hintFor` in the provider**

Replace the private `hintFor` in `Collector`, and update its two callers' expectations. The signature gains the model, since resolution needs the schema version and traits:

```kotlin
/**
 * The hint for a field as its summary parts, or null when nothing should be said.
 *
 * Null only where the field type is undeclared. Property resolution is three-tier — field, then
 * field type, then Solr's default — and an undeclared type removes the middle tier without
 * removing the fall-through, so every default would be attributed to Solr when the type that
 * might have overridden it simply does not exist. That is an inspection's finding, not a hint's.
 *
 * An unrecognised factory is a different case and no longer silences anything but the match half:
 * property values are read from attributes and from version and class defaults, and never
 * depended on the analyser chain.
 */
private fun hintFor(field: SolrField, fieldType: SolrFieldType?, model: SolrFieldModel): List<String>? {
    if (fieldType == null) return null
    val capability = SolrMatchAnalysis.of(fieldType)
    val match = if (capability.confident) capability.summaryParts else emptyList()
    return match + storageShape(field, fieldType, model)
}

/**
 * The four storage-shape phrases, in the order the Reference Guide lists the properties.
 *
 * A property with no answer contributes nothing rather than a guess — the catalog not carrying a
 * type's class is exactly where asserting a `docValues` default would be inventing one. That
 * silence is per property, because that is the granularity the underlying facts have.
 */
private fun storageShape(field: SolrField, fieldType: SolrFieldType?, model: SolrFieldModel): List<String> {
    val traits = model.traitsOf(fieldType)
    return SolrFieldProperties.FOR_FIELD.mapNotNull { property ->
        val meaning = property.meaning ?: return@mapNotNull null
        if (meaning.inlineWhenTrue == null) return@mapNotNull null
        when (SolrFieldProperties.resolve(property, field, fieldType, model.schemaVersion, traits).value) {
            "true" -> meaning.inlineWhenTrue
            "false" -> meaning.inlineWhenFalse
            else -> null
        }
    }
}
```

Update the call in `collectFromElement`:

```kotlin
val parts = hintFor(field, model.typeOf(field), model) ?: return
```

Add imports: `org.apache.solr.ide.model.SolrFieldModel` and `org.apache.solr.ide.model.SolrFieldProperties`.

Note `storageShape` returns a possibly-empty list, and `hintFor` can therefore return an empty list — for a field whose chain is unconfident *and* whose every property is undetermined. Guard the sink call in `collectFromElement` so an empty hint is not rendered as an empty inlay:

```kotlin
val parts = hintFor(field, model.typeOf(field), model)?.takeIf { it.isNotEmpty() } ?: return
```

- [ ] **Step 5: Update the provider's class KDoc**

The class comment currently says nothing is shown where the analysis is not confident, which this task falsifies. Replace that paragraph:

```kotlin
 * Nothing is shown where the field's type is undeclared: property resolution is three-tier, and a
 * missing type removes the middle tier without removing the fall-through, so every default would be
 * attributed to Solr when the type that might have overridden it does not exist.
 *
 * Where [org.apache.solr.ide.model.SolrMatchAnalysis] is not confident the match half is dropped and
 * the storage shape stands alone. An unrecognized factory means the chain was not fully understood,
 * and a wrong claim about what a field matches is worse than no claim — but it says nothing about
 * `stored` or `multiValued`, and withholding those was withholding a fact the plugin is certain of.
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
./gradlew test --tests "*.SolrMatchInlayHintsProviderTest"
```

Expected: PASS.

If a `doc values` / `no doc values` assertion fails, the cause is almost certainly the schema version rather than the code. The `docValues` default turns on schema version 1.7, the fixture declares no `version`, and `SolrSchemaVersion.ASSUMED` is 1.0 — so every field in this fixture resolves to `no doc values`. The demo configset declares `1.6` and behaves the same way; only a `1.7` schema flips it. Fix the *expectation* and record the reason in the test's KDoc, as the two tests above already do.

- [ ] **Step 7: Run the full build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL. Watch for `SolrConfigsetDocumentationProviderTest` failures — there should be none, since Task 3 already covered the popup.

- [ ] **Step 8: Verify in the sandbox**

```bash
./gradlew runIde
```

Open `demo/solr/conf/managed-schema.xml`. Confirm all seven fields carry match parts followed by four property phrases, and that `text` reads `multi-valued, not stored`. Close the sandbox.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/org/apache/solr/ide/configset/hint/SolrMatchInlayHintsProvider.kt \
        src/test/kotlin/org/apache/solr/ide/configset/hint/SolrMatchInlayHintsProviderTest.kt
git commit -s -m "feat: state a field's storage shape inline beside what it matches

A field that is searchable but not returnable is the most common cause of
'my query works but the field is missing from the response', and inline it
looked identical to one that is both.

Two changes to when a hint appears at all. An unrecognised factory no longer
silences everything: property values are read from attributes and from
version and class defaults, and never depended on the analyser chain, so
such a field gets the storage half alone. An undeclared type still silences
the hint entirely — resolution is three-tier, and a missing type removes the
middle tier without removing the fall-through, so every default would be
attributed to Solr when the type that might have overridden it does not
exist.

Silence for an unresolvable property is now per property rather than per
hint, which is the granularity the underlying facts have."
```

---

### Task 5: The documentation catches up

**Files:**
- Modify: `docs/screenshots.md`
- Modify: `docs/manual-test-suite.md`
- Move: `docs/design/pending/field-property-explanations.md` and `field-property-explanations-plan.md` → `docs/design/archive/2026-08-02-field-property-explanations/design.md` and `plan.md`

**Interfaces:**
- Consumes: the rendered behaviour from Tasks 3 and 4. Do this task last — the screenshot instructions must describe what the code now does.

- [ ] **Step 1: Rewrite screenshot catalog entry 1**

In `docs/screenshots.md`, replace entry 1's **Shows**, **Capture** and **Redo when** paragraphs:

```markdown
**Shows** the feature nothing else in the ecosystem does — what each field can *actually* match —
and, beside it, the storage shape that decides whether a matched document can be returned at all.
Inline, without a hover. This is the lead image for the README.

**Capture** the field block at `managed-schema.xml:47-53` with no interaction at all — the hints
render themselves. Frame all seven fields so both contrasts are visible in one shot: `id`/`sku`/
`category` read as whole-value and case-sensitive, `name`/`description`/`text` as tokenised and
case-insensitive, `name_prefix` as prefix-capable; and `description`/`name_prefix`/`text` read as
`not stored` where the rest read `stored`, with `text` alone reading `multi-valued`.

**Redo when** the hint wording changes, a property's inline phrase changes, a new analysis
capability is recognised, or the demo schema's field list changes.
```

- [ ] **Step 2: Fix the two stale line references**

Both predate a change to the demo schema and are wrong today, independently of this feature:

- Entry 1 cited `managed-schema.xml:40-46` for the field block. It is at 47-53. Step 1 already corrects it.
- Entry 2 cites `managed-schema.xml:44` for `category`. It is at line 51. Change it.

- [ ] **Step 3: Drop the EdgeNGram claim from entry 1**

The old entry said `name_prefix` "names EdgeNGram as the mechanism rather than claiming `prefix: true`". The hint does not do that — `SolrMatchCapability.summaryParts` emits the bare phrase `prefix-capable`, and naming the factory happens in `SolrFieldPresentation.prefixMechanism`, which only the popup calls. Step 1's replacement text already says `prefix-capable`; confirm no other sentence in entry 1 still claims otherwise.

- [ ] **Step 4: Update the HINT block in the manual test suite**

In `docs/manual-test-suite.md`, replace HINT-1 through HINT-3 and add HINT-5:

```markdown
- [ ] **HINT-1** — `string` fields (`id`, `sku`, `category`) read as whole-value,
      case-sensitive, and carry `indexed, stored, single-valued`.
- [ ] **HINT-2** — `text_general` fields (`description`, `text`) read as tokenised,
      case-insensitive, with no efficient prefix support. `text` alone reads
      `multi-valued`; both read `not stored`… except `description`, which is stored.
- [ ] **HINT-3** — `name_prefix` (type `text_prefix`) reads as prefix-capable and
      `not stored`.
- [ ] **HINT-4** — Hints sit inline beside the declaration (no hover needed), readable
      at presentation font size.
- [ ] **HINT-5** — A field whose analyser chain contains an unrecognised factory shows
      the storage-shape phrases and no match claim; a field whose `type` is undeclared
      shows no hint at all.
```

Verify the `description` clause in HINT-2 against the demo schema before committing — it declares `stored="true"`, unlike `name_prefix` and `text`. If the schema has changed, describe what it actually says.

HINT-4 needs no rewording but is now the check that matters most: it asks that the hint be readable at presentation font size, and this change roughly doubles its length.

- [ ] **Step 5: Archive the design record**

```bash
mkdir -p docs/design/archive/2026-08-02-field-property-explanations
git mv docs/design/pending/field-property-explanations.md \
       docs/design/archive/2026-08-02-field-property-explanations/design.md
git mv docs/design/pending/field-property-explanations-plan.md \
       docs/design/archive/2026-08-02-field-property-explanations/plan.md
```

`docs/design/README.md` says a record moves from `pending/` to `archive/` when its feature merges, **not before**. If this plan is being executed on a branch that has not merged, skip this step and do it in the merge commit instead.

- [ ] **Step 6: Update the plan of record**

`specs/plans/0002-solr-intellij-plugin-plan.md` is the only file that owns what is built. Add this feature as a completed step, following the format of the steps already there. Do not mirror its status into `CLAUDE.md`, the API reference or the specification — the project rule is that position changes every step and a copy goes stale.

- [ ] **Step 7: Verify nothing else claims the old behaviour**

```bash
grep -rn "prefix: true\|EdgeNGram as the mechanism" docs/ README.md
grep -rn "40-46\|managed-schema.xml:44" docs/
```

Expected: no hits. Any that remain are the same stale claim in another file.

- [ ] **Step 8: Commit**

```bash
git add docs/ specs/
git commit -s -m "docs: describe the hint that now carries storage shape

Screenshot entry 1 and HINT-1..3 both described a match-only hint. Entry 1
also cited the field block at lines 40-46 and category at line 44; they are
at 47-53 and 51, stale since the demo schema last changed.

Entry 1's claim that the hint 'names EdgeNGram as the mechanism' was never
true of the hint — summaryParts emits the bare phrase prefix-capable, and
naming the factory happens in prefixMechanism, which only the popup calls.

HINT-5 is new: the suite did not exercise an unrecognised factory at all,
which is the case whose behaviour this change reverses."
```

---

## Notes for the executor

**The one thing to get right.** Every silence in this feature is deliberate and each has a different reason. An unconfident chain drops the match half only. An undeclared type drops everything. An undetermined property drops one phrase. A non-boolean property has no phrase to drop. Four cases, four reasons — if a test looks like it is asserting the same thing twice, read the KDoc above it before deleting either.

**Where a wrong answer costs most.** `SolrFieldProperties`' own KDoc says a type naming a class the catalog does not carry is "exactly where asserting a default would be inventing one". Task 2's null-versus-empty-set test is the guard for that, and Task 4's `testAPropertyWithNoAnswerContributesNoPhrase` is the same guard one layer up. Neither is optional.

**If every fixture test fails at once with `FileDeletedException`** while the plain JUnit tests stay green, the sandbox VFS is corrupt rather than the code. It survives `./gradlew clean`. Delete `.intellijPlatform/sandbox/<project>/<IDE>/system-test` and re-run.

---

## Corrections found during implementation

Six errors surfaced while executing this plan, none of which changed the design — all are corrections to what the plan *said*, not to what shipped. Four are worth reading before you trust any code snippet above; the last two are staleness in the prose.

**They share a shape.** Every one is a claim about a *resolved* value, written by reading the property table rather than by tracing `SolrFieldProperties.resolve` through its tiers. Resolution consults the field, then the field type, then a version-conditional default, then a class-trait rule — and a claim written from any single tier is wrong whenever an earlier one fires. That is the exact confusion this feature exists to end for users, and it caught the plan six times.

1. **`SolrField`'s constructor was called positionally with the wrong third argument.** The plan's test snippet wrote `SolrField("sku", "string", mapOf(...))`, intending the map as the field's attributes. The third positional parameter is actually `indexed: Boolean?`; a map there does not compile. Fixed by passing `attributes = mapOf(...)` as a named argument.

2. **The plan's expected hint strings ordered `multiValued` before `docValues`.** `SolrFieldProperties.ALL` (and therefore `FOR_FIELD`) declares `docValues` before `multiValued`, so every rendered hint reads `..., doc values, single-valued` or `..., no doc values, multi-valued`, never the reverse. This error was pervasive enough to reach the shipped screenshot and manual-test-suite prose too, and Task 5 corrected it there as well.

3. **The plan assumed a versionless test fixture defaults `multiValued` to `false`.** `multiValued`'s rule is `defaultTrueWithin(below = 1.1f)`, and `SolrSchemaVersion.ASSUMED` (used when a `<schema>` declares no `version`) is `1.0` — below 1.1 — so an unversioned fixture defaults `multiValued` to **true**, the same version-conditional trap `docValues` sets for the same fixture. The demo schema declares `version="1.6"`, well above 1.1, so this does not affect it.

4. **The plan did not anticipate that dropping the match-only silence rule would break `testEachHintSegmentFitsTheRenderersInlineBudget`.** That pre-existing test asserted a segment budget sized for a match-only hint; once storage-shape phrases render unconditionally, the longest hint is longer. The test was amended to size its budget against the new combined hint rather than loosened, so it still catches a genuine regression in segment length.

5. Found while writing Task 5's documentation rather than while coding: the design record's own worked example (the "Rendered against `demo/solr/conf/managed-schema.xml`" block) claimed every one of the seven demo fields reads `no doc values`. That was never true of `id`, `sku` and `category` — the demo's `string` fieldType declares `docValues="true"` directly on the `fieldType` element, and `SolrFieldProperties.resolve` reads a fieldType-level attribute before it ever reaches the schema-version default that the design record's prose describes. Those three fields read `doc values`, not `no doc values`. Task 5 corrected the rendered block, the prose explaining it, and every other document that repeated the assumption.

6. **The plan says "all seven fields" in two places** — Task 4's sandbox check and Task 5's draft of the screenshot catalog entry. The demo schema declares **nine** after this branch: the human ruling on HINT-5 baked in `notes` (an unrecognisable tokenizer, so no match half) and `legacy` (an undeclared type, so no hint at all) rather than asking a tester to add and undo them each pass. The shipped documents count nine; only this plan still says seven.
