package org.apache.solr.ide.code.inspection

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Reporting a field name in Java or Kotlin that no configset in the project declares.
 *
 * **The clean cases come first, and there are more of them than reporting cases.** This inspection
 * runs over ordinary application code rather than over a Solr file, which means the population it
 * examines is mostly *not* about Solr at all — and a warning on a correct string is the thing that
 * gets a plugin switched off. Every silence below is a rule, not an omission.
 *
 * The most important is the one that cannot be seen by reading a single file: the project's
 * configsets are found through the filename index, which answers nothing while the IDE is still
 * indexing. An inspection that trusted that answer would report every field name in the codebase as
 * undeclared, for a minute, on every project open.
 *
 * **No fixture here names `java.lang.String`.** The light project carries no JDK, so it would not
 * resolve, and `checkHighlighting` fails on the resulting error before reaching the warnings this
 * file is about. Names the source does not spell out are covered where they are decided, in
 * `SolrJRecognizerTest`, which asserts the recognizer's output directly and needs no highlighting.
 */
class SolrUnknownCodeFieldInspectionTest : SolrConfigsetTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(SolrUnknownCodeFieldInspection())
    }

    /**
     * A configset declaring [fields], plus a `*_s` pattern and a unique key.
     *
     * Assembled by joining lines rather than by interpolating into a raw string. A multi-line value
     * dropped into `trimIndent` brings its own indentation, which makes the common indent zero and
     * leaves the XML declaration indented — and an `<?xml?>` that is not the first thing in the
     * document is malformed, so the schema silently parses to nothing and every field in it reads as
     * undeclared. That failed exactly one test here, the one declaring two fields.
     */
    private fun givenAConfigsetDeclaring(vararg fields: String) {
        val lines = buildList {
            add("""<?xml version="1.0" encoding="UTF-8"?>""")
            add("""<schema name="test" version="1.6">""")
            add("""  <fieldType name="string" class="solr.StrField"/>""")
            fields.forEach { add("""  <field name="$it" type="string" indexed="true" stored="true"/>""") }
            add("""  <dynamicField name="*_s" type="string" indexed="true" stored="true"/>""")
            add("  <uniqueKey>id</uniqueKey>")
            add("</schema>")
        }
        myFixture.addFileToProject("solr/conf/managed-schema.xml", lines.joinToString("\n"))
    }

    private fun givenSolrJ() {
        myFixture.addFileToProject(
            "org/apache/solr/client/solrj/SolrQuery.java",
            """
            package org.apache.solr.client.solrj;
            public class SolrQuery {
                public SolrQuery(String q) {}
                public SolrQuery addFilterQuery(String... fq) { return this; }
                public SolrQuery setFields(String... fields) { return this; }
            }
            """.trimIndent(),
        )
    }

    private fun searching(body: String) = """
        import org.apache.solr.client.solrj.SolrQuery;
        class Search {
            void go() {
                SolrQuery q = new SolrQuery("*:*");
                $body
            }
        }
    """.trimIndent()

    private fun check(text: String) {
        myFixture.configureByText("Search.java", text)
        myFixture.checkHighlighting()
    }

    // --- the clean file, first --------------------------------------------------------------------

    /** A declared field is not reported, which is the case every other test is measured against. */
    fun testADeclaredFieldIsNotReported() {
        givenSolrJ()
        givenAConfigsetDeclaring("id", "category")

        check(searching("""q.addFilterQuery("category:books");"""))
    }

    /** A name a dynamic pattern covers is declared, even though nothing spells it out. */
    fun testANameADynamicPatternCoversIsNotReported() {
        givenSolrJ()
        givenAConfigsetDeclaring("id")

        check(searching("""q.addFilterQuery("author_s:herbert");"""))
    }

    /**
     * Nothing is reported where the project has no configset at all.
     *
     * The plugin cannot know a name is wrong if it has nothing to check it against, and a warning
     * here would fire in every project that uses SolrJ against a server whose schema lives
     * elsewhere — which is most of them.
     */
    fun testNothingIsReportedWithNoConfigsetToCheckAgainst() {
        givenSolrJ()

        check(searching("""q.addFilterQuery("categry:books");"""))
    }

    /**
     * Solr's own fields are not reported, because no schema declares them.
     *
     * `score` is computed per result and appears in half the field lists ever written.
     */
    fun testSolrsOwnFieldsAreNotReported() {
        givenSolrJ()
        givenAConfigsetDeclaring("id")

        check(searching("""q.setFields("id", "score");"""))
    }

    /** A module with no Solr client is not examined, so nothing in it can be reported. */
    fun testNothingIsReportedWithoutASolrClient() {
        givenSolrJ()
        givenAConfigsetDeclaring("id")
        givenNoSolrOnTheClasspath()

        check(searching("""q.addFilterQuery("categry:books");"""))
    }

    // --- and then what it reports -----------------------------------------------------------------

    fun testAnUndeclaredFieldIsReported() {
        givenSolrJ()
        givenAConfigsetDeclaring("id", "category")

        check(
            searching(
                """q.addFilterQuery("<warning descr="Solr: no field named 'categry' is declared in this project's configsets">categry</warning>:books");""",
            ),
        )
    }

    /** The same name in a bean annotation is reported the same way. */
    fun testAnUndeclaredBeanFieldIsReported() {
        givenAConfigsetDeclaring("id", "price")
        myFixture.addFileToProject(
            "org/apache/solr/client/solrj/beans/Field.java",
            """
            package org.apache.solr.client.solrj.beans;
            public @interface Field { String value() default "#default"; }
            """.trimIndent(),
        )

        myFixture.configureByText(
            "Product.java",
            """
            import org.apache.solr.client.solrj.beans.Field;
            class Product {
                @Field("<warning descr="Solr: no field named 'prce' is declared in this project's configsets">prce</warning>") int price;
            }
            """.trimIndent(),
        )
        myFixture.checkHighlighting()
    }
}
