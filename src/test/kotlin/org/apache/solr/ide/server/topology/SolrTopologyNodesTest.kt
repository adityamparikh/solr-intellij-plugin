package org.apache.solr.ide.server.topology

import org.apache.solr.ide.server.reading.SolrCollection
import org.apache.solr.ide.server.reading.SolrCore
import org.apache.solr.ide.server.reading.SolrIndexContents
import org.apache.solr.ide.server.reading.SolrIndexField
import org.apache.solr.ide.server.reading.SolrIndexSummary
import org.apache.solr.ide.server.reading.SolrReplica
import org.apache.solr.ide.server.reading.SolrServerMode
import org.apache.solr.ide.server.reading.SolrShard
import org.apache.solr.ide.server.reading.SolrTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning what a server holds into what a tree shows.
 *
 * Plain JUnit 4: this is a function from one data shape to another, and the tree that renders it
 * needs no say in what it says.
 */
class SolrTopologyNodesTest {

    private fun replica(name: String, leader: Boolean = false, state: String = "active") = SolrReplica(
        name = name,
        core = "books_shard1_replica_n1",
        nodeName = "127.0.0.1:8983_solr",
        state = state,
        type = "NRT",
        leader = leader,
    )

    private fun shard(name: String = "shard1", replicas: List<SolrReplica> = listOf(replica("core_node2"))) =
        SolrShard(name = name, range = "80000000-7fffffff", state = "active", health = "GREEN", replicas = replicas)

    private fun collection(name: String = "books", shards: List<SolrShard> = listOf(shard())) =
        SolrCollection(name = name, configName = "books_config", health = "GREEN", shards = shards)

    private fun cloud(
        collections: List<SolrCollection> = listOf(collection()),
        aliases: Map<String, String> = emptyMap(),
        liveNodes: List<String> = emptyList(),
    ) = SolrTopology(SolrServerMode.SOLR_CLOUD, collections = collections, aliases = aliases, liveNodes = liveNodes)

    private fun rootsOf(topology: SolrTopology) = SolrTopologyNodes.rootsOf(topology)

    private fun labelsOf(nodes: List<SolrTopologyNode>) = nodes.map { it.label }

    // Selected by kind rather than by position: a collection carries its indexed-fields row as well
    // as its shards, and a test that reached for "the only child" would break every time the row
    // order changed for reasons it does not care about.
    private fun shardsOf(collection: SolrTopologyNode) =
        collection.children.filter { it.kind == SolrTopologyNodeKind.SHARD }

    // --- the cloud vocabulary ---------------------------------------------------------------------

    @Test
    fun `a cloud server is grouped by collections`() {
        val roots = rootsOf(cloud())

        assertEquals(listOf("Collections"), labelsOf(roots))
        assertEquals(SolrTopologyNodeKind.GROUP, roots.single().kind)
    }

    @Test
    fun `each collection is a node under the group`() {
        val collections = rootsOf(cloud(collections = listOf(collection("books"), collection("films")))).single()

        assertEquals(listOf("books", "films"), labelsOf(collections.children))
        assertTrue(collections.children.all { it.kind == SolrTopologyNodeKind.COLLECTION })
    }

    @Test
    fun `a collection says which configset made it and how healthy it is`() {
        val books = rootsOf(cloud()).single().children.single()

        assertTrue(books.detail, books.detail!!.contains("books_config"))
        assertTrue(books.detail, books.detail!!.contains("GREEN"))
    }

    @Test
    fun `shards hang under their collection and replicas under their shard`() {
        val books = rootsOf(cloud()).single().children.single()
        val shard = shardsOf(books).single()

        assertEquals("shard1", shard.label)
        assertEquals(SolrTopologyNodeKind.SHARD, shard.kind)
        assertEquals(SolrTopologyNodeKind.REPLICA, shard.children.single().kind)
    }

    /** A replica is identified by the core backing it, which is what a user finds on disk. */
    @Test
    fun `a replica is labelled by its core`() {
        val replica = shardsOf(rootsOf(cloud()).single().children.single()).single().children.single()

        assertEquals("books_shard1_replica_n1", replica.label)
        assertTrue(replica.detail, replica.detail!!.contains("127.0.0.1:8983_solr"))
    }

