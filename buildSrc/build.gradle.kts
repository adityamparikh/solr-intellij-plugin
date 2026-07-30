// Build logic the main build script runs, chiefly the catalog-generator task. ASM lives here as an
// ordinary dependency because it is a tool of the build, not a library the plugin ships — the same
// reasoning that once put it on the buildscript classpath, without the legacy mechanism.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.asm)
    testImplementation(libs.junit)
}

// Gradle 8 stopped running buildSrc's tests as part of the main build — it only asks this build
// for its jar. This restores the gate from inside the only build that can wire it
// (`gradle.includedBuild(...)` deliberately excludes buildSrc), so a broken descriptor rule fails
// every `./gradlew build` rather than only a by-hand `-p buildSrc test` run. A finalizer rather
// than a dependency because kotlin-dsl compiles the tests *against* the jar, so `jar dependsOn
// test` is a cycle. Up-to-date checking keeps the cost at zero while buildSrc is unchanged.
tasks.named("jar") {
    finalizedBy(tasks.named("test"))
}
