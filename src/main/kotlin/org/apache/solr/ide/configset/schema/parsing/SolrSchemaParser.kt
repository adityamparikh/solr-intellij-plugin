package org.apache.solr.ide.configset.schema.parsing

import org.apache.solr.ide.configset.activation.SolrSchemaTags
import org.apache.solr.ide.configset.reading.SolrXmlDocuments
import org.apache.solr.ide.configset.reading.SolrXmlDocuments.attributeOrNull
import org.apache.solr.ide.configset.reading.SolrXmlDocuments.attributesExcept
import org.apache.solr.ide.configset.reading.SolrXmlDocuments.booleanAttribute
import org.apache.solr.ide.configset.reading.SolrXmlDocuments.childrenNamed
import org.apache.solr.ide.configset.reading.SolrXmlDocuments.descendantsNamed
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.schema.SolrAnalyzerChain
import org.apache.solr.ide.model.schema.SolrAnalyzerComponent
import org.apache.solr.ide.model.schema.SolrCopyField
import org.apache.solr.ide.model.schema.SolrDynamicField
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.schema.SolrFieldType
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
            // Reported as absent when absent. Solr reads a missing attribute as 1.0, but making
            // that substitution here would put a fact in the parser's output that the file does
            // not contain; the model applies it instead.
            schemaVersion = root.attributeOrNull("version"),
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
        val analyzers = element.childrenNamed(SolrSchemaTags.ANALYZER)
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
        charFilters = element.childrenNamed(SolrSchemaTags.CHAR_FILTER).mapNotNull { readComponent(it) },
        tokenizer = element.childrenNamed(SolrSchemaTags.TOKENIZER).firstOrNull()?.let { readComponent(it) },
        filters = element.childrenNamed(SolrSchemaTags.FILTER).mapNotNull { readComponent(it) },
        className = element.attributeOrNull("class"),
    )

    /**
     * Reads both spellings of an analysis component.
     *
     * Solr accepts `class="solr.LowerCaseFilterFactory"` and `name="lowercase"`, and the second is
     * what every configset Solr ships uses. Reading only `class` dropped each of those components
     * while the chain around it still parsed, so a field type kept its analyzer and lost everything
     * inside it — visible as an empty chain in quick documentation and as an unrecognized one to
     * match analysis, and invisible to a suite whose own fixtures were all written the other way.
     *
     * The name is recorded as written. Resolving the two spellings to one factory is the catalog's
     * job, which is the only thing that knows they name the same class.
     */
    private fun readComponent(element: Element): SolrAnalyzerComponent? {
        val className = element.attributeOrNull("class")
            ?: element.attributeOrNull("name")
            ?: return null
        return SolrAnalyzerComponent(className, element.attributesExcept("class", "name"))
    }

    private fun readCopyField(element: Element): SolrCopyField? {
        val source = element.attributeOrNull("source") ?: return null
        val destination = element.attributeOrNull("dest") ?: return null
        return SolrCopyField(source, destination, element.attributeOrNull("maxChars")?.toIntOrNull())
    }
}
