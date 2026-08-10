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

    /**
     * Resolves **this plugin's** reference at the caret, not whichever reference answers first.
     *
     * The platform contributes its own references into XML attribute values, so accepting any
     * resolution would let the positive cases pass with this contributor removed entirely — the exact
     * shape of a test that passes for the wrong reason.
     */
    private fun resolveAtCaret(): Any? {
        val value = myFixture.file.findElementAt(myFixture.caretOffset)!!
            .parentOfType<XmlAttributeValue>(withSelf = true)!!
        return value.references.filterIsInstance<SolrClassReference>().firstNotNullOfOrNull { it.resolve() }
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

    // --- rename ----------------------------------------------------------------------------------

    /**
     * Renaming the class rewrites the value, and a `solr.`-prefixed value keeps its prefix.
     *
     * Making a `class` value navigable is what exposes it to rename, so the two arrive together. The
     * inherited behaviour replaces the reference's whole range with the new simple name, which would
     * turn `solr.StrField` into `Renamed` — a configset Solr can no longer load, produced by a
     * refactoring the reader trusted.
     */
    fun testRenamingAPrefixedClassKeepsSolrsSpelling() {
        myFixture.addFileToProject(
            "src/org/apache/solr/schema/StrField.java",
            """
            package org.apache.solr.schema;
            public class StrField {}
            """.trimIndent(),
        )
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<fieldType name="s2" class="solr.Str<caret>Field"/>"""),
        )
        val reference = myFixture.file.findElementAt(myFixture.caretOffset)!!
            .parentOfType<XmlAttributeValue>(withSelf = true)!!
            .references.filterIsInstance<SolrClassReference>().single()

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            reference.handleElementRename("RenamedField")
        }

        assertTrue(
            "the prefix must survive the rename: ${myFixture.file.text}",
            myFixture.file.text.contains("""class="solr.RenamedField""""),
        )
    }

    /** An unprefixed value takes the new name as written, because that is what it named before. */
    fun testRenamingAFullyQualifiedClassWritesTheNewName() {
        givenACustomFactory()
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<fieldType name="custom" class="com.example.MyToken<caret>izerFactory"/>"""),
        )
        val reference = myFixture.file.findElementAt(myFixture.caretOffset)!!
            .parentOfType<XmlAttributeValue>(withSelf = true)!!
            .references.filterIsInstance<SolrClassReference>().single()

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            reference.handleElementRename("com.example.Renamed")
        }

        assertTrue(
            "expected the written name: ${myFixture.file.text}",
            myFixture.file.text.contains("com.example.Renamed"),
        )
        assertFalse("no prefix should appear", myFixture.file.text.contains("solr.com.example"))
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
        // And nothing is reported about it — the point of the soft reference. Every category is
        // checked, including infos and weak warnings: a soft reference that produced a weak warning
        // would satisfy an errors-and-warnings-only check while breaking the promise this asserts.
        myFixture.checkHighlighting(true, true, true)
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
