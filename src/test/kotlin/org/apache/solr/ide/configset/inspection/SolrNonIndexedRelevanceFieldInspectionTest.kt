package org.apache.solr.ide.configset.inspection

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The non-indexed relevance-field inspection, on flagged and clean fixtures alike.
 *
 * The clean cases carry the weight. This inspection reads the parameters most crowded with syntax
 * that is not a field name — per-field boosts, function queries, parameter references — and it
 * fires on files that are otherwise entirely correct, which is the position from which a false
 * positive does the most damage.
 *
 * Only this inspection is enabled, so a fixture may name an undeclared field without marking a
 * warning: that finding belongs to `SolrUnknownFieldReferenceInspection` and is asserted there.
 */
class SolrNonIndexedRelevanceFieldInspectionTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name="t" version="1.6">
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text_general" class="solr.TextField"/>
          <fieldType name="archived" class="solr.TextField" indexed="false"/>
          <field name="id" type="string"/>
          <field name="title" type="text_general"/>
          <field name="body" type="text_general" indexed="false" stored="true"/>
          <field name="notes" type="archived"/>
          <field name="rescued" type="archived" indexed="true"/>
          <field name="popularity" type="string" indexed="false" docValues="true"/>
          <field name="orphan" type="nosuchtype"/>
          <field name="orphan_off" type="nosuchtype" indexed="false"/>
          <dynamicField name="*_ns" type="string" indexed="false"/>
          <dynamicField name="*_s" type="string"/>
        </schema>
    """.trimIndent()

    private fun checkConfig(body: String) {
        myFixture.addFileToProject("managed-schema.xml", schema)
        myFixture.enableInspections(SolrNonIndexedRelevanceFieldInspection())
        myFixture.configureByText("solrconfig.xml", "<config>\n$body\n</config>")
        myFixture.checkHighlighting(true, false, false)
    }

    private fun handler(vararg parameters: String) =
        """<requestHandler name="/select"><lst name="defaults">${parameters.joinToString("")}</lst></requestHandler>"""

    private fun notIndexed(field: String, parameter: String) =
        """<warning descr="Solr: '$field' is not indexed, so '$parameter' cannot search or boost it">$field</warning>"""

    // --- clean fixtures, written first -----------------------------------------------------------

    /** The everyday shape: per-field boosts on indexed fields, and nothing to say about any of it. */
    fun testPerFieldBoostsOnIndexedFieldsAreClean() {
        checkConfig(
            handler(
                """<str name="qf">title^2.0 id^0.5</str>""",
                """<str name="pf">title^3</str>""",
                """<str name="pf2">title^1.5</str>""",
                """<str name="pf3">title</str>""",
            ),
        )
    }

    /**
     * A boost function is not a term query. `bf` and `boost` values are evaluated per document out
     * of doc values, so a non-indexed field is exactly what a well-tuned `bf` often names — and the
     * function syntax around it is not a field name at all.
     */
    fun testFunctionQueriesAndNonIndexedBoostFunctionsAreClean() {
        checkConfig(
            handler(
                """<str name="bf">recip(ms(NOW,indexed_date),3.16e-11,1,1)</str>""",
                """<str name="bf">popularity</str>""",
                """<str name="boost">popularity</str>""",
            ),
        )
    }

    /**
     * The parameters that look adjacent and are served by doc values rather than by the index.
     * Sorting and faceting on a non-indexed field is ordinary, and `fl` returns a stored value
     * without ever consulting the index.
     */
    fun testParametersThatDoNotSearchTheIndexAreClean() {
        checkConfig(
            handler(
                """<str name="fl">id,body</str>""",
                """<str name="sort">popularity desc</str>""",
                """<arr name="facet.field"><str>popularity</str></arr>""",
            ),
        )
    }

    /** Syntax that resembles a field name in a `qf`, on a file that is entirely correct. */
    fun testSolrSyntaxInsideAQueryFieldIsNotReported() {
        checkConfig(
            handler(
                """<str name="qf">${'$'}boostParam</str>""",
                """<str name="qf">*</str>""",
                """<str name="qf">score</str>""",
            ),
        )
    }

    /** A field that overrides its type's `indexed="false"` is searchable, and the field wins. */
    fun testAFieldOverridingItsTypeIsClean() {
        checkConfig(handler("""<str name="qf">rescued^2</str>"""))
    }

    /**
     * An undeclared field type removes the middle tier of property resolution without removing the
     * fall-through, so a field declaring nothing would have Solr's default attributed to it while
     * the type that might have overridden it does not exist. That is the unknown-field-type
     * inspection's finding, not this one's.
     */
    fun testAFieldWhoseTypeIsUndeclaredIsNotReported() {
        checkConfig(handler("""<str name="qf">orphan</str>"""))
    }

    /** A dynamic pattern that indexes what it matches has nothing wrong with it. */
    fun testADynamicFieldThatIsIndexedIsClean() {
        checkConfig(handler("""<str name="qf">title_s^2</str>"""))
    }

    /** Parameters carrying no field reference at all are not examined. */
    fun testUnrelatedParametersAreClean() {
        checkConfig(
            handler(
                """<str name="defType">edismax</str>""",
                """<str name="rows">10</str>""",
            ),
        )
    }

    /**
     * `<lst name="defaults">` also appears under things that are not queries — an update processor
     * chain, for one — and a `qf` there configures nothing a searcher will read.
     */
    fun testAParameterListOutsideARequestHandlerIsNotInspected() {
        checkConfig(
            """
            <updateRequestProcessorChain name="x">
              <processor class="solr.Custom">
                <lst name="defaults"><str name="qf">body</str></lst>
              </processor>
            </updateRequestProcessorChain>
            """.trimIndent(),
        )
    }

    /** The schema is not where handler parameters live, so it is not visited at all. */
    fun testTheSchemaFileIsNotInspected() {
        myFixture.enableInspections(SolrNonIndexedRelevanceFieldInspection())
        myFixture.configureByText("managed-schema.xml", schema)
        myFixture.checkHighlighting(true, false, false)
    }

    /** Outside a Solr project every inspection is inert, like every other surface. */
    fun testNothingIsReportedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        checkConfig(handler("""<str name="qf">body</str>"""))
    }

    // --- flagged fixtures ------------------------------------------------------------------------

    /** The defect: a correctly spelled field that the query can never reach. */
    fun testAQueryFieldNamingANonIndexedFieldIsFlagged() {
        checkConfig(handler("""<str name="qf">title^2.0 ${notIndexed("body", "qf")}^0.5</str>"""))
    }

    /** The phrase-field family asks the index the same question `qf` does. */
    fun testThePhraseFieldsAreFlaggedToo() {
        checkConfig(
            handler(
                """<str name="pf">${notIndexed("body", "pf")}</str>""",
                """<str name="pf2">${notIndexed("body", "pf2")}</str>""",
                """<str name="pf3">${notIndexed("body", "pf3")}</str>""",
            ),
        )
    }

    /** The property is inherited: a field declaring nothing takes its type's `indexed="false"`. */
    fun testAFieldInheritingNonIndexedFromItsTypeIsFlagged() {
        checkConfig(handler("""<str name="qf">${notIndexed("notes", "qf")}</str>"""))
    }

    /**
     * A dynamic field is resolved by Solr's own longest-literal rule and its `indexed` is as real as
     * any other's, so a `qf` matching one is as broken.
     */
    fun testADynamicFieldThatIsNotIndexedIsFlagged() {
        checkConfig(handler("""<str name="qf">${notIndexed("title_ns", "qf")}</str>"""))
    }

    /**
     * A field declaring `indexed="false"` on an undeclared type is *not* reported, which reverses what
     * this fixture asserted while the rule read one property.
     *
     * `indexed="false"` is still definite. Searchability is not, because it is a disjunction: doc values
     * would rescue the field, and whether it has them cannot be answered when the type naming their
     * default does not exist. "Not indexed" was a fact this inspection could state; "not searchable" is
     * the claim it actually makes, and it cannot state that one here.
     *
     * **Losing the warning costs the reader nothing**, because the schema is already wrong in a way
     * another inspection reports: the field names a type that is not declared. Adding a speculative
     * second warning on top of a definite first one is not a service.
     */
    fun testAFieldDeclaringNonIndexedItselfWithAnUndeclaredTypeIsNotReported() {
        checkConfig(handler("""<str name="qf">orphan_off</str>"""))
    }

    /** Every occurrence is underlined, not merely the first. */
    fun testEveryOccurrenceIsUnderlined() {
        checkConfig(
            handler("""<str name="qf">${notIndexed("body", "qf")}^2 title ${notIndexed("body", "qf")}^3</str>"""),
        )
    }

    /** An `arr` supplies the parameter name to each `str` inside it. */
    fun testANonIndexedFieldInsideAnArrIsFlagged() {
        checkConfig(
            handler("""<arr name="qf"><str>${notIndexed("body", "qf")}</str><str>title</str></arr>"""),
        )
    }

    /**
     * A doc-values-only field in a `qf` is clean, which reverses what this fixture first recorded.
     *
     * `popularity` is `indexed="false" docValues="true"`, and the clean cases above already rely on
     * that combination being ordinary for `bf`, `sort` and `facet.field`. It was flagged in a `qf`,
     * because the inspection read `indexed` and nothing else.
     *
     * **Solr answers the query.** `FieldType.getFieldQuery` opens with `hasDocValues() && !indexed()`
     * and on that branch delegates to `getRangeQuery` with the value as both bounds, reaching
     * `SortedSetDocValuesField.newSlowRangeQuery` — an exact match becomes a single-value doc-values
     * range query. Slower than a term lookup, and functional. `StrField` overrides none of it, and the
     * branch is byte-identical on both supported Solr lines.
     *
     * The warning was therefore firing on a working configuration. Silence rather than a reworded
     * message is the repair: a doc-values range query is constant-scoring, so a boost still multiplies
     * by the boost, and the field is searchable *and* boostable. What it is not is ranked by term
     * statistics — a distinction too fine for an inspection to be drawing.
     */
    fun testADocValuesOnlyFieldInAQueryFieldIsClean() {
        checkConfig(handler("""<str name="qf">popularity</str>"""))
    }

    /**
     * And the case the inspection was written for is untouched. `TextField` overrides `getFieldQuery`
     * for the analysis path and declares no doc-values support at all, so a non-indexed text field in
     * a `qf` is genuinely unsearchable — no doc values can rescue it.
     */
    fun testANonIndexedTextFieldInAQueryFieldIsStillFlagged() {
        checkConfig(handler("""<str name="qf">${notIndexed("body", "qf")}</str>"""))
    }

    /** `appends` and `invariants` supply the same parameters, differing only in precedence. */
    fun testInvariantsAreInspectedLikeDefaults() {
        checkConfig(
            """<requestHandler name="/select"><lst name="invariants">""" +
                """<str name="qf">${notIndexed("body", "qf")}</str>""" +
                """</lst></requestHandler>""",
        )
    }
}
