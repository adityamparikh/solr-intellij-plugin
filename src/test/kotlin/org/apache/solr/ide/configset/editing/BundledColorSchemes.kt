package org.apache.solr.ide.configset.editing

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.JDOMUtil

/**
 * The editor colour schemes a user actually meets, for tests that assert how a finding is *drawn*.
 *
 * **Severity is not presentation, and only a scheme turns one into the other.** A finding reaches
 * the editor as a highlight type; the scheme decides whether that type is an underline, a
 * background, or grey text. The light schemes draw a warning as a pale background with no line at
 * all — which is how a sandbox pass on the Islands Light theme came to report the unknown-type
 * check as not underlined, while every fixture asserting `<warning>` stayed green.
 *
 * A light test application loads only `Default` and `Darcula`. `Light` — the editor scheme behind
 * the *Islands Light* theme the sandbox runs on — is read here from the resource the
 * IDE itself registers, over `Default` as its declared parent.
 */
internal object BundledColorSchemes {

    /** Every scheme a presentation claim is held to, by name. */
    fun all(): Map<String, EditorColorsScheme> = linkedMapOf(
        "Default" to named("Default"),
        "Darcula" to named("Darcula"),
        "Light" to light(),
    )

    /** Whether [attributes] draw a visible line under the text: an effect of an underline kind, in a colour. */
    fun underlines(attributes: TextAttributes?): Boolean =
        attributes?.effectColor != null && attributes.effectType in UNDERLINES

    /** Whether [attributes] draw any effect at all — underline, box, strikeout — rather than colour alone. */
    fun drawsAnEffect(attributes: TextAttributes?): Boolean =
        attributes?.effectColor != null && attributes.effectType != null

    /** How [info] renders in [scheme], exactly as the editor asks for it. */
    fun attributesOf(info: HighlightInfo, scheme: EditorColorsScheme): TextAttributes? =
        info.getTextAttributes(null, scheme)

    private fun named(name: String): EditorColorsScheme =
        checkNotNull(EditorColorsManager.getInstance().getScheme(name)) { "no bundled scheme named $name" }

    private fun light(): EditorColorsScheme {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(LIGHT_SCHEME)) {
            "$LIGHT_SCHEME is not on the test classpath"
        }
        return EditorColorsSchemeImpl(named("Default")).apply { readExternal(stream.use(JDOMUtil::load)) }
    }

    private const val LIGHT_SCHEME = "themes/expUI/expUI_lightScheme.xml"

    private val UNDERLINES = setOf(
        EffectType.LINE_UNDERSCORE,
        EffectType.BOLD_LINE_UNDERSCORE,
        EffectType.BOLD_DOTTED_LINE,
        EffectType.WAVE_UNDERSCORE,
    )
}
