package de.codevoid.wmsproxy.core

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
        return "%02d:%02d:%02d.%03d".format(h, m, s, ms)
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

    private val entries = ArrayDeque<LoggedRequest>()

    @Synchronized
    fun record(entry: LoggedRequest) {
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<LoggedRequest> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

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
