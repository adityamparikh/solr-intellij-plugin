import java.io.File
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    id("org.jetbrains.kotlinx.kover")
    id("org.sonarqube")
    id("org.jetbrains.dokka")
}

kotlin {
    // JDK 21 is the floor set by the version-support policy (see specs/0002-solr-intellij-plugin.md
    // "Version-support policy"): Solr 10 requires Java 21, and the reference-data generator reflects
    // over Solr artifacts from every supported line. A JDK 21 toolchain also loads the previous
    // major's lower-baseline classes, so one toolchain spans all supported lines.
    jvmToolchain(21)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // IntelliJ IDEA is a single unified distribution as of 2025.3 (Community/Ultimate merged),
        // so we target the unified `intellijIdea(...)` artifact. XML PSI (configset parsing) is part
        // of platform core, so no extra bundledPlugin is needed yet. Phase 3 will add
        // bundledPlugin("com.intellij.java").
        intellijIdea("2026.2")
        testFramework(TestFrameworkType.Platform)
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
