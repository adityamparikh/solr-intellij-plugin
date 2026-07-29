package org.apache.solr.ide.model

/**
 * What a configset attribute accepts, as opposed to what it means.
 *
 * Two populations answer with this and they learn it two different ways. [SolrFieldProperties]
 * declares it by hand for the twenty-odd schema properties, which is the same deliberate exception
 * that table already makes. The generated catalog infers it from bytecode: the JVM return type of
 * the method a class used to read the attribute is the compiler's own answer about what the class
 * did with the string.
 *
 * **[FREE] is a positive statement, not an absence.** It says any value is legal here, which is what
 * a plain `get` or an erased `Map.get` genuinely means. Nothing typed [FREE] is ever value-checked,
 * so a type this model is unsure about must arrive as [FREE] rather than as a guess — a wrong type
 * produces a warning on a correct file, which is the one outcome the inspections may not produce.
 *
 * @property token the word the generated catalog writes after an attribute's name
 */
enum class SolrValueType(internal val token: String) {

    /** `true` or `false`, read by Solr with a boolean accessor. */
    BOOLEAN("bool"),

    /**
     * A whole number.
     *
     * Wider than it looks. `WordDelimiterGraphFilterFactory` reads `generateWordParts` and its
     * neighbours with `getInt` and treats them as `0`/`1` flags, so they are integers here even
     * though a reader would call them booleans — and `generateWordParts="true"` really does fail in
     * Solr. The type records what Solr does, not what the name suggests.
     */
    INTEGER("int"),

    /** A decimal number. */
    FLOAT("float"),

    /**
     * One of a closed set of names.
     *
     * Only [SolrFieldProperties] produces this. A closed member set is not recoverable from a return
     * descriptor, so a generated catalog never writes this token.
     */
    ENUM("enum"),

    /** Any value is legal. */
    FREE("free");

    /**
     * Whether [value] is one this type accepts.
     *
     * Answers true wherever the plugin cannot be certain, which is the direction the inspections
     * need: a value it declines to judge is a value it stays silent about.
     *
     * @param value the attribute value exactly as written in the configset
     * @param members the legal names when this is [ENUM]; ignored otherwise
     * @return false only when the value definitely cannot satisfy this type
     */
    fun accepts(value: String, members: List<String> = emptyList()): Boolean {
        // Solr's resource loader expands `${solr.gap:100}` before a field type ever sees it, and the
        // replacement may come from a system property set outside the repository. A file using one
        // is correct, so judging it is out of the question rather than merely hard.
        if (SUBSTITUTION in value) return true
        val trimmed = value.trim()
        return when (this) {
            BOOLEAN -> trimmed.equals("true", ignoreCase = true) || trimmed.equals("false", ignoreCase = true)
            INTEGER -> trimmed.toLongOrNull() != null
            FLOAT -> trimmed.toDoubleOrNull() != null
            // An enum with no declared members is a table entry that forgot them, and inventing a
            // verdict from an empty set would reject every value.
            ENUM -> members.isEmpty() || trimmed in members
            FREE -> true
        }
    }

    /** Token parsing, and the marker that makes a value unjudgeable. */
    companion object {

        /** What Solr substitutes before reading a configset, and this model therefore never judges. */
        private const val SUBSTITUTION = "\${"

        /**
         * The type the generated catalog's [token] names.
         *
         * @param token the word after an attribute's name, or empty
         * @return the matching type, or [FREE] when the token is absent or unrecognized
         */
        fun forToken(token: String): SolrValueType =
            entries.firstOrNull { it.token == token } ?: FREE
    }
}
