package org.apache.solr.ide.build

/**
 * The SPI name a tokenizer, filter or char filter factory declares.
 *
 * Solr accepts two spellings for the same component — `class="solr.LowerCaseFilterFactory"` and
 * `name="lowercase"` — and the second cannot be derived from the first. `LowerCaseFilterFactory` is
 * `lowercase` rather than `lowerCase`, `UAX29URLEmailTokenizerFactory` is `uax29URLEmail`, and
 * `KStemFilterFactory` is `kStem`. Every rule that would produce one of those breaks on another, so
 * the name is read from the `NAME` constant Lucene requires each factory to declare, in the same
 * spirit as the attribute names: what Solr actually says, rather than what a transformation of the
 * class name suggests it might.
 */
internal object SolrSpiNames {

    /** The field every SPI-registered analysis factory declares its short name in. */
    private const val NAME_FIELD = "NAME"

    /**
     * The SPI name among [constants], or null if the class declares none.
     *
     * @param constants every string constant on the class, as field name to value
     * @return the value of the `NAME` field, or null if there is no such field
     */
    fun of(constants: List<Pair<String, String>>): String? =
        constants.firstOrNull { (field, _) -> field == NAME_FIELD }?.second
}
