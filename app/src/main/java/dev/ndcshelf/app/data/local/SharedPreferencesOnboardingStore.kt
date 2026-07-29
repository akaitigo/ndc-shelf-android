package dev.ndcshelf.app.data.local

import android.content.Context
import androidx.core.content.edit
import dev.ndcshelf.app.domain.model.OnboardingStore

class SharedPreferencesOnboardingStore(
    context: Context,
) : OnboardingStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun hasCompleted(): Boolean = preferences.getBoolean(KEY_COMPLETED, false)

    override fun markCompleted() {
        preferences.edit { putBoolean(KEY_COMPLETED, true) }
    }

    private companion object {
        const val PREFERENCES_NAME = "onboarding"
        const val KEY_COMPLETED = "completed"
    }
}
