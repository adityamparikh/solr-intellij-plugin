package org.apache.solr.ide.configset.schema.documentation

import org.apache.solr.ide.model.vocabulary.SolrAttributeMeanings
import org.apache.solr.ide.model.vocabulary.SolrClassAttribute
import org.apache.solr.ide.model.vocabulary.SolrClassEntry
import org.apache.solr.ide.model.vocabulary.SolrClassKind
import org.apache.solr.ide.model.schema.SolrEffectiveProperty
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.schema.SolrFieldProperties
import org.apache.solr.ide.model.schema.SolrFieldProperty
import org.apache.solr.ide.model.schema.SolrFieldType
import org.apache.solr.ide.model.schema.SolrMatchAnalysis
import org.apache.solr.ide.model.schema.SolrMatchCapability
import org.apache.solr.ide.model.schema.SolrMatchGranularity
import org.apache.solr.ide.model.schema.SolrMatchTrait
import org.apache.solr.ide.model.schema.SolrPrefixSupport
import org.apache.solr.ide.model.schema.SolrPropertyOrigin
import org.apache.solr.ide.model.SolrReferenceGuide
import org.apache.solr.ide.model.schema.SolrSchemaVersion
import org.apache.solr.ide.model.schema.SolrTypeTrait
import org.apache.solr.ide.model.schema.SolrValueType
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

    /**
     * The documentation popup for a factory *tag* — every attribute the class accepts, each at its
     * effective value.
     *
     * This is the factory sibling of [propertyTable]. The class-value popup answers what the named
     * class is and which attributes it can read; this answers what *this* filter, tokenizer or
     * char filter resolves to once Solr fills in the defaults the author left unwritten. The two
     * questions share a catalog entry and almost nothing else, so they stay on different elements:
     * the `class` value keeps the identity popup, and the tag carries the configuration table. A
     * single popup that mixed both would bury the effective state behind a list of accepted types
     * and, worse, would answer a configuration question on a value that may appear twice with
     * different attributes.
     *
     * Written values are bold and labelled as on this tag; defaults are plain and labelled as
     * Solr's — the same visual language the field property table uses, so a reader who has seen one
     * half can read the other without learning a second vocabulary. Attributes the catalog marks
     * required but the tag omits, and optional attributes with no recorded default, still appear:
     * a complete-configuration picture that dropped them would look like the class accepted less
     * than it does. Their values are an em dash rather than an invented number, because the catalog
     * does not cite one.
     *
     * Callers must only reach this with a catalog entry. An unknown class is the caller's problem
     * to decline — rendering an empty configuration table for a custom plugin factory would claim
     * the class accepts nothing, which is the one lie this surface is organised never to tell.
     *
     * [tagName] is the element the file wrote, not the element the class belongs on, and the two
     * can disagree: `<filter class="solr.StandardTokenizerFactory"/>` resolves a tokenizer entry
     * while the caret is on a `filter`. Naming the tag from the file and the kind from the catalog
     * puts that disagreement on screen — `<filter> solr.StandardTokenizerFactory — tokenizer
     * factory` reads as the mistake it is — where deriving the tag from the entry's kind would
     * quietly rewrite the file into a valid one. Documenting a misplaced class as what it is, and
     * leaving the complaint to the inspections, is the same contract [classDocumentation] keeps.
     *
     * @param tagName the element name as this configset writes it
     * @param entry the catalog entry for the class named on the tag
     * @param writtenAttributes the attributes written on the tag, excluding `class`
     * @param version the Solr line this configset targets, for the guide link
     * @return HTML for the documentation popup
     */
    fun factoryDocumentation(
        tagName: String,
        entry: SolrClassEntry,
        writtenAttributes: Map<String, String>,
        version: SolrVersionSelection,
    ): String = buildString {
        append("<div class='definition'><pre>")
        append("&lt;${escape(tagName)}&gt; ")
        append("<b>${escape(entry.shortName)}</b> — ${kindText(entry.kind)}")
        append("\n${escape(entry.className)}")
        append("</pre></div>")
        append("<div class='content'>")
        entry.summary?.let { append("<p>${it}</p>") }
        if (entry.attributes.isNotEmpty()) {
            append(factoryConfigurationTable(tagName, entry, writtenAttributes))
        }
        append("</div>")
        append(classGuideLink(entry, version))
    }

    /**
     * The popup for one attribute a class reads — owner, value type, and default or required marker.
     *
     * **This is deliberately thinner than [propertyDocumentation].** Field properties have hand-written
     * summaries and a resolution chain; factory attributes have neither. Javadoc is written per
     * class, so there is no per-argument prose the catalog could carry, and inventing one from the
     * attribute name is exactly the false confidence the standing rule forbids. What remains is what
     * bytecode proved: the class that consumes the name, the JVM type of the reader that did, and a
     * literal default or a required marker where one was recovered. The guide link carries the rest.
     *
     * A [SolrValueType.FREE] attribute omits the "Accepts" row rather than promising "any value".
     * FREE means the generator could not narrow the type, which is weaker than a promise, and the
     * class-level Accepts table already uses the same empty rendering for the same reason.
     *
     * @param entry the catalog entry for the class that reads the attribute
     * @param attribute the attribute being hovered
     * @param version the Solr line this configset targets, for the guide link
     * @return HTML for the documentation popup
     */
    fun classAttributeDocumentation(
        entry: SolrClassEntry,
        attribute: SolrClassAttribute,
        version: SolrVersionSelection,
    ): String = buildString {
        append("<div class='definition'><pre>${escape(attribute.name)}</pre></div>")
        append("<div class='content'>")
        append("<table>")
        // Meaning first, because it is the question being asked. The rows beneath it are what the
        // catalog proved from bytecode; this one is written down, and says what the value does
        // rather than what shape it takes. Absent for an attribute nobody has described, which
        // leaves the rest of this popup exactly as it was.
        SolrAttributeMeanings.ofFactoryAttribute(attribute.name)?.let {
            append("<tr><td>Does</td><td>$it</td></tr>")
        }
        append("<tr><td>Read by</td><td><code>${escape(entry.shortName)}</code></td></tr>")
        val accepts = valueTypeText(attribute.valueType)
        if (accepts.isNotEmpty()) {
            append("<tr><td>Accepts</td><td>${escape(accepts)}</td></tr>")
        }
        when {
            attribute.required ->
                append(
                    "<tr><td>Required</td>" +
                        "<td>Solr rejects the class when this is absent</td></tr>",
                )
            attribute.defaultValue != null ->
                append(
                    "<tr><td>Default</td>" +
                        "<td><code>${escape(attribute.defaultValue)}</code></td></tr>",
                )
        }
        append("</table>")
        append("</div>")
        append(classGuideLink(entry, version))
    }

    /**
     * Every attribute [entry] accepts, resolved against what the tag wrote.
     *
     * Written attributes sort first for the same reason the field property table puts declared
     * values first: the author came to check what they wrote, and burying those rows under a list
     * of defaults makes the popup answer a different question from the one the gesture asked.
     */
    internal fun effectiveFactoryAttributes(
        entry: SolrClassEntry,
        writtenAttributes: Map<String, String>,
    ): List<SolrEffectiveFactoryAttribute> =
        entry.attributes.map { attribute ->
            val written = writtenAttributes[attribute.name]
            when {
                written != null ->
                    SolrEffectiveFactoryAttribute(attribute, written, SolrFactoryAttributeOrigin.TAG)
                attribute.defaultValue != null ->
                    SolrEffectiveFactoryAttribute(
                        attribute,
                        attribute.defaultValue,
                        SolrFactoryAttributeOrigin.SOLR_DEFAULT,
                    )
                attribute.required ->
                    SolrEffectiveFactoryAttribute(attribute, null, SolrFactoryAttributeOrigin.REQUIRED)
                else ->
                    SolrEffectiveFactoryAttribute(attribute, null, SolrFactoryAttributeOrigin.UNSET)
            }
        }.sortedBy { if (it.origin == SolrFactoryAttributeOrigin.TAG) 0 else 1 }

    /**
     * The configuration table for a factory tag — effective value, origin, and accepted type.
     *
     * Mirrors [propertyTable]'s columns closely enough that the two halves of the plugin read as
     * one surface. "Meaning" is absent on purpose: the catalog has no per-attribute prose, and
     * inventing one is exactly the claim [classDocumentation] already refuses to make.
     */
    private fun factoryConfigurationTable(
        tagName: String,
        entry: SolrClassEntry,
        writtenAttributes: Map<String, String>,
    ): String = buildString {
        append("<p><b>Configuration</b></p><table>")
        append("<tr><th>Attribute</th><th>Value</th><th>From</th><th>Accepts</th></tr>")
        for (effective in effectiveFactoryAttributes(entry, writtenAttributes)) {
            val written = effective.origin == SolrFactoryAttributeOrigin.TAG
            append("<tr><td><code>${escape(effective.attribute.name)}</code></td>")
            append(
                if (written) {
                    "<td><b>${escape(factoryValueText(effective))}</b></td>"
                } else {
                    "<td>${escape(factoryValueText(effective))}</td>"
                },
            )
            append("<td><i>${escape(factoryOriginText(effective.origin, tagName))}</i></td>")
            append("<td>${escape(valueTypeText(effective.attribute.valueType))}</td></tr>")
        }
        append("</table>")
    }

    /**
     * The value cell for one factory attribute.
     *
     * An em dash stands where the catalog cannot cite a value — required-but-missing and optional-
     * with-no-default alike. Collapsing those two into one glyph is deliberate: the From column
     * already separates them, and putting "required" in the value cell would look like Solr's
     * fallback was the string `required`.
     */
    private fun factoryValueText(effective: SolrEffectiveFactoryAttribute): String =
        effective.value ?: NO_RECORDED_VALUE

    /**
     * Where a factory attribute's effective value came from, named so the reader knows what to edit.
     *
     * Tag-specific "on this filter" / "on this tokenizer" wording matches the field half's "on
     * this field": a generic "on this tag" would force the reader to look up which element they
     * are on, which is the one fact the caret position already gave them.
     *
     * Named from the tag the file wrote rather than from the class's kind, so a tokenizer factory
     * written on a `<filter>` says *on this filter* — the element the reader would have to edit.
     */
    private fun factoryOriginText(origin: SolrFactoryAttributeOrigin, tagName: String): String =
        when (origin) {
            SolrFactoryAttributeOrigin.TAG -> "on this ${tagWord(tagName)}"
            SolrFactoryAttributeOrigin.SOLR_DEFAULT -> "Solr default"
            SolrFactoryAttributeOrigin.REQUIRED -> "required, not set"
            SolrFactoryAttributeOrigin.UNSET -> "no default recorded"
        }

    /**
     * A schema element in words, for the "on this …" label.
     *
     * `charFilter` is two words spoken and one written, which is the only reason this is a mapping
     * rather than the tag name itself. An element outside the analysis vocabulary falls back to the
     * generic word: echoing an unrecognised element name into prose reads as a typo, and `fieldType`
     * never reaches this surface.
     */
    private fun tagWord(tagName: String): String = when (SolrClassKind.forTag(tagName)) {
        SolrClassKind.TOKENIZER -> "tokenizer"
        SolrClassKind.TOKEN_FILTER -> "filter"
        SolrClassKind.CHAR_FILTER -> "char filter"
        // Every other kind, `fieldType` and an unrecognised element included. This surface is only
        // reached from an analysis component, so the generic word is what the remaining kinds want:
        // "on this requestHandler" would be prose that never runs.
        else -> "tag"
    }

    /**
     * The kind in words, for the definition line.
     *
     * The four analysis kinds are spelled out because their words are not their tag names — a
     * `<filter>` is a *token filter factory*, and a reader who has not learned that is exactly who
     * this line is for. Every `solrconfig.xml` kind falls through to its tag name split into words,
     * because there the tag *is* the plain-language name: `requestHandler` reads as "request
     * handler" and nothing is gained by writing that out twenty times.
     */
    private fun kindText(kind: SolrClassKind): String = when (kind) {
        SolrClassKind.FIELD_TYPE -> "field type class"
        SolrClassKind.TOKENIZER -> "tokenizer factory"
        SolrClassKind.TOKEN_FILTER -> "token filter factory"
        SolrClassKind.CHAR_FILTER -> "character filter factory"
        else -> spacedTagName(kind)
    }

    /**
     * A camel-cased tag name as words: `queryResponseWriter` becomes `query response writer`.
     *
     * Only ever applied to a token Solr itself declared, so the input is always camel case with no
     * acronyms to mishandle.
     */
    private fun spacedTagName(kind: SolrClassKind): String =
        kind.token.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase()

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
            // The remaining kinds name their own page well enough: a reader arriving from a
            // `<requestHandler>` is offered "Request handlers in the Reference Guide". The four above
            // stay written out because their page titles do not follow from their tag names.
            else -> "${spacedTagName(entry.kind).replaceFirstChar { it.uppercase() }}s in the Reference Guide"
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
     * The value cell where the catalog cites neither a written value nor a literal default.
     *
     * Shared by required-missing and optional-unset rows so the value column never pretends Solr
     * would use a string the bytecode never wrote.
     */
    private const val NO_RECORDED_VALUE = "—"

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

    /**
     * The popup for a structural attribute — the ones that make a schema a graph rather than text.
     *
     * [detail] carries the part that is specific to this configset rather than true of the
     * attribute everywhere, which today is only the schema version's resolved defaults. Rendered
     * as a second paragraph so the general answer reads first, the same order the field-property
     * popups use.
     *
     * @param attributeName the attribute being hovered
     * @param meaning what it means, as HTML
     * @param detail what it decides here, as HTML, or null when nothing is specific
     * @return the popup
     */
    fun attributeMeaning(attributeName: String, meaning: String, detail: String? = null): String =
        buildString {
            append("<div class='definition'><pre>${escape(attributeName)}</pre></div>")
            append("<div class='content'>")
            append("<p>$meaning</p>")
            if (detail != null) append("<p>$detail</p>")
            append("</div>")
        }
}
