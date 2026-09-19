package de.codevoid.wmsproxy.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** One inbound request, recorded as received. */
data class LoggedRequest(
    val at: Long,
    val method: String,
    val path: String,
    val query: String,
    val userAgent: String?,
    val status: Int,
    /** What the proxy made of it — the tile it resolved to, or why it could not. */
    val note: String,
) {
    fun format(): String = buildString {
        append(formatTime(at))
        append(' ')
        append(status)
        append(' ')
        append(method)
        append(' ')
        append(path)
        if (query.isNotEmpty()) {
            append('?')
            append(query)
        }
        if (note.isNotEmpty()) {
            append("\n    ")
            append(note)
        }
        userAgent?.let {
            append("\n    ua: ")
            append(it)
        }
    }

    private fun formatTime(epochMillis: Long): String {
        val totalSeconds = epochMillis / 1000
        val h = (totalSeconds / 3600) % 24
        val m = (totalSeconds / 60) % 60
        val s = totalSeconds % 60
        val ms = epochMillis % 1000
        // %d substitutes digits for the default locale, so a device set to one with
        // non-ASCII digits would write a timestamp nobody can grep.
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", h, m, s, ms)
    }
}

/**
 * A bounded, in-memory record of what the client actually asked for.
 *
 * This is the project's primary diagnostic. DMD2's request shape is not documented, so
 * the log is how questions like "are its GetMap extents tile-aligned?" get answered from
 * evidence rather than assumption. It is deliberately capped and never persisted —
 * queries can carry credentials, and nothing here is worth keeping across a restart.
 */
class RequestLog(private val capacity: Int = DEFAULT_CAPACITY) {

    private val recent = ArrayDeque<LoggedRequest>()
    private val _requests = MutableStateFlow<List<LoggedRequest>>(emptyList())

    /**
     * The log as it stands, republished on every change.
     *
     * A flow rather than a method the caller polls. Requests arrive on the server's
     * worker threads while the user is looking at the screen, so anything that has to be
     * asked for is stale the moment it is drawn — which is how a request counter ends up
     * showing a number that never moves.
     *
     * Each change publishes a fresh immutable list. Copying up to [DEFAULT_CAPACITY]
     * references per request is nothing next to the network call that produced it, and it
     * means a reader can never observe the deque mid-mutation.
     */
    val requests: StateFlow<List<LoggedRequest>> = _requests.asStateFlow()

    @Synchronized
    fun record(entry: LoggedRequest) {
        recent.addLast(entry)
        while (recent.size > capacity) recent.removeFirst()
        _requests.value = recent.toList()
    }

    /** The current contents, oldest first. */
    fun snapshot(): List<LoggedRequest> = _requests.value

    @Synchronized
    fun clear() {
        recent.clear()
        _requests.value = emptyList()
    }

    /** Renders the log as text for sharing. Newest last, so it reads chronologically. */
    fun asText(header: String): String {
        val lines = snapshot()
        if (lines.isEmpty()) return "$header\n\n(no requests yet)"
        return buildString {
            append(header)
            append("\n\n")
            lines.forEach {
                append(it.format())
                append('\n')
            }
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 300
    }
}
