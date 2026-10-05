package com.xin.flaremusic

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SyncedLyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class SyncedLyrics(
    val source: String,
    val lines: List<SyncedLyricLine>
)

object LyricsRepository {
    private val http = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val cache = ConcurrentHashMap<String, SyncedLyrics>()

    suspend fun fetch(track: Track): SyncedLyrics? = withContext(Dispatchers.IO) {
        val key = "${track.title.trim().lowercase(Locale.ROOT)}|${track.artist.trim().lowercase(Locale.ROOT)}"
        cache[key]?.let { return@withContext it }

        val durationSeconds = (track.duration / 1000L).toInt().takeIf { it > 0 }
        val results = coroutineScope {
            val better = async { runCatching { fetchBetterLyrics(track, durationSeconds) }.getOrNull() }
            val kugou = async { runCatching { fetchBetterLyricsKugou(track, durationSeconds) }.getOrNull() }
            val lrc = async { runCatching { fetchLrcLib(track, null) }.getOrNull() }
            val search = async { runCatching { searchLrcLib(track) }.getOrNull() }
            listOf(better.await(), kugou.await(), lrc.await(), search.await()).filterNotNull()
        }

        val best = results.maxByOrNull { it.lines.size }?.takeIf { it.lines.isNotEmpty() }
        if (best != null) cache[key] = best
        best
    }

    private fun fetchBetterLyrics(track: Track, duration: Int?): SyncedLyrics? {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api.betterlyrics.org")
            .addPathSegments("getLyrics")
            .addQueryParameter("s", track.title)
            .addQueryParameter("a", track.artist)
            .apply { if (duration != null) addQueryParameter("d", duration.toString()) }
            .apply { if (track.album.isNotBlank()) addQueryParameter("al", track.album) }
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "FlareMusic/1.0")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val raw = response.body?.string().orEmpty()
            val ttml = runCatching { JSONObject(raw).optString("ttml") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val lines = parseTtml(ttml)
            return lines.takeIf { it.size >= 2 }?.let { SyncedLyrics("BetterLyrics", it) }
        }
    }

    private fun fetchBetterLyricsKugou(track: Track, duration: Int?): SyncedLyrics? {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api.betterlyrics.org")
            .addPathSegments("kugou/getLyrics")
            .addQueryParameter("s", track.title)
            .addQueryParameter("a", track.artist)
            .apply { if (duration != null) addQueryParameter("d", duration.toString()) }
            .apply { if (track.album.isNotBlank()) addQueryParameter("al", track.album) }
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "FlareMusic/1.0")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body?.string().orEmpty())
            val lrc = json.optString("lyrics").takeIf { it.isNotBlank() } ?: return null
            val lines = parseLrc(lrc)
            return lines.takeIf { it.size >= 2 }?.let { SyncedLyrics("BetterLyrics • Kugou", it) }
        }
    }

    private fun fetchLrcLib(track: Track, duration: Int?): SyncedLyrics? {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("lrclib.net")
            .addPathSegments("api/get")
            .addQueryParameter("track_name", track.title)
            .addQueryParameter("artist_name", track.artist)
            .apply { if (track.album.isNotBlank()) addQueryParameter("album_name", track.album) }
            .apply { if (duration != null) addQueryParameter("duration", duration.toString()) }
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "FlareMusic/1.0")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body?.string().orEmpty())
            val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() } ?: return null
            val lines = parseLrc(synced)
            return lines.takeIf { it.size >= 2 }?.let { SyncedLyrics("LRCLIB", it) }
        }
    }

    private fun searchLrcLib(track: Track): SyncedLyrics? {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("lrclib.net")
            .addPathSegments("api/search")
            .addQueryParameter("track_name", track.title)
            .addQueryParameter("artist_name", track.artist)
            .addQueryParameter("q", track.title)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "FlareMusic/1.0 (Android)")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val array = org.json.JSONArray(response.body?.string().orEmpty())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val synced = item.optString("syncedLyrics").takeIf { it.isNotBlank() } ?: continue
                val lines = parseLrc(synced)
                if (lines.size >= 2) return SyncedLyrics("LRCLIB • Search", lines)
            }
            return null
        }
    }

    private fun parseLrc(lrc: String): List<SyncedLyricLine> {
        val parsed = mutableListOf<Pair<Long, String>>()
        val regex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\](.*)""")
        lrc.lineSequence().forEach { raw ->
            val match = regex.find(raw.trim()) ?: return@forEach
            val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
            val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
            val fraction = match.groupValues[3].takeIf { it.isNotBlank() }?.let {
                when (it.length) {
                    1 -> it.toLong() * 100
                    2 -> it.toLong() * 10
                    else -> it.take(3).toLong()
                }
            } ?: 0L
            val text = match.groupValues[4].trim()
            if (text.isNotBlank()) parsed += ((minutes * 60 + seconds) * 1000L + fraction) to text
        }
        val ordered = parsed.sortedBy { it.first }
        return ordered.mapIndexedNotNull { index, (start, text) ->
            val next = ordered.getOrNull(index + 1)?.first ?: (start + 5000L)
            SyncedLyricLine(start, maxOf(start + 500L, next), text)
        }
    }

    private fun parseTtml(ttml: String): List<SyncedLyricLine> {
        val parser = Xml.newPullParser()
        parser.setInput(ttml.reader())
        val lines = mutableListOf<SyncedLyricLine>()
        var currentStart: Long? = null
        var currentEnd: Long? = null
        var currentText = StringBuilder()
        var depth = 0

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name.equals("p", true)) {
                        depth = 1
                        currentStart = parseTtmlTime(parser.getAttributeValue(null, "begin"))
                        currentEnd = parseTtmlTime(parser.getAttributeValue(null, "end"))
                        currentText = StringBuilder()
                    } else if (depth > 0) {
                        depth++
                    }
                }
                XmlPullParser.TEXT -> if (depth > 0) currentText.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("p", true) && depth > 0) {
                        val start = currentStart
                        val text = currentText.toString().replace(Regex("\\s+"), " ").trim()
                        if (start != null && text.isNotBlank()) {
                            val end = currentEnd ?: (start + 5000L)
                            lines += SyncedLyricLine(start, maxOf(start + 500L, end), text)
                        }
                        depth = 0
                    } else if (depth > 0) {
                        depth--
                    }
                }
            }
            parser.next()
        }

        val ordered = lines.sortedBy { it.startMs }
        return ordered.mapIndexed { index, line ->
            val next = ordered.getOrNull(index + 1)?.startMs
            if (next != null && line.endMs > next) line.copy(endMs = next) else line
        }
    }

    private fun parseTtmlTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val raw = value.trim()
        raw.toDoubleOrNull()?.let { return (it * 1000.0).toLong() }
        if (raw.endsWith("ms")) return raw.removeSuffix("ms").toDoubleOrNull()?.toLong()
        if (raw.endsWith("s")) return (raw.removeSuffix("s").toDoubleOrNull()?.times(1000.0))?.toLong()
        val parts = raw.split(":")
        if (parts.size == 2) {
            val minutes = parts[0].toLongOrNull() ?: return null
            val seconds = parts[1].toDoubleOrNull() ?: return null
            return ((minutes * 60.0 + seconds) * 1000.0).toLong()
        }
        if (parts.size == 3) {
            val hours = parts[0].toLongOrNull() ?: return null
            val minutes = parts[1].toLongOrNull() ?: return null
            val seconds = parts[2].toDoubleOrNull() ?: return null
            return ((hours * 3600.0 + minutes * 60.0 + seconds) * 1000.0).toLong()
        }
        return null
    }
}
