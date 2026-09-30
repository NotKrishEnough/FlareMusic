package com.xin.flaremusic

import android.Manifest
import android.graphics.BitmapFactory
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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

private val Ink = Color(0xFF0B0D12)
private val Panel = Color(0xFF171B24)
private val Violet: Color get() = FlarePreferences.accents[FlarePreferences.accentIndex.intValue.coerceIn(0, FlarePreferences.accents.lastIndex)]
private val Mint: Color get() = Violet.copy(alpha = .82f)

private object FlarePreferences {
    val accentIndex = mutableIntStateOf(0)
    val animations = mutableStateOf(true)
    val compact = mutableStateOf(false)
    val accents = listOf(Color(0xFFFF694F), Color(0xFF9B8CFF), Color(0xFF35C9A5), Color(0xFFFFB84D))
}

data class Track(val id: Long, val title: String, val artist: String, val album: String, val uri: Uri, val duration: Long, val artwork: String? = null)

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var amoledMode by mutableStateOf(false)
    private lateinit var googleAuthLauncher: ActivityResultLauncher<IntentSenderRequest>
    private var googleStatus by mutableStateOf("Not connected")
    private var youtubePlaylists by mutableStateOf(emptyList<YouTubePlaylist>())
    private var playlistLoading by mutableStateOf(false)
    private var playlistError by mutableStateOf("")
    private var youtubeAccessToken: String? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setContent { FlareTheme(amoledMode) { FlareApp(player, ::loadTracks, amoledMode, googleStatus, youtubePlaylists, playlistLoading, playlistError, ::connectGoogle, ::syncYouTubePlaylists) { enabled -> amoledMode = enabled; getSharedPreferences("flare_settings", MODE_PRIVATE).edit().putBoolean("amoled", enabled).apply() } } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        googleAuthLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            try {
                val auth = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)
                acceptYouTubeAuthorization(auth.accessToken)
            } catch (e: Exception) {
                googleStatus = "Not connected"
                playlistError = e.message ?: "Google authorization cancelled"
            }
        }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
        amoledMode = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("amoled", false)
        FlarePreferences.accentIndex.intValue = getSharedPreferences("flare_settings", MODE_PRIVATE).getInt("accent_index", 0).coerceIn(0, FlarePreferences.accents.lastIndex)
        FlarePreferences.animations.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("animations", true)
        FlarePreferences.compact.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("compact", false)
        setContent { FlareTheme(amoledMode) { FlareApp(player, ::loadTracks, amoledMode, googleStatus, youtubePlaylists, playlistLoading, playlistError, ::connectGoogle, ::syncYouTubePlaylists) { enabled -> amoledMode = enabled; getSharedPreferences("flare_settings", MODE_PRIVATE).edit().putBoolean("amoled", enabled).apply() } } }
        if (ContextCompat.checkSelfPermission(this, audioPermission()) != PackageManager.PERMISSION_GRANTED) permission.launch(audioPermission())
    }

    private fun connectGoogle() {
        playlistError = ""
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/youtube.readonly")))
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { auth ->
                if (auth.hasResolution()) {
                    val pending = auth.pendingIntent
                    if (pending != null) googleAuthLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                    else playlistError = "Google authorization needs to be retried"
                } else acceptYouTubeAuthorization(auth.accessToken)
            }
            .addOnFailureListener { e -> playlistError = e.message ?: "Google authorization failed" }
    }

    private fun acceptYouTubeAuthorization(token: String?) {
        if (token.isNullOrBlank()) { playlistError = "Google did not return an access token"; return }
        youtubeAccessToken = token
        googleStatus = "Connected to YouTube"
        syncYouTubePlaylists()
    }

    private fun syncYouTubePlaylists() {
        val token = youtubeAccessToken ?: run { connectGoogle(); return }
        lifecycleScope.launch {
            playlistLoading = true
            playlistError = ""
            try { youtubePlaylists = YouTubePlaylists.fetch(token) }
            catch (e: Exception) { playlistError = e.message ?: "Could not sync playlists" }
            playlistLoading = false
        }
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

@Composable private fun FlareTheme(amoled: Boolean, content: @Composable () -> Unit) {
    val accent = FlarePreferences.accents[FlarePreferences.accentIndex.intValue.coerceIn(0, FlarePreferences.accents.lastIndex)]
    val scheme = darkColorScheme(primary = accent, secondary = accent.copy(alpha = .85f), tertiary = Mint, background = Color(0xFF0B0D12), surface = Color(0xFF151922), surfaceVariant = Color(0xFF202532), onPrimary = Color.White, onSecondary = Color(0xFF101116), onTertiary = Color(0xFF101116), onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Color(0xFFE1E3EA), inverseSurface = Color(0xFFE1E3EA), inverseOnSurface = Color(0xFF17191F))
    MaterialTheme(colorScheme = if (amoled) scheme.copy(background = Color.Black, surface = Color.Black, surfaceContainer = Color(0xFF080808)) else scheme, content = content)
}

@Composable private fun FlareApp(player: ExoPlayer, scan: suspend () -> List<Track>, amoled: Boolean, googleStatus: String, youtubePlaylists: List<YouTubePlaylist>, playlistLoading: Boolean, playlistError: String, onConnectGoogle: () -> Unit, onSyncPlaylists: () -> Unit, onAmoledChange: (Boolean) -> Unit) {
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
            override fun onPlayerError(playbackError: androidx.media3.common.PlaybackException) {
                error = "Playback failed: ${playbackError.errorCodeName}. ${playbackError.message ?: "Stream rejected"}"
                playing = false
            }
        })
        loading = true
        try { tracks = scan() } catch (e: Exception) { error = e.message ?: "Unable to read music" }
        loading = false
    }
    fun play(track: Track) {
        error = ""
        current = track
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(MediaItem.fromUri(track.uri))
        player.prepare()
        player.playWhenReady = true
    }
    fun playOnline(track: OnlineTrack) {
        scope.launch {
            error = ""
            try {
                loading = true
                val url = innerTube.resolveProgressiveUrl(track.videoId)
                if (url == null) { error = "This video has no playable audio stream. Try another result."; loading = false; return@launch }
                play(Track(-track.videoId.hashCode().toLong().let { kotlin.math.abs(it) }, track.title, track.author, "YouTube", Uri.parse(url), 0L, track.thumbnail))
                loading = false
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
    Scaffold(containerColor = if (amoled) Color.Black else Ink, bottomBar = {
        Column(Modifier.padding(bottom = 12.dp)) {
            if (current != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(if (amoled) Color.Black else Panel).clickable { playerExpanded = true }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(current!!.artwork, Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)))
                Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(current!!.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(current!!.artist, color = Color.LightGray, fontSize = 12.sp, maxLines = 1) }
                IconButton(onClick = { if (playing) player.pause() else player.play() }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Violet) }
                IconButton(onClick = { player.seekToNextMediaItem() }) { Icon(Icons.Rounded.SkipNext, null, tint = Violet) }
                if (totalDuration > 0) { Spacer(Modifier.width(6.dp)); Text(formatTime(position), color = Color.LightGray, fontSize = 10.sp) }
            }
            if (current != null && totalDuration > 0) Slider(value = (position.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f), onValueChange = { player.seekTo((it * totalDuration).toLong()) }, modifier = Modifier.fillMaxWidth().height(18.dp).padding(horizontal = 12.dp), colors = SliderDefaults.colors(thumbColor = Violet, activeTrackColor = Violet, inactiveTrackColor = Panel))
            Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background((if (amoled) Color.Black else Panel).copy(alpha = 0.98f)).padding(horizontal = 10.dp, vertical = 7.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    listOf("Home", "Search", "Library", "Settings").forEach { item ->
                        val selected = tab == item
                        Row(Modifier.clip(RoundedCornerShape(22.dp)).background(if (selected) Violet.copy(alpha = 0.18f) else Color.Transparent).clickable { tab = item }.padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(when(item) { "Home" -> Icons.Rounded.Home; "Search" -> Icons.Rounded.Search; "Library" -> Icons.Rounded.LibraryMusic; else -> Icons.Rounded.Settings }, null, tint = if (selected) Violet else Color.LightGray)
                            if (selected) { Spacer(Modifier.width(7.dp)); Text(item, color = Violet, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }) { padding ->
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.padding(padding),
            label = "page",
            transitionSpec = {
                if (FlarePreferences.animations.value) {
                    (fadeIn(animationSpec = androidx.compose.animation.core.tween(260)) + slideInVertically(animationSpec = androidx.compose.animation.core.spring(dampingRatio = .86f, stiffness = 420f)) { it / 14 }) togetherWith
                        (fadeOut(animationSpec = androidx.compose.animation.core.tween(150)) + slideOutVertically(animationSpec = androidx.compose.animation.core.tween(180)) { -it / 20 })
                } else androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            }
        ) { page ->
            when(page) {
                "Home" -> HomeScreen(tracks.size, loading, error) { tab = "Library" }
                "Search" -> SearchScreen(query, { query = it }, tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }, ::play, onlineResults, searching, ::searchOnline, ::playOnline, error)
                "Library" -> LibraryScreen(tracks, loading, ::play, { tab = "Search" }, { tracks = emptyList(); loading = true })
                else -> SettingsScreen(amoled, onAmoledChange, googleStatus, youtubePlaylists, playlistLoading, playlistError, onConnectGoogle, onSyncPlaylists)
            }
        }
    }
    AnimatedVisibility(visible = playerExpanded && current != null, modifier = Modifier.fillMaxSize(), enter = fadeIn() + slideInVertically { it / 6 }, exit = fadeOut() + slideOutVertically { it / 6 }) {
        current?.let { track -> FullPlayer(track, playing, position, totalDuration, onClose = { playerExpanded = false }, onPlayPause = { if (playing) player.pause() else player.play() }, onSeek = { player.seekTo(it) }, onPrevious = { player.seekToPreviousMediaItem() }, onNext = { player.seekToNextMediaItem() }) }
    }
    }
}

@Composable private fun SettingsScreen(amoled: Boolean, onAmoledChange: (Boolean) -> Unit, googleStatus: String, playlists: List<YouTubePlaylist>, loading: Boolean, error: String, onConnect: () -> Unit, onSync: () -> Unit) {
    val settingsContext = LocalContext.current
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0D12)).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
        Text("Settings", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = Color.White)
        Text("Make FlareMusic yours.", color = Color(0xFFA6ADBC), fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp, bottom = 24.dp))
        Text("APPEARANCE", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = Modifier.padding(bottom = 9.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF171B24)).clickable { onAmoledChange(!amoled) }.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF292E39)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.DarkMode, null, tint = Mint) }
            Column(Modifier.weight(1f).padding(start = 13.dp)) { Text("AMOLED mode", color = Color.White, fontWeight = FontWeight.SemiBold); Text("Pure black backgrounds", color = Color(0xFF9298A8), fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp)) }
            Switch(checked = amoled, onCheckedChange = onAmoledChange)
        }
        Text("FlareMusic uses a dark-first palette.", color = Color(0xFF9298A8), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 20.dp))
        Text("PERSONALIZATION", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = Modifier.padding(bottom = 9.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF171B24)).padding(17.dp)) {
            Text("Accent colour", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("Choose the colour used for highlights and controls.", color = Color(0xFFA6ADBC), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 13.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                FlarePreferences.accents.forEachIndexed { index, color ->
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(15.dp)).background(color).clickable {
                        FlarePreferences.accentIndex.intValue = index
                        settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putInt("accent_index", index).apply()
                    }, contentAlignment = Alignment.Center) {
                        if (FlarePreferences.accentIndex.intValue == index) Icon(Icons.Rounded.Check, null, tint = Color.White)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Color(0xFF303542))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Smooth animations", color = Color.White, fontWeight = FontWeight.SemiBold); Text("Spring transitions between screens", color = Color(0xFFA6ADBC), fontSize = 12.sp) }
                Switch(checked = FlarePreferences.animations.value, onCheckedChange = {
                    FlarePreferences.animations.value = it
                    settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("animations", it).apply()
                })
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Color(0xFF303542))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Compact layout", color = Color.White, fontWeight = FontWeight.SemiBold); Text("Reduce spacing in lists", color = Color(0xFFA6ADBC), fontSize = 12.sp) }
                Switch(checked = FlarePreferences.compact.value, onCheckedChange = {
                    FlarePreferences.compact.value = it
                    settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("compact", it).apply()
                })
            }
        }
        PublicPlaylistsSection()
        Spacer(Modifier.height(20.dp))
        Text("FLAREMUSIC  •  MADE FOR YOUR MUSIC", color = Color(0xFF626A79), fontSize = 9.sp, letterSpacing = 1.2.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 14.dp))
    }
}

