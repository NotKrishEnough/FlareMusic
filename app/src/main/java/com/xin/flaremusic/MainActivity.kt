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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URL

private val Ink = Color(0xFF0B0A10)
private val Panel = Color(0xFF19151E)
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
    private var player: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var amoledMode by mutableStateOf(false)
    private lateinit var youtubeLoginLauncher: ActivityResultLauncher<Intent>
    private var googleStatus by mutableStateOf("Not connected")
    private var youtubePlaylists by mutableStateOf(emptyList<YouTubePlaylist>())
    private var playlistLoading by mutableStateOf(false)
    private var playlistError by mutableStateOf("")
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showApp()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        youtubeLoginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cookieHeader = result.data?.getStringExtra(YouTubeCookieLoginActivity.EXTRA_COOKIE_HEADER)
            if (result.resultCode == RESULT_OK && !cookieHeader.isNullOrBlank()) {
                lifecycleScope.launch {
                    playlistLoading = true
                    playlistError = ""
                    val valid = YouTubeSessionVerifier.verify(cookieHeader)
                    if (valid) {
                        YouTubeSessionStore.save(this@MainActivity, cookieHeader)
                        googleStatus = "Connected to YouTube Music"
                        try {
                            youtubePlaylists = YouTubePlaylists.fetchFromMusicSession(cookieHeader)
                            playlistError = if (youtubePlaylists.isEmpty()) "No playlists found in your YouTube Music library." else ""
                        } catch (e: Exception) {
                            playlistError = e.message ?: "Couldn't load YouTube Music playlists."
                        }
                    } else {
                        googleStatus = "Not connected"
                        playlistError = "Could not verify the YouTube Music session. Try signing in again."
                    }
                    playlistLoading = false
                }
            } else {
                googleStatus = "Not connected"
            }
        }
        lifecycleScope.launch {
            val savedCookies = YouTubeSessionStore.read(this@MainActivity)
            if (!savedCookies.isNullOrBlank()) {
                googleStatus = if (YouTubeSessionVerifier.verify(savedCookies)) "Connected to YouTube Music" else {
                    YouTubeSessionStore.clear(this@MainActivity)
                    "Not connected"
                }
            }
        }
        amoledMode = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("amoled", false)
        FlarePreferences.accentIndex.intValue = getSharedPreferences("flare_settings", MODE_PRIVATE).getInt("accent_index", 0).coerceIn(0, FlarePreferences.accents.lastIndex)
        FlarePreferences.animations.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("animations", true)
        FlarePreferences.compact.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("compact", false)
        val token = SessionToken(this, android.content.ComponentName(this, FlarePlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try { player = controllerFuture?.get(); showApp() }
            catch (e: Exception) { googleStatus = "Playback service unavailable" }
        }, ContextCompat.getMainExecutor(this))
        if (ContextCompat.checkSelfPermission(this, audioPermission()) != PackageManager.PERMISSION_GRANTED) permission.launch(audioPermission())
    }

    private fun showApp() {
        val activePlayer = player ?: return
        if (ContextCompat.checkSelfPermission(this, audioPermission()) != PackageManager.PERMISSION_GRANTED) return
        setContent { FlareTheme(amoledMode) { FlareApp(activePlayer, ::loadTracks, amoledMode, googleStatus, youtubePlaylists, playlistLoading, playlistError, ::connectGoogle, ::syncYouTubePlaylists, ::disconnectYouTube) { enabled -> amoledMode = enabled; getSharedPreferences("flare_settings", MODE_PRIVATE).edit().putBoolean("amoled", enabled).apply() } } }
    }

    private fun connectGoogle() {
        playlistError = ""
        youtubeLoginLauncher.launch(Intent(this, YouTubeCookieLoginActivity::class.java))
    }

    private fun syncYouTubePlaylists() {
        lifecycleScope.launch {
            playlistLoading = true
            val savedCookies = YouTubeSessionStore.read(this@MainActivity)
            val valid = !savedCookies.isNullOrBlank() && YouTubeSessionVerifier.verify(savedCookies)
            googleStatus = if (valid) "Connected to YouTube Music" else "Not connected"
            if (valid) {
                try {
                    youtubePlaylists = YouTubePlaylists.fetchFromMusicSession(savedCookies!!)
                    playlistError = if (youtubePlaylists.isEmpty()) "No playlists found in your YouTube Music library." else ""
                } catch (e: Exception) {
                    playlistError = e.message ?: "Couldn't load YouTube Music playlists."
                }
            } else {
                YouTubeSessionStore.clear(this@MainActivity)
                youtubePlaylists = emptyList()
                playlistError = "Your YouTube Music session needs to be connected again."
            }
            playlistLoading = false
        }
    }

    private fun disconnectYouTube() {
        YouTubeSessionStore.clear(this)
        googleStatus = "Not connected"
        youtubePlaylists = emptyList()
        playlistError = ""
    }

    private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    private suspend fun loadTracks(): List<Track> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID)
        contentResolver.query(collection, projection, null, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(id)
                val artId = cursor.getLong(albumId)
                val artwork = if (artId > 0L) "content://media/external/audio/albumart/$artId" else null
                list += Track(mediaId, cursor.getString(title) ?: "Unknown", cursor.getString(artist) ?: "Unknown artist", cursor.getString(album) ?: "", ContentUris.withAppendedId(collection, mediaId), cursor.getLong(duration), artwork)
            }
        }
        list
    }
    override fun onDestroy() { controllerFuture?.let { MediaController.releaseFuture(it) }; super.onDestroy() }
}

