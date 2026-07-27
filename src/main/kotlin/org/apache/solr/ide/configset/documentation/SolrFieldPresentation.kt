package org.apache.solr.ide.configset.documentation

import org.apache.solr.ide.model.SolrEffectiveProperty
import org.apache.solr.ide.model.SolrField
import org.apache.solr.ide.model.SolrFieldProperties
import org.apache.solr.ide.model.SolrFieldType
import org.apache.solr.ide.model.SolrMatchAnalysis
import org.apache.solr.ide.model.SolrMatchCapability
import org.apache.solr.ide.model.SolrMatchGranularity
import org.apache.solr.ide.model.SolrMatchTrait
import org.apache.solr.ide.model.SolrPrefixSupport
import org.apache.solr.ide.model.SolrPropertyOrigin
import org.apache.solr.ide.model.SolrReferenceGuide
import org.apache.solr.ide.model.SolrVersionSelection

/**
 * Turns model facts into the text the editor shows.
 *
 * Shared by the inlay hints and the documentation popup on purpose: the two must never disagree
 * about what a field matches, and the surest way to guarantee that is for both to render the same
 * computed value rather than each phrasing it themselves.
 */
object SolrFieldPresentation {

    /**
     * The documentation popup for a field: what it is, what it matches, and every property's
     * effective value with where that value came from.
     *
     * The third of those is the part no external documentation can supply. The Reference Guide can
     * say what `omitNorms` defaults to; only this can say what it is for *this* field, and whether
     * that came from the field, from its type, or from Solr.
     *
     * @param field the field to document
     * @param fieldType its type, or null when undeclared
     * @param version the Solr line this configset targets, for the guide link
     * @return HTML for the documentation popup
     */
    fun fieldDocumentation(field: SolrField, fieldType: SolrFieldType?, version: SolrVersionSelection): String =
        buildString {
            append("<div class='definition'><pre>")
            append("field <b>${escape(field.name)}</b>")
            if (field.type.isNotEmpty()) append(" of type ${escape(field.type)}")
            append("</pre></div>")
            append("<div class='content'>")

            if (fieldType == null) {
                append("<p>Type <code>${escape(field.type)}</code> is not declared in this configset.</p>")
            } else {
                val capability = SolrMatchAnalysis.of(fieldType)
                if (capability.confident) {
                    append("<p><b>Matches:</b> ${escape(capability.summary)}.")
                    append(prefixMechanism(capability))
                    append("</p>")
                    append(WILDCARD_CAVEAT)
                } else {
                    append("<p><b>Matches:</b> not determined — this chain contains a factory the plugin does not recognise.</p>")
                }
            }

            append(propertyTable(field, fieldType))
            append("</div>")
            append(guideLinks(version))
        }

    /**
     * The documentation popup for a field type: its class, its chains, and what it makes fields match.
     *
     * @param fieldType the type to document
     * @param version the Solr line this configset targets, for the guide link
     * @return HTML for the documentation popup
     */
    fun fieldTypeDocumentation(fieldType: SolrFieldType, version: SolrVersionSelection): String = buildString {
        append("<div class='definition'><pre>")
        append("fieldType <b>${escape(fieldType.name)}</b>")
        if (fieldType.className.isNotEmpty()) append("\nclass ${escape(fieldType.className)}")
        append("</pre></div>")
        append("<div class='content'>")

        val capability = SolrMatchAnalysis.of(fieldType)
        if (capability.confident) {
            append("<p><b>Fields of this type match:</b> ${escape(capability.summary)}.</p>")
            append(WILDCARD_CAVEAT)
        } else {
            append("<p><b>Match behaviour not determined</b> — this chain contains a factory the plugin does not recognise.</p>")
        }

        fieldType.indexAnalyzer?.let { append(chainHtml("Index analyser", it.components.map { c -> c.className }, version)) }
        fieldType.queryAnalyzer?.let { append(chainHtml("Query analyser", it.components.map { c -> c.className }, version)) }

        if (fieldType.attributes.isNotEmpty()) {
            append("<p><b>Declared on this type</b></p><table>")
            for ((name, value) in fieldType.attributes) {
                append("<tr><td><code>${escape(name)}</code></td><td>${escape(value)}</td></tr>")
            }
            append("</table>")
        }
        append("</div>")
        append(guideLinks(version))
    }

