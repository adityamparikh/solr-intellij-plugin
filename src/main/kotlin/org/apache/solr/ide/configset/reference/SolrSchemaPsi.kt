package org.apache.solr.ide.configset.reference

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.activation.SolrSchemaTags

/**
 * Finds the PSI element that *declares* something in a schema.
 *
 * The field model deliberately holds no PSI — it is a pure data structure so that it can be tested
 * without an IDE — which means it can say a field type called `text_general` exists but not where it
 * was written. Navigation needs the second answer, so it is asked of the PSI directly.
 *
 * Declarations resolve to the value of the `name` attribute rather than to the tag. That is the text
 * the user expects to land on, and it is the element rename will later need to modify.
 */
internal object SolrSchemaPsi {

    /**
     * The `name` attribute value of the `fieldType` declaring [typeName], or null if none does.
     *
     * @param file the schema file to search
     * @param typeName the type name to find
     * @return the declaring element, or null
     */
    fun findFieldType(file: PsiFile, typeName: String): XmlAttributeValue? =
        findDeclaration(file, SolrSchemaTags.FIELD_TYPE, typeName)

    /**
     * The `name` attribute value of the `field` or `dynamicField` declaring [fieldName].
     *
     * Dynamic fields are searched by their pattern rather than by matching against it: a
     * `copyField dest="*_t"` names the pattern literally, and that is the declaration to land on.
     *
     * @param file the schema file to search
     * @param fieldName the field name or dynamic pattern to find
     * @return the declaring element, or null
     */
    fun findField(file: PsiFile, fieldName: String): XmlAttributeValue? =
        findDeclaration(file, SolrSchemaTags.FIELD, fieldName)

    /**
     * The first tag named in [tagNames] whose `name` attribute is [name].
     *
     * **Stops at the match and checks for cancellation on the way.** This runs once per reference
     * per highlighting pass, and a real schema is thousands of elements — `findChildrenOfType` would
     * collect every tag in the file into a list before looking at any of them, and would keep
     * building that list while the user carried on typing.
     */
    private fun findDeclaration(file: PsiFile, tagNames: Set<String>, name: String): XmlAttributeValue? {
        var found: XmlAttributeValue? = null
        PsiTreeUtil.processElements(file, XmlTag::class.java) { tag ->
            ProgressManager.checkCanceled()
            if (tag.name in tagNames && tag.getAttributeValue("name") == name) {
                found = tag.getAttribute("name")?.valueElement
            }
            // A matching tag with no value element is not an answer, so keep looking.
            found == null
        }
        return found
    }
}