@Composable private fun Artwork(source: String?, modifier: Modifier = Modifier) {
    var bitmap by remember(source) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(source) {
        bitmap = withContext(Dispatchers.IO) {
            try { if (source.isNullOrBlank()) null else URL(source).openConnection().apply { connectTimeout = 8000; readTimeout = 8000 }.getInputStream().use { BitmapFactory.decodeStream(it) } }
            catch (_: Exception) { null }
        }
    }
    if (bitmap != null) Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "Album art", modifier = modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else Box(modifier.background(Color(0xFF100D18)), contentAlignment = Alignment.Center) { Image(painterResource(R.drawable.ic_flare_logo), contentDescription = "FlareMusic logo", modifier = Modifier.fillMaxSize().padding(5.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
}

@Composable private fun FullPlayer(track: Track, playing: Boolean, position: Long, duration: Long, onClose: () -> Unit, onPlayPause: () -> Unit, onSeek: (Long) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background))).statusBarsPadding().navigationBarsPadding().padding(horizontal = 26.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Collapse player", tint = Color.White, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.weight(1f))
            Text("NOW PLAYING", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Rounded.MoreHoriz, "Close player", tint = Color.White) }
        }
        Spacer(Modifier.weight(1f))
        Artwork(track.artwork, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(30.dp)))
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth()) {
            Text(track.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = Color(0xFFD0D3DC), fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(24.dp))
            Slider(value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f, onValueChange = { if (duration > 0) onSeek((it * duration).toLong()) }, colors = SliderDefaults.colors(thumbColor = Violet, activeTrackColor = Violet, inactiveTrackColor = Color.DarkGray))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(position), color = Color.LightGray, fontSize = 12.sp); Text(formatTime(duration), color = Color.LightGray, fontSize = 12.sp) }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(34.dp)) }
                Spacer(Modifier.width(24.dp))
                FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Violet, contentColor = Color.White)) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", modifier = Modifier.size(38.dp)) }
                Spacer(Modifier.width(24.dp))
                IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(34.dp)) }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable private fun HomeScreen(count: Int, loading: Boolean, error: String, openLibrary: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0D12)).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_flare_logo), contentDescription = "FlareMusic", modifier = Modifier.size(42.dp))
            Spacer(Modifier.width(10.dp))
            Column { Text("FLARE MUSIC", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 17.sp); Text("YOUR PERSONAL SOUND", color = Color(0xFF9298A8), fontSize = 9.sp, letterSpacing = 1.6.sp) }
        }
        Spacer(Modifier.height(30.dp))
        Text("Feel every\nfrequency.", fontSize = 42.sp, lineHeight = 46.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.4).sp, color = Color.White)
        Text("Your music, all in one place.", color = Color(0xFFA6ADBC), fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(26.dp))
        Box(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF43233C), Color(0xFF222A49), Color(0xFF153C3B)))).clickable { openLibrary() }) {
            Box(Modifier.align(Alignment.TopEnd).padding(20.dp).size(112.dp).clip(RoundedCornerShape(56.dp)).background(Color.White.copy(alpha = .07f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.GraphicEq, null, tint = Violet, modifier = Modifier.size(64.dp)) }
            Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) { Text("MADE FOR YOUR MOMENTS", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp); Text("Press play.\nDisappear.", color = Color.White, fontSize = 27.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Your library", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color.White); Text(if (loading) "Looking for your tracks…" else "$count tracks ready to play", color = Color(0xFFA6ADBC), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF20232D)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.LibraryMusic, null, tint = Violet, modifier = Modifier.size(24.dp)) }
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF9B9B), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(16.dp))
        Button(onClick = openLibrary, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color.White)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Explore my music", fontWeight = FontWeight.Bold) }
    }
}

