package com.xin.flaremusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
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
    /** Fetches the signed-in user's YouTube Music library playlists using the saved Web session. */
    suspend fun fetchFromMusicSession(cookieHeader: String): List<YouTubePlaylist> = withContext(Dispatchers.IO) {
        val userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
        val origin = "https://music.youtube.com"
        val cookies = cookieHeader.split(';').mapNotNull {
            val part = it.trim()
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
        }.toMap()
        val sapisid = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"]
            ?: throw IllegalStateException("YouTube Music session is missing its authentication cookie. Reconnect your account.")

        // Get the current web client's API key/version rather than hard-coding stale values.
        val pageRequest = Request.Builder().url(origin).header("Cookie", cookieHeader)
            .header("User-Agent", userAgent).get().build()
        val (apiKey, clientVersion) = http.newCall(pageRequest).execute().use { response ->
            val html = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Couldn't load YouTube Music config: HTTP ${response.code}")
            val key = Regex("""["']INNERTUBE_API_KEY["']\\s*:\\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: throw IllegalStateException("YouTube Music did not provide an API key. Please try again later.")
            val version = Regex("""["']INNERTUBE_CLIENT_VERSION["']\\s*:\\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: "1.20260304.03.00"
            key to version
        }

        val timestamp = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1").digest("$timestamp $sapisid $origin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", clientVersion)
                .put("hl", "en").put("gl", "US")))
            .put("browseId", "FEmusic_library_playlists").toString()
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("music.youtube.com")
            .addPathSegments("youtubei/v1/browse").addQueryParameter("key", apiKey)
            .addQueryParameter("prettyPrint", "false").build()
        val request = Request.Builder().url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Cookie", cookieHeader)
            .header("Authorization", "SAPISIDHASH ${timestamp}_$digest")
            .header("Origin", origin)
            .header("X-Origin", origin)
            .header("User-Agent", userAgent)
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("YouTube Music library failed: HTTP ${response.code} - ${raw.take(350)}")
            val root = JSONObject(raw)
            val found = mutableListOf<YouTubePlaylist>()
            fun text(value: JSONObject?): String {
                if (value == null) return ""
                value.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
                val runs = value.optJSONArray("runs") ?: return ""
                return buildString { for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty()) }
            }
            fun walk(value: Any?) {
                when (value) {
                    is JSONObject -> {
                        val renderer = value.optJSONObject("musicTwoRowItemRenderer")
                            ?: value.optJSONObject("gridPlaylistRenderer")
                            ?: value.optJSONObject("playlistRenderer")
                        if (renderer != null) {
                            val title = text(renderer.optJSONObject("title"))
                            val navigation = renderer.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("browseEndpoint")
                            val id = navigation?.optString("browseId").orEmpty()
                                .ifBlank { renderer.optString("playlistId") }
                            if (id.isNotBlank() && title.isNotBlank()) {
                                val thumbnail = renderer.optJSONObject("thumbnail")
                                    ?.optJSONArray("thumbnails")
                                    ?.optJSONObject(0)?.optString("url").orEmpty()
                                val subtitle = text(renderer.optJSONObject("subtitle"))
                                found.add(YouTubePlaylist(id, title, subtitle, 0, thumbnail))
                            }
                        }
                        val keys = value.keys()
                        while (keys.hasNext()) walk(value.opt(keys.next()))
                    }
                    is org.json.JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
                }
            }
            walk(root)
            found.distinctBy { it.id }
        }
    }

}
