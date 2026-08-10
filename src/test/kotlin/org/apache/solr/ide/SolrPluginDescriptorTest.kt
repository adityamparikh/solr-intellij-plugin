package org.apache.solr.ide

import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every class `plugin.xml` names exists.
 *
 * **The gap this closes is specific and was reached by a real defect.** A registration names its class
 * as a *string*, so a wrong name compiles, and every fixture test builds its subject directly —
 * `myFixture.enableInspections(SomeInspection())` — so the whole suite passes while the platform finds
 * nothing to load. A package rename moved three inspections and left three registrations pointing at
 * the package that used to hold them; the build was green and all three were dead in the IDE.
 *
 * **`verifyPlugin` does not close it**, which was checked rather than assumed: with one
 * `implementationClass` pointed at a deleted package, the IntelliJ Plugin Verifier reports nothing and
 * the task succeeds. It verifies API compatibility against IDE builds, not that this descriptor's own
 * class names resolve.
 *
 * Plain JUnit and reflection, so it costs milliseconds and runs in `./gradlew build` beside everything
 * else. Classes are loaded without initialization: this asks whether the name resolves, and running a
 * static initializer outside a platform application would prove something else and fail for its own
 * reasons.
 */
class SolrPluginDescriptorTest {

    @Test
    fun `every class named in plugin xml resolves`() {
        val descriptor = checkNotNull(javaClass.getResourceAsStream("/META-INF/plugin.xml")) {
            "plugin.xml is not on the test classpath"
        }
        val document = DocumentBuilderFactory.newInstance()
            .also { it.isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(descriptor)

        val names = mutableListOf<Pair<String, String>>()
        val elements = document.getElementsByTagName("*")
        for (i in 0 until elements.length) {
            val element = elements.item(i)
            val attributes = element.attributes ?: continue
            for (a in 0 until attributes.length) {
                val attribute = attributes.item(a)
                // The three spellings the platform accepts for "the class behind this extension".
                if (attribute.nodeName !in CLASS_ATTRIBUTES) continue
                val value = attribute.nodeValue ?: continue
                if (value.startsWith("org.apache.solr.ide")) names += element.nodeName to value
            }
        }

        assertTrue("expected plugin.xml to name some classes", names.size > 10)

        val loader = javaClass.classLoader
        val missing = names.filter { (_, fqn) ->
            runCatching { Class.forName(fqn, false, loader) }.isFailure
        }
        assertTrue(
            "plugin.xml names classes that do not exist:\n" +
                missing.joinToString("\n") { (tag, fqn) -> "  <$tag> → $fqn" },
            missing.isEmpty(),
        )
    }

    private companion object {
        val CLASS_ATTRIBUTES = setOf("implementationClass", "implementation", "serviceImplementation")
    }
}
