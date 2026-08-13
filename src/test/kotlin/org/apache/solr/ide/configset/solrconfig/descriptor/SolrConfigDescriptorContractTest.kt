package org.apache.solr.ide.configset.solrconfig.descriptor

import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The descriptor contract the platform calls, rather than the completion it produces.
 *
 * [SolrConfigElementDescriptorTest] asks what a reader is offered; this asks what the platform is
 * told when it asks directly. The two are not the same surface: element naming, content type and
 * attribute resolution are consulted while painting the file and while resolving a reference at a
 * caret, on paths no completion test enters. **The permissiveness rule lives here** — a descriptor
 * that answered null for an unknown attribute would paint a custom component's configuration as
 * wrong, which is the ordinary case in this file rather than an edge one.
 */
class SolrConfigDescriptorContractTest : SolrConfigsetTestCase() {

    private fun configWith(body: String): XmlFile {
        myFixture.addFileToProject(
            "managed-schema.xml",
            """<schema name="t" version="1.7"><fieldType name="string" class="solr.StrField"/></schema>""",
        )
        return myFixture.configureByText("solrconfig.xml", body) as XmlFile
    }

    private fun tagNamed(file: XmlFile, name: String): XmlTag {
        val root = requireNonNull(file.rootTag)
        if (root.name == name) return root
        return requireNonNull(
            generateSequence(listOf(root)) { level -> level.flatMap { it.subTags.toList() }.ifEmpty { null } }
                .flatten()
                .firstOrNull { it.name == name },
        )
    }

    private fun <T : Any> requireNonNull(value: T?): T = value ?: error("no such element in the fixture")

    private fun descriptorFor(body: String, tagName: String): SolrConfigTagDescriptor {
        val file = configWith(body)
        val tag = tagNamed(file, tagName)
        return requireNonNull(tag.descriptor as? SolrConfigTagDescriptor)
    }

    /** The name a descriptor reports is the element's, by all three spellings the platform asks with. */
    fun testADescriptorReportsTheElementsName() {
        val descriptor = descriptorFor("<config>\n  <query/>\n</config>", "query")
        assertEquals("query", descriptor.name)
        assertEquals("query", descriptor.qualifiedName)
        assertEquals("query", descriptor.defaultName)
        assertEquals("query", descriptor.getName(null))
    }

    /**
     * Solr's configuration has no namespaces, no element groups and no defaults on an element.
     *
     * Answering anything else would be inventing a structure the file does not have. `CONTENT_TYPE_ANY`
     * is the same decision in the platform's vocabulary: the descriptor never rejects an element for
     * what it contains, because the plugin's inspections own every such judgement.
     */
    fun testADescriptorClaimsNoStructureSolrDoesNotHave() {
        val descriptor = descriptorFor("<config>\n  <query/>\n</config>", "query")
        assertNull(descriptor.nsDescriptor)
        assertNull(descriptor.topGroup)
        assertNull(descriptor.defaultValue)
        assertEquals(
            com.intellij.xml.XmlElementDescriptor.CONTENT_TYPE_ANY,
            descriptor.contentType,
        )
    }

    /**
     * Every attribute resolves, declared or not — the rule that keeps a custom component unpainted.
     *
     * The declared set feeds completion and is asserted elsewhere. What matters here is the answer for
     * a name no catalog contains, since returning null is how the platform is told an attribute is
     * wrong.
     */
    fun testEveryAttributeNameResolvesIncludingOnesNoCatalogCarries() {
        val descriptor = descriptorFor("<config>\n  <query/>\n</config>", "query")
        assertNotNull(descriptor.getAttributeDescriptor("acmeUnheardOf", null))
        assertNotNull(descriptor.getAttributeDescriptor("size", null))
    }

    /** An element the reader has not written yet describes itself, and nothing inside it. */
    fun testAnOfferedChildNamesItselfAndDescribesNoContents() {
        val descriptor = descriptorFor("<config>\n  <query/>\n</config>", "query")
        val child = requireNonNull(
            descriptor.getElementsDescriptors(null).firstOrNull { it.name == "filterCache" },
        )
        assertEquals("filterCache", child.qualifiedName)
        assertEquals("filterCache", child.defaultName)
        assertTrue(child.getElementsDescriptors(null).isEmpty())
        assertTrue(child.getAttributesDescriptors(null).isEmpty())
        assertNotNull(child.getAttributeDescriptor("anything", null))
    }

    /**
     * A child of an unknown element still gets a descriptor, one level down.
     *
     * The permissiveness rule has to survive nesting, or a custom component's own children would be
     * painted wrong even though the component itself is not.
     */
    fun testAnElementInsideAnUnknownOneIsStillDescribed() {
        val file = configWith("<config>\n  <acmeComponent>\n    <acmeChild/>\n  </acmeComponent>\n</config>")
        val parent = tagNamed(file, "acmeComponent")
        val child = tagNamed(file, "acmeChild")
        val descriptor = requireNonNull(parent.descriptor as? SolrConfigTagDescriptor)
        assertNotNull(descriptor.getElementDescriptor(child, parent))
        assertEquals("acmeChild", descriptor.getElementDescriptor(child, parent).name)
    }
}
