package org.apache.solr.ide.server.topology

import org.apache.solr.ide.server.reading.SolrCollection
import org.apache.solr.ide.server.reading.SolrIndexContents
import org.apache.solr.ide.server.reading.SolrIndexField
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

    /**
     * The heading under which a collection's indexed fields appear, before they are fetched.
     *
     * Distinct from [GROUP] because it is the one row that stands for a request not yet made:
     * expanding it is what asks the server, and the tree needs to tell it from a heading whose
     * children it already has.
     */
    FIELDS,

    /** One field as the index actually holds it. */
    FIELD,
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
 * @property collection which collection or core a request from this row would be addressed to, or
 *   null where the row stands for no request. Carried rather than read back out of [label], because
 *   a label is what the row *says* and is free to change — a fetch keyed on display text breaks the
 *   moment someone rewords a heading
 */
data class SolrTopologyNode(
    val label: String,
    val detail: String? = null,
    val kind: SolrTopologyNodeKind,
    val children: List<SolrTopologyNode> = emptyList(),
    val collection: String? = null,
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

    /**
     * The rows for what an index actually holds.
     *
     * **Dynamic instances are called out rather than merely listed**, because they are the reason
     * this view exists: a field the schema declares can be read from the configset without a server,
     * while `author_s` exists only because `*_s` matched something at index time and appears in no
     * configset anywhere. The group's own detail says how many there are, so the answer is visible
     * before anything is expanded.
     *
     * @param contents what the Luke handler reported
     * @return one group holding a row per field
     */
    fun fieldNodesOf(contents: SolrIndexContents): List<SolrTopologyNode> {
        val dynamic = contents.fields.count { it.isDynamicInstance }
        return contents.fields.map(::fieldNode).let { fields ->
            listOf(
                SolrTopologyNode(
                    label = FIELDS_LABEL,
                    detail = detailOf(
                        "${contents.fields.size} fields",
                        "$dynamic from dynamic patterns".takeIf { dynamic > 0 },
                        contents.summary.numDocs?.let { "$it documents" },
                    ),
                    kind = SolrTopologyNodeKind.GROUP,
                    children = fields,
                ),
            )
        }
    }

    private fun fieldNode(field: SolrIndexField) = SolrTopologyNode(
        label = field.name,
        detail = detailOf(
            field.type,
            // The pattern is written as an arrow because "created by" is the relationship, and a
            // bare `*_s` beside a type reads as another type.
            field.dynamicBase?.let { "← $it" },
            // Omitted rather than shown as zero where Solr gave no count: a point field reports none
            // even holding documents, and "0 documents" would be false about exactly the field types
            // Solr recommends.
            field.docs?.let { "$it docs" },
            field.indexNote,
        ),
        kind = SolrTopologyNodeKind.FIELD,
    )

    private fun group(label: String, children: List<SolrTopologyNode>) =
        SolrTopologyNode(label, kind = SolrTopologyNodeKind.GROUP, children = children)

    private fun collectionNode(collection: SolrCollection) = SolrTopologyNode(
        label = collection.name,
        detail = detailOf(collection.health, collection.configName),
        kind = SolrTopologyNodeKind.COLLECTION,
        // The fields row comes first because it is what most questions are about, and it is a
        // promise rather than an answer — the request behind it is made when it is expanded.
        children = listOf(fieldsPlaceholder(collection.name)) + collection.shards.map(::shardNode),
    )

    /**
     * The unexpanded row standing for a collection's indexed fields.
     *
     * **Empty on purpose.** Reading an index costs a request per collection, and a server holding
     * thirty of them would turn opening the tool window into thirty requests — which is both slow
     * and a quiet violation of the rule that server data moves only when someone asks. Expanding
     * this row is the asking.
     *
     * @param collection the collection or core whose fields it stands for, which is what the fetch
     *   is addressed to
     * @return the placeholder row
     */
    fun fieldsPlaceholder(collection: String) =
        SolrTopologyNode(label = FIELDS_LABEL, kind = SolrTopologyNodeKind.FIELDS, collection = collection)

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

    private fun coreNode(core: SolrCore) = SolrTopologyNode(
        label = core.name,
        detail = core.configSet,
        kind = SolrTopologyNodeKind.CORE,
        // A core has an index exactly as a collection does, and the question asked of it is the same.
        children = listOf(fieldsPlaceholder(core.name)),
    )

    // Absent parts drop out rather than rendering as a gap or the word "null" — Solr omits health on
    // an older line, a range on an implicitly routed shard, and a configset on a core it did not
    // create from one, and all three are ordinary rather than faults.
    private fun detailOf(vararg parts: String?): String? =
        parts.filter { !it.isNullOrBlank() }.joinToString(SEPARATOR).takeIf { it.isNotEmpty() }

    private const val SEPARATOR = " · "

    /** What the indexed-fields row is called, in one place so the placeholder and the filled row agree. */
    private const val FIELDS_LABEL = "Fields"
}
