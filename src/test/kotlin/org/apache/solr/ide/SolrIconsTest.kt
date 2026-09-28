package org.apache.solr.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The tool window's icon loads, at the size each UI asks for.
 *
 * **A broken icon fails by not appearing**, and not appearing is also what a registration pointing
 * at the wrong path looks like, so nothing in the build would notice either. The platform does not
 * throw on a missing or unparseable SVG; it draws a placeholder in the stripe where the icon should
 * be, and only a person looking at the IDE would see it.
 */
class SolrIconsTest : BasePlatformTestCase() {

    /** The classic tool window stripe takes 13x13, and the icon registered in `plugin.xml` is that one. */
    fun testTheToolWindowIconIsThirteenPixelsSquare() {
        val icon = SolrIcons.ToolWindow

        assertEquals(13, icon.iconWidth)
        assertEquals(13, icon.iconHeight)
    }

    /**
     * Every variant the platform may look for is on the classpath, at the size its name promises.
     *
     * The new UI reaches the `expui` files through the mapping file rather than through [SolrIcons],
     * so they are loaded here by path: a typo in any of them would otherwise ship unnoticed, and only
     * in the UI most people now run.
     */
    fun testEveryVariantIsPresentAtItsSize() {
        val expected = mapOf(
            "/icons/solrToolWindow.svg" to 13,
            "/icons/solrToolWindow_dark.svg" to 13,
            "/icons/expui/solrToolWindow.svg" to 16,
            "/icons/expui/solrToolWindow_dark.svg" to 16,
            "/icons/expui/solrToolWindow@20x20.svg" to 20,
            "/icons/expui/solrToolWindow@20x20_dark.svg" to 20,
        )

        for ((path, size) in expected) {
            val svg = checkNotNull(javaClass.getResourceAsStream(path)) { "missing $path" }.use {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
            }
            assertEquals(path, size.toString(), svg.getAttribute("width"))
            assertEquals(path, size.toString(), svg.getAttribute("height"))
        }
    }

    /**
     * The mapping file names the icon [SolrIcons] loads, and the `expui` file it maps from exists.
     *
     * The mapping is keyed by the new-UI file and valued by the classic one; a value that names no
     * resource leaves the new UI drawing the 13-pixel icon inside a 20-pixel button.
     */
    fun testTheNewUiMappingPointsAtTheClassicIcon() {
        val mapping = checkNotNull(javaClass.getResourceAsStream("/SolrIconMappings.json")) { "missing mapping" }
            .bufferedReader().use { it.readText() }

        assertTrue(mapping, mapping.contains("\"solrToolWindow.svg\": \"icons/solrToolWindow.svg\""))
        assertNotNull(javaClass.getResource("/icons/solrToolWindow.svg"))
    }
}
