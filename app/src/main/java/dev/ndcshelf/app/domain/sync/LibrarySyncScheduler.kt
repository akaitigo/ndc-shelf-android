package dev.ndcshelf.app.domain.sync

/**
 * opt-in時だけ定期同期workを登録し、停止・撤回でcancelする。
 * SeriesWatchSchedulerと同じ形の抽象で、workerは実行時にも同意を再検査する。
 */
fun interface LibrarySyncScheduler {
    fun reconcile(enabled: Boolean)
}
