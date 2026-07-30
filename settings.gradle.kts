import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "solr-intellij-plugin"

// The only versions declared outside gradle/libs.versions.toml. A settings `plugins` block runs
// before any version catalog exists, so these two cannot be aliased; every other plugin is applied
// in build.gradle.kts via `alias(libs.plugins...)`. The IntelliJ Platform version named here also
// governs the unversioned `org.jetbrains.intellij.platform` id the build script applies.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    // Configure all projects' repositories
    repositories {
        mavenCentral()

        // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform {
            defaultRepositories()
        }
    }
}