@Composable private fun SearchScreen(query: String, onQuery: (String) -> Unit, results: List<Track>, play: (Track) -> Unit, online: List<OnlineTrack>, searching: Boolean, searchOnline: (String) -> Unit, playOnline: (OnlineTrack) -> Unit, error: String) {
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0D12)).padding(horizontal = 18.dp)) {
        Text("Discover", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = Color.White, modifier = Modifier.padding(top = 18.dp))
        Text("Find something for the moment.", color = Color(0xFFA6ADBC), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
        OutlinedTextField(value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Track, artist or album") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(18.dp), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Violet, unfocusedBorderColor = Color(0xFF303542), focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLeadingIconColor = Violet))
        Spacer(Modifier.height(10.dp))
        Button(onClick = { searchOnline(query) }, enabled = query.isNotBlank() && !searching, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color(0xFF171014))) {
            if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF171014)) else Icon(Icons.Rounded.Public, null)
            Spacer(Modifier.width(9.dp)); Text(if (searching) "Searching…" else "Search online", fontWeight = FontWeight.Bold)
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFFA0A0), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF301D24)).padding(12.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 18.dp, bottom = 16.dp)) {
            item { Text("ON THIS DEVICE", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            if (results.isEmpty()) item { Text(if (query.isBlank()) "Search your downloaded music" else "No matching tracks in your library", color = Color(0xFF8E95A5), fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp)) }
            items(results, key = { it.id }) { TrackRow(it, onClick = { play(it) }) }
            item { Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text("ONLINE", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp); Spacer(Modifier.width(8.dp)); Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF292E39))) } }
            if (online.isEmpty() && !searching) item { Text("Search online to discover more music", color = Color(0xFF8E95A5), fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp)) }
            items(online, key = { it.videoId }) { result -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { playOnline(result) }.padding(vertical = 9.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(result.thumbnail, Modifier.size(54.dp).clip(RoundedCornerShape(13.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(result.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text(result.author + " • " + result.duration, color = Color(0xFF9298A8), fontSize = 11.sp, maxLines = 1) }
                Icon(Icons.Rounded.PlayCircleFilled, null, tint = Violet, modifier = Modifier.size(30.dp))
            } }
        }
    }
}

