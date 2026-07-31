# Design records

One-off design records for a single feature or decision — smaller-grained than the
overall spec and plan in `specs/`, which cover the whole plugin.

- **`pending/`** — a design that has been written but not yet folded into
  `specs/plans/0002-solr-intellij-plugin-plan.md` as a step. Not started.
- **`archive/<feature-name>/`** — a design (`design.md`) and, if the feature was
  large enough to need one, an implementation plan (`plan.md`), kept together once the
  feature has shipped. Historical record, not living documentation — if the described
  behavior and the code disagree, the code is right.

`specs/0002-solr-intellij-plugin.md` and its plan are the only documents that own
current intent and status. A record moves from `pending/` to `archive/` when its
feature merges, not before.
