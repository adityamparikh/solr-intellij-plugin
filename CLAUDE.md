# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

The human-facing documentation is the substance; this file keeps only what gets violated when it sits
behind a link. Read the relevant one before designing anything.

| Document | Owns |
|---|---|
| [`docs/contributing.md`](docs/contributing.md) | Setup, first run, where work comes from, commit and PR mechanics, CI |
| [`docs/code-organization.md`](docs/code-organization.md) | Where a change goes, and what each package boundary forbids |
| [`docs/how-to/add-an-editor-feature.md`](docs/how-to/add-an-editor-feature.md) | Adding an inspection, completion, reference, documentation or hint |
| [`docs/how-to/extend-the-field-model.md`](docs/how-to/extend-the-field-model.md) | Adding to `model` and the parsers that fill it |
| [`docs/how-to/testing-and-the-build-gates.md`](docs/how-to/testing-and-the-build-gates.md) | Test conventions, and clearing Dokka and Kover |
| [`docs/manual-test-suite.md`](docs/manual-test-suite.md) | The sandbox verification pass: gesture, expected outcome, pass history — never build status |
| [`docs/platform-mechanisms.md`](docs/platform-mechanisms.md) | Dumb mode and model caching — what they are and what this plugin decided |
| [`docs/solr-configuration-files.md`](docs/solr-configuration-files.md) | Which Solr config is hand-edited vs API-written |
| [`docs/faq.md`](docs/faq.md) | Why quick documentation links to the Reference Guide rather than copying it, and why version resolution can't lean on a live sources-jar lookup |
| [`specs/0002-solr-intellij-plugin.md`](specs/0002-solr-intellij-plugin.md) | **Intent.** What the plugin is for. Read before designing a feature |
| [`specs/plans/0002-solr-intellij-plugin-plan.md`](specs/plans/0002-solr-intellij-plugin-plan.md) | **Status and order.** The only file that owns what is built |
| [`docs/design/`](docs/design/README.md) | Per-feature design records: backlog write-ups not yet in the plan (`pending/`), and shipped ones kept for history (`archive/`) |

## Commands

```bash
./gradlew build            # compile, test, and run both gates below
./gradlew check            # tests + coverage floor + documentation gate
./gradlew test --tests "*.SolrConfigsetDetectorTest"                          # one test class
./gradlew test --tests "*.SolrConfigsetDetectorTest.testSchemaUnderConfDirIsRecognized"  # one test
./gradlew runIde           # sandbox IDE with the plugin installed, opening demo/
./gradlew buildPlugin      # distributable ZIP in build/distributions/
./gradlew verifyPlugin     # IntelliJ Plugin Verifier + plugin structure checks
./gradlew dokkaGenerate    # render API docs to build/dokka/html
./gradlew koverXmlReport   # coverage report at build/reports/kover/report.xml
```

Run configurations in `.run/`: Run Plugin, Run Tests, Run Verifications. A first or post-`clean` build
resolves Solr artifacts for every supported line to generate the class catalog, and is slow.

## Status

**The plan owns what is built.** Do not mirror its step status into this file or any other, and do not
infer status from the API reference, the specification, or the code. Position changes every step and
orientation does not, so a copy goes stale while the plan stays correct.

## Two build gates that will bite you

**Documentation coverage.** Dokka runs with `reportUndocumented` and `failOnWarning`, and
`dokkaGenerate` is a dependency of `check`. Any public class, function or property in
`src/main/kotlin` without KDoc fails the build, naming the declaration. Adding public API means
documenting it in the same change. Tests are exempt.

**Test coverage.** Kover enforces an 80% line floor via `koverVerify`, also bound to `check`. The
floor sits below actual coverage on purpose; SonarCloud's new-code gate is what catches gradual
erosion.

Package and module prose lives in `docs/Module.md`. KDoc reads `[foo]` as a symbol link, so Markdown
reference-style links (`[text][ref]`) do not work there; use inline `[text](url)`.

## Rules no build gate enforces

