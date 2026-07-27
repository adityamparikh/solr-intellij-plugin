package org.apache.solr.ide.configset.documentation

import org.apache.solr.ide.model.SolrFieldModel

/**
 * What each element of a Solr schema is, and what a particular one of them does.
 *
 * **The general half is hand-maintained, and that is the same exception match analysis and the
 * field properties take.** There are eight elements, they have been stable across Solr majors, and
 * they define the vocabulary rather than enumerating what happens to exist. The generated factory
 * catalog covers analysis factories, which is a different set reached by different machinery.
 *
 * **The specific half is the part worth having.** A reader hovering `<copyField>` can find out what
 * a copy rule is from the Reference Guide; what they cannot find anywhere else is that *this* rule
 * joins `name` to `text`, and that its source is not a field this schema declares. Everything here
 * that can be answered from the model is.
 */
internal object SolrSchemaElements {

    /**
     * One element the plugin can explain.
     *
     * @property tagName the element name as written in the schema
     * @property summary what the element is, in one or two sentences
     */
    data class Description(val tagName: String, val summary: String)

    /**
     * The elements legal directly inside [parentTag], or empty when nothing is known about it.
     *
     * Nesting is checked rather than offering every element everywhere. A `<copyField>` inside an
     * `<analyzer>` is not a thing, and a completion list that includes it is teaching the reader
     * something false about Solr.
     *
     * @param parentTag the element the caret sits inside, or null at the file root
     * @return the elements that may be written there
     */
    fun childrenOf(parentTag: String?): List<Description> = when (parentTag) {
        null -> listOfNotNull(forTag("schema"))
        "schema" -> listOfNotNull(
            forTag("field"), forTag("dynamicField"), forTag("fieldType"),
            forTag("copyField"), forTag("uniqueKey"),
        )
        "fieldType", "fieldtype" -> listOfNotNull(forTag("analyzer"))
        else -> emptyList()
    }

    /**
     * The description for [tagName], or null if this is not an element the plugin explains.
     *
     * @param tagName an element name from a schema
     * @return the description, or null
     */
    fun forTag(tagName: String): Description? = BY_TAG[tagName]

    /**
     * What *this* element does, given the configset it sits in — or null when nothing specific can
     * be said.
     *
     * This is the half no external documentation can supply, and the reason hovering an element is
     * worth more than a link. Where the model can answer, it answers about the configset in front
     * of the user rather than about Solr in general.
     *
     * @param tagName the element being hovered
     * @param attributes its attributes, by name
     * @param model the configset's model
     * @return a sentence about this particular element, or null
     */
    fun specifics(tagName: String, attributes: Map<String, String>, model: SolrFieldModel): String? =
        when (tagName) {
            "schema" -> buildString {
                append("This schema declares ${model.fields.size} field")
                if (model.fields.size != 1) append("s")
                append(", ${model.dynamicFields.size} dynamic field")
                if (model.dynamicFields.size != 1) append("s")
                append(" and ${model.fieldTypes.size} field type")
                if (model.fieldTypes.size != 1) append("s")
                append(".")
                model.uniqueKey?.let { append(" Its unique key is <code>${it.effective}</code>.") }
            }
            "copyField" -> copyFieldSpecifics(attributes, model)
            "uniqueKey" -> model.uniqueKey?.let { key ->
                val resolved = model.resolve(key.effective)
                if (resolved == null) {
                    "This names <code>${key.effective}</code>, which this schema does not declare."
                } else {
                    "This schema's unique key is <code>${key.effective}</code>, of type " +
                        "<code>${resolved.type}</code>."
                }
            }
            "fieldType" , "fieldtype" -> attributes["name"]?.let { name ->
                val users = model.fields.values.map { it.effective }.count { it.type == name }
                "<code>$name</code> is used by $users field${if (users == 1) "" else "s"} in this schema."
            }
            "field", "dynamicField" -> attributes["name"]?.let { name ->
                val copies = model.copyFieldsFrom(name)
                if (copies.isEmpty()) null else "Copied into ${copies.joinToString(", ") { "<code>${it.destination}</code>" }}."
            }
            else -> null
        }

    /**
     * What a particular copy rule joins, and whether both ends exist.
     *
     * Saying "this copies `name` into `text`" is worth more than saying what a `copyField` is,
     * because the reader can see the element and cannot see whether either end resolves.
     */
    private fun copyFieldSpecifics(attributes: Map<String, String>, model: SolrFieldModel): String? {
        val source = attributes["source"] ?: return null
        val destination = attributes["dest"] ?: return null
        val missing = listOf(source, destination)
            .filter { '*' !in it && model.resolve(it) == null }
        return buildString {
            append("Copies <code>$source</code> into <code>$destination</code> at index time.")
            if (missing.isNotEmpty()) {
                append(" This schema declares no ")
                append(missing.joinToString(" and ") { "<code>$it</code>" })
                append(".")
            }
        }
    }

    private val BY_TAG: Map<String, Description> = listOf(
        Description(
            "schema",
            "The root of a Solr schema. It declares the fields a document may have, the field " +
                "types that decide how each field is indexed and queried, and the copy rules " +
                "between them.",
        ),
        Description(
            "field",
            "Declares one field by name. Its <code>type</code> decides how values are indexed and " +
                "therefore what queries can match it; the other attributes are properties Solr " +
                "reads, each of which falls back to the field type when unset.",
        ),
        Description(
            "dynamicField",
            "Declares a field by <em>pattern</em> rather than by name. Any field a document " +
                "supplies that matches the pattern — and that no declared field claims — is " +
                "indexed with this declaration. Solr resolves a name against the longest matching " +
                "pattern.",
        ),
        Description(
            "fieldType",
            "Declares a field type: the class that stores the values, and the analyzer chains that " +
                "decide what a field of this type can match. An index-time chain that tokenises " +
                "and lowercases is the difference between matching a whole value and matching a " +
                "word within it.",
        ),
        Description(
            "fieldtype",
            "The older spelling of <code>fieldType</code>. Solr accepts both, and both mean the " +
                "same thing.",
        ),
        Description(
            "copyField",
            "Copies values from one field into another at index time, before analysis. This is how " +
                "a schema builds a catch-all search field, or feeds one field into a second type " +
                "with different matching behaviour. The copy happens on indexing only — changing a " +
                "copy rule does not alter documents already indexed.",
        ),
        Description(
            "uniqueKey",
            "Names the field that identifies a document. Indexing a document whose key already " +
                "exists replaces the existing one, which is how Solr does updates.",
        ),
        Description(
            "analyzer",
            "A chain that turns a field's text into the terms actually stored or searched. The " +
                "<code>type</code> attribute selects index time or query time; a chain with no " +
                "type applies to both. The two chains do not have to match, and often should not.",
        ),
    ).associateBy { it.tagName }
}
