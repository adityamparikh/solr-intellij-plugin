package org.apache.solr.ide.code.spring

import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * A Spring Boot application's configuration files, read into property sources in Spring's order.
 *
 * **Read through the IDE's YAML and properties parsers, not through Spring's model.** The Spring
 * plugin's own resolver would bring imports and relaxed binding for free, but it is undocumented,
 * needs indexes and is absent without a Spring licence; these two parsers ship with every IDE this
 * plugin runs in and need neither. What they do not know — which file and which document belongs to
 * which profile — is the part Spring defines and this object states.
 *
 * **Looked for where Spring looks: the classpath root and `config/` beneath it**, which in the IDE
 * is each of the module's source and resource roots. No index is consulted, so this is safe to call
 * while the IDE is still indexing.
 */
object SpringConfigFiles {

    /**
     * Every property source in [module], in the order Spring applies them — later ones win.
     *
     * Profile-less files come first, from every location, then profile files; within one location
     * YAML comes before `.properties`, because Spring lets a `.properties` file override a YAML one
     * beside it.
     *
     * @param module the module whose configuration to read
     * @return its property sources, empty where it has no application configuration
     */
    fun sourcesIn(module: Module): List<SpringPropertySource> {
        val psiManager = PsiManager.getInstance(module.project)
        val files = ModuleRootManager.getInstance(module).sourceRoots
            .flatMap { root -> listOfNotNull(root, root.findChild(CONFIG_DIRECTORY)) }
            .flatMap { dir -> dir.children.filter { !it.isDirectory && APPLICATION_FILE.matches(it.name) }.sortedWith(YAML_FIRST) }
        val (profiled, plain) = files.partition { profileOfFileName(it.name) != null }
        return (plain + profiled).flatMap { file ->
            psiManager.findFile(file)?.let { sourcesOf(it, profileOfFileName(file.name)) }.orEmpty()
        }
    }

    private fun sourcesOf(file: PsiFile, fileProfile: String?): List<SpringPropertySource> {
        val documents = when (file) {
            is YAMLFile -> file.documents.map { document -> buildMap { flatten(document.topLevelValue, "", this) } }
            is PropertiesFile -> propertiesDocuments(file)
            else -> emptyList()
        }
        return documents.filter { it.isNotEmpty() }.map { properties ->
            SpringPropertySource(documentProfile(properties) ?: fileProfile, properties, file.name)
        }
    }

    /**
     * Flattens a YAML mapping into Spring's dotted keys, keeping scalars only.
     *
     * A sequence is skipped rather than read: `urls: [a, b]` is a list, and a list read as a single
     * value would offer a server whose URL is two URLs.
     */
    private fun flatten(value: Any?, prefix: String, into: MutableMap<String, String>) {
        val mapping = value as? YAMLMapping ?: return
        mapping.keyValues.forEach { entry: YAMLKeyValue ->
            val key = if (prefix.isEmpty()) entry.keyText else "$prefix.${entry.keyText}"
            when (val child = entry.value) {
                is YAMLMapping -> flatten(child, key, into)
                is YAMLScalar -> into[key] = child.textValue
                else -> Unit
            }
        }
    }

    /**
     * A `.properties` file's documents, split where Spring Boot splits one — on a `#---` or `!---`
     * line. The properties parser reads those as comments, so the split is made here by offset.
     */
    private fun propertiesDocuments(file: PropertiesFile): List<Map<String, String>> {
        val text = file.containingFile.text
        val separators = DOCUMENT_SEPARATOR.findAll(text).map { it.range.first }.toList()
        return file.properties.groupBy { property ->
            val offset = property.psiElement.textRange.startOffset
            separators.count { it < offset }
        }.toSortedMap().values.map { properties ->
            properties.mapNotNull { property -> property.key?.let { key -> key to property.value.orEmpty() } }.toMap()
        }
    }

    private fun documentProfile(properties: Map<String, String>): String? =
        (properties[ON_PROFILE_KEY] ?: properties[LEGACY_PROFILE_KEY])?.trim()?.takeIf { it.isNotEmpty() }

    private fun profileOfFileName(name: String): String? = APPLICATION_FILE.matchEntire(name)?.groups?.get(1)?.value

    private val YAML_FIRST = compareBy<VirtualFile> { it.extension == "properties" }.thenBy { it.name }

    /** `application.yml`, `application-dev.properties` and the like; the profile is group 1. */
    private val APPLICATION_FILE = Regex("""application(?:-([^.]+))?\.(?:ya?ml|properties)""")
    private val DOCUMENT_SEPARATOR = Regex("""(?m)^[#!]---\s*$""")
    private const val CONFIG_DIRECTORY = "config"
    private const val ON_PROFILE_KEY = "spring.config.activate.on-profile"
    private const val LEGACY_PROFILE_KEY = "spring.profiles"
}
