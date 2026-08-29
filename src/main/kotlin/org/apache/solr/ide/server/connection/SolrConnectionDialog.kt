package org.apache.solr.ide.server.connection

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import java.util.UUID
import javax.swing.JComponent
import org.apache.solr.ide.SolrBundle

/**
 * The form for one Solr connection.
 *
 * **The password field opens empty even when a secret is stored, and that is deliberate.**
 * Pre-filling it would put the credential into a live Swing component for as long as the dialog
 * stays open, which is one more place than the storage design allows it. The cost is that an empty
 * field is ambiguous — it could mean "no password" or "one you cannot see" — so the stored secret is
 * announced in text, and forgetting it is an explicit checkbox rather than the field being cleared.
 * Typing nothing therefore changes nothing, which is what a user editing a display name expects.
 *
 * Every rule about what may be entered lives in [SolrConnectionValidation], so the rules can be
 * exercised without a dialog. What is here is the binding.
 *
 * @param project the project the connection belongs to
 * @param initial the connection being edited, or null when adding a new one
 * @param hasStoredPassword whether a secret is already stored — the value itself is never read
 */
class SolrConnectionDialog(
    project: Project,
    private val initial: SolrConnection?,
    private val hasStoredPassword: Boolean,
) : DialogWrapper(project) {

    private val nameField = JBTextField(initial?.displayName.orEmpty())
    private val urlField = JBTextField(initial?.baseUrl.orEmpty())
    private val usernameField = JBTextField(initial?.username.orEmpty())
    private val passwordField = JBPasswordField()
    private val forgetPasswordBox = JBCheckBox(SolrBundle.message("connection.dialog.forgetPassword"))

    init {
        title = SolrBundle.message(if (initial == null) "connection.dialog.addTitle" else "connection.dialog.editTitle")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(SolrBundle.message("connection.dialog.url")) {
            cell(urlField).align(Align.FILL)
                .comment(SolrBundle.message("connection.dialog.urlComment"))
        }
        row(SolrBundle.message("connection.dialog.name")) {
            cell(nameField).align(Align.FILL)
                .comment(SolrBundle.message("connection.dialog.nameComment"))
        }
        row(SolrBundle.message("connection.dialog.username")) {
            cell(usernameField).align(Align.FILL)
        }
        row(SolrBundle.message("connection.dialog.password")) {
            cell(passwordField).align(Align.FILL)
                .comment(
                    SolrBundle.message(
                        if (hasStoredPassword) "connection.dialog.passwordStored" else "connection.dialog.passwordComment",
                    ),
                )
        }
        if (hasStoredPassword) {
            row("") { cell(forgetPasswordBox) }
        }
    }

    /**
     * The field the caret lands in, which is the URL — the one field with no useful default.
     *
     * @return the server URL field
     */
    override fun getPreferredFocusedComponent(): JComponent = urlField

    /**
     * Whether what has been entered can be saved.
     *
     * @return the problem to show beside the offending field, or null where there is none
     */
    override fun doValidate(): ValidationInfo? {
        val problem = SolrConnectionValidation.problemIn(
            baseUrl = urlField.text,
            username = usernameField.text,
            // A secret already stored counts: it is what would be sent, so a username cleared away
            // from underneath it is the same mistake as typing one with no user.
            hasPassword = passwordField.password.isNotEmpty() || (hasStoredPassword && !forgetPasswordBox.isSelected),
        ) ?: return null
        return ValidationInfo(messageFor(problem), fieldFor(problem))
    }

    /**
     * The connection as entered.
     *
     * A new connection is given a fresh identifier; an edited one keeps the identifier it had, since
     * that is the key its stored secret is filed under and changing it would orphan the credential.
     *
     * @return the non-secret half of the connection
     */
    val connection: SolrConnection
        get() {
            val baseUrl = urlField.text.trim()
            return SolrConnection(
                id = initial?.id ?: UUID.randomUUID().toString(),
                // A row with no label reads as empty rather than as itself, so the URL stands in.
                displayName = nameField.text.trim().ifEmpty { baseUrl },
                baseUrl = baseUrl,
                username = usernameField.text.trim().ifEmpty { null },
            )
        }

    /**
     * Whether the stored secret should be rewritten at all.
     *
     * False for an empty field with nothing asked of it, which is why editing a display name leaves
     * a credential alone. Read together with [password], the pair says the same two things
     * `SolrConnectionSettings.setPassword` is told: whether to write, and what.
     */
    val passwordEdited: Boolean
        get() = passwordField.password.isNotEmpty() || forgetPasswordBox.isSelected

    /**
     * The secret to store, or null to forget whatever is stored.
     *
     * Meaningful only where [passwordEdited] is true; null otherwise means the same as it does then,
     * which is why the two are read together rather than this one alone.
     */
    val password: CharArray?
        get() = passwordField.password.takeIf { it.isNotEmpty() }

    private fun messageFor(problem: SolrConnectionProblem) = SolrBundle.message(
        when (problem) {
            SolrConnectionProblem.BASE_URL_MISSING -> "connection.problem.urlMissing"
            SolrConnectionProblem.BASE_URL_MALFORMED -> "connection.problem.urlMalformed"
            SolrConnectionProblem.BASE_URL_NOT_HTTP -> "connection.problem.urlNotHttp"
            SolrConnectionProblem.PASSWORD_WITHOUT_USERNAME -> "connection.problem.passwordWithoutUsername"
        },
    )

    private fun fieldFor(problem: SolrConnectionProblem) =
        if (problem == SolrConnectionProblem.PASSWORD_WITHOUT_USERNAME) usernameField else urlField
}
