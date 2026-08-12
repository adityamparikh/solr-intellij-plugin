package org.apache.solr.ide.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.Type

/**
 * The descriptor-parsing rules of the catalog generator, pinned as plain functions.
 *
 * The generator's end-to-end behavior is asserted by `SolrClassCatalogTest` in the main suite,
 * against catalogs read from real Solr artifacts. These tests cover the other direction: each
 * rule's contract on descriptors chosen to *name* it, so a regression fails here with the rule's
 * name on it rather than surfacing as one attribute quietly changing type in a 170-entry file.
 */
class GenerateSolrCatalogTaskTest {

    /** The three checked types are exactly the primitive returns Solr's typed readers use. */
    @Test
    fun `a primitive return names the value type`() {
        assertEquals("int", GenerateSolrCatalogTask.valueTypeOf("(Ljava/util/Map;Ljava/lang/String;I)I"))
        assertEquals("bool", GenerateSolrCatalogTask.valueTypeOf("(Ljava/util/Map;Ljava/lang/String;Z)Z"))
        assertEquals("float", GenerateSolrCatalogTask.valueTypeOf("(Ljava/util/Map;Ljava/lang/String;F)F"))
        assertEquals("float", GenerateSolrCatalogTask.valueTypeOf("(Ljava/util/Map;Ljava/lang/String;D)D"))
    }

    /**
     * Everything else is `free`, including returns a stricter reading could type. `J` matters
     * here: no typed reader on `AbstractAnalysisFactory` returns a long today, so if one appears
     * it must land on `free` — more permissive, never a warning on a correct file — rather than
     * on a guess.
     */
    @Test
    fun `an unrecognized return is free, not a guess`() {
        assertEquals("free", GenerateSolrCatalogTask.valueTypeOf("(Ljava/util/Map;Ljava/lang/String;)Ljava/lang/String;"))
        assertEquals("free", GenerateSolrCatalogTask.valueTypeOf("(Ljava/util/Map;Ljava/lang/String;C)C"))
        assertEquals("free", GenerateSolrCatalogTask.valueTypeOf("(Ljava/util/Map;Ljava/lang/String;)Ljava/util/Set;"))
        assertEquals("free", GenerateSolrCatalogTask.valueTypeOf("(Ljava/util/Map;Ljava/lang/String;J)J"))
        // The erased `Map.remove`, which rule B admits as a read but which can never carry a type.
        assertEquals("free", GenerateSolrCatalogTask.valueTypeOf("(Ljava/lang/Object;)Ljava/lang/Object;"))
    }

    @Test
    fun `an agreeing or first reading keeps its type`() {
        assertEquals("int", GenerateSolrCatalogTask.mergeType(null, "int"))
        assertEquals("int", GenerateSolrCatalogTask.mergeType("int", "int"))
    }

    /**
     * A conflict resolves to `free` no matter which side was checked: a name read as `getInt` in
     * one class and `get` in its subclass means the extraction did not fully understand the
     * hierarchy, and declining the claim beats value-checking against the wrong type.
     */
    @Test
    fun `a conflicting reading resolves to free`() {
        assertEquals("free", GenerateSolrCatalogTask.mergeType("int", "bool"))
        assertEquals("free", GenerateSolrCatalogTask.mergeType("int", "free"))
        assertEquals("free", GenerateSolrCatalogTask.mergeType("free", "int"))
    }

