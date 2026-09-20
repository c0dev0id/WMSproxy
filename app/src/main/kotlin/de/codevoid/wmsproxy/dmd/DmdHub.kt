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
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What the UI is allowed to see of the account: never the token or the password. */
data class DmdSession(val name: String, val email: String)

/**
 * The DMD Hub account, held for the life of the process.
 *
 * An object, like [de.codevoid.wmsproxy.proxy.Sources], because the session outlives any
 * one screen: the tab that signs in is not the only thing that will ask whether it is.
 *
 * The request shape follows the DMD Android app exactly — the endpoint refuses anything
 * without the [USER_AGENT] it expects, so that header is not optional and not ours to
 * rename. The recovery model is deliberately simpler than the app's refresh-token dance:
 * when a call comes back 401 the token has lapsed, so we sign in again with the stored
 * password **once**; if that still fails, the credentials are stale and we sign out
 * rather than hammer the endpoint. One recovery path, not two.
 *
 * Every call throws on failure, so a caller has one thing to catch. A [DmdAuthException]
 * means there is no session any more — never signed in, refused, or signed out because
 * renewal failed; anything else is the network or the server, and the session stands.
 *
 * The client validates TLS — this is a real host, so it must not borrow the upstream
 * relay's certificate-blind client.
 */
object DmdHub {

    private const val HOST = "https://app.advhub.net"
    private const val API = "/api/ios"
    private const val LOGIN_PATH = "$API/auth/login"
    private const val CUSTOM_LAYERS_PATH = "$API/custom-layers"

    private const val USER_AGENT = "DMD-HUB-Android/1.0"
    private val JSON = "application/json".toMediaType()

    // Lazily, because building an OkHttpClient loads the system trust store, and this one
    // is first needed after a sign-in rather than at launch. Every use is on IO.
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

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
                _session.value = restored.session
            }
        }
    }

    /**
     * Signs in and persists the credentials. On failure the previous session, if any, is
     * left untouched — a mistyped password on a re-login attempt should not sign you out.
     * On IO throughout, because persisting goes through the Keystore, which is not free.
     */
    suspend fun login(email: String, password: String) = withContext(Dispatchers.IO) {
        persist(authenticate(email, password), email, password)
    }

    fun logout() {
        credentials = null
        _session.value = null
        if (::store.isInitialized) store.clear()
    }

    /** The account's custom layers as the server sent them. Doubles as the live-session check. */
    suspend fun fetchLayers(): String = request("GET", CUSTOM_LAYERS_PATH)

    /** Replaces the account's custom layers; the endpoint has no partial update. */
    suspend fun pushLayers(body: String) {
        request("POST", CUSTOM_LAYERS_PATH, body)
    }

    private suspend fun request(method: String, path: String, jsonBody: String? = null): String =
        withContext(Dispatchers.IO) {
            val token = credentials?.token ?: throw DmdAuthException("Not signed in to DMD Hub")

            var reply = execute(method, path, jsonBody, token)
            if (reply.code == 401) {
                // The token lapsed: sign in again with the stored password, once. A renewal
                // that fails, or a renewed token still refused, means the credentials are
                // stale, and hammering the endpoint would not change that.
                reply = renew()?.let { execute(method, path, jsonBody, it) } ?: reply
                if (reply.code == 401) {
                    logout()
                    throw DmdAuthException("DMD Hub session expired — sign in again")
                }
            }
            if (reply.code !in 200..299) throw IOException("DMD Hub returned HTTP ${reply.code}")
            reply.body
        }

    private class Reply(val code: Int, val body: String)

    /** The bare HTTP round-trip, no retry logic — [request] owns that. */
    private fun execute(method: String, path: String, jsonBody: String?, token: String): Reply {
        val request = to(path)
            .header("Authorization", "Bearer $token")
            .method(method, jsonBody?.toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            return Reply(response.code, response.body?.string().orEmpty())
        }
    }

    /** Every request to the hub starts here, so the header it gates on exists once. */
    private fun to(path: String): Request.Builder = Request.Builder()
        .url(HOST + path)
        .header("Accept", "application/json")
        .header("User-Agent", USER_AGENT)

    /** Signs in with the stored credentials and updates the token; null when that fails. */
    private fun renew(): String? {
        val current = credentials ?: return null
        val login = runCatching { authenticate(current.email, current.password) }.getOrNull()
            ?: return null
        persist(login, current.email, current.password)
        return login.token
    }

    /** Blocking, so callers are on IO. Throws [DmdAuthException] carrying the server's reason. */
    private fun authenticate(email: String, password: String): DmdLogin {
        val request = to(LOGIN_PATH)
            .post(DmdAuth.loginBody(email, password).toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            return DmdAuth.parseLogin(response.body?.string().orEmpty()).getOrThrow()
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
        _session.value = updated.session
        if (::store.isInitialized) store.save(DmdAuth.encodeCredentials(updated))
    }

    private val DmdCredentials.session: DmdSession get() = DmdSession(name, email)
}
