package org.apache.solr.ide.server.query.screen

/**
 * What the query console's form holds, and the parameters that makes.
 *
 * **Immutable, and replaced whole on every edit.** The tab holds one of these and every editor of
 * the tab reads it, so a second editor opened on the same tab, or a fill from the collections tree,
 * meets the same form rather than a copy that drifted.
 *
 * **A hidden field keeps its value and is not sent.** The Admin UI sends a hidden field's leftover
 * value — an edismax `pf2` after the parser went back to lucene — which is a parameter nobody can
 * see changing the answer. Keeping the value means switching back shows it again.
 *
 * @property spec the form this fills
 * @property values single values by parameter name; a name not here holds its default
 * @property lines the lines of each repeated or raw parameter, by name
 */
data class SolrQueryScreenState(
  val spec: SolrQueryScreenSpec,
  val values: Map<String, String> = emptyMap(),
  val lines: Map<String, List<String>> = emptyMap(),
) {

  /**
   * The value of [name]: what was entered, or its default.
   *
   * @param name a parameter, or a section's toggle
   * @return the value, empty where there is none
   */
  fun value(name: String): String = values[name] ?: spec.field(name)?.default.orEmpty()

  /**
   * The lines entered for the repeated or raw parameter [name].
   *
   * @param name a parameter
   * @return its lines, as entered
   */
  fun lines(name: String): List<String> = lines[name].orEmpty()

  /**
   * Whether the checkbox or toggle [name] is on.
   *
   * @param name a checkbox parameter, or a section's toggle
   * @return true where its value is `true`
   */
  fun isOn(name: String): Boolean = value(name) == TRUE

  /**
   * This state with [name] set to [value].
   *
   * @param name a parameter, or a section's toggle
   * @param value the value; `true` or `false` for a checkbox
   * @return the new state
   */
  fun with(name: String, value: String): SolrQueryScreenState =
    copy(values = values + (name to value))

  /**
   * This state with the lines of [name] replaced.
   *
   * @param name a repeated or raw parameter
   * @param entered its lines
   * @return the new state
   */
  fun withLines(name: String, entered: List<String>): SolrQueryScreenState =
    copy(lines = lines + (name to entered))

  /**
   * Whether [field] is shown, and therefore sent.
   *
   * @param field one of the form's parameters
   * @return false where its section's toggle is off or a condition it shows under does not hold
   */
  fun isVisible(field: SolrQueryScreenField): Boolean {
    val toggle = spec.sectionOf(field.name)?.toggle
    if (toggle != null && !isOn(toggle)) return false
    return field.showWhen.all { (name, allowed) -> value(name) in allowed }
  }

  /** The request handler's path as entered; `/select` where none is. */
  val handler: String
    get() =
      spec.fields
        .firstOrNull { it.kind == SolrQueryScreenFieldKind.HANDLER }
        ?.let { value(it.name).trim() }
        .orEmpty()
        .ifEmpty { DEFAULT_HANDLER }

  /**
   * The parameters to send, in form order.
   *
   * A section's toggle comes before the fields it opens. Raw parameters are last, because the Admin
   * UI puts them there and because a raw entry then reads as an addition to the form rather than as
   * something the form overrides.
   *
   * @return name and value pairs; a name may repeat
   */
  fun parameters(): List<Pair<String, String>> = buildList {
    spec.sections.forEach { section ->
      section.toggle?.let { toggle ->
        if (!isOn(toggle)) return@forEach
        add(toggle to TRUE)
      }
      section.fields.filter { isVisible(it) }.forEach { addAll(parametersOf(it)) }
    }
  }

  /**
   * The raw lines that cannot be sent, because they carry no name.
   *
   * @return each such line as entered, in order
   */
  fun rawProblems(): List<String> =
    visibleFields(SolrQueryScreenFieldKind.RAW)
      .flatMap { lines(it.name) }
      .filter { it.isNotBlank() && it.indexOf('=') <= 0 }

  private fun visibleFields(kind: SolrQueryScreenFieldKind) =
    spec.fields.filter { it.kind == kind && isVisible(it) }

  private fun parametersOf(field: SolrQueryScreenField): List<Pair<String, String>> =
    when (field.kind) {
      SolrQueryScreenFieldKind.HANDLER -> emptyList()
      // Blank is not sent, and for a choice that includes the empty entry: it stands for the
      // handler's own default, and sending a value would override what its solrconfig.xml set.
      SolrQueryScreenFieldKind.TEXT,
      SolrQueryScreenFieldKind.CHOICE ->
        value(field.name)
          .trim()
          .takeIf { it.isNotEmpty() }
          ?.let { listOf(field.name to it) }
          .orEmpty()
      // A checkbox on by default sends `false` when turned off, or turning it off would change
      // nothing: Solr's own default for `indent` or edismax's `stopwords` is the same `true`.
      SolrQueryScreenFieldKind.CHECKBOX ->
        when {
          isOn(field.name) -> listOf(field.name to TRUE)
          field.default == TRUE -> listOf(field.name to FALSE)
          else -> emptyList()
        }
      SolrQueryScreenFieldKind.REPEATED ->
        lines(field.name).map { it.trim() }.filter { it.isNotEmpty() }.map { field.name to it }
      // Split on the first `=` only, and an `&` is part of the value. That is the whole reason
      // the console exists: a value holding one cannot be written into a URL by hand.
      SolrQueryScreenFieldKind.RAW ->
        lines(field.name)
          .filter { it.isNotBlank() && it.indexOf('=') > 0 }
          .map { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim() }
    }

  private companion object {
    const val TRUE = "true"
    const val FALSE = "false"
    const val DEFAULT_HANDLER = "/select"
  }
}