    /**
     * The count that decides how many pending literals a call could have consumed. The motivating
     * shape is `get(args, "language", "English")` — name plus default, two string-like slots —
     * where counting only one took the default for the attribute name.
     */
    @Test
    fun `string parameters are counted, name and default alike`() {
        assertEquals(1, GenerateSolrCatalogTask.stringLikeParameters("(Ljava/util/Map;Ljava/lang/String;)Ljava/lang/String;"))
        assertEquals(
            2,
            GenerateSolrCatalogTask.stringLikeParameters(
                "(Ljava/util/Map;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
    }

    /** A primitive default — `getInt(args, "minGramSize", 1)` — holds no literal and is not counted. */
    @Test
    fun `primitives and non-string objects are not counted`() {
        assertEquals(1, GenerateSolrCatalogTask.stringLikeParameters("(Ljava/util/Map;Ljava/lang/String;I)I"))
        assertEquals(0, GenerateSolrCatalogTask.stringLikeParameters("(IZF)V"))
        assertEquals(0, GenerateSolrCatalogTask.stringLikeParameters("()V"))
    }

    /** `Object` counts because the erased `Map.get` and `Map.remove` take the name as `Object`. */
    @Test
    fun `an erased object parameter is string-like`() {
        assertEquals(1, GenerateSolrCatalogTask.stringLikeParameters("(Ljava/lang/Object;)Ljava/lang/Object;"))
    }

    /** The array prefix is skipped and the element type judged on its own. */
    @Test
    fun `an array of strings is string-like`() {
        assertEquals(1, GenerateSolrCatalogTask.stringLikeParameters("([Ljava/lang/String;)V"))
        assertEquals(0, GenerateSolrCatalogTask.stringLikeParameters("([I)V"))
    }

    /** A malformed descriptor stops the count rather than throwing mid-extraction. */
    @Test
    fun `an unterminated reference type ends the count`() {
        assertEquals(0, GenerateSolrCatalogTask.stringLikeParameters("(Ljava/lang/String"))
    }

    /**
     * A third parameter after the (Map, String) prefix is the default slot. `get(args, name, def)`
     * and `getInt(args, name, 1)` each have one; the default text is then read from the operand
     * pushed into it.
     */
    @Test
    fun `a third parameter is a default slot`() {
        assertTrue(GenerateSolrCatalogTask.hasDefaultParameter("(Ljava/util/Map;Ljava/lang/String;I)I"))
        assertTrue(
            GenerateSolrCatalogTask.hasDefaultParameter(
                "(Ljava/util/Map;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
    }

    /**
     * A reader that takes only the map and a name has no default: a plain `get(args, name)`, and
     * every `require*` reader — which is what keeps a required attribute from also carrying one.
     */
    @Test
    fun `a two-parameter reader has no default slot`() {
        assertFalse(
            GenerateSolrCatalogTask.hasDefaultParameter("(Ljava/util/Map;Ljava/lang/String;)Ljava/lang/String;"),
        )
        assertFalse(GenerateSolrCatalogTask.hasDefaultParameter("(Ljava/util/Map;Ljava/lang/String;)I"))
    }

    // --- the documentation column: reading a class's own Javadoc from a -sources jar --------------

    /** The ordinary shape: a class comment directly above `public class X`. */
    @Test
    fun `a class comment is found above its declaration`() {
        val source = """
            package org.example;

            /**
             * Creates new instances of EdgeNGramTokenFilter.
             */
            public class EdgeNGramFilterFactory extends TokenFilterFactory {
            }
        """.trimIndent()
        val comment = GenerateSolrCatalogTask.classJavadocComment(source, "EdgeNGramFilterFactory")
        assertTrue(comment != null && comment.contains("Creates new instances"))
    }

    /** An annotation between the comment and the declaration does not hide it. */
    @Test
    fun `an annotated class still finds its comment`() {
        val source = """
            /**
             * Filters LowerCase.
             */
            @Deprecated
            public final class LowerCaseFilterFactory extends TokenFilterFactory {
            }
        """.trimIndent()
        val comment = GenerateSolrCatalogTask.classJavadocComment(source, "LowerCaseFilterFactory")
        assertTrue(comment != null && comment.contains("Filters LowerCase"))
    }

    /**
     * A commented type ahead of the target keeps its own comment. A lazy `.*?` still backtracks
     * across an intervening comment terminator when that is what lets the rest of the pattern
     * match, so this file shape used to hand the helper's comment to the factory.
     */
    @Test
    fun `an earlier commented class does not lend its comment to a later one`() {
        val source = """
            package org.example;

            /**
             * A helper nobody asked about.
             */
            class Helper {
            }

            /**
             * Creates new instances of NGramTokenFilter.
             */
            public class NGramFilterFactory extends TokenFilterFactory {
            }
        """.trimIndent()
        val comment = GenerateSolrCatalogTask.classJavadocComment(source, "NGramFilterFactory")
        assertTrue("read the wrong class's comment: $comment", comment != null && comment.contains("NGramTokenFilter"))
        assertTrue("leaked the earlier comment: $comment", comment != null && !comment.contains("helper"))
    }

    /** A class with no comment above it is absent, not an empty string. */
    @Test
    fun `a class with no comment reads as absent`() {
        val source = "public class NoComment extends TokenFilterFactory {\n}"
        assertEquals(null, GenerateSolrCatalogTask.classJavadocComment(source, "NoComment"))
    }

    /**
     * A one-line comment with no sentence-ending period reads in full, rather than being
     * discarded for lacking one. This is the common shape for a factory's class comment.
     */
    @Test
    fun `a comment with no period is kept whole`() {
        val summary = GenerateSolrCatalogTask.summarizeJavadocComment(
            " * Creates new instances of EdgeNGramTokenFilter\n",
        )
        assertEquals("Creates new instances of EdgeNGramTokenFilter", summary)
    }

    /** Only the first sentence survives when there is more than one. */
    @Test
    fun `only the first sentence is kept`() {
        val summary = GenerateSolrCatalogTask.summarizeJavadocComment(
            " * Splits words. See the example below for details.\n",
        )
        assertEquals("Splits words.", summary)
    }

    /** A block tag ends the summary; nothing after `@since` or `@param` belongs in one sentence. */
    @Test
    fun `a block tag ends the summary`() {
        val summary = GenerateSolrCatalogTask.summarizeJavadocComment(
            " * Splits words on case change\n * @since 4.0\n * @lucene.spi {@value #NAME}\n",
        )
        assertEquals("Splits words on case change", summary)
    }

    /** `{@link}` resolves to a plain label, with or without an explicit one. */
    @Test
    fun `a link tag reads as its label`() {
        assertEquals(
            "Creates new instances of EdgeNGramTokenFilter.",
            GenerateSolrCatalogTask.summarizeJavadocComment(
                " * Creates new instances of {@link org.apache.lucene.analysis.ngram.EdgeNGramTokenFilter}.\n",
            ),
        )
        assertEquals(
            "See the ngram filter.",
            GenerateSolrCatalogTask.summarizeJavadocComment(
                " * See the {@link org.apache.lucene.analysis.ngram.EdgeNGramTokenFilter ngram filter}.\n",
            ),
        )
    }

    /** `{@code}` reads as its plain content, and an HTML tag is stripped rather than shown raw. */
    @Test
    fun `code tags and HTML markup are reduced to plain text`() {
        assertEquals(
            "Reads the minGramSize argument.",
            GenerateSolrCatalogTask.summarizeJavadocComment(" * Reads the {@code minGramSize} argument.\n"),
        )
        assertEquals(
            "A filter factory for this.",
            GenerateSolrCatalogTask.summarizeJavadocComment(" * A <b>filter factory</b> for this.\n"),
        )
    }

    /** An empty comment, or one that is only whitespace and stars, summarizes to nothing. */
    @Test
    fun `a blank comment summarizes to nothing`() {
        assertEquals(null, GenerateSolrCatalogTask.summarizeJavadocComment(" *\n * \n"))
    }

    // --- the plugin roots Solr declares -----------------------------------------------------------

    @Test
    fun `each class constant pairs with the tag that follows it`() {
        val roots = SolrConfigPlugins.pair(
            listOf(
                Type.getObjectType("org/apache/solr/request/SolrRequestHandler"), "requestHandler",
                Type.getObjectType("org/apache/solr/search/QParserPlugin"), "queryParser",
            ),
        )
        assertEquals("org/apache/solr/request/SolrRequestHandler", roots["requestHandler"])
        assertEquals("org/apache/solr/search/QParserPlugin", roots["queryParser"])
    }

    /**
     * Three tags are paths rather than bare names, and one of those means "at any depth". The catalog is
     * keyed by the element as a reader writes it, so only the last segment belongs here — the nesting is
     * a fact about element structure and not about a table of classes.
     */
    @Test
    fun `a tag declared as a path keeps only the element name`() {
        val roots = SolrConfigPlugins.pair(
            listOf(
                Type.getObjectType("org/apache/solr/core/SolrEventListener"), "//listener",
                Type.getObjectType("org/apache/solr/update/UpdateLog"), "updateHandler/updateLog",
                Type.getObjectType("org/apache/solr/core/IndexDeletionPolicy"), "indexConfig/deletionPolicy",
            ),
        )
        assertEquals(setOf("listener", "updateLog", "deletionPolicy"), roots.keys)
    }

    /**
     * A tag with no class before it is dropped rather than attached to whichever class came last.
     *
     * Solr writes the constructor class-first, so this cannot happen today. The point is what happens
     * when it changes: pairing a tag with a distant class would mis-attribute a root silently, and a
     * missing tag is the failure that shows up as an absent kind and a failing assertion.
     */
    @Test
    fun `a tag with no class before it is dropped rather than guessed`() {
        val roots = SolrConfigPlugins.pair(
            listOf(
                "strandedTag",
                Type.getObjectType("org/apache/solr/request/SolrRequestHandler"), "requestHandler",
            ),
        )
        assertEquals(mapOf("requestHandler" to "org/apache/solr/request/SolrRequestHandler"), roots)
    }

    /** One class is used once: a second tag does not silently reuse the previous root. */
    @Test
    fun `a class is consumed by the tag it pairs with`() {
        val roots = SolrConfigPlugins.pair(
            listOf(Type.getObjectType("org/apache/solr/request/SolrRequestHandler"), "requestHandler", "orphan"),
        )
        assertEquals(setOf("requestHandler"), roots.keys)
    }
}
