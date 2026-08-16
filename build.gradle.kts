import java.io.File
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.apache.solr.ide.build.GenerateSolrCatalogTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.jvm.JvmLibrary
import org.gradle.language.base.artifact.SourcesArtifact
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin.jvm)
    // Versioned in settings.gradle.kts, not the catalog: the IntelliJ Platform settings plugin
    // applied there must match this one, and a settings `plugins` block cannot read the catalog.
    id("org.jetbrains.intellij.platform")
    alias(libs.plugins.changelog)
    alias(libs.plugins.kover)
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.dokka)
}

kotlin {
    // JDK 21 is the floor set by the version-support policy (see specs/0002-solr-intellij-plugin.md
    // "Version-support policy"): Solr 10 requires Java 21, and the reference-data generator reflects
    // over Solr artifacts from every supported line. A JDK 21 toolchain also loads the previous
    // major's lower-baseline classes, so one toolchain spans all supported lines.
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // IntelliJ IDEA is a single unified distribution as of 2025.3 (Community/Ultimate merged),
        // so we target the unified `intellijIdea(...)` artifact. XML PSI (configset parsing) is part
        // of platform core.
        intellijIdea("2026.2")

        // Java PSI, for resolving the class a `class` attribute names. Needed to *compile* the
        // reference provider and to test it; the plugin itself depends on `com.intellij.modules.java`
        // **optionally**, so the provider is registered only where Java PSI exists and the rest of the
        // plugin works in an IDE without it. This is the dependency the comment above used to defer to
        // Phase 3 — it arrives early because class navigation is the one Step 25 action that needs
        // nothing else, and Phase 3 will need it unconditionally regardless.
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)

        // The Plugin Verifier CLI, declared rather than assumed. `verifyPlugin` needs this
        // executable, and without a declaration it only ran where a previous build had already left
        // it in the Gradle cache — which is every local machine, and a CI runner only sometimes. The
        // verify job restores its cache read-only, so on a runner whose cache lacked the CLI the
        // task failed with *IntelliJ Plugin Verifier executable not found*, having resolved nothing
        // and produced no report. A gate that passes according to what a cache happens to hold is
        // not a gate.
        //
        // **Pinned, and that is the same argument carried one step further.** The argument-less
        // overload defaults to `Constraints.LATEST_VERSION`, which resolves whatever the newest
        // published verifier is at build time — so the gate's answer could change with no commit in
        // this repository, and would change on each machine as its dynamic-version cache expired.
        // Declaring the tool but not its version leaves the same hole a shape smaller. Bumping this
        // is a commit, exactly as the action SHAs in `.github/workflows` are.
        pluginVerifier("1.409")
    }
}

// ---------------------------------------------------------------------------------------------
// What the Plugin Verifier checks against. **This is the single place the verified IDE builds are
// declared**, and the compatibility matrix Step 21 writes is due to be rendered from it rather than
// restated beside it — a matrix promising a build the Verifier never checked is worse than no
// matrix at all.
//
// One entry today, and that is the honest number rather than a placeholder. `since-build` is 262
// with no upper bound, so the plugin claims every build from 2026.2 onward; 2026.2 is the newest
// that exists, and nothing can verify a build that has not shipped. The list grows by one on each
// platform release the plugin is bumped to, in the same commit.
// ---------------------------------------------------------------------------------------------
val verifiedIdeBuilds = listOf("2026.2")

intellijPlatform {
    pluginVerification {
        ides {
            // Declared rather than defaulted. Left unconfigured, the task verifies against whatever
            // the build happens to target — which is the same class of hole the pinned verifier
            // version above closed: a gate whose subject is decided somewhere else changes answer
            // without a commit here.
            //
            // **IntelliJ IDEA only, and that is the product decision rather than an omission.** The
            // plugin targets IDEA and nothing else, so this list is complete rather than a first
            // entry. It is stated here because the alternative reads as an oversight: `plugin.xml`
            // takes `com.intellij.modules.java` as an *optional* dependency, which looks like a
            // claim that the plugin runs in an IDE without Java PSI — and nothing verifies that,
            // because nothing needs to. IDEA has been a single unified distribution since 2025.3
            // and bundles Java, so the optional dependency is always satisfied in every IDE this
            // list names.
            verifiedIdeBuilds.forEach { create(IntelliJPlatformType.IntellijIdea, it) }
        }
    }
}

