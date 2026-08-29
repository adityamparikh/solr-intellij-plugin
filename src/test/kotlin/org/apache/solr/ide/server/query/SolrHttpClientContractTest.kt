package org.apache.solr.ide.server.query

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.ide.plugins.PluginManagerCore
import org.apache.solr.ide.SolrDescriptors
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Every HTTP Client extension point this plugin registers actually exists.
 *
 * **This test exists because the gate the specification names does not do what it says.** FR-18
 * accepts the exposure of building on another plugin's extension points on the grounds that
 * `verifyPlugin` "runs against every entry in `verifiedIdeBuilds` in CI, so an IDE that removed or
 * changed one fails the pull request raising the target rather than a user's editor." That was
 * checked rather than believed: with the registration below renamed to
 * `httpClient.thisExtensionPointDoesNotExist`, the verifier's verdict is **"Compatible"** and the
 * task succeeds. It verifies API compatibility of *classes*, not that a descriptor's extension point
 * names resolve — the same gap `SolrPluginDescriptorTest` was written to close for class names.
 *
 * So the exposure is real and was unmitigated. The failure it would produce is the quiet kind: the
 * plugin loads, nothing errors, and the templates simply never appear in a menu nobody thinks to
 * check. Here it is a failing test naming the point that vanished.
 */
class SolrHttpClientContractTest : SolrConfigsetTestCase() {

    /**
     * Every `httpClient.*` extension the descriptor registers, as its fully qualified name.
     *
     * Discovered by walking the descriptor rather than listed here, for the reason the server
     * boundary test gives: a hand-maintained list is wrong the day someone adds a registration and
     * forgets it, and a registration missing from the list is a registration this test permits.
     */
    private fun registeredHttpClientPoints(): List<String> {
        val extensions = SolrDescriptors.parse("plugin.xml").getElementsByTagName("extensions")
        return (0 until extensions.length)
            .map { extensions.item(it) }
            .flatMap { group ->
                val children = group.childNodes
                (0 until children.length).map { children.item(it) }
            }
            .mapNotNull { it.nodeName }
            .filter { it.startsWith("httpClient.") }
            // Registered under `defaultExtensionNs="com.intellij"`, so that prefix is what the tag
            // name is short for.
            .map { "com.intellij.$it" }
    }

    fun testTheDescriptorRegistersHttpClientExtensions() {
        assertFalse(
            "this test is vacuous unless the descriptor registers at least one — if the integration " +
                "was removed, remove this test too rather than leaving it passing over nothing",
            registeredHttpClientPoints().isEmpty(),
        )
    }

    /**
     * The HTTP Client plugin is present, which everything else here depends on.
     *
     * Separate from the point check so a missing dependency reports itself as a missing dependency
     * rather than as five extension points that all mysteriously vanished at once.
     */
    fun testTheHttpClientPluginIsAvailable() {
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId(HTTP_CLIENT_PLUGIN))

        assertNotNull("$HTTP_CLIENT_PLUGIN is declared in plugin.xml and must be loaded", descriptor)
        assertTrue("$HTTP_CLIENT_PLUGIN must be enabled", descriptor!!.isEnabled)
    }

    /**
     * Each registered point is one the HTTP Client actually declares.
     *
     * The assertion `verifyPlugin` does not make.
     */
    fun testEveryRegisteredExtensionPointExists() {
        val area = ApplicationManager.getApplication().extensionArea
        val missing = registeredHttpClientPoints().filterNot { area.hasExtensionPoint(it) }

        assertTrue(
            "these extension points are registered in plugin.xml but do not exist in this IDE — " +
                "the HTTP Client may have renamed or removed them: $missing",
            missing.isEmpty(),
        )
    }

    /**
     * The template provider is not merely registered but actually reachable through the point.
     *
     * A registration can name a real point and a real class and still contribute nothing, if the
     * class does not implement what the point expects. This is the end-to-end version: the platform
     * resolved it, instantiated it, and it is in the list.
     */
    fun testTheTemplateProviderIsContributed() {
        val area = ApplicationManager.getApplication().extensionArea
        val point = area.getExtensionPoint<Any>(TEMPLATE_POINT)

        assertTrue(
            "expected ${SolrRequestTemplateProvider::class.java.simpleName} among ${point.extensionList.map { it.javaClass.simpleName }}",
            point.extensionList.any { it is SolrRequestTemplateProvider },
        )
    }

    private companion object {
        const val HTTP_CLIENT_PLUGIN = "com.jetbrains.restClient"
        const val TEMPLATE_POINT = "com.intellij.httpClient.addRequestTemplateProvider"
    }
}
