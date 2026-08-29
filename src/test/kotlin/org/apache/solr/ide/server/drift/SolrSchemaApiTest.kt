package org.apache.solr.ide.server.drift

import org.apache.solr.ide.model.SolrAgreement
import org.apache.solr.ide.model.schema.SolrCopyField
import org.apache.solr.ide.model.schema.SolrDynamicField
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.schema.SolrFieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which differences map onto a Schema API request, and which of those this plugin will send.
 *
 * **The asymmetry is the feature, so most of these tests are about what is *not* offered.** A
 * plugin that offered every command Solr accepts would hand a user a green checkmark on a corrupted
 * index — verified against Solr 10.0.0, where `replace-field` from `string` to `pint` succeeds with
 * status 0 and afterwards `fl=that_field` fails the whole query with HTTP 500.
 */
class SolrSchemaApiTest {

    private fun field(name: String, type: String = "string", indexed: Boolean? = true, stored: Boolean? = true) =
        SolrField(name = name, type = type, indexed = indexed, stored = stored)

    private fun changeFor(agreement: SolrAgreement, repository: Any? = null, server: Any? = null) =
        SolrSchemaApi.changeFor(agreement, repository, server)

    // --- what is offered ---------------------------------------------------------------------------

    @Test
    fun `a field only the configset has becomes an add-field this plugin will send`() {
        val change = changeFor(SolrAgreement.REPOSITORY_ONLY, repository = field("price", type = "pfloat"))!!

        assertEquals("add-field", change.command)
        assertTrue(change.applicable)
        assertNull("nothing is declined, so there is nothing to explain", change.declined)
        assertTrue(change.payload, change.payload.contains("\"name\" : \"price\""))
        assertTrue(change.payload, change.payload.contains("\"type\" : \"pfloat\""))
    }

    @Test
    fun `a dynamic pattern only the configset has becomes an add-dynamic-field`() {
        val pattern = SolrDynamicField("*_s", field("*_s"))

        val change = changeFor(SolrAgreement.REPOSITORY_ONLY, repository = pattern)!!

        assertEquals("add-dynamic-field", change.command)
        assertTrue(change.applicable)
        assertTrue(change.payload, change.payload.contains("\"name\" : \"*_s\""))
    }

    /**
     * A field type is offered too, though the specification's list names three commands.
     *
     * It passes the same test the others do — a type nothing uses cannot invalidate a document — and
     * it is required for them to work: `add-field` naming a type the server lacks is refused with
     * "Field type 'x' not found", verified, so offering the field without its type would fail on a
     * difference the plugin had just claimed it could close.
     */
    @Test
    fun `a field type only the configset has becomes an add-field-type`() {
        val change = changeFor(SolrAgreement.REPOSITORY_ONLY, repository = SolrFieldType("money", "solr.DoublePointField"))!!

        assertEquals("add-field-type", change.command)
        assertTrue(change.applicable)
        assertTrue(change.payload, change.payload.contains("solr.DoublePointField"))
    }

    /** Solr's own spelling in this command is `dest`, not `destination`. */
    @Test
    fun `a copy rule only the configset has becomes an add-copy-field using solr's spelling`() {
        val change = changeFor(SolrAgreement.REPOSITORY_ONLY, repository = SolrCopyField("title", "text"))!!

        assertEquals("add-copy-field", change.command)
        assertTrue(change.applicable)
        assertTrue(change.payload, change.payload.contains("\"dest\" : \"text\""))
        assertFalse(change.payload, change.payload.contains("destination"))
    }

    // --- what is shown and refused -----------------------------------------------------------------

    /**
     * A field both sides define differently shows `replace-field` and is not offered.
     *
     * **The requirement that protects the index**, and it protects the user from Solr rather than
     * from this plugin: Solr accepts exactly this request and reports success while every indexed
     * document keeps its old encoding.
     */
    @Test
    fun `a differing field shows replace-field and refuses to send it`() {
        val change = changeFor(SolrAgreement.DISAGREEING, repository = field("code", type = "pint"))!!

        assertEquals("replace-field", change.command)
        assertFalse("this is the button that must not exist", change.applicable)
        assertEquals(SolrSchemaApi.REINDEX_REQUIRED, change.declined)
    }

    /** The payload is still shown, because reading it is what the user is left able to do. */
    @Test
    fun `a refused change still shows the request it would need`() {
        val change = changeFor(SolrAgreement.DISAGREEING, repository = field("code", type = "pint"))!!

        assertTrue(change.payload, change.payload.contains("replace-field"))
        assertTrue(change.payload, change.payload.contains("\"type\" : \"pint\""))
    }

    @Test
    fun `a differing field type shows replace-field-type and refuses`() {
        val change = changeFor(SolrAgreement.DISAGREEING, repository = SolrFieldType("text", "solr.TextField"))!!

        assertEquals("replace-field-type", change.command)
        assertFalse(change.applicable)
    }

    /**
     * A declaration only the server has shows a delete and refuses it.
     *
     * Removing from a live collection is not additive: documents indexed under it stay in the index.
     */
    @Test
    fun `a server-only field shows delete-field and refuses`() {
        val change = changeFor(SolrAgreement.SERVER_ONLY, server = field("added_via_api"))!!

        assertEquals("delete-field", change.command)
        assertFalse(change.applicable)
        assertEquals(SolrSchemaApi.REMOVAL_NOT_OFFERED, change.declined)
    }

