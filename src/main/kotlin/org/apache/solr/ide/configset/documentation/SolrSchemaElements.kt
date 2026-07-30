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
     * One attribute a structural element accepts.
     *
     * @property name the attribute name as written in the schema
     * @property summary what the attribute does, in one or two sentences
     */
    data class Attribute(val name: String, val summary: String)

    /**
     * The attributes [tagName] accepts — for the elements whose attributes are *not* field
     * properties.
     *
     * The property table owns `field`, `dynamicField` and `fieldType`, and the generated catalog
     * owns the analysis factories; this table is the remainder — the schema root, a copy rule, an
     * analyzer — and is hand-maintained for the same reason the element descriptions are: the set
     * is small and has been stable across Solr majors.
     *
     * @param tagName an element name from a schema
     * @return the attributes it accepts, or empty when it takes none or is not listed here
     */
    fun attributesOf(tagName: String): List<Attribute> = ATTRIBUTES_BY_TAG[tagName].orEmpty()

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
        null -> describe("schema")
        "schema" -> describe("field", "dynamicField", "fieldType", "copyField", "uniqueKey")
        "fieldType", "fieldtype" -> describe("analyzer")
        else -> emptyList()
    }

    /**
     * The descriptions for [tagNames], all of which must exist.
     *
     * Deliberately not null-tolerant. These names are written twice in this file — once here and
     * once in the table — and a typo in either copy is a completion that silently stops being
     * offered. Failing loudly is the only way that gets noticed.
     */
    private fun describe(vararg tagNames: String): List<Description> =
        tagNames.map { BY_TAG.getValue(it) }

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
                append("This schema declares ")
                append(count(model.fields.size, "field"))
                append(", ${count(model.dynamicFields.size, "dynamic field")}")
                append(" and ${count(model.fieldTypes.size, "field type")}.")
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
            "fieldType", "fieldtype" -> attributes["name"]?.let { name ->
                val users = model.fields.values.map { it.effective }.count { it.type == name }
                "<code>$name</code> is used by ${count(users, "field")} in this schema."
            }
            "field", "dynamicField" -> attributes["name"]?.let { name ->
                val copies = model.copyFieldsFrom(name)
                if (copies.isEmpty()) null else "Copied into ${copies.joinToString(", ") { "<code>${it.destination}</code>" }}."
            }
            else -> null
        }

    /** `1 field`, `2 fields` — the plural rule this vocabulary needs, which is the regular one. */
    private fun count(n: Int, noun: String): String = if (n == 1) "$n $noun" else "$n ${noun}s"

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

    private val ATTRIBUTES_BY_TAG: Map<String, List<Attribute>> = mapOf(
        "schema" to listOf(
            Attribute(
                "name",
                "Names the schema. Shown by the Admin UI and in log messages to identify it; " +
                    "nothing about indexing reads it.",
            ),
            Attribute(
                "version",
                "The schema syntax version, not the Solr version. It governs schema-wide " +
                    "defaults — 1.6 is what turned useDocValuesAsStored on by default — so " +
                    "raising it can change behaviour without any other edit.",
            ),
        ),
        "copyField" to listOf(
            Attribute(
                "source",
                "The field values are copied from. May be a glob such as *_t, which copies " +
                    "every matching field.",
            ),
            Attribute(
                "dest",
                "The field values are copied into. It must be a declared field, or match a " +
                    "dynamic field pattern.",
            ),
            Attribute(
                "maxChars",
                "An upper bound on the characters copied, counted from the start of the value. " +
                    "Used to keep a catch-all field from growing without limit.",
            ),
        ),
        "analyzer" to listOf(
            Attribute(
                "type",
                "Selects the chain: index or query. An analyzer with no type applies to both.",
            ),
        ),
    )
}
