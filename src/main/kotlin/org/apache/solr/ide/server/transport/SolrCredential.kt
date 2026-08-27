package org.apache.solr.ide.server.transport

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * What a connection authenticates as, including the state that is neither present nor absent.
 *
 * **Three cases rather than two nullable strings, and the third is why this type exists.** A
 * connection naming a username with no stored password is *broken*, not anonymous, and the two
 * collapse into each other the moment they are carried as `(String?, String?)`. Sending an
 * empty-string password is worse than sending none: most Solr Basic Auth configurations reject it as
 * a *wrong* credential rather than *no* credential, so a configuration mistake — a cleared
 * PasswordSafe entry, a connection imported without its secret — surfaces as a spurious
 * authentication failure against a server that was never asked properly.
 *
 * **Nothing here renders itself.** These are deliberately not data classes: a synthesised `toString`
 * putting a password into a log line, an exception message or a result type is precisely how a
 * secret escapes the two places it is allowed to be, which are `PasswordSafe` and the in-flight
 * request.
 *
 * The type is pure JDK, like everything else in this package. Resolving a connection's stored secret
 * needs the IDE's credential store, and that lookup belongs beside the settings that own it rather
 * than here.
 */
sealed class SolrCredential {

    /**
     * The header value to send, or null where there is nothing to send.
     *
     * Null for [Missing] as well as [None] — a broken credential must never reach the wire. What
     * distinguishes them is what a caller reports, not what it transmits.
     *
     * @return the `Authorization` value, or null
     */
    abstract fun authorizationHeader(): String?

    /** The connection names no user, and the server is expected to want none. */
    data object None : SolrCredential() {
        override fun authorizationHeader(): String? = null
    }

    /**
     * A username and the password stored for it.
     *
     * @property username the user to authenticate as
     * @property password that user's password, which exists here and in `PasswordSafe` and nowhere
     *   else
     */
    class Resolved(val username: String, val password: String) : SolrCredential() {

        override fun authorizationHeader(): String =
            "Basic " + Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))

        /**
         * Two credentials are the same when they name the same account with the same secret.
         *
         * Written out because this is not a `data class` — see the class comment on why a
         * synthesised `toString` is the hazard. Equality is still wanted, so it is spelled here.
         *
         * @param other the value to compare against
         * @return true when both are the same case with the same contents
         */
        override fun equals(other: Any?): Boolean =
            other is Resolved && other.username == username && other.password == password

        /**
         * A hash consistent with [equals].
         *
         * @return the hash of the contents equality compares
         */
        override fun hashCode(): Int = 31 * username.hashCode() + password.hashCode()

        /** Deliberately says nothing. See the class comment. */
        override fun toString(): String = "SolrCredential.Resolved"
    }

    /**
     * A username with no password behind it, which is a broken connection rather than an anonymous
     * one.
     *
     * @property username the user the connection names, kept so a caller can say which connection is
     *   incomplete without the user having to guess
     */
    class Missing(val username: String) : SolrCredential() {

        override fun authorizationHeader(): String? = null

        /**
         * Two credentials are the same when they name the same account with the same secret.
         *
         * Written out because this is not a `data class` — see the class comment on why a
         * synthesised `toString` is the hazard. Equality is still wanted, so it is spelled here.
         *
         * @param other the value to compare against
         * @return true when both are the same case with the same contents
         */
        override fun equals(other: Any?): Boolean = other is Missing && other.username == username

        /**
         * A hash consistent with [equals].
         *
         * @return the hash of the contents equality compares
         */
        override fun hashCode(): Int = username.hashCode()

        /** Deliberately says nothing; a username identifies an account as much as a password does. */
        override fun toString(): String = "SolrCredential.Missing"
    }

    /** Building one from what a connection and the credential store hold. */
    companion object {

        /**
         * The credential a connection's fields describe.
         *
         * @param username the connection's username, or null where it names none
         * @param password the password stored for it, or null where none is stored
         * @return [None], [Resolved] or [Missing]
         */
        fun of(username: String?, password: String?): SolrCredential = when {
            username.isNullOrEmpty() -> None
            // An empty stored password counts as missing: PasswordSafe returns one for some cleared
            // entries, and `user:` on the wire is the same wrong credential as `user:` from a null.
            password.isNullOrEmpty() -> Missing(username)
            else -> Resolved(username, password)
        }
    }
}