@Composable private fun FlareTheme(amoled: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val accent = FlarePreferences.accents[FlarePreferences.accentIndex.intValue.coerceIn(0, FlarePreferences.accents.lastIndex)]
    val fallback = darkColorScheme(
        primary = accent, secondary = accent.copy(alpha = .85f), tertiary = Mint,
        background = Color(0xFF101116), surface = Color(0xFF171922),
        surfaceVariant = Color(0xFF292D39), onPrimary = Color.White,
        onSecondary = Color(0xFF101116), onTertiary = Color(0xFF101116),
        onBackground = Color.White, onSurface = Color.White,
        onSurfaceVariant = Color(0xFFE1E3EA), inverseSurface = Color(0xFFE1E3EA),
        inverseOnSurface = Color(0xFF17191F)
    )
    val wallpaperScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context) else fallback
    val expressiveScheme = wallpaperScheme.copy(
        primary = accent,
        secondary = accent.copy(alpha = .88f),
        tertiary = Mint,
        onPrimary = Color.White,
        background = Color(0xFF101116),
        onBackground = Color.White
    )
    MaterialTheme(
        colorScheme = if (amoled) expressiveScheme.copy(background = Color.Black, surface = Color.Black, surfaceContainer = Color(0xFF080808)) else expressiveScheme,
        content = content
    )
}

