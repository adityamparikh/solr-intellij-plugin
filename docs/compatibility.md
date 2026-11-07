# Compatibility

## Plugin 0.2.0

| What | Supported | Declared in |
|---|---|---|
| IDE | **IntelliJ IDEA 2026.2 and later** | `targetIdeVersion`, passed to `intellijIdea(...)` |
| Verified against | IntelliJ IDEA `IU-262.8665.258` | `verifiedIdeBuilds` |
| Apache Solr | **9.10.1** and **10.0.0** | `supportedSolrLines` |
| Build toolchain | JDK 21 | `jvmToolchain(21)` |

### IntelliJ IDEA only

The plugin depends on `com.intellij.modules.java`, because resolving the class a `class` attribute
names goes through Java PSI. IDEA bundles Java, so the dependency is always met there, but the plugin
does not install in PyCharm, WebStorm or the other IDEs.

### An open upper bound

The descriptor sets `since-build="262"` with no upper bound, so the plugin declares itself compatible
with every IDE build from 2026.2 on. Only the build in the table is verified, by the Plugin Verifier
on every pull request; a later IDE that breaks the plugin will do so without this page changing. The
open bound avoids shipping a release for every platform bump.

### Solr lines

The plugin supports the Solr release lines Apache has not declared end-of-life, and drops a line in
the release after Solr declares it. The exact releases matter because the class, parameter and
element catalogs are generated from those artifacts. The inspections run over Solr's `_default` and
`sample_techproducts_configs` configsets from both lines and must report nothing, except the
unused-field-type inspection, which rightly reports the types those configsets ship unused.

A connected server's version affects only the drift view, which resolves each side's defaults against
the server's line and names it in the summary. The editor never contacts a server, so it answers from
the configset alone.

### Changing any of this

Each value is declared once in `build.gradle.kts`, and `SolrCompatibilityPageTest` fails the build
until this page matches; see [contributing](contributing.md#things-that-will-bite-you).
