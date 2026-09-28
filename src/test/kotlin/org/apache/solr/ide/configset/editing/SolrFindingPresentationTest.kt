package org.apache.solr.ide.configset.editing

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.editor.colors.CodeInsightColors
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.configset.schema.inspection.SolrDanglingCopyFieldInspection
import org.apache.solr.ide.configset.schema.inspection.SolrUnknownAttributeInspection
import org.apache.solr.ide.configset.schema.inspection.SolrUnknownFieldTypeInspection
import org.apache.solr.ide.configset.solrconfig.inspection.SolrUnknownFieldReferenceInspection

/**
 * How a name that resolves to nothing is *drawn*, in the schemes a user actually has.
 *
 * **Every other test of these four checks asserts severity, and severity was never the defect.** A
 * sandbox pass on the default light theme reported the unknown type and the unknown attribute as not
 * underlined, while each check's own fixture, marking `<warning>`, stayed green. Both were right:
 * the light schemes draw a warning as a pale background and no line at all. So the claim this file
 * holds is the one the reader sees — a line under the name, in every bundled scheme — and it is
 * asserted against the scheme's own attributes rather than against the highlight type that happens
 * to produce them today.
 */
class SolrFindingPresentationTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name="t" version="1.7">
          <fieldType name="string" class="solr.StrField"/>
          <field name="id" type="string"/>
          <field name="sku" type="strng"/>
          <field name="name" type="string" indxed="true"/>
          <copyField source="id" dest="missing"/>
        </schema>
    """.trimIndent()

    private val config = """
        <config>
          <requestHandler name="/select">
            <lst name="defaults"><str name="qf">descriptoin</str></lst>
          </requestHandler>
        </config>
    """.trimIndent()

    /** The four findings, one per check, from the two files they are written in. */
    private fun unresolvedNames(): List<HighlightInfo> {
        myFixture.enableInspections(
            SolrUnknownFieldTypeInspection(),
            SolrUnknownAttributeInspection(),
            SolrDanglingCopyFieldInspection(),
            SolrUnknownFieldReferenceInspection(),
        )
        myFixture.addFileToProject("conf/managed-schema.xml", schema)
        myFixture.addFileToProject("conf/solrconfig.xml", config)
        return listOf("conf/managed-schema.xml", "conf/solrconfig.xml").flatMap { path ->
            myFixture.configureFromTempProjectFile(path)
            myFixture.doHighlighting().filter { it.inspectionToolId in CHECKS }
        }
    }

    fun testEveryUnresolvedNameIsUnderlinedInEveryBundledScheme() {
        val findings = unresolvedNames()
        assertEquals(
            "expected one finding per check, got ${findings.map { it.inspectionToolId }}",
            CHECKS,
            findings.mapNotNull { it.inspectionToolId }.toSet(),
        )
        val notUnderlined = BundledColorSchemes.all().flatMap { (name, scheme) ->
            findings
                .filterNot { BundledColorSchemes.underlines(BundledColorSchemes.attributesOf(it, scheme)) }
                .map { "$name: ${it.inspectionToolId} drew ${BundledColorSchemes.attributesOf(it, scheme)}" }
        }
        assertEquals(emptyList<String>(), notUnderlined)
    }

    /**
     * Why the checks above cannot be warnings, pinned so that the reason is re-read if it stops
     * being true: in the light scheme a warning is a background and nothing more.
     *
     * Also what proves [BundledColorSchemes] read `Light` rather than handing back its parent — the
     * two disagree on exactly this attribute.
     */
    fun testALightSchemeDrawsAWarningWithoutAnyLine() {
        val schemes = BundledColorSchemes.all()
        val light = schemes.getValue("Light").getAttributes(CodeInsightColors.WARNINGS_ATTRIBUTES)
        val default = schemes.getValue("Default").getAttributes(CodeInsightColors.WARNINGS_ATTRIBUTES)
        assertFalse("Light draws a warning with a line: $light", BundledColorSchemes.underlines(light))
        assertFalse("Light was not read over its parent", light.backgroundColor == default.backgroundColor)
    }

    private companion object {
        /** The checks whose finding is a name that resolves to nothing. */
        val CHECKS = setOf(
            "SolrUnknownFieldType",
            "SolrUnknownAttribute",
            "SolrDanglingCopyField",
            "SolrUnknownFieldReference",
        )
    }
}
