package org.apache.solr.ide.server.query.screen

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Opens a query console tab in its editor.
 *
 * **`accept` runs for every file the IDE opens**, so it is a bare check of the file's class and
 * reads nothing. Dumb-aware because the console reads no index: its form is a committed file and
 * its queries go to a server.
 */
internal class SolrQueryScreenEditorProvider : FileEditorProvider, DumbAware {

  override fun accept(project: Project, file: VirtualFile): Boolean = file is SolrQueryScreenFile

  override fun acceptRequiresReadAction(): Boolean = false

  override fun createEditor(project: Project, file: VirtualFile): FileEditor =
    SolrQueryScreenEditor(project, file as SolrQueryScreenFile)

  override fun getEditorTypeId(): String = EDITOR_TYPE

  // The tab is nothing but the console; a text editor beside it would show an empty light file.
  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_OTHER_EDITORS

  private companion object {
    const val EDITOR_TYPE = "solr-query-console"
  }
}
