package org.apache.solr.ide.code

import com.intellij.testFramework.fixtures.CodeInsightTestFixture

/**
 * The fixtures the code-track tests share.
 *
 * **Stubs rather than the real jar, and that is the point of collecting them.** Every recognizer
 * here matches a qualified name and a method name, so a source file declaring both answers exactly
 * the question the code asks without putting SolrJ into the build for the sake of a test. Written
 * once because a stub that drifts between two test files is two different SolrJs, and the tests
 * would disagree about which one the plugin supports.
 */
object SolrCodeFixtures {

    /** Everything of SolrJ these tests resolve against: the query builder and the annotation. */
    fun givenSolrJ(fixture: CodeInsightTestFixture) {
        givenSolrQuery(fixture)
        givenBeanAnnotation(fixture)
    }

    /** SolrJ's query builder, carrying the methods the recognizer maps. */
    fun givenSolrQuery(fixture: CodeInsightTestFixture) {
        fixture.addFileToProject(
            "org/apache/solr/client/solrj/SolrQuery.java",
            """
            package org.apache.solr.client.solrj;
            public class SolrQuery {
                public SolrQuery(String q) {}
                public SolrQuery addFilterQuery(String... fq) { return this; }
                public SolrQuery setFields(String... fields) { return this; }
                public SolrQuery addField(String field) { return this; }
                public SolrQuery setRows(Integer rows) { return this; }
            }
            """.trimIndent(),
        )
    }

    /** SolrJ's bean-binding annotation, with the `#default` sentinel it really carries. */
    fun givenBeanAnnotation(fixture: CodeInsightTestFixture) {
        fixture.addFileToProject(
            "org/apache/solr/client/solrj/beans/Field.java",
            """
            package org.apache.solr.client.solrj.beans;
            public @interface Field { String value() default "#default"; }
            """.trimIndent(),
        )
    }

    /**
     * A configset declaring [fields], plus a `*_s` pattern and a unique key.
     *
     * Assembled by joining lines rather than by interpolating into a raw string. A multi-line value
     * dropped into `trimIndent` brings its own indentation, which makes the common indent zero and
     * leaves the XML declaration indented — and an `<?xml?>` that is not the first thing in the
     * document is malformed, so the schema parses to nothing and every field in it reads as
     * undeclared. That cost one debugging round already.
     */
    fun givenConfigsetDeclaring(fixture: CodeInsightTestFixture, vararg fields: String) {
        val lines = buildList {
            add("""<?xml version="1.0" encoding="UTF-8"?>""")
            add("""<schema name="test" version="1.6">""")
            add("""  <fieldType name="string" class="solr.StrField"/>""")
            fields.forEach { add("""  <field name="$it" type="string" indexed="true" stored="true"/>""") }
            add("""  <dynamicField name="*_s" type="string" indexed="true" stored="true"/>""")
            add("  <uniqueKey>id</uniqueKey>")
            add("</schema>")
        }
        fixture.addFileToProject("solr/conf/managed-schema.xml", lines.joinToString("\n"))
    }
}