// Open the demo fixture in the sandbox IDE instead of whatever project the sandbox happened to
// have open last. IntelliJ takes a project path as a positional argument; passing it through an
// argument provider rather than `args` keeps it lazy, so the path is resolved at execution time and
// the task stays compatible with the configuration cache.
//
// Overridable for the cases where the demo project is the wrong target — inspecting a real-world
// configset, or reproducing a bug report:
//
//   ./gradlew runIde -PrunIdeProject=/path/to/project    open something else
//   ./gradlew runIde -PrunIdeProject=                    open nothing; sandbox restores its own state
tasks.runIde {
    // Resolved through providers only. An earlier version called `file(path)` inside the argument
    // provider, which captures the Gradle `Project` — a script object reference the configuration
    // cache cannot serialize, so `runIde` failed the moment the cache had to be rebuilt.
    val requested = providers.gradleProperty("runIdeProject")
        .orElse(layout.projectDirectory.dir("demo").asFile.absolutePath)
        .map { path ->
            // A blank value is the documented opt-out, and a path to a directory that does not
            // exist must not become an argument the IDE tries to interpret as a file to create.
            if (path.isNotBlank() && File(path).isDirectory) listOf(path) else emptyList()
        }
    argumentProviders.add(CommandLineArgumentProvider { requested.get() })
}

// SonarCloud analysis. Runs from CI rather than SonarCloud's Automatic Analysis, because
// Automatic Analysis cannot ingest a coverage report — the two modes are mutually exclusive.
sonar {
    properties {
        property("sonar.projectKey", "adityamparikh_solr-intellij-plugin")
        property("sonar.organization", "adityamparikh")
        property("sonar.host.url", "https://sonarcloud.io")
        // Kover writes a JaCoCo-format XML report; this is the property that makes coverage
        // visible in SonarCloud at all.
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory.file("reports/kover/report.xml").get().asFile.path,
        )
        // The configsets Apache Solr ships, vendored verbatim as the zero-findings fixture. They are
        // not this project's code and must not be edited to satisfy an analyser — the whole point of
        // them is that nobody here wrote them. Excluded rather than merely exempted from duplication
        // detection, which they would otherwise dominate: `_default`'s schema is byte-identical on
        // both supported lines, because Solr did not change it between them.
        property("sonar.exclusions", "src/test/resources/shipped-configsets/**")
    }
}

// Code coverage. SonarCloud cannot compute coverage itself — it ingests a report, so the XML
// variant must stay enabled for `sonar.coverage.jacoco.xmlReportPaths` to resolve.
kover {
    reports {
        total {
            xml {
                // Uploaded as an artifact by the Test job in .github/workflows/build.yml. Once
                // CI-based Sonar analysis replaces Automatic Analysis, this is the path
                // `sonar.coverage.jacoco.xmlReportPaths` will point at.
                xmlFile = layout.buildDirectory.file("reports/kover/report.xml")
            }
            html {
                onCheck = false
            }

            filters {
                excludes {
                    // Thin DynamicBundle wrapper: platform resource-bundle plumbing with no branches
                    // of our own, and exercising it meaningfully needs a running IDE.
                    classes("org.apache.solr.ide.SolrBundle*")
                }
            }

            // Coverage floor, enforced as part of `check`. Set at 80% against 100% actual: the
            // headroom is deliberate, so that adding UI and PSI code — which is awkward to unit
            // test — does not block a PR the moment it lands. Sonar's new-code gate is what
            // catches gradual erosion; this rule is the backstop against a sharp drop.
            verify {
                onCheck = true
                rule {
                    bound {
                        minValue = 80
                        coverageUnits = CoverageUnit.LINE
                    }
                }
            }
        }
    }
}
// API documentation. `reportUndocumented` emits a warning for every public declaration without
// KDoc and `failOnWarning` turns those warnings into a build failure, so undocumented public API
// cannot merge. Only the `main` source set is gated — tests are exempt by design.
dokka {
    moduleName = "Solr IntelliJ Plugin"

    dokkaPublications.html {
        failOnWarning = true
    }

    dokkaSourceSets.main {
        reportUndocumented = true

        // Module and package overviews (the landing page and per-package headers).
        includes.from("docs/Module.md")

        // Deep-link every documented symbol to its exact line on GitHub.
        sourceLink {
            localDirectory = file("src/main/kotlin")
            remoteUrl("${providers.gradleProperty("pluginRepositoryUrl").get()}/tree/main/src/main/kotlin")
            remoteLineSuffix = "#L"
        }
    }
}

