package org.apache.solr.ide.configset.editing

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiFile
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.configset.reading.SolrConfigsetReader
import org.apache.solr.ide.configset.schema.inspection.SolrUnusedFieldTypeInspection
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every registered inspection over the configsets Apache Solr ships, asserting nothing is reported.
 *
 * **This is the only fixture in the suite nobody here wrote.** Every other clean fixture was composed
 * by the author of the inspection it reassures — which makes it evidence about the cases that author
 * thought of, and silent about the rest. These four files were written by Solr to configure Solr, and
 * they are full of exactly the syntax that resembles a mistake: an `fl` holding `score` and
 * `[docid]`, a `bf` holding `recip(...)`, forty field types of which a demo configset uses a
 * fraction.
 *
 * **The line each configset targets comes from the file rather than from this test.** Both
 * `solrconfig.xml`s declare a `<luceneMatchVersion>` — `9.12` and `10.3`, which are *Lucene*
 * versions — so the 9 copy resolves the 9 catalog and the 10 copy the 10 catalog with no version
 * plumbing here at all. Both generated catalogs are therefore exercised, and a catalog regression on
 * one line cannot hide behind the other.
 *
 * **One rule is held out, and it is held out by name rather than by a filter on what it said.**
 * [SolrUnusedFieldTypeInspection] reports 45 types in `sample_techproducts_configs` and two in
 * `_default`, every one of them true: Solr ships a palette of language and spatial types for fields
 * the copier has not written yet. That is a fact about the file rather than a defect in it, which is
 * this suite's only finding of that kind — so it is the one rule a *zero findings* gate cannot hold.
 * How it should then be presented is a separate question, recorded rather than answered here:
 * lowering its severity to `INFORMATION` was measured and does not work, because the platform drops
 * `INFORMATION` inspections from the daemon entirely and the grey-out disappears with them. An
 * annotator is the shape that would do it, which is what the restated-default dim already is.
 *
 * The hold-out is pinned from both sides. Everything else must report nothing, so a new rule firing
 * here still fails; and [testTheHeldOutRuleIsWhyItIsHeldOut] asserts the held-out rule does fire, so
 * silencing it is not a way to make this file agree with itself.
 */
class SolrShippedConfigsetTest : SolrConfigsetTestCase() {

    fun testTheDefaultConfigsetIsCleanOnSolr9() = assertNothingIsReported("9", "_default")

    fun testTheDefaultConfigsetIsCleanOnSolr10() = assertNothingIsReported("10", "_default")

    fun testTheTechproductsConfigsetIsCleanOnSolr9() =
        assertNothingIsReported("9", "sample_techproducts_configs")

    fun testTheTechproductsConfigsetIsCleanOnSolr10() =
        assertNothingIsReported("10", "sample_techproducts_configs")

    /**
     * A defect planted in a shipped file is reported, which is what makes the four tests above mean
     * anything.
     *
     * A gate asserting an empty list passes just as well when it is looking at nothing — a fixture
     * the detector declined, a model that failed to parse, an inspection list that came back empty.
     * The same harness, pointed at the same file with one word changed, has to produce a finding.
     */
    fun testThePlantedDefectIsReported() {
        val findings = reportedFor(
            "9",
            "_default",
            corrupt = { it.replace("""<int name="rows">10</int>""", """<int name="rwos">10</int>""") },
        )
        assertTrue(
            "expected the planted parameter typo to be reported, got: $findings",
            findings.any { "rwos" in it },
        )
    }

    /**
     * The model behind the four tests is the shipped configset, rather than an empty one.
     *
     * The failure this catches is the one that would make every assertion above pass while proving
     * nothing: a fixture the detector never recognised leaves each inspection correctly silent, and
     * silence is what those tests assert. Counting what was parsed is what separates *found nothing
     * to report* from *had nothing to read*.
     */
    fun testTheFixtureIsReadAsAConfigset() {
        val files = givenConfigset("9", "sample_techproducts_configs")
        val model = checkNotNull(SolrConfigsetReader.getInstance(project).modelFor(files.first())) {
            "the vendored configset was not recognised as one"
        }
        assertEquals("9.12", model.luceneMatchVersion)
        assertTrue("expected many fields, got ${model.fields.size}", model.fields.size > 20)
        assertTrue("expected many field types, got ${model.fieldTypes.size}", model.fieldTypes.size > 20)
        assertTrue(
            "expected solrconfig.xml to reference fields, got ${model.fieldReferences.size}",
            model.fieldReferences.size > 5,
        )
    }

