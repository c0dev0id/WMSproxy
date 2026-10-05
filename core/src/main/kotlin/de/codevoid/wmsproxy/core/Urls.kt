package de.codevoid.wmsproxy.core

/** String-level views of a URL for display. Nothing here parses or validates. */
object Urls {

    private const val ELLIPSIS = "…"

    /** The host and port between the scheme and the path, or `""` without a scheme. */
    fun hostOf(url: String): String =
        url.substringAfter("://", "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfter('@')

    /**
     * The URL cut to about [max] characters with the middle taken out: the scheme and host
     * stay, because they say which server, and the tail stays, because it says which
     * layer or file. A middle ellipsis of its own is what the UI framework lacks.
     */
    fun abbreviate(url: String, max: Int = 48): String {
        if (url.length <= max) return url
        val scheme = url.indexOf("://")
        val slash = if (scheme >= 0) url.indexOf('/', scheme + 3) else url.indexOf('/')
        val head = if (slash > 0) url.substring(0, slash + 1) else ""
        val room = max - head.length - ELLIPSIS.length
        if (room < 8) return url.take(max - ELLIPSIS.length) + ELLIPSIS
        return head + ELLIPSIS + url.takeLast(room)
    }
}
