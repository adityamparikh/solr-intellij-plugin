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
}
