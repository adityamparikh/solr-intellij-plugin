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
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.SolrContainer
import org.testcontainers.containers.wait.strategy.Wait
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

    private fun get(container: SolrContainer, path: String) = get(container.host, container.solrPort, path)

    private fun get(container: GenericContainer<*>, path: String) =
        get(container.host, container.getMappedPort(SOLR_PORT), path)

    private fun get(host: String, port: Int, path: String) = runBlocking {
        transport.get("http://$host:$port/solr", path)
    }

    private fun bodyOf(container: SolrContainer, path: String) = bodyOf(get(container, path), path)

    private fun bodyOf(container: GenericContainer<*>, path: String) = bodyOf(get(container, path), path)

    private fun bodyOf(response: SolrResponse<tools.jackson.databind.JsonNode>, path: String): tools.jackson.databind.JsonNode {
        assertTrue("$path: $response", response is SolrResponse.Success)
        return (response as SolrResponse.Success).value
    }

    /**
     * The topology a server reports, read the way the composition reads it.
     *
     * The mode decides the endpoint here exactly as it does in `SolrServerReader`, which is what
     * makes this a test of the shapes rather than a second implementation: composition is tested
     * against an embedded server, and only a real Solr can say what these responses look like.
     */
    private fun topologyOf(container: SolrContainer): SolrTopology =
        topologyOf { path -> bodyOf(container, path) }

    private fun topologyOf(container: GenericContainer<*>): SolrTopology =
        topologyOf { path -> bodyOf(container, path) }

    /** The mode decides the endpoint here exactly as it does in the composition under test. */
    private fun topologyOf(body: (String) -> tools.jackson.databind.JsonNode): SolrTopology =
        when (val mode = SolrTopologyReader.modeIn(body("/admin/info/system"))) {
            SolrServerMode.SOLR_CLOUD ->
                SolrTopologyReader.cloudTopologyIn(body("/admin/collections?action=CLUSTERSTATUS"))
            SolrServerMode.STANDALONE ->
                SolrTopologyReader.standaloneTopologyIn(body("/admin/cores?action=STATUS"))
            SolrServerMode.UNKNOWN -> throw AssertionError("the server reported mode $mode")
        }

    private fun schemaOf(container: SolrContainer, collection: String) = runBlocking {
        transport.get("http://${container.host}:${container.solrPort}/solr", "/$collection/schema")
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
                transport.get("http://${container.host}:${container.solrPort}/solr", "/admin/info/system")
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
                "http://${container.host}:${container.solrPort}/solr",
                "/$COLLECTION/select?q=nosuchfield:x",
            )
        }

        assertTrue(response.toString(), response is SolrResponse.SolrError)
        assertEquals(400, (response as SolrResponse.SolrError).code)
        assertTrue("expected Solr's own words, got: ${response.message}", response.message?.isNotEmpty() == true)
    }

    // --- the two shapes a server runs in ------------------------------------------------------------

    /**
     * A SolrCloud server reports collections, and the reader reads them.
     *
     * `withZookeeper` is what makes this a fixture rather than an argument: the same image, started
     * the other way, and the whole vocabulary changes.
     */
    @Test
    fun `a solrcloud server reports its collections`() {
        val topology = topologyOf(cloud)

        assertEquals(SolrServerMode.SOLR_CLOUD, topology.mode)
        assertTrue("expected a collection, got ${topology.collections.map { it.name }}",
            topology.collections.any { it.name == COLLECTION })
        assertTrue("expected live nodes, got ${topology.liveNodes}", topology.liveNodes.isNotEmpty())
        assertTrue("a collection should have shards", topology.collections.first().shards.isNotEmpty())
    }

    /** A shard names its replicas, and one of them leads. */
    @Test
    fun `a shard reports replicas and which one leads`() {
        val shard = topologyOf(cloud).collections.single { it.name == COLLECTION }.shards.first()

        assertTrue("expected replicas", shard.replicas.isNotEmpty())
        assertTrue("no replica reported itself leader: ${shard.replicas}", shard.replicas.any { it.leader })
        assertTrue("a replica should name its core", shard.replicas.all { it.core.isNotEmpty() })
    }

    /**
     * A standalone server reports cores, and is never asked for collections.
     *
     * The requirement's real content: this same request against a standalone Solr's Collections API
     * answers HTTP 400, so a reader that assumed one vocabulary would report a hard failure against a
     * server that is working perfectly.
     */
    @Test
    fun `a standalone server reports cores rather than collections`() {
        val topology = topologyOf(standalone)

        assertEquals(SolrServerMode.STANDALONE, topology.mode)
        assertTrue(
            "expected a core, got ${topology.cores.map { it.name }}",
            topology.cores.any { it.name == STANDALONE_CORE },
        )
        assertTrue("a standalone server has no collections", topology.collections.isEmpty())
    }

    /** And the Collections API really does refuse it, which is why the mode is read first. */
    @Test
    fun `a standalone server refuses the collections api`() {
        val response = get(standalone, "/admin/collections?action=LIST")

        assertTrue(response.toString(), response is SolrResponse.SolrError)
        assertTrue(
            "expected Solr to say why, got: ${(response as SolrResponse.SolrError).message}",
            response.message?.contains("SolrCloud") == true,
        )
    }

    private companion object {

        /** The collection every container is created with. */
        const val COLLECTION = "contract"

        /** Solr's port inside the image, which a plain container has to be told about. */
        /** The core the standalone container precreates, named once so the wait and the test agree. */
        private const val STANDALONE_CORE: String = "standalone_core"

        const val SOLR_PORT = 8983

        /**
         * One container per supported line, started once for the class.
         *
         * Per test would be correct and unaffordable: a Solr takes several seconds to become ready,
         * and nothing here writes to the server, so the containers can be shared without a test
         * depending on another's leftovers.
         */
        private val containers = mutableListOf<Pair<String, SolrContainer>>()

        /** One SolrCloud server, for the half of the requirement standalone cannot show. */
        private lateinit var cloud: SolrContainer

        /**
         * One standalone server, which `SolrContainer` cannot provide.
         *
         * **The module always starts SolrCloud** — `withZookeeper(false)` does not change the mode
         * the server reports, and it creates a `dummy` collection regardless, which is a cloud
         * concept. Measured: a container built every way the module allows reports `mode: solrcloud`.
         *
         * So the other shape comes from a plain `GenericContainer` running the image's own
         * `solr-precreate`, which is how a standalone Solr is started by hand. That is the one place
         * this file assembles a container itself, and it is because the module's abstraction does not
         * reach the distinction the requirement is about.
         */
        private lateinit var standalone: GenericContainer<*>

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
            cloud = SolrContainer(DockerImageName.parse("solr:10.0.0"))
                .withZookeeper(true)
                .withCollection(COLLECTION)
            cloud.start()

            standalone = GenericContainer(DockerImageName.parse("solr:10.0.0"))
                .withExposedPorts(SOLR_PORT)
                .withCommand("solr-precreate", STANDALONE_CORE)
                // **Waits for the core, not for Solr.** `/admin/info/system` answers 200 as soon as
                // Jetty is up, which is before `solr-precreate` has finished creating the core — so
                // a container reported ready on that signal can still fail a test that asks what
                // cores exist, and did: one CI run saw the mode read correctly and the core list
                // come back empty. A core's own ping handler answers only once the core is loaded,
                // which is the condition these tests actually depend on.
                .waitingFor(
                    Wait.forHttp("/solr/$STANDALONE_CORE/admin/ping").forPort(SOLR_PORT).forStatusCode(200),
                )
            standalone.start()
        }

        @AfterClass
        @JvmStatic
        fun stopContainers() {
            containers.forEach { (_, container) -> container.stop() }
            containers.clear()
            if (::cloud.isInitialized) cloud.stop()
            if (::standalone.isInitialized) standalone.stop()
        }
    }
}
