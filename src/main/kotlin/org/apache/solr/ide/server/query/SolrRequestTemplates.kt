package org.apache.solr.ide.server.query

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The starting requests this plugin offers inside an `.http` file.
 *
 * **Read from the live template file rather than written here, because that file is what the IDE
 * loads.** The HTTP Client's *Add Request* action does not take the request text from the extension
 * point: it takes a *key*, and resolves it with `TemplateSettings.getTemplate(key, group)` against
 * the live templates the IDE has registered. A descriptor carrying the text instead resolves to
 * nothing, and the action answers *Cannot find template for this request* — which is what shipped,
 * for every entry, while a test asserting the descriptor carried the request text passed.
 *
 * So `liveTemplates/solr.xml` is the source of truth and this reads it back. Holding the text in
 * both would be two copies that have to agree, and a test asserting properties of the copy the IDE
 * never loads would be checking nothing.
 *
 * Plain data, separate from the extension point that publishes it, so what the templates say can be
 * checked without the IDE deciding when to offer them.
 */
object SolrRequestTemplates {

    /**
     * The group these appear under in the HTTP Client's *Add Request* menu.
     *
     * Also the live template group, and necessarily the same string: it is half of the pair the
     * action looks the key up by.
     */
    const val GROUP = "Solr"

    /** The environment variable a template expects to hold the Solr base URL. */
    const val URL_VARIABLE = "solrUrl"

    /** The environment variable a template expects to hold the collection or core name. */
    const val COLLECTION_VARIABLE = "collection"

    /** The classpath resource holding the templates, which is also what the IDE registers. */
    const val RESOURCE = "/liveTemplates/solr.xml"

    /**
     * One offered request.
     *
     * @property key the live template name, which is what the *Add Request* action resolves
     * @property description what the menu entry says
     * @property template the `.http` text the live template expands to
     */
    data class Template(val key: String, val description: String, val template: String)

    /**
     * Every request offered, in the order the file declares them.
     *
     * That order is the order a user meets them: querying first because it is what the console is
     * for, then the two that read a collection's shape, which a user reaches for when a query
     * returned something they did not expect.
     */
    val all: List<Template> = read()

    private fun read(): List<Template> {
        val stream = checkNotNull(SolrRequestTemplates::class.java.getResourceAsStream(RESOURCE)) {
            "$RESOURCE is missing from the plugin"
        }
        val document = stream.use {
            // Not a document anyone but this build writes, but the defaults are the defaults: a
            // template file is data, and data does not get to name external entities.
            DocumentBuilderFactory.newInstance()
                .apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    isXIncludeAware = false
                    isExpandEntityReferences = false
                }
                .newDocumentBuilder()
                .parse(it)
        }
        val templates = document.getElementsByTagName("template")
        return (0 until templates.length).map { index ->
            val element = templates.item(index) as Element
            Template(
                key = element.getAttribute("name"),
                description = element.getAttribute("description"),
                // `$$` is how the file spells a literal `$`, since a single one delimits a live
                // template variable. Undone here so callers read the text a user will see.
                template = element.getAttribute("value").replace("\$\$", "$"),
            )
        }
    }
}
