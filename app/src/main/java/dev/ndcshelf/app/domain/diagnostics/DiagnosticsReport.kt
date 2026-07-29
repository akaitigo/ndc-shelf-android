package dev.ndcshelf.app.domain.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** 診断ファイルへ含めるセクション。生成前にユーザーが選択する。 */
enum class DiagnosticsSection {
    APP_AND_DEVICE,
    LIBRARY_COUNTS,
    SYNC_STATE,
    CONSENT_STATE,
    RECENT_EVENTS,
}

/**
 * 選択済みセクションだけを含むJSONを組み立てる。値は数値・boolean・enum名・
 * バージョン文字列に限定し、自由形式の文字列を出力しない。
 */
fun buildDiagnosticsReport(
    snapshot: DiagnosticsSnapshot,
    sections: Set<DiagnosticsSection>,
): JsonObject =
    buildJsonObject {
        put("format", JsonPrimitive("ndc-shelf-diagnostics"))
        put("formatVersion", JsonPrimitive(1))
        if (DiagnosticsSection.APP_AND_DEVICE in sections) {
            put(
                "appAndDevice",
                buildJsonObject {
                    put("appVersionName", JsonPrimitive(snapshot.appVersionName))
                    put("appVersionCode", JsonPrimitive(snapshot.appVersionCode))
                    put("androidSdkInt", JsonPrimitive(snapshot.androidSdkInt))
                    put("databaseVersion", JsonPrimitive(snapshot.databaseVersion))
                },
            )
        }
        if (DiagnosticsSection.LIBRARY_COUNTS in sections) {
            put(
                "libraryCounts",
                buildJsonObject {
                    put("works", JsonPrimitive(snapshot.workCount))
                    put("editions", JsonPrimitive(snapshot.editionCount))
                    put("copies", JsonPrimitive(snapshot.copyCount))
                    put("series", JsonPrimitive(snapshot.seriesCount))
                    put("scanSessions", JsonPrimitive(snapshot.scanSessionCount))
                },
            )
        }
        if (DiagnosticsSection.SYNC_STATE in sections) {
            put(
                "syncState",
                buildJsonObject {
                    put("enabled", JsonPrimitive(snapshot.syncEnabled))
                    put("pendingOperations", JsonPrimitive(snapshot.syncPendingOperations))
                    put("unresolvedConflicts", JsonPrimitive(snapshot.syncUnresolvedConflicts))
                    put(
                        "lastSuccessAtMillis",
                        snapshot.syncLastSuccessAtMillis
                            ?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?),
                    )
                },
            )
        }
        if (DiagnosticsSection.CONSENT_STATE in sections) {
            put("consentedPurposes", snapshot.consentedPurposes.toJsonArray())
        }
        if (DiagnosticsSection.RECENT_EVENTS in sections) {
            put(
                "recentEvents",
                buildJsonArray {
                    snapshot.recentEvents.forEach { event ->
                        add(
                            buildJsonObject {
                                put("timestampMillis", JsonPrimitive(event.timestampMillis))
                                put("category", JsonPrimitive(event.code.category.name))
                                put("code", JsonPrimitive(event.code.name))
                            },
                        )
                    }
                },
            )
        }
    }

private fun List<String>.toJsonArray(): JsonArray =
    buildJsonArray {
        forEach { add(JsonPrimitive(it)) }
    }
