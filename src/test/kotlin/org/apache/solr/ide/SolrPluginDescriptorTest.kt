package org.apache.solr.ide

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
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
        for (descriptor in DESCRIPTORS) resolveEveryClassIn(descriptor)
    }

    /**
     * Every registered inspection has the description file the IDE shows beside it.
     *
     * **The failure is silent in the place a user meets it.** A missing file is not a load error: the
     * inspection works, and the Settings → Inspections pane simply shows an empty panel where the
     * explanation should be. Nothing in the build notices, and nobody adding an inspection sees the
     * pane, because the fixture tests construct the tool directly and never render it.
     *
     * The description is also the plugin's published catalog entry on the JetBrains Marketplace, so
     * the omission is user-facing twice over.
     *
     * Keyed on `shortName`, which is what the platform resolves the file by — the file has to be
     * `inspectionDescriptions/<shortName>.html` and the name in the registration is the only place
     * that pairing is written down.
     */
    @Test
    fun `every registered inspection has a description file`() {
        val shortNames = attributesOf("localInspection", "shortName")
        assertTrue("expected plugin.xml to register inspections", shortNames.isNotEmpty())

        val missing = shortNames.filter { javaClass.getResource("/inspectionDescriptions/$it.html") == null }
        assertTrue(
            "inspections registered with no description file:\n" +
                missing.joinToString("\n") { "  inspectionDescriptions/$it.html" },
            missing.isEmpty(),
        )
    }

    /** One attribute of every element of a kind, in descriptor order. */
    private fun attributesOf(tagName: String, attribute: String): List<String> {
        val elements = parse("plugin.xml").getElementsByTagName(tagName)
        return (0 until elements.length).mapNotNull {
            elements.item(it).attributes.getNamedItem(attribute)?.nodeValue
        }
    }

    /**
     * **Every descriptor, not only `plugin.xml`.** An optional dependency puts its extensions in a
     * separate file, and a registration nobody checks is exactly what this test exists to catch — so
     * the list of files to walk has to grow with them. A missing file fails rather than passing
     * vacuously, because a descriptor that stops being on the classpath is itself the defect.
     */
    private fun resolveEveryClassIn(name: String) {
        val document = parse(name)

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
        assertTrue("expected $name to name at least one of our classes", names.isNotEmpty())

        val loader = javaClass.classLoader
        val missing = names.filter { (_, fqn) ->
            runCatching { Class.forName(fqn, false, loader) }.isFailure
        }
        assertTrue(
            "$name names classes that do not exist:\n" +
                missing.joinToString("\n") { (where, fqn) -> "  $where → $fqn" },
            missing.isEmpty(),
        )
    }

    /** A shipped descriptor, parsed. Namespace-unaware, so the tag names read as they are written. */
    private fun parse(name: String): Document {
        val descriptor = checkNotNull(javaClass.getResourceAsStream("/META-INF/$name")) {
            "$name is not on the test classpath"
        }
        return DocumentBuilderFactory.newInstance()
            .also { it.isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(descriptor)
    }

    private companion object {
        const val OUR_PACKAGE = "org.apache.solr.ide"

        /**
         * The descriptors the plugin ships. `solr-withJava.xml` is loaded only where Java PSI exists,
         * and its one registration is as capable of naming a class that has moved as any other.
         */
        val DESCRIPTORS = listOf("plugin.xml", "solr-withJava.xml")
    }
}
