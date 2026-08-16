package org.apache.solr.ide.configset.solrconfig.inspection

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * An element Solr no longer accepts, reported in Solr's own words.
 *
 * **The one finding here that is not advice.** Four of the five elements Solr retired stop the core
 * starting — `<indexDefaults>` and `<mainIndex>` raise `SolrException`, `<nrtMode>` and
 * `<unlockOnStartup>` fail one level down — so this does not report a file that could be tidier, it
 * reports a file that will not load. `<jmx>` is the one Solr merely logs about, and it is reported the
 * same way because Solr's own sentence is the message.
 */
class SolrDiscontinuedElementInspectionTest : SolrConfigsetTestCase() {

    private fun check(body: String) {
        myFixture.enableInspections(SolrDiscontinuedElementInspection())
        myFixture.addFileToProject(
            "managed-schema.xml",
            """<schema name="t" version="1.7"><fieldType name="string" class="solr.StrField"/></schema>""",
        )
        myFixture.configureByText("solrconfig.xml", body)
        myFixture.checkHighlighting(true, false, true)
    }

    /** Solr's own wording, because paraphrasing it would lose the replacement it names. */
    fun testAnElementSolrDiscontinuedIsReported() {
        check(
            """
            <config>
              <indexConfig>
                <<warning descr="Solr: The <nrtMode> config has been discontinued and NRT mode is always used by Solr. This config will be removed in future versions.">nrtMode</warning>>true</nrtMode>
              </indexConfig>
            </config>
            """.trimIndent(),
        )
    }

    /**
     * And nothing is reported one level up, where Solr never reads it.
     *
     * This is the position the rule used to fire on, and it fired there for a reason worth keeping in
     * front of a reader: `SolrConfig` reaches this element as `get(indexConfigPrefix).get("nrtMode")`,
     * so the parent arrives through a local variable that the catalog generator could not follow. It
     * recorded no parent; an absent parent already meant *top level*; and the rule inherited a
     * confident claim about a position Solr ignores. A `<nrtMode>` directly under `<config>` is
     * inert — Solr neither reads nor complains about it — so reporting it was the one thing an
     * inspection here must never do.
     */
    fun testNothingIsReportedWhereSolrDoesNotReadTheElement() {
        check(
            """
            <config>
              <nrtMode>true</nrtMode>
            </config>
            """.trimIndent(),
        )
    }

    /**
     * The message carries the replacement, which is the reason Solr's sentence is used unedited.
     *
     * `<indexDefaults>` is the case that makes the point: a reader told only that it is discontinued
     * still has a file full of settings and nowhere to put them. Solr's own sentence names
     * `<indexConfig>`.
     */
    fun testTheMessageNamesTheReplacementSolrNames() {
        check(
            """
            <config>
              <<warning descr="Solr: <indexDefaults> and <mainIndex> configuration sections are discontinued. Use <indexConfig> instead.">indexDefaults</warning>>
                <useCompoundFile>false</useCompoundFile>
              </indexDefaults>
            </config>
            """.trimIndent(),
        )
    }

    /**
     * An ordinary configset draws nothing, which is the failure that would matter.
     *
     * Every element here is one Solr still reads, and several sit beside the retired ones in the
     * vocabulary — `<indexConfig>` is literally the replacement named by the notice above.
     */
    fun testACurrentConfigsetIsSilent() {
        check(
            """
            <config>
              <luceneMatchVersion>10.0.0</luceneMatchVersion>
              <dataDir>${'$'}{solr.data.dir:}</dataDir>
              <indexConfig>
                <useCompoundFile>false</useCompoundFile>
              </indexConfig>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults">
                  <str name="rows">10</str>
                </lst>
              </requestHandler>
            </config>
            """.trimIndent(),
        )
    }

    /**
     * A custom component's element draws nothing, which is the ordinary case rather than an edge one.
     *
     * Never validation by absence: an element the vocabulary does not carry is not thereby wrong, and
     * `solrconfig.xml` accepts components from outside Solr that declare elements of their own.
     */
    fun testAnElementNoVocabularyCarriesIsSilent() {
        check(
            """
            <config>
              <acmeRankingConfig>
                <acmeProfile>aggressive</acmeProfile>
              </acmeRankingConfig>
            </config>
            """.trimIndent(),
        )
    }

    /**
     * The rule claims to run while the project is indexing, and this is what holds the claim.
     *
     * It reads a generated resource and the already-parsed model, so there is nothing here to wait for
     * — but a declaration is cheap to write and stays green forever whether or not the code beneath it
     * grew an index read, which is why the plugin asserts them rather than trusting them.
     */
    fun testTheRuleDeclaresItselfDumbAware() {
        assertTrue(SolrDiscontinuedElementInspection().isDumbAware)
    }

    /**
     * The other configset file draws nothing, whatever it happens to contain.
     *
     * Both files are XML and sit in the same directory, so the name is all that separates them. This
     * schema carries a `<nrtMode>` on purpose: if the rule ran here it would report it, since the
     * vocabulary it consults knows nothing about which file it was asked about.
     */
    fun testASchemaIsNotThisRulesFile() {
        myFixture.enableInspections(SolrDiscontinuedElementInspection())
        myFixture.addFileToProject("solrconfig.xml", "<config/>")
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="t" version="1.7">
              <fieldType name="string" class="solr.StrField"/>
              <indexConfig>
                <nrtMode>true</nrtMode>
              </indexConfig>
            </schema>
            """.trimIndent(),
        )
        myFixture.checkHighlighting(true, false, true)
    }

    /**
     * The detection master switch silences this too, and it has to do so deliberately.
     *
     * The vocabulary is a generated resource, so this rule could answer with no configset around it at
     * all. Answering would make it the one surface a reader cannot turn off.
     */
    fun testNothingIsReportedWhileDetectionIsSwitchedOff() {
        settings.setDetectionEnabled(false)
        check(
            """
            <config>
              <indexConfig>
                <nrtMode>true</nrtMode>
              </indexConfig>
            </config>
            """.trimIndent(),
        )
    }

    /**
     * The same name somewhere Solr never read it draws nothing.
     *
     * A retirement notice belongs to a position, not to a word. `nrtMode` under a custom component is
     * that component's business, and reporting it would be the plugin claiming ownership of a name it
     * only recognises from somewhere else in the file.
     */
    fun testTheNameIsOnlyRetiredWhereSolrReadIt() {
        check(
            """
            <config>
              <acmeRankingConfig>
                <nrtMode>true</nrtMode>
              </acmeRankingConfig>
            </config>
            """.trimIndent(),
        )
    }
}