@Composable private fun LibraryScreen(tracks: List<Track>, loading: Boolean, play: (Track) -> Unit, search: () -> Unit, refresh: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0D12)).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Your library", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = Color.White); Text(tracks.size.toString() + " songs on this device", color = Color(0xFFA6ADBC), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            IconButton(onClick = refresh) { Icon(Icons.Rounded.Refresh, "Refresh", tint = Violet) }
            IconButton(onClick = search) { Icon(Icons.Rounded.Search, "Search", tint = Violet) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Violet, trackColor = Color(0xFF292E39))
        if (tracks.isEmpty() && !loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Rounded.LibraryMusic, null, tint = Color(0xFF555D6D), modifier = Modifier.size(54.dp)); Text("Your library is quiet", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp)); Text("Add audio to your device, then refresh.", color = Color(0xFF9298A8), fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp)) } }
        LazyColumn(contentPadding = PaddingValues(bottom = 18.dp)) { items(tracks, key = { it.id }) { TrackRow(it, onClick = { play(it) }) } }
    }
}

@Composable private fun TrackRow(track: Track, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = if (FlarePreferences.compact.value) 4.dp else 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track.artwork, Modifier.size(50.dp).clip(RoundedCornerShape(13.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = Color(0xFF9298A8), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(Icons.Rounded.PlayCircleFilled, null, tint = Violet, modifier = Modifier.size(27.dp))
    }
}

private fun formatTime(ms: Long): String { val seconds = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(seconds / 60, seconds % 60) }