    /**
     * The held-out rule fires here, which is the whole reason it is held out.
     *
     * Without this, the exclusion above could be satisfied by the rule going quiet — a regression
     * that would leave every other test in this file green while a shipped feature stopped working.
     */
    fun testTheHeldOutRuleIsWhyItIsHeldOut() {
        val findings = reportedFor(
            "9",
            "sample_techproducts_configs",
            tools = listOf(SolrUnusedFieldTypeInspection()),
        )
        assertTrue(
            "expected the unused-type rule to report Solr's spare types, got ${findings.size}",
            findings.size > 20,
        )
    }

    private fun assertNothingIsReported(line: String, configset: String) {
        val findings = reportedFor(line, configset)
        assertEquals(
            "Solr's own $configset configset on the $line line should report nothing:\n" +
                findings.joinToString("\n"),
            emptyList<String>(),
            findings,
        )
    }

    /** Every finding [tools] make across both files of one shipped configset. */
    private fun reportedFor(
        line: String,
        configset: String,
        corrupt: (String) -> String = { it },
        tools: List<LocalInspectionTool> = everyRegisteredInspection()
            .filterNot { it is SolrUnusedFieldTypeInspection },
    ): List<String> {
        myFixture.enableInspections(*tools.toTypedArray())
        return givenConfigset(line, configset, corrupt).flatMap { file ->
            myFixture.configureFromExistingVirtualFile(file.virtualFile)
            myFixture.doHighlighting()
                .filter { it.severity >= HighlightSeverity.WEAK_WARNING }
                .map { describe(file, it) }
        }
    }

    /**
     * Puts both files of one shipped configset in the project, in a directory of their own.
     *
     * The path matters: the two files have to sit beside each other for the locator to read them as
     * one configset, and each configset needs a directory the others do not share.
     */
    private fun givenConfigset(
        line: String,
        configset: String,
        corrupt: (String) -> String = { it },
    ): List<PsiFile> = FILE_NAMES.map { name ->
        val resource = "/shipped-configsets/$line/$configset/$name"
        val text = checkNotNull(javaClass.getResourceAsStream(resource)) {
            "$resource is not on the test classpath"
        }.use { it.readBytes().decodeToString() }
        myFixture.addFileToProject("$line/$configset/conf/$name", corrupt(text))
    }

    private fun describe(file: PsiFile, info: HighlightInfo): String {
        val line = myFixture.editor.document.getLineNumber(info.startOffset) + 1
        val text = file.text.substring(info.startOffset, info.endOffset)
        return "${file.name}:$line  [${info.severity}] $text — ${info.description}"
    }

    /**
     * Every inspection `plugin.xml` registers, read out of the descriptor rather than listed here.
     *
     * A list would be a second place to remember, and the inspection that went unchecked would be
     * the newest one — the one whose behaviour on a real configset nobody has seen yet.
     */
    private fun everyRegisteredInspection(): List<LocalInspectionTool> {
        val descriptor = checkNotNull(javaClass.getResourceAsStream("/META-INF/plugin.xml")) {
            "plugin.xml is not on the test classpath"
        }
        val document = DocumentBuilderFactory.newInstance()
            .also { it.isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(descriptor)
        val registrations = document.getElementsByTagName("localInspection")
        val tools = (0 until registrations.length).map { index ->
            val className = registrations.item(index).attributes
                .getNamedItem("implementationClass").nodeValue
            Class.forName(className).getDeclaredConstructor().newInstance() as LocalInspectionTool
        }
        assertTrue("expected plugin.xml to register inspections", tools.isNotEmpty())
        return tools
    }

    private companion object {
        /** The two files the plugin parses. Solr's `conf/` holds more; nothing here reads them. */
        val FILE_NAMES = listOf("managed-schema.xml", "solrconfig.xml")
    }
}
