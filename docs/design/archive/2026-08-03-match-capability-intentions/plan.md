# Prefix-Capable Companion Intention Implementation Plan

> **Shipped. This is a historical record, not a plan to execute.** The `_prefix` and `_exact`
> companion intentions exist; the checkboxes below are how the work was tracked at the time and are
> left unticked as written rather than back-filled. The instruction this plan opened with — implement
> it task-by-task — is preserved in the line beneath, and no longer applies.
>
> Two things it describes have since moved. The PSI half landed in
> `org.apache.solr.ide.configset.intention`, which the aspect split renamed to
> `org.apache.solr.ide.configset.schema.intention`; and `org.apache.solr.ide.model` is now
> `model.schema` for what a field is. Read package names here as the tree at the time of writing.
>
> *Original directive, retained for the record:* For agentic workers: REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement
> this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Alt-Enter intention on a schema `<field>` that generates the prefix-matching companion pattern — a companion field, a copy rule, and the edge-n-gram field type when the schema has none.

**Architecture:** The decision is a pure function over `SolrFieldModel` in `org.apache.solr.ide.model`, which imports nothing from IntelliJ and is therefore tested without a fixture. The PSI half is a thin `IntentionAction` in a new `org.apache.solr.ide.configset.intention` package that asks that function what to do and then writes it with `XmlElementFactory`.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, JUnit 4 (pure) and `BasePlatformTestCase` (PSI), Gradle.

## Global Constraints

Copied from the design record and from `CLAUDE.md`. Every task's requirements implicitly include this section.

- `./gradlew build` must pass. It runs the tests, the Kover 80% line floor and the Dokka documentation gate.
- **Every new public declaration in `src/main/kotlin` needs KDoc in the same change**, or Dokka fails the build by name. Tests are exempt.
- **Nothing in `org.apache.solr.ide.model` may import an IntelliJ type.** That rule is what keeps a third of the suite fixture-free.
- **Inspections must not fire on a correct file.** This work adds no inspection, and must not add one.
- The plugin edits configuration files directly and never refuses a write.
- Anything touching `SolrConfigsetSettings` or `SolrConnectionSettings` extends `SolrConfigsetTestCase`. Every fixture test in this plan does, because they all need the Solr-on-classpath gate that base class sets up.
- PSI tests are JUnit 3-style: methods must be named `testSomething()`. Pure tests are JUnit 4 with `@Test` and backtick names.
- Commit subjects are conventional-commit style and every commit is signed off (`git commit -s`).
- The generated field type is named `text_prefix`, with `minGramSize="2"` and `maxGramSize="15"`, and the edge-n-gram filter appears on the **index analyzer only**.
- The companion field is named `<source>_prefix` and is written `indexed="true" stored="false"`.

## File Structure

| File | Responsibility |
|---|---|
| `src/main/kotlin/org/apache/solr/ide/model/SolrPrefixCompanion.kt` | **Create.** The decision: whether to offer, which type to use, whether to write one. Pure. |
| `src/test/kotlin/org/apache/solr/ide/model/SolrPrefixCompanionTest.kt` | **Create.** Plain JUnit 4 over the above. |
| `src/main/kotlin/org/apache/solr/ide/configset/intention/SolrAddPrefixCompanionIntention.kt` | **Create.** The `IntentionAction`: caret → field → plan → PSI writes. |
| `src/test/kotlin/org/apache/solr/ide/configset/intention/SolrAddPrefixCompanionIntentionTest.kt` | **Create.** Fixture tests, availability and applied result. |
| `src/main/resources/META-INF/plugin.xml` | **Modify.** Register `<intentionAction>`. |
| `src/main/resources/messages/SolrBundle.properties` | **Modify.** Three intention strings. |
| `src/main/resources/intentionDescriptions/SolrAddPrefixCompanionIntention/description.html` | **Create.** Required by the platform. |
| `src/main/resources/intentionDescriptions/SolrAddPrefixCompanionIntention/before.xml.template` | **Create.** Required by the platform. |
| `src/main/resources/intentionDescriptions/SolrAddPrefixCompanionIntention/after.xml.template` | **Create.** Required by the platform. |
| `docs/Module.md` | **Modify.** `# Package` section for the new package. |
| `docs/manual-test-suite.md` | **Modify.** The sandbox gesture for this feature. |

---

### Task 1: The decision, as a pure function

**Files:**
- Create: `src/main/kotlin/org/apache/solr/ide/model/SolrPrefixCompanion.kt`
- Test: `src/test/kotlin/org/apache/solr/ide/model/SolrPrefixCompanionTest.kt`

**Interfaces:**
- Consumes: `SolrFieldModel.fields`, `SolrFieldModel.fieldTypes`, `SolrFieldModel.typeOf(SolrField)`, `SolrMatchAnalysis.of(SolrFieldType)`, `SolrPrefixSupport.NONE` — all existing.
- Produces: `SolrPrefixCompanionPlan(fieldName: String, companionName: String, typeName: String, generateType: Boolean)` and `SolrPrefixCompanion.planFor(model: SolrFieldModel, field: SolrField): SolrPrefixCompanionPlan?`. Task 2 depends on both names exactly.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/org/apache/solr/ide/model/SolrPrefixCompanionTest.kt`:

```kotlin
package org.apache.solr.ide.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether a field earns a prefix companion, and which type it would use.
 *
 * Plain JUnit 4: this reads the model and imports nothing from the platform, so booting a headless
 * IDE to exercise it would cost a second of wall-clock for nothing.
 */
