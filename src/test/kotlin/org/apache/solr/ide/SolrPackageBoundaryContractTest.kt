package org.apache.solr.ide

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The two package boundaries the code organization states and no compiler holds: what a configset
 * *means* imports no IntelliJ type, and the two configset aspects never reach sideways for each
 * other.
 *
 * **Neither is caught by anything else in the suite, which is why this exists.** The plain JUnit
 * tests over `model` run with the platform jars on the classpath, so a `TextRange` creeping into
 * `model.query` compiles, passes, and quietly turns a pure function into one only a fixture can
 * reach. And an import from `configset.schema` into `configset.solrconfig` is one line in an
 * otherwise reasonable diff; the rule that forbids it lives in prose. A separate Gradle module
 * would make the first a compile error, but reuse outside the IDE is not a goal, so a module
 * boundary would cost a multi-project build to buy what one source walk buys here.
 *
 * The live-server boundary is the third such rule and has its own test,
 * `server.SolrServerBoundaryContractTest`, because its shape is an allowlist of consumers rather
 * than a list of pure packages.
 */
class SolrPackageBoundaryContractTest {

  /**
   * Packages, and whole subtrees under them, that must import nothing from the IntelliJ Platform.
   *
   * `model` because it is what both sources read. The two parsers because each turns one file's
   * text into facts, which is what lets the correctness-critical reading be tested over a string.
   */
  private val platformFreePackages =
    listOf(
      "org.apache.solr.ide.model",
      "org.apache.solr.ide.configset.schema.parsing",
      "org.apache.solr.ide.configset.solrconfig.parsing",
    )

  /**
   * Single files that must stay platform-free although their package need not.
   *
   * `SolrXmlDocuments` is the XML walking both parsers are built on. It lives in
   * `configset.reading` beside the platform-aware reader, so a package rule would be wrong for it —
   * but if it took a platform type, both parsers would take it with it.
   */
  private val platformFreeFiles = listOf("configset/reading/SolrXmlDocuments.kt")

  /**
   * Import prefixes that name the IntelliJ Platform.
   *
   * `org.jetbrains` is too wide to forbid whole: coroutines and the nullability annotations are
   * JetBrains libraries but not the platform, and a pure package may use either.
   */
  private val platformPrefixes = listOf("com.intellij.", "com.jetbrains.", "org.jetbrains.")

  private val nonPlatformPrefixes = listOf("org.jetbrains.kotlinx.", "org.jetbrains.annotations.")

  private val schemaAspect = "org.apache.solr.ide.configset.schema"

  private val solrconfigAspect = "org.apache.solr.ide.configset.solrconfig"

  private fun sourceRoot(): File {
    // Resolved rather than assumed, so a wrong working directory fails naming the directory instead
    // of walking nothing and passing.
    val root = File("src/main/kotlin/org/apache/solr/ide")
    assertTrue("expected Kotlin sources at ${root.absolutePath}", root.isDirectory)
    return root
  }

  private fun sources(): List<File> =
    sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

  private fun packageOf(file: File): String =
    file
      .readLines()
      .firstOrNull { it.startsWith("package ") }
      ?.removePrefix("package ")
      ?.trim()
      .orEmpty()

  private fun within(pkg: String, root: String): Boolean = pkg == root || pkg.startsWith("$root.")

  /**
   * Code lines, with comments and KDoc dropped.
   *
   * A doc comment may legitimately *name* the other aspect or a platform type — this plugin's prose
   * does so constantly — and failing on a sentence about the rule would make the rule unstateable.
   */
  private fun codeLines(file: File): List<String> =
    file
      .readLines()
      .map { it.trim() }
      .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }

  private fun platformImportsIn(file: File): List<String> =
    codeLines(file)
      .filter { it.startsWith("import ") }
      .filter { line ->
        val imported = line.removePrefix("import ").trim()
        platformPrefixes.any { imported.startsWith(it) } &&
          nonPlatformPrefixes.none { imported.startsWith(it) }
      }

  private fun report(rule: String, offenders: List<Pair<String, List<String>>>, remedy: String) {
    if (offenders.isEmpty()) return
    fail(
      buildString {
        appendLine(rule)
        offenders.forEach { (path, lines) ->
          appendLine("  $path")
          lines.forEach { appendLine("      $it") }
        }
        appendLine()
        append(remedy)
      }
    )
  }

  @Test
  fun `what a configset means imports nothing from the IntelliJ Platform`() {
    val root = sourceRoot()
    val offenders =
      sources()
        .filter { file ->
          val pkg = packageOf(file)
          platformFreePackages.any { within(pkg, it) } ||
            file.relativeTo(root).invariantSeparatorsPath in platformFreeFiles
        }
        .mapNotNull { file ->
          platformImportsIn(file).takeIf { it.isNotEmpty() }?.let { file.path to it }
        }

    report(
      "The model and the parsers must stay plain Kotlin, testable without an IDE.",
      offenders,
      "Move the platform-facing part into the gesture package that needs it, and pass the " +
        "model a plain value instead.",
    )
  }

  @Test
  fun `neither configset aspect imports the other`() {
    val offenders =
      sources().mapNotNull { file ->
        val pkg = packageOf(file)
        val other =
          when {
            within(pkg, schemaAspect) -> solrconfigAspect
            within(pkg, solrconfigAspect) -> schemaAspect
            else -> return@mapNotNull null
          }
        codeLines(file)
          .filter { line -> line.contains("$other.") }
          .takeIf { it.isNotEmpty() }
          ?.let { file.path to it }
      }

    report(
      "configset.schema and configset.solrconfig must never import each other.",
      offenders,
      "A capability that needs both traverses the configset, and belongs above the aspects, in " +
        "configset.navigation or configset.reading.",
    )
  }

  /**
   * The test can actually see the sources it claims to check.
   *
   * Without this the rules above pass just as well against an empty directory or a renamed package
   * — a green tick carrying no information.
   */
  @Test
  fun `the walk reaches every package it claims to check`() {
    val packages = sources().map { packageOf(it) }.toSet()
    val root = sourceRoot()

    (platformFreePackages + schemaAspect + solrconfigAspect).forEach { expected ->
      assertTrue(
        "expected sources in $expected, found none",
        packages.any { within(it, expected) },
      )
    }
    platformFreeFiles.forEach { assertTrue("expected $it to exist", root.resolve(it).isFile) }
  }
}