@Composable private fun FlareApp(player: Player, scan: suspend () -> List<Track>, amoled: Boolean, googleStatus: String, youtubePlaylists: List<YouTubePlaylist>, playlistLoading: Boolean, playlistError: String, onConnectGoogle: () -> Unit, onSyncPlaylists: () -> Unit, onDisconnectYouTube: () -> Unit, onAmoledChange: (Boolean) -> Unit) {
    val uiViewModel: FlareUiViewModel = viewModel()
    val tab = uiViewModel.selectedTab
    val selectTab: (String) -> Unit = uiViewModel::selectTab
    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    var current by remember { mutableStateOf<Track?>(null) }
    var queueTracks by remember { mutableStateOf(emptyMap<String, Track>()) }
    var playing by remember { mutableStateOf(false) }
    var playerExpanded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var onlineResults by remember { mutableStateOf(emptyList<OnlineTrack>()) }
    var selectedPlaylist by remember { mutableStateOf<YouTubePlaylist?>(null) }
    var selectedPlaylistTracks by remember { mutableStateOf(emptyList<YouTubePlaylistTrack>()) }
    var selectedPlaylistLoading by remember { mutableStateOf(false) }
    var selectedPlaylistError by remember { mutableStateOf("") }
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
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                current = mediaItem?.mediaId?.let { queueTracks[it] }
            }
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
        val localQueue = tracks
        val selectedIndex = localQueue.indexOfFirst { it.id == track.id }
        val queue = if (selectedIndex >= 0 && localQueue.isNotEmpty()) localQueue else listOf(track)
        queueTracks = queue.associateBy { it.id.toString() }
        val mediaItems = queue.map { item ->
            val metadataBuilder = MediaMetadata.Builder().setTitle(item.title).setArtist(item.artist).setAlbumTitle(item.album)
            item.artwork?.let { artwork -> metadataBuilder.setArtworkUri(Uri.parse(artwork)) }
            MediaItem.Builder().setMediaId(item.id.toString()).setUri(item.uri).setMediaMetadata(metadataBuilder.build()).build()
        }
        current = track
        player.setMediaItems(mediaItems, if (selectedIndex >= 0) selectedIndex else 0, 0L)
        player.prepare()
        player.play()
    }
    fun playOnline(track: OnlineTrack) {
        scope.launch {
            error = ""
            loading = true
            try {
                val url = innerTube.resolveProgressiveUrl(track.videoId)
                play(Track(-kotlin.math.abs(track.videoId.hashCode().toLong()).coerceAtLeast(1L), track.title, track.author, "YouTube", Uri.parse(url), 0L, track.thumbnail))
            } catch (e: Exception) {
                error = e.message ?: "Could not load stream"
            } finally {
                loading = false
            }
        }
    }
    fun openYouTubePlaylist(playlist: YouTubePlaylist) {
        selectedPlaylist = playlist
        selectedPlaylistTracks = emptyList()
        selectedPlaylistError = ""
        scope.launch {
            selectedPlaylistLoading = true
            try {
                val cookies = YouTubeSessionStore.read(context)
                val musicTracks = if (!cookies.isNullOrBlank()) {
                    runCatching { YouTubePlaylists.fetchPlaylistTracks(cookies, playlist.id) }.getOrDefault(emptyList())
                } else emptyList()
                // Fall back to the regular YouTube playlist endpoint; it also handles public YT playlists.
                val tracksFromYouTube = if (musicTracks.isEmpty()) {
                    innerTube.fetchPlaylist(playlist.id).map { item ->
                        YouTubePlaylistTrack(item.videoId, item.title, item.author,
                            item.thumbnail.ifBlank { "https://i.ytimg.com/vi/${item.videoId}/hqdefault.jpg" })
                    }
                } else emptyList()
                selectedPlaylistTracks = (musicTracks + tracksFromYouTube).distinctBy { it.videoId }
                    .map { item -> if (item.thumbnail.isBlank()) item.copy(thumbnail = "https://i.ytimg.com/vi/${item.videoId}/hqdefault.jpg") else item }
                if (selectedPlaylistTracks.isEmpty()) selectedPlaylistError = "No tracks found. This playlist may be private or unavailable.";
            } catch (e: Exception) {
                selectedPlaylistError = e.message ?: "Couldn't load this playlist."
            } finally { selectedPlaylistLoading = false }
        }
    }
    fun playYouTubePlaylistQueue(startItem: YouTubePlaylistTrack) {
        scope.launch {
            error = ""
            loading = true
            val playlist = selectedPlaylistTracks
            val playlistTitle = selectedPlaylist?.title ?: "YouTube playlist"
            try {
                val startIndex = playlist.indexOfFirst { it.videoId == startItem.videoId }
                    .takeIf { it >= 0 } ?: 0
                // Resolve and start the tapped song first; never wait for the whole playlist.
                val firstUrl = innerTube.resolveProgressiveUrl(startItem.videoId)
                fun makeItem(item: YouTubePlaylistTrack, url: String): MediaItem {
                    val mediaId = "yt:${item.videoId}"
                    val artwork = item.thumbnail.ifBlank { "https://i.ytimg.com/vi/${item.videoId}/hqdefault.jpg" }
                    val metadata = MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.artist.ifBlank { "YouTube" })
                        .setAlbumTitle(playlistTitle)
                        .setArtworkUri(Uri.parse(artwork))
                        .build()
                    return MediaItem.Builder().setMediaId(mediaId).setUri(url).setMediaMetadata(metadata).build()
                }
                fun makeTrack(item: YouTubePlaylistTrack): Track = Track(
                    -kotlin.math.abs(item.videoId.hashCode().toLong()).coerceAtLeast(1L),
                    item.title, item.artist.ifBlank { "YouTube" }, playlistTitle,
                    Uri.parse("https://www.youtube.com/watch?v=${item.videoId}"), 0L,
                    item.thumbnail.ifBlank { "https://i.ytimg.com/vi/${item.videoId}/hqdefault.jpg" }
                )
                val firstMediaItem = makeItem(startItem, firstUrl)
                queueTracks = mapOf(firstMediaItem.mediaId to makeTrack(startItem))
                current = makeTrack(startItem)
                player.setMediaItem(firstMediaItem)
                player.prepare()
                player.play()
                loading = false

                // Fill the rest of the queue in the background while the first song plays.
                val semaphore = kotlinx.coroutines.sync.Semaphore(permits = 3)
                val resolvedOthers = kotlinx.coroutines.coroutineScope {
                    playlist.filterNot { it.videoId == startItem.videoId }.mapIndexed { index, item ->
                        async {
                            semaphore.withPermit {
                                runCatching { Triple(index, item, innerTube.resolveProgressiveUrl(item.videoId)) }.getOrNull()
                            }
                        }
                    }.awaitAll().filterNotNull().sortedBy { it.first }
                }
                if (resolvedOthers.isNotEmpty()) {
                    val prior = resolvedOthers.filter { it.first < startIndex }
                    val following = resolvedOthers.filter { it.first > startIndex }
                    val priorItems = prior.map { makeItem(it.second, it.third) }
                    val followingItems = following.map { makeItem(it.second, it.third) }
                    queueTracks = queueTracks + resolvedOthers.associate { (_, item, _) ->
                        "yt:${item.videoId}" to makeTrack(item)
                    }
                    // Insert previous tracks before the current item and following tracks after it.
                    if (priorItems.isNotEmpty()) player.addMediaItems(0, priorItems)
                    if (followingItems.isNotEmpty()) player.addMediaItems(followingItems)
                }
            } catch (e: Exception) {
                error = e.message ?: "Couldn't start this song."
                loading = false
            }
        }
    }

    fun searchOnline(term: String) {
        scope.launch {
            searching = true; error = ""
            try {
                onlineResults = innerTube.search(term)
                // Warm a few likely choices while the user is browsing, so tapping Play
                // does not have to wait for YouTube stream extraction.
                val warmup = onlineResults.take(5)
                scope.launch {
                    val semaphore = kotlinx.coroutines.sync.Semaphore(permits = 2)
                    warmup.map { result ->
                        async {
                            semaphore.withPermit {
                                runCatching { innerTube.resolveProgressiveUrl(result.videoId) }
                            }
                        }
                    }.awaitAll()
                }
            } catch (e: Exception) {
                error = e.message ?: "Online search failed"
            } finally {
                searching = false
            }
        }
    }
    BackHandler(enabled = playerExpanded) { playerExpanded = false }
    Box(Modifier.fillMaxSize()) {
    Scaffold(containerColor = if (amoled) Color.Black else Ink, bottomBar = {
        Column(Modifier.padding(bottom = 12.dp)) {
            if (current != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(if (amoled) Color.Black else Panel).clickable { playerExpanded = true }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(current!!.artwork, Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)))
                Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(current!!.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(current!!.artist, color = Color(0xFFBDB5C0), fontSize = 12.sp, maxLines = 1) }
                IconButton(onClick = { if (playing) player.pause() else player.play() }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Violet) }
                IconButton(onClick = { player.seekToNextMediaItem() }) { Icon(Icons.Rounded.SkipNext, null, tint = Violet) }
                if (totalDuration > 0) { Spacer(Modifier.width(6.dp)); Text(formatTime(position), color = Color.LightGray, fontSize = 10.sp) }
            }
            if (current != null && totalDuration > 0) Slider(value = (position.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f), onValueChange = { player.seekTo((it * totalDuration).toLong()) }, modifier = Modifier.fillMaxWidth().height(18.dp).padding(horizontal = 12.dp), colors = SliderDefaults.colors(thumbColor = Violet, activeTrackColor = Violet, inactiveTrackColor = Panel))
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(30.dp))
                        .background((if (amoled) Color(0xFF111111) else Color(0xFF24212B)).copy(alpha = 0.96f))
                        .padding(horizontal = 7.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("Home", "Search", "Library", "Settings").forEach { item ->
                        val selected = tab == item
                        Column(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(24.dp))
                                .background(if (selected) Violet.copy(alpha = 0.20f) else Color.Transparent)
                                .clickable { selectTab(item) }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                when(item) { "Home" -> Icons.Rounded.Home; "Search" -> Icons.Rounded.Search; "Library" -> Icons.Rounded.LibraryMusic; else -> Icons.Rounded.Settings },
                                contentDescription = item,
                                tint = if (selected) Violet else Color(0xFFAAA6B2),
                                modifier = Modifier.size(21.dp)
                            )
                            Text(item, color = if (selected) Violet else Color(0xFFAAA6B2), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp), maxLines = 1)
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
                "Home" -> HomeScreen(tracks.size, loading, error, Violet) { selectTab("Library") }
                "Search" -> SearchScreen(query, { query = it }, tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }, ::play, onlineResults, searching, ::searchOnline, ::playOnline, error)
                "Library" -> LibraryScreen(tracks, loading, youtubePlaylists, googleStatus, playlistLoading, playlistError, selectedPlaylist, selectedPlaylistTracks, selectedPlaylistLoading, selectedPlaylistError, ::openYouTubePlaylist, { selectedPlaylist = null; selectedPlaylistTracks = emptyList() }, { item -> playYouTubePlaylistQueue(item) }, ::play, { selectTab("Search") }, { tracks = emptyList(); loading = true }, onSyncPlaylists, onConnectGoogle)
                else -> SettingsScreen(amoled, onAmoledChange, googleStatus, youtubePlaylists, playlistLoading, playlistError, onConnectGoogle, onSyncPlaylists, onDisconnectYouTube)
            }
        }
    }
    AnimatedVisibility(visible = playerExpanded && current != null, modifier = Modifier.fillMaxSize(), enter = fadeIn() + slideInVertically { it / 6 }, exit = fadeOut() + slideOutVertically { it / 6 }) {
        current?.let { track -> FullPlayer(track, playing, position, totalDuration, onClose = { playerExpanded = false }, onPlayPause = { if (playing) player.pause() else player.play() }, onSeek = { player.seekTo(it) }, onPrevious = { player.seekToPreviousMediaItem() }, onNext = { player.seekToNextMediaItem() }) }
    }
    }
}

