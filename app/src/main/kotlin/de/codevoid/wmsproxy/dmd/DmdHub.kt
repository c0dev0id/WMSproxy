package de.codevoid.wmsproxy.dmd

import android.content.Context
import de.codevoid.wmsproxy.core.DmdAuth
import de.codevoid.wmsproxy.core.DmdAuthException
import de.codevoid.wmsproxy.core.DmdCredentials
import de.codevoid.wmsproxy.core.DmdLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What the UI is allowed to see of the account: never the token or the password. */
data class DmdSession(val name: String, val email: String)

/** An authenticated response: the status and the body, parsed by the caller. */
data class DmdResponse(val code: Int, val body: String)

/**
 * The DMD Hub account, held for the life of the process.
 *
 * An object, like [de.codevoid.wmsproxy.proxy.Sources], because the session outlives any
 * one screen and a future sync will reach it from the service, not just the activity.
 *
 * The request shape follows the DMD Android app exactly — the endpoint refuses anything
 * without the [USER_AGENT] it expects, so that header is not optional and not ours to
 * rename. The recovery model is deliberately simpler than the app's refresh-token dance:
 * when a call comes back 401 the token has lapsed, so we sign in again with the stored
 * password **once**; if that still fails, the credentials are stale and we sign out
 * rather than hammer the endpoint. One recovery path, not two.
 *
 * The client validates TLS — this is a real host, so it must not borrow the upstream
 * relay's certificate-blind client.
 */
object DmdHub {

    private const val HOST = "https://app.advhub.net"
    private const val API = "/api/ios"
    private const val LOGIN_PATH = "$API/auth/login"

    /** The path a live-session check and, later, the layer sync both use. */
    const val CUSTOM_LAYERS_PATH = "$API/custom-layers"

    private const val USER_AGENT = "DMD-HUB-Android/1.0"
    private val JSON = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var store: SecureStore

    // The full credentials, in memory for the life of the process. The exposed session is
    // a redacted view of the same thing, so the two cannot drift apart. Volatile because
    // the restore runs on a background thread (see init) while requests read it on IO.
    @Volatile
    private var credentials: DmdCredentials? = null

    private val _session = MutableStateFlow<DmdSession?>(null)
    val session: StateFlow<DmdSession?> = _session.asStateFlow()

    /** Called once from [de.codevoid.wmsproxy.WmsProxyApp], before any screen reads it. */
    fun init(context: Context) {
        store = SecureStore(context.applicationContext)
        // Off the main thread: the restore decrypts through the AndroidKeyStore, which can
        // cost tens of ms on some devices, and a returning user pays it on every launch for
        // a tab most launches never open. The session flips from null to the restored value
        // when this finishes; the DMD tab already handles that transition.
        thread(name = "dmd-restore") {
            store.load()?.let(DmdAuth::decodeCredentials)?.let { restored ->
                credentials = restored
                _session.value = DmdSession(restored.name, restored.email)
            }
        }
    }

    /**
     * Signs in and persists the credentials. On failure the previous session, if any, is
     * left untouched — a mistyped password on a re-login attempt should not sign you out.
     */
    suspend fun login(email: String, password: String): Result<Unit> =
        authenticate(email, password).map { persist(it, email, password) }

    fun logout() {
        credentials = null
        _session.value = null
        if (::store.isInitialized) store.clear()
    }

    /**
     * Issues an authenticated request, renewing the session once on a 401. A body of null
     * sends no body (a GET); a non-null body is sent as JSON. Throws [DmdAuthException]
     * when there is no session or when renewal fails — the latter having already signed
     * the account out.
     */
    suspend fun request(method: String, path: String, jsonBody: String? = null): DmdResponse =
        withContext(Dispatchers.IO) {
            val token = credentials?.token
                ?: throw DmdAuthException("Not signed in to DMD Hub")

            fun expired(): Nothing {
                logout()
                throw DmdAuthException("DMD Hub session expired — sign in again")
            }

            var response = execute(method, path, jsonBody, token)
            if (response.code == 401) {
                val renewed = renew() ?: expired()
                response = execute(method, path, jsonBody, renewed)
                if (response.code == 401) expired()
            }
            response
        }

    /**
     * Confirms the stored token still works, exercising the same renew-once path a real
     * request would. Success means the account is genuinely connected, not merely
     * remembered; a thrown [DmdAuthException] means it has been signed out.
     */
    suspend fun checkConnection(): Result<Unit> = runCatching {
        val response = request("GET", CUSTOM_LAYERS_PATH)
        if (response.code !in 200..299) {
            throw DmdAuthException("DMD Hub returned HTTP ${response.code}")
        }
    }

    /** The bare HTTP round-trip, no retry logic — [request] owns that. */
    private fun execute(method: String, path: String, jsonBody: String?, token: String): DmdResponse {
        val builder = Request.Builder()
            .url(HOST + path)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer $token")

        val body = jsonBody?.toRequestBody(JSON)
        builder.method(method, body)

        client.newCall(builder.build()).execute().use { response ->
            return DmdResponse(response.code, response.body?.string().orEmpty())
        }
    }

    /** Signs in with the stored credentials and updates the token; null when it fails. */
    private fun renew(): String? {
        val current = credentials ?: return null
        val login = authenticateBlocking(current.email, current.password).getOrNull() ?: return null
        persist(login, current.email, current.password)
        return login.token
    }

    private suspend fun authenticate(email: String, password: String) =
        withContext(Dispatchers.IO) { authenticateBlocking(email, password) }

    private fun authenticateBlocking(email: String, password: String) = runCatching {
        val request = Request.Builder()
            .url(HOST + LOGIN_PATH)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(DmdAuth.loginBody(email, password).toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            DmdAuth.parseLogin(response.body?.string().orEmpty()).getOrThrow()
        }
    }

    private fun persist(login: DmdLogin, email: String, password: String) {
        val updated = DmdCredentials(
            email = email,
            password = password,
            token = login.token,
            // The server echoes the display name; fall back to the email if it is blank so
            // the signed-in line always says something.
            name = login.name.ifBlank { email },
        )
        credentials = updated
        _session.value = DmdSession(updated.name, updated.email)
        if (::store.isInitialized) store.save(DmdAuth.encodeCredentials(updated))
    }
}
