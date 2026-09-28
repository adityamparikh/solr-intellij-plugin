package org.apache.solr.ide.code.highlighting

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import org.apache.solr.ide.SolrBundle
import javax.swing.Icon

/**
 * *Settings → Editor → Color Scheme → Solr Query*: where a reader tunes the colours of a query
 * written in code.
 *
 * **Offered because the defaults are borrowed, and a borrowed colour can collide.** [SolrQueryColors]
 * falls back to the platform's field and keyword colours, which is what makes a theme this plugin has
 * never seen work at all; but a theme is free to colour a keyword the way it colours a string, and
 * the only remedy for that is a place to say otherwise.
 *
 * The preview is a SolrJ call rather than a bare query, because the thing being tuned is how a query
 * reads *inside a string*: a colour chosen against a plain background can vanish against the string's
 * own. The string's colour is shown around the query for exactly that reason, and is the platform's
 * own key rather than one this page owns.
 */
class SolrQueryColorSettingsPage : ColorSettingsPage {

    /** No icon of its own; the page is found by its name. */
    override fun getIcon(): Icon? = null

    /** Plain text: every colour in the preview comes from its tags. */
    override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()

    /**
     * A query as the demo project writes one, tagged where each colour applies.
     *
     * The quotes and values are tagged as a string, piece by piece, so the preview shows the query's
     * colours against the literal they have to stand out from.
     */
    override fun getDemoText(): String =
        """
        q.setQuery(<string>"</string><field>category</field><string>:books </string><operator>AND</operator><string> </string><field>price</field><string>:[1 </string><operator>TO</operator><string> 20]"</string>);
        q.addFilterQuery(<string>"</string><field>inStock</field><string>:true </string><operator>OR</operator><string> </string><operator>NOT</operator><string> </string><field>name</field><string>:dune"</string>);
        """.trimIndent()

    /**
     * The tags [getDemoText] uses, and the key each one previews.
     *
     * @return the tag-to-key map the preview is painted from
     */
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "field" to SolrQueryColors.FIELD,
        "operator" to SolrQueryColors.OPERATOR,
        "string" to DefaultLanguageHighlighterColors.STRING,
    )

    /**
     * The two colours a reader can set: a field, and an operator.
     *
     * @return one descriptor per key this plugin owns
     */
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor(SolrBundle.message("colors.query.field"), SolrQueryColors.FIELD),
        AttributesDescriptor(SolrBundle.message("colors.query.operator"), SolrQueryColors.OPERATOR),
    )

    /** None: a query in code changes the colour of text, never of the editor around it. */
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    /** The page's name in the Color Scheme tree. */
    override fun getDisplayName(): String = SolrBundle.message("colors.query.displayName")
}
