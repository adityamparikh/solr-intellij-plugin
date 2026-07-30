# Class-Value Hover Documentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hovering `class="solr.StrField"` — or any `tokenizer`/`filter`/`charFilter` class value — answers with quick documentation built from the class catalog and the field model.

**Architecture:** A fourth target arm in the existing `SolrConfigsetDocumentationProvider`, fed by a new shared tag→kind mapping (`SolrClassKind.forTag`), a schema-specifics helper in `SolrSchemaElements`, and a popup builder in `SolrFieldPresentation`. No new extension points, no new files except tests. Spec: `docs/superpowers/specs/2026-07-29-class-value-hover-docs-design.md`.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (PSI, `AbstractDocumentationProvider`), Gradle, JUnit 4 (two conventions — see constraints).

**Spec deviations (deliberate, cosmetic):** the spec sketches `Target.Class(name, kind)`. The implementation uses `Target.SchemaClass(name)` — `Class` collides with `java.lang.Class` in readers' heads, and the `kind` parameter is dropped because the catalog entry's own `kind` is the authority for rendering and links.

## Global Constraints

- Build/test everything with `./gradlew build` (jvmToolchain 21). Single test class: `./gradlew test --tests "*.SolrClassCatalogTest"`.
- **Dokka gate:** every *public* declaration in `src/main/kotlin` needs KDoc or the build fails (`reportUndocumented` + `failOnWarning`). `internal` is exempt. KDoc reads `[foo]` as a symbol link; never use `[text][ref]` reference-style links.
- **Kover gate:** 80% line coverage floor, bound to `check`.
- Nothing in `org.apache.solr.ide.model` may import an IntelliJ type.
- Everything stays dumb-aware and index-free; nothing on the editor path contacts a server.
- The popup must never assert something false about a correct file; when nothing true can be said, say nothing.
- **Test conventions:** anything touching PSI extends `SolrConfigsetTestCase` and is JUnit 3-style — methods MUST be named `testSomething()`, discovered by prefix, no `@Test`. Pure-model tests are plain JUnit 4 with `@Test` and backtick names.
- If every fixture test suddenly fails with `FileDeletedException` while plain JUnit tests stay green, the sandbox VFS is corrupt, not your code: delete `.intellijPlatform/sandbox/<project>/<IDE>/system-test` and re-run.
- Commits: conventional subjects, sign-off (`git commit -s`), and end the body with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Work in this worktree on branch `feat/class-value-hover-docs`; verify with `git branch --show-current` before the first commit.

---

### Task 1: One tag→kind mapping, shared by completion and documentation

**Files:**
- Modify: `src/main/kotlin/org/apache/solr/ide/model/SolrClassCatalog.kt` (the `SolrClassKind` enum, lines 8–21)
- Modify: `src/main/kotlin/org/apache/solr/ide/configset/completion/SolrConfigsetCompletionContributor.kt` (two private mappings, ~line 142 in `classNames` and ~line 299 in `analysisAttributeNames`)
- Test: `src/test/kotlin/org/apache/solr/ide/model/SolrClassCatalogTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `SolrClassKind.forTag(tagName: String): SolrClassKind?` — used by Task 4 and by both completion call sites.

- [ ] **Step 1: Write the failing tests**

Append to `SolrClassCatalogTest` (plain JUnit 4; add `org.junit.Assert.assertEquals` / `assertNull` imports if absent):

```kotlin
@Test
fun `every schema element carrying a class maps to its kind`() {
    assertEquals(SolrClassKind.FIELD_TYPE, SolrClassKind.forTag("fieldType"))
    assertEquals(SolrClassKind.FIELD_TYPE, SolrClassKind.forTag("fieldtype"))
    assertEquals(SolrClassKind.TOKENIZER, SolrClassKind.forTag("tokenizer"))
    assertEquals(SolrClassKind.TOKEN_FILTER, SolrClassKind.forTag("filter"))
    assertEquals(SolrClassKind.CHAR_FILTER, SolrClassKind.forTag("charFilter"))
}

