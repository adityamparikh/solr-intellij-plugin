package org.apache.solr.ide.server.topology

import org.apache.solr.ide.server.reading.SolrCollection
import org.apache.solr.ide.server.reading.SolrCore
import org.apache.solr.ide.server.reading.SolrReplica
import org.apache.solr.ide.server.reading.SolrServerMode
import org.apache.solr.ide.server.reading.SolrShard
import org.apache.solr.ide.server.reading.SolrTopology

/**
 * What kind of thing a row in the collections tree stands for.
 *
 * Carried so the tree can pick an icon and a caller can tell a collection from a core without
 * reading the label, which is presentation and will change.
 */
enum class SolrTopologyNodeKind {

    /** A heading — "Collections", "Aliases" — that stands for nothing on the server itself. */
    GROUP,

    /** One collection on a SolrCloud server. */
    COLLECTION,

    /** One shard of a collection. */
    SHARD,

    /** One replica of a shard. */
    REPLICA,

    /** One core on a standalone server. */
    CORE,

    /** One alias, and what it points at. */
    ALIAS,

    /** One node currently in the cluster. */
    NODE,
}

/**
 * One row of the collections tree.
 *
 * A plain data shape rather than a Swing node, so what the tree says can be decided and tested
 * without building one.
 *
 * @property label the row's name, which is the server's own name for the thing
 * @property detail the greyed text beside it, or null where the thing has nothing more to say —
 *   null rather than an empty string, so a row with no detail cannot render as a stray separator
 * @property kind what the row stands for
 * @property children its rows, in the order the server reported them
 */
data class SolrTopologyNode(
    val label: String,
    val detail: String? = null,
    val kind: SolrTopologyNodeKind,
    val children: List<SolrTopologyNode> = emptyList(),
)

/**
 * The collections tree, built from what a server said it holds.
 *
 * **Which vocabulary appears is decided by the mode and never by what happens to be populated.** A
 * standalone server has no collections and a cloud server has no cores, so reading whichever list is
 * non-empty would give the right answer for the wrong reason — and give the wrong answer for a cloud
 * server that genuinely holds nothing, which is a real state a user needs to see as "connected, and
 * empty" rather than as an unrecognized server.
 *
 * Pure, like the reader that produces its input.
 */
object SolrTopologyNodes {

    /**
     * The tree's top-level rows for [topology].
     *
     * The group naming the server's own vocabulary always appears, even holding nothing, because
     * "connected and empty" and "not connected" are different facts and an empty tree states the
     * second. The groups beside it — aliases, live nodes — appear only where the server reported
     * any: a response with no aliases omits the key rather than carrying an empty one, so an empty
     * group would report an ordinary server as missing something.
     *
     * @param topology what the server said it holds
     * @return the rows to show, or nothing at all where the server would not say what it is
     */
    fun rootsOf(topology: SolrTopology): List<SolrTopologyNode> = when (topology.mode) {
        SolrServerMode.SOLR_CLOUD -> buildList {
            add(group("Collections", topology.collections.map(::collectionNode)))
            if (topology.aliases.isNotEmpty()) {
                add(group("Aliases", topology.aliases.map { (name, target) ->
                    SolrTopologyNode(name, target, SolrTopologyNodeKind.ALIAS)
                }))
            }
            if (topology.liveNodes.isNotEmpty()) {
                add(group("Live nodes", topology.liveNodes.map { SolrTopologyNode(it, kind = SolrTopologyNodeKind.NODE) }))
            }
        }
        SolrServerMode.STANDALONE -> listOf(group("Cores", topology.cores.map(::coreNode)))
        // Neither vocabulary, because showing an empty Collections group would assert this is a
        // cloud with nothing in it — the guess the mode check exists to avoid.
        SolrServerMode.UNKNOWN -> emptyList()
    }

    private fun group(label: String, children: List<SolrTopologyNode>) =
        SolrTopologyNode(label, kind = SolrTopologyNodeKind.GROUP, children = children)

    private fun collectionNode(collection: SolrCollection) = SolrTopologyNode(
        label = collection.name,
        detail = detailOf(collection.health, collection.configName),
        kind = SolrTopologyNodeKind.COLLECTION,
        children = collection.shards.map(::shardNode),
    )

    private fun shardNode(shard: SolrShard) = SolrTopologyNode(
        label = shard.name,
        detail = detailOf(shard.health, shard.state, shard.range),
        kind = SolrTopologyNodeKind.SHARD,
        children = shard.replicas.map(::replicaNode),
    )

    // Labelled by its core rather than its replica name, because the core is what a user finds on
    // disk and in a log line; the replica name appears nowhere else they will look.
    private fun replicaNode(replica: SolrReplica) = SolrTopologyNode(
        label = replica.core,
        detail = detailOf(
            replica.type,
            replica.state,
            "leader".takeIf { replica.leader },
            replica.nodeName,
        ),
        kind = SolrTopologyNodeKind.REPLICA,
    )

    private fun coreNode(core: SolrCore) =
        SolrTopologyNode(core.name, core.configSet, SolrTopologyNodeKind.CORE)

    // Absent parts drop out rather than rendering as a gap or the word "null" — Solr omits health on
    // an older line, a range on an implicitly routed shard, and a configset on a core it did not
    // create from one, and all three are ordinary rather than faults.
    private fun detailOf(vararg parts: String?): String? =
        parts.filter { !it.isNullOrBlank() }.joinToString(SEPARATOR).takeIf { it.isNotEmpty() }

    private const val SEPARATOR = " · "
}
