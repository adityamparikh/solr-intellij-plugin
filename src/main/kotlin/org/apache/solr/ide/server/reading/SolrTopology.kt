package org.apache.solr.ide.server.reading

/**
 * Which of Solr's two shapes a server is running in.
 *
 * **The distinction decides which endpoint may be asked at all**, which is why it is a type rather
 * than a boolean on something else. A standalone Solr refuses every `/admin/collections` action —
 * `LIST` included — with HTTP 400 and "Solr instance is not running in SolrCloud mode", so a reader
 * that assumed the Collections API would report a hard failure against a perfectly healthy server.
 */
enum class SolrServerMode {

    /** One node, cores rather than collections, and no Collections or Config Sets API. */
    STANDALONE,

    /** ZooKeeper-coordinated, with collections, shards and replicas. */
    SOLR_CLOUD,

    /**
     * The server did not say, so nothing may be assumed about it.
     *
     * Distinct from either answer on purpose: guessing SolrCloud here would produce the spurious
     * failure this enum exists to avoid, and guessing standalone would hide a cluster.
     */
    UNKNOWN,
}

/**
 * One replica of one shard.
 *
 * @property name the replica's name within its shard
 * @property core the core backing it on its node
 * @property nodeName the node it lives on
 * @property state what Solr reports it doing — `active`, `down`, `recovering`
 * @property type `NRT`, `TLOG` or `PULL`, which decides what the replica may serve
 * @property leader whether this replica leads its shard
 */
data class SolrReplica(
    val name: String,
    val core: String,
    val nodeName: String,
    val state: String,
    val type: String,
    val leader: Boolean,
)

/**
 * One shard of one collection.
 *
 * @property name the shard's name
 * @property range the hash range it covers, or null for an implicitly routed collection
 * @property state what Solr reports it doing
 * @property health Solr's own summary, which is worth showing rather than recomputing
 * @property replicas its replicas, in the order reported
 */
data class SolrShard(
    val name: String,
    val range: String?,
    val state: String,
    val health: String?,
    val replicas: List<SolrReplica>,
)

/**
 * One collection on a SolrCloud server.
 *
 * @property name the collection's name
 * @property configName the configset it was created from, as the server knows it. Worth showing
 *   beside a pairing prompt so a human confirming one chooses in the server's own vocabulary — it
 *   does not decide the pairing, since a name on the server says nothing about which directory on
 *   this developer's disk holds that configset
 * @property health Solr's own summary of the collection
 * @property shards its shards, in the order reported
 */
data class SolrCollection(
    val name: String,
    val configName: String?,
    val health: String?,
    val shards: List<SolrShard>,
)

/**
 * One core on a standalone server.
 *
 * A core is not a collection with fewer parts — it is the other vocabulary entirely, which is why it
 * is its own type rather than a collection with empty shards.
 *
 * @property name the core's name
 * @property configSet the configset directory it was created from, where the server reports one
 */
data class SolrCore(val name: String, val configSet: String?)

/**
 * What a server holds, in whichever vocabulary it uses.
 *
 * **Both lists exist and only one is ever populated**, which is deliberate: a view has to render one
 * vocabulary or the other, and a single list called "things" would leave it guessing which word to
 * put on screen. The mode says which list to read, and a caller that ignores the mode and reads both
 * still gets a correct answer rather than a merged one.
 *
 * @property mode which shape the server is running in
 * @property collections its collections, empty unless the mode is [SolrServerMode.SOLR_CLOUD]
 * @property cores its cores, empty unless the mode is [SolrServerMode.STANDALONE]
 * @property aliases alias name to what it points at, empty where none are defined — a response with
 *   no aliases omits the key entirely rather than carrying an empty one, and that is its ordinary
 *   shape rather than a fault
 * @property liveNodes the nodes currently in the cluster, empty on a standalone server
 */
data class SolrTopology(
    val mode: SolrServerMode,
    val collections: List<SolrCollection> = emptyList(),
    val cores: List<SolrCore> = emptyList(),
    val aliases: Map<String, String> = emptyMap(),
    val liveNodes: List<String> = emptyList(),
)
