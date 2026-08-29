package org.apache.solr.ide.server.reading

/**
 * One field as the index actually holds it.
 *
 * **Not a [org.apache.solr.ide.model.schema.SolrField], and deliberately so.** That type is the shape
 * a configset and a schema response share, because the same shape serves both sources. This is a
 * third answer to a different question, and the difference is [dynamicBase]: a configset can declare
 * every field it has, but it cannot declare a field that exists only because `*_s` matched something
 * at index time. Folding this into the symmetric type would make the symmetry false, and the first
 * thing to break would be drift — every dynamic field's instances would read as server-only fields
 * the repository forgot to declare, on a configset that declared the pattern perfectly well.
 *
 * @property name the field's name in the index
 * @property type the field type Solr resolved it to
 * @property dynamicBase the dynamic pattern that created it, or null where the schema declares it by
 *   name. **The one fact that makes this view worth having**, since it is precisely what no
 *   configset can tell you
 * @property docs how many documents carry it, or null where Solr did not say — which is not zero and
 *   must not be shown as zero. A point field reports no count at all even with documents in it,
 *   because it has no inverted index to count from
 * @property schemaProperties what the schema says the field is, decoded from Solr's own legend
 * @property indexProperties what the index actually has for it, decoded the same way — empty where
 *   Solr reported none, which is ordinary for a field that is `docValues`-only
 * @property indexNote what Solr said in place of index flags, where it wrote prose instead —
 *   `(unstored field)` is the shape observed on both supported lines, and reading it as flags would
 *   invent properties out of its punctuation
 */
data class SolrIndexField(
    val name: String,
    val type: String,
    val dynamicBase: String? = null,
    val docs: Int? = null,
    val schemaProperties: List<String> = emptyList(),
    val indexProperties: List<String> = emptyList(),
    val indexNote: String? = null,
) {
    /** Whether this field exists because a dynamic pattern matched, rather than by being declared. */
    val isDynamicInstance: Boolean get() = dynamicBase != null
}

/**
 * What the index holds in the large.
 *
 * @property numDocs live documents
 * @property maxDoc documents including those deleted but not yet merged away
 * @property deletedDocs documents marked deleted, which is the gap between the two above
 * @property segmentCount how many segments the index is in
 * @property current whether the index is up to date with its last commit
 */
data class SolrIndexSummary(
    val numDocs: Int? = null,
    val maxDoc: Int? = null,
    val deletedDocs: Int? = null,
    val segmentCount: Int? = null,
    val current: Boolean? = null,
)

/**
 * What one collection's index actually contains, as the Luke handler reports it.
 *
 * A third view beside the repository's schema and the server's schema, never merged into either.
 *
 * @property summary the index in the large
 * @property fields every field the index knows about, in the order Solr reported them
 */
data class SolrIndexContents(
    val summary: SolrIndexSummary = SolrIndexSummary(),
    val fields: List<SolrIndexField> = emptyList(),
)
