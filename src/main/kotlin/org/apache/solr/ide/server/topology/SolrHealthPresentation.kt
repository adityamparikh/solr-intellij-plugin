package org.apache.solr.ide.server.topology

import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import org.apache.solr.ide.SolrBundle

/**
 * How a [SolrHealth] is drawn in the collections tree: a filled dot in its colour, and the word on
 * hover.
 *
 * **The word is kept, in the tooltip**, because a colour alone says nothing to a reader who cannot
 * tell green from red. Solr's own word is used rather than a translation of it, so it matches what
 * the Admin UI and a `CLUSTERSTATUS` response say.
 */
internal object SolrHealthPresentation {

    // Each pair is light then dark. The dark halves are lighter and a little less saturated, so the
    // dot holds its edge against a dark panel without glowing; yellow and orange are kept far enough
    // apart in hue that the two warnings are not one colour at a glance.
    private val colours = mapOf(
        SolrHealth.GREEN to JBColor(0x369650, 0x5FAD65),
        SolrHealth.YELLOW to JBColor(0xD9A800, 0xE8C14B),
        SolrHealth.ORANGE to JBColor(0xE56D17, 0xE08855),
        SolrHealth.RED to JBColor(0xDB3B4B, 0xDB5C5C),
    )

    private val icons = SolrHealth.entries.associateWith { HealthDot(colourOf(it)) }

    /**
     * The colour [health] is drawn in, resolved against the current theme when painted.
     *
     * @param health what Solr reported
     * @return its colour
     */
    fun colourOf(health: SolrHealth): JBColor = colours.getValue(health)

    /**
     * The dot drawn at the start of a row with [health].
     *
     * @param health what Solr reported
     * @return a filled circle in [health]'s colour, sized like any other tree icon
     */
    fun iconOf(health: SolrHealth): Icon = icons.getValue(health)

    /**
     * What hovering the row says.
     *
     * @param health what Solr reported
     * @return the tooltip, naming the health in Solr's own word
     */
    fun tooltipOf(health: SolrHealth): String = SolrBundle.message("collections.health.tooltip", health.name)

    // Drawn rather than loaded: four colours of one circle are not worth eight SVG files, and a
    // JBColor painted directly follows a theme switch without the icon having to be reloaded.
    private class HealthDot(private val colour: JBColor) : Icon {
        override fun getIconWidth() = JBUIScale.scale(SIZE)

        override fun getIconHeight() = JBUIScale.scale(SIZE)

        override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = colour
                val diameter = JBUIScale.scale(DIAMETER)
                val inset = (iconWidth - diameter) / 2
                g.fillOval(x + inset, y + inset, diameter, diameter)
            } finally {
                g.dispose()
            }
        }
    }

    // The standard tree icon box, so a row with a dot lines its label up with the icons elsewhere in
    // the IDE; the dot inside it is small enough to read as a status rather than as a picture.
    private const val SIZE = 16
    private const val DIAMETER = 9
}
