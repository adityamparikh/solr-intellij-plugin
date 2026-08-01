# Match-capability intentions: generating the companion field everybody re-derives

## Problem

The plugin tells a reader that `description` cannot do prefix matching, and then leaves them to fix
it from memory.

Match analysis, the inline hint and quick documentation all landed together and all of them
*describe*. Nothing acts. A reader who has just learned that their field does not support the search
they thought it did has to go and write the remedy by hand, and the remedy is a three-part pattern —
a field type with an edge-n-gram filter, a companion field using it, and a copy rule to populate it —
that almost nobody writes from memory. It gets copied off a blog post, and the copy is frequently
wrong in one specific way described below.

**Nothing in `src/main` implements `IntentionAction`.** This is the whole of Step 7 action 3, and the
last criterion holding that step open.

**The demo depends on it.** Demo step 33 puts the cursor on `description`, presses Alt-Enter and
takes a fix that generates the companion field and its copy rule. It is the payoff for steps 28 to
32, which spend five steps establishing that the audience's mental model of matching is wrong. A demo
that diagnoses and cannot treat is a worse demo than one that never raised the subject.

## Goals

- Generate the prefix-matching pattern from the field that lacks it, in one gesture.
- Reuse a field type the schema already declares, rather than adding a second one that does the same
  job.
