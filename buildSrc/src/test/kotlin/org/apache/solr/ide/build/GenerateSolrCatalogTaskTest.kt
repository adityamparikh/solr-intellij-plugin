package org.apache.solr.ide.build

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
