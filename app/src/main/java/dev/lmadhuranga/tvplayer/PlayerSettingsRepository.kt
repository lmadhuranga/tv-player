package dev.lmadhuranga.tvplayer

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playerSettingsDataStore by preferencesDataStore(name = "player_settings")

/** Persists playback policy preferences that do not contain media URLs or credentials. */
class PlayerSettingsRepository(private val context: Context) {
    private val bufferMinutesKey = intPreferencesKey("maximum_buffer_minutes")

    val maximumBufferMinutes: Flow<Int> = context.playerSettingsDataStore.data.map { preferences ->
        preferences[bufferMinutesKey]?.takeIf { it in BUFFER_CHOICES } ?: DEFAULT_BUFFER_MINUTES
    }

    suspend fun setMaximumBufferMinutes(minutes: Int) {
        require(minutes in BUFFER_CHOICES)
        context.playerSettingsDataStore.edit { preferences -> preferences[bufferMinutesKey] = minutes }
    }

    companion object {
        const val DEFAULT_BUFFER_MINUTES = 1
        val BUFFER_CHOICES = setOf(1, 2, 5)
    }
}
