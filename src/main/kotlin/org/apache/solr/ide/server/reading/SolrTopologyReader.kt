package org.apache.solr.ide.server.reading

import tools.jackson.databind.JsonNode

/**
 * Reads what a server holds out of the responses that report it.
 *
 * Three responses, because Solr has no single one that answers the question: the system-info response
 * says which shape the server is running in, and then either `CLUSTERSTATUS` or the cores status
 * answers in that shape's vocabulary. Which of the two is asked is decided before asking, never by
 * trying one and catching the refusal — see [SolrServerMode].
 *
 * Pure functions from a parsed body, like the schema reader beside it, so the shapes can be tested
 * against captured responses and the composition tested separately.
 */
object SolrTopologyReader {

    /**
     * The shape [systemInfo] says the server is running in.
     *
     * `std` and `solrcloud` are the two values Solr reports, verified on both supported lines.
     * Anything else — including a response that carries no `mode` at all — is
     * [SolrServerMode.UNKNOWN] rather than a guess, because both guesses are wrong in a way a user
     * would meet: assuming cloud produces a spurious failure on a healthy standalone server, and
     * assuming standalone hides a cluster.
     *
     * @param systemInfo a parsed response from `GET /admin/info/system`
     * @return the mode it reports
     */
    fun modeIn(systemInfo: JsonNode): SolrServerMode = when (systemInfo.path(MODE).asString("")) {
        "solrcloud" -> SolrServerMode.SOLR_CLOUD
        "std" -> SolrServerMode.STANDALONE
        else -> SolrServerMode.UNKNOWN
    }

    /**
     * The cluster in [clusterStatus], as a SolrCloud server reports it.
     *
     * @param clusterStatus a parsed response from `/admin/collections?action=CLUSTERSTATUS`
     * @return its collections, aliases and live nodes
     */
    fun cloudTopologyIn(clusterStatus: JsonNode): SolrTopology {
        val cluster = clusterStatus.path(CLUSTER)
        return SolrTopology(
            mode = SolrServerMode.SOLR_CLOUD,
            collections = cluster.path(COLLECTIONS).properties()
                .map { (name, node) -> readCollection(name, node) },
            // Absent rather than empty when none are defined, which is this response's ordinary
            // shape and not a fault.
            aliases = cluster.path(ALIASES).properties()
                .filter { (_, value) -> value.isValueNode }
                .associate { (name, value) -> name to value.asString("") },
            liveNodes = cluster.path(LIVE_NODES).items().map { it.asString("") },
        )
    }

    /**
     * The cores in [coresStatus], as a standalone server reports them.
     *
     * @param coresStatus a parsed response from `/admin/cores?action=STATUS`
     * @return its cores
     */
    fun standaloneTopologyIn(coresStatus: JsonNode): SolrTopology = SolrTopology(
        mode = SolrServerMode.STANDALONE,
        cores = coresStatus.path(STATUS).properties().map { (name, node) ->
            SolrCore(name = name, configSet = node.path(CONFIG_SET).asString("").takeIf { it.isNotEmpty() })
        },
    )

    private fun readCollection(name: String, node: JsonNode): SolrCollection = SolrCollection(
        name = name,
        configName = node.path(CONFIG_NAME).asString("").takeIf { it.isNotEmpty() },
        health = node.path(HEALTH).asString("").takeIf { it.isNotEmpty() },
        shards = node.path(SHARDS).properties().map { (shardName, shard) -> readShard(shardName, shard) },
    )

    private fun readShard(name: String, node: JsonNode): SolrShard = SolrShard(
        name = name,
        // Absent on an implicitly routed collection, where a shard covers no hash range at all.
        range = node.path(RANGE).asString("").takeIf { it.isNotEmpty() },
        state = node.path(STATE).asString(""),
        health = node.path(HEALTH).asString("").takeIf { it.isNotEmpty() },
        replicas = node.path(REPLICAS).properties().map { (replicaName, replica) ->
            SolrReplica(
                name = replicaName,
                core = replica.path(CORE).asString(""),
                nodeName = replica.path(NODE_NAME).asString(""),
                state = replica.path(STATE).asString(""),
                type = replica.path(TYPE).asString(""),
                // Solr writes this as the *string* "true" rather than a JSON boolean, and only on
                // the replica that leads — an absent key means "not the leader" rather than unknown.
                leader = replica.path(LEADER).asString("") == "true" || replica.path(LEADER).asBoolean(false),
            )
        },
    )

    private fun JsonNode.items(): List<JsonNode> = if (isArray) toList() else emptyList()

    // The keys these responses use. Named for the same reason the schema reader's are: `path`
    // answers a misspelling with an empty node rather than an error, so a typo here is a server that
    // reports nothing and a plugin that says so confidently.
    private const val MODE = "mode"
    private const val CLUSTER = "cluster"
    private const val COLLECTIONS = "collections"
    private const val ALIASES = "aliases"
    private const val LIVE_NODES = "live_nodes"
    private const val SHARDS = "shards"
    private const val REPLICAS = "replicas"
    private const val STATUS = "status"
    private const val CONFIG_NAME = "configName"
    private const val CONFIG_SET = "configSet"
    private const val HEALTH = "health"
    private const val RANGE = "range"
    private const val STATE = "state"
    private const val CORE = "core"
    private const val NODE_NAME = "node_name"
    private const val TYPE = "type"
    private const val LEADER = "leader"
}