@Composable private fun SettingsScreen(amoled: Boolean, onAmoledChange: (Boolean) -> Unit, googleStatus: String, playlists: List<YouTubePlaylist>, loading: Boolean, error: String, onConnect: () -> Unit, onSync: () -> Unit, onDisconnectYouTube: () -> Unit) {
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
        Text("YOUTUBE MUSIC", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = Modifier.padding(bottom = 9.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF171B24)).padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MusicNote, null, tint = Mint, modifier = Modifier.size(25.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("YouTube Music account", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(googleStatus, color = if (googleStatus.startsWith("Connected")) Mint else Color(0xFFA6ADBC), fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                }
                if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Mint)
            }
            Text("Sign in through Google's page. Your session is encrypted on this device.", color = Color(0xFFA6ADBC), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, modifier = Modifier.weight(1f), enabled = !loading) {
                    Text(if (googleStatus.startsWith("Connected")) "Reconnect" else "Connect")
                }
                if (googleStatus.startsWith("Connected")) {
                    OutlinedButton(onClick = onDisconnectYouTube, enabled = !loading) { Text("Disconnect") }
                }
            }
            if (error.isNotBlank()) Text(error, color = Color(0xFFFF8A80), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("FLAREMUSIC  •  MADE FOR YOUR MUSIC", color = Color(0xFF626A79), fontSize = 9.sp, letterSpacing = 1.2.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 14.dp))
    }
}

