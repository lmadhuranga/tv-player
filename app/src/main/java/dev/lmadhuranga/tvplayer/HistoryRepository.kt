package dev.lmadhuranga.tvplayer

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HistoryEntry(
    val id: String,
    val title: String,
    val sourceHost: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
)

private val Context.historyDataStore by preferencesDataStore(name = "playback_history")

/** Stores local labels and resume positions only. Media and subtitle URLs are never persisted. */
class HistoryRepository(private val context: Context) {
    private val enabledKey = booleanPreferencesKey("remember_positions")
    private val entriesKey = stringPreferencesKey("recent_positions")

    val enabled: Flow<Boolean> = context.historyDataStore.data.map { it[enabledKey] ?: false }
    val entries: Flow<List<HistoryEntry>> = context.historyDataStore.data.map { prefs -> decode(prefs[entriesKey]) }

    suspend fun setEnabled(enabled: Boolean) {
        context.historyDataStore.edit { prefs ->
            prefs[enabledKey] = enabled
            if (!enabled) prefs.remove(entriesKey)
        }
    }

    suspend fun save(entry: HistoryEntry) {
        context.historyDataStore.edit { prefs ->
            if (prefs[enabledKey] != true) return@edit
            val current = decode(prefs[entriesKey]).filterNot { it.id == entry.id }
            prefs[entriesKey] = encode((listOf(entry) + current).take(MAX_ENTRIES))
        }
    }

    fun newEntry(title: String, host: String): HistoryEntry = HistoryEntry(
        id = UUID.randomUUID().toString(), title = title.take(100), sourceHost = host.take(253),
        positionMs = 0L, durationMs = 0L, updatedAtMs = System.currentTimeMillis(),
    )

    suspend fun clear() {
        context.historyDataStore.edit { it.remove(entriesKey) }
    }

    private fun encode(entries: List<HistoryEntry>): String = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().apply {
                put("id", entry.id)
                put("title", entry.title)
                put("host", entry.sourceHost)
                put("position", entry.positionMs)
                put("duration", entry.durationMs)
                put("updated", entry.updatedAtMs)
            })
        }
    }.toString()

    private fun decode(raw: String?): List<HistoryEntry> = try {
        val json = JSONArray(raw ?: "[]")
        (0 until json.length()).mapNotNull { index ->
            val item = json.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            HistoryEntry(
                id = id,
                title = item.optString("title", "Video").take(100),
                sourceHost = item.optString("host").take(253),
                positionMs = item.optLong("position").coerceAtLeast(0),
                durationMs = item.optLong("duration").coerceAtLeast(0),
                updatedAtMs = item.optLong("updated").coerceAtLeast(0),
            )
        }.sortedByDescending(HistoryEntry::updatedAtMs).take(MAX_ENTRIES)
    } catch (_: Exception) {
        emptyList()
    }

    companion object { private const val MAX_ENTRIES = 20 }
}
