package de.codevoid.wmsproxy

/** What a failure says to the user: its message, or its type when it has none. */
internal fun Throwable.describe(): String = message ?: javaClass.simpleName
