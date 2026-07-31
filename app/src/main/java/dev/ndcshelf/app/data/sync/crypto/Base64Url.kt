package dev.ndcshelf.app.data.sync.crypto

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** protocol v1のbinary表現（unpadded base64url）。API 23互換のためokioを使う。 */
object Base64Url {
    private val ALPHABET = Regex("^[A-Za-z0-9_-]*$")

    fun encode(bytes: ByteArray): String = bytes.toByteString().base64Url().trimEnd('=')

    /** unpadded base64url以外（padding・標準alphabet・空白）を拒否する。 */
    fun decode(value: String): ByteArray? {
        if (!ALPHABET.matches(value)) return null
        return value.decodeBase64()?.toByteArray()
    }

    fun decodeOrThrow(value: String): ByteArray = requireNotNull(decode(value)) { "Invalid base64url value." }
}
