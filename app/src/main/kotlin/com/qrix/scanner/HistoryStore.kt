package com.qrix.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Record(
    val id: Long,
    val text: String,
    val format: String,
    val kind: Int,
    val time: Long,
    val fav: Boolean = false
) {
    companion object {
        const val SCANNED = 0
        const val GENERATED = 1
    }
}

/** Tiny JSON-in-SharedPreferences store. Newest first, max 500 entries. */
object HistoryStore {
    private const val KEY = "items"
    private const val MAX = 500

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("qrix_history", Context.MODE_PRIVATE)

    @Synchronized
    fun all(c: Context): List<Record> {
        val raw = sp(c).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Record(
                    o.getLong("id"), o.getString("text"), o.getString("format"),
                    o.getInt("kind"), o.getLong("time"), o.optBoolean("fav", false)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun list(c: Context, kind: Int?): List<Record> =
        all(c).filter { kind == null || it.kind == kind }

    fun favorites(c: Context): List<Record> = all(c).filter { it.fav }

    fun isFav(c: Context, id: Long): Boolean = all(c).firstOrNull { it.id == id }?.fav == true

    @Synchronized
    fun setFav(c: Context, id: Long, fav: Boolean) {
        save(c, all(c).map { if (it.id == id) it.copy(fav = fav) else it })
    }

    @Synchronized
    fun clearFavorites(c: Context) {
        save(c, all(c).filterNot { it.fav })
    }

    @Synchronized
    fun add(c: Context, text: String, format: String, kind: Int): Record {
        val now = System.currentTimeMillis()
        val existing = all(c)
        val same = { r: Record -> r.kind == kind && r.text == text && r.format == format }
        // A code that was saved as favourite stays a favourite when it is scanned again.
        val rec = Record(now, text, format, kind, now, existing.any { same(it) && it.fav })
        val list = existing.filterNot(same).toMutableList()
        list.add(0, rec)
        save(c, list.take(MAX))
        return rec
    }

    @Synchronized
    fun restore(c: Context, rec: Record) {
        save(c, (all(c) + rec).sortedByDescending { it.time }.take(MAX))
    }

    @Synchronized
    fun delete(c: Context, id: Long) {
        save(c, all(c).filterNot { it.id == id })
    }

    @Synchronized
    fun clear(c: Context, kind: Int?) {
        save(c, if (kind == null) emptyList() else all(c).filterNot { it.kind == kind })
    }

    private fun save(c: Context, list: List<Record>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("text", it.text).put("format", it.format)
                    .put("kind", it.kind).put("time", it.time).put("fav", it.fav)
            )
        }
        sp(c).edit().putString(KEY, arr.toString()).apply()
    }
}