    /** Which replica leads decides where writes go, so it is worth saying rather than leaving to be worked out. */
    @Test
    fun `the leading replica says so`() {
        val collectionNode = rootsOf(cloud(collections = listOf(collection(shards = listOf(shard(replicas = listOf(
            replica("core_node2", leader = true),
            replica("core_node4", leader = false),
        ))))))).single().children.single()
        val leaders = shardsOf(collectionNode).single().children

        assertTrue(leaders[0].detail, leaders[0].detail!!.contains("leader"))
        assertTrue(leaders[1].detail, !leaders[1].detail!!.contains("leader"))
    }

    // --- the standalone vocabulary ----------------------------------------------------------------

    @Test
    fun `a standalone server is grouped by cores`() {
        val roots = rootsOf(SolrTopology(SolrServerMode.STANDALONE, cores = listOf(SolrCore("books", "_default"))))

        assertEquals(listOf("Cores"), labelsOf(roots))
        assertEquals(listOf("books"), labelsOf(roots.single().children))
    }

    @Test
    fun `a core says which configset made it`() {
        val core = rootsOf(SolrTopology(SolrServerMode.STANDALONE, cores = listOf(SolrCore("books", "_default"))))
            .single().children.single()

        assertEquals("_default", core.detail)
    }

    /** A core Solr reports no configset for says nothing rather than saying "null". */
    @Test
    fun `a core with no configset has no detail`() {
        val core = rootsOf(SolrTopology(SolrServerMode.STANDALONE, cores = listOf(SolrCore("books", null))))
            .single().children.single()

        assertNull(core.detail)
    }

    // --- what appears only when there is something to say -----------------------------------------

    @Test
    fun `aliases appear when there are any`() {
        val roots = rootsOf(cloud(aliases = mapOf("current" to "books")))

        assertEquals(listOf("Collections", "Aliases"), labelsOf(roots))
        val alias = roots[1].children.single()
        assertEquals("current", alias.label)
        assertEquals("books", alias.detail)
    }

    /**
     * A server with no aliases shows no Aliases group.
     *
     * A response with no aliases omits the key entirely rather than carrying an empty one — that is
     * its ordinary shape, and an empty group would report a normal server as having something
     * missing.
     */
    @Test
    fun `no aliases means no aliases group`() {
        assertTrue(labelsOf(rootsOf(cloud())).none { it == "Aliases" })
    }

    @Test
    fun `live nodes appear when the server reports any`() {
        val roots = rootsOf(cloud(liveNodes = listOf("127.0.0.1:8983_solr")))

        assertEquals(listOf("Collections", "Live nodes"), labelsOf(roots))
        assertEquals(listOf("127.0.0.1:8983_solr"), labelsOf(roots[1].children))
    }

    /**
     * The primary group stays even when it is empty, unlike the secondary ones.
     *
     * "Connected, and there is nothing here" and "not connected" are different facts, and an empty
     * tree says the second when it means the first.
     */
    @Test
    fun `a cloud server with no collections still shows the collections group`() {
        val roots = rootsOf(cloud(collections = emptyList()))

        assertEquals(listOf("Collections"), labelsOf(roots))
        assertTrue(roots.single().children.isEmpty())
    }

    // --- the mode that is not a vocabulary --------------------------------------------------------

    /**
     * A server that would not say which mode it is in gets neither vocabulary.
     *
     * Showing an empty Collections group would assert the server is a cloud with nothing in it,
     * which is exactly the guess the mode check exists to avoid.
     */
    @Test
    fun `an unknown server offers no vocabulary at all`() {
        assertTrue(rootsOf(SolrTopology(SolrServerMode.UNKNOWN)).isEmpty())
    }

    // --- the fields row, which stands for a request not yet made -----------------------------------

    /**
     * Every collection offers its indexed fields, and offers them empty.
     *
     * Reading an index costs a request per collection. A server holding thirty of them would turn
     * opening the tool window into thirty requests — slow, and a quiet breach of the rule that
     * server data moves only when someone asks.
     */
    @Test
    fun `a collection offers an unfetched fields row`() {
        val books = rootsOf(cloud()).single().children.single()
        val fields = books.children.first()

        assertEquals(SolrTopologyNodeKind.FIELDS, fields.kind)
        assertTrue("the request has not been made yet", fields.children.isEmpty())
    }

