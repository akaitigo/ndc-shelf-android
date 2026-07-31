package dev.ndcshelf.app.data.sync.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.erdtman.jcs.JsonCanonicalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * SYNC_PROTOCOL.md 5〜6節のwire文書。canonical bytesはRFC 8785準拠の
 * 保守済み実装（java-json-canonicalization）で生成し、独自canonicalizerを
 * 作らない。64-bit counterはunsigned decimal string、binaryはunpadded
 * base64url、timestampはUTC RFC 3339で表現する。
 */
object SyncWireJson {
    /** 受信時は同一majorの未知optional fieldを無視して継続する（原本はbackendに残る）。 */
    val lenient: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

    val strict: Json =
        Json {
            ignoreUnknownKeys = false
            isLenient = false
        }

    fun canonicalBytes(jsonText: String): ByteArray = JsonCanonicalizer(jsonText).encodedUTF8

    inline fun <reified T> canonicalEncode(value: T): ByteArray =
        canonicalBytes(strict.encodeToString(kotlinx.serialization.serializer(), value))
}

object SyncWireTime {
    private fun formatter(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }

    fun encode(millis: Long): String = formatter().format(Date(millis))

    fun decode(value: String): Long? =
        try {
            formatter().parse(value)?.time
        } catch (_: Exception) {
            null
        }
}

@Serializable
data class WireProtectedHeader(
    val protocolVersion: String,
    val suite: String,
    val libraryOpaqueId: String,
    val epoch: Int,
    val registryGeneration: Int,
    val paddedLength: Int,
    val signingDevicePublicKeyId: String,
)

@Serializable
data class WireSyncEnvelope(
    val objectId: String,
    val protectedHeader: WireProtectedHeader,
    val nonce: String,
    val ciphertext: String,
    val signature: String,
)

@Serializable
data class WireDot(
    val deviceId: String,
    val counter: String,
)

@Serializable
data class WireOperation(
    val kind: String,
    val entityType: String,
    val entityId: String,
    val dot: WireDot,
    val causalContext: Map<String, String>,
    val transactionId: String,
    val transactionIndex: Int,
    val transactionSize: Int,
    val createdAt: String,
    val fields: Map<String, JsonElement>? = null,
)

@Serializable
data class WireTransaction(
    val transactionId: String,
    val operations: List<WireOperation>,
)

@Serializable
data class WireCounterRange(
    val first: String,
    val last: String,
)

@Serializable
data class WireOperationsPayload(
    val kind: String = KIND,
    val previousObjectHash: String?,
    val deviceId: String,
    val counterRange: WireCounterRange?,
    val versionVector: Map<String, String>,
    val transactions: List<WireTransaction>,
    val createdAt: String,
    val requiredCapabilities: List<String> = emptyList(),
) {
    companion object {
        const val KIND = "operations"
    }
}

@Serializable
data class WireFieldState(
    val entityType: String,
    val entityId: String,
    val fieldName: String,
    val value: JsonElement,
    val winner: WireDot,
    val causalContext: Map<String, String>,
)

@Serializable
data class WireTombstone(
    val entityType: String,
    val entityId: String,
    val dot: WireDot,
    val deletedAt: String,
)

@Serializable
data class WireSnapshotPayload(
    val kind: String = KIND,
    val previousObjectHash: String?,
    val deviceId: String,
    val versionVector: Map<String, String>,
    val fieldStates: List<WireFieldState>,
    val tombstones: List<WireTombstone>,
    val createdAt: String,
    val requiredCapabilities: List<String> = emptyList(),
) {
    companion object {
        const val KIND = "snapshot"
    }
}

/**
 * device registry項目。端末名は平文でbackendへ置かず、registryが宣言する
 * epochのname keyで暗号化する（脅威モデル5節のprivacy minimization）。
 */
