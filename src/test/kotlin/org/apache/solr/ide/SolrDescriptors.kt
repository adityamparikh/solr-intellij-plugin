package org.apache.solr.ide

import org.w3c.dom.Document
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The descriptors the plugin ships, parsed, for the tests that read them.
 *
 * **Two tests read `plugin.xml`, and one parser is what keeps them agreeing.** [SolrPluginDescriptorTest]
 * asks whether every class the descriptor names resolves and every inspection has its description
 * file; `SolrShippedConfigsetTest` instantiates every inspection it registers, so that a rule added
 * tomorrow is run over Solr's own configsets without anyone remembering to list it. Both want the same
 * question answered — *what does the descriptor actually say* — and a second copy of the answer is the
 * kind of thing that drifts on the day one of them is hardened and the other is not.
 *
 * Namespace-unaware, so the tag names read as they are written. Otherwise the parser is closed down to
 * what reading a shipped resource needs: no DOCTYPE, no external entities, no external DTD or schema
 * fetch. Neither descriptor declares any of that, so nothing here is load-bearing today — it is the
 * default that stays correct if one ever does, and it costs a builder configuration.
 */
internal object SolrDescriptors {

    /**
     * The descriptors the plugin ships. `solr-withJava.xml` is loaded only where Java PSI exists, and
     * its one registration is as capable of naming a class that has moved as any other.
     */
    val ALL = listOf("plugin.xml", "solr-withJava.xml")

    /**
     * One attribute of every element of a kind, in descriptor order.
     *
     * **An element missing the attribute fails rather than being skipped.** Dropping it silently is
     * the same vacuity these tests exist to close: `shortName` is optional to the platform, so a
     * registration written without one would leave the inspection unchecked while the assertion that
     * *some* were found still passed, and the omission would be invisible in exactly the place it
     * matters — a blank panel in Settings → Inspections.
     */
    fun attributesOf(name: String, tagName: String, attribute: String): List<String> {
        val elements = parse(name).getElementsByTagName(tagName)
        return (0 until elements.length).map { index ->
            val element = elements.item(index)
            element.attributes?.getNamedItem(attribute)?.nodeValue
                ?: error("$name: <$tagName> #${index + 1} has no `$attribute`")
        }
    }

    /** A shipped descriptor, parsed. A missing file fails rather than passing vacuously. */
    fun parse(name: String): Document =
        checkNotNull(javaClass.getResourceAsStream("/META-INF/$name")) {
            "$name is not on the test classpath"
        }.use { descriptor -> BUILDER_FACTORY.newDocumentBuilder().parse(descriptor) }

    private val BUILDER_FACTORY: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
}