**Every contribution declares itself dumb-aware, and that is a promise about data sources.** Almost
nothing here reads an index — a configset is text. The one thing that does, class navigation through
`JavaPsiFacade`, must **both** decline the declaration **and** guard with `DumbService`. An earlier
revision of this rule said *or*, and that is how a defect shipped: declining keeps a contribution out
of the paths that consult the flag, while its `resolve` is still called directly by anything walking
references at a caret — unguarded, it throws during indexing and takes the whole quick-documentation
popup with it, including what needed no index. Note the mechanism differs by extension point:
inspections and completion override `isDumbAware()`, documentation and inlay providers implement the
`DumbAware` marker interface. `SolrDumbModeContractTest` is what holds this to more than a promise.

**Nothing in the `org.apache.solr.ide.model` tree imports an IntelliJ type.** That is what lets a
third of the suite be plain JUnit 4 with no fixture. Inside it, `model.schema` is what a field *is*
and `model.vocabulary` is what a configuration file may legally contain — the test being whether a
server reader would need the thing to interpret what it fetched.

**`configset.schema` and `configset.solrconfig` must never import each other.** That is the whole
prohibition, and it is one-directional rather than a ban on the two aspects appearing together: a
shared package **may** import both, because composing them is what makes it shared.
`configset.reading` imports both parsers, since dispatching on the file kind is its job, and
`configset.navigation` imports both aspects' reference types, since labelling usages from either is
its job. What neither aspect may do is reach sideways for the other.

A capability falls under an aspect when the caret that triggers it is always in that aspect's file;
one that traverses the configset — Find Usages, rename — belongs to `configset.navigation`, because
filing it under `schema` would make that aspect depend on the other.

**Nothing on the editor path contacts a server**, and detection signals stay cheap, local and cached
because detection runs on every file the user opens.

**Never build a cache in front of `SolrConfigsetReader.modelFor`.** It already caches through the
platform's `CachedValuesManager`. A fact read from a file the reader does not already read must join
`sourcesOf`, or the model goes stale after the first edit.

**The plugin edits configuration files directly and never refuses a write.** If you find code or docs
asking whether a write is *allowed*, it predates this and should go.

**Inspections must not fire on a correct file.** Solr configuration is full of syntax that resembles a
field name — `fl` holds `score`, `*`, `[docid]`, `max(price,0)`. Use `SolrInspections`, and write the
clean fixtures first.

## Tests

**Two conventions, and what you are testing decides which you get.** Anything with PSI extends
`BasePlatformTestCase`, which is JUnit 3-style despite the JUnit 4 dependency: methods must be named
`testSomething()` and are discovered by that prefix, not by `@Test`. Anything importing nothing from
the platform is plain JUnit 4 with `@Test` and backtick names, because booting an IDE to exercise a
pure function costs a second of wall-clock for nothing.

**Anything touching `SolrConfigsetSettings` or `SolrConnectionSettings` must extend
`SolrConfigsetTestCase`.** `BasePlatformTestCase` reuses one light project across test methods *and*
classes, so settings state leaks between tests. That base class also puts a Solr client on the
fixture's classpath — without it, a test asserting nothing fires passes for the wrong reason.

Build fixtures with `myFixture.addFileToProject(path, content)`; the path shapes the directory
structure the detector reads, so it is part of the test's meaning. `checkHighlighting` fails on
highlights the fixture did *not* mark as well as ones it did.

**If every fixture test fails at once with `FileDeletedException` while the plain JUnit tests stay
green, the sandbox VFS is corrupt, not your code.** It survives `./gradlew clean`. Delete
`.intellijPlatform/sandbox/<project>/<IDE>/system-test` and re-run;
[the testing guide](docs/how-to/testing-and-the-build-gates.md) has the full signature.

## Conventions

Conventional-commit subjects (`feat:`, `fix:`, `docs:`, `build:`, `ci:`) and mandatory sign-off
(`git commit -s`). **Commit bodies carry real weight** — for several changes they are the only record
of *why* a constraint exists.

GitHub Actions are pinned to full commit SHAs with a trailing version comment; keep it that way.
Gradle dependency verification was tried and deliberately removed, so its absence is not an oversight.
CI runs the gates last so a failure still leaves reports uploaded — preserve that ordering.
[`docs/contributing.md`](docs/contributing.md) explains all three.

The build pins `jvmToolchain(21)`, and `supportedSolrLines` in `build.gradle.kts` is the single place
Solr lines are declared. A Solr EOL announcement is a maintenance trigger, not a background event.
