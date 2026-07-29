package dev.ndcshelf.app.data.sync

import dev.ndcshelf.app.domain.sync.SyncVersionVector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object SyncJsonCodec {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    fun encodeFields(fields: Map<String, JsonElement>): String = JsonObject(fields.toSortedMap()).toString()

    fun decodeFields(value: String): Map<String, JsonElement> =
        json.parseToJsonElement(value).jsonObject.toMap()

    fun encodeVector(vector: SyncVersionVector): String = JsonObject(
        vector.counters.toSortedMap().mapValues { (_, counter) ->
            kotlinx.serialization.json.JsonPrimitive(counter.toString())
        },
    ).toString()

    fun decodeVector(value: String): SyncVersionVector = SyncVersionVector(
        json.parseToJsonElement(value).jsonObject.mapValues { (_, counter) ->
            counter.jsonPrimitive.content.toLong().also { require(it >= 0) }
        },
    )
}
