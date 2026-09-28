package org.apache.solr.ide.configset.activation

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager

/**
 * Makes every open editor re-read its file after what counts as a configset has changed.
 *
 * **A daemon restart alone redraws the highlighting and leaves the inlay hints as they were.** The
 * platform's inlay-hint pass skips a file whose PSI modification count has not moved since it last
 * ran, and marking a root, unmarking one or switching recognition changes no PSI. The first sandbox
 * pass over these gestures found the result: an unmarked file, plain XML again, still showed the Solr
 * hint it had while marked, until its tab was switched. Dropping the PSI caches moves that count, so
 * the restart that follows runs every pass, hints included.
 *
 * Dropping them is not free, which is why this is called from the gestures that change activation —
 * rare, and deliberate — and never from anything that runs as the user types.
 */
object SolrActivationRefresh {

    /**
     * Re-reads every open file in [project], hints included.
     *
     * Must run on the EDT, as the settings page's apply and an action's perform both do.
     *
     * @param project the project whose activation changed
     */
    fun afterActivationChanged(project: Project) {
        PsiManager.getInstance(project).dropPsiCaches()
        DaemonCodeAnalyzer.getInstance(project).restart()
    }
}
