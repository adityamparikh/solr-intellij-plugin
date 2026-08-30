package org.apache.solr.ide.server.indexing

import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.schema.SolrFieldType

/**
 * A test document written from what a schema declares.
 *
 * **A starting point, not a fixture.** What it produces is meant to be edited before it is indexed —
 * the values are placeholders chosen to be obviously placeholders, because a sample document whose
 * values look real is one somebody indexes without reading.
 *
 * The unique key comes first and the required fields follow, because those are the two things a
 * document cannot omit and the two a user editing this must not delete by accident.
 */
object SolrSampleDocument {

    /** What a generated document says where a value has to be something. */
    const val PLACEHOLDER: String = "example"

    /**
     * A document for [facts], as formatted JSON.
     *
     * Only fields the schema *requires* and the unique key are included. A schema of two hundred
     * fields would otherwise produce a document nobody reads, and every optional field a user did
     * not want is one they have to delete before indexing — which is more work than adding the two
     * they did.
     *
     * Internal fields are left out. `_version_` is Solr's to manage, and a document supplying one
     * is asserting an optimistic-concurrency check it did not mean to make.
     *
     * @param facts the schema to write a document for
     * @return the document as formatted JSON, ready to be edited
     */
    fun forSchema(facts: SolrConfigsetFacts): String {
        val typesByName = facts.fieldTypes.associateBy { it.name }
        val included = buildList {
            facts.uniqueKey?.let { key -> facts.fields.firstOrNull { it.name == key }?.let(::add) }
            addAll(
                facts.fields.filter { it.required == true && it.name != facts.uniqueKey && !it.isInternal() },
            )
        }.distinctBy { it.name }

        if (included.isEmpty()) return "{\n  \n}"
        return included.joinToString(",\n", prefix = "{\n", postfix = "\n}") { field ->
            """  "${field.name}": ${valueFor(field, typesByName[field.type])}"""
        }
    }

    /**
     * A placeholder of the right JSON shape for [field].
     *
     * **The shape matters more than the value.** A number field given `"example"` is rejected by
     * Solr with a parse error that reads as the plugin's fault; a number field given `1` indexes,
     * and the user replaces it with the number they meant.
     */
    private fun valueFor(field: SolrField, type: SolrFieldType?): String {
        val value = when {
            type == null -> quoted(PLACEHOLDER)
            type.className.isNumeric() -> if (type.className.isIntegral()) "1" else "1.0"
            type.className.isBoolean() -> "true"
            type.className.isDate() -> quoted("2026-01-01T00:00:00Z")
            else -> quoted(PLACEHOLDER)
        }
        // A multiValued field takes an array, and Solr rejects a bare value for one — so the shape
        // has to follow the declaration rather than the type alone.
        return if (field.multiValued == true) "[$value]" else value
    }

    private fun quoted(value: String) = "\"$value\""

    private fun String.isNumeric() = INTEGRAL.any { contains(it) } || FRACTIONAL.any { contains(it) }

    private fun String.isIntegral() = INTEGRAL.any { contains(it) }

    private fun String.isBoolean() = contains("BoolField")

    private fun String.isDate() = contains("DatePoint") || contains("TrieDate") || contains("DateRange")

    // Matched on the class rather than the type's name, because a type may be called anything —
    // `books_price` is as legal a name as `pfloat` — while the class it names is Solr's own.
    private val INTEGRAL = listOf("IntPoint", "LongPoint", "TrieInt", "TrieLong")
    private val FRACTIONAL = listOf("FloatPoint", "DoublePoint", "TrieFloat", "TrieDouble")

    private fun SolrField.isInternal() = name.length > 2 && name.startsWith('_') && name.endsWith('_')
}
