package com.xin.flaremusic

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class FlareRelease(val tag: String, val name: String, val notes: String, val url: String)

object FlareUpdater {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun latestRelease(): FlareRelease {
        val request = Request.Builder()
            .url("https://api.github.com/repos/NotKrishEnough/FlareMusic/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "FlareMusic-Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub returned HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: error("Empty response"))
            return FlareRelease(
                tag = json.optString("tag_name"),
                name = json.optString("name").ifBlank { json.optString("tag_name") },
                notes = json.optString("body").ifBlank { "No release notes provided." },
                url = json.optString("html_url")
            )
        }
    }
}
