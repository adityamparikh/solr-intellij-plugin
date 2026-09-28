package org.apache.solr.ide.code.spring

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.module.Module
import com.intellij.spring.profiles.SpringProfilesService

/**
 * Which Spring profiles the user made active in the IDE, where something can say.
 *
 * **An extension point so that the one class touching the Spring plugin is loaded only with it.**
 * The Spring plugin is an optional dependency: the plugin must load and discover endpoints without
 * it. Registering [SpringPluginActiveProfiles] from `solr-spring.xml`, which the platform reads only
 * when Spring is installed, keeps every reference to Spring's classes behind that condition — calling
 * `SpringProfilesService` directly from discovery would fail to link in an IDE without it.
 *
 * Without an implementation, discovery falls back to `spring.profiles.active` in the configuration.
 */
interface SpringActiveProfiles {

    /**
     * The profiles active for [module].
     *
     * @param module the module asked about
     * @return the active profiles, empty where the user has chosen none
     */
    fun activeProfiles(module: Module): Set<String>

    /** The registered implementations, and the answer they give together. */
    companion object {

        /** Where `solr-spring.xml` registers the Spring plugin's answer. */
        val EP_NAME: ExtensionPointName<SpringActiveProfiles> =
            ExtensionPointName.create("org.apache.solr.ide.springActiveProfiles")

        /**
         * The profiles something registered says are active, or null where nothing chose any.
         *
         * @param module the module asked about
         * @return the chosen profiles, or null to fall back to what the configuration declares
         */
        fun of(module: Module): Set<String>? =
            EP_NAME.extensionList.firstNotNullOfOrNull { it.activeProfiles(module).takeIf { set -> set.isNotEmpty() } }
    }
}

/**
 * The Spring plugin's answer: the profiles chosen in its editor notification or run configuration.
 *
 * Registered only from `solr-spring.xml`, and so loaded only where the Spring plugin is.
 */
class SpringPluginActiveProfiles : SpringActiveProfiles {

    /**
     * Asks the Spring plugin which profiles are active for [module].
     *
     * @param module the module asked about
     * @return the profiles it reports, empty where none are chosen
     */
    override fun activeProfiles(module: Module): Set<String> =
        SpringProfilesService.getInstance(module.project).getActiveProfiles(module)
}
