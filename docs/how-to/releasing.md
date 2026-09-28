# Releasing the plugin

> **Who this is for.** Whoever is cutting a version — either a GitHub release people install by hand,
> or a JetBrains Marketplace listing the IDE offers on its own.
> **Read first:** [Contributing](../contributing.md) for the build and CI ·
> [Compatibility](../compatibility.md) for what a version claims to support.

**Nothing here has been done yet.** 0.1.0 was cut in [the changelog](../../CHANGELOG.md) and never
published. `gradle.properties` now says 0.2.0, and the draft release waiting on GitHub is that
version — but no tag has been pushed, no release published, and nothing uploaded to the
Marketplace. The
[preflight](#preflight-what-must-be-true-before-a-marketplace-release) below is the list of what is
still missing, and it is written from what the repository actually contains rather than from what a
release usually needs.

## What a version number means

The mechanism below cuts whatever number you give it. This section is the part that decides which
number that should be, and it exists because "0.2.0 or 0.1.1?" is answered differently by every person
who is asked it in the moment.

| Bump | When |
|---|---|
| **Major** | A configset the plugin used to read stops being read, a setting stops being honoured, or a supported Solr line is dropped. Nothing here is a major yet — the plugin is pre-1.0 and the first release has not been cut |
| **Minor** | The plugin can do something it could not do before: a new inspection, a new editor surface, a new Solr line, **or support for one more framework** |
| **Patch** | The same capabilities, behaving more correctly. A wrong URL resolved right, a false positive silenced, a link that pointed at the wrong guide |

### One framework per minor release

**Support for a framework's configuration is never bundled with another framework's.** Spring Boot,
Quarkus, Micronaut and MicroProfile each land in their own minor version, in
[their own plan steps](../../specs/plans/0002-solr-intellij-plugin-plan.md#step-18-framework-configuration-the-shared-half-and-spring-boot).

Two reasons, and the second is the one that matters:

- **A user can say which version understands their project.** "Quarkus support arrived in 0.4.0" is a
  sentence someone can act on. "Framework support arrived in 0.3.0" is not, because it is only true for
  whichever framework happened to work.
- **A framework resolver is either right or it silently offers a wrong URL**, and four of them shipping
  together makes that indistinguishable. A profile-precedence bug in the Micronaut resolver looks like
  a perfectly working plugin to everyone using Spring — same version, same changelog line. Split, the
  fix is a patch naming one framework, rather than a question about which of four resolvers regressed.

**A plain Java application using SolrJ is supported before any framework is**, which is why
[the recognizer interface and SolrJ](../../specs/plans/0002-solr-intellij-plugin-plan.md#step-16-recognizer-interface-and-solrj)
is a step of its own and comes first. A framework recognizer resolves a URL that a SolrJ client then
uses; without the plain case underneath it, there is nothing to hand the resolved URL to.

## The mechanism, which is two stages and one manual step

Releases here follow the IntelliJ Platform Plugin Template's shape:

| Stage | Trigger | What happens |
|---|---|---|
| **Draft** | every push to `main` | `build.yml`'s `releaseDraft` job deletes old drafts and creates a new one, named for `version` in `gradle.properties` |
| **Publish** | a human publishes the draft on GitHub | Creates the git tag and the public release page |
| **Marketplace** | the published release fires `release.yml` | Runs `patchChangelog`, then `./gradlew publishPlugin`, then uploads the ZIP as a release asset |

**The draft is not a decision.** It is rebuilt on every push to `main`, so its existence means the
last build succeeded and nothing more. Publishing it is the decision, and it is irreversible in the
way that matters: the tag becomes public, and a version number that has been on the Marketplace
cannot be reused.

**A version can only be published to the Marketplace by hand the first time.** This is JetBrains'
rule, not this project's — *"The first plugin publication must always be uploaded manually."*
`publishPlugin` works for every version after that. So the first upload — 0.2.0, as things stand — is a person on
[the Marketplace upload page](https://plugins.jetbrains.com/plugin/add), whatever the workflow says.

## Preflight: what must be true before a Marketplace release

Three things are missing today, all of them accounts and credentials. A fourth — who the listing says published
the plugin — was repository content, and is settled.

### Settled: the name and the vendor

`plugin.xml` used to read `<name>Apache Solr</name>` and `<vendor>Apache Software Foundation</vendor>`,
while its own description says the project is *"not affiliated with or endorsed by the Apache
Software Foundation."* Both could not be true, and `<vendor>` is the one the Marketplace displays as
**the publisher**. They now read **Solr Support** and **Aditya Parikh**: a name that describes the
relationship rather than claiming the mark, which is the shape
[the ASF trademark policy](https://www.apache.org/foundation/marks/) expects of third-party software.

**The icon has not had the same look, and it is the mark.** Since `34fb335` the plugin icon is the
sunburst disc from Apache Solr's own logo, and the tool window's icon is the same shape in one grey.
An earlier revision of this paragraph said the icon was drawn *not* to be the Solr logo; that
described the angle-bracket icon it replaced. Whether a third-party plugin may carry the mark is the
same trademark question the name and vendor were changed for, and it is the owner's to settle before
the first Marketplace upload — keeping it is a decision to make rather than to inherit.

**The plugin ID has not had the same look.** `<id>` is still `org.apache.solr.ide`, a reversed
domain the project does not own. Nobody installing sees it, but the Marketplace keeps it forever:
the first upload claims it, and after that it cannot change without publishing a different plugin.
Changing it is one line; deciding to is the owner's call, and the first upload is the deadline.

### 1. There is no signing configuration

`build.gradle.kts` has no `signing` block, and the JetBrains documentation is explicit that a plugin
must be signed before publication. Signing needs a certificate chain and a private key, which the
IntelliJ Platform Gradle Plugin reads from the `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and
`PRIVATE_KEY_PASSWORD` properties. [JetBrains' signing guide](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
covers generating them.

### 2. The publish secrets do not exist

`release.yml` reads four repository secrets. Only `SONAR_TOKEN` is configured today, so the publish
step would fail on an empty token:

| Secret | For |
|---|---|
| `PUBLISH_TOKEN` | the Marketplace API, from your JetBrains account |
| `CERTIFICATE_CHAIN` | signing |
| `PRIVATE_KEY` | signing |
| `PRIVATE_KEY_PASSWORD` | signing |

### 3. A JetBrains account owns the plugin ID

`org.apache.solr.ide` has to be registered to the account that will publish it. That happens as part
of the manual first upload.

**Until all three are settled, publishing the draft produces a real GitHub release and a failed
`release.yml` run.** That is not harmful — the release page and its ZIP are genuine and installable —
but the red run on the repository means the Marketplace step did not happen, and anyone reading the
Actions tab should know why.

## Cutting a GitHub-only release

This is available today and needs none of the three above.

1. **Decide the version.** `version` in `gradle.properties`. The changelog's top section must match
   it, and [the compatibility matrix](../compatibility.md) names the version in its heading.
2. **Make sure the changelog says what shipped.** `<change-notes>` is rendered *from* `CHANGELOG.md`
   by `patchPluginXml`, so the changelog is the only copy and the descriptor follows it.
3. **Merge everything intended for the release**, and let the push to `main` rebuild the draft.
4. **Check the draft** on the releases page: right version, and notes that read as release notes
   rather than as commit subjects.
5. **Publish it.** The tag is created at that moment.
6. **Expect `release.yml` to fail** until the preflight is done, and say so in the release notes so a
   reader is not left guessing whether the plugin reached the Marketplace.

## Cutting a Marketplace release

Once the preflight is settled, the first time only:

1. `./gradlew buildPlugin` — the ZIP lands in `build/distributions/`.
2. `./gradlew verifyPlugin` — the same gate CI runs, against the IDEs in `verifiedIdeBuilds`.
3. Upload the ZIP at [plugins.jetbrains.com/plugin/add](https://plugins.jetbrains.com/plugin/add) and
   claim the plugin ID.
4. Wait for the listing to go live.

Every version after that: publish the GitHub draft and let `release.yml` do it.

## Verifying a release did what it claimed

- **The tag exists and points where you think.** `git tag -l` and `git show <tag> --stat`.
- **The ZIP is on the release page** and its version matches the tag.
- **`verifyPlugin` passed in CI for that commit** — the Verify job, not just Build and Test.
- **The Marketplace listing shows the change notes** from the changelog, not the placeholder.

## If it goes wrong

**A bad draft** can simply be deleted; the next push to `main` builds another.

**A published GitHub release** can be deleted along with its tag, and should be if it was published in
error and nobody has installed it. Once it has been downloaded, prefer a new version over rewriting
history.

**A published Marketplace version cannot be unpublished or replaced** — that number is spent. The fix
is to publish the next one, which is why the preflight is worth doing slowly.
