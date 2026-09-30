package com.xin.flaremusic

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

private data class SavedPublicPlaylist(val id: String, val title: String, val url: String, val videos: List<String>)

@Composable
fun PublicPlaylistsSection() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("public_playlists", Context.MODE_PRIVATE) }
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var playlists by remember { mutableStateOf(loadSavedPlaylists(prefs)) }
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(JSONObject().put("version", 1).put("playlists", JSONArray().also { a -> playlists.forEach { p -> a.put(JSONObject().put("id", p.id).put("title", p.title).put("url", p.url).put("videos", JSONArray(p.videos))) } }).toString(2).toByteArray()) }
            message = "Backup exported"
        }.onFailure { message = it.message ?: "Export failed" }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val root = JSONObject(context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            val arr = root.optJSONArray("playlists") ?: JSONArray()
            val merged = (playlists + (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                SavedPublicPlaylist(p.optString("id"), p.optString("title"), p.optString("url"), p.optJSONArray("videos")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList())
            }).distinctBy { it.id }
            playlists = merged
            savePlaylists(prefs, merged)
            message = "Backup restored"
        }.onFailure { message = it.message ?: "Could not read backup" }
    }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF171B24)).padding(16.dp)) {
        Text("PUBLIC PLAYLISTS", color = Color(0xFFFF806B), style = MaterialTheme.typography.labelMedium)
        Text("Import a public YouTube playlist by link. No Google sign-in required.", color = Color(0xFFA6ADBC), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        OutlinedTextField(value = link, onValueChange = { link = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Paste YouTube playlist URL") })
        Button(onClick = {
            scope.launch {
                busy = true; message = ""
                try {
                    val p = PublicPlaylistImporter.fetch(link)
                    playlists = (playlists.filterNot { it.id == p.id } + p)
                    savePlaylists(prefs, playlists)
                    link = ""
                    message = "Imported ${p.title} (${p.videos.size} videos)"
                } catch (e: Exception) { message = e.message ?: "Import failed" }
                busy = false
            }
        }, enabled = link.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.AddLink, null)
            Spacer(Modifier.width(8.dp)); Text(if (busy) "Importing…" else "Import playlist")
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { export.launch("FlareMusic-playlists.json") }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Upload, null); Spacer(Modifier.width(5.dp)); Text("Export") }
            OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(5.dp)); Text("Restore") }
        }
        if (message.isNotBlank()) Text(message, color = Color(0xFFB8DCCB), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
    playlists.forEach { p ->
        Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF171B24)).padding(14.dp)) {
            Text(p.title, color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text("${p.videos.size} videos • Public YouTube playlist", color = Color(0xFF9298A8), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { playlists = playlists.filterNot { it.id == p.id }; savePlaylists(prefs, playlists) }) { Icon(Icons.Rounded.Delete, null); Text("Remove") }
        }
    }
}

private fun loadSavedPlaylists(prefs: android.content.SharedPreferences): List<SavedPublicPlaylist> = runCatching {
    val a = JSONArray(prefs.getString("items", "[]"))
    (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { p -> SavedPublicPlaylist(p.optString("id"), p.optString("title"), p.optString("url"), p.optJSONArray("videos")?.let { v -> (0 until v.length()).map { v.optString(it) } } ?: emptyList()) } }
}.getOrDefault(emptyList())

private fun savePlaylists(prefs: android.content.SharedPreferences, items: List<SavedPublicPlaylist>) {
    prefs.edit().putString("items", JSONArray().also { a -> items.forEach { p -> a.put(JSONObject().put("id", p.id).put("title", p.title).put("url", p.url).put("videos", JSONArray(p.videos))) } }.toString()).apply()
}

private object PublicPlaylistImporter {
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    suspend fun fetch(input: String): SavedPublicPlaylist = withContext(Dispatchers.IO) {
        val uri = Uri.parse(input.trim())
        val id = uri.getQueryParameter("list") ?: uri.lastPathSegment?.takeIf { uri.host?.contains("youtu.be") == true }
        require(!id.isNullOrBlank() && id.matches(Regex("[A-Za-z0-9_-]{10,}"))) { "Paste a valid YouTube playlist link" }
        val page = http.newCall(Request.Builder().url("https://www.youtube.com/playlist?list=$id").header("User-Agent", "Mozilla/5.0").build()).execute().use {
            if (!it.isSuccessful) error("YouTube returned HTTP ${it.code}")
            it.body?.string().orEmpty()
        }
        val marker = "var ytInitialData = "
        val start = page.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: error("Could not read this playlist. It may be private or unavailable.")
        val jsonStart = page.indexOf('{', start)
        val json = extractJson(page, jsonStart)
        val root = JSONObject(json)
        val title = findText(root, "title") ?: "YouTube playlist"
        val ids = mutableListOf<String>()
        collectVideoIds(root, ids)
        require(ids.isNotEmpty()) { "No public videos found in this playlist" }
        SavedPublicPlaylist(id, title, "https://www.youtube.com/playlist?list=$id", ids.distinct())
    }

    private fun extractJson(s: String, start: Int): String {
        require(start >= 0) { "YouTube playlist data was not found" }
        var depth = 0; var quoted = false; var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) } }
        }
        error("Playlist data was incomplete")
    }

    private fun findText(value: Any?, key: String): String? {
        when (value) {
            is JSONObject -> {
                if (value.has(key)) {
                    val v = value.opt(key)
                    if (v is JSONObject) v.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
                }
                val keys = value.keys(); while (keys.hasNext()) findText(value.opt(keys.next()), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until value.length()) findText(value.opt(i), key)?.let { return it }
        }
        return null
    }

    private fun collectVideoIds(value: Any?, out: MutableList<String>) {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("playlistVideoRenderer")?.optString("videoId")?.takeIf { it.isNotBlank() }?.let { out += it }
                val keys = value.keys(); while (keys.hasNext()) collectVideoIds(value.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until value.length()) collectVideoIds(value.opt(i), out)
        }
    }
}
