package org.apache.solr.ide.server.indexing

import org.apache.solr.ide.model.SolrConfigsetFacts

/**
 * Something about a document worth saying before it is sent.
 *
 * @property field the field the problem is about, or null where it is about the document
 * @property message what to say, in the user's terms
 * @property severity whether this stops the send or merely warns
 */
data class SolrDocumentProblem(
    val field: String?,
    val message: String,
    val severity: SolrDocumentSeverity,
)

/** How much a problem matters. */
enum class SolrDocumentSeverity {

    /** Sending this would fail, or would do something the user did not ask for. */
    ERROR,

    /** Sending this works and is worth knowing about first. */
    WARNING,
}

/**
 * What is wrong with a document before Solr is asked.
 *
 * **This exists because Solr will not tell you.** Verified against Solr 10.0.0 running the
 * `_default` configset, which is what a collection created without one gets:
 *
 * - A document naming a field the schema does not have returns `status: 0`. The default update
 *   chain is `add-unknown-fields-to-the-schema`, so the field is **added to the deployed schema** —
 *   one typo produced a field, a `_str` companion, and a copy-field directive between them. The
 *   drift view then reports all three as server-only declarations the repository forgot.
 * - A document with no unique key also returns `status: 0`. Solr generates a UUID, producing a
 *   document that indexed successfully and cannot be found again by the id anyone knows.
 *
 * Both are cases where the answer to "did that work" is yes and the outcome is not what was meant.
 * Checking before sending is the only place either can be caught.
 */
object SolrDocumentValidation {

    /**
     * Everything worth saying about [fieldNames] against [facts].
     *
     * @param fieldNames the field names the document carries, in document order
     * @param facts the schema the document will be indexed against
     * @return the problems, most severe first
     */
    fun problemsIn(fieldNames: List<String>, facts: SolrConfigsetFacts): List<SolrDocumentProblem> {
        val problems = mutableListOf<SolrDocumentProblem>()

        val uniqueKey = facts.uniqueKey
        if (uniqueKey != null && uniqueKey !in fieldNames) {
            problems += SolrDocumentProblem(
                field = uniqueKey,
                message = "The document has no $uniqueKey. Solr will index it under a generated " +
                    "identifier rather than refusing it, and the document will not be findable by " +
                    "any id you know.",
                severity = SolrDocumentSeverity.ERROR,
            )
        }

        fieldNames.filterNot { it.isKnownTo(facts) }.forEach { unknown ->
            problems += SolrDocumentProblem(
                field = unknown,
                message = "The schema declares no $unknown and no pattern matching it. A collection " +
                    "running the default update chain will add it to the schema rather than " +
                    "refusing the document, which changes the deployed schema and shows up as drift.",
                severity = SolrDocumentSeverity.ERROR,
            )
        }

        fieldNames.filter { it.isInternal() }.forEach { internal ->
            problems += SolrDocumentProblem(
                field = internal,
                message = "$internal is Solr's own field. Supplying one asserts a concurrency check " +
                    "or a routing decision that was probably not intended.",
                severity = SolrDocumentSeverity.WARNING,
            )
        }

        return problems.sortedBy { it.severity }
    }

    /**
     * Whether the schema has somewhere to put a field of this name.
     *
     * A dynamic pattern counts. `author_s` is not declared anywhere and is entirely legitimate,
     * which is the whole reason the model carries patterns as well as fields.
     */
    private fun String.isKnownTo(facts: SolrConfigsetFacts): Boolean =
        facts.fields.any { it.name == this } ||
            facts.dynamicFields.any { it.matches(this) } ||
            isInternal()

    private fun String.isInternal() = length > 2 && startsWith('_') && endsWith('_')
}
