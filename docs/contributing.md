# Contributing

How to get the plugin building, find work worth doing, and get a change merged. For where code goes
once you are writing it, see [code organization](code-organization.md); for the step-by-step of
adding a feature, see [adding an editor feature](how-to/add-an-editor-feature.md). The build
commands are under [Setup](#setup). Of the three gates `check` runs,
[testing and the build gates](how-to/testing-and-the-build-gates.md) covers clearing documentation
and coverage; formatting is under [things that will bite you](#things-that-will-bite-you).

## Setup

**JDK 21 or later.** The build pins `jvmToolchain(21)`: the newest supported Solr requires Java 21,
and the catalog generator reads Solr artifacts from every supported line. A JDK 21 toolchain also
loads the previous major's lower-baseline classes, so one toolchain spans every line the plugin
supports.

Nothing else needs installing. Gradle resolves the IntelliJ Platform itself.

```bash
git clone https://github.com/adityamparikh/solr-intellij-plugin.git
cd solr-intellij-plugin
./gradlew build
```

The commands you will use from then on:

```bash
./gradlew build            # compile, test, and run every gate
./gradlew check            # tests + coverage floor + documentation gate + formatting
./gradlew spotlessApply    # Google-style formatting for every Kotlin, Gradle and demo Java file
./gradlew test --tests "*.SolrConfigsetDetectorTest"   # one test class
./gradlew test --tests "*.SolrConfigsetDetectorTest.testManagedSchemaWithSiblingSolrConfigIsRecognized"  # one test
./gradlew runIde           # sandbox IDE with the plugin installed, opening demo/
./gradlew buildPlugin      # distributable ZIP in build/distributions/
./gradlew verifyPlugin     # IntelliJ Plugin Verifier + plugin structure checks
./gradlew dokkaGenerate    # render API docs to build/dokka/html
./gradlew koverXmlReport   # coverage report at build/reports/kover/report.xml
```

IDE run configurations live in `.run/`: **Run Plugin**, **Run Tests**, **Run Verifications**. They
appear in the run-configuration dropdown after the Gradle import finishes.

### Your first build is slow, and that is expected

`generateSolrCatalog` resolves `solr-core` for **every supported Solr line** and reads the class
files inside each one, along with Lucene's. It is wired in as a resource source directory:

```kotlin
sourceSets.named("main") { resources.srcDir(generateSolrCatalog) }
```

That line is what makes `processResources` depend on the generator, and therefore what makes a clean
build regenerate the catalog. A first build — or any build after `./gradlew clean` — downloads a
large dependency set before it compiles anything. On a slow connection this looks like a hang. It is
not.

The catalog exists because a [configset](glossary.md#configset) names Solr classes as strings, and
the plugin cannot complete or explain `class="solr.StrField"` without knowing what exists. That list
runs to hundreds of entries per line and changes between lines, so it is generated rather than
written down.

### First run

`./gradlew runIde` launches a [sandbox](glossary.md#sandbox) IDE with the plugin installed, and opens
the `demo/` project rather than whatever the sandbox had open last. `demo/` is a real Solr
application: it declares `org.apache.solr:solr-solrj` in its build, which passes the outer activation
gate, and it carries a real configset under `demo/solr/conf/`. So the plugin activates immediately
and you can see the features working without building a [fixture](glossary.md#fixture) first.

Open `demo/solr/conf/managed-schema.xml` and you should see inline hints beside each
[field](glossary.md#field) whose type the schema declares, saying what it can match and how it is
stored. Two fields are quiet on purpose: `notes` carries only the storage half, because its analyser
names a [factory](glossary.md#factory) the plugin does not recognise, and `legacy` carries no hint at
all, because its type is undeclared. If you see nothing anywhere, the plugin has not activated; see
[why nothing is happening](installing.md#why-nothing-is-happening).

To point the sandbox somewhere else:

```bash
./gradlew runIde -PrunIdeProject=/path/to/project   # a real-world configset, or a bug report
./gradlew runIde -PrunIdeProject=                   # open nothing; the sandbox restores its own state
```

### When nothing activates

What a user sees and does when the plugin stays silent — the two gates, and marking a configset root
by hand — is in [installing](installing.md#why-nothing-is-happening). How the decision is made, and
the classes that make it, is [the activation decision](code-organization.md#the-activation-decision).

## Where work comes from

| Document | Owns |
|---|---|
| [`specs/0002-solr-intellij-plugin.md`](../specs/0002-solr-intellij-plugin.md) | **Intent.** What the plugin is for and what each feature should do. Read it before designing anything. |
| [`specs/plans/0002-solr-intellij-plugin-plan.md`](../specs/plans/0002-solr-intellij-plugin-plan.md) | **Status and order.** Which steps are done, which block which, and what "done" means for each. |
| [`docs/demo/README.md`](demo/README.md) | **Acceptance criteria in user terms.** A runbook for demonstrating the plugin. |
| [`README.md`](../README.md) | The short version, for someone deciding whether to care. |

**The plan owns status.** Do not infer what is built from the API reference, from this guide, or from
the specification — the specification describes intent. If you want to know whether something works
today, the plan is the only file that answers.

The plan groups work into **tracks** — foundation, editor, server, code, and cross-cutting — which
are independent of each other once the field model exists. Pick a step from a track whose
prerequisites are done. Its success criteria are the acceptance test, written in user terms, and a
step is not finished until they are true.

The [demo runbook](demo/README.md) is worth reading alongside. *"Ctrl-click `name` inside the `qf`
line and land in the schema"* is a sharper definition of done than "request-handler parameters
resolve to schema fields", because it can only pass when the whole path works — detection, model,
[reference](glossary.md#reference) resolution, and the platform registration that unit tests
routinely miss.

## Commits and pull requests

**Conventional-commit subjects.** `feat:`, `fix:`, `docs:`, `build:`, `ci:`.

**Sign-off is mandatory.** Use `git commit -s`, which appends the `Signed-off-by` trailer. A pull
request whose commits lack it will be asked to rebase.

**Commit bodies carry real weight here.** For several changes, the body is the only record of *why* a
constraint exists. Write it for the person who, a year from now, will look at your constraint and
wonder whether it can be deleted. If the body only restates the subject in more words, it is not
doing the job; if it names something that would otherwise have to be rediscovered, it is.

### What a reviewer will push back on

- A public declaration with no KDoc, or KDoc that restates the signature.
- A commit body that does not say why.
- An [inspection](glossary.md#inspection) that can fire on a correct file; see the ground rules in
  `SolrInspections`.
- Anything on the editor path that contacts a server, or reads an index while declaring itself
  dumb-aware ([platform mechanisms](platform-mechanisms.md#dumb-mode-and-dumbaware)).
- Mirroring plan status into another file.

## CI

Three jobs in [`.github/workflows/build.yml`](../.github/workflows/build.yml): **Build** produces the
plugin ZIP, **Test** runs the suite and the gates, **Verify plugin** runs the IntelliJ Plugin
Verifier. A draft release is prepared on pushes to `main`, never on pull requests.

**The Test job runs the gates last.** `koverVerify` and `dokkaGenerate` are bound to `check`, so the
job runs `test koverXmlReport`, uploads the coverage report and runs the Sonar scan *before*
`check`. That makes the reports exist whether or not a gate fails — which is when you most want to
read them. If you add a step, keep it on the correct side of `check`: an upload of something `check`
generates belongs after it, with `always()`.

**GitHub Actions are pinned to full commit SHAs** with a trailing version comment. Tags are mutable
and CI has `SONAR_TOKEN` in scope; the trailing comment is what lets Dependabot still propose
upgrades.

**Gradle dependency verification is absent on purpose.** It was tried and removed: regenerating the
metadata on every dependency bump was not worth it for a pre-release plugin.

## Things that will bite you

**Formatting in the IDE.** `./gradlew spotlessApply` is the whole workflow. In the IDE, the
[ktfmt plugin](https://plugins.jetbrains.com/plugin/14912-ktfmt) set to *Google (internal)* style
formats on <kbd>Ctrl-Alt-L</kbd> the same way. Without it, `.editorconfig` brings the IDE's own
formatter close — indent, continuation indent, line length, import order, no wildcard imports — but
does not wrap lines as ktfmt does, so run `spotlessApply` before committing either way. The commit
that first reformatted the tree is listed in `.git-blame-ignore-revs`; for local `git blame`, run
`git config blame.ignoreRevsFile .git-blame-ignore-revs` once.

**The configuration cache is on.** `org.gradle.configuration-cache = true` in `gradle.properties`. A
task action that calls a function declared in `build.gradle.kts`, or reads one of its properties,
captures the Gradle `Project` object, which the cache cannot serialize. The task works until the
cache has to be rebuilt, and then fails. `runIde` and `generateSolrCatalog` each capture what they
need as plain values first, and declare local functions inside the action rather than at script
level. Copy that shape.

**Supported Solr lines are declared in one place**, `supportedSolrLines` in `build.gradle.kts`.
Adding a line or dropping an end-of-life one is one edit there. The policy — whatever Apache Solr has
not declared EOL — lives in the specification's "Version support" section.

**A corrupted test sandbox looks exactly like a broken plugin.** If every fixture test fails at once
with `FileDeletedException` while the plain JUnit tests stay green, see
[testing and the build gates](how-to/testing-and-the-build-gates.md).
