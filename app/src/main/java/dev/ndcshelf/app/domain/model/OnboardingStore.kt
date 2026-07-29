package dev.ndcshelf.app.domain.model

/** 初回オンボーディングの完了状態。端末内にだけ保存し、同期・バックアップ対象にしない。 */
interface OnboardingStore {
    fun hasCompleted(): Boolean

    fun markCompleted()
}

class InMemoryOnboardingStore(
    private var completed: Boolean = false,
) : OnboardingStore {
    override fun hasCompleted(): Boolean = completed

    override fun markCompleted() {
        completed = true
    }
}
