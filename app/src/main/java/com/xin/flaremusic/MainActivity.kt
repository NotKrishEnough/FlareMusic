package com.xin.flaremusic

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF101014)
private val Panel = Color(0xFF1C1B22)
private val Violet = Color(0xFFFF5A35)
private val Mint = Color(0xFFFF3D83)

data class Track(val id: Long, val title: String, val artist: String, val album: String, val uri: Uri, val duration: Long)

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setContent { FlareTheme { FlareApp(player, ::loadTracks) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContent { FlareTheme { FlareApp(player, ::loadTracks) } }
        if (ContextCompat.checkSelfPermission(this, audioPermission()) != PackageManager.PERMISSION_GRANTED) permission.launch(audioPermission())
    }
    private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    private suspend fun loadTracks(): List<Track> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)
        contentResolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(id)
                list += Track(mediaId, cursor.getString(title) ?: "Unknown", cursor.getString(artist) ?: "Unknown artist", cursor.getString(album) ?: "", ContentUris.withAppendedId(collection, mediaId), cursor.getLong(duration))
            }
        }
        list
    }
    override fun onDestroy() { player.release(); super.onDestroy() }
}

@Composable private fun FlareTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, primary = Violet, secondary = Mint), content = content)
}

@Composable private fun FlareApp(player: ExoPlayer, scan: suspend () -> List<Track>) {
    var tab by remember { mutableStateOf("Home") }
    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    var current by remember { mutableStateOf<Track?>(null) }
    var playing by remember { mutableStateOf(false) }
    var playerExpanded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var onlineResults by remember { mutableStateOf(emptyList<OnlineTrack>()) }
    var searching by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    val innerTube = remember { InnerTubeClient() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(player) { while (true) { position = player.currentPosition.coerceAtLeast(0L); totalDuration = player.duration.takeIf { it > 0 } ?: 0L; delay(500) } }
    LaunchedEffect(Unit) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        })
        loading = true
        try { tracks = scan() } catch (e: Exception) { error = e.message ?: "Unable to read music" }
        loading = false
    }
    fun play(track: Track) {
        current = track
        player.setMediaItem(MediaItem.fromUri(track.uri))
        player.prepare()
        player.play()
    }
    fun playOnline(track: OnlineTrack) {
        scope.launch {
            error = ""
            try {
                val url = innerTube.resolveProgressiveUrl(track.videoId)
                if (url == null) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(track.watchUrl))); return@launch }
                play(Track(-track.videoId.hashCode().toLong().let { kotlin.math.abs(it) }, track.title, track.author, "YouTube", Uri.parse(url), 0L))
            } catch (e: Exception) { error = e.message ?: "Could not load stream" }
        }
    }
    fun searchOnline(term: String) {
        scope.launch {
            searching = true; error = ""
            try { onlineResults = innerTube.search(term) } catch (e: Exception) { error = e.message ?: "Online search failed" }
            searching = false
        }
    }
    BackHandler(enabled = playerExpanded) { playerExpanded = false }
    Box(Modifier.fillMaxSize()) {
    Scaffold(containerColor = Ink, bottomBar = {
        Column {
            if (current != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(Panel).clickable { playerExpanded = true }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Violet, Mint))), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null, tint = Ink) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(current!!.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(current!!.artist, color = Color.LightGray, fontSize = 12.sp, maxLines = 1) }
                IconButton(onClick = { if (playing) player.pause() else player.play() }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Violet) }
                IconButton(onClick = { player.seekToNextMediaItem() }) { Icon(Icons.Rounded.SkipNext, null, tint = Violet) }
                if (totalDuration > 0) { Spacer(Modifier.width(6.dp)); Text(formatTime(position), color = Color.LightGray, fontSize = 10.sp) }
            }
            if (current != null && totalDuration > 0) Slider(value = (position.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f), onValueChange = { player.seekTo((it * totalDuration).toLong()) }, modifier = Modifier.fillMaxWidth().height(18.dp).padding(horizontal = 12.dp), colors = SliderDefaults.colors(thumbColor = Violet, activeTrackColor = Violet, inactiveTrackColor = Panel))
            Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel.copy(alpha = 0.98f)).padding(horizontal = 10.dp, vertical = 7.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    listOf("Home", "Search", "Library").forEach { item ->
                        val selected = tab == item
                        Row(Modifier.clip(RoundedCornerShape(22.dp)).background(if (selected) Violet.copy(alpha = 0.18f) else Color.Transparent).clickable { tab = item }.padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(when(item) { "Home" -> Icons.Rounded.Home; "Search" -> Icons.Rounded.Search; else -> Icons.Rounded.LibraryMusic }, null, tint = if (selected) Violet else Color.LightGray)
                            if (selected) { Spacer(Modifier.width(7.dp)); Text(item, color = Violet, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }) { padding ->
        AnimatedContent(tab, modifier = Modifier.padding(padding), label = "page") { page ->
            when(page) {
                "Home" -> HomeScreen(tracks.size, loading, error) { tab = "Library" }
                "Search" -> SearchScreen(query, { query = it }, tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }, ::play, onlineResults, searching, ::searchOnline, ::playOnline, error)
                else -> LibraryScreen(tracks, loading, ::play, { tab = "Search" }, { tracks = emptyList(); loading = true })
            }
        }
    }
    AnimatedVisibility(visible = playerExpanded && current != null, modifier = Modifier.fillMaxSize(), enter = fadeIn() + slideInVertically { it / 6 }, exit = fadeOut() + slideOutVertically { it / 6 }) {
        current?.let { track -> FullPlayer(track, playing, position, totalDuration, onClose = { playerExpanded = false }, onPlayPause = { if (playing) player.pause() else player.play() }, onSeek = { player.seekTo(it) }, onPrevious = { player.seekToPreviousMediaItem() }, onNext = { player.seekToNextMediaItem() }) }
    }
    }
}