// Documentation coverage joins the coverage floor as part of `check`, so both gates fire together
// on `./gradlew build` locally and in the CI step that runs `check` — no separate workflow to
// keep in sync.
tasks.check {
    dependsOn(tasks.dokkaGenerate)
}

// Where the committed demo configset is, told to the tests that read it rather than inferred by
// them. `DemoConfigsetTest` and `SolrDemoFindUsagesTest` exercise the files the demo is actually
// driven on, so they need a path to real files on disk — and they had been deriving it from
// `user.dir`, which is the project directory under Gradle but is a launcher's choice, not a fact
// about the repository. The build knows the answer without inferring it, so it says so; the tests
// keep the `user.dir` reading as a fallback for any runner that bypasses this task.
//
// Resolved at configuration time to a plain String, which is what keeps the configuration cache
// able to store it.
tasks.test {
    systemProperty("demo.configset.dir", layout.projectDirectory.dir("demo/solr/conf").asFile.absolutePath)
}

// ---------------------------------------------------------------------------------------------
// The generated class catalog. What it is, why it is generated, and how the bytecode extraction
// works all live on `GenerateSolrCatalogTask` in buildSrc; this script declares only the policy —
// which Solr lines are supported — and wires the task in.
// ---------------------------------------------------------------------------------------------

// The Solr lines this plugin supports, newest first. **This is the single place they are declared**,
// so adding a line or dropping an end-of-life one is one edit. The policy itself lives in the
// specification's "Version support" section: whatever Apache Solr has not declared EOL.
val supportedSolrLines = mapOf(
    "10" to "10.0.0",
    "9" to "9.10.1",
)

supportedSolrLines.forEach { (line, version) ->
    val configuration = configurations.create("solrArtifacts$line") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    dependencies.add(configuration.name, "org.apache.solr:solr-core:$version")
    // Chinese (smartcn), ICU, Stempel and Morfologik ship in Solr's analysis-extras module rather
    // than in core, so resolving core alone produced a catalog with Japanese and Korean support and
    // no Chinese at all. A configset can name these, so the catalog has to know them.
    dependencies.add(configuration.name, "org.apache.solr:solr-analysis-extras:$version")
}

/**
 * Resolves the `-sources` jar for every component already resolved into [configuration], for the
 * catalog's documentation column.
 *
 * `ArtifactResolutionQuery` has no lazy, task-execution-time form the configuration cache accepts —
 * it always reaches for the live `DependencyHandler` — so, unlike the artifacts themselves, this
 * runs here, eagerly, at configuration time, rather than through the task's own lazy
 * `ConfigurableFileCollection`. That is what keeps it affordable rather than merely correct: a
 * configuration-cache hit skips the configuration phase entirely, so this resolves once per cache
 * entry and not on every invocation that happens to need the catalog.
 *
 * A component with no published sources — Solr's own transitive dependencies chief among them —
 * is silently absent from the result, the same decline-rather-than-guess rule the generator's
 * attribute pass already follows.
 */
fun resolveSources(configuration: Configuration): List<File> {
    val components = configuration.incoming.resolutionResult.allComponents
        .mapNotNull { it.id as? ModuleComponentIdentifier }
    val resolved = dependencies.createArtifactResolutionQuery()
        .forComponents(components)
        .withArtifacts(JvmLibrary::class.java, SourcesArtifact::class.java)
        .execute()
    return resolved.resolvedComponents.flatMap { component ->
        component.getArtifacts(SourcesArtifact::class.java)
            .filterIsInstance<ResolvedArtifactResult>()
            .map { it.file }
    }
}

val generateSolrCatalog = tasks.register<GenerateSolrCatalogTask>("generateSolrCatalog") {
    group = "build"
    description = "Reads each supported Solr line's artifacts into the shipped class catalog."
    outputDirectory = layout.buildDirectory.dir("generated/solr-catalog-resources")
    supportedSolrLines.forEach { (line, version) ->
        val configuration = configurations.named("solrArtifacts$line").get()
        solrLine(line, version, configuration, resolveSources(configuration))
    }
}

// The catalog ships with the plugin, so it is a resource of the main source set. This is what makes
// `processResources` depend on the generator, and therefore what makes a clean build regenerate it.
sourceSets.named("main") { resources.srcDir(generateSolrCatalog) }
