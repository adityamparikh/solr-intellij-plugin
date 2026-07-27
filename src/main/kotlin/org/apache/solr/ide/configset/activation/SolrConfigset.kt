package org.apache.solr.ide.configset.activation

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile

/**
 * One Solr configset: the directory holding a schema, a `solrconfig.xml` and their companions.
 *
 * Identity in this plugin is per-configset rather than per-file. Fields, field types and analyzer
 * chains are properties of the directory as a whole — `schema.xml` declares a field that
 * `solrconfig.xml` names in a handler's `qf`, and neither file means anything without the other.
 * A project may contain several configsets (a multi-core layout, or an application vendoring more
 * than one), which must not be merged into a single model, so every file that activates the plugin
 * also resolves to the configset that owns it.
 *
 * @property root the directory holding the configset's files, conventionally named `conf`
 */
data class SolrConfigset(val root: VirtualFile) {

    /**
     * A display name for this configset.
     *
     * The root directory is conventionally called `conf` and sits inside a directory named for the
     * core or collection, so `conf` alone would label every configset in a multi-core project
     * identically. In that case the parent's name is used, which is the name the user thinks in.
     */
    val name: String
        get() = if (root.name == CONVENTIONAL_ROOT_NAME) root.parent?.name ?: root.name else root.name

    /**
     * Whether [file] lives inside this configset.
     *
     * The root counts as containing itself, so the configset directory passed back in is recognized
     * as its own member.
     *
     * @param file the file or directory to test
     * @return true if [root] is an ancestor of [file], or is [file] itself
     */
    fun contains(file: VirtualFile): Boolean = FileUtil.isAncestor(root.path, file.path, false)

    /** Conventions shared with [SolrConfigsetDetector]. */
    companion object {
        /** The directory name Solr conventionally gives a configset root. */
        const val CONVENTIONAL_ROOT_NAME: String = "conf"
    }
}
