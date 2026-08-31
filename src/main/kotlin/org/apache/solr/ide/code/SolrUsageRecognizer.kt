package org.apache.solr.ide.code

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * A field name a piece of code references, and where it was written.
 *
 * @property fieldName the name as written, with no surrounding query syntax
 * @property parameter the Solr request parameter it was written into, which is what the field is
 *   being asked to support — `fq` searches, `sort` orders, `facet.field` facets
 * @property element the expression the name was read out of, which is what a finding anchors to
 */
data class SolrFieldUsage(
    val fieldName: String,
    val parameter: String,
    val element: PsiElement,
)

/**
 * A Solr server a piece of code says it talks to, and who it connects as.
 *
 * **The credential travels with the URL rather than being recovered later, and that is the whole
 * reason this is one type.** Wherever an endpoint is written — a client builder, a Spring profile, a
 * Camel URI — the identity to use is written beside it, and a reader that returned bare URLs would
 * force every consumer to go back to the source a second time to ask who to connect as.
 *
 * **The password is deliberately absent.** What a source file holds in that position is usually a
 * placeholder or a reference to somewhere else — an environment variable, a vault key, a CI secret —
 * so reading it would yield a value that does not open the connection while putting something
 * secret-shaped into a model that describes code. The plugin already keeps real credentials in the
 * IDE's password safe, entered once against a connection; this type says which server and which
 * user, and that safe answers the rest.
 *
 * @property url the base URL as the source spells it, which may name a core or a collection
 * @property username the identity the code connects as, or null where it connects anonymously or
 *   the name is not spelled out at the call site
 * @property element the expression the URL was read out of, which is what a finding anchors to
 */
data class SolrEndpointUsage(
    val url: String,
    val username: String?,
    val element: PsiElement,
)

/**
 * One way of recognizing Solr usage in source: a client library, a framework, an integration.
 *
 * **A recognizer says which library it needs and never asks whether that library is present.** The
 * question is answered once, for every recognizer, by [SolrRecognizers], against the *module* rather
 * than the project — a repository where one module talks to Solr should not have the other modules
 * warned about their field names. Placing the gate here rather than in each implementation is what
 * keeps it from being the thing a later recognizer forgets, and the reason this interface exists
 * before there is more than one implementation of it.
 *
 * **Both halves are read from the same file in one pass by the same implementation**, because the two
 * facts are written together: the code that constructs a client names the server, and the code
 * beside it names the fields. Splitting them into two interfaces would mean two traversals of the
 * same syntax tree to answer questions about the same statement.
 */
interface SolrUsageRecognizer {

    /**
     * Fragments of the library coordinates this recognizer needs on a module to be worth running.
     *
     * Matched as substrings against library names as the project model reports them, which is how
     * `solr-solrj` finds `Gradle: org.apache.solr:solr-solrj:9.10.0` without this having to know how
     * any one build tool spells a version.
     */
    val libraryCoordinates: List<String>

    /**
     * The field references [file] makes, in the order they are written.
     *
     * **Ungated.** Callers reach this through [SolrRecognizers], which applies the module gate;
     * calling it directly reads a file whether or not its module has the library.
     *
     * @param file a file in a language this recognizer reads
     * @return the field usages it contains, empty where it references none
     */
    fun readFieldUsages(file: PsiFile): List<SolrFieldUsage>

    /**
     * The Solr servers [file] says it talks to, in the order they are written.
     *
     * **Ungated**, for the reason given on [readFieldUsages].
     *
     * @param file a file in a language this recognizer reads
     * @return the endpoints it names, empty where it names none
     */
    fun readEndpoints(file: PsiFile): List<SolrEndpointUsage>
}
