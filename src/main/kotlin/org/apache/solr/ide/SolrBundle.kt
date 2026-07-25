package org.apache.solr.ide

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.SolrBundle"

/**
 * Localizable messages for the Solr plugin, backed by `messages/SolrBundle.properties`.
 */
object SolrBundle : DynamicBundle(BUNDLE) {

    /**
     * The message for [key], with [params] substituted into its placeholders.
     *
     * @param key a key present in `SolrBundle.properties`; checked at compile time
     * @param params values for the message's `{0}`-style placeholders
     * @return the localized message
     */
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getMessage(key, *params)

    /**
     * A lazily resolved supplier of the message for [key].
     *
     * Preferred over [message] where the text is requested during plugin registration but must
     * reflect the IDE's language at the moment it is displayed — action and inspection
     * presentations, for example.
     *
     * @param key a key present in `SolrBundle.properties`; checked at compile time
     * @param params values for the message's `{0}`-style placeholders
     * @return a supplier that resolves the localized message on demand
     */
    @Suppress("unused")
    @JvmStatic
    fun messagePointer(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getLazyMessage(key, *params)
}
