package com.tvplayer.app

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.playerPrefs by preferencesDataStore(name = "tv_player")

class PlaybackPrefs(private val context: Context) {
    private val saveUrlsKey = booleanPreferencesKey("save_full_urls")
    private val historyKey = stringPreferencesKey("recent_items")
    private val subtitleSizeKey = floatPreferencesKey("subtitles_size")
    private val subtitleColorKey = intPreferencesKey("subtitles_color")
    private val subtitleBgKey = booleanPreferencesKey("subtitles_background")
    private val subtitleOffsetKey = longPreferencesKey("subtitles_offset")
    private val displayKey = stringPreferencesKey("display_mode")

    val settings: Flow<AppSettings> = context.playerPrefs.data.map { prefs ->
        AppSettings(
            saveFullUrls = prefs[saveUrlsKey] ?: false,
            subtitleSize = prefs[subtitleSizeKey] ?: 1f,
            subtitleColor = prefs[subtitleColorKey] ?: 0xffffffff.toInt(),
            subtitleBackground = prefs[subtitleBgKey] ?: true,
            subtitleOffsetMs = prefs[subtitleOffsetKey] ?: 0L,
            displayMode = prefs[displayKey] ?: "Fit"
        )
    }
    val history: Flow<List<RecentItem>> = context.playerPrefs.data.map { prefs ->
        decode(prefs[historyKey] ?: "[]")
    }

    suspend fun updateSettings(settings: AppSettings) {
        context.playerPrefs.edit {
            it[saveUrlsKey] = settings.saveFullUrls
            it[subtitleSizeKey] = settings.subtitleSize
            it[subtitleColorKey] = settings.subtitleColor
            it[subtitleBgKey] = settings.subtitleBackground
            it[subtitleOffsetKey] = settings.subtitleOffsetMs
            it[displayKey] = settings.displayMode
            if (!settings.saveFullUrls) {
                it[historyKey] = encode(decode(it[historyKey] ?: "[]").map { item ->
                    item.copy(savedVideoUrl = null, savedSubtitleUrl = null)
                })
            }
        }
    }

    suspend fun record(entry: VideoEntry, positionMs: Long, saveUrls: Boolean) {
        val id = UrlRules.sourceId(entry.videoUrl)
        context.playerPrefs.edit { data ->
            val old = decode(data[historyKey] ?: "[]")
            val item = RecentItem(
                id, UrlRules.sanitizedLabel(entry.videoUrl), positionMs.coerceAtLeast(0),
                System.currentTimeMillis(),
                if (saveUrls) entry.videoUrl else null,
                if (saveUrls) entry.subtitleUrl else null
            )
            data[historyKey] = encode((listOf(item) + old.filterNot { it.id == id }).take(20))
        }
    }

    suspend fun clearHistory() { context.playerPrefs.edit { it.remove(historyKey) } }

    private fun encode(items: List<RecentItem>): String = JSONArray().apply {
        items.forEach { item -> add(this, item) }
    }.toString()

    private fun add(array: JSONArray, item: RecentItem) {
        array.put(JSONObject().apply {
            put("id", item.id); put("label", item.label)
            put("position", item.positionMs); put("updated", item.updatedMs)
            item.savedVideoUrl?.let { put("video", it) }
            item.savedSubtitleUrl?.let { put("subtitle", it) }
        })
    }

    private fun decode(raw: String): List<RecentItem> = try {
        val array = JSONArray(raw)
        (0 until minOf(array.length(), 20)).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id")
            if (id.isBlank()) null else RecentItem(
                id, obj.optString("label", "Recent video"), obj.optLong("position"),
                obj.optLong("updated"), obj.optString("video").ifBlank { null },
                obj.optString("subtitle").ifBlank { null }
            )
        }
    } catch (_: Exception) { emptyList() }
}
