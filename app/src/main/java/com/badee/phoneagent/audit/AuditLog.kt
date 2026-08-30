package com.badee.phoneagent.audit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class AuditEntry(
    val timestamp: String,
    val action: String,
    val success: Boolean,
    val detail: String,
)

class AuditLog(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun record(action: String, success: Boolean, detail: String) {
        val entries = readJson()
        entries.put(
            JSONObject()
                .put("timestamp", Instant.now().toString())
                .put("action", action.take(MAX_ACTION_LENGTH))
                .put("success", success)
                .put("detail", detail.take(MAX_DETAIL_LENGTH)),
        )

        val trimmed = JSONArray()
        val first = (entries.length() - MAX_ENTRIES).coerceAtLeast(0)
        for (index in first until entries.length()) trimmed.put(entries.getJSONObject(index))
        preferences.edit().putString(KEY_ENTRIES, trimmed.toString()).apply()
    }

    @Synchronized
    fun latest(limit: Int = 20): List<AuditEntry> {
        val entries = readJson()
        val result = mutableListOf<AuditEntry>()
        val first = (entries.length() - limit.coerceIn(1, MAX_ENTRIES)).coerceAtLeast(0)
        for (index in entries.length() - 1 downTo first) {
            val item = entries.optJSONObject(index) ?: continue
            result += AuditEntry(
                timestamp = item.optString("timestamp"),
                action = item.optString("action"),
                success = item.optBoolean("success"),
                detail = item.optString("detail"),
            )
        }
        return result
    }

    private fun readJson(): JSONArray = runCatching {
        JSONArray(preferences.getString(KEY_ENTRIES, "[]"))
    }.getOrDefault(JSONArray())

    private companion object {
        const val PREFERENCES_NAME = "agent_audit"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 200
        const val MAX_ACTION_LENGTH = 64
        const val MAX_DETAIL_LENGTH = 240
    }
}
