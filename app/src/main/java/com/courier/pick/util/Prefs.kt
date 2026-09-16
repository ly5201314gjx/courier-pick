package com.courier.pick.util

import android.content.Context
import com.courier.pick.model.ScanEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tiny persistence layer (no extra deps) for:
 *  - scan history (last N recognized runs)
 *  - favorite tracking numbers (one-tap fill)
 */
class Prefs(context: Context) {

    private val prefs = context.getSharedPreferences("courier_pick", Context.MODE_PRIVATE)

    private val pendingIds = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

    // ---------------- History ----------------

    fun history(): List<ScanEntry> {
        val raw = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun addHistory(entry: ScanEntry) {
        val all = history().toMutableList()
        all.add(0, entry.copy(id = entry.id.takeIf { it > 0 } ?: pendingIds.incrementAndGet()))
        val trimmed = all.take(MAX_HISTORY)
        prefs.edit().putString(KEY_HISTORY, JSONArray().apply {
            trimmed.forEach { put(it.toJson()) }
        }.toString()).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    fun removeHistory(id: Long) {
        prefs.edit().putString(
            KEY_HISTORY,
            JSONArray().apply {
                history().filter { it.id != id }.forEach { put(it.toJson()) }
            }.toString()
        ).apply()
    }

    // ---------------- Favorites ----------------

    fun favorites(): List<String> = prefs.getString(
        KEY_FAVORITES, ""
    )?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

    fun addFavorite(number: String) {
        val n = number.trim()
        if (n.isBlank()) return
        val current = favorites().toMutableList()
        if (current.contains(n)) return
        current.add(0, n)
        prefs.edit().putString(KEY_FAVORITES, current.joinToString("\n")).apply()
    }

    fun removeFavorite(number: String) {
        prefs.edit().putString(
            KEY_FAVORITES,
            favorites().filter { it != number }.joinToString("\n")
        ).apply()
    }

    fun isFavorite(number: String): Boolean = favorites().contains(number.trim())

    private companion object {
        const val KEY_HISTORY = "KEY_HISTORY"
        const val KEY_FAVORITES = "KEY_FAVORITES"
        const val MAX_HISTORY = 60

        fun ScanEntry.toJson(): JSONObject = JSONObject()
            .put("id", id).put("ts", ts)
            .put("title", title).put("detail", detail)
            .put("matched", matched).put("algorithm", algorithm)

        fun fromJson(o: JSONObject): ScanEntry = ScanEntry(
            id = o.optLong("id"),
            ts = o.optLong("ts"),
            title = o.optString("title"),
            detail = o.optString("detail"),
            matched = o.optBoolean("matched"),
            algorithm = o.optString("algorithm")
        )
    }
}