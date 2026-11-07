# Releasing the plugin

How to cut a version: a GitHub release people install by hand, or a JetBrains Marketplace listing
the IDE offers on its own. Which releases have shipped, and what each carries, is
[the plan's](../../specs/plans/0002-solr-intellij-plugin-plan.md) to say.

## What a version number means

| Bump | When |
|---|---|
| **Major** | A configset the plugin used to read stops being read, a setting stops being honoured, or a supported Solr line is dropped |
| **Minor** | The plugin can do something it could not do before: a new inspection, a new editor surface, a new Solr line, **or support for one more framework** |
| **Patch** | The same capabilities, behaving more correctly. A wrong URL resolved right, a false positive silenced, a link that pointed at the wrong guide |

**One framework per minor release.** Spring Boot, Quarkus, Micronaut and MicroProfile support each
land in their own minor version, so a user can say which version understands their project, and a
regression in one framework's resolver is a patch naming that framework. Plain SolrJ support comes
before any framework, because a framework resolver produces a URL that a SolrJ client then uses.

## The mechanism

| Stage | Trigger | What happens |
|---|---|---|
| **Draft** | every push to `main` | `build.yml`'s `releaseDraft` job deletes old drafts and creates a new one, named for `version` in `gradle.properties` |
| **Publish** | a human publishes the draft on GitHub | Creates the git tag and the public release page |
| **Marketplace** | the published release fires `release.yml` | Runs `patchChangelog`, then `./gradlew publishPlugin`, then uploads the ZIP as a release asset |

The draft is rebuilt on every push, so it only means the last build succeeded. Publishing it is the
decision: the tag becomes public, and a version number that has reached the Marketplace cannot be
reused.

JetBrains requires the first publication of a plugin to be uploaded by hand. `publishPlugin` works
for every version after that.

## Before the first Marketplace release

- **Signing.** JetBrains requires a signed plugin. The IntelliJ Platform Gradle Plugin reads the
  certificate chain and private key from the `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and
  `PRIVATE_KEY_PASSWORD` properties; `build.gradle.kts` needs a `signing` block that uses them.
  [JetBrains' signing guide](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) covers
  generating them.
- **Repository secrets** that `release.yml` reads:

  | Secret | For |
  |---|---|
  | `PUBLISH_TOKEN` | the Marketplace API, from your JetBrains account |
  | `CERTIFICATE_CHAIN` | signing |
  | `PRIVATE_KEY` | signing |
  | `PRIVATE_KEY_PASSWORD` | signing |

- **A JetBrains account that owns the plugin ID** (`<id>` in `plugin.xml`). The first upload claims
  it, and it cannot change afterwards without publishing a different plugin.

Without these, publishing the draft still produces a real, installable GitHub release, and
`release.yml` fails at the Marketplace step.

## Cutting a GitHub-only release

1. **Decide the version.** Set `version` in `gradle.properties`. The changelog's top section must
   match it, and [the compatibility matrix](../compatibility.md) names the version in its heading.
2. **Make sure the changelog says what shipped.** `patchPluginXml` renders `<change-notes>` from
   `CHANGELOG.md`, so the changelog is the only copy.
3. **Merge everything intended for the release**, and let the push to `main` rebuild the draft.
4. **Check the draft** on the releases page: the right version, and notes that read as release
   notes rather than as commit subjects.
5. **Publish it.** The tag is created at that moment.
6. If the Marketplace prerequisites are not in place, `release.yml` fails; say so in the release
   notes so readers know the plugin did not reach the Marketplace.

## Cutting a Marketplace release

The first time:

1. `./gradlew buildPlugin` — the ZIP lands in `build/distributions/`.
2. `./gradlew verifyPlugin` — the same gate CI runs, against the IDEs in `verifiedIdeBuilds`.
3. Upload the ZIP at [plugins.jetbrains.com/plugin/add](https://plugins.jetbrains.com/plugin/add) and
   claim the plugin ID.
4. Wait for the listing to go live.

Every version after that: publish the GitHub draft and let `release.yml` do it.

## Verifying a release

- **The tag exists and points where you think.** `git tag -l` and `git show <tag> --stat`.
- **The ZIP is on the release page** and its version matches the tag.
- **`verifyPlugin` passed in CI for that commit** — the Verify job, not just Build and Test.
- **The Marketplace listing shows the change notes** from the changelog, not the placeholder.

## If it goes wrong

- **A bad draft** can be deleted; the next push to `main` builds another.
- **A published GitHub release** can be deleted with its tag if nobody has installed it. Once it has
  been downloaded, publish a new version instead of rewriting history.
- **A published Marketplace version cannot be unpublished or replaced.** That number is spent; the
  fix is the next version.