    /** The row knows what a request from it would be addressed to. */
    @Test
    fun `the fields row carries the collection it would ask about`() {
        val books = rootsOf(cloud(collections = listOf(collection("books")))).single().children.single()

        assertEquals("books", books.children.first().collection)
    }

    /** A core has an index exactly as a collection does. */
    @Test
    fun `a core offers an unfetched fields row too`() {
        val core = rootsOf(SolrTopology(SolrServerMode.STANDALONE, cores = listOf(SolrCore("books", "_default"))))
            .single().children.single()

        assertEquals(SolrTopologyNodeKind.FIELDS, core.children.single().kind)
        assertEquals("books", core.children.single().collection)
    }

    /** Shards still hang under the collection, after the fields row rather than instead of it. */
    @Test
    fun `the fields row does not displace the shards`() {
        val books = rootsOf(cloud()).single().children.single()

        assertEquals(listOf(SolrTopologyNodeKind.FIELDS, SolrTopologyNodeKind.SHARD), books.children.map { it.kind })
    }

    // --- what the fetched fields say ---------------------------------------------------------------

    private val indexed = SolrIndexContents(
        summary = SolrIndexSummary(numDocs = 3),
        fields = listOf(
            SolrIndexField(name = "id", type = "string", docs = 3),
            SolrIndexField(name = "author_s", type = "string", dynamicBase = "*_s", docs = 3),
            SolrIndexField(name = "price_f", type = "pfloat", dynamicBase = "*_f"),
        ),
    )

    @Test
    fun `every field the index holds gets a row`() {
        val fields = SolrTopologyNodes.fieldNodesOf(indexed).single()

        assertEquals(listOf("id", "author_s", "price_f"), labelsOf(fields.children))
        assertTrue(fields.children.all { it.kind == SolrTopologyNodeKind.FIELD })
    }

    /**
     * A field a dynamic pattern created names the pattern.
     *
     * The fact the whole view exists for, and the one no configset can supply.
     */
    @Test
    fun `a dynamic instance shows the pattern that created it`() {
        val author = SolrTopologyNodes.fieldNodesOf(indexed).single().children[1]

        assertTrue(author.detail, author.detail!!.contains("*_s"))
    }

    @Test
    fun `a declared field shows no pattern`() {
        val id = SolrTopologyNodes.fieldNodesOf(indexed).single().children[0]

        assertFalse(id.detail, id.detail!!.contains("←"))
    }

    /**
     * How many fields the schema could not have told you is said before anything is expanded.
     *
     * That count is the view's answer in one line: two of these three fields exist because a pattern
     * matched, and no configset names either.
     */
    @Test
    fun `the group counts the fields and the dynamic ones among them`() {
        val fields = SolrTopologyNodes.fieldNodesOf(indexed).single()

        assertTrue(fields.detail, fields.detail!!.contains("3 fields"))
        assertTrue(fields.detail, fields.detail!!.contains("2 from dynamic patterns"))
        assertTrue(fields.detail, fields.detail!!.contains("3 documents"))
    }

    /** An index with nothing dynamic in it says so by omission rather than by saying zero. */
    @Test
    fun `no dynamic instances means the count is left unsaid`() {
        val plain = SolrIndexContents(fields = listOf(SolrIndexField(name = "id", type = "string")))

        val detail = SolrTopologyNodes.fieldNodesOf(plain).single().detail

        assertTrue(detail, detail!!.contains("1 fields"))
        assertFalse(detail, detail.contains("dynamic"))
    }

    /**
     * A field Solr gave no document count for shows none, rather than showing zero.
     *
     * `price_f` is a point field: it holds three documents and Solr reports no count, having no
     * inverted index to count from. "0 docs" would be false about exactly the field types Solr
     * recommends people use.
     */
    @Test
    fun `a field with no reported count says nothing about documents`() {
        val price = SolrTopologyNodes.fieldNodesOf(indexed).single().children[2]

        assertFalse(price.detail, price.detail!!.contains("docs"))
        assertTrue(price.detail, price.detail!!.contains("pfloat"))
    }

    @Test
    fun `an empty index still produces a fields group`() {
        val fields = SolrTopologyNodes.fieldNodesOf(SolrIndexContents()).single()

        assertEquals(SolrTopologyNodeKind.GROUP, fields.kind)
        assertTrue(fields.children.isEmpty())
    }
}