    /**
     * The sentence naming where partial matching comes from, or empty when there is none.
     *
     * Reads the mechanism off the capability rather than matching on class names. An earlier
     * version looked for "NGram" in the evidence, which silently said nothing for a
     * path-hierarchy tokenizer — a mechanism the model knows about exactly.
     */
    private fun prefixMechanism(capability: SolrMatchCapability): String {
        if (capability.prefix == SolrPrefixSupport.NONE) return ""
        val factory = capability.evidenceFor(SolrMatchTrait.PREFIX) ?: return ""
        return " Partial matching comes from <code>${escape(factory)}</code>."
    }

    /**
     * A chain rendered in pipeline order, each component linking to the guide page for its kind.
     *
     * Per-factory documentation waits on the generated catalog, but the page-level link costs
     * nothing and is the difference between a name and something a reader can follow.
     */
    private fun chainHtml(label: String, components: List<String>, version: SolrVersionSelection): String =
        if (components.isEmpty()) {
            ""
        } else {
            "<p><b>$label:</b> " + components.joinToString(" &rarr; ") { className ->
                val simpleName = escape(className.substringAfterLast('.'))
                SolrReferenceGuide.analyzerComponentPage(className, version)
                    ?.let { "<a href='$it'><code>$simpleName</code></a>" }
                    ?: "<code>$simpleName</code>"
            } + "</p>"
        }

    /**
     * The property table, showing each property's effective value and where it came from.
     *
     * Properties whose default depends on the field type are shown as such rather than given a
     * plausible value — `omitNorms` differs between primitive and text types, and asserting one
     * answer where Solr has two is how a plugin loses a user's trust.
     */
    private fun propertyTable(field: SolrField, fieldType: SolrFieldType?): String = buildString {
        append("<p><b>Properties</b></p><table>")
        append("<tr><th>Property</th><th>Value</th><th>From</th><th>Accepts</th><th>Meaning</th></tr>")
        // Declared values first: what the author actually wrote is what they came to check, and it
        // was previously indistinguishable from a default except by reading the middle column.
        val ordered = SolrFieldProperties.effectiveFor(field, fieldType)
            .sortedBy { if (it.origin == SolrPropertyOrigin.FIELD) 0 else 1 }
        for (effective in ordered) {
            val declared = effective.origin == SolrPropertyOrigin.FIELD
            append("<tr><td><code>${escape(effective.property.name)}</code></td>")
            append(if (declared) "<td><b>${escape(valueText(effective))}</b></td>" else "<td>${escape(valueText(effective))}</td>")
            append("<td><i>${escape(originText(effective.origin))}</i></td>")
            append("<td>${escape(effective.property.validValues)}</td>")
            append("<td>${escape(effective.property.summary)}</td></tr>")
        }
        append("</table>")
    }

    private fun valueText(effective: SolrEffectiveProperty): String =
        effective.value ?: "depends on the field type"

    private fun originText(origin: SolrPropertyOrigin): String = when (origin) {
        SolrPropertyOrigin.FIELD -> "on this field"
        SolrPropertyOrigin.FIELD_TYPE -> "from the field type"
        SolrPropertyOrigin.SOLR_DEFAULT -> "Solr default"
        SolrPropertyOrigin.UNDETERMINED -> "see the guide"
    }

    private fun guideLinks(version: SolrVersionSelection): String = buildString {
        append("<div class='bottom'>")
        append("<p><a href='${SolrReferenceGuide.fieldTypesPage(version)}'>Field types included with Solr</a>")
        append(" &middot; ")
        append("<a href='${SolrReferenceGuide.fieldPropertiesPage(version)}'>Field type definitions and properties</a></p>")
        append("<p><small>Reference Guide for ${escape(version.describeSource())}.</small></p>")
        append("</div>")
    }

    /**
     * The caveat that keeps the match claim honest.
     *
     * A wildcard query works against any indexed field, so a reader who knows that will assume the
     * hint is simply wrong unless it says what it means. Saying it first is the difference between
     * a claim that survives scrutiny and one that does not.
     */
    private const val WILDCARD_CAVEAT =
        "<p><small>Wildcard queries such as <code>wid*</code> work on any indexed field and are " +
            "expanded across the dictionary at query time. This describes what the index supports " +
            "<i>efficiently</i>.</small></p>"

    /**
     * Escapes text for HTML, quotes included.
     *
     * Quotes matter even though no configset text currently reaches an attribute: links are built
     * with single-quoted `href`, so a partial escape is one careless edit away from an injection
     * point in a popup rendering a file the user does not necessarily trust.
     */
    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
