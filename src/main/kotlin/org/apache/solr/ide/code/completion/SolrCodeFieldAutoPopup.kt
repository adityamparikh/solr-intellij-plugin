package org.apache.solr.ide.code.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import org.apache.solr.ide.code.SolrRecognizers
import org.apache.solr.ide.code.solrj.SolrJFieldPositions
import org.jetbrains.uast.UastFacade

/**
 * Whether the caret, in a file already committed, sits where the code completion offers field names.
 *
 * **One question for both halves of the auto-popup**, and it is the completion contributor's own:
 * [SolrJFieldPositions.fieldSlotAt]. A popup scheduled where the contributor then declines is a
 * flicker, and one suppressed where it would have offered is the defect this exists to fix.
 *
 * **Guarded against indexing, because the answer resolves a call.** Deciding that `addFilterQuery` is
 * SolrJ's means resolving the receiver, which reads an index; during indexing this says no rather
 * than throwing on a keystroke. Nothing is lost — the contributor offers nothing then either, since
 * the configsets are found through an index too.
 */
private fun isFieldPosition(file: PsiFile, element: PsiElement?, caretOffset: Int): Boolean {
    if (element == null) return false
    if (UastFacade.findPlugin(file.language) == null) return false
    if (DumbService.isDumb(file.project)) return false
    if (!SolrRecognizers.recognizeSolrIn(file)) return false
    return SolrJFieldPositions.fieldSlotAt(element, caretOffset) != null
}

/**
 * Lets the completion popup open by itself while a field name is typed into a SolrJ string.
 *
 * **Why it did not.** Typing a letter always schedules the popup; what then cancels it inside a
 * string is the Java and Kotlin plugins' `SkipAutopopupInStrings`, which says a string is prose and
 * a popup there is an interruption. That is right for a log message and wrong for
 * `addFilterQuery("cat`, where a field name is exactly what is being typed — so the sandbox pass
 * found completion that worked on Ctrl-Space and never appeared unasked.
 *
 * **Overrides only where a field name goes, and says nothing elsewhere.** It answers
 * [ThreeState.NO] — do not skip — at a field position, and [ThreeState.UNSURE] everywhere else, so
 * every other string in the file is left to the language's own rule. That includes the value half of
 * a clause: after `category:` the popup stays suppressed, because the contributor has nothing to
 * offer there. Registered ahead of both languages' string rules, since the first definite answer wins.
 */
class SolrCodeFieldCompletionConfidence : CompletionConfidence() {

    /**
     * [ThreeState.NO] where a field name is being typed, [ThreeState.UNSURE] everywhere else.
     *
     * @param editor the editor being typed in
     * @param contextElement the element just before the caret
     * @param psiFile the committed file
     * @param offset the caret offset
     * @return whether to skip the popup, where this can say
     */
    override fun shouldSkipAutopopup(
        editor: Editor,
        contextElement: PsiElement,
        psiFile: PsiFile,
        offset: Int,
    ): ThreeState = if (isFieldPosition(psiFile, contextElement, offset)) ThreeState.NO else ThreeState.UNSURE
}

/**
 * Opens the popup after a comma in a SolrJ field list, where the next name starts.
 *
 * A letter schedules the popup already — [SolrCodeFieldCompletionConfidence] only has to stop the
 * string rule cancelling it. A comma schedules nothing, and in `setFields("id,|")` it is the moment
 * the next name begins, so it is scheduled here, on the condition that the caret — once the comma is
 * in — is at a field position. The condition is what keeps every other comma in every Java file
 * exactly as it was.
 */
class SolrCodeFieldTypedHandler : TypedHandlerDelegate() {

    /**
     * Schedules the popup for a comma typed into a field-naming string.
     *
     * @param charTyped the character about to be inserted
     * @param project the project
     * @param editor the editor
     * @param file the file being typed in
     * @return always [TypedHandlerDelegate.Result.CONTINUE]: this adds a popup and prevents nothing
     */
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != ',' || UastFacade.findPlugin(file.language) == null) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor, CompletionType.BASIC) { committed ->
            val caret = editor.caretModel.offset
            isFieldPosition(committed, committed.findElementAt(caret - 1), caret)
        }
        return Result.CONTINUE
    }
}
