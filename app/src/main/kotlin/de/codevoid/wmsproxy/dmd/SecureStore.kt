package de.codevoid.wmsproxy.dmd

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import de.codevoid.wmsproxy.writeAtomically
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A single encrypted string, kept out of the plaintext config JSON.
 *
 * The DMD account password has to survive a restart so the session can be renewed
 * silently, but it must not sit in `sources.json`, which the user is invited to export
 * and share. So the blob is encrypted with an AES-GCM key that lives in the
 * `AndroidKeyStore` and never leaves it: the ciphertext on disk is useless without the
 * device. This is the "secrets live in the Keystore" rule the project set for itself.
 *
 * GCM binds an authentication tag, so a truncated or tampered file fails to decrypt
 * rather than yielding garbage. The random IV is stored alongside the ciphertext because
 * GCM needs the same IV to decrypt and reusing one across encryptions would defeat it —
 * a fresh IV every save is the requirement, not a secret.
 */
class SecureStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    /** A failure leaves any previous value untouched. */
    fun save(plaintext: String) {
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val iv = cipher.iv
            val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            // iv length | iv | ciphertext+tag — self-describing so the IV size is not a
            // constant the reader has to keep in step with the writer.
            val out = ByteArray(1 + iv.size + body.size)
            out[0] = iv.size.toByte()
            iv.copyInto(out, destinationOffset = 1)
            body.copyInto(out, destinationOffset = 1 + iv.size)
            file.writeAtomically(out)
        }
    }

    /** Null when nothing is stored, or the key is gone, or the file no longer decrypts. */
    fun load(): String? = runCatching {
        val bytes = file.readBytes()
        val ivLength = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val body = bytes.copyOfRange(1 + ivLength, bytes.size)
        Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
            .doFinal(body)
            .toString(Charsets.UTF_8)
    }.getOrNull()

    fun clear() {
        runCatching { file.delete() }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val FILE_NAME = "dmd-credentials.bin"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "wmsproxy.dmd.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
