package com.xin.flaremusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class YouTubePlaylist(val id: String, val title: String, val description: String, val itemCount: Int, val thumbnail: String)

object YouTubePlaylists {
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    suspend fun fetch(accessToken: String): List<YouTubePlaylist> = withContext(Dispatchers.IO) {
        val playlists = mutableListOf<YouTubePlaylist>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("https://www.googleapis.com/youtube/v3/playlists?part=snippet,contentDetails&mine=true&maxResults=50")
                if (pageToken != null) append("&pageToken=").append(URLEncoder.encode(pageToken, "UTF-8"))
            }
            val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException("YouTube playlists failed: HTTP ${response.code} - ${body.take(250)}")
                val root = JSONObject(body)
                val items = root.optJSONArray("items")
                for (i in 0 until (items?.length() ?: 0)) {
                    val item = items!!.optJSONObject(i) ?: continue
                    val snippet = item.optJSONObject("snippet") ?: JSONObject()
                    val thumbs = snippet.optJSONObject("thumbnails")
                    val thumb = listOf("maxres", "high", "medium", "default").firstNotNullOfOrNull { key ->
                        thumbs?.optJSONObject(key)?.optString("url")?.takeIf { it.isNotBlank() }
                    }.orEmpty()
                    playlists += YouTubePlaylist(
                        item.optString("id"),
                        snippet.optString("title", "Untitled playlist"),
                        snippet.optString("description"),
                        item.optJSONObject("contentDetails")?.optInt("itemCount") ?: 0,
                        thumb
                    )
                }
                pageToken = root.optString("nextPageToken").takeIf { it.isNotBlank() }
            }
        } while (pageToken != null && playlists.size < 500)
        playlists
    }
}