@Test
fun `an element that carries no class maps to nothing`() {
    assertNull(SolrClassKind.forTag("copyField"))
    assertNull(SolrClassKind.forTag("analyzer"))
    assertNull(SolrClassKind.forTag("field"))
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests "*.SolrClassCatalogTest"`
Expected: compilation failure — `forTag` unresolved.

- [ ] **Step 3: Implement `forTag`**

In `SolrClassCatalog.kt`, give `SolrClassKind` a companion (inside the enum body, after the `CHAR_FILTER` entry — note the `;` after the last constant):

```kotlin
    /** The `<charFilter>`, applied to the raw text before tokenization. */
    CHAR_FILTER("charFilter");

    /** Lookup from the schema's element vocabulary. */
    companion object {

        /**
         * The kind of class [tagName]'s `class` attribute names, or null when the element does
         * not carry one.
         *
         * Both spellings of `fieldType` are accepted, as Solr accepts both. This is the single
         * mapping from schema vocabulary to catalog population; completion and documentation both
         * read it, so a kind added to one cannot silently be missed by the other.
         *
         * @param tagName an element name as written in a schema
         * @return the kind, or null
         */
        fun forTag(tagName: String): SolrClassKind? = when (tagName) {
            "fieldType", "fieldtype" -> FIELD_TYPE
            "tokenizer" -> TOKENIZER
            "filter" -> TOKEN_FILTER
            "charFilter" -> CHAR_FILTER
            else -> null
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "*.SolrClassCatalogTest"`
Expected: PASS.

- [ ] **Step 5: Switch both completion sites to the shared mapping**

In `SolrConfigsetCompletionContributor.kt`, `classNames` (~line 142), replace:

```kotlin
        val kind = when (tagName) {
            in SolrSchemaTags.FIELD_TYPE -> SolrClassKind.FIELD_TYPE
            "tokenizer" -> SolrClassKind.TOKENIZER
            "filter" -> SolrClassKind.TOKEN_FILTER
            "charFilter" -> SolrClassKind.CHAR_FILTER
            else -> return emptyList()
        }
```

with:

```kotlin
        val kind = SolrClassKind.forTag(tagName) ?: return emptyList()
```

In `analysisAttributeNames` (~line 299), replace:

```kotlin
        val kind = when (tag.name) {
            "tokenizer" -> SolrClassKind.TOKENIZER
            "filter" -> SolrClassKind.TOKEN_FILTER
            "charFilter" -> SolrClassKind.CHAR_FILTER
            else -> return null
        }
```

with:

```kotlin
        // A fieldType names a class too, but its attributes are the field properties, and the
        // property path owns those — this null keeps the fall-through that gets them offered.
        val kind = SolrClassKind.forTag(tag.name)?.takeIf { it != SolrClassKind.FIELD_TYPE }
            ?: return null
```

Do not remove the `SolrSchemaTags` import — the property paths still use it.

- [ ] **Step 6: Run the completion tests to verify no behaviour changed**

Run: `./gradlew test --tests "*.SolrSchemaVocabularyCompletionTest" --tests "*.SolrClassCatalogTest"`
Expected: PASS — the completion tests are the guard that the hoist preserved both sites' behaviour, including the fieldType exclusion.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -s -m "feat: one mapping from schema tag to catalog kind

Completion carried two private copies of tokenizer/filter/charFilter to
kind; documentation is about to need a third. SolrClassKind.forTag is
the one copy all of them read, so a kind added to completion cannot be
missed by documentation. The analysis-attribute site keeps excluding
FIELD_TYPE on purpose: a fieldType's attributes are field properties.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: The schema-specific half — which field types use this class

**Files:**
- Modify: `src/main/kotlin/org/apache/solr/ide/configset/documentation/SolrSchemaElements.kt`
- Test: `src/test/kotlin/org/apache/solr/ide/configset/documentation/SolrSchemaElementsTest.kt`

**Interfaces:**
- Consumes: `SolrClassEntry` (`kind`, `className`, `shortName`) and `SolrClassKind` from the model; `count(n, noun)` already private in this object.
- Produces: `SolrSchemaElements.classSpecifics(entry: SolrClassEntry, model: SolrFieldModel): String?` — used by Task 4.

- [ ] **Step 1: Write the failing tests**

Append to `SolrSchemaElementsTest` (plain JUnit 4). Add imports `org.apache.solr.ide.model.SolrClassEntry` and `org.apache.solr.ide.model.SolrClassKind`, and these fixtures at the bottom of the class:

```kotlin
    private val strField = SolrClassEntry(
        SolrClassKind.FIELD_TYPE,
        "org.apache.solr.schema.StrField",
        "solr.StrField",
    )

    private val edgeNGram = SolrClassEntry(
        SolrClassKind.TOKEN_FILTER,
        "org.apache.lucene.analysis.ngram.EdgeNGramFilterFactory",
        "solr.EdgeNGramFilterFactory",
    )
```

and the tests:

```kotlin
    @Test
    fun `a field type class reports the types declared with it, under either spelling`() {
        val model = SolrFieldModel.of(
            SolrConfigsetFacts(
                fieldTypes = listOf(
                    SolrFieldType("string", "solr.StrField"),
                    SolrFieldType("strings", "org.apache.solr.schema.StrField"),
                    SolrFieldType("text_general", "solr.TextField"),
                ),
            ),
        )
        val specifics = SolrSchemaElements.classSpecifics(strField, model)
        assertNotNull(specifics)
        assertTrue("expected a count of both spellings: $specifics", specifics!!.contains("2 field types"))
        assertTrue(specifics.contains("<code>string</code>"))
        assertTrue(specifics.contains("<code>strings</code>"))
    }

    @Test
    fun `a factory class has no usage line`() {
        assertNull(SolrSchemaElements.classSpecifics(edgeNGram, SolrFieldModel.of(SolrConfigsetFacts())))
    }

    @Test
    fun `a class no field type uses says nothing rather than counting zero`() {
        assertNull(SolrSchemaElements.classSpecifics(strField, SolrFieldModel.of(SolrConfigsetFacts())))
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests "*.SolrSchemaElementsTest"`
Expected: compilation failure — `classSpecifics` unresolved.

- [ ] **Step 3: Implement `classSpecifics`**

In `SolrSchemaElements.kt`, add imports `org.apache.solr.ide.model.SolrClassEntry` and `org.apache.solr.ide.model.SolrClassKind`, and add after the `specifics` function:

```kotlin
    /**
     * What this schema does with the class [entry] names, or null when nothing specific is known.
     *
     * Only field type classes get a usage line. A factory is hovered inside the very chain that
     * uses it, so "used here" would state what the reader is looking at; which *field types* were
     * declared with a class is the fact spread across the file that a hover can gather.
     *
     * @param entry the catalog entry for the hovered class
     * @param model the configset's model
     * @return a sentence about this schema's use of the class, or null
     */
    fun classSpecifics(entry: SolrClassEntry, model: SolrFieldModel): String? {
        if (entry.kind != SolrClassKind.FIELD_TYPE) return null
        val users = model.fieldTypes.values.map { it.effective }
            .filter { it.className == entry.shortName || it.className == entry.className }
            .map { it.name }
        if (users.isEmpty()) return null
        return "Used by ${count(users.size, "field type")}: " +
            users.joinToString(", ") { "<code>$it</code>" } + "."
    }
```

(`SolrSchemaElements` is an `internal object`, so no Dokka exposure; KDoc is still written because the object documents every member.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "*.SolrSchemaElementsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -s -m "feat: the schema-specific half of class documentation

Which field types were declared with a class is the fact a reader
cannot see from inside one declaration; a factory gets no usage line
because it is hovered inside the very chain that uses it.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: The popup builder

**Files:**
- Modify: `src/main/kotlin/org/apache/solr/ide/configset/documentation/SolrFieldPresentation.kt`
- Test: `src/test/kotlin/org/apache/solr/ide/configset/documentation/SolrFieldPresentationTest.kt`

**Interfaces:**
- Consumes: `SolrClassEntry`, `SolrClassKind`, `SolrClassAttribute` (`name`, `valueType`), `SolrValueType` (BOOLEAN, INTEGER, FLOAT, ENUM, FREE), `SolrReferenceGuide.fieldTypesPage` / `analyzerComponentPage`, and the object's private `escape`.
- Produces: `SolrFieldPresentation.classDocumentation(entry: SolrClassEntry, specifics: String?, version: SolrVersionSelection): String` — used by Task 4.

- [ ] **Step 1: Write the failing tests**

Append to `SolrFieldPresentationTest` (plain JUnit 4). Add imports `org.apache.solr.ide.model.SolrClassAttribute`, `org.apache.solr.ide.model.SolrClassEntry`, `org.apache.solr.ide.model.SolrClassKind`, `org.apache.solr.ide.model.SolrValueType` (plus `org.junit.Assert.assertFalse` if absent), and fixtures:

```kotlin
    private val strFieldEntry = SolrClassEntry(
        SolrClassKind.FIELD_TYPE,
        "org.apache.solr.schema.StrField",
        "solr.StrField",
        listOf(SolrClassAttribute("docValuesFormat")),
    )

    private val edgeNGramEntry = SolrClassEntry(
        SolrClassKind.TOKEN_FILTER,
        "org.apache.lucene.analysis.ngram.EdgeNGramFilterFactory",
        "solr.EdgeNGramFilterFactory",
        listOf(
            SolrClassAttribute("maxGramSize", SolrValueType.INTEGER),
            SolrClassAttribute("preserveOriginal", SolrValueType.BOOLEAN),
        ),
    )
```

and the tests:

```kotlin
    @Test
    fun `a class popup names the kind and both spellings`() {
        val html = SolrFieldPresentation.classDocumentation(strFieldEntry, null, SolrVersionSelection.DEFAULT)
        assertTrue(html.contains("solr.StrField"))
        assertTrue("the kind belongs in the definition: $html", html.contains("field type class"))
        assertTrue(html.contains("org.apache.solr.schema.StrField"))
    }

    @Test
    fun `a class popup lists the attributes the class accepts with their value types`() {
        val html = SolrFieldPresentation.classDocumentation(edgeNGramEntry, null, SolrVersionSelection.DEFAULT)
        assertTrue(html.contains("maxGramSize"))
        assertTrue("an int attribute reads as a whole number: $html", html.contains("whole number"))
        assertTrue("a bool attribute reads as true or false: $html", html.contains("true or false"))
    }

    @Test
    fun `a class with no known attributes claims nothing about them`() {
        val bare = SolrClassEntry(SolrClassKind.TOKENIZER, "org.example.T", "solr.T")
        val html = SolrFieldPresentation.classDocumentation(bare, null, SolrVersionSelection.DEFAULT)
        assertFalse("no attribute table for an empty list: $html", html.contains("Accepts"))
    }

    @Test
    fun `a factory popup links the guide page for its kind`() {
        val html = SolrFieldPresentation.classDocumentation(edgeNGramEntry, null, SolrVersionSelection.DEFAULT)
        assertTrue("expected the filters page: $html", html.contains("/indexing-guide/filters.html"))
    }

    @Test
    fun `a field type class popup links the field types page`() {
        val html = SolrFieldPresentation.classDocumentation(strFieldEntry, null, SolrVersionSelection.DEFAULT)
        assertTrue("expected the field types page: $html", html.contains("/indexing-guide/field-types-included-with-solr.html"))
    }

    @Test
    fun `specifics render under the configset heading`() {
        val html = SolrFieldPresentation.classDocumentation(
            strFieldEntry,
            "Used by 1 field type: <code>string</code>.",
            SolrVersionSelection.DEFAULT,
        )
        assertTrue(html.contains("In this configset:"))
        assertTrue(html.contains("<code>string</code>"))
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests "*.SolrFieldPresentationTest"`
Expected: compilation failure — `classDocumentation` unresolved.

- [ ] **Step 3: Implement the builder**

In `SolrFieldPresentation.kt`, add imports `org.apache.solr.ide.model.SolrClassEntry`, `org.apache.solr.ide.model.SolrClassKind`, `org.apache.solr.ide.model.SolrValueType`. Add after `fieldTypeDocumentation`:

```kotlin
    /**
     * The documentation popup for a class named in a `class` attribute.
     *
     * What it renders is what the generated catalog and the model can vouch for: the kind of
     * class, both of its spellings, the attributes its constructor actually reads, and what this
     * schema declared with it. There is deliberately no prose paragraph yet — that column joins
     * the catalog when the `-sources` artifacts are resolved, and inventing one meanwhile would
     * be asserting something no source states.
     *
     * @param entry the catalog entry for the class
     * @param specifics what this schema does with it, or null when nothing specific is known
     * @param version the Solr line this configset targets, for the guide link
     * @return HTML for the documentation popup
     */
    fun classDocumentation(
        entry: SolrClassEntry,
        specifics: String?,
        version: SolrVersionSelection,
    ): String = buildString {
        append("<div class='definition'><pre>")
        append("<b>${escape(entry.shortName)}</b> — ${kindText(entry.kind)}")
        append("\n${escape(entry.className)}")
        append("</pre></div>")
        append("<div class='content'>")
        if (entry.attributes.isNotEmpty()) {
            append("<p><b>Accepts</b></p><table>")
            for (attribute in entry.attributes) {
                append("<tr><td><code>${escape(attribute.name)}</code></td>")
                append("<td>${valueTypeText(attribute.valueType)}</td></tr>")
            }
            append("</table>")
        }
        // Not escaped: the specifics are built by this plugin from model values and carry markup
        // of their own, the same contract elementDocumentation documents.
        specifics?.let { append("<p><b>In this configset:</b> $it</p>") }
        append("</div>")
        append(classGuideLink(entry, version))
    }

    /** The kind in words, for the definition line. */
    private fun kindText(kind: SolrClassKind): String = when (kind) {
        SolrClassKind.FIELD_TYPE -> "field type class"
        SolrClassKind.TOKENIZER -> "tokenizer factory"
        SolrClassKind.TOKEN_FILTER -> "token filter factory"
        SolrClassKind.CHAR_FILTER -> "character filter factory"
    }

    /**
     * The value type in words, or empty for a free-form attribute.
     *
     * Empty rather than "any value": the catalog's FREE means the generator could not narrow the
     * type, which is weaker than a promise that anything is legal.
     */
    private fun valueTypeText(type: SolrValueType): String = when (type) {
        SolrValueType.BOOLEAN -> "true or false"
        SolrValueType.INTEGER -> "a whole number"
        SolrValueType.FLOAT -> "a decimal number"
        SolrValueType.ENUM -> "one of a closed set"
        SolrValueType.FREE -> ""
    }

    /**
     * The guide footer for a class popup — one page, chosen by what the class is.
     *
     * The shared [guideLinks] footer names the two field pages, which are the wrong destination
     * for a tokenizer; a link that lands somewhere unrelated teaches a reader to stop clicking.
     */
    private fun classGuideLink(entry: SolrClassEntry, version: SolrVersionSelection): String {
        val url = when (entry.kind) {
            SolrClassKind.FIELD_TYPE -> SolrReferenceGuide.fieldTypesPage(version)
            else -> SolrReferenceGuide.analyzerComponentPage(entry.className, version)
        } ?: return ""
        val label = when (entry.kind) {
            SolrClassKind.FIELD_TYPE -> "Field types included with Solr"
            SolrClassKind.TOKENIZER -> "Tokenizers in the Reference Guide"
            SolrClassKind.TOKEN_FILTER -> "Filters in the Reference Guide"
            SolrClassKind.CHAR_FILTER -> "Char filter factories in the Reference Guide"
        }
        return "<div class='bottom'><p><a href='$url'>$label</a></p>" +
            "<p><small>Reference Guide for ${escape(version.describeSource())}.</small></p></div>"
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "*.SolrFieldPresentationTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -s -m "feat: render the class documentation popup

Kind, both spellings, the attributes the constructor actually reads,
and the schema's own usage. No prose paragraph on purpose: that column
joins the catalog with the -sources artifacts, and inventing one
meanwhile would assert something no source states.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: The provider arm — hover answers on class values

**Files:**
- Modify: `src/main/kotlin/org/apache/solr/ide/configset/documentation/SolrConfigsetDocumentationProvider.kt`
- Test: `src/test/kotlin/org/apache/solr/ide/configset/documentation/SolrConfigsetDocumentationProviderTest.kt`

**Interfaces:**
- Consumes: `SolrClassKind.forTag` (Task 1), `SolrSchemaElements.classSpecifics` (Task 2), `SolrFieldPresentation.classDocumentation` (Task 3), `SolrClassCatalog.find(name, version)`, existing `versionOf(model)` and `documentedTarget(value)`.
- Produces: user-visible hover documentation; no new API.

- [ ] **Step 1: Write the failing fixture tests**

Append to `SolrConfigsetDocumentationProviderTest` (JUnit 3-style: `test`-prefixed names, no `@Test`). The shared `schema` fixture already contains `solr.StrField`, `solr.StandardTokenizerFactory` and `solr.LowerCaseFilterFactory`; `caretInside(word)` puts the caret mid-word, and `docAtCaret` returns null when nothing documents.

```kotlin
    fun testDocumentationIsOfferedOnAFieldTypesClassValue() {
        val doc = docAtCaret(caretInside("solr.StrField"))
        assertNotNull(doc)
        assertTrue("expected the kind in words: $doc", doc!!.contains("field type class"))
        assertTrue("expected the fully qualified name: $doc", doc.contains("org.apache.solr.schema.StrField"))
        assertTrue("expected the declaring type in the usage line: $doc", doc.contains("<code>string</code>"))
    }

    fun testDocumentationIsOfferedOnATokenizersClassValue() {
        val doc = docAtCaret(caretInside("solr.StandardTokenizerFactory"))
        assertNotNull(doc)
        assertTrue("expected the kind in words: $doc", doc!!.contains("tokenizer factory"))
        assertTrue("expected a catalog attribute: $doc", doc.contains("maxTokenLength"))
    }

    fun testDocumentationIsOfferedOnAFilterClassValue() {
        val doc = docAtCaret(caretInside("solr.LowerCaseFilterFactory"))
        assertNotNull(doc)
        assertTrue("expected the kind in words: $doc", doc!!.contains("token filter factory"))
    }

    /** The catalog matches both spellings, so the hover must too. */
    fun testAFullyQualifiedClassValueAnswersLikeItsShortForm() {
        val fqn = schema.replace("solr.StrField", "org.apache.solr.schema.StrField")
        val doc = docAtCaret(caretInside("org.apache.solr.schema.StrField", text = fqn))
        assertNotNull(doc)
        assertTrue("expected the short spelling: $doc", doc!!.contains("solr.StrField"))
    }

    /**
     * A class the catalog does not know gets no popup — the same contract an undeclared type
     * has. Saying nothing beats describing a class the plugin has never seen.
     */
    fun testAnUnknownClassValueOffersNothing() {
        val custom = schema.replace("solr.StrField", "com.example.CustomField")
        val doc = docAtCaret(caretInside("com.example.CustomField", text = custom))
        assertNull("an unknown class must not be described: $doc", doc)
    }

    /**
     * A class in the wrong position still gets documented as what it is. Naming its kind is
     * itself the honest answer; flagging the misplacement is the inspections' job, not the hover's.
     */
    fun testAMisplacedClassValueIsDocumentedAsWhatItIs() {
        val misplaced = schema.replace("class=\"solr.StrField\"", "class=\"solr.StandardTokenizerFactory\"")
        val doc = docAtCaret(caretInside("solr.StandardTokenizerFactory", text = misplaced))
        assertNotNull(doc)
        assertTrue("expected the actual kind: $doc", doc!!.contains("tokenizer factory"))
    }

    fun testExternalUrlForAFilterClassNamesTheFiltersPage() {
        givenSolrConfigAtFixtureRoot()
        myFixture.configureByText("managed-schema.xml", caretInside("solr.LowerCaseFilterFactory"))
        val element: PsiElement = provider.getCustomDocumentationElement(
            myFixture.editor,
            myFixture.file,
            myFixture.file.findElementAt(myFixture.caretOffset),
            myFixture.caretOffset,
        )!!
        val urls = provider.getUrlFor(element, element)
        assertNotNull(urls)
        assertTrue("expected the filters page: $urls", urls!!.single().endsWith("/indexing-guide/filters.html"))
    }
```

`caretInside` has no `text` parameter default change to make — it already accepts `text: String = schema`. If `assertNull` is not yet imported, it is inherited from the JUnit 3 base class — no import needed.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests "*.SolrConfigsetDocumentationProviderTest"`
Expected: FAIL — the class-value tests get the *element* documentation (the old fall-through) or null, not the class popup. `testAnUnknownClassValueOffersNothing` fails because the fall-through documents the `fieldType` element.

- [ ] **Step 3: Implement the provider arm**

In `SolrConfigsetDocumentationProvider.kt`:

1. Add imports `org.apache.solr.ide.model.SolrClassCatalog` and `org.apache.solr.ide.model.SolrClassKind`.

2. In `documentedTarget` (line ~197), add a branch before `else -> null` and extend the sealed interface:

```kotlin
    private fun documentedTarget(value: XmlAttributeValue): Target? {
        val attribute = value.parentOfType<XmlAttribute>() ?: return null
        val tag = attribute.parentOfType<XmlTag>() ?: return null
        val name = value.value.takeIf { it.isNotEmpty() } ?: return null
        return when {
            tag.name in SolrSchemaTags.FIELD && attribute.name == "type" -> Target.Type(name)
            tag.name in SolrSchemaTags.FIELD && attribute.name == "name" -> Target.Field(name)
            tag.name in SolrSchemaTags.FIELD_TYPE && attribute.name == "name" -> Target.Type(name)
            attribute.name == "class" && SolrClassKind.forTag(tag.name) != null -> Target.SchemaClass(name)
            else -> null
        }
    }

    private sealed interface Target {
        data class Field(val name: String) : Target
        data class Type(val name: String) : Target
        /** A `class` attribute's value — named `SchemaClass` so it cannot shadow `java.lang.Class`. */
        data class SchemaClass(val name: String) : Target
    }
```

3. In `generateDoc`'s `when` (line ~88), add the arm:

```kotlin
            is Target.SchemaClass -> {
                val entry = SolrClassCatalog.find(target.name, version) ?: return null
                SolrFieldPresentation.classDocumentation(
                    entry,
                    SolrSchemaElements.classSpecifics(entry, model),
                    version,
                )
            }
```

4. In `getUrlFor` (line ~112), rebind the `when` subject and add the arm:

```kotlin
        return when (val target = documentedTarget(value)) {
            is Target.Field -> listOf(SolrReferenceGuide.fieldPropertiesPage(version))
            is Target.Type -> listOf(SolrReferenceGuide.fieldTypesPage(version))
            is Target.SchemaClass -> classPage(target.name, version)?.let { listOf(it) }
            null -> null
        }
```

and add the helper after `documentedTarget`:

```kotlin
    /** The guide page for the class [name] refers to, or null when the catalog does not know it. */
    private fun classPage(name: String, version: SolrVersionSelection): String? {
        val entry = SolrClassCatalog.find(name, version) ?: return null
        return when (entry.kind) {
            SolrClassKind.FIELD_TYPE -> SolrReferenceGuide.fieldTypesPage(version)
            else -> SolrReferenceGuide.analyzerComponentPage(entry.className, version)
        }
    }
```

5. Update the class-level KDoc's first paragraph to include classes — replace the sentence starting "Quick documentation for fields and field types in a configset." with:

```
 * Quick documentation for fields, field types and `class` attribute values in a configset.
```

6. In the test file, the KDoc on `testAnUnknownAttributeFallsBackToItsElement` says the plugin "cannot yet name what belongs there" — no longer true of the value half. Replace that comment with:

```kotlin
    /**
     * An attribute the plugin has nothing to say about still falls back to its element.
     *
     * The caret here is on the *name* `class`, not its value. The value half now answers with
     * the named class itself; the name half keeps falling back to the element, which answers
     * the "what is this element" a reader mid-tag is actually asking.
     */
```

- [ ] **Step 4: Run the provider tests to verify they pass**

Run: `./gradlew test --tests "*.SolrConfigsetDocumentationProviderTest"`
Expected: PASS — including every pre-existing test; the element fall-through tests (`testAPositionInsideAnElementFallsBackToTheElement`, `testAnUnknownAttributeFallsBackToItsElement`) must stay green.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -s -m "feat: quick documentation on class attribute values

Hovering class=\"solr.StrField\" — or any tokenizer, filter or
charFilter class — now answers with the catalog's half: the kind,
both spellings, the attributes the constructor reads, and which field
types this schema declared with it. The prose paragraph waits for the
catalog's -sources column rather than being hand-written; an unknown
class gets silence rather than invention, the same contract an
undeclared type has.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Gates, plan record, and handoff

**Files:**
- Modify: `specs/plans/0002-solr-intellij-plugin-plan.md` (Step 10's opening paragraph, ~line 795)

**Interfaces:**
- Consumes: everything above.
- Produces: a green `./gradlew build` and the plan's record of what shipped.

- [ ] **Step 1: Run the full build — all gates**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — tests, the Kover 80% floor, and the Dokka documentation gate (new public API: `SolrClassKind.forTag`, `SolrFieldPresentation.classDocumentation` — both have KDoc from their tasks). If Dokka names an undocumented declaration, add KDoc to exactly that declaration and re-run.

- [ ] **Step 2: Record the shipped slice in the project plan**

In `specs/plans/0002-solr-intellij-plugin-plan.md`, Step 10's opening paragraph ends with "What remains here is the catalog-backed half, which is what the dependency below is about." Append one sentence to that paragraph:

```
Quick documentation on `class` values shipped ahead of the catalog's prose column: the popup
renders the catalog's kind, spellings and attributes plus the schema's own usage, and the
Javadoc summary waits on [Step 9's](#step-9-factory-catalog-generator-in-progress) `-sources`
resolution.
```

- [ ] **Step 3: Commit**

```bash
git add specs/plans/0002-solr-intellij-plugin-plan.md
git commit -s -m "docs: record the class-value hover in the plan

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

- [ ] **Step 4: Verify the branch is clean and fully committed**

Run: `git status --short && git log --oneline main..HEAD 2>/dev/null || git log --oneline -8`
Expected: no uncommitted changes; the branch holds the design doc, this plan, and one commit per task.
