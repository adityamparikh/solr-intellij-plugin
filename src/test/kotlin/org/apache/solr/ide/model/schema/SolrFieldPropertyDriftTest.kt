package org.apache.solr.ide.model.schema

import org.apache.solr.ide.model.SolrVersionSelection
import org.apache.solr.ide.model.vocabulary.SolrAttributeVocabulary
import org.apache.solr.ide.model.vocabulary.SolrClassCatalog
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The field vocabulary, held against what solr-core actually accepts.
 *
 * [SolrFieldProperties] is hand-maintained and [SolrAttributeVocabulary.closedVocabularyFor] treats
 * it as complete, so the one failure mode neither can see is Solr moving: a line adding an accepted
 * field property turns a correct file into a warning, and nothing notices until a user reports it.
 * The generator reads the accepted names out of `FieldProperties` in each supported line's own
 * bytecode; holding the vocabulary against that list makes drift a build failure that names the
 * missing attribute.
 *
 * The two directions guard each other. If extraction ever broke and produced an empty or short
 * list, the first test would pass vacuously — and the second would fail, because the vocabulary
 * would suddenly claim names the list lacks.
 */
class SolrFieldPropertyDriftTest {

    /**
     * Names the vocabulary accepts for the plugin's own reasons, not because Solr's parser does.
     *
     * `name` and `type` are consumed before the property parser runs, and `source`/`dest` belong to
     * `copyField`. Solr would actually reject `class` on a `<field>`, but the vocabulary keeps the
     * structural set whole — a missed error is the safe direction, and the simplicity is the point.
     */
    private val structural = setOf("class", "name", "type", "source", "dest")

    private fun acceptedBySolr(line: Int): Set<String> {
        val resource = "/solr-catalog/field-properties-$line.txt"
        val stream = checkNotNull(SolrClassCatalog::class.java.getResourceAsStream(resource)) {
            "$resource was not generated"
        }
        return stream.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toSet()
    }

    private fun legalOnField(line: Int): Set<String> = checkNotNull(
        SolrAttributeVocabulary.closedVocabularyFor(
            "field",
            null,
            SolrVersionSelection.fromLuceneMatchVersion("$line.0.0"),
        ),
    ) { "a <field> must have a closed vocabulary" }

    @Test
    fun `every field attribute a supported line accepts is legal`() {
        for (line in SolrClassCatalog.SUPPORTED_LINES) {
            val legal = legalOnField(line)
            for (name in acceptedBySolr(line)) {
                assertTrue(
                    "Solr $line accepts '$name' on a <field>; reporting it would underline a correct file",
                    name in legal,
                )
            }
        }
    }

    @Test
    fun `the vocabulary claims nothing a supported line rejects`() {
        for (line in SolrClassCatalog.SUPPORTED_LINES) {
            val accepted = acceptedBySolr(line)
            for (name in legalOnField(line) - structural) {
                assertTrue(
                    "Solr $line does not accept '$name' on a <field>; the vocabulary is ahead of Solr",
                    name in accepted,
                )
            }
        }
    }
}
