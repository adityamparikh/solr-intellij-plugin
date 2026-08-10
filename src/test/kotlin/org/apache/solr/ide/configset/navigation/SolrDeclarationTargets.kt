package org.apache.solr.ide.configset.navigation

import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.PsiElement

/**
 * Whether [element] is the target this plugin contributes, as opposed to one the platform had.
 *
 * **Asserting merely that a target exists is weaker than it looks, and shared here so that the
 * weaker form stops being written by accident.** The platform answers some carets in a schema with
 * a target of its own — an attribute name resolves to the enclosing tag through the descriptor
 * machinery, which `SolrDeclarationTargetTest` pins deliberately. A test that only checks for
 * non-null would therefore stay green if [SolrDeclarationSearcher] stopped contributing anything at
 * all, which is the regression those tests exist to catch.
 *
 * @param element the target a caret resolved to, or null
 * @return true when it is a Solr declaration target
 */
internal fun isSolrDeclarationTarget(element: PsiElement?): Boolean =
    (element as? PomTargetPsiElement)?.target is SolrDeclarationTarget