@Serializable
data class WireRegistryDevice(
    val deviceId: String,
    val signingPublicKey: String,
    val hpkePublicKey: String,
    val addedAtGeneration: Int,
    val revokedAtGeneration: Int?,
    val nameNonce: String,
    val nameCiphertext: String,
)

@Serializable
data class WireRegistry(
    val protocolVersion: String,
    val suite: String,
    val libraryOpaqueId: String,
    val registryGeneration: Int,
    val epoch: Int,
    val devices: List<WireRegistryDevice>,
)

@Serializable
data class WireSignedRegistry(
    val registry: WireRegistry,
    val signedByKeyId: String,
    val signature: String,
)

@Serializable
data class WireHead(
    val protocolVersion: String,
    val libraryOpaqueId: String,
    /** head書込みごとに単調増加するlibrary generation（unsigned decimal string）。 */
    val generation: String,
    val epoch: Int,
    val registryGeneration: Int,
    val registryHash: String,
    val deviceLogHeads: Map<String, String>,
    val snapshotObjectId: String?,
)

@Serializable
data class WireSignedHead(
    val head: WireHead,
    val signedByKeyId: String,
    val signature: String,
)

/** 新端末の参加リクエスト。端末名は招待secret由来のkeyで暗号化する。 */
@Serializable
data class WireJoinRequest(
    val protocolVersion: String,
    val suite: String,
    val libraryOpaqueId: String,
    val deviceId: String,
    val signingPublicKey: String,
    val hpkePublicKey: String,
    val inviteNonce: String,
    val nameNonce: String,
    val nameCiphertext: String,
    val createdAt: String,
)

@Serializable
data class WireMacJoinRequest(
    val request: WireJoinRequest,
    val mac: String,
)

/** SYNC_PROTOCOL.md 6.1節のHPKE device key envelope authorization。 */
@Serializable
data class WireKeyAuthorization(
    val protocolVersion: String,
    val suite: String,
    val libraryOpaqueId: String,
    val epoch: Int,
    val registryGeneration: Int,
    val registryHash: String,
    val trustedHeadHash: String,
    val senderSigningKeyId: String,
    val recipientDeviceId: String,
    val recipientHpkePublicKey: String,
    val expiresAt: String,
    val inviteNonce: String,
    val bootstrapSnapshotObjectId: String? = null,
    /** join envelope限定: 招待secret由来keyで暗号化したlibraryId（HKDF salt）。 */
    val libraryIdNonce: String? = null,
    val libraryIdCiphertext: String? = null,
)

@Serializable
data class WireKeyEnvelope(
    val authorization: WireKeyAuthorization,
    val enc: String,
    val ciphertext: String,
    val envelopeId: String,
    val signature: String,
    /**
     * join envelope限定: 招待secretによるHMAC。新端末はregistry未信頼の
     * 段階で、承認者がout-of-band secretを知ることを検証する。
     */
    val inviteMac: String? = null,
)

/** payloadの多態decode。majorが未知・kindが未知なら呼び出し側で拒否する。 */
sealed interface WirePayload {
    data class Operations(
        val payload: WireOperationsPayload,
    ) : WirePayload

    data class Snapshot(
        val payload: WireSnapshotPayload,
    ) : WirePayload
}

fun decodeWirePayload(canonicalJson: ByteArray): WirePayload? {
    val text = canonicalJson.toString(Charsets.UTF_8)
    val kind =
        try {
            SyncWireJson.lenient
                .parseToJsonElement(text)
                .jsonObject["kind"]
                ?.jsonPrimitive
                ?.content
        } catch (_: Exception) {
            null
        } ?: return null
    return try {
        when (kind) {
            WireOperationsPayload.KIND -> {
                WirePayload.Operations(SyncWireJson.lenient.decodeFromString(text))
            }

            WireSnapshotPayload.KIND -> {
                WirePayload.Snapshot(SyncWireJson.lenient.decodeFromString(text))
            }

            else -> {
                null
            }
        }
    } catch (_: Exception) {
        null
    }
}
