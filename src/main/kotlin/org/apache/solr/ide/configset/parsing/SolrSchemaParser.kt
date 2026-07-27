package org.apache.solr.ide.configset.parsing

import org.apache.solr.ide.model.SolrAnalyzerChain
import org.apache.solr.ide.model.SolrAnalyzerComponent
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.SolrCopyField
import org.apache.solr.ide.model.SolrDynamicField
import org.apache.solr.ide.model.SolrField
import org.apache.solr.ide.model.SolrFieldType
import org.apache.solr.ide.configset.parsing.SolrXmlDocuments.attributeOrNull
import org.apache.solr.ide.configset.parsing.SolrXmlDocuments.attributesExcept
import org.apache.solr.ide.configset.parsing.SolrXmlDocuments.booleanAttribute
import org.apache.solr.ide.configset.parsing.SolrXmlDocuments.childrenNamed
import org.apache.solr.ide.configset.parsing.SolrXmlDocuments.descendantsNamed
import org.w3c.dom.Element

/**
 * Parses a Solr schema — `managed-schema.xml`, `managed-schema` or `schema.xml` — into facts.
 *
 * A pure function from text to data, with no IntelliJ types in its signature, so that the component
 * the rest of the plugin depends on for correctness can be tested as a plain unit test.
 *
 * **Nothing here rejects a schema.** Elements are read where they are found rather than validated
 * against where Solr expects them, and anything unrecognized is ignored. A schema that is invalid,
 * half-typed or written for a Solr version this plugin has never seen still yields whatever facts
 * it does contain, because a user editing a file needs the model most exactly when the file is not
 * yet correct.
 */
object SolrSchemaParser {

    /**
     * Reads [xml] as a Solr schema.
     *
     * @param xml the schema document's text
     * @return the facts it declares; empty facts if the text is not well-formed XML
     */
    fun parse(xml: CharSequence): SolrConfigsetFacts {
        val root = SolrXmlDocuments.rootOf(xml) ?: return SolrConfigsetFacts()
        return SolrConfigsetFacts(
            fields = root.descendantsNamed("field").mapNotNull { readField(it) },
            dynamicFields = root.descendantsNamed("dynamicField")
                .mapNotNull { element -> readField(element)?.let { SolrDynamicField(it.name, it) } },
            fieldTypes = readFieldTypes(root),
            copyFields = root.descendantsNamed("copyField").mapNotNull { readCopyField(it) },
            uniqueKey = root.descendantsNamed("uniqueKey").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun readField(element: Element): SolrField? {
        val name = element.attributeOrNull("name") ?: return null
        // A field with no type is malformed, but recording it with an empty type keeps it visible
        // to the inspection that will report exactly that, rather than making it vanish.
        return SolrField(
            name = name,
            type = element.attributeOrNull("type").orEmpty(),
            indexed = element.booleanAttribute("indexed"),
            stored = element.booleanAttribute("stored"),
            docValues = element.booleanAttribute("docValues"),
            multiValued = element.booleanAttribute("multiValued"),
            required = element.booleanAttribute("required"),
            defaultValue = element.attributeOrNull("default"),
            attributes = element.attributesExcept("name", "type"),
        )
    }

    /**
     * Reads both spellings of a type declaration.
     *
     * Solr accepts `fieldType` and the older `fieldtype`, and real configsets in the wild contain
     * both. Matching only the modern spelling would silently drop every type in an older schema.
     */
    private fun readFieldTypes(root: Element): List<SolrFieldType> =
        (root.descendantsNamed("fieldType") + root.descendantsNamed("fieldtype"))
            .mapNotNull { readFieldType(it) }

    private fun readFieldType(element: Element): SolrFieldType? {
        val name = element.attributeOrNull("name") ?: return null
        val analyzers = element.childrenNamed("analyzer")
        // An analyzer with no `type` applies to both phases; a typed one applies to its own. Solr
        // allows either form, and a configset mixing them is legal.
        val untyped = analyzers.firstOrNull { it.attributeOrNull("type") == null }
        return SolrFieldType(
            name = name,
            className = element.attributeOrNull("class").orEmpty(),
            attributes = element.attributesExcept("name", "class"),
            indexAnalyzer = (analyzers.firstOrNull { it.attributeOrNull("type") == "index" } ?: untyped)
                ?.let { readAnalyzer(it) },
            queryAnalyzer = (analyzers.firstOrNull { it.attributeOrNull("type") == "query" } ?: untyped)
                ?.let { readAnalyzer(it) },
        )
    }

    private fun readAnalyzer(element: Element): SolrAnalyzerChain = SolrAnalyzerChain(
        charFilters = element.childrenNamed("charFilter").mapNotNull { readComponent(it) },
        tokenizer = element.childrenNamed("tokenizer").firstOrNull()?.let { readComponent(it) },
        filters = element.childrenNamed("filter").mapNotNull { readComponent(it) },
        className = element.attributeOrNull("class"),
    )

    private fun readComponent(element: Element): SolrAnalyzerComponent? {
        val className = element.attributeOrNull("class") ?: return null
        return SolrAnalyzerComponent(className, element.attributesExcept("class"))
    }

    private fun readCopyField(element: Element): SolrCopyField? {
        val source = element.attributeOrNull("source") ?: return null
        val destination = element.attributeOrNull("dest") ?: return null
        return SolrCopyField(source, destination, element.attributeOrNull("maxChars")?.toIntOrNull())
    }
}
