package de.codevoid.wmsproxy.core

/**
 * String-level handling of URLs: views for display, and cleaning up what a person typed.
 * Nothing here parses or validates.
 */
object Urls {

    private const val ELLIPSIS = "…"

    /** A percent-encoded brace pair around a name a placeholder could have. */
    private val ENCODED_PLACEHOLDER = Regex("%7[Bb]([-A-Za-z0-9_:]+)%7[Dd]")

    /**
     * An address as typed or pasted, ready to use: trimmed, and with its placeholders
     * decoded. A browser shows a tile template with its braces but copies it with them
     * percent-encoded, `%7Bz%7D`, which nothing reads as a placeholder. Only a brace pair
     * around a placeholder-shaped name is decoded; anything else stays encoded, because
     * a `%3A` in a layer name or JSON in a query value means what it says encoded.
     */
    fun fromInput(raw: String): String =
        ENCODED_PLACEHOLDER.replace(raw.trim()) { "{" + it.groupValues[1] + "}" }

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
