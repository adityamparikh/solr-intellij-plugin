package org.apache.solr.ide.configset.solrconfig.inspection

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The one rule the parameter catalog can prove wrong.
 *
 * **The clean cases carry the weight, and there are more of them than flagged ones on purpose.** A
 * parameter name the catalog does not carry is the ordinary case in this file, so the failure that
 * matters is a warning on a correct configset — not a missed typo.
 */
class SolrMisspelledParameterInspectionTest : SolrConfigsetTestCase() {

    private fun configure(body: String) {
        myFixture.enableInspections(SolrMisspelledParameterInspection())
        myFixture.addFileToProject(
            "managed-schema.xml",
            """<schema name="t" version="1.7"><fieldType name="string" class="solr.StrField"/></schema>""",
        )
        myFixture.configureByText("solrconfig.xml", body)
    }

    private fun check(body: String) {
        configure(body)
        myFixture.checkHighlighting(true, false, true)
    }

    private fun handler(parameters: String) = """
        <config>
          <requestHandler name="/select" class="solr.SearchHandler">
            <lst name="defaults">
        $parameters
            </lst>
          </requestHandler>
        </config>
    """.trimIndent()

    /** A transposition, which is the commonest typo and the reason two edits are allowed. */
    fun testATransposedParameterNameIsReported() {
        check(
            handler(
                """      <str name="<warning descr="Solr: no request parameter is named 'rwos' — did you mean 'rows'?">rwos</warning>">10</str>""",
            ),
        )
    }

    /** Nothing fires on the name spelled correctly. */
    fun testTheCorrectSpellingIsNotReported() {
        check(handler("""      <str name="rows">10</str>"""))
    }

    /**
     * The guarantee this rule exists under.
     *
     * Solr's parameter families contain genuinely different names one edit apart. A rule comparing
     * every name against every other would report each of these as a misspelling of the other, in a
     * file that is completely correct — so knownness is checked first and decides the matter.
     */
    fun testTwoRealParametersOneEditApartAreBothQuiet() {
        check(
            handler(
                """      <str name="pf2">title^2</str>
      <str name="pf3">title^3</str>""",
            ),
        )
    }

    /**
     * A custom component's parameter draws nothing, which is what "never validation by absence"
     * means here. This is the ordinary case in a real configset, not an edge one.
     */
    fun testACustomParameterIsNotReported() {
        check(handler("""      <str name="acmeRankingProfile">aggressive</str>"""))
    }

    /**
     * A name the catalog knows as the *stem* of a family is not a misspelling of its own members.
     *
     * **Found by running this rule over the configsets Solr ships, where it fired on all four.**
     * `<str name="spellcheck">on</str>` is how every one of them turns the spell checker on, and it
     * was reported as a typo of `spellcheck.q`. Solr's convention is that `X` enables a component and
     * `X.*` configures it, so a name with known members below it is a parameter whatever else the
     * catalog does or does not carry.
     *
     * The catalog is missing this one for a reason that will recur: `SpellingParams` declares only the
     * `spellcheck.` prefix — dropped by the generator's ends-with-a-dot rule, correctly — while the
     * bare toggle lives on `SpellCheckComponent`. Every other component's toggle happens to be
     * declared twice and survives by luck. That makes this a rule about the shape of the vocabulary
     * rather than a patch for one name.
     */
    fun testAParameterFamilyRootIsNotATypoOfItsOwnMember() {
        check(handler("""      <str name="spellcheck">on</str>"""))
    }

    /**
     * The fix is applied, not merely offered.
     *
     * Offering was never the half that broke. `SolrReplaceNameQuickFix` once listed the right
     * spellings and did nothing when one was chosen, because it only knew how to write one of the two
     * positions it is asked for — and every test that stopped at `getAllQuickFixes` passed throughout
     * that. An inspection that reports a name while withholding the repair is doing half a job, and it
     * computed the list of right names in order to decide there was a wrong one.
     */
    fun testApplyingTheFixRepairsTheParameterName() {
        configure(handler("""      <str name="rw<caret>os">10</str>"""))
        val fix = myFixture.getAllQuickFixes().first { it.text.contains("rows") }
        myFixture.launchAction(fix)
        assertTrue(
            "expected the parameter renamed, got: ${myFixture.file.text}",
            myFixture.file.text.contains("""<str name="rows">10</str>"""),
        )
        assertFalse(myFixture.file.text.contains("rwos"))
    }

    /**
     * A component from outside Solr, and every parameter it reads, draws nothing.
     *
     * The whole configuration is the author's: Solr's catalog has never heard of the class or of a
     * single name under it. This is the shape the rule most has to stay quiet on, because a warning
     * here would land on every project that ships a plugin of its own.
     */
    fun testACustomComponentAndAllItsParametersAreQuiet() {
        check(
            """
            <config>
              <requestHandler name="/rank" class="com.acme.solr.RankingHandler">
                <lst name="defaults">
                  <str name="acmeRankingProfile">aggressive</str>
                  <str name="acmeFallbackMode">lenient</str>
                  <int name="acmeCandidateDepth">500</int>
                </lst>
              </requestHandler>
            </config>
            """.trimIndent(),
        )
    }

    /** A short name is not checked at all: within two edits of four letters lies most of Solr. */
    fun testAShortUnknownNameIsNotReported() {
        check(handler("""      <str name="zqx">1</str>"""))
    }

    /**
     * The detection master switch silences this rule too, and that is not automatic here.
     *
     * Every other rule in the plugin needs the model for its answer, so switching detection off
     * silences them by taking away what they read. This one reads only the generated catalog and
     * could answer without a configset — so it has to decline deliberately, or it becomes the single
     * surface a reader cannot turn off. The same null model is what a project with no Solr dependency
     * presents, which is the case the manual root exists for.
     */
    fun testNothingIsReportedWhileDetectionIsSwitchedOff() {
        settings.setDetectionEnabled(false)
        check(handler("""      <str name="rwos">10</str>"""))
    }
}
