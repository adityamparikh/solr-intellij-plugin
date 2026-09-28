package org.apache.solr.ide.server.topology

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.treeStructure.Tree
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.tree.DefaultMutableTreeNode

/**
 * What a row of the collections tree looks like once it is drawn.
 *
 * Worth a test of its own because a renderer fails quietly: `GREEN` spelled out beside a collection
 * is still perfectly legible text, which is how it reached a tester instead of a build.
 */
class SolrTopologyNodeRendererTest : BasePlatformTestCase() {

    private val renderer = SolrTopologyNodeRenderer()
    private val tree = Tree()

    private fun render(node: SolrTopologyNode) {
        renderer.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(node), false, false, true, 0, false)
    }

    private fun text() = renderer.getCharSequence(false).toString()

    private fun books(health: SolrHealth?) = SolrTopologyNode(
        label = "books",
        detail = "books_config",
        kind = SolrTopologyNodeKind.COLLECTION,
        health = health,
    )

    // The colour at the icon's centre, which is inside the dot whatever the scale.
    private fun centreColourOf(icon: Icon): Color {
        val image = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            icon.paintIcon(null, graphics, 0, 0)
        } finally {
            graphics.dispose()
        }
        return Color(image.getRGB(icon.iconWidth / 2, icon.iconHeight / 2), true)
    }

    fun testAHealthyCollectionShowsADotRatherThanTheWord() {
        render(books(SolrHealth.GREEN))

        assertFalse(text(), text().contains("GREEN"))
        assertTrue(text(), text().contains("books"))
        assertTrue(text(), text().contains("books_config"))
        assertNotNull("a recognised health is drawn", renderer.icon)
        assertEquals(SolrHealthPresentation.colourOf(SolrHealth.GREEN).rgb, centreColourOf(renderer.icon).rgb)
    }

    /**
     * The word is still there to be found, on hover.
     *
     * A colour alone says nothing to a reader who cannot tell green from red, and nothing to a screen
     * reader; the tooltip is what keeps the dot from being the only place the fact lives.
     */
    fun testTheHealthWordMovesToTheTooltip() {
        render(books(SolrHealth.ORANGE))

        assertTrue(renderer.toolTipText, renderer.toolTipText.orEmpty().contains("ORANGE"))
    }

    /**
     * One renderer draws every row, so what the last row set must not leak into the next.
     *
     * A replica drawn after its collection would otherwise inherit the collection's dot and tooltip,
     * and report a health Solr never gave it.
     */
    fun testARowWithNoHealthCarriesNoDotAndNoTooltip() {
        render(books(SolrHealth.RED))
        render(books(null))

        assertNull(renderer.icon)
        assertNull(renderer.toolTipText)
    }

    fun testEachHealthIsDrawnInItsOwnColour() {
        val colours = SolrHealth.entries.map {
            render(books(it))
            centreColourOf(renderer.icon).rgb
        }

        assertEquals(colours.toString(), SolrHealth.entries.size, colours.toSet().size)
    }

    /** The test IDE runs light, so the dark half is asked for directly rather than left unexercised. */
    fun testTheDarkThemeKeepsThemDistinctToo() {
        val dark = SolrHealth.entries.map { SolrHealthPresentation.colourOf(it).darkVariant.rgb }

        assertEquals(dark.toString(), SolrHealth.entries.size, dark.toSet().size)
    }
}
