package de.codevoid.wmsproxy.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The DMD Hub sign-in, kept to its pure parts so they are testable without a device.
 *
 * The HTTP call, the User-Agent the endpoint guards on, and the credential storage all
 * live in `:app`; what stays here is the request body and the response envelope, because
 * those are where a wrong field name or a moved token silently breaks the flow and a
 * plain JUnit test can catch it.
 *
 * The envelope mirrors the `app.advhub.net/api/ios/auth` endpoints exactly: a `{success, message,
 * error, data:{token, refresh_token, user}}` wrapper. The refresh token is parsed but
 * not used — the session is renewed by signing in again with stored credentials rather
 * than by exchanging a refresh token, so there is one recovery path, not two.
 */
data class DmdLogin(
    val token: String,
    val name: String,
    val email: String,
)

/** Carries the server's own message so the sign-in screen can show why it refused. */
class DmdAuthException(message: String) : Exception(message)

/**
 * What is held for the account between launches: enough to re-authenticate silently when
 * the token expires. Serialized into the encrypted store in `:app`, never into the
 * plaintext config JSON.
 */
@Serializable
data class DmdCredentials(
    val email: String,
    val password: String,
    val token: String,
    val name: String,
)

object DmdAuth {

    // Lenient in, exact out: an envelope from a newer server that carries fields this
    // build does not know about must still yield the token, not a parse failure.
    // coerceInputValues because the live server sends `"message":null` on success, and a
    // JSON null against a non-nullable field with a default is a decode failure otherwise.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** The JSON body of a login request: only the two fields the endpoint reads. */
    fun loginBody(email: String, password: String): String =
        json.encodeToString(LoginRequest.serializer(), LoginRequest(email, password))

    /**
     * Turns a login response into a [DmdLogin], or a [DmdAuthException] carrying the
     * server's reason. A missing token is the failure signal — the wrapper reports
     * success in its own field, but an empty token means there is nothing to authenticate
     * with regardless of what that field says.
     */
    fun parseLogin(payload: String): Result<DmdLogin> {
        val envelope = runCatching { json.decodeFromString(AuthEnvelope.serializer(), payload) }
            .getOrNull()
            ?: return Result.failure(DmdAuthException("Unexpected response from DMD Hub"))

        if (envelope.data.token.isBlank()) {
            val reason = listOf(envelope.error, envelope.message)
                .firstOrNull { it.isNotBlank() }
                ?: "Sign-in failed"
            return Result.failure(DmdAuthException(reason))
        }

        return Result.success(
            DmdLogin(
                token = envelope.data.token,
                name = envelope.data.user.name,
                email = envelope.data.user.email,
            ),
        )
    }

    fun encodeCredentials(credentials: DmdCredentials): String =
        json.encodeToString(DmdCredentials.serializer(), credentials)

    /** Null when the stored blob is empty or no longer parses, i.e. treat as signed out. */
    fun decodeCredentials(text: String): DmdCredentials? =
        runCatching { json.decodeFromString(DmdCredentials.serializer(), text) }.getOrNull()

    @Serializable
    private data class LoginRequest(val email: String, val password: String)

    @Serializable
    private data class AuthEnvelope(
        val success: Boolean = false,
        val message: String = "",
        val error: String = "",
        val data: AuthData = AuthData(),
    )

    @Serializable
    private data class AuthData(
        val token: String = "",
        @SerialName("refresh_token") val refreshToken: String = "",
        val user: AuthUser = AuthUser(),
    )

    @Serializable
    private data class AuthUser(
        val id: String = "",
        val name: String = "",
        val email: String = "",
    )
}
