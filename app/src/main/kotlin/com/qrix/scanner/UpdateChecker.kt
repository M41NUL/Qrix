/*
 * File   : UpdateChecker.kt
 * Owner  : MAINUL (CODEX-M41NUL)
 * GitHub : M41NUL
 * About  : Reads update info from Firebase Realtime Database (REST, no SDK needed)
 */
package com.qrix.scanner

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    /** Your Realtime Database URL, no trailing slash. */
    const val DB_URL = "https://qrix-apps-default-rtdb.firebaseio.com"

    /** Node holding download_link, latest_version, update_message. Use "/.json" for the database root. */
    private const val PATH = "/qrix.json"

    class Info(
        val latestVersion: String,
        val downloadLink: String,
        val message: String
    )

    private val main = Handler(Looper.getMainLooper())

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0.0"
    } catch (e: Exception) {
        "1.0.0"
    }

    /** True when [latest] is higher than [current] (compares 1.0.3 style numbers part by part). */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.trim().split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val b = current.trim().split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Splits update_message into lines: new line, "|" or a typed \\n all work. */
    fun fixes(message: String): List<String> =
        message.split(Regex("\\r?\\n|\\\\n|\\|")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Calls [done] on the main thread: (info, null) on success, (null, message) on failure. */
    fun fetch(done: (Info?, String?) -> Unit) {
        Thread {
            var info: Info? = null
            var error: String? = null
            try {
                val c = URL(DB_URL + PATH).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                try {
                    if (c.responseCode != 200) {
                        error = "Server error (${c.responseCode})"
                    } else {
                        val body = c.inputStream.bufferedReader().use { it.readText() }.trim()
                        val j = if (body.isEmpty() || body == "null") null else JSONObject(body)
                        val v = j?.optString("latest_version", "").orEmpty()
                        if (j == null || v.isEmpty()) {
                            error = "No update info found"
                        } else {
                            info = Info(
                                latestVersion = v,
                                downloadLink = j.optString("download_link", ""),
                                message = j.optString("update_message", "")
                            )
                        }
                    }
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                error = "Couldn't reach the server"
            }
            main.post { done(info, error) }
        }.start()
    }
}
