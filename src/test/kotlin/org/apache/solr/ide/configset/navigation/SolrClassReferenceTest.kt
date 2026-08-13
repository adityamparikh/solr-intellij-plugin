package org.apache.solr.ide.configset.navigation

import com.intellij.psi.PsiClass
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.util.parentOfType
import com.intellij.testFramework.DumbModeTestUtils
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
     * Resolving while the project is indexing answers nothing instead of throwing.
     *
     * **The one state no other test here is in.** Every fixture runs in smart mode, so a resolve that
     * reads the stub index unguarded passes the whole suite and fails the moment a reader opens a
     * project — which is exactly how this was found: a `<directoryFactory>` that explained nothing in
     * the sandbox while the class-value tests were green.
     *
     * The throw does not stay local. The platform asks for target symbols at the caret before it asks
     * any documentation provider anything, and that resolves this reference — so an
     * `IndexNotReadyException` here takes down the whole popup, including the parts of it this plugin
     * could have answered from a file it had already parsed. Navigation being unavailable during
     * indexing is correct and expected; taking the explanation with it is not.
     */
    fun testResolvingWhileIndexingAnswersNothingRatherThanThrowing() {
        givenACustomFactory()
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<fieldType name="custom" class="com.example.MyToken<caret>izerFactory"/>"""),
        )
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertNull("indexing makes the class unavailable, which is not an error", resolveAtCaret())
        }
        assertTrue("and the same reference resolves once indexing finishes", resolveAtCaret() is PsiClass)
    }

    /**
     * `solr.` is Solr's abbreviation for its own packages rather than a package, so it only resolves
     * through a catalog entry that supplies the qualified name — and a name no entry carries resolves
     * to nothing, exactly as a class that is genuinely absent does.
     *
     * The spelling matters here. An earlier revision of this test used `solr.SearchHandler`, back when
     * the catalog covered the schema's four kinds only; it still passed once the catalog learned
     * `solrconfig.xml`'s kinds, because the fixture never put that class on the classpath either — so
     * it went on asserting a lifted limitation, for a reason that had nothing to do with the catalog.
     * A name Solr does not ship is what actually tests the abbreviation.
     */
    fun testASolrPrefixedNameTheCatalogDoesNotKnowResolvesToNothing() {
        myFixture.addFileToProject("managed-schema.xml", schema(""))
        myFixture.configureByText(
            "solrconfig.xml",
            """<config><requestHandler name="/select" class="solr.NoSuch<caret>Handler"/></config>""",
        )
        assertNull(resolveAtCaret())
    }

    /**
     * A `solrconfig.xml` plugin class navigates, which the catalog's `solrconfig.xml` kinds are what
     * made possible.
     *
     * `solr.SearchHandler` is the most written class in the file, and until the catalog carried its
     * kind the abbreviation could not be expanded, so the commonest `class` value in a configset was
     * the one that went nowhere. The fixture declares the class at Solr's own coordinates, since what
     * is being tested is whether the short name expands — not whether an SDK happens to ship Solr.
     */
    fun testASolrconfigPluginClassResolvesThroughItsShortName() {
        myFixture.addFileToProject(
            "src/org/apache/solr/handler/component/SearchHandler.java",
            """
            package org.apache.solr.handler.component;
            public class SearchHandler {}
            """.trimIndent(),
        )
        myFixture.addFileToProject("managed-schema.xml", schema(""))
        myFixture.configureByText(
            "solrconfig.xml",
            """<config><requestHandler name="/select" class="solr.Search<caret>Handler"/></config>""",
        )
        val target = resolveAtCaret()
        assertTrue("expected a Java class, got $target", target is PsiClass)
        assertEquals(
            "org.apache.solr.handler.component.SearchHandler",
            (target as PsiClass).qualifiedName,
        )
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