@Composable private fun Artwork(source: String?, modifier: Modifier = Modifier, fitArtwork: Boolean = false) {
    var bitmap by remember(source) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(source) {
        bitmap = withContext(Dispatchers.IO) {
            try { if (source.isNullOrBlank()) null else URL(source).openConnection().apply { connectTimeout = 8000; readTimeout = 8000 }.getInputStream().use { BitmapFactory.decodeStream(it) } }
            catch (_: Exception) { null }
        }
    }
    if (bitmap != null) Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "Album art", modifier = modifier, contentScale = if (fitArtwork) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop)
    else Box(modifier.background(Color(0xFF100D18)), contentAlignment = Alignment.Center) { Image(painterResource(R.drawable.ic_flare_logo), contentDescription = "FlareMusic logo", modifier = Modifier.fillMaxSize().padding(5.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
}

@Composable private fun FullPlayer(track: Track, playing: Boolean, position: Long, duration: Long, onClose: () -> Unit, onPlayPause: () -> Unit, onSeek: (Long) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF25151F), Color(0xFF100D14), Color(0xFF09070F))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().displayCutoutPadding().padding(horizontal = 24.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Collapse player", tint = Color.White, modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NOW PLAYING", color = Color(0xFFFF9A79), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
                    Text("FlareMusic", color = Color(0xFFB8AEB7), fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.MoreHoriz, "More options", tint = Color.White) }
            }
            Spacer(Modifier.weight(.65f))
            Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 8.dp).clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(Color(0xFF3A202F), Color(0xFF1A1726)))).padding(10.dp)) {
                Artwork(track.artwork, Modifier.fillMaxSize().clip(RoundedCornerShape(23.dp)))
            }
            Spacer(Modifier.weight(.65f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = Color(0xFFBDB5C0), fontSize = 15.sp, modifier = Modifier.padding(top = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.FavoriteBorder, "Favorite", tint = Color(0xFFFF8B78), modifier = Modifier.size(25.dp))
            }
            Spacer(Modifier.height(22.dp))
            Slider(value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f, onValueChange = { if (duration > 0) onSeek((it * duration).toLong()) }, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFFF755B), inactiveTrackColor = Color(0xFF51434E)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), color = Color(0xFFBDB5C0), fontSize = 11.sp)
                Text(formatTime(duration), color = Color(0xFFBDB5C0), fontSize = 11.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(31.dp)) }
                FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(76.dp), shape = RoundedCornerShape(26.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFFF654F), contentColor = Color.White)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(31.dp)) }
            }
            Spacer(Modifier.weight(.55f))
        }
    }
}

