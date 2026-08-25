package com.moji.app.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Context.dataStore by preferencesDataStore("moji_settings")

data class UserSettings(
    val onboardingDone: Boolean = false,
    val darkTheme: Boolean = false,
    val captureConsent: Boolean = false,
    val debugCaptureUntil: Long = 0,
    val hideRecents: Boolean = false
)

class MojiSettings(private val context: Context) {
    private object Keys {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val darkTheme = booleanPreferencesKey("dark_theme")
        val captureConsent = booleanPreferencesKey("capture_consent")
        val debugCaptureUntil = longPreferencesKey("debug_capture_until")
        val hideRecents = booleanPreferencesKey("hide_recents")
    }

    val values: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            onboardingDone = prefs[Keys.onboardingDone] ?: false,
            darkTheme = prefs[Keys.darkTheme] ?: false,
            captureConsent = prefs[Keys.captureConsent] ?: false,
            debugCaptureUntil = prefs[Keys.debugCaptureUntil] ?: 0,
            hideRecents = prefs[Keys.hideRecents] ?: false
        )
    }

    suspend fun completeOnboarding() = context.dataStore.edit { it[Keys.onboardingDone] = true }
    suspend fun setDarkTheme(enabled: Boolean) = context.dataStore.edit { it[Keys.darkTheme] = enabled }
    suspend fun setCaptureConsent(enabled: Boolean) = context.dataStore.edit { it[Keys.captureConsent] = enabled }
    suspend fun enableDebugCapture() {
        withContext(Dispatchers.IO) { context.filesDir.resolve("debug-capture.jsonl").delete() }
        context.dataStore.edit {
            it[Keys.debugCaptureUntil] = System.currentTimeMillis() + 24 * 60 * 60 * 1000L
        }
    }
    suspend fun setHideRecents(enabled: Boolean) = context.dataStore.edit { it[Keys.hideRecents] = enabled }
    suspend fun backupValues(): UserSettings = values.first()
    suspend fun restoreAppearance(darkTheme: Boolean, hideRecents: Boolean) = context.dataStore.edit {
        it[Keys.darkTheme] = darkTheme
        it[Keys.hideRecents] = hideRecents
        it[Keys.onboardingDone] = true
    }
}