@Composable private fun FullPlayer(track: Track, playing: Boolean, position: Long, duration: Long, onClose: () -> Unit, onPlayPause: () -> Unit, onSeek: (Long) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF282035), Ink))).statusBarsPadding().navigationBarsPadding().padding(horizontal = 26.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Collapse player", tint = Color.White, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.weight(1f))
            Text("NOW PLAYING", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Rounded.MoreHoriz, "Close player", tint = Color.White) }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(Violet, Mint))), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = .9f), modifier = Modifier.size(120.dp))
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth()) {
            Text(track.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = Color.LightGray, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(24.dp))
            Slider(value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f, onValueChange = { if (duration > 0) onSeek((it * duration).toLong()) }, colors = SliderDefaults.colors(thumbColor = Violet, activeTrackColor = Violet, inactiveTrackColor = Color.DarkGray))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(position), color = Color.LightGray, fontSize = 12.sp); Text(formatTime(duration), color = Color.LightGray, fontSize = 12.sp) }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(34.dp)) }
                Spacer(Modifier.width(24.dp))
                FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Violet, contentColor = Ink)) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", modifier = Modifier.size(38.dp)) }
                Spacer(Modifier.width(24.dp))
                IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(34.dp)) }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable private fun HomeScreen(count: Int, loading: Boolean, error: String, openLibrary: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Image(painterResource(R.drawable.ic_flare_logo), contentDescription = "FlareMusic logo", modifier = Modifier.size(38.dp)); Spacer(Modifier.width(9.dp)); Text("FlareMusic", color = Color.White, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, fontSize = 22.sp) }
        Spacer(Modifier.height(24.dp))
        Text("Sound,\nwithout limits.", fontSize = 38.sp, lineHeight = 43.sp, fontWeight = FontWeight.Bold)
        Text("Your music, your way.", color = Color.LightGray, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(28.dp))
        Box(Modifier.fillMaxWidth().height(185.dp).clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(Color(0xFF59468D), Color(0xFF24665F)))).clickable { openLibrary() }, contentAlignment = Alignment.BottomStart) {
            Column(Modifier.padding(22.dp)) { Text("YOUR SOUNDTRACK", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp); Text("All your music.\nOne place.", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            Icon(Icons.Rounded.GraphicEq, null, Modifier.align(Alignment.TopEnd).padding(24.dp).size(64.dp), tint = Color.White.copy(alpha = .8f))
        }
        Spacer(Modifier.height(24.dp))
        Text("Your library", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(if (loading) "Scanning your device…" else "$count local tracks found", color = Mint, modifier = Modifier.padding(top = 8.dp))
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF9B9B), modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = openLibrary) { Icon(Icons.Rounded.LibraryMusic, null); Spacer(Modifier.width(8.dp)); Text("Open library") }
    }
}

@Composable private fun SearchScreen(query: String, onQuery: (String) -> Unit, results: List<Track>, play: (Track) -> Unit, online: List<OnlineTrack>, searching: Boolean, searchOnline: (String) -> Unit, playOnline: (OnlineTrack) -> Unit, error: String) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Discover", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Search your library or discover online music.", color = Color.LightGray, modifier = Modifier.padding(top = 5.dp, bottom = 20.dp))
        OutlinedTextField(value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Songs, artists, albums") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(20.dp), singleLine = true)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { searchOnline(query) }, enabled = query.isNotBlank() && !searching, modifier = Modifier.fillMaxWidth()) { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Public, null); Spacer(Modifier.width(8.dp)); Text("Search online") }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF9B9B), modifier = Modifier.padding(vertical = 8.dp))
        Text("ON THIS DEVICE", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(results, key = { it.id }) { TrackRow(it, onClick = { play(it) }) }
            item { Text("ONLINE RESULTS", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            items(online, key = { it.videoId }) { item -> Row(Modifier.fillMaxWidth().clickable { playOnline(item) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.PlayCircle, null, tint = Violet, modifier = Modifier.size(42.dp)); Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold); Text("${item.author} • ${item.duration}", color = Color.LightGray, fontSize = 12.sp, maxLines = 1) } } }
        }
    }
}

@Composable private fun LibraryScreen(tracks: List<Track>, loading: Boolean, play: (Track) -> Unit, search: () -> Unit, refresh: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Your library", fontSize = 30.sp, fontWeight = FontWeight.Bold); Text("${tracks.size} songs on this device", color = Color.LightGray) }
            IconButton(onClick = refresh) { Icon(Icons.Rounded.Refresh, "Refresh library", tint = Violet) }
            IconButton(onClick = search) { Icon(Icons.Rounded.Search, "Search", tint = Violet) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (tracks.isEmpty() && !loading) Text("No audio found. Add music to your device and refresh.", color = Color.LightGray, modifier = Modifier.padding(18.dp))
        LazyColumn { items(tracks, key = { it.id }) { TrackRow(it, onClick = { play(it) }) } }
    }
}

@Composable private fun TrackRow(track: Track, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF40365F), Color(0xFF24534D)))), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null, tint = Mint) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = Color.LightGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.PlayCircle, null, tint = Violet)
    }
}

private fun formatTime(ms: Long): String { val seconds = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(seconds / 60, seconds % 60) }
