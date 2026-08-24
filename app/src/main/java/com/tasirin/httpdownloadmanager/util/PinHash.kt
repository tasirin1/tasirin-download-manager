package com.tasirin.httpdownloadmanager.util

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Hash PIN memakai PBKDF2 agar nilai disk tidak cepat di-brute-force. */
object PinHash {

    // Varian SHA-1 tersedia di seluruh minSdk 21; kekuatan datang dari salt
    // acak dan iterasi tinggi, bukan memakai API yang tidak ada di Android 6.
    private const val ALGORITHM = "PBKDF2WithHmacSHA1"
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    const val ITERATIONS = 100_000
    private const val MAX_PARSED_ITERATIONS = 500_000
    private val HEX_RE = Regex("^[0-9a-f]+$")

    fun hash(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        return hash(pin, salt, ITERATIONS)
    }

    internal fun hash(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): String {
        require(pin.isNotEmpty()) { "PIN must not be empty" }
        require(salt.isNotEmpty()) { "Salt must not be empty" }
        require(iterations in 1..MAX_PARSED_ITERATIONS) { "Invalid iteration count" }
        val digest = derive(pin, salt, iterations)
        return "$PREFIX$iterations\$${Hex.encode(salt)}\$${Hex.encode(digest)}"
    }

    fun verify(pin: String, stored: String): Boolean {
        val parts = parse(stored) ?: return false
        val (iterations, salt, expected) = parts
        val actual = runCatching { derive(pin, salt, iterations) }.getOrNull() ?: return false
        return StoragePrefs.constantEquals(Hex.encode(actual), expected)
    }

    fun isModern(stored: String): Boolean = parse(stored) != null

    private fun parse(stored: String): Triple<Int, ByteArray, String>? {
        if (!stored.startsWith(PREFIX)) return null
        val fields = stored.removePrefix(PREFIX).split('$')
        if (fields.size != 3) return null
        val iterations = fields[0].toIntOrNull()?.takeIf { it in 10_000..MAX_PARSED_ITERATIONS }
            ?: return null
        val salt = fields[1].hexToBytesOrNull() ?: return null
        val digest = fields[2]
        if (digest.length != KEY_LENGTH_BITS / 4 || !HEX_RE.matches(digest)) return null
        return Triple(iterations, salt, digest)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        val digest = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        spec.clearPassword()
        return digest
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (isEmpty() || length % 2 != 0 || !HEX_RE.matches(this)) return null
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private const val PREFIX = "\$pbkdf2-sha1\$"
}
