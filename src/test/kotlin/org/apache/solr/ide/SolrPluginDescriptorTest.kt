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
 * **Every attribute naming one of ours is checked, rather than a list of the spellings the platform
 * uses.** A list would drift the first time an extension point wanted a different attribute, and
 * drifting silently is the failure this test exists to stop. A value is treated as a class when its
 * final segment starts upper-case, which is what separates `implementationClass` from the plugin's own
 * `<id>` and from a `groupPath`.
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
                // Every attribute naming one of ours, rather than a list of the spellings the
                // platform currently uses for it. `implementationClass` and `implementation` are what
                // this descriptor writes today, and an extension point registered tomorrow under a
                // third spelling is covered without anyone remembering to add it here.
                val value = attribute.nodeValue ?: continue
                if (!value.startsWith("$OUR_PACKAGE.")) continue
                // A class, not a package or an id: `<id>org.apache.solr.ide</id>` and a `groupPath`
                // are legitimately not resolvable, and only a final segment starting upper-case is
                // claiming to be a type.
                if (value.substringAfterLast('.').firstOrNull()?.isUpperCase() != true) continue
                names += "${element.nodeName}/${attribute.nodeName}" to value
            }
        }

        // A guard against the walk silently finding nothing — a namespace-aware parser, a moved
        // resource or a typo in the attribute scan would otherwise make this test vacuously green.
        assertTrue("expected plugin.xml to name many of our classes, found ${names.size}", names.size > 15)

        val loader = javaClass.classLoader
        val missing = names.filter { (_, fqn) ->
            runCatching { Class.forName(fqn, false, loader) }.isFailure
        }
        assertTrue(
            "plugin.xml names classes that do not exist:\n" +
                missing.joinToString("\n") { (where, fqn) -> "  $where → $fqn" },
            missing.isEmpty(),
        )
    }

    private companion object {
        const val OUR_PACKAGE = "org.apache.solr.ide"
    }
}