@Composable private fun HomeScreen(count: Int, loading: Boolean, error: String, accent: Color, openLibrary: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0A10)).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YOUR SOUND, YOUR SPACE", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                Text("Good music.\nGood moments.", color = Color.White, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, modifier = Modifier.padding(top = 9.dp))
            }
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(17.dp)).background(Color(0xFF211722)), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_flare_logo), contentDescription = "FlareMusic", modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().height(224.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF8C392D), Color(0xFF54263D), Color(0xFF242039)))).clickable { openLibrary() }) {
            Box(Modifier.align(Alignment.TopEnd).padding(16.dp).size(140.dp).clip(RoundedCornerShape(70.dp)).background(Color.White.copy(alpha = .07f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(104.dp).clip(RoundedCornerShape(52.dp)).background(Color.White.copy(alpha = .06f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.GraphicEq, null, tint = accent.copy(alpha = .72f), modifier = Modifier.size(58.dp)) }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                Text("MADE FOR THE MOMENT", color = accent.copy(alpha = .82f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Text("Let the music\ntake over.", color = Color.White, fontSize = 28.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 7.dp))
                Row(Modifier.padding(top = 13.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .16f)).padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Explore library", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(7.dp)); Icon(Icons.Rounded.ArrowForward, null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
        }
        Spacer(Modifier.height(27.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your library", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text(if (loading) "Finding your music…" else "$count tracks ready when you are", color = Color(0xFF9B94A1), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            TextButton(onClick = openLibrary) { Text("See all", color = accent, fontWeight = FontWeight.SemiBold) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            listOf(Triple(Icons.Rounded.MusicNote, "Songs", "All tracks"), Triple(Icons.Rounded.Album, "Albums", "Your collection")).forEach { item ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(Color(0xFF19151E)).clickable { openLibrary() }.padding(16.dp)) {
                    Icon(item.first, null, tint = Color(0xFFFF9679), modifier = Modifier.size(24.dp))
                    Text(item.second, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 17.dp))
                    Text(item.third, color = Color(0xFF9B94A1), fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFFA0A0), fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF17131B)).clickable { openLibrary() }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF34202A)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.LibraryMusic, null, tint = Color(0xFFFF9679)) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Pick up where you left off", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("Browse and play something you love", color = Color(0xFF9B94A1), fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFF9B94A1))
        }
        Spacer(Modifier.height(12.dp))
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

