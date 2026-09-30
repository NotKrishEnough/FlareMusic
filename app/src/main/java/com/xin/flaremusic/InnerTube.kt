package com.xin.flaremusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class OnlineTrack(val videoId: String, val title: String, val author: String, val duration: String, val thumbnail: String) {
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$videoId"
}

/**
 * Unofficial YouTube InnerTube client. Google can change this private API at any time.
 * It does not bypass DRM, sign-in restrictions, age gates, or region restrictions.
 */
class InnerTubeClient {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val endpoint = "https://www.youtube.com/youtubei/v1"
    private val client = JSONObject().put("clientName", "WEB").put("clientVersion", "2.20250626.01.00").put("hl", "en").put("gl", "US")

    suspend fun search(query: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Enter a search term" }
        val body = JSONObject().put("context", JSONObject().put("client", client)).put("query", query).toString()
        val request = Request.Builder().url("$endpoint/search?prettyPrint=false").post(body.toRequestBody(jsonType)).header("User-Agent", "com.google.android.youtube/19.09.37 (Linux; U; Android 14)").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("InnerTube search failed: HTTP ${response.code} - ${response.body?.string()?.take(300)}")
            val root = JSONObject(response.body?.string() ?: "{}")
            val found = mutableListOf<OnlineTrack>()
            collectVideos(root, found)
            found.distinctBy { it.videoId }.take(30)
        }
    }

    private fun collectVideos(value: Any?, out: MutableList<OnlineTrack>) {
        when (value) {
            is JSONObject -> {
                if (value.has("videoId") && value.optString("videoId").isNotBlank()) {
                    val id = value.optString("videoId")
                    val title = text(value.optJSONObject("title")) ?: "Unknown title"
                    val owner = text(value.optJSONObject("ownerText")) ?: text(value.optJSONObject("shortBylineText")) ?: "YouTube"
                    val length = text(value.optJSONObject("lengthText")) ?: ""
                    val thumbs = value.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    val thumb = thumbs?.optJSONObject((thumbs.length() - 1).coerceAtLeast(0))?.optString("url") ?: ""
                    out += OnlineTrack(id, title, owner, length, thumb)
                }
                val keys = value.keys()
                while (keys.hasNext()) collectVideos(value.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until value.length()) collectVideos(value.opt(i), out)
        }
    }

    private fun text(obj: JSONObject?): String? {
        if (obj == null) return null
        obj.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = obj.optJSONArray("runs") ?: return null
        return buildString { for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text") ?: "") }.takeIf { it.isNotBlank() }
    }

    /** Returns a direct progressive stream URL when the API provides one; many results use ciphered URLs. */
    suspend fun resolveProgressiveUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("context", JSONObject().put("client", client)).put("videoId", videoId).toString()
        val request = Request.Builder().url("$endpoint/player?prettyPrint=false").post(body.toRequestBody(jsonType)).header("User-Agent", "com.google.android.youtube/19.09.37 (Linux; U; Android 14)").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Stream lookup failed: HTTP ${response.code} - ${response.body?.string()?.take(300)}")
            val root = JSONObject(response.body?.string() ?: "{}")
            val status = root.optJSONObject("playabilityStatus")?.optString("status")
            if (status != "OK") return@withContext null
            val formats = root.optJSONObject("streamingData")?.optJSONArray("formats") ?: return@withContext null
            for (i in 0 until formats.length()) {
                val item = formats.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (url.startsWith("https://")) return@withContext url
            }
            null
        }
    }
}
