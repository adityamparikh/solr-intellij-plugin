package org.apache.solr.ide.server.query

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The HTTP Client's environment files, read for the variables a request line uses.
 *
 * **Which environment is selected cannot be asked.** The HTTP Client records it per file, in a class
 * it does not publish, and the specification holds this plugin to the extension points it does. So a
 * variable is read across every environment and answered only where the answer does not depend on
 * which one is selected — see [agreedValue].
 *
 * **Pure over the files' text**, in the order the HTTP Client layers them: public files, then private
 * ones, a private value replacing the public value of the same environment.
 */
object SolrHttpEnvironments {

    /**
     * Every environment the files declare, each as its string-valued variables.
     *
     * `$shared` is not an environment of its own: its values reach every environment that does not
     * set the same variable. A file that is not JSON contributes nothing rather than failing.
     *
     * @param texts the files' contents, public before private
     * @return one map per named environment
     */
    fun parse(texts: List<String>): List<Map<String, String>> {
        val shared = mutableMapOf<String, String>()
        val named = linkedMapOf<String, MutableMap<String, String>>()
        for (text in texts) {
            val root = runCatching { MAPPER.readTree(text) }.getOrNull()?.takeIf { it.isObject } ?: continue
            for ((environment, variables) in root.properties()) {
                val into = if (environment == SHARED) shared else named.getOrPut(environment) { mutableMapOf() }
                into += stringsIn(variables)
            }
        }
        return named.values.map { shared + it }
    }

    private fun stringsIn(variables: JsonNode): Map<String, String> =
        if (!variables.isObject) {
            emptyMap()
        } else {
            variables.properties()
                .filter { (_, value) -> value.isString }
                .associate { (name, value) -> name to value.stringValue() }
        }

    /**
     * The value [variable] has whichever environment is selected, or null where that depends on the
     * selection.
     *
     * **Agreement, because environments ordinarily differ by host and not by collection.** `dev` and
     * `staging` point `{{solrUrl}}` at different servers and call the collection `books` in both, so
     * the common case resolves; what is refused is the case where guessing would be wrong most often.
     * The costs are unequal the other way too: refusing falls back to configset fields that name
     * their configset, visibly, while a guess offers another collection's fields labelled as this
     * one's — and a field that collection lacks returns zero results rather than an error.
     *
     * **An environment that does not define the variable does not disagree.** Environment files
     * routinely hold other services beside Solr, and counting their silence as a second answer would
     * refuse exactly the projects this should serve.
     *
     * @param variable the variable's name, without braces
     * @param environments every environment, as [parse] returns them
     * @return the value, or null where no environment defines it or they do not agree
     */
    fun agreedValue(variable: String, environments: List<Map<String, String>>): String? =
        environments.mapNotNull { it[variable] }.distinct().singleOrNull()

    /** The HTTP Client's name for the values every environment inherits. */
    private const val SHARED = "\$shared"

    private val MAPPER: JsonMapper = JsonMapper.builder().build()
}
