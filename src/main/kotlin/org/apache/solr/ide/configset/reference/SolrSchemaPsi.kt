package org.apache.solr.ide.configset.reference

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

    private fun findDeclaration(file: PsiFile, tagNames: Set<String>, name: String): XmlAttributeValue? =
        PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .firstOrNull { it.name in tagNames && it.getAttributeValue("name") == name }
            ?.getAttribute("name")
            ?.valueElement


}
