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
                public SolrQuery setQuery(String q) { return this; }
                public SolrQuery addFilterQuery(String... fq) { return this; }
                public SolrQuery setFields(String... fields) { return this; }
                public SolrQuery addField(String field) { return this; }
                public SolrQuery setRows(Integer rows) { return this; }
                public enum ORDER { desc, asc }
                public SolrQuery setSort(String field, ORDER order) { return this; }
                public SolrQuery addFacetField(String... fields) { return this; }
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

    /**
     * A configset whose fields differ in what they can do, for asserting what each position offers.
     *
     * Schema version 1.7 deliberately, where an indexed field is no longer un-inverted by default — so
     * a text field is searchable and cannot be faceted or sorted, which is the distinction worth
     * testing. `docValues` is written out rather than left to the type's default, so the expectations
     * rest on this file rather than on the default table.
     *
     * - `id`, `sku`: single-valued strings with doc values — every operation
     * - `title`: analysed text — searched, never faceted or sorted
     * - `tags`: multi-valued with doc values — faceted, never sorted
     * - `payload`: stored only — returned in `fl`, and nothing else
     */
    fun givenConfigsetOfCapabilities(fixture: CodeInsightTestFixture) {
        val lines = listOf(
            """<?xml version="1.0" encoding="UTF-8"?>""",
            """<schema name="test" version="1.7">""",
            """  <fieldType name="string" class="solr.StrField"/>""",
            """  <fieldType name="text" class="solr.TextField"/>""",
            """  <field name="id" type="string" indexed="true" stored="true" docValues="true"/>""",
            """  <field name="sku" type="string" indexed="true" stored="true" docValues="true"/>""",
            """  <field name="title" type="text" indexed="true" stored="true"/>""",
            """  <field name="tags" type="string" indexed="true" stored="true" docValues="true" multiValued="true"/>""",
            """  <field name="payload" type="string" indexed="false" stored="true" docValues="false"/>""",
            """  <dynamicField name="*_s" type="string" indexed="true" stored="true" docValues="true"/>""",
            "  <uniqueKey>id</uniqueKey>",
            "</schema>",
        )
        fixture.addFileToProject("solr/conf/managed-schema.xml", lines.joinToString("\n"))
    }
}
