package org.apache.solr.ide.configset.navigation

import com.intellij.psi.PsiClass
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.util.parentOfType
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Navigation from a `class` attribute to the class it names.
 *
 * **What this must not do carries the weight.** A configset names classes that are not on the
 * project's classpath as a matter of course — the reader is editing configuration, not building Solr —
 * so the unresolved case is the normal one, and it has to produce nothing at all rather than an
 * underline or a second opinion about a class the inspections deliberately decline to judge.
 *
 * The fixture puts a real class on the classpath by declaring it in Java, which is what lets these
 * assertions be about the reference rather than about whatever happens to be in the SDK.
 */
class SolrClassReferenceTest : SolrConfigsetTestCase() {

    private fun schema(body: String) = """
        <schema name="t" version="1.6">
          <fieldType name="string" class="solr.StrField"/>
          $body
        </schema>
    """.trimIndent()

    /** The class the fixture can actually resolve to, declared where the project can see it. */
    private fun givenACustomFactory() {
        myFixture.addFileToProject(
            "src/com/example/MyTokenizerFactory.java",
            """
            package com.example;
            public class MyTokenizerFactory {}
            """.trimIndent(),
        )
    }

    private fun resolveAtCaret(): Any? {
        val value = myFixture.file.findElementAt(myFixture.caretOffset)!!
            .parentOfType<XmlAttributeValue>(withSelf = true)!!
        return value.references.firstNotNullOfOrNull { it.resolve() }
    }

    // --- what resolves ---------------------------------------------------------------------------

    /**
     * A fully qualified name resolves without the catalog knowing anything about it, which is the
     * case that matters for a custom plugin: the whole point of naming a class in configuration is
     * that it may be one this build has never heard of.
     */
    fun testAFullyQualifiedClassNameResolves() {
        givenACustomFactory()
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<fieldType name="custom" class="com.example.MyToken<caret>izerFactory"/>"""),
        )
        val target = resolveAtCaret()
        assertTrue("expected a Java class, got $target", target is PsiClass)
        assertEquals("com.example.MyTokenizerFactory", (target as PsiClass).qualifiedName)
    }

    /** The same gesture in `solrconfig.xml`, since a `class` attribute is written in both files. */
    fun testAFullyQualifiedClassNameResolvesInSolrconfig() {
        givenACustomFactory()
        myFixture.addFileToProject("managed-schema.xml", schema(""))
        myFixture.configureByText(
            "solrconfig.xml",
            """<config><searchComponent name="x" class="com.example.MyToken<caret>izerFactory"/></config>""",
        )
        assertTrue("expected a Java class", resolveAtCaret() is PsiClass)
    }

    // --- what must resolve to nothing -------------------------------------------------------------

    /**
     * A class absent from the classpath is the normal case, not a defect, so the reference resolves to
     * null and contributes no highlighting. This is the assertion the soft-reference contract exists
     * for: were it hard, every configset naming a plugin this project does not build would be painted
     * with unresolved-reference errors.
     */
    fun testAClassNotOnTheClasspathResolvesToNothing() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<fieldType name="custom" class="com.example.NotHer<caret>eAtAll"/>"""),
        )
        assertNull(resolveAtCaret())
        // And nothing is reported about it — the point of the soft reference.
        myFixture.checkHighlighting(true, false, false)
    }

    /**
     * `solr.` is Solr's abbreviation for its own packages rather than a package, so it only resolves
     * through a catalog entry that supplies the qualified name. The catalog covers the schema's four
     * kinds, so a `solrconfig.xml` plugin class does not resolve yet — and degrades to nothing, which
     * is the same behaviour as a class that is genuinely absent.
     */
    fun testASolrPrefixedNameTheCatalogDoesNotKnowResolvesToNothing() {
        myFixture.addFileToProject("managed-schema.xml", schema(""))
        myFixture.configureByText(
            "solrconfig.xml",
            """<config><requestHandler name="/select" class="solr.Search<caret>Handler"/></config>""",
        )
        assertNull(resolveAtCaret())
    }

    /** A `class` attribute in an unrelated XML file is nobody's business but that file's. */
    fun testAClassAttributeOutsideAConfigsetIsNotReferenced() {
        givenACustomFactory()
        myFixture.configureByText(
            "beans.xml",
            """<beans><bean class="com.example.MyToken<caret>izerFactory"/></beans>""",
        )
        val value = myFixture.file.findElementAt(myFixture.caretOffset)!!
            .parentOfType<XmlAttributeValue>(withSelf = true)!!
        assertTrue(
            "this plugin must contribute no reference here: ${value.references.toList()}",
            value.references.none { it is SolrClassReference },
        )
    }

    /** Outside a Solr project every surface is inert, this one included. */
    fun testNothingIsReferencedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        givenACustomFactory()
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<fieldType name="custom" class="com.example.MyToken<caret>izerFactory"/>"""),
        )
        assertNull(resolveAtCaret())
    }
}