    /** Every refusal explains itself; a refusal with no reason is the tooltip this design rejects. */
    @Test
    fun `every refused change carries its reason`() {
        val refused = listOf(
            changeFor(SolrAgreement.DISAGREEING, repository = field("a")),
            changeFor(SolrAgreement.DISAGREEING, repository = SolrFieldType("t", "solr.StrField")),
            changeFor(SolrAgreement.SERVER_ONLY, server = field("b")),
            changeFor(SolrAgreement.SERVER_ONLY, server = SolrCopyField("a", "b")),
        ).filterNotNull()

        assertTrue(refused.isNotEmpty())
        refused.forEach { assertFalse(it.command, it.applicable) }
        refused.forEach { assertNotNull(it.command, it.declined) }
    }

    @Test
    fun `an agreeing declaration has nothing to change`() {
        assertNull(changeFor(SolrAgreement.AGREEING, repository = field("id"), server = field("id")))
    }

    /** A copy rule has no `replace` in Solr's vocabulary, and inventing one would be worse. */
    @Test
    fun `a differing copy rule offers no command at all`() {
        assertNull(changeFor(SolrAgreement.DISAGREEING, repository = SolrCopyField("title", "text")))
    }

    // --- sending several at once -------------------------------------------------------------------

    /**
     * A field type goes before a field that uses it.
     *
     * Solr applies a request's commands in order, so the two succeed together where the field alone
     * would be refused — verified against Solr 10.0.0, one type and two fields using it in a single
     * request.
     */
    @Test
    fun `a request puts field types before the fields that use them`() {
        val request = SolrSchemaApi.requestFor(
            listOf(
                changeFor(SolrAgreement.REPOSITORY_ONLY, repository = field("price", type = "money"))!!,
                changeFor(SolrAgreement.REPOSITORY_ONLY, repository = SolrFieldType("money", "solr.DoublePointField"))!!,
            ),
        )!!

        assertTrue(request, request.indexOf("add-field-type") < request.indexOf("\"add-field\""))
    }

    /** Two commands of a kind go in one array, which is how Solr reads several of the same. */
    @Test
    fun `two fields share one add-field entry`() {
        val request = SolrSchemaApi.requestFor(
            listOf(
                changeFor(SolrAgreement.REPOSITORY_ONLY, repository = field("price"))!!,
                changeFor(SolrAgreement.REPOSITORY_ONLY, repository = field("cost"))!!,
            ),
        )!!

        assertEquals("one entry for the command", 1, Regex("\"add-field\"").findAll(request).count())
        assertTrue(request, request.contains("price"))
        assertTrue(request, request.contains("cost"))
    }

    /** A refused change is never in the request, whatever else is. */
    @Test
    fun `a request carries only what will be sent`() {
        val request = SolrSchemaApi.requestFor(
            listOf(
                changeFor(SolrAgreement.REPOSITORY_ONLY, repository = field("price"))!!,
                changeFor(SolrAgreement.DISAGREEING, repository = field("code", type = "pint"))!!,
                changeFor(SolrAgreement.SERVER_ONLY, server = field("gone"))!!,
            ),
        )!!

        assertTrue(request, request.contains("price"))
        assertFalse("a refused change must never be sent", request.contains("replace-field"))
        assertFalse("a refused change must never be sent", request.contains("delete-field"))
    }

    /** Nothing applicable means no request, rather than an empty one Solr would accept. */
    @Test
    fun `nothing applicable produces no request`() {
        val request = SolrSchemaApi.requestFor(
            listOf(changeFor(SolrAgreement.DISAGREEING, repository = field("code", type = "pint"))!!),
        )

        assertNull(request)
    }

    @Test
    fun `an empty list produces no request`() {
        assertNull(SolrSchemaApi.requestFor(emptyList()))
    }

    // --- what a payload carries --------------------------------------------------------------------

    /**
     * Every property the configset declares reaches the request.
     *
     * A payload that dropped `docValues` would produce a field Solr accepts and a user did not ask
     * for — and the drift view would then report the two as agreeing, because the configset and the
     * server would differ in a property nobody sent.
     */
    @Test
    fun `a fully specified field carries all of its properties`() {
        val full = SolrField(
            name = "price",
            type = "pfloat",
            indexed = true,
            stored = false,
            docValues = true,
            multiValued = true,
            required = false,
            defaultValue = "0",
        )

        val payload = SolrSchemaApi.changeFor(SolrAgreement.REPOSITORY_ONLY, repository = full, server = null)!!.payload

        listOf("indexed", "stored", "docValues", "multiValued", "required", "default").forEach {
            assertTrue("$it is missing from $payload", payload.contains("\"$it\""))
        }
    }

    /** A property the configset leaves unsaid is left unsaid, rather than sent as a guess. */
    @Test
    fun `a field says nothing about properties it does not declare`() {
        val sparse = SolrField(name = "price", type = "pfloat")

        val payload = SolrSchemaApi.changeFor(SolrAgreement.REPOSITORY_ONLY, repository = sparse, server = null)!!.payload

        assertFalse(payload, payload.contains("docValues"))
        assertFalse(payload, payload.contains("multiValued"))
    }

    @Test
    fun `a copy rule carries its character limit where it has one`() {
        val limited = SolrCopyField("title", "text", maxChars = 300)

        val payload = SolrSchemaApi.changeFor(SolrAgreement.REPOSITORY_ONLY, repository = limited, server = null)!!.payload

        assertTrue(payload, payload.contains("\"maxChars\" : 300"))
    }
}
