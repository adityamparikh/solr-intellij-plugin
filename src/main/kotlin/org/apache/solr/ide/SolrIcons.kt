package org.apache.solr.ide

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * The plugin's own icons, for `plugin.xml` to name and code to reach.
 *
 * Fields rather than properties with getters, because a registration names an icon as
 * `org.apache.solr.ide.SolrIcons.ToolWindow` and the platform reads that as a static field.
 */
object SolrIcons {

    /**
     * The Solr tool window's stripe icon: the plugin icon's mark, in the single grey tool window
     * icons are drawn in.
     *
     * 13x13, which is what the classic stripe takes. The new UI asks for 20x20 and 16x16 instead, and
     * gets them from `icons/expui/` through `SolrIconMappings.json` rather than through this field —
     * the platform swaps the file behind the same icon, so nothing here needs to know which UI is on.
     */
    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/solrToolWindow.svg", SolrIcons::class.java)
}
