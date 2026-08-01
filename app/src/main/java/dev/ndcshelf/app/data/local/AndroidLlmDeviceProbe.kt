package dev.ndcshelf.app.data.local

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import dev.ndcshelf.app.domain.ai.llm.LlmDeviceProfile
import java.io.File

/**
 * 端末能力の実測。取得できない値は「満たさない」側へ倒す（fail-closed）。
 *
 * [runtimeAvailable]は推論runtimeのネイティブライブラリを読み込めるかで、
 * runtimeを同梱しないビルドでは常にfalseになる。
 */
class AndroidLlmDeviceProbe(
    private val context: Context,
    private val storageDirectory: File = context.noBackupFilesDir,
    private val runtimeAvailable: () -> Boolean = { false },
) {
    fun profile(): LlmDeviceProfile =
        LlmDeviceProfile(
            sdkInt = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            totalRamBytes = totalRamBytes(),
            availableStorageBytes = availableStorageBytes(),
            isLowRamDevice = isLowRamDevice(),
            runtimeAvailable = runCatching(runtimeAvailable).getOrDefault(false),
        )

    private fun totalRamBytes(): Long =
        runCatching {
            val activityManager = context.getSystemService<ActivityManager>() ?: return@runCatching 0L
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            info.totalMem
        }.getOrDefault(0L)

    private fun availableStorageBytes(): Long = runCatching { storageDirectory.usableSpace }.getOrDefault(0L)

    private fun isLowRamDevice(): Boolean =
        runCatching {
            context.getSystemService<ActivityManager>()?.isLowRamDevice ?: true
        }.getOrDefault(true)
}
