package org.apache.solr.ide.code.solrj

import com.intellij.psi.PsiFile
import org.apache.solr.ide.code.SolrRecognizers
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The same SolrJ constructs, written in Kotlin, read by the same recognizer.
 *
 * **This suite is the evidence for a decision, not extra coverage.** The recognizer is written
 * against UAST rather than Java PSI for one stated reason: that a single implementation serves both
 * JVM languages. Until this file existed that claim was only asserted — every fixture was Java, so
 * the suite was green whether or not Kotlin worked, and it did not. The cases below mirror
 * [SolrJRecognizerTest] deliberately, including the ones that must stay silent, because parity is
 * the property being tested and a construct read in one language and not the other is the defect.
 *
 * The `SolrQuery` stub stays Java, which is also what a real project has: SolrJ is a Java library
 * whether or not the code calling it is.
 */
class SolrJRecognizerKotlinTest : SolrConfigsetTestCase() {

    /** Enough of SolrJ for the receiver type to resolve, exactly as the Java suite supplies it. */
    private fun givenSolrJ() {
        myFixture.addFileToProject(
            "org/apache/solr/client/solrj/SolrQuery.java",
            """
            package org.apache.solr.client.solrj;
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

    private fun kotlinFile(body: String, name: String = "Search"): PsiFile =
        myFixture.addFileToProject(
            "src/$name.kt",
            """
            import org.apache.solr.client.solrj.SolrQuery
            fun go$name() {
                val q = SolrQuery("*:*")
                $body
            }
            """.trimIndent(),
        )

    // Through the dispatcher rather than the recognizer, because that is where the module gate now
    // lives and therefore what a consumer actually calls. Asking the recognizer directly would test
    // a path nothing in the plugin takes.
    private fun usages(file: PsiFile) = SolrRecognizers.fieldUsagesIn(file)

    // --- the constructs that must be read, mirroring the Java suite -------------------------------

    /** The demo's planted defect, written in Kotlin. */
    fun testAFilterQueryNamesTheFieldInsideIt() {
        givenSolrJ()
        val found = usages(kotlinFile("""q.addFilterQuery("categry:books")"""))

        assertEquals(listOf("categry"), found.map { it.fieldName })
        assertEquals(listOf("fq"), found.map { it.parameter })
    }

    /** A field list is one argument holding several names. */
    fun testAFieldListNamesEachOfItsFields() {
        givenSolrJ()
        val found = usages(kotlinFile("""q.setFields("id,name,price")""", name = "Fields"))

        assertEquals(listOf("id", "name", "price"), found.map { it.fieldName })
        assertTrue(found.all { it.parameter == "fl" })
    }

    /** A single-field method holds one name. */
    fun testASingleFieldArgumentIsOneName() {
        givenSolrJ()
        val found = usages(kotlinFile("""q.addField("price")""", name = "Single"))

        assertEquals(listOf("price"), found.map { it.fieldName })
    }

    /** Each vararg of a facet call is its own field. */
    fun testEveryArgumentOfAVarargsCallIsRead() {
        givenSolrJ()
        val found = usages(kotlinFile("""q.addFacetField("cat", "manu")""", name = "Varargs"))

        assertEquals(listOf("cat", "manu"), found.map { it.fieldName })
    }

    // --- silence, which must match the Java suite exactly -----------------------------------------

    /** A method that is not on the map is not read. */
    fun testAnUnmappedMethodIsNotRead() {
        givenSolrJ()
        assertEmpty(usages(kotlinFile("""q.setRows(10)""", name = "Rows")))
    }

    /** A glob is legal in a field list and names no field. */
    fun testAGlobArgumentNamesNoField() {
        givenSolrJ()
        assertEmpty(usages(kotlinFile("""q.addField("*")""", name = "Glob")))
    }

    /**
     * A value held in a variable is not followed, in Kotlin as in Java.
     *
     * This is the case that decides the shape of the argument check rather than merely exercising
     * it. UAST's `evaluateString` would read this one — it follows a Kotlin `val` while declining a
     * Java local and even a Java `static final` — so adopting it would make the two languages
     * disagree, which is the outcome writing against UAST was chosen to prevent.
     */
    fun testAVariableArgumentIsNotRead() {
        givenSolrJ()
        assertEmpty(
            usages(
                kotlinFile("""val f = "categry:books"; q.addFilterQuery(f)""", name = "Variable"),
            ),
        )
    }

    /**
     * A name spelled across a concatenation is read, as it is in Java.
     *
     * The case the recursion exists for. Kotlin's operands here are themselves templates rather
     * than literals, so a check that looked only one level down would read the Java spelling of
     * this and not the Kotlin one.
     */
    fun testLiteralsJoinedByConcatenationAreRead() {
        givenSolrJ()
        val found = usages(kotlinFile("""q.addFilterQuery("categry" + ":books")""", name = "Concat"))

        assertEquals(listOf("categry"), found.map { it.fieldName })
    }

    /**
     * An interpolated string is not read, and this construct exists only in Kotlin.
     *
     * The part of the name that is known cannot be checked without inventing the part that is not,
     * so the whole reference is left alone.
     */
    fun testAnInterpolatedStringIsNotRead() {
        givenSolrJ()
        assertEmpty(
            usages(
                kotlinFile("""val p = "cat"; q.addFilterQuery("${'$'}p:books")""", name = "Interpolated"),
            ),
        )
    }

    /** A call on a class that is not SolrJ's `SolrQuery` is not read. */
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
            "src/Unrelated.kt",
            """
            import com.example.Other
            fun goUnrelated() {
                Other().addFilterQuery("categry:books")
            }
            """.trimIndent(),
        )
        assertEmpty(usages(file))
    }

    /** No Solr client on the module, no reading — the gate applies whatever the language. */
    fun testAModuleWithNoSolrClientIsNotRead() {
        givenSolrJ()
        val file = kotlinFile("""q.addFilterQuery("categry:books")""", name = "Gated")
        givenNoSolrOnTheClasspath()
        assertEmpty(usages(file))
    }
}