@Composable private fun LibraryScreen(tracks: List<Track>, loading: Boolean, youtubePlaylists: List<YouTubePlaylist>, googleStatus: String, playlistLoading: Boolean, playlistError: String, selectedPlaylist: YouTubePlaylist?, selectedPlaylistTracks: List<YouTubePlaylistTrack>, selectedPlaylistLoading: Boolean, selectedPlaylistError: String, openPlaylist: (YouTubePlaylist) -> Unit, closePlaylist: () -> Unit, playPlaylistTrack: (YouTubePlaylistTrack) -> Unit, play: (Track) -> Unit, search: () -> Unit, refresh: () -> Unit, refreshPlaylists: () -> Unit, connectYouTube: () -> Unit) {
    val libraryContext = LocalContext.current
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0D12)).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Your library", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = Color.White); Text(tracks.size.toString() + " songs on this device", color = Color(0xFFA6ADBC), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            IconButton(onClick = refresh) { Icon(Icons.Rounded.Refresh, "Refresh", tint = Violet) }
            IconButton(onClick = search) { Icon(Icons.Rounded.Search, "Search", tint = Violet) }
        }
        if (selectedPlaylist != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = closePlaylist) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Violet) }
                Column(Modifier.weight(1f)) {
                    Text(selectedPlaylist.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("YouTube Music playlist", color = Color(0xFFA6ADBC), fontSize = 12.sp)
                }
                IconButton(onClick = { openPlaylist(selectedPlaylist) }, enabled = !selectedPlaylistLoading) { Icon(Icons.Rounded.Refresh, "Reload playlist", tint = Violet) }
            }
            if (selectedPlaylistLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Violet, trackColor = Color(0xFF292D39))
            if (selectedPlaylistError.isNotBlank()) Text(selectedPlaylistError, color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 18.dp)) {
                items(selectedPlaylistTracks, key = { it.videoId }) { item ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { playPlaylistTrack(item) }.padding(vertical = 8.dp, horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(item.thumbnail, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.artist.ifBlank { "YouTube Music" }, color = Color(0xFF9298A8), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                        }
                        Icon(Icons.Rounded.PlayCircleFilled, null, tint = Violet, modifier = Modifier.size(27.dp))
                    }
                }
            }
        } else {
        Text("YOUTUBE MUSIC PLAYLISTS", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, modifier = Modifier.padding(top = 8.dp, bottom = 9.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (googleStatus.startsWith("Connected")) "Your online library" else "Connect your account to see playlists", color = Color(0xFFA6ADBC), fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = if (googleStatus.startsWith("Connected")) refreshPlaylists else connectYouTube, enabled = !playlistLoading) {
                Text(if (playlistLoading) "Loading…" else if (googleStatus.startsWith("Connected")) "Refresh" else "Connect", color = Mint)
            }
        }
        if (playlistError.isNotBlank()) Text(playlistError, color = Color(0xFFFF8A80), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (youtubePlaylists.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
                items(youtubePlaylists, key = { it.id }) { playlist ->
                    Column(Modifier.width(142.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF171B24)).clickable { openPlaylist(playlist) }.padding(9.dp)) {
                        Artwork(playlist.thumbnail, Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(11.dp)))
                        Text(playlist.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        Text(if (playlist.itemCount > 0) "${playlist.itemCount} tracks" else playlist.description, color = Color(0xFF9298A8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Violet, trackColor = Color(0xFF292D39))
        if (tracks.isEmpty() && !loading) Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Rounded.LibraryMusic, null, tint = Color(0xFF555D6D), modifier = Modifier.size(54.dp)); Text("No local songs yet", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp)); Text("Add audio to your device, then refresh.", color = Color(0xFF9298A8), fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp)) } }
        LazyColumn(contentPadding = PaddingValues(bottom = 18.dp)) { items(tracks, key = { it.id }) { TrackRow(it, onClick = { play(it) }) } }
        }
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