- Where no such type exists, write one that is correct — in particular, correct in the asymmetry
  described under [the recipe](#the-generated-recipe).
- Offer nothing at all where the plugin cannot be sure, on the same terms match analysis already
  uses.

## Non-goals

- **The `_exact` companion.** It is the sibling half of Step 7 action 3 and ships next, reusing this
  work's naming, insertion and copy-rule code. Splitting them follows how the inspections landed —
  one capability per pull request — and the prefix half is the one the demo needs.
- **Tuning the generated type.** `minGramSize` and `maxGramSize` are written at conventional values
  and are not derived from anything. A user who wants different bounds edits the file, which is what
  the file is for.
- **Query-side changes.** Populating a companion field does not make anything search it. Adding it to
  a request handler's `qf` is a separate decision the plugin does not make on the user's behalf.
- **`solrconfig.xml`.** Untouched.

## Design

### An intention, not an inspection quick-fix

A field without prefix support is **correct**. Underlining it would be the plugin manufacturing a
problem in order to have somewhere to attach a fix, and the standing rule is that inspections do not
fire on correct files.

[Showing that an attribute restates the default](../../../specs/plans/0002-solr-intellij-plugin-plan.md)
already settled this exact question for its own feature, and the reasoning transfers unchanged: the
platform's idiom for "true but improvable" is an intention, because an intention carries no claim
that anything is wrong. Nothing underlines, and nothing appears in the Problems view.

New package `org.apache.solr.ide.configset.intention`, which gets its `# Package` section in
`docs/Module.md` as it becomes real.

### When the intention offers itself

The caret is inside a `<field>` element in a schema file, and every one of these holds:

| Condition | Reason |
|---|---|
| the field and its type resolve in the model | there is nothing to reason about otherwise |
| `SolrMatchAnalysis.of(type).prefix == NONE` | the field already has prefix support; nothing to add |
| the capability is `confident` | an unrecognized factory means the chain was not understood |
| `<name>_prefix` is not already declared | do not invent `description_prefix2` |
| in the generation case only: `text_prefix` is free, or names a type that is already edge-n-gram-backed | writing a second type under a taken name is not an option, and silently renaming it is worse |

Failing any of them, the intention does not appear. No greyed-out entry and no explanation, which is
the platform's own convention for an intention that does not apply.

**`indexed="true"` is deliberately not required of the source field**, and the reason is worth
recording because the guard is the obvious thing to add. `copyField` copies the *incoming document
value*, before analysis, so the source's own `indexed` and `stored` settings do not affect what the
destination receives. A display-only field —

```xml
<field name="description" type="text_general" indexed="false" stored="true"/>
```

— is precisely where the pattern is cleanest: retrieve from `description`, search against
`description_prefix`. A plausible-sounding guard would have suppressed the feature on the schema that
most wants it, which is the zero-false-positive failure mode pointing the other way.

### Reuse before generation

`SolrMatchAnalysis` already answers *which of this schema's declared field types are backed by an
edge n-gram*, because classifying a chain is exactly what it does. So the type is chosen, not
invented, whenever the schema offers one — and the intention names it, so the reader sees what they
are about to get:

> Add prefix-capable companion field (`text_prefix`)

A schema declaring more than one such type is rare; the first in document order wins, and naming it
in the intention text is what keeps that from being a silent choice.

Only when the schema declares none does the intention write one, and it says so:

> Add prefix-capable companion field and its type

### The generated recipe

```xml
<fieldType name="text_prefix" class="solr.TextField" positionIncrementGap="100">
  <analyzer type="index">
    <tokenizer class="solr.StandardTokenizerFactory"/>
    <filter class="solr.LowerCaseFilterFactory"/>
    <filter class="solr.EdgeNGramFilterFactory" minGramSize="2" maxGramSize="15"/>
  </analyzer>
  <analyzer type="query">
    <tokenizer class="solr.StandardTokenizerFactory"/>
    <filter class="solr.LowerCaseFilterFactory"/>
  </analyzer>
</fieldType>
```

**The asymmetry is the point, and it is the part hand-written copies get wrong.** The n-gram filter
appears on the index side only. Put it on the query side too and a search for `wid` is itself ground
into `wi` and `wid`, both of which match a large fraction of the index; relevance collapses and the
symptom is reported as "search is broken" rather than as a schema bug. Generating this correctly is
worth more than generating the field, because the field is the part a person gets right unaided.

`2` and `15` are conventional bounds rather than derived ones: below two, a single character matches
most of the index; above fifteen, prefixes nobody types are being stored. The name `text_prefix`
follows the same convention and is only used when the schema has no type of that name.

### What is added, and where

```xml
<field name="description_prefix" type="text_prefix" indexed="true" stored="false"/>
<copyField source="description" dest="description_prefix"/>
```

`stored="false"` because a companion exists to be matched against, not retrieved — the original field
is still there for that, and storing both doubles the index for nothing.

Schemas are conventionally written in blocks, and the demo configset is, so each new element joins
its own:

| Element | Insertion point |
|---|---|
| `<field>` | immediately after the source field, so the pair reads together |
| `<copyField>` | after the last existing copy rule, or at the end of `<schema>` if there are none |
| `<fieldType>` | after the last existing field type — generation case only |

The file is edited directly and without confirmation, per the rule that the plugin never refuses a
write.

## Testing strategy

**Type selection is a pure function and is tested as one.** "Which of these declared types are
edge-n-gram-backed" reads the model and imports nothing from the platform, so it is plain JUnit 4
with backtick names and no fixture.

**Everything else is a `BasePlatformTestCase` fixture**, asserting the file text after the intention
runs — reuse and generation, and that the result parses to a model whose companion field carries
`EDGE_NGRAM`.

**The negative cases carry more weight than the positive one.** An intention that appears where it
should not is worse than one that is missing, because the user acts on it. One test per availability
rule: already prefix-capable, companion name taken, capability not confident, and the caret on
something that is not a field.

## Registration

`<intentionAction>` in `plugin.xml`, implementing the `DumbAware` marker interface — the mechanism
differs by extension point, and this is the marker-interface kind rather than the `isDumbAware()`
kind.

The platform requires description files at `intentionDescriptions/<ClassName>/description.html` with
`before.xml.template` and `after.xml.template` beside them. Same shape as the inspection description
files already written, and [CI gates](../../../specs/plans/0002-solr-intellij-plugin-plan.md) will
want them regardless.

## Risks

- **The generated type collides with a name already in use.** Carried as the fifth availability rule
  above: if `text_prefix` names a type that is *not* edge-n-gram-backed, the generation path cannot
  use the name, and the intention declines rather than suffixing. Note that this case is narrow — a
  schema whose `text_prefix` *is* edge-n-gram-backed never reaches generation, because reuse would
  have found it first.
- **A schema with several edge-n-gram types picks the wrong one.** Mitigated by naming the chosen type
  in the intention text, so the choice is visible before it is taken rather than after.
- **The conventional gram bounds suit nobody in particular.** Accepted. They are a starting point in a
  file the user owns and edits, not a claim about their data.

## Delivery

This is **Step 7 action 3**, less its `_exact` half, and it closes the last open criterion on
[match hints and quick-fixes](../../../specs/plans/0002-solr-intellij-plugin-plan.md) once the
sibling lands. Action 4 — edit the file directly, no provenance check — is a constraint on this work
rather than separate work, so it lands here.

**Acceptance:** demo step 33. Cursor on `description`, Alt-Enter, take the fix, and the companion
field and its copy rule appear.
