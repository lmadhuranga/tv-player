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
import java.net.URI
import java.security.MessageDigest

data class HistoryEntry(
    val id: String,
    val title: String,
    val sourceHost: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
    val mediaUrl: String? = null,
)

private val Context.historyDataStore by preferencesDataStore(name = "playback_history")

/** Stores local labels, direct video links, and resume positions. Subtitle URLs are never persisted. */
class HistoryRepository(private val context: Context) {
    private val enabledKey = booleanPreferencesKey("remember_positions")
    private val entriesKey = stringPreferencesKey("recent_positions")

    val enabled: Flow<Boolean> = context.historyDataStore.data.map { it[enabledKey] ?: true }
    val entries: Flow<List<HistoryEntry>> = context.historyDataStore.data.map { prefs -> decode(prefs[entriesKey]) }

    suspend fun setEnabled(enabled: Boolean) {
        context.historyDataStore.edit { prefs ->
            prefs[enabledKey] = enabled
            if (!enabled) prefs.remove(entriesKey)
        }
    }

    suspend fun save(entry: HistoryEntry) {
        context.historyDataStore.edit { prefs ->
            if (prefs[enabledKey] == false) return@edit
            val current = decode(prefs[entriesKey]).filterNot { it.id == entry.id || (entry.mediaUrl != null && it.mediaUrl?.let(::mediaIdentity) == mediaIdentity(entry.mediaUrl)) }
            prefs[entriesKey] = encode((listOf(entry) + current).take(MAX_ENTRIES))
        }
    }

    fun newEntry(title: String, host: String, url: String? = null): HistoryEntry {
        val replayUrl = url?.takeIf { mediaIdentity(it) != null }
        val identity = replayUrl?.let(::mediaIdentity)
        return HistoryEntry(
            id = identity?.let(::stableId) ?: UUID.randomUUID().toString(),
            title = title.take(100),
            sourceHost = host.take(253),
            positionMs = 0L,
            durationMs = 0L,
            updatedAtMs = System.currentTimeMillis(),
            mediaUrl = replayUrl,
        )
    }

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
                entry.mediaUrl?.let { put("mediaUrl", it) }
            })
        }
    }.toString()

    private fun decode(raw: String?): List<HistoryEntry> = try {
        val json = JSONArray(raw ?: "[]")
        (0 until json.length()).mapNotNull { index ->
            val item = json.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val mediaUrl = item.optString("mediaUrl").takeIf { it.isNotBlank() && mediaIdentity(it) != null }
            val title = item.optString("title", "Video").take(100)
            val host = item.optString("host").take(253)
            HistoryEntry(
                id = mediaUrl?.let(::mediaIdentity)?.let(::stableId) ?: id,
                title = title,
                sourceHost = host,
                positionMs = item.optLong("position").coerceAtLeast(0),
                durationMs = item.optLong("duration").coerceAtLeast(0),
                updatedAtMs = item.optLong("updated").coerceAtLeast(0),
                mediaUrl = mediaUrl,
            )
        }.sortedByDescending(HistoryEntry::updatedAtMs).distinctBy { entry ->
            entry.mediaUrl?.let(::mediaIdentity) ?: "legacy:${entry.title.lowercase()}@${entry.sourceHost.lowercase()}"
        }.take(MAX_ENTRIES)
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        private const val MAX_ENTRIES = 20

        internal fun mediaIdentity(url: String): String? {
            return try {
                val uri = URI(url.trim())
                val scheme = uri.scheme?.lowercase() ?: return null
                val host = uri.host?.lowercase() ?: return null
                if (scheme != "http" && scheme != "https") return null
                val port = when { uri.port == 80 && scheme == "http" -> -1; uri.port == 443 && scheme == "https" -> -1; else -> uri.port }
                val path = (uri.rawPath.orEmpty().ifBlank { "/" }).replace(Regex("/{2,}"), "/").let { if (it.length > 1) it.trimEnd('/') else it }
                "$scheme://$host${if (port >= 0) ":$port" else ""}$path"
            } catch (_: Exception) { null }
        }

        private fun stableId(identity: String): String = "url:" + MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
