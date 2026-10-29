# Extension points

Everything this plugin contributes to the IDE is registered in one file,
[`plugin.xml`](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/src/main/resources/META-INF/plugin.xml).
This page is the map from *a thing you see in the IDE* to *the class that provides it*, so you can
find where to start reading. The classes themselves are in the [API reference](api-reference.md).

## What the plugin depends on

| Dependency | Why |
|---|---|
| `com.intellij.modules.platform`, `com.intellij.modules.xml` | The platform and XML support every feature is built on. |
| `com.intellij.modules.java` | Java and Kotlin field-name checking, which reads SolrJ calls. |
| `com.jetbrains.restClient` | The HTTP Client, where generated Solr requests run and are rendered. |

## What is registered

### Inspections

Twelve `localInspection` registrations. What each one reports, its registered severity, and what it
stays silent about is in the [inspection catalog](inspection-catalog.md).

| Inspection | Applies to |
|---|---|
| `SolrDanglingCopyFieldInspection` | `managed-schema.xml` |
| `SolrUnknownFieldTypeInspection` | `managed-schema.xml` |
| `SolrUnusedFieldTypeInspection` | `managed-schema.xml` |
| `SolrAnalyzerChainOrderInspection` | `managed-schema.xml` |
| `SolrUnknownFieldReferenceInspection` | `solrconfig.xml` |
| `SolrNonIndexedRelevanceFieldInspection` | `solrconfig.xml` |
| `SolrUnsupportedFieldOperationInspection` | `solrconfig.xml` |
| `SolrDiscontinuedElementInspection` | `solrconfig.xml` |
| `SolrMisspelledParameterInspection` | `solrconfig.xml` |
| `SolrUnknownAttributeInspection` | Either file |
| `SolrInvalidAttributeValueInspection` | Either file |
| `SolrUnknownCodeFieldInspection` | Java and Kotlin using SolrJ |

### Completion

| Registration | Class | Where it applies |
|---|---|---|
| `completion.contributor` | `SolrSchemaCompletionContributor` | XML: `managed-schema.xml` |
| `completion.contributor` | `SolrConfigCompletionContributor` | XML: `solrconfig.xml` |
| `completion.contributor` | `SolrCodeFieldCompletionContributor` | Java and Kotlin |
| `completion.contributor` | `SolrQueryFieldCompletionContributor` | JSON in an `.http` request body: field names, from the collection the request names |
| `completion.contributor` | `SolrDocumentFieldCompletionContributor` | The test-document editor only: field names from the target collection's schema |
| `completion.confidence`, `typedHandler` | `SolrCodeFieldCompletionConfidence`, `SolrCodeFieldTypedHandler` | Let the popup open by itself while a field name is typed in a Java or Kotlin string, where the platform would otherwise cancel it |

### Reading, navigation and documentation

| Registration | Class | What it gives you |
|---|---|---|
| `psi.referenceContributor` | `SolrSchemaReferenceContributor`, `SolrConfigReferenceContributor` | Names in the configset that navigate to what they name |
| `psi.referenceContributor` | `SolrClassReferenceContributor` | `class="solr.…"` values that navigate to the implementing class |
| `psi.referenceContributor` | `SolrCodeFieldReferenceContributor` | A field name in Java or Kotlin that navigates to its declaration |
| `referencesSearch` | `SolrDynamicFieldSearcher` | Finds usages of a dynamic field that its pattern supplies but never spells, such as `body_t` under `*_t` |
| `lang.documentationProvider` | `SolrSchemaDocumentationProvider`, `SolrConfigDocumentationProvider` | Quick documentation |
| `xml.elementDescriptorProvider` | `SolrSchemaElementDescriptorProvider`, `SolrConfigElementDescriptorProvider` | Element and attribute descriptors, since neither file has an XSD; deliberately permissive, with validation left to the inspections |
| `codeInsight.declarativeInlayProvider` | `SolrMatchInlayHintsProvider` | The inline "what can this field match" hints |
| `pom.declarationSearcher` | `SolrDeclarationSearcher` | Makes a schema declaration something Find Usages and rename can start from |
| `elementDescriptionProvider`, `usageTypeProvider` | `SolrDeclarationDescriptionProvider`, `SolrUsageTypeProvider` | What the Find Usages header and each usage are called, in place of the platform's fallbacks |

### Highlighting

| Registration | Class | What it gives you |
|---|---|---|
| `annotator` | `SolrRestatedDefaultAnnotator` | Dims an attribute that restates Solr's own default |
| `annotator` | `SolrQueryHighlightAnnotator` | Colours a query's fields and operators in a code string |
| `colorSettingsPage` | `SolrQueryColorSettingsPage` | Where those colours are configured |

### The server side

| Registration | Class | What it gives you |
|---|---|---|
| `toolWindow` | `SolrCollectionsToolWindowFactory` | The **Solr** tool window: collections, shards, replicas, fields, and the **Drift** tab |
| `projectConfigurable` | `SolrConnectionsConfigurable` | Settings → Tools → Solr Connections |
| `projectConfigurable` | `SolrConfigsetsConfigurable` | Settings → Languages & Frameworks → Solr Configsets |
| `fileEditorProvider` | `SolrQueryScreenEditorProvider` | The query console |
| `codeInsight.lineMarkerProvider` | `SolrRunQueryLineMarkerProvider` | The gutter icon that runs a query from code |
| `httpClient.addRequestTemplateProvider` | `SolrRequestTemplateProvider` | The Solr starting requests in the HTTP Client's *Add Request* menu |
| `httpClient.responseCustomPresentation` | `SolrQueryResponsePresentation` | The readable summary of a Solr response |
| `action` | `SolrMarkConfigsetRootAction` | *Mark Directory as Solr Configset Root* |

## The rule every registration follows

Every contribution declares itself **dumb-aware**, which is a promise about data sources: almost
nothing here reads an index, because a configset is text. The one thing that does, class navigation
through `JavaPsiFacade`, must both decline the declaration and guard with `DumbService`.

The mechanism differs by extension point: inspections and completion override `isDumbAware()`, while
documentation and inlay providers implement the `DumbAware` marker interface. The
[platform mechanisms](platform-mechanisms.md) page explains what dumb mode is and what this plugin
decided about it.

## Adding one

[Adding an editor feature](how-to/add-an-editor-feature.md) walks through an inspection,
completion, a reference, documentation and a hint, and [code organization](code-organization.md)
says which package each belongs in and what that package may import.