class SolrPrefixCompanionTest {

    private val edgeNgramChain = SolrAnalyzerChain(
        tokenizer = SolrAnalyzerComponent("solr.StandardTokenizerFactory"),
        filters = listOf(
            SolrAnalyzerComponent("solr.LowerCaseFilterFactory"),
            SolrAnalyzerComponent("solr.EdgeNGramFilterFactory"),
        ),
    )

    private val plainChain = SolrAnalyzerChain(
        tokenizer = SolrAnalyzerComponent("solr.StandardTokenizerFactory"),
        filters = listOf(SolrAnalyzerComponent("solr.LowerCaseFilterFactory")),
    )

    private fun modelOf(
        fields: List<SolrField>,
        types: List<SolrFieldType>,
    ) = SolrFieldModel(
        fields = fields.associate { it.name to SolrFact(repository = it) },
        fieldTypes = types.associate { it.name to SolrFact(repository = it) },
    )

    @Test
    fun `reuses a declared edge n-gram type`() {
        val model = modelOf(
            fields = listOf(SolrField(name = "description", type = "text_general")),
            types = listOf(
                SolrFieldType("text_general", "solr.TextField", indexAnalyzer = plainChain),
                SolrFieldType("text_prefix", "solr.TextField", indexAnalyzer = edgeNgramChain),
            ),
        )

        val plan = SolrPrefixCompanion.planFor(model, model.fields.getValue("description").effective)

        assertEquals("description_prefix", plan?.companionName)
        assertEquals("text_prefix", plan?.typeName)
        assertEquals(false, plan?.generateType)
    }

    @Test
    fun `writes a type when the schema declares none`() {
        val model = modelOf(
            fields = listOf(SolrField(name = "description", type = "text_general")),
            types = listOf(SolrFieldType("text_general", "solr.TextField", indexAnalyzer = plainChain)),
        )

        val plan = SolrPrefixCompanion.planFor(model, model.fields.getValue("description").effective)

        assertEquals("text_prefix", plan?.typeName)
        assertEquals(true, plan?.generateType)
    }

    @Test
    fun `declines a field that already matches prefixes`() {
        val model = modelOf(
            fields = listOf(SolrField(name = "name_prefix", type = "text_prefix")),
            types = listOf(SolrFieldType("text_prefix", "solr.TextField", indexAnalyzer = edgeNgramChain)),
        )

        assertNull(SolrPrefixCompanion.planFor(model, model.fields.getValue("name_prefix").effective))
    }

    @Test
    fun `declines when the companion name is taken`() {
        val model = modelOf(
            fields = listOf(
                SolrField(name = "description", type = "text_general"),
                SolrField(name = "description_prefix", type = "text_general"),
            ),
            types = listOf(SolrFieldType("text_general", "solr.TextField", indexAnalyzer = plainChain)),
        )

        assertNull(SolrPrefixCompanion.planFor(model, model.fields.getValue("description").effective))
    }

    @Test
    fun `declines when the chain was not understood`() {
        val mystery = SolrAnalyzerChain(
            tokenizer = SolrAnalyzerComponent("com.example.MysteryTokenizerFactory"),
        )
        val model = modelOf(
            fields = listOf(SolrField(name = "description", type = "custom")),
            types = listOf(SolrFieldType("custom", "solr.TextField", indexAnalyzer = mystery)),
        )

        assertNull(SolrPrefixCompanion.planFor(model, model.fields.getValue("description").effective))
    }

    @Test
    fun `declines when the generated type name is taken by something else`() {
        val model = modelOf(
            fields = listOf(SolrField(name = "description", type = "text_general")),
            types = listOf(
                SolrFieldType("text_general", "solr.TextField", indexAnalyzer = plainChain),
                SolrFieldType("text_prefix", "solr.StrField"),
            ),
        )

        assertNull(SolrPrefixCompanion.planFor(model, model.fields.getValue("description").effective))
    }

