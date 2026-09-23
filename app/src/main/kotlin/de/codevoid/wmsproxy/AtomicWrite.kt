package de.codevoid.wmsproxy

import java.io.File

/**
 * Writes [bytes] so a reader never sees a half-written file: to a sibling first, then
 * renamed over the target, which is atomic on one filesystem. Where the rename is refused,
 * a plain write is the fallback rather than no write at all.
 */
internal fun File.writeAtomically(bytes: ByteArray) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(this)) {
        writeBytes(bytes)
        tmp.delete()
    }
}
