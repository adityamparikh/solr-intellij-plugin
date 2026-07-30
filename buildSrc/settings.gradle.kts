// buildSrc is a build of its own, so the root's version catalog is not inherited. This points the
// conventional `libs` accessor at the same file, keeping ASM's version in the one place versions live.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
