package dev.lmadhuranga.tvplayer

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/** Revocable, expiring browser grants. Only token hashes are saved on the TV. */
internal class TrustedBrowserRegistry(context: Context) {
    private val prefs = context.getSharedPreferences("tv_player_trusted_browsers", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    @Synchronized
    fun create(userAgent: String): String {
        val secret = ByteArray(32).also(random::nextBytes)
        val token = Base64.encodeToString(secret, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val saved = validRecords()
        while (saved.length() >= MAX_TRUSTED_BROWSERS) saved.remove(0)
        saved.put(JSONObject()
            .put("hash", digest(token))
            .put("agent", digest(userAgent))
            .put("expires", System.currentTimeMillis() + TRUST_DURATION_MS))
        prefs.edit().putString("records", saved.toString()).commit()
        return token
    }

    @Synchronized
    fun valid(token: String, userAgent: String): Boolean {
        if (token.length !in 32..128 || userAgent.isBlank()) return false
        val records = validRecords()
        val hash = digest(token)
        val agent = digest(userAgent)
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            if (constantTimeEqual(record.optString("hash"), hash) &&
                constantTimeEqual(record.optString("agent"), agent)) return true
        }
        return false
    }

    @Synchronized
    fun forgetAll() {
        prefs.edit().remove("records").commit()
    }

    @Synchronized
    fun count(): Int = validRecords().length()

    private fun validRecords(): JSONArray {
        val parsed = runCatching { JSONArray(prefs.getString("records", "[]")) }.getOrElse { JSONArray() }
        val now = System.currentTimeMillis()
        return JSONArray().apply {
            for (i in 0 until parsed.length()) {
                val item = parsed.optJSONObject(i) ?: continue
                if (item.optLong("expires") > now &&
                    item.optString("hash").length == 64 &&
                    item.optString("agent").length == 64) put(item)
            }
        }
    }

    private fun constantTimeEqual(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        const val TRUST_DURATION_DAYS = 30
        private const val TRUST_DURATION_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_TRUSTED_BROWSERS = 12
    }
}
