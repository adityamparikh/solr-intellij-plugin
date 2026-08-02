# Design records

One-off design records for a single feature or decision — smaller-grained than the
overall spec and plan in `specs/`, which cover the whole plugin.

- **`pending/`** — a design that has been written but not yet folded into
  `specs/plans/0002-solr-intellij-plugin-plan.md` as a step. Not started.
- **`archive/<feature-name>/`** — a design (`design.md`) and, if the feature was
  large enough to need one, an implementation plan (`plan.md`), kept together once the
  feature has shipped. Historical record, not living documentation — if the described
  behavior and the code disagree, the code is right.

`specs/0002-solr-intellij-plugin.md` owns current intent, and
`specs/plans/0002-solr-intellij-plugin-plan.md` alone owns implementation status and
order — including for the features described here. A record moves from `pending/` to
`archive/` when its feature ships; that move follows the plan rather than reporting it,
so the directory a record sits in is not a second place to read status from.
