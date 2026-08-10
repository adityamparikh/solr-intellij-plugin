package org.apache.solr.ide.configset.reading

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Shared XML plumbing for the configset parsers.
 *
 * Parsing happens through the JDK's DOM rather than IntelliJ's XML PSI, and that is a deliberate
 * constraint rather than an oversight. The field model is the component that has to be exhaustively
 * correct, so its tests must be able to run as plain unit tests over a string; binding it to PSI
 * would make every one of them require a running IDE fixture. The PSI-based features that come
 * later — reference resolution, rename — locate elements by name at the point of use, which they
 * have to do anyway.
 */
internal object SolrXmlDocuments {

    /**
     * Parses [xml] into a root element, or returns null if it is not well-formed.
     *
     * A malformed configset returns null rather than throwing. The user is editing the file, so it
     * is *expected* to be broken in the middle of a keystroke, and an exception on the editor path
     * would surface as a red banner every time a tag is half-typed.
     */
    fun rootOf(xml: CharSequence): Element? = try {
        SECURE_FACTORY.newDocumentBuilder()
            .parse(InputSource(StringReader(xml.toString())))
            .documentElement
    } catch (_: Exception) {
        // Well-formedness failures, IO failures and entity failures alike: all mean "no model from
        // this text", and none of them is worth distinguishing on the editor path.
        null
    }

    /**
     * A parser configured to ignore doctypes and external entities.
     *
     * Configsets are read from the user's project, which is not necessarily trusted content — a
     * repository is often cloned before it is read. Leaving entity resolution on would let a
     * crafted configset read local files or reach the network during what the user experiences as
     * opening a file in the editor.
     */
    private val SECURE_FACTORY: DocumentBuilderFactory = secureBuilderFactory()

    /**
     * Builds the factory once.
     *
     * `DocumentBuilderFactory.newInstance()` performs service-loader discovery on every call, which
     * measured at roughly a quarter of a millisecond. That is invisible when a configset is parsed
     * twice and cached, and it is not invisible to the inspection that parses once per handler
     * parameter on every highlighting pass. The factory is configured once and only read afterwards;
     * a fresh `DocumentBuilder` is still created per parse, since builders are not reusable.
     */
    private fun secureBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }

    /** All direct child elements, in document order. */
    fun Element.children(): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) result += node as Element
        }
        return result
    }

    /** Direct child elements named [name]. */
    fun Element.childrenNamed(name: String): List<Element> = children().filter { it.tagName == name }

    /** Every element named [name] at any depth, including this one. */
    fun Element.descendantsNamed(name: String): List<Element> {
        val result = mutableListOf<Element>()
        if (tagName == name) result += this
        for (child in children()) result += child.descendantsNamed(name)
        return result
    }

    /** The value of attribute [name], or null when absent or empty. */
    fun Element.attributeOrNull(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

    /**
     * Attribute [name] read as a boolean, or null when absent or not a boolean.
     *
     * Case-insensitive because Solr reads these through `Boolean.parseBoolean`, which is: a schema
     * writing `indexed="TRUE"` is one Solr loads. Matching case exactly would have made the property
     * read as *unspecified* and silently resolved to the type's default instead — a wrong answer on
     * a correct file, arrived at quietly. [org.apache.solr.ide.model.schema.SolrValueType] already accepts
     * either case when validating, so a case-sensitive read here also disagreed with this plugin's
     * own inspection.
     *
     * A value that is neither still returns null rather than false, which is where this deliberately
     * stops short of `Boolean.parseBoolean` — that reads every unrecognized string as false, and
     * mirroring it would swallow the typo the invalid-value inspection exists to report.
     */
    fun Element.booleanAttribute(name: String): Boolean? = attributeOrNull(name)?.let { value ->
        when {
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            else -> null
        }
    }

    /** Every attribute except those in [except], as a map in document order. */
    fun Element.attributesExcept(vararg except: String): Map<String, String> {
        val excluded = except.toSet()
        val result = LinkedHashMap<String, String>()
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i)
            if (attribute.nodeName !in excluded) result[attribute.nodeName] = attribute.nodeValue
        }
        return result
    }
}
