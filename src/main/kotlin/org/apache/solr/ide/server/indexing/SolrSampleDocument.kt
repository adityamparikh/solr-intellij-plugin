package org.apache.solr.ide.server.indexing

import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.schema.SolrFieldType
import org.apache.solr.ide.model.schema.SolrGlob

/**
 * A test document written from what a schema declares.
 *
 * **A starting point, not a fixture.** What it produces is meant to be edited before it is indexed —
 * the values are placeholders chosen to be obviously placeholders, because a sample document whose
 * values look real is one somebody indexes without reading.
 *
 * The unique key comes first and the required fields follow, because those are the two things a
 * document cannot omit and the two a user editing this must not delete by accident. Every other
 * field a user would fill comes after, in the order the schema declares them.
 */
object SolrSampleDocument {

    /** What a generated document says where a value has to be something. */
    const val PLACEHOLDER: String = "example"

    /**
     * A document for [facts], as formatted JSON.
     *
     * **Every field a user would fill, not only the ones they must.** A document of only an id
     * tests nothing about a schema, and the names a user needs are exactly the ones they would
     * otherwise have to look up and spell; deleting a line they did not want is cheaper. Declared
     * fields only — a dynamic pattern is a rule for names, not a name, and one invented for it is a
     * field nobody asked for.
     *
     * Left out, each because a value supplied for it is not what the user meant:
     * - **Solr's internal fields**, any name starting and ending with `_`. `_version_` asserts an
     *   optimistic-concurrency check, `_root_` and `_nest_path_` a nesting decision, and `_text_` is
     *   a catch-all Solr fills by copying.
     * - **Copy-field destinations.** Solr fills them from their sources; a value supplied as well is
     *   added beside the copy rather than replacing it.
     * - **Fields that keep nothing** — neither indexed, stored nor docValues, as Solr's `ignored`
     *   type is. Only an explicit `false`, on the field or its type, counts: the Schema API reports
     *   what was written, and an unset flag inherits a default that is usually `true`.
     *
     * The unique key is always kept, whatever else is true of it.
     *
     * @param facts the schema to write a document for
     * @return the document as formatted JSON, ready to be edited
     */
    fun forSchema(facts: SolrConfigsetFacts): String {
        val typesByName = facts.fieldTypes.associateBy { it.name }
        val candidates = facts.fields.filter { field ->
            field.name != facts.uniqueKey &&
                !field.isInternal() &&
                !field.isCopyDestination(facts) &&
                !field.keepsNothing(typesByName[field.type])
        }
        val included = buildList {
            facts.uniqueKey?.let { key -> facts.fields.firstOrNull { it.name == key }?.let(::add) }
            addAll(candidates.filter { it.required == true })
            addAll(candidates.filter { it.required != true })
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

    // A destination may be a glob when its source is one (`*` to `*_str`), so it is matched rather
    // than compared.
    private fun SolrField.isCopyDestination(facts: SolrConfigsetFacts) =
        facts.copyFields.any { SolrGlob.matches(it.destination, name) }

    private fun SolrField.keepsNothing(type: SolrFieldType?): Boolean {
        fun flag(declared: Boolean?, attribute: String) =
            declared ?: type?.attributes?.get(attribute)?.toBooleanStrictOrNull()
        return flag(indexed, "indexed") == false &&
            flag(stored, "stored") == false &&
            flag(docValues, "docValues") == false
    }
}
