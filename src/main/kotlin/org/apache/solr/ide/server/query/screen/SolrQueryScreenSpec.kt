package org.apache.solr.ide.server.query.screen

import org.apache.solr.ide.server.reading.SolrJsonDocuments
import tools.jackson.databind.JsonNode

/** What kind of input a parameter is drawn as, and how its value becomes parameters. */
enum class SolrQueryScreenFieldKind {

    /** The request handler: a path the request is sent to, never a parameter. */
    HANDLER,

    /** One line of text, sent where it is not blank. */
    TEXT,

    /** One of a fixed list; an empty choice means the handler's own default and is not sent. */
    CHOICE,

    /** On or off; sent as `true` when on, and as `false` when off only if it is on by default. */
    CHECKBOX,

    /** One value per line, each sent under the same name, as several `fq` are. */
    REPEATED,

    /** One `name=value` per line, each sent as its own parameter. */
    RAW,
}

/**
 * One parameter the form offers.
 *
 * @property name the parameter's name, which is also how the form's state keys it
 * @property label what the form calls it, which is the Admin UI's label
 * @property kind how it is drawn and sent
 * @property tooltip the Admin UI's explanation, where it gives one
 * @property default the value it starts with; empty for none
 * @property choices what a [SolrQueryScreenFieldKind.CHOICE] offers, in order
 * @property showWhen the values other parameters must hold for this one to show, by name; empty to
 *   always show
 * @property completesFields whether its input completes the collection's field names
 * @property alsoComplete what it offers beside field names, such as `score`
 */
data class SolrQueryScreenField(
    val name: String,
    val label: String,
    val kind: SolrQueryScreenFieldKind,
    val tooltip: String? = null,
    val default: String = "",
    val choices: List<String> = emptyList(),
    val showWhen: Map<String, List<String>> = emptyMap(),
    val completesFields: Boolean = false,
    val alsoComplete: List<String> = emptyList(),
)

/**
 * One group of parameters, as the Admin UI groups them.
 *
 * @property id a stable name for the section
 * @property label what the form heads it with
 * @property toggle the parameter a checkbox in the heading sends, which also opens the section's
 *   fields — `debugQuery` is one — or null where the section is always open
 * @property fields its parameters, in order
 */
data class SolrQueryScreenSection(
    val id: String,
    val label: String,
    val toggle: String? = null,
    val fields: List<SolrQueryScreenField> = emptyList(),
)

/**
 * The query console's form: which parameters it offers, how each is drawn, and when each shows.
 *
 * **Read from a file transcribed from Solr's own query page, the way IntelliJ draws the Spring
 * Initializr wizard from metadata the service publishes.** Solr publishes no Admin UI to Maven, and
 * the page a running server serves is Angular markup rather than an interface for tools, so neither
 * a build step nor the connected server can supply this. The file says which page and which line it
 * was taken from, and adopting a new Solr line means reading its query page against it.
 *
 * Pure: no IntelliJ type, so what the form holds is tested without an IDE.
 *
 * @property source where the definition was transcribed from, and where it departs from it
 * @property sections the form's sections, in order
 */
data class SolrQueryScreenSpec(val source: String, val sections: List<SolrQueryScreenSection>) {

    /** Every parameter, in form order. */
    val fields: List<SolrQueryScreenField> get() = sections.flatMap { it.fields }

    /**
     * The parameter called [name], or null where the form has none.
     *
     * @param name a parameter name
     * @return its definition, or null
     */
    fun field(name: String): SolrQueryScreenField? = fields.firstOrNull { it.name == name }

    /**
     * The section holding [name], as a field or as its toggle.
     *
     * @param name a parameter name
     * @return the section, or null where no section holds it
     */
    fun sectionOf(name: String): SolrQueryScreenSection? =
        sections.firstOrNull { section -> section.toggle == name || section.fields.any { it.name == name } }

    /** Reading a definition, and the one the plugin ships. */
    companion object {

        /** Where the shipped definition lives on the plugin's classpath. */
        const val RESOURCE: String = "/queryScreen/solr-query-screen.json"

        /** The definition the plugin ships, read once. */
        val bundled: SolrQueryScreenSpec by lazy {
            val stream = checkNotNull(SolrQueryScreenSpec::class.java.getResourceAsStream(RESOURCE)) {
                "$RESOURCE is missing from the plugin"
            }
            parse(stream.use { it.readBytes().decodeToString() })
        }

        /**
         * Reads a definition.
         *
         * **A mistake is refused, naming the entry, rather than drawn around.** The file is edited by
         * hand, and a form that silently lacks a parameter looks exactly like a form that never
         * offered it.
         *
         * @param json the definition's text
         * @return the form it describes
         * @throws IllegalArgumentException where the text is not a definition
         */
        fun parse(json: String): SolrQueryScreenSpec {
            val root = requireNotNull(SolrJsonDocuments.treeOf(json)?.takeIf { it.isObject }) {
                "the query console's form definition is not a JSON object"
            }
            val sections = root.path("sections")
            require(sections.isArray) { "the query console's form definition has no sections" }

            val spec = SolrQueryScreenSpec(root.path("source").asString(""), sections.toList().map { sectionOf(it) })
            val names = spec.fields.map { it.name } + spec.sections.mapNotNull { it.toggle }
            names.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
                throw IllegalArgumentException("${it.key} is defined twice in the query console's form")
            }
            spec.fields.forEach { field ->
                field.showWhen.keys.firstOrNull { it !in names }?.let {
                    throw IllegalArgumentException("${field.name} shows when $it has a value, and the form has no $it")
                }
            }
            return spec
        }

        private fun sectionOf(node: JsonNode) = SolrQueryScreenSection(
            id = node.requiredText("id"),
            label = node.requiredText("label"),
            toggle = node.path("toggle").asString("").ifEmpty { null },
            fields = node.path("fields").toList().map { fieldOf(it) },
        )

        private fun fieldOf(node: JsonNode): SolrQueryScreenField {
            val name = node.requiredText("name")
            val kindName = node.path("kind").asString("")
            val kind = SolrQueryScreenFieldKind.entries.firstOrNull { it.name.equals(kindName, ignoreCase = true) }
                ?: throw IllegalArgumentException("$name has an unknown kind '$kindName'")
            val choices = node.path("choices").toList().map { it.asString("") }
            require(kind != SolrQueryScreenFieldKind.CHOICE || choices.isNotEmpty()) {
                "$name is a choice with nothing to choose"
            }
            return SolrQueryScreenField(
                name = name,
                label = node.path("label").asString(name),
                kind = kind,
                tooltip = node.path("tooltip").asString("").ifEmpty { null },
                default = node.path("default").asString(""),
                choices = choices,
                showWhen = node.path("showWhen").properties().associate { (key, values) ->
                    key to values.toList().map { it.asString("") }
                },
                completesFields = node.path("completes").asString("") == "fields",
                alsoComplete = node.path("also").toList().map { it.asString("") },
            )
        }

        private fun JsonNode.requiredText(property: String): String =
            path(property).asString("").ifEmpty {
                throw IllegalArgumentException("an entry in the query console's form has no $property: $this")
            }
    }
}
