package org.apache.solr.ide.code.solrj

import com.intellij.psi.PsiFile
import org.apache.solr.ide.code.SolrRecognizers
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Reading field names out of SolrJ's `@Field` annotation, in Java and in Kotlin.
 *
 * **The other half of what names a field, and the half a call-expression visitor cannot see.** The
 * recognizer's traversal answered one question — which calls name fields — and an annotation is not
 * a call, so a bean mapping every document in the application goes through was read as nothing at
 * all. The demo carries `@Field("prce")` for exactly this reason: it compiles, it ships, and Solr
 * answers a query against `prce` with zero results rather than an error.
 *
 * The annotation is matched by qualified name, so a `@Field` from somewhere else — and there are
 * several, JPA's among them — names nothing here.
 */
class SolrJBeanFieldTest : SolrConfigsetTestCase() {

    /**
     * SolrJ's annotation, as source.
     *
     * A stub for the same reason the query stub is one: the recognizer matches a qualified name, so
     * a file declaring that name answers the question it asks without a jar in the build.
     */
    private fun givenTheAnnotation(qualifiedPackage: String = "org.apache.solr.client.solrj.beans") {
        myFixture.addFileToProject(
            "${qualifiedPackage.replace('.', '/')}/Field.java",
            """
            package $qualifiedPackage;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.FIELD, ElementType.METHOD})
            public @interface Field {
                String value() default "#default";
            }
            """.trimIndent(),
        )
    }

    private fun javaBean(body: String): PsiFile =
        myFixture.addFileToProject(
            "src/Product.java",
            """
            import org.apache.solr.client.solrj.beans.Field;
            class Product {
                $body
            }
            """.trimIndent(),
        )

    private fun kotlinBean(body: String): PsiFile =
        myFixture.addFileToProject(
            "src/Product.kt",
            """
            import org.apache.solr.client.solrj.beans.Field
            class Product {
                $body
            }
            """.trimIndent(),
        )

    private fun usages(file: PsiFile) = SolrRecognizers.fieldUsagesIn(file)

    // --- what it reads ------------------------------------------------------------------------------

    fun testAnAnnotationValueNamesTheField() {
        givenTheAnnotation()

        val found = usages(javaBean("""@Field("prce") String price;"""))

        assertEquals(listOf("prce"), found.map { it.fieldName })
    }

    /**
     * An annotation with no value names the property it sits on, which is what SolrJ does with it.
     *
     * Reading only the spelled-out case would be the more cautious choice and the wrong one: the
     * name still reaches Solr, so declining to read it is a miss that shows up as silence — the
     * failure mode this project has already paid for once.
     */
    fun testAnAnnotationWithNoValueNamesItsProperty() {
        givenTheAnnotation()

        val found = usages(javaBean("@Field String categry;"))

        assertEquals(listOf("categry"), found.map { it.fieldName })
    }

    fun testEveryAnnotatedPropertyIsRead() {
        givenTheAnnotation()

        val found = usages(
            javaBean(
                """
                @Field("id") String id;
                @Field("name") String name;
                @Field("prce") String price;
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("id", "name", "prce"), found.map { it.fieldName })
    }

    /** The same bean in Kotlin reads the same, through the same recognizer. */
    fun testKotlinReadsTheSameNames() {
        givenTheAnnotation()

        val found = usages(kotlinBean("""@Field("prce") val price: String? = null"""))

        assertEquals(listOf("prce"), found.map { it.fieldName })
    }

    /** A Kotlin property annotated without a value names itself, as the Java one does. */
    fun testKotlinReadsAnImplicitName() {
        givenTheAnnotation()

        val found = usages(kotlinBean("@Field val categry: String? = null"))

        assertEquals(listOf("categry"), found.map { it.fieldName })
    }

    // --- what it declines to read -------------------------------------------------------------------

    /**
     * A `@Field` that is somebody else's annotation names nothing.
     *
     * The name is among the most reused in the JVM ecosystem — JPA, Lucene and several serialization
     * libraries all ship one — so matching on the simple name would report a field reference for
     * every annotated property in a great many projects that have never touched Solr.
     */
    fun testAnUnrelatedFieldAnnotationIsNotRead() {
        givenTheAnnotation()
        myFixture.addFileToProject(
            "jakarta/persistence/Field.java",
            """
            package jakarta.persistence;
            public @interface Field { String value() default ""; }
            """.trimIndent(),
        )

        val found = usages(
            myFixture.addFileToProject(
                "src/Entity.java",
                """
                import jakarta.persistence.Field;
                class Entity {
                    @Field("prce") String price;
                }
                """.trimIndent(),
            ),
        )

        assertEmpty(found)
    }

    /** A value that is not spelled out in the source names nothing, as everywhere else here. */
    fun testAComputedValueNamesNoField() {
        givenTheAnnotation()

        val found = usages(javaBean("""static final String N = "prce"; @Field(N) String price;"""))

        assertEmpty(found)
    }

    /** A class with no annotated property reads as nothing rather than as an error. */
    fun testAPlainClassNamesNoField() {
        givenTheAnnotation()

        assertEmpty(usages(javaBean("String price;")))
    }

    // --- where the finding points -------------------------------------------------------------------

    /**
     * The finding anchors to the name as written, not to the whole annotation.
     *
     * A warning highlighting `@Field("prce")` entire would underline the annotation a reader already
     * knows is fine; what is wrong is the four characters inside it.
     */
    fun testTheFindingAnchorsToTheWrittenName() {
        givenTheAnnotation()

        val found = usages(javaBean("""@Field("prce") String price;""")).single()

        assertTrue(found.element.text, found.element.text.contains("prce"))
    }
}
