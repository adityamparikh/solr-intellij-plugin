package org.apache.solr.ide.configset.documentation

import org.apache.solr.ide.model.SolrClassEntry
import org.apache.solr.ide.model.SolrClassKind
import org.apache.solr.ide.model.SolrEffectiveProperty
import org.apache.solr.ide.model.SolrField
import org.apache.solr.ide.model.SolrFieldProperties
import org.apache.solr.ide.model.SolrFieldProperty
import org.apache.solr.ide.model.SolrFieldType
import org.apache.solr.ide.model.SolrMatchAnalysis
import org.apache.solr.ide.model.SolrMatchCapability
import org.apache.solr.ide.model.SolrMatchGranularity
import org.apache.solr.ide.model.SolrMatchTrait
import org.apache.solr.ide.model.SolrPrefixSupport
import org.apache.solr.ide.model.SolrPropertyOrigin
import org.apache.solr.ide.model.SolrReferenceGuide
import org.apache.solr.ide.model.SolrSchemaVersion
import org.apache.solr.ide.model.SolrTypeTrait
import org.apache.solr.ide.model.SolrValueType
import org.apache.solr.ide.model.SolrVersionSelection

/**
 * Turns model facts into the HTML the documentation popup shows.
 *
 * The inlay hints provider does not call this — it reads `SolrFieldProperties` and
 * `SolrMatchCapability` directly to build its own short phrases — but both surfaces render the same
 * underlying model, `SolrFieldProperties.meaning` and `SolrMatchAnalysis.of` chief among it. That
 * shared table, not this class, is what keeps the popup and the hint from disagreeing about what a
 * field matches or what a property's value means; this class only turns the popup's half of that
 * table into markup.
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
     * @param schemaVersion the schema's own declared version, which decides several defaults
     * @param typeTraits the field type class's traits from the catalog, or null when the catalog
     *   does not know the class — which is not the same as the class carrying no traits
     * @return HTML for the documentation popup
     */
    fun fieldDocumentation(
        field: SolrField,
        fieldType: SolrFieldType?,
        version: SolrVersionSelection,
        schemaVersion: SolrSchemaVersion,
        typeTraits: Set<SolrTypeTrait>? = null,
    ): String =
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

            append(propertyTable(field, fieldType, schemaVersion, typeTraits))
            append("</div>")
            append(guideLinks(version))
        }

    /**
     * The documentation popup for a schema element.
     *
     * @param description what the element is, in general
     * @param specifics what this particular one does, or null when nothing specific can be said
     * @param version the Solr line this configset targets, for the guide link
     * @param field the field this element declares, when it declares one
     * @param fieldType that field's type, or null when undeclared
     * @param schemaVersion the schema's own declared version, which decides several defaults
     * @param typeTraits the field type class's traits from the catalog, or null when unknown
     * @return HTML for the popup
     */
    internal fun elementDocumentation(
        description: SolrSchemaElements.Description,
        specifics: String?,
        version: SolrVersionSelection,
        field: SolrField? = null,
        fieldType: SolrFieldType? = null,
        schemaVersion: SolrSchemaVersion,
        typeTraits: Set<SolrTypeTrait>? = null,
    ): String = buildString {
        append("<div class='definition'><pre>&lt;${escape(description.tagName)}&gt;</pre></div>")
        append("<div class='content'>")
        append("<p>${description.summary}</p>")
        // Not escaped: the specifics are built by this plugin from model values, and carry markup
        // of their own. XML attribute values cannot contain a raw `<`, so no markup arrives here.
        specifics?.let { append("<p><b>In this configset:</b> $it</p>") }
        // The resolved configuration, on the element rather than only on its name. Hovering the tag
        // is the gesture a reader makes; requiring the caret to be inside the `name` quotes hid the
        // one answer no other tool can give behind the one gesture nobody guesses.
        field?.let { append(propertyTable(it, fieldType, schemaVersion, typeTraits)) }
        append("</div>")
        append(guideLinks(version))
    }

    /**
     * The popup for one property attribute — what it means, what it accepts, and what it is *here*.
     *
     * Hovering `omitNorms="false"` is the obvious way to ask what the property does and what Solr
     * would have used instead. Before this it answered with the enclosing element's description,
     * which is a reasonable answer to a question nobody asked.
     *
     * [effective] is null on a `fieldType`, where "the value for this field" has no meaning. The
     * general half — summary, accepted values, Solr's default — is the same either way.
     *
     * @param property the property being hovered
     * @param effective its resolved value for the enclosing field, or null on a field type
     * @param version the Solr line this configset targets, for the guide link
     * @param schemaVersion the schema's own declared version, which decides several defaults
     * @param typeClassName the enclosing field's type class, named when a default turns on it
     * @return HTML for the documentation popup
     */
    fun propertyDocumentation(
        property: SolrFieldProperty,
        effective: SolrEffectiveProperty?,
        version: SolrVersionSelection,
        schemaVersion: SolrSchemaVersion,
        typeClassName: String? = null,
    ): String = buildString {
        append("<div class='definition'><pre>${escape(property.name)}</pre></div>")
        append("<div class='content'>")
        append("<p>${escape(property.summary)}</p>")
        append("<table>")
        append("<tr><td>Accepts</td><td>${escape(property.validValues)}</td></tr>")
        append("<tr><td>Solr default</td><td>${escape(generalDefaultText(property))}</td></tr>")
        effective?.let {
            append(
                "<tr><td>Here</td><td><b>${escape(valueText(it))}</b> — " +
                    "${escape(originText(it.origin, schemaVersion, typeClassName))}</td></tr>",
            )
            val meaning = meaningText(it)
            if (meaning != it.property.summary) {
                append("<tr><td></td><td>${escape(meaning)}</td></tr>")
            }
        }
        append("</table>")
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
     * The documentation popup for a class named in a `class` attribute.
     *
     * What it renders is what the generated catalog and the model can vouch for: a one-sentence
     * summary of the class's own Javadoc where the line's `-sources` artifacts carried one, the
     * kind of class, both of its spellings, the attributes its constructor actually reads, and
     * what this schema declared with it. **The summary is not a substitute for the Reference
     * Guide.** A factory's class comment is typically one sentence — "Creates new instances of
     * `EdgeNGramTokenFilter`" — while the guide page carries per-argument descriptions, defaults
     * in words and worked examples; the summary is the honest, mechanically sourced fraction of
     * that available without hand-copying the guide, which [SolrReferenceGuide] already argues
     * against.
     *
     * @param entry the catalog entry for the class
     * @param specifics what this schema does with it, or null when nothing specific is known
     * @param version the Solr line this configset targets, for the guide link
     * @return HTML for the documentation popup
     */
    fun classDocumentation(
        entry: SolrClassEntry,
        specifics: String?,
        version: SolrVersionSelection,
    ): String = buildString {
        append("<div class='definition'><pre>")
        append("<b>${escape(entry.shortName)}</b> — ${kindText(entry.kind)}")
        append("\n${escape(entry.className)}")
        append("</pre></div>")
        append("<div class='content'>")
        // Not escaped: a Javadoc summary is prose the generator extracted from Solr's own sources,
        // not markup a configset could inject — the same reasoning that leaves the specifics
        // paragraph below unescaped, applied to a different source of trusted text.
        entry.summary?.let { append("<p>${it}</p>") }
        if (entry.attributes.isNotEmpty()) {
            append("<p><b>Accepts</b></p><table>")
            for (attribute in entry.attributes) {
                append("<tr><td><code>${escape(attribute.name)}</code></td>")
                append("<td>${valueTypeText(attribute.valueType)}</td></tr>")
            }
            append("</table>")
        }
        // Not escaped: the specifics are built by this plugin from model values and carry markup
        // of their own, the same contract elementDocumentation documents.
        specifics?.let { append("<p><b>In this configset:</b> $it</p>") }
        append("</div>")
        append(classGuideLink(entry, version))
    }

    /** The kind in words, for the definition line. */
    private fun kindText(kind: SolrClassKind): String = when (kind) {
        SolrClassKind.FIELD_TYPE -> "field type class"
        SolrClassKind.TOKENIZER -> "tokenizer factory"
        SolrClassKind.TOKEN_FILTER -> "token filter factory"
        SolrClassKind.CHAR_FILTER -> "character filter factory"
    }

    /**
     * The value type in words, or empty for a free-form attribute.
     *
     * Empty rather than "any value": the catalog's FREE means the generator could not narrow the
     * type, which is weaker than a promise that anything is legal.
     */
    private fun valueTypeText(type: SolrValueType): String = when (type) {
        SolrValueType.BOOLEAN -> "true or false"
        SolrValueType.INTEGER -> "a whole number"
        SolrValueType.FLOAT -> "a decimal number"
        SolrValueType.ENUM -> "one of a closed set"
        SolrValueType.FREE -> ""
    }

    /**
     * The guide footer for a class popup — one page, chosen by what the class is.
     *
     * The shared [guideLinks] footer names the two field pages, which are the wrong destination
     * for a tokenizer; a link that lands somewhere unrelated teaches a reader to stop clicking.
     */
    private fun classGuideLink(entry: SolrClassEntry, version: SolrVersionSelection): String {
        val url = SolrReferenceGuide.classPage(entry.kind, entry.className, version) ?: return ""
        val label = when (entry.kind) {
            SolrClassKind.FIELD_TYPE -> "Field types included with Solr"
            SolrClassKind.TOKENIZER -> "Tokenizers in the Reference Guide"
            SolrClassKind.TOKEN_FILTER -> "Filters in the Reference Guide"
            SolrClassKind.CHAR_FILTER -> "Char filter factories in the Reference Guide"
        }
        return "<div class='bottom'><p><a href='$url'>$label</a></p>" +
            "<p><small>Reference Guide for ${escape(version.describeSource())}.</small></p></div>"
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
    private fun propertyTable(
        field: SolrField,
        fieldType: SolrFieldType?,
        schemaVersion: SolrSchemaVersion,
        typeTraits: Set<SolrTypeTrait>? = null,
    ): String = buildString {
        append("<p><b>Properties</b></p><table>")
        append("<tr><th>Property</th><th>Value</th><th>From</th><th>Accepts</th><th>Meaning</th></tr>")
        // Declared values first: what the author actually wrote is what they came to check, and it
        // was previously indistinguishable from a default except by reading the middle column.
        val ordered = SolrFieldProperties.effectiveFor(field, fieldType, schemaVersion, typeTraits)
            .sortedBy { if (it.origin == SolrPropertyOrigin.FIELD) 0 else 1 }
        for (effective in ordered) {
            val declared = effective.origin == SolrPropertyOrigin.FIELD
            append("<tr><td><code>${escape(effective.property.name)}</code></td>")
            append(if (declared) "<td><b>${escape(valueText(effective))}</b></td>" else "<td>${escape(valueText(effective))}</td>")
            append("<td><i>${escape(originText(effective.origin, schemaVersion, fieldType?.className))}</i></td>")
            append("<td>${escape(effective.property.validValues)}</td>")
            append("<td>${escape(meaningText(effective))}</td></tr>")
        }
        append("</table>")
    }

    private fun valueText(effective: SolrEffectiveProperty): String =
        effective.value ?: DEPENDS_ON_TYPE

    /**
     * The consequence of a property's resolved value, or its value-neutral summary where there is none.
     *
     * Falls back in the two cases where no consequence can be stated: the value is undetermined, so
     * choosing one of the two sentences would assert what the null value exists to avoid; or the
     * property takes something other than a boolean and has no two consequences to choose between.
     */
    private fun meaningText(effective: SolrEffectiveProperty): String {
        val meaning = effective.property.meaning ?: return effective.property.summary
        return when (effective.value) {
            "true" -> meaning.whenTrue
            "false" -> meaning.whenFalse
            else -> effective.property.summary
        }
    }

    /**
     * What is reported where Solr's own default is decided by the field type's class.
     *
     * `omitNorms` is true for primitive types and false for text; `docValues` is documented as
     * "true for most fields". Naming one is a confident wrong answer, and this is the output most
     * likely to be quoted back at someone.
     */
    private const val DEPENDS_ON_TYPE = "depends on the field type"

    /**
     * Solr's default for a property in general, before any particular schema is considered.
     *
     * A version-dependent default is stated with the version that decides it rather than collapsed
     * into "depends on the field type", which would be the wrong reason: `uninvertible` does not
     * depend on the type at all, it depends on one attribute on the root element.
     */
    private fun generalDefaultText(property: SolrFieldProperty): String {
        property.defaultValue?.let { return it }
        val range = property.defaultTrueWithin ?: return DEPENDS_ON_TYPE
        return when {
            range.from != null && range.below != null ->
                "true from schema version ${range.from} up to ${range.below}, otherwise false"
            range.from != null -> "true from schema version ${range.from}, false below it"
            range.below != null -> "true below schema version ${range.below}, false from it"
            else -> DEPENDS_ON_TYPE
        }
    }

    /**
     * Where a value came from, named so the reader knows what they would have to change.
     *
     * A version-dependent default names the version, because that is the actionable half: a reader
     * seeing `uninvertible` true on a 1.6 schema is looking at a value they move by editing the
     * root element, not one that is true of Solr as such. A type-dependent one names the class for
     * the same reason — `omitNorms` being true is a fact about `solr.StrField`, and a reader who
     * wants it false changes the type or writes the attribute.
     */
    private fun originText(
        origin: SolrPropertyOrigin,
        schemaVersion: SolrSchemaVersion,
        typeClassName: String?,
    ): String = when (origin) {
        SolrPropertyOrigin.FIELD -> "on this field"
        SolrPropertyOrigin.FIELD_TYPE -> "from the field type"
        SolrPropertyOrigin.SOLR_DEFAULT -> "Solr default"
        SolrPropertyOrigin.SCHEMA_VERSION_DEFAULT -> "Solr default at schema version ${schemaVersion.label}"
        SolrPropertyOrigin.FIELD_TYPE_DEFAULT ->
            typeClassName?.let { "Solr default for $it" } ?: "Solr default for this field type"
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
