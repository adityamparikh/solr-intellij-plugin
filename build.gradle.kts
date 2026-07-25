import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    id("org.jetbrains.kotlinx.kover")
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