    @Test
    fun `declines a field whose type is not declared`() {
        val model = modelOf(
            fields = listOf(SolrField(name = "orphan", type = "undeclared")),
            types = emptyList(),
        )

        assertNull(SolrPrefixCompanion.planFor(model, model.fields.getValue("orphan").effective))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "*.SolrPrefixCompanionTest"`
Expected: FAIL — compilation error, `SolrPrefixCompanion` is unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/org/apache/solr/ide/model/SolrPrefixCompanion.kt`:

```kotlin
package org.apache.solr.ide.model

/**
 * What an intention would add to give a field efficient prefix matching.
 *
 * A value rather than a set of loose strings, because the two halves have to agree: the field's
 * `type` attribute and the type that is written or reused are the same name, and separating them
 * invites a companion pointing at a type nobody declared.
 *
 * @property fieldName the field the companion is derived from
 * @property companionName the field to add
 * @property typeName the field type the companion will use
 * @property generateType whether that type has to be written, or already exists
 */
data class SolrPrefixCompanionPlan(
    val fieldName: String,
    val companionName: String,
    val typeName: String,
    val generateType: Boolean,
)

/**
 * Decides whether a field earns a prefix-matching companion, and what that companion would use.
 *
 * Pure, and deliberately separate from the intention that applies it: the interesting part is the
 * decision, and a decision that can only be tested by driving an editor does not get tested
 * exhaustively. The intention is left with PSI manipulation and nothing to reason about.
 *
 * **Silence is the default.** Every rule below returns null rather than approximating, on the same
 * terms [SolrMatchAnalysis] already sets: a suggestion the plugin cannot defend is worse than no
 * suggestion, because the user acts on it.
 */
object SolrPrefixCompanion {

    /** The suffix a companion field takes, and the name of the type written when none exists. */
    const val SUFFIX: String = "_prefix"

    /** The name given to a generated field type, used only when the schema declares no such name. */
    const val GENERATED_TYPE_NAME: String = "text_prefix"

    /**
     * The plan for [field], or null where no companion should be offered.
     *
     * Null in six cases, each a rule from the design record: the type is not declared, the chain was
     * not understood, the field already matches prefixes, the companion name is taken, or — in the
     * generation case — [GENERATED_TYPE_NAME] already names something that is not edge-n-gram-backed.
     *
     * @param model the configset's field model
     * @param field the field under the caret
     * @return what to add, or null to offer nothing
     */
    fun planFor(model: SolrFieldModel, field: SolrField): SolrPrefixCompanionPlan? {
        val fieldType = model.typeOf(field) ?: return null

        val capability = SolrMatchAnalysis.of(fieldType)
        if (!capability.confident) return null
        if (capability.prefix != SolrPrefixSupport.NONE) return null

        val companionName = field.name + SUFFIX
        if (model.fields.containsKey(companionName)) return null

        val existing = prefixTypesIn(model).firstOrNull()
        if (existing != null) {
            return SolrPrefixCompanionPlan(field.name, companionName, existing.name, generateType = false)
        }

        // No edge-n-gram type exists, so anything already holding the generated name is something
        // else. Renaming around it silently would produce a type the user did not ask for.
        if (model.fieldTypes.containsKey(GENERATED_TYPE_NAME)) return null

        return SolrPrefixCompanionPlan(field.name, companionName, GENERATED_TYPE_NAME, generateType = true)
    }

    /**
     * The declared field types whose index-time chain grinds terms into prefixes.
     *
     * Read through [SolrMatchAnalysis] rather than by looking for a factory name, so that a type
     * qualifies for exactly the reason the hint says it does — the two can never disagree about
     * what "prefix-capable" means.
     *
     * @param model the configset's field model
     * @return the qualifying types, in the order the schema declares them
     */
    fun prefixTypesIn(model: SolrFieldModel): List<SolrFieldType> =
        model.fieldTypes.values
            .map { it.effective }
            .filter { SolrMatchAnalysis.of(it).prefix == SolrPrefixSupport.EDGE_NGRAM }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "*.SolrPrefixCompanionTest"`
Expected: PASS, 7 tests.

- [ ] **Step 5: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. If Dokka fails, a declaration above is missing KDoc — add it rather than suppressing the gate.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/org/apache/solr/ide/model/SolrPrefixCompanion.kt \
        src/test/kotlin/org/apache/solr/ide/model/SolrPrefixCompanionTest.kt
git commit -s -m "feat: decide when a field earns a prefix-matching companion

The decision is a pure function over the field model so it can be tested
exhaustively without an IDE fixture, leaving the intention that applies it with
PSI manipulation and nothing to reason about.

Qualifying types are found through SolrMatchAnalysis rather than by looking for
an EdgeNGram factory by name, so a type qualifies for exactly the reason the
inline hint gives. The two cannot drift apart into disagreeing about what
prefix-capable means.

Six ways to return null, each a rule the design record argues: undeclared type,
unconfident analysis, a field that already matches prefixes, a taken companion
name, and a taken generated-type name. Silence is the default because a
suggestion the plugin cannot defend is worse than none -- the user acts on it."
```

---

### Task 2: The intention, reusing a declared type

**Files:**
- Create: `src/main/kotlin/org/apache/solr/ide/configset/intention/SolrAddPrefixCompanionIntention.kt`
- Create: `src/test/kotlin/org/apache/solr/ide/configset/intention/SolrAddPrefixCompanionIntentionTest.kt`
- Create: `src/main/resources/intentionDescriptions/SolrAddPrefixCompanionIntention/description.html`
- Create: `src/main/resources/intentionDescriptions/SolrAddPrefixCompanionIntention/before.xml.template`
- Create: `src/main/resources/intentionDescriptions/SolrAddPrefixCompanionIntention/after.xml.template`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/main/resources/messages/SolrBundle.properties`
- Modify: `docs/Module.md`

**Interfaces:**
- Consumes: `SolrPrefixCompanion.planFor`, `SolrPrefixCompanionPlan` from Task 1. `SolrConfigsetDetector.isConfigsetFile(PsiFile)` and `SolrConfigsetDetector.configsetFor(PsiFile)`, `SolrConfigsetReader.getInstance(project).modelFor(configset)`, `SolrSchemaTags.FIELD` / `.FIELD_TYPE` / `.COPY_FIELD` — all existing.
- Produces: `SolrAddPrefixCompanionIntention`, a registered `IntentionAction`. Task 3 extends its `invoke` with the generation branch.

**Note on scope:** this task deliberately handles only the reuse branch. `planFor` can return `generateType = true`, and this task's `invoke` throws nothing and writes nothing extra for it — Task 3 adds that branch. To keep the intermediate state honest rather than half-broken, `isAvailable` in this task returns false when `generateType` is true, and Task 3 removes that guard.

- [ ] **Step 1: Add the bundle strings**

Append to `src/main/resources/messages/SolrBundle.properties`:

```properties
intention.prefixCompanion.family=Add prefix-capable companion field
intention.prefixCompanion.reuse=Add prefix-capable companion field ({0})
intention.prefixCompanion.generate=Add prefix-capable companion field and its type
```

- [ ] **Step 2: Write the failing test**

Create `src/test/kotlin/org/apache/solr/ide/configset/intention/SolrAddPrefixCompanionIntentionTest.kt`:

```kotlin
package org.apache.solr.ide.configset.intention

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * When the companion intention offers itself, and what it writes.
 *
 * The negative cases carry more weight than the positive one. An intention that fails to appear is
 * a missing feature; one that appears where it should not is a suggestion the user acts on, and the
 * result is a schema they did not ask for.
 */
class SolrAddPrefixCompanionIntentionTest : SolrConfigsetTestCase() {

    private val reuseSchema = """
        <schema name="products">
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text_general" class="solr.TextField">
            <analyzer type="index">
              <tokenizer class="solr.StandardTokenizerFactory"/>
              <filter class="solr.LowerCaseFilterFactory"/>
            </analyzer>
          </fieldType>
          <fieldType name="text_prefix" class="solr.TextField">
            <analyzer type="index">
              <tokenizer class="solr.StandardTokenizerFactory"/>
              <filter class="solr.LowerCaseFilterFactory"/>
              <filter class="solr.EdgeNGramFilterFactory" minGramSize="2" maxGramSize="15"/>
            </analyzer>
          </fieldType>
          <field name="description" type="text_general"/>
          <field name="name_prefix" type="text_prefix"/>
        </schema>
    """.trimIndent()

    private val familyName = "Add prefix-capable companion field"

    /**
     * Opens [content] as the configset's schema with the caret just inside the named field's tag.
     *
     * The caret goes immediately after `<field`, so `findElementAt` lands on the tag-name token and
     * its parent is the tag itself — no dependence on where the attributes happen to sit. The
     * `core/conf/solrconfig.xml` alongside follows `SolrMatchInlayHintsProviderTest`, which is the
     * fixture shape already proven to satisfy the activation gate.
     */
    private fun givenSchemaWithCaretOn(fieldName: String, content: String) {
        myFixture.addFileToProject("core/conf/solrconfig.xml", "<config/>")
        myFixture.configureByText(
            "managed-schema.xml",
            content.replace("""<field name="$fieldName"""", """<field<caret> name="$fieldName""""),
        )
    }

    fun testOffersOnAFieldWithNoPrefixSupport() {
        givenSchemaWithCaretOn("description", reuseSchema)

        val intention = myFixture.filterAvailableIntentions(familyName)
        assertFalse("expected the intention to be offered", intention.isEmpty())
    }

    fun testReusesTheDeclaredEdgeNgramType() {
        givenSchemaWithCaretOn("description", reuseSchema)

        myFixture.launchAction(myFixture.filterAvailableIntentions(familyName).first())

        val text = myFixture.file.text
        assertTrue(
            "expected a companion field reusing text_prefix, got:\n$text",
            text.contains("""<field name="description_prefix" type="text_prefix" indexed="true" stored="false"/>"""),
        )
        assertTrue(
            "expected a copy rule, got:\n$text",
            text.contains("""<copyField source="description" dest="description_prefix"/>"""),
        )
        assertEquals("no second type should be written", 3, Regex("<fieldType ").findAll(text).count())
    }

    fun testStaysSilentOnAFieldThatAlreadyMatchesPrefixes() {
        givenSchemaWithCaretOn("name_prefix", reuseSchema)

        assertTrue(myFixture.filterAvailableIntentions(familyName).isEmpty())
    }

    fun testStaysSilentWhenTheCompanionNameIsTaken() {
        val schema = reuseSchema.replace(
            """<field name="name_prefix" type="text_prefix"/>""",
            """<field name="description_prefix" type="text_prefix"/>""",
        )
        givenSchemaWithCaretOn("description", schema)

        assertTrue(myFixture.filterAvailableIntentions(familyName).isEmpty())
    }

    fun testStaysSilentWhenTheChainWasNotUnderstood() {
        val schema = reuseSchema.replace(
            """<tokenizer class="solr.StandardTokenizerFactory"/>
              <filter class="solr.LowerCaseFilterFactory"/>
            </analyzer>
          </fieldType>
          <fieldType name="text_prefix"""",
            """<tokenizer class="com.example.MysteryTokenizerFactory"/>
            </analyzer>
          </fieldType>
          <fieldType name="text_prefix""",
        )
        givenSchemaWithCaretOn("description", schema)

        assertTrue(myFixture.filterAvailableIntentions(familyName).isEmpty())
    }

    fun testStaysSilentOutsideAField() {
        myFixture.addFileToProject("core/conf/solrconfig.xml", "<config/>")
        myFixture.configureByText(
            "managed-schema.xml",
            reuseSchema.replace("""<schema name="products">""", """<schema<caret> name="products">"""),
        )

        assertTrue(myFixture.filterAvailableIntentions(familyName).isEmpty())
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew test --tests "*.SolrAddPrefixCompanionIntentionTest"`
Expected: FAIL — every test fails because no intention with that family name is registered, so `filterAvailableIntentions` returns empty.

- [ ] **Step 4: Write the intention**

Create `src/main/kotlin/org/apache/solr/ide/configset/intention/SolrAddPrefixCompanionIntention.kt`:

```kotlin
package org.apache.solr.ide.configset.intention

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.XmlElementFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrSchemaTags
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader
import org.apache.solr.ide.model.SolrPrefixCompanion
import org.apache.solr.ide.model.SolrPrefixCompanionPlan

/**
 * Adds the companion field that gives a field efficient prefix matching.
 *
 * **An intention rather than an inspection quick-fix, deliberately.** A field without prefix support
 * is correct, and the standing rule is that inspections do not fire on correct files — an underline
 * here would be the plugin manufacturing a problem in order to have somewhere to attach a fix. The
 * platform's idiom for "correct but improvable" is an intention, which carries no such claim.
 *
 * The decision of whether to offer at all lives in [SolrPrefixCompanion], which is pure and tested
 * without a fixture. This class resolves the caret to a field, asks that function, and writes what
 * it answers.
 */
class SolrAddPrefixCompanionIntention : IntentionAction, DumbAware {

    /**
     * The plan computed by the most recent [isAvailable].
     *
     * [getText] takes no arguments, so it cannot recompute; the platform's contract is that it is
     * called after [isAvailable] returned true on the same instance, which is what makes this safe.
     */
    private var plan: SolrPrefixCompanionPlan? = null

    override fun getFamilyName(): String = SolrBundle.message("intention.prefixCompanion.family")

    override fun getText(): String {
        val current = plan ?: return familyName
        return if (current.generateType) {
            SolrBundle.message("intention.prefixCompanion.generate")
        } else {
            SolrBundle.message("intention.prefixCompanion.reuse", current.typeName)
        }
    }

    override fun startInWriteAction(): Boolean = true

    /**
     * Whether a companion can be offered for the field under the caret.
     *
     * Returns false rather than offering a disabled entry, which is the platform's own convention:
     * an intention that does not apply is absent, not greyed out.
     */
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        plan = null
        if (file == null || editor == null) return false
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return false

        val tag = fieldTagAt(editor, file) ?: return false
        val fieldName = tag.getAttributeValue("name") ?: return false

        val configset = SolrConfigsetDetector.configsetFor(file) ?: return false
        val model = SolrConfigsetReader.getInstance(project).modelFor(configset)
        val field = model.fields[fieldName]?.effective ?: return false

        val computed = SolrPrefixCompanion.planFor(model, field) ?: return false
        // The generation branch lands with the field type writer; until then, offering it would
        // produce a companion pointing at a type nothing declares.
        if (computed.generateType) return false

        plan = computed
        return true
    }

    /** Writes the companion field beside its source, and the copy rule that populates it. */
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val current = plan ?: return
        if (file == null || editor == null) return
        val sourceTag = fieldTagAt(editor, file) ?: return
        val schema = sourceTag.parentTag ?: return
        val factory = XmlElementFactory.getInstance(project)

        val companion = factory.createTagFromText(
            """<field name="${current.companionName}" type="${current.typeName}" """ +
                """indexed="true" stored="false"/>""",
        )
        schema.addAfter(companion, sourceTag)

        val copyRule = factory.createTagFromText(
            """<copyField source="${current.fieldName}" dest="${current.companionName}"/>""",
        )
        schema.addAfter(copyRule, copyRuleAnchor(schema))
    }

    /**
     * Where a new copy rule goes: after the last existing one, or at the end of the schema.
     *
     * Schemas are conventionally written in blocks — types, fields, then copy rules — and a rule
     * dropped beside the field it copies from reads as a mistake in a file organised that way.
     */
    private fun copyRuleAnchor(schema: XmlTag): XmlTag =
        schema.subTags.lastOrNull { it.name == SolrSchemaTags.COPY_FIELD } ?: schema.subTags.last()

    /** The `field` or `dynamicField` tag containing the caret, or null when it is elsewhere. */
    private fun fieldTagAt(editor: Editor, file: PsiFile): XmlTag? {
        val element = file.findElementAt(editor.caretModel.offset) ?: return null
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java) ?: return null
        return tag.takeIf { it.name in SolrSchemaTags.FIELD }
    }
}
```

- [ ] **Step 5: Register it and write the description files**

Add to `src/main/resources/META-INF/plugin.xml`, inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
        <!-- Adds the prefix-matching companion pattern. An intention rather than a quick-fix
             because a field without prefix support is correct, and inspections do not fire on
             correct files. -->
        <intentionAction>
            <language>XML</language>
            <className>org.apache.solr.ide.configset.intention.SolrAddPrefixCompanionIntention</className>
            <category>Solr</category>
        </intentionAction>
```

Create `src/main/resources/intentionDescriptions/SolrAddPrefixCompanionIntention/description.html`:

```html
<html>
<body>
Adds the companion field that gives a field efficient prefix matching.
<p>
    A search for <code>wid</code> does not match a document whose field holds <code>widget</code>,
    unless the index was built to answer that question. This intention adds the standard pattern: a
    companion field backed by an edge n-gram field type, and the <code>copyField</code> rule that
    populates it from the original.
</p>
<p>
    Where the schema already declares an edge n-gram field type, that type is reused rather than a
    second one added. Where it declares none, one is written with the n-gram filter on the index
    analyser only &mdash; putting it on the query side as well grinds the search term into its own
    prefixes, which matches nearly everything and collapses relevance.
</p>
<p>
    Offered only where the plugin is sure: a field whose type it cannot fully analyse, or which
    already supports prefix matching, is left alone.
</p>
</body>
</html>
```

Create `src/main/resources/intentionDescriptions/SolrAddPrefixCompanionIntention/before.xml.template`:

```xml
<field name="description" type="text_general"/>
```

Create `src/main/resources/intentionDescriptions/SolrAddPrefixCompanionIntention/after.xml.template`:

```xml
<field name="description" type="text_general"/>
<field name="description_prefix" type="text_prefix" indexed="true" stored="false"/>
<copyField source="description" dest="description_prefix"/>
```

- [ ] **Step 6: Document the new package**

Add to `docs/Module.md`, following the existing `# Package` sections:

```markdown
# Package org.apache.solr.ide.configset.intention

Intentions offered on a configset that is already correct.

The distinction from `inspection` is the point of the package rather than a filing detail. An
inspection reports a mistake; an intention offers an improvement to a file that has none, and the
standing rule that inspections must not fire on a correct file is what forces the two apart.
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew test --tests "*.SolrAddPrefixCompanionIntentionTest"`
Expected: PASS, 6 tests.

If `filterAvailableIntentions` returns empty on the positive case, the usual cause is the caret
marker landing outside the tag — print `myFixture.file.findElementAt(myFixture.caretOffset)` and
confirm it resolves to a token inside `<field …>`.

- [ ] **Step 8: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/org/apache/solr/ide/configset/intention/ \
        src/test/kotlin/org/apache/solr/ide/configset/intention/ \
        src/main/resources/intentionDescriptions/ \
        src/main/resources/META-INF/plugin.xml \
        src/main/resources/messages/SolrBundle.properties \
        docs/Module.md
git commit -s -m "feat: offer a prefix-capable companion field, reusing a declared type

The plugin could tell a reader that a field cannot match a prefix and had
nowhere to act on it. This adds the Alt-Enter fix: a companion field and the
copy rule that populates it.

An intention rather than an inspection quick-fix. A field without prefix support
is correct, and the standing rule is that inspections do not fire on correct
files -- an underline would be the plugin manufacturing a problem in order to
have somewhere to attach a fix. Step 26 already settled the same question for
restated defaults.

Reuse only, for now. Where the schema declares no edge n-gram type the intention
stays silent rather than pointing a companion at a type nothing declares; the
type writer lands next and removes that guard.

The chosen type is named in the Alt-Enter text, so a schema declaring more than
one makes the choice visible before it is taken rather than after."
```

---

### Task 3: Writing the field type when the schema has none

**Files:**
- Modify: `src/main/kotlin/org/apache/solr/ide/configset/intention/SolrAddPrefixCompanionIntention.kt`
- Modify: `src/test/kotlin/org/apache/solr/ide/configset/intention/SolrAddPrefixCompanionIntentionTest.kt`

**Interfaces:**
- Consumes: `SolrPrefixCompanionPlan.generateType` and `SolrPrefixCompanion.GENERATED_TYPE_NAME` from Task 1; `SolrAddPrefixCompanionIntention` from Task 2.
- Produces: nothing new. This completes the intention.

- [ ] **Step 1: Write the failing tests**

Add to `SolrAddPrefixCompanionIntentionTest`:

```kotlin
    private val noPrefixTypeSchema = """
        <schema name="products">
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text_general" class="solr.TextField">
            <analyzer type="index">
              <tokenizer class="solr.StandardTokenizerFactory"/>
              <filter class="solr.LowerCaseFilterFactory"/>
            </analyzer>
          </fieldType>
          <field name="description" type="text_general"/>
          <copyField source="description" dest="text"/>
        </schema>
    """.trimIndent()

    fun testWritesTheTypeWhenTheSchemaDeclaresNone() {
        givenSchemaWithCaretOn("description", noPrefixTypeSchema)

        myFixture.launchAction(myFixture.filterAvailableIntentions(familyName).first())

        val text = myFixture.file.text
        assertTrue("expected a generated type, got:\n$text", text.contains("""<fieldType name="text_prefix""""))
        assertTrue(
            "expected the n-gram filter on the index analyser, got:\n$text",
            text.contains("""<filter class="solr.EdgeNGramFilterFactory" minGramSize="2" maxGramSize="15"/>"""),
        )
        assertTrue(
            "expected the companion to use the generated type, got:\n$text",
            text.contains("""<field name="description_prefix" type="text_prefix" indexed="true" stored="false"/>"""),
        )
    }

    fun testTheGeneratedTypeDoesNotNgramTheQuerySide() {
        givenSchemaWithCaretOn("description", noPrefixTypeSchema)

        myFixture.launchAction(myFixture.filterAvailableIntentions(familyName).first())

        val queryAnalyser = myFixture.file.text
            .substringAfter("""<analyzer type="query">""")
            .substringBefore("</analyzer>")
        assertFalse(
            "an n-gram filter on the query side collapses relevance:\n$queryAnalyser",
            queryAnalyser.contains("EdgeNGram"),
        )
    }

    fun testStaysSilentWhenTheGeneratedTypeNameIsTaken() {
        val schema = noPrefixTypeSchema.replace(
            """<fieldType name="string" class="solr.StrField"/>""",
            """<fieldType name="text_prefix" class="solr.StrField"/>""",
        )
        givenSchemaWithCaretOn("description", schema)

        assertTrue(myFixture.filterAvailableIntentions(familyName).isEmpty())
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "*.SolrAddPrefixCompanionIntentionTest"`
Expected: `testWritesTheTypeWhenTheSchemaDeclaresNone` and `testTheGeneratedTypeDoesNotNgramTheQuerySide` FAIL — the intention is not offered, because Task 2's guard rejects `generateType`. `testStaysSilentWhenTheGeneratedTypeNameIsTaken` passes already, and must keep passing.

- [ ] **Step 3: Remove the guard and write the type**

In `SolrAddPrefixCompanionIntention`, delete these three lines from `isAvailable`:

```kotlin
        // The generation branch lands with the field type writer; until then, offering it would
        // produce a companion pointing at a type nothing declares.
        if (computed.generateType) return false
```

Add to `invoke`, immediately after `val factory = XmlElementFactory.getInstance(project)`:

```kotlin
        if (current.generateType) {
            val type = factory.createTagFromText(generatedTypeText(current.typeName))
            schema.addAfter(type, fieldTypeAnchor(schema))
        }
```

Add these two members to the class:

```kotlin
    /**
     * The field type written when the schema declares none, as text.
     *
     * **The n-gram filter is on the index analyser only, and that asymmetry is the point.** Put it
     * on the query side as well and a search for `wid` is itself ground into `wi` and `wid`, both of
     * which match a large fraction of the index; relevance collapses, and the symptom gets reported
     * as "search is broken" rather than as a schema bug. It is the part a hand-written copy of this
     * pattern most often gets wrong, which is most of the reason generating it is worth anything.
     *
     * The gram bounds are conventional rather than derived: below two, a single character matches
     * most of the index; above fifteen, prefixes nobody types are being stored.
     */
    private fun generatedTypeText(name: String): String = """
        <fieldType name="$name" class="solr.TextField" positionIncrementGap="100">
          <analyzer type="index">
            <tokenizer class="solr.StandardTokenizerFactory"/>
            <filter class="solr.LowerCaseFilterFactory"/>
            <filter class="solr.EdgeNGramFilterFactory" minGramSize="2" maxGramSize="15"/>
          </analyzer>
          <analyzer type="query">
            <tokenizer class="solr.StandardTokenizerFactory"/>
            <filter class="solr.LowerCaseFilterFactory"/>
          </analyzer>
        </fieldType>
    """.trimIndent()

    /** Where a new field type goes: after the last existing one, or first in the schema. */
    private fun fieldTypeAnchor(schema: XmlTag): XmlTag =
        schema.subTags.lastOrNull { it.name in SolrSchemaTags.FIELD_TYPE } ?: schema.subTags.first()
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "*.SolrAddPrefixCompanionIntentionTest"`
Expected: PASS, 9 tests. All six from Task 2 must still pass — in particular `testReusesTheDeclaredEdgeNgramType`, whose `assertEquals(3, …)` on the field type count is what proves the generation branch does not fire when a type already exists.

- [ ] **Step 5: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/org/apache/solr/ide/configset/intention/ \
        src/test/kotlin/org/apache/solr/ide/configset/intention/
git commit -s -m "feat: write the edge n-gram type when the schema declares none

Completes the companion intention for the case that matters most: a schema that
has never had prefix matching, which is where the pattern gets copied off a blog
post and copied wrong.

The n-gram filter goes on the index analyser only. On the query side as well, a
search for wid is itself ground into wi and wid, both matching a large fraction
of the index -- relevance collapses and the symptom is reported as search being
broken rather than as a schema bug. That asymmetry is the part hand-written
copies get wrong, and generating it correctly is worth more than generating the
field, which is the part a person gets right unaided.

A test asserts the query analyser carries no n-gram filter, because that is the
regression a later edit to the template would introduce silently.

The gram bounds are conventional rather than derived, in a file the user owns
and can edit."
```

---

### Task 4: Record it where the project keeps status

**Files:**
- Modify: `docs/manual-test-suite.md`
- Modify: `specs/plans/0002-solr-intellij-plugin-plan.md`

**Interfaces:**
- Consumes: nothing. Documentation only.
- Produces: nothing.

- [ ] **Step 1: Add the sandbox gesture**

Add a row to `docs/manual-test-suite.md`, matching the columns already there (gesture, expected outcome, pass history — never build status):

```markdown
| Alt-Enter on `description` in the demo schema | Offers *Add prefix-capable companion field (text_prefix)*. Taking it adds `description_prefix` beside the field and a copy rule with the other copy rules. | |
| Alt-Enter on `name_prefix` in the demo schema | Offers nothing. The field already matches prefixes. | |
```

- [ ] **Step 2: Update the plan's step status**

In `specs/plans/0002-solr-intellij-plugin-plan.md`, under *Step 7: Match hints and quick-fixes*, replace the unchecked criterion:

```markdown
- [ ] Quick-fixes produce valid configset edits.
```

with:

```markdown
- [x] Quick-fixes produce valid configset edits. The prefix companion ships; the `_exact`
      companion is the remaining half of action 3.
```

And update the "Action 3 is what remains" paragraph to record what landed, replacing it with:

```markdown
**Action 3 has shipped its prefix half.** `SolrAddPrefixCompanionIntention` adds the companion
field, its copy rule, and the edge n-gram field type where the schema declares none, deciding
through the pure `SolrPrefixCompanion` in `model`. The `_exact` companion is what remains, and it
reuses this work's naming, insertion and copy-rule code rather than designing anything new. Action 4
is a constraint on that work rather than something to build, so it landed with this.
```

Do **not** change the step's heading from `(in progress)` — it stays open until the `_exact`
companion lands, and the heading is an anchor other steps link to.

- [ ] **Step 3: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Documentation-only, but the plan links are checked by the docs gate.

- [ ] **Step 4: Commit**

```bash
git add docs/manual-test-suite.md specs/plans/0002-solr-intellij-plugin-plan.md
git commit -s -m "docs: record the prefix companion in the plan and the manual suite

The plan is the only file that owns what is built, so a shipped capability that
it still lists as unbuilt makes it wrong about the one thing it is for.

The step heading stays 'in progress' -- the _exact companion is the remaining
half of action 3, and the heading is an anchor other steps link to by name.

Two manual gestures rather than one: the negative case, Alt-Enter on a field
that already matches prefixes, is the one worth checking by hand, because an
intention appearing where it should not is a suggestion a user acts on."
```

---

## Self-Review

**Spec coverage.** Every section of the design record maps to a task:

| Design record section | Task |
|---|---|
| An intention, not an inspection quick-fix | 2 (class KDoc, registration, package doc) |
| When the intention offers itself — five rules | 1 (all five in `planFor`), 2 and 3 (a test each) |
| `indexed="true"` deliberately not required | 1 — no such condition exists in `planFor`; nothing to add |
| Reuse before generation | 1 (`prefixTypesIn`), 2 (reuse path), 3 (generation path) |
| The generated recipe | 3 (`generatedTypeText`, plus the query-side test) |
| What is added, and where | 2 (`copyRuleAnchor`), 3 (`fieldTypeAnchor`) |
| Testing strategy — pure and fixture tiers | 1 (JUnit 4), 2 and 3 (`BasePlatformTestCase`) |
| Registration and description files | 2 |
| Risks — generated type name taken | 1 (`planFor`), 3 (`testStaysSilentWhenTheGeneratedTypeNameIsTaken`) |
| Risks — several edge n-gram types | 1 (`prefixTypesIn` takes the first), 2 (named in `getText`) |
| Delivery — acceptance is demo step 33 | 4 (manual suite row) |

**Placeholder scan.** No TBD, TODO, "handle edge cases", or "similar to Task N". Every code step carries the code.

**Type consistency.** `SolrPrefixCompanionPlan`'s four properties — `fieldName`, `companionName`, `typeName`, `generateType` — are the names used in Tasks 2 and 3. `SolrPrefixCompanion.planFor`, `.prefixTypesIn`, `.SUFFIX` and `.GENERATED_TYPE_NAME` are spelled identically everywhere. `familyName` in the test matches the value of `intention.prefixCompanion.family`.

**One gap accepted knowingly.** `planFor` checks `model.fields` for a taken companion name but not `model.dynamicFields`. A schema declaring `*_prefix` as a dynamic field would already match the companion name, and the intention would add a concrete field that shadows it — legal in Solr, and arguably what the user wants, but not something this plan tested. Left out rather than guessed at; worth a follow-up if it shows up in practice.
