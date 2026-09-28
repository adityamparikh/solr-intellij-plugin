package org.apache.solr.ide.code.highlighting

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey

/**
 * The colours of a Solr query written inside a Java or Kotlin string.
 *
 * **Keys of this plugin's own, falling back to the platform's, and the fallback is what was wrong.**
 * The query is drawn *over* a string literal: an annotation's colour is layered on the lexer's, so a
 * key that sets no foreground repaints nothing and the text stays the string's colour. The operator
 * used to borrow the platform's operator-sign key, which the classic *Default* and *Darcula* schemes
 * leave unset, and which the New UI and *Islands Dark* schemes set to the plain-text grey. Either
 * way `AND` was annotated correctly and could not be seen — green in one theme, ordinary text in
 * the other — which is what the sandbox pass found.
 *
 * Each fallback is chosen for being set in every bundled scheme and differing from a string there, and
 * for meaning the same thing in a language: a field is a field, and `AND` is a keyword of the query
 * language. A theme this plugin has never seen still colours both, through the keys it already
 * defines; a reader who wants something else tunes them under *Settings → Editor → Color Scheme →
 * Solr Query*, which [SolrQueryColorSettingsPage] provides.
 */
object SolrQueryColors {

    /** A field name before the colon of a fielded clause — `category` in `category:books`. */
    val FIELD: TextAttributesKey =
        createTextAttributesKey("SOLR_QUERY_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD)

    /** A boolean or range operator Solr reads as one — `AND`, `OR`, `NOT`, `TO`, `&&`, `||`. */
    val OPERATOR: TextAttributesKey =
        createTextAttributesKey("SOLR_QUERY_OPERATOR", DefaultLanguageHighlighterColors.KEYWORD)
}
