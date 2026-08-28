package org.apache.solr.ide.server.reading

import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.apache.solr.ide.server.transport.SolrHttpTransport
import org.apache.solr.ide.server.transport.SolrResponse
import org.junit.AfterClass
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.SolrContainer
import org.testcontainers.utility.DockerImageName

/**
 * The reader against a Solr that is really running.
 *
 * **The tier a fake cannot stand in for.** Everything below this is checked against bodies somebody
 * captured or composed, which proves the reader parses what Solr *said once*. Only a live server
 * proves it parses what Solr *says* — and the specification's own wire-format pass found three of its
 * five guesses wrong, so the gap between those two is not theoretical.
 *
 * Both supported lines are exercised, pinned by tag and never `latest`, and the containers are
 * started and stopped by this class rather than by a developer beforehand.
 *
 * `SolrContainer` is the Testcontainers project's own module rather than a `GenericContainer`
 * assembled here: it knows the readiness check, and `withZookeeper` is what makes the
 * standalone-versus-SolrCloud distinction a fixture rather than an argument.
 */
class SolrServerContractTest {

    private val transport = SolrHttpTransport(timeout = Duration.ofSeconds(30))

    private fun schemaOf(container: SolrContainer, collection: String) = runBlocking {
        transport.get("http://${container.host}:${container.solrPort}", "/solr/$collection/schema")
    }

    // --- what the reader is built on ---------------------------------------------------------------

    /**
     * The response parses into facts, on both lines.
     *
     * The assertion that would fail the day a supported Solr changes the schema endpoint's shape,
     * which no captured body can notice.
     */
    @Test
    fun `a real schema response reads into facts on every supported line`() {
        for ((line, container) in containers) {
            val response = schemaOf(container, COLLECTION)
            assertTrue("$line: ${response}", response is SolrResponse.Success)

            val facts = SolrServerSchemaReader.read((response as SolrResponse.Success).value)

            // Named rather than counted. `_default` declares a handful of fields where techproducts
            // declares thirty, so a threshold tuned to one is wrong about the other — and naming the
            // two every Solr collection must have says more than any count would.
            assertTrue("$line declared no id: ${facts.fields.map { it.name }}", facts.fields.any { it.name == "id" })
            assertTrue("$line declared no _version_", facts.fields.any { it.name == "_version_" })
            assertTrue("$line declared few field types: ${facts.fieldTypes.size}", facts.fieldTypes.size > 10)
            assertEquals("$line has the wrong unique key", "id", facts.uniqueKey)
        }
    }

    /**
     * Every field type carrying an analyzer has its components read.
     *
     * The regression this exists for is the one the repository parser shipped: a chain that parses
     * while everything inside it is dropped. Counting components is what tells the two apart, and a
     * real schema is where the count is worth counting.
     */
    @Test
    fun `analyzer components are read from a real schema`() {
        for ((line, container) in containers) {
            val facts = SolrServerSchemaReader.read(
                (schemaOf(container, COLLECTION) as SolrResponse.Success).value,
            )
            val components = facts.fieldTypes.sumOf {
                (it.indexAnalyzer?.components?.size ?: 0) + (it.queryAnalyzer?.components?.size ?: 0)
            }

            assertTrue("$line parsed no analyzer components at all, got $components", components > 20)
        }
    }

    /** The version the model treats as authoritative, read from the endpoint that reports it. */
    @Test
    fun `the reported version names the line the container is running`() {
        for ((line, container) in containers) {
            val response = runBlocking {
                transport.get("http://${container.host}:${container.solrPort}", "/solr/admin/info/system")
            }
            val version = SolrServerSchemaReader.solrVersionIn((response as SolrResponse.Success).value)

            assertNotNull("$line reported no version", version)
            assertTrue("$line reported '$version'", version!!.startsWith("$line."))
        }
    }

    // --- the failures the transport classifies -----------------------------------------------------

    /**
     * A mistyped collection is answered with an HTML page, not JSON.
     *
     * The single most likely user mistake, and the state the fake tier had to be *told* about because
     * no fixture author invents it. Asserting it against a real server is what says the fake is
     * faithful.
     */
    @Test
    fun `a collection that does not exist is an error carrying no Solr message`() {
        val response = schemaOf(containers.first().second, "nosuchcollection")

        assertTrue(response.toString(), response is SolrResponse.SolrError)
        assertEquals(404, (response as SolrResponse.SolrError).code)
        assertNull("Solr said nothing here, and nothing must be invented", response.message)
    }

    /** A Solr error reaches the caller in Solr's own words. */
    @Test
    fun `an unknown field in a query is a Solr error with Solr's message`() {
        val container = containers.first().second
        val response = runBlocking {
            transport.get(
                "http://${container.host}:${container.solrPort}",
                "/solr/$COLLECTION/select?q=nosuchfield:x",
            )
        }

        assertTrue(response.toString(), response is SolrResponse.SolrError)
        assertEquals(400, (response as SolrResponse.SolrError).code)
        assertTrue("expected Solr's own words, got: ${response.message}", response.message?.isNotEmpty() == true)
    }

    private companion object {

        /** The collection every container is created with. */
        const val COLLECTION = "contract"

        /**
         * One container per supported line, started once for the class.
         *
         * Per test would be correct and unaffordable: a Solr takes several seconds to become ready,
         * and nothing here writes to the server, so the containers can be shared without a test
         * depending on another's leftovers.
         */
        private val containers = mutableListOf<Pair<String, SolrContainer>>()

        /**
         * Skipped where a developer has no Docker, and *failed* where CI has none.
         *
         * `check` is what a contributor runs before every commit, and failing it on a machine with no
         * Docker teaches people to skip the gate rather than install one. So locally this tier stands
         * down — through `Assume`, so a skipped test is reported as skipped rather than passing
         * quietly.
         *
         * **In CI it must not stand down, and that asymmetry is the point.** A tier that skips
         * everywhere is a tier that runs nowhere, and its green tick would mean only that nobody
         * looked. Nothing downstream can tell those apart: the test report is uploaded on failure
         * only, so a passing build says the same thing either way. The distinction is therefore made
         * here, where it can still be enforced — `CI` is set by GitHub Actions, and where it is set a
         * missing Docker is a failure that names itself.
         */
        @BeforeClass
        @JvmStatic
        fun startContainers() {
            val dockerAvailable = DockerClientFactory.instance().isDockerAvailable
            if (System.getenv("CI") != null) {
                assertTrue(
                    "CI must run the contract tests, and found no Docker environment to run them in",
                    dockerAvailable,
                )
            } else {
                Assume.assumeTrue("no Docker environment, so the contract tests cannot run", dockerAvailable)
            }
            for ((line, tag) in listOf("10" to "10.0.0", "9" to "9.10.1")) {
                val container = SolrContainer(DockerImageName.parse("solr:$tag"))
                    .withCollection(COLLECTION)
                container.start()
                containers += line to container
            }
        }

        @AfterClass
        @JvmStatic
        fun stopContainers() {
            containers.forEach { (_, container) -> container.stop() }
            containers.clear()
        }
    }
}
