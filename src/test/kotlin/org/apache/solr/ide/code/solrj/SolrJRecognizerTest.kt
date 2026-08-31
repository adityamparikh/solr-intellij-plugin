package org.apache.solr.ide.code.solrj

import com.intellij.psi.PsiFile
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Reading field names out of SolrJ calls in Java.
 *
 * The first test in this suite that needs a *resolvable* type rather than a file that merely parses:
 * the recognizer confirms a call sits on SolrJ's `SolrQuery` before reading anything, so a fixture
 * where that class does not resolve proves nothing. The fixture's Solr library carries no jar — it
 * is a name, which is all the module gate needs — so the class is supplied as source instead.
 */
class SolrJRecognizerTest : SolrConfigsetTestCase() {

    /**
     * Enough of SolrJ for the receiver type to resolve, under the Solr 9 package.
     *
     * A stub rather than the real jar. The recognizer matches a qualified name and a method name,
     * so a source file declaring both answers exactly the question it asks, without putting a
     * dependency into the build for the sake of a test.
     */
    private fun givenSolrJ(qualifiedPackage: String = "org.apache.solr.client.solrj") {
        val path = qualifiedPackage.replace('.', '/')
        myFixture.addFileToProject(
            "$path/SolrQuery.java",
            """
            package $qualifiedPackage;
            public class SolrQuery {
                public SolrQuery(String q) {}
                public SolrQuery addFilterQuery(String... fq) { return this; }
                public SolrQuery setFields(String... fields) { return this; }
                public SolrQuery addField(String field) { return this; }
                public SolrQuery addFacetField(String... fields) { return this; }
                public SolrQuery setRows(Integer rows) { return this; }
            }
            """.trimIndent(),
        )
    }

    private fun javaFile(body: String, import: String = "org.apache.solr.client.solrj.SolrQuery"): PsiFile =
        myFixture.addFileToProject(
            "src/Search.java",
            """
            import $import;
            class Search {
                void go() {
                    SolrQuery q = new SolrQuery("*:*");
                    $body
                }
            }
            """.trimIndent(),
        )

    private fun usages(file: PsiFile) = SolrJRecognizer.fieldUsagesIn(file)

    /** The demo's planted defect: a typo inside a filter query, which compiles and matches nothing. */
    fun testAFilterQueryNamesTheFieldInsideIt() {
        givenSolrJ()
        val found = usages(javaFile("""q.addFilterQuery("categry:books");"""))

        assertEquals(listOf("categry"), found.map { it.fieldName })
        assertEquals(listOf("fq"), found.map { it.parameter })
    }

    /** A field list is one argument holding several names, and each is a usage. */
    fun testAFieldListNamesEachOfItsFields() {
        givenSolrJ()
        val found = usages(javaFile("""q.setFields("id,name,price");"""))

        assertEquals(listOf("id", "name", "price"), found.map { it.fieldName })
        assertTrue(found.all { it.parameter == "fl" })
    }

    /** A single-field method holds one name however it is punctuated. */
    fun testASingleFieldArgumentIsOneName() {
        givenSolrJ()
        val found = usages(javaFile("""q.addField("price");"""))

        assertEquals(listOf("price"), found.map { it.fieldName })
    }

    /** Each vararg of a facet call is its own field. */
    fun testEveryArgumentOfAVarargsCallIsRead() {
        givenSolrJ()
        val found = usages(javaFile("""q.addFacetField("cat", "manu");"""))

        assertEquals(listOf("cat", "manu"), found.map { it.fieldName })
    }

    // --- silence, which matters more than recall --------------------------------------------------

    /** A method that is not on the map is not read, whatever its argument looks like. */
    fun testAnUnmappedMethodIsNotRead() {
        givenSolrJ()
        assertEmpty(usages(javaFile("""q.setRows(10);""")))
    }

    /**
     * A call on something that is not SolrJ's `SolrQuery` is not read.
     *
     * The case that makes the type check worth doing: a project's own builder with a method of the
     * same name. Reading it would warn about fields in code that has nothing to do with Solr.
     */
    fun testACallOnAnUnrelatedClassIsNotRead() {
        givenSolrJ()
        myFixture.addFileToProject(
            "src/Other.java",
            """
            package com.example;
            public class Other {
                public Other addFilterQuery(String fq) { return this; }
            }
            """.trimIndent(),
        )
        val file = myFixture.addFileToProject(
            "src/Unrelated.java",
            """
            import com.example.Other;
            class Unrelated {
                void go() { new Other().addFilterQuery("categry:books"); }
            }
            """.trimIndent(),
        )
        assertEmpty(usages(file))
    }

    /**
     * A glob is legal in a field list and is not a field anybody declared.
     *
     * The exclusions a field list already applies must apply to a single-field argument too, or the
     * recognizer reports `*` as a missing field on an `fl` that is entirely correct.
     */
    fun testAGlobArgumentNamesNoField() {
        givenSolrJ()
        assertEmpty(usages(javaFile("""q.addField("*");""")))
    }

    /** A name spelled across a concatenation is read, as it is in Kotlin. */
    fun testLiteralsJoinedByConcatenationAreRead() {
        givenSolrJ()
        val found = usages(javaFile("""q.addFilterQuery("categry" + ":books");"""))

        assertEquals(listOf("categry"), found.map { it.fieldName })
    }

    /** A non-literal argument cannot be read, and is not guessed at. */
    fun testAVariableArgumentIsNotRead() {
        givenSolrJ()
        assertEmpty(usages(javaFile("""String f = "categry:books"; q.addFilterQuery(f);""")))
    }

    /** The match-all query names no field, so a plain constructor produces nothing. */
    fun testTheMatchAllConstructorNamesNoField() {
        givenSolrJ()
        assertEmpty(usages(javaFile("")))
    }

    /**
     * The Solr 10 package spelling resolves too.
     *
     * `SolrQuery` moved from `client.solrj` to `client.solrj.request` between the supported lines
     * with no shim, so a recognizer holding one name is silent on every project using the other.
     */
    fun testTheSolr10PackageSpellingIsRecognized() {
        givenSolrJ("org.apache.solr.client.solrj.request")
        val found = usages(
            javaFile(
                """q.addFilterQuery("categry:books");""",
                import = "org.apache.solr.client.solrj.request.SolrQuery",
            ),
        )
        assertEquals(listOf("categry"), found.map { it.fieldName })
    }

    /** No Solr client on the module, no reading — the gate from the module detector applies here. */
    fun testAModuleWithNoSolrClientIsNotRead() {
        givenSolrJ()
        val file = javaFile("""q.addFilterQuery("categry:books");""")
        givenNoSolrOnTheClasspath()
        assertEmpty(usages(file))
    }
}
