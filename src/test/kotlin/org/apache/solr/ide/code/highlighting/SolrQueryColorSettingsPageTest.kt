package org.apache.solr.ide.code.highlighting

import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The *Solr Query* colour page previews every colour it lets a reader set.
 *
 * A page whose preview omits one of its own keys offers a setting whose effect nobody can see, and a
 * tag the page never maps is drawn as literal `<angle brackets>` in the preview. Both are invisible to
 * the compiler and obvious to the first person who opens the page.
 */
class SolrQueryColorSettingsPageTest : BasePlatformTestCase() {

    private val page = SolrQueryColorSettingsPage()

    private fun tagsIn(text: String) = Regex("</?([a-z]+)>").findAll(text).map { it.groupValues[1] }.toSet()

    fun testTheColourPageIsRegistered() {
        assertTrue(ColorSettingsPage.EP_NAME.extensionList.any { it is SolrQueryColorSettingsPage })
    }

    fun testEveryTagInThePreviewIsMapped() {
        val mapped = page.additionalHighlightingTagToDescriptorMap.keys

        assertEquals(mapped, tagsIn(page.demoText))
    }

    fun testEveryColourOnThePageIsPreviewed() {
        val previewed = tagsIn(page.demoText).mapNotNull { page.additionalHighlightingTagToDescriptorMap[it] }.toSet()

        for (descriptor in page.attributeDescriptors) {
            assertTrue("${descriptor.displayName} is never shown in the preview", descriptor.key in previewed)
        }
    }

    fun testThePageIsNamedForWhatItColours() {
        assertEquals("Solr Query", page.displayName)
    }
}
