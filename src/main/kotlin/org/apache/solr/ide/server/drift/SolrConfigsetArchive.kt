package org.apache.solr.ide.server.drift

import com.intellij.openapi.vfs.VirtualFile
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A configset directory, zipped the way Solr's ConfigSets API expects it.
 *
 * **Entries are relative to the configset root, with no wrapping directory.** Solr unpacks the
 * archive as the configset's contents, so a zip made from the parent — `conf/managed-schema.xml`
 * rather than `managed-schema.xml` — uploads without complaint and produces a configset Solr cannot
 * build a collection from. That failure arrives much later, as a core creation error naming a
 * missing schema, and by then nothing points back at how the zip was made.
 */
object SolrConfigsetArchive {

    /**
     * [root] and everything under it, as a zip.
     *
     * Directories are not written as entries: Solr reads the paths, and a zip of files alone
     * reconstructs the tree. Anything unreadable is skipped rather than failing the whole archive —
     * a configset directory can hold an editor's lock file or a stale symlink, and refusing to
     * upload because of one would be refusing over something no Solr will ever read.
     *
     * @param root the configset directory, conventionally named `conf`
     * @return the archive's bytes
     */
    fun of(root: VirtualFile): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            filesUnder(root).forEach { file ->
                val path = pathOf(root, file) ?: return@forEach
                val content = runCatching { file.contentsToByteArray() }.getOrNull() ?: return@forEach
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /**
     * Every file under [root], deepest last, in a stable order.
     *
     * Ordered so two uploads of an unchanged directory produce identical bytes — which is not
     * something Solr cares about, but is what lets a test say the archive is a function of the
     * directory rather than of the filesystem's mood.
     */
    private fun filesUnder(root: VirtualFile): List<VirtualFile> =
        root.children.orEmpty().sortedBy { it.name }.flatMap { child ->
            if (child.isDirectory) filesUnder(child) else listOf(child)
        }

    private fun pathOf(root: VirtualFile, file: VirtualFile): String? {
        val rootPath = root.path.trimEnd('/') + "/"
        return file.path.removePrefix(rootPath).takeIf { it != file.path && it.isNotEmpty() }
    }
}
