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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.core.view.WindowCompat
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
private val Violet: Color get() = if (FlarePreferences.dynamicColors.value) FlarePreferences.dynamicAccent.value else FlarePreferences.accents[FlarePreferences.accentIndex.intValue.coerceIn(0, FlarePreferences.accents.lastIndex)]
private val Mint: Color get() = Violet.copy(alpha = .82f)

private object FlarePreferences {
    val glassmorphism = mutableStateOf(true)
    val progressStyle = mutableIntStateOf(0)
    val darkMode = mutableStateOf(true)
    val accentIndex = mutableIntStateOf(0)
    val animations = mutableStateOf(true)
    val compact = mutableStateOf(false)
    val dynamicColors = mutableStateOf(true)
    val dynamicAccent = mutableStateOf(Color(0xFFFF694F))
    val swipeToMinimize = mutableStateOf(true)
    val swipeToChangeTracks = mutableStateOf(true)
    val accents = listOf(
        Color(0xFFFF694F), Color(0xFF9B8CFF), Color(0xFF35C9A5), Color(0xFFFFB84D),
        Color(0xFF64B5F6), Color(0xFFE879B9), Color(0xFFB0C46A), Color(0xFFB39DDB)
    )
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
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
        FlarePreferences.darkMode.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("dark_mode", true)
        FlarePreferences.glassmorphism.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("glassmorphism", true)
        FlarePreferences.progressStyle.intValue = getSharedPreferences("flare_settings", MODE_PRIVATE).getInt("progress_style", 0).coerceIn(0, 2)
        FlarePreferences.accentIndex.intValue = getSharedPreferences("flare_settings", MODE_PRIVATE).getInt("accent_index", 0).coerceIn(0, FlarePreferences.accents.lastIndex)
        FlarePreferences.animations.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("animations", true)
        FlarePreferences.compact.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("compact", false)
        FlarePreferences.dynamicColors.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("dynamic_colors", true)
        FlarePreferences.swipeToMinimize.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("gesture_minimize", true)
        FlarePreferences.swipeToChangeTracks.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("gesture_tracks", true)
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
        setContent { FlareTheme(amoledMode, FlarePreferences.dynamicColors.value, FlarePreferences.darkMode.value) { FlareApp(activePlayer, ::loadTracks, amoledMode, googleStatus, youtubePlaylists, playlistLoading, playlistError, ::connectGoogle, ::syncYouTubePlaylists, ::disconnectYouTube) { enabled -> amoledMode = enabled; getSharedPreferences("flare_settings", MODE_PRIVATE).edit().putBoolean("amoled", enabled).apply() } } }
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

@Composable private fun FlareTheme(amoled: Boolean, dynamicColors: Boolean, darkMode: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemPalette = if (dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { if (darkMode) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) } else null
    val accent = systemPalette?.primary ?: FlarePreferences.accents[FlarePreferences.accentIndex.intValue.coerceIn(0, FlarePreferences.accents.lastIndex)]
    val darkFallback = darkColorScheme(
        primary = accent, secondary = accent.copy(alpha = .85f), tertiary = Mint,
        background = Color(0xFF101116), surface = Color(0xFF171922), surfaceVariant = Color(0xFF292D39),
        onPrimary = Color.White, onSecondary = Color(0xFF101116), onTertiary = Color(0xFF101116),
        onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Color(0xFFE1E3EA),
        inverseSurface = Color(0xFFE1E3EA), inverseOnSurface = Color(0xFF17191F)
    )
    val lightFallback = lightColorScheme(
        primary = accent, secondary = accent.copy(alpha = .85f), tertiary = accent,
        background = Color(0xFFF7F7FB), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE9EAF1),
        onPrimary = Color.White, onSecondary = Color.White, onTertiary = Color.White,
        onBackground = Color(0xFF171821), onSurface = Color(0xFF171821), onSurfaceVariant = Color(0xFF555966),
        outlineVariant = Color(0xFFD5D7E0)
    )
    val wallpaperScheme = systemPalette ?: if (darkMode) darkFallback else lightFallback
    SideEffect {
        if (dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            FlarePreferences.dynamicAccent.value = wallpaperScheme.primary
        }
    }
    val expressiveScheme = wallpaperScheme
    MaterialTheme(
        colorScheme = if (amoled && darkMode) expressiveScheme.copy(background = Color.Black, surface = Color.Black, surfaceContainer = Color(0xFF080808)) else expressiveScheme,
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
    val context = LocalContext.current
    var favouriteIds by remember {
        mutableStateOf(context.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE)
            .getStringSet("favourite_ids", emptySet())?.toSet() ?: emptySet())
    }
    val innerTube = remember { InnerTubeClient() }
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
    fun toggleFavourite(track: Track) {
        val updated = favouriteIds.toMutableSet()
        if (!updated.add(track.id.toString())) updated.remove(track.id.toString())
        favouriteIds = updated
        context.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE)
            .edit().putStringSet("favourite_ids", updated).apply()
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
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Scaffold(containerColor = Color.Transparent, bottomBar = {
        Column(Modifier.padding(bottom = 12.dp)) {
            if (current != null) {
                val miniProgress = if (totalDuration > 0) (position.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f) else 0f
                val miniColors = MaterialTheme.colorScheme
                val miniShape = RoundedCornerShape(50)
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)
                        .clip(miniShape)
                        .background(Brush.linearGradient(listOf(
                            miniColors.surfaceVariant.copy(alpha = if (FlarePreferences.glassmorphism.value) 0.10f else 0.94f),
                            miniColors.primaryContainer.copy(alpha = if (FlarePreferences.glassmorphism.value) 0.08f else 0.38f)
                        )))
                        .clickable { playerExpanded = true }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 9.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Artwork(current!!.artwork, Modifier.size(42.dp).clip(CircleShape))
                        Column(Modifier.weight(1f).padding(start = 10.dp, end = 6.dp)) {
                            Text(current!!.title, color = miniColors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(current!!.artist, color = miniColors.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        }
                        IconButton(onClick = { if (playing) player.pause() else player.play() }, modifier = Modifier.size(36.dp).clip(CircleShape).background(miniColors.primary)) {
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = miniColors.onPrimary, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { player.seekToNextMediaItem() }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Rounded.SkipNext, null, tint = miniColors.onSurface, modifier = Modifier.size(21.dp))
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp).clip(RoundedCornerShape(2.dp)).background(miniColors.onSurface.copy(alpha = 0.10f))) {
                        Box(Modifier.fillMaxWidth(miniProgress).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(miniColors.primary))
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(34.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = if (FlarePreferences.glassmorphism.value) 0.12f else if (amoled) 0.88f else 0.76f))
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
                                tint = if (selected) Violet else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(21.dp)
                            )
                            Text(item, color = if (selected) Violet else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp), maxLines = 1)
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
                "Library" -> LibraryScreen(tracks, loading, tracks.filter { it.id.toString() in favouriteIds }, youtubePlaylists, googleStatus, playlistLoading, playlistError, selectedPlaylist, selectedPlaylistTracks, selectedPlaylistLoading, selectedPlaylistError, ::openYouTubePlaylist, { selectedPlaylist = null; selectedPlaylistTracks = emptyList() }, { item -> playYouTubePlaylistQueue(item) }, ::play, { selectTab("Search") }, { tracks = emptyList(); loading = true }, onSyncPlaylists, onConnectGoogle)
                else -> SettingsScreen(amoled, onAmoledChange, googleStatus, youtubePlaylists, playlistLoading, playlistError, onConnectGoogle, onSyncPlaylists, onDisconnectYouTube)
            }
        }
    }
    AnimatedVisibility(visible = playerExpanded && current != null, modifier = Modifier.fillMaxSize(), enter = fadeIn() + slideInVertically { it / 6 }, exit = fadeOut() + slideOutVertically { it / 6 }) {
        current?.let { track ->
            val queue = (0 until player.mediaItemCount).mapNotNull { index -> queueTracks[player.getMediaItemAt(index).mediaId] }
            FullPlayer(track, playing, position, totalDuration,
                isFavourite = track.id.toString() in favouriteIds, queue = queue,
                onClose = { playerExpanded = false },
                onPlayPause = { if (playing) player.pause() else player.play() },
                onSeek = { player.seekTo(it) }, onPrevious = { player.seekToPreviousMediaItem() }, onNext = { player.seekToNextMediaItem() },
                onToggleFavourite = { toggleFavourite(track) },
                onPlayQueueItem = { index -> player.seekTo(index, 0L); player.play() },
                onRemoveQueueItem = { if (it in 0 until player.mediaItemCount) player.removeMediaItem(it) },
                onClearQueue = { if (player.mediaItemCount > 0) player.clearMediaItems() },
                onStartSleepTimer = { minutes -> scope.launch { delay(minutes * 60_000L); player.pause() } },
                swipeToMinimize = FlarePreferences.swipeToMinimize.value,
                swipeToChangeTracks = FlarePreferences.swipeToChangeTracks.value
            )
        }
    }
    }
}

@Composable private fun SettingsScreen(amoled: Boolean, onAmoledChange: (Boolean) -> Unit, googleStatus: String, playlists: List<YouTubePlaylist>, loading: Boolean, error: String, onConnect: () -> Unit, onSync: () -> Unit, onDisconnectYouTube: () -> Unit) {
    val settingsContext = LocalContext.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
        Text("Settings", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = MaterialTheme.colorScheme.onSurface)
        Text("Make FlareMusic yours.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp, bottom = 24.dp))
        Text("APPEARANCE", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = Modifier.padding(bottom = 9.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (FlarePreferences.glassmorphism.value) MaterialTheme.colorScheme.surface.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant).clickable {
            FlarePreferences.darkMode.value = !FlarePreferences.darkMode.value
            if (!FlarePreferences.darkMode.value) onAmoledChange(false)
            settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("dark_mode", FlarePreferences.darkMode.value).apply()
        }.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.outlineVariant), contentAlignment = Alignment.Center) { Icon(if (FlarePreferences.darkMode.value) Icons.Rounded.DarkMode else Icons.Rounded.LightMode, null, tint = Mint) }
            Column(Modifier.weight(1f).padding(start = 13.dp)) { Text("Dark theme", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold); Text(if (FlarePreferences.darkMode.value) "Use the dark appearance" else "Use the light appearance", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp)) }
            Switch(checked = FlarePreferences.darkMode.value, onCheckedChange = {
                FlarePreferences.darkMode.value = it
                if (!it) onAmoledChange(false)
                settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("dark_mode", it).apply()
            })
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (FlarePreferences.glassmorphism.value) MaterialTheme.colorScheme.surface.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant).clickable {
            FlarePreferences.glassmorphism.value = !FlarePreferences.glassmorphism.value
            settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("glassmorphism", FlarePreferences.glassmorphism.value).apply()
        }.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Glassmorphism", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold); Text("Translucent surfaces throughout the app", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp)) }
            Switch(checked = FlarePreferences.glassmorphism.value, onCheckedChange = { enabled ->
                FlarePreferences.glassmorphism.value = enabled
                settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("glassmorphism", enabled).apply()
            })
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (FlarePreferences.glassmorphism.value) MaterialTheme.colorScheme.surface.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant).clickable { onAmoledChange(!amoled) }.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.outlineVariant), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.DarkMode, null, tint = Mint) }
            Column(Modifier.weight(1f).padding(start = 13.dp)) { Text("AMOLED mode", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold); Text("Pure black backgrounds", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp)) }
            Switch(checked = amoled, onCheckedChange = onAmoledChange)
        }
        Text("Material You colours adapt to your wallpaper on supported Android versions.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 14.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (FlarePreferences.glassmorphism.value) MaterialTheme.colorScheme.surface.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant).clickable {
            FlarePreferences.dynamicColors.value = !FlarePreferences.dynamicColors.value
            settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("dynamic_colors", FlarePreferences.dynamicColors.value).apply()
        }.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Material You dynamic colours", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text("Use colours from your wallpaper", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Switch(checked = FlarePreferences.dynamicColors.value, onCheckedChange = {
                FlarePreferences.dynamicColors.value = it
                settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("dynamic_colors", it).apply()
            })
        }
        Text("Choose a preset or let Android generate a palette from your wallpaper.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 20.dp))
        Text("PERSONALIZATION", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = Modifier.padding(bottom = 9.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (FlarePreferences.glassmorphism.value) MaterialTheme.colorScheme.surface.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant).padding(17.dp)) {
            Text("Accent colour", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text("Choose the colour used for highlights and controls.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 13.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                FlarePreferences.accents.forEachIndexed { index, color ->
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(15.dp)).background(color).clickable {
                        FlarePreferences.accentIndex.intValue = index
                        settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putInt("accent_index", index).apply()
                    }, contentAlignment = Alignment.Center) {
                        if (FlarePreferences.accentIndex.intValue == index) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Smooth animations", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold); Text("Spring transitions between screens", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                Switch(checked = FlarePreferences.animations.value, onCheckedChange = {
                    FlarePreferences.animations.value = it
                    settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("animations", it).apply()
                })
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Compact layout", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold); Text("Reduce spacing in lists", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                Switch(checked = FlarePreferences.compact.value, onCheckedChange = {
                    FlarePreferences.compact.value = it
                    settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("compact", it).apply()
                })
            }
        }
        Text("PLAYER GESTURES", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = Modifier.padding(top = 20.dp, bottom = 9.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (FlarePreferences.glassmorphism.value) MaterialTheme.colorScheme.surface.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant).padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Swipe down to minimize", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold); Text("Pull down anywhere in the full player", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                Switch(checked = FlarePreferences.swipeToMinimize.value, onCheckedChange = {
                    FlarePreferences.swipeToMinimize.value = it
                    settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("gesture_minimize", it).apply()
                })
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Swipe left/right to change tracks", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold); Text("Swipe left for next, right for previous", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                Switch(checked = FlarePreferences.swipeToChangeTracks.value, onCheckedChange = {
                    FlarePreferences.swipeToChangeTracks.value = it
                    settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("gesture_tracks", it).apply()
                })
            }
        }
        Text("YOUTUBE MUSIC", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = Modifier.padding(bottom = 9.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (FlarePreferences.glassmorphism.value) MaterialTheme.colorScheme.surface.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant).padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MusicNote, null, tint = Mint, modifier = Modifier.size(25.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("YouTube Music account", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    Text(googleStatus, color = if (googleStatus.startsWith("Connected")) Mint else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                }
                if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Mint)
            }
            Text("Sign in through Google's page. Your session is encrypted on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, modifier = Modifier.weight(1f), enabled = !loading) {
                    Text(if (googleStatus.startsWith("Connected")) "Reconnect" else "Connect")
                }
                if (googleStatus.startsWith("Connected")) {
                    OutlinedButton(onClick = onDisconnectYouTube, enabled = !loading) { Text("Disconnect") }
                }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("FLAREMUSIC  •  MADE FOR YOUR MUSIC", color = MaterialTheme.colorScheme.outline, fontSize = 9.sp, letterSpacing = 1.2.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 14.dp))
    }
}

@Composable private fun Artwork(source: String?, modifier: Modifier = Modifier, fitArtwork: Boolean = false) {
    val context = LocalContext.current
    var bitmap by remember(source) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(source) {
        bitmap = withContext(Dispatchers.IO) {
            if (source.isNullOrBlank()) return@withContext null
            val candidates = buildList {
                // Prefer the largest YouTube video thumbnail; some videos do not provide maxres.
                if (source.contains("i.ytimg.com/vi/") || source.contains("img.youtube.com/vi/")) {
                    add(source.replace(Regex("/(default|mqdefault|hqdefault|sddefault|maxresdefault)\\.jpg"), "/maxresdefault.jpg"))
                    add(source.replace(Regex("/(default|mqdefault|hqdefault|sddefault|maxresdefault)\\.jpg"), "/sddefault.jpg"))
                }
                add(source)
                if (source.contains("i.ytimg.com/vi/") || source.contains("img.youtube.com/vi/")) {
                    add(source.replace(Regex("/(default|mqdefault|hqdefault|sddefault|maxresdefault)\\.jpg"), "/hqdefault.jpg"))
                }
            }.distinct()
            var decoded: android.graphics.Bitmap? = null
            for (candidate in candidates) {
                decoded = try {
                    if (candidate.startsWith("content://") || candidate.startsWith("file://")) {
                        context.contentResolver.openInputStream(Uri.parse(candidate))?.use { BitmapFactory.decodeStream(it) }
                    } else {
                        URL(candidate).openConnection().apply { connectTimeout = 7000; readTimeout = 7000 }.getInputStream().use { BitmapFactory.decodeStream(it) }
                    }
                } catch (_: Exception) { null }
                if (decoded != null) break
            }
            decoded?.let { image ->
                // YouTube video thumbnails are 16:9 with the cover centered; crop to square.
                val side = minOf(image.width, image.height)
                if (side > 0 && image.width.toFloat() / image.height.toFloat() > 1.15f) {
                    android.graphics.Bitmap.createBitmap(image, (image.width - side) / 2, 0, side, side)
                } else if (side > 0 && image.height.toFloat() / image.width.toFloat() > 1.15f) {
                    android.graphics.Bitmap.createBitmap(image, 0, (image.height - side) / 2, side, side)
                } else image
            }
        }
    }
    if (bitmap != null) Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "Album art", modifier = modifier, contentScale = if (fitArtwork) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) { Image(painterResource(R.drawable.ic_flare_logo), contentDescription = "Album art", modifier = Modifier.fillMaxSize().padding(5.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
}

@Composable private fun FullPlayer(track: Track, playing: Boolean, position: Long, duration: Long, isFavourite: Boolean, queue: List<Track>, onClose: () -> Unit, onPlayPause: () -> Unit, onSeek: (Long) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onToggleFavourite: () -> Unit, onPlayQueueItem: (Int) -> Unit, onRemoveQueueItem: (Int) -> Unit, onClearQueue: () -> Unit, onStartSleepTimer: (Int) -> Unit, swipeToMinimize: Boolean, swipeToChangeTracks: Boolean) {
    var showQueue by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var sleepTimerMinutes by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().pointerInput(swipeToMinimize, swipeToChangeTracks) {
        var dragX = 0f
        var dragY = 0f
        detectDragGestures(onDragEnd = {
            val horizontal = dragX; val vertical = dragY
            if (kotlin.math.abs(vertical) > kotlin.math.abs(horizontal) && vertical > 85f && swipeToMinimize) onClose()
            else if (kotlin.math.abs(horizontal) > 85f && swipeToChangeTracks) { if (horizontal < 0f) onNext() else onPrevious() }
            dragX = 0f; dragY = 0f
        }, onDragCancel = { dragX = 0f; dragY = 0f }) { _, amount -> dragX += amount.x; dragY += amount.y }
    }.background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface.copy(alpha = if (FlarePreferences.glassmorphism.value) 0.12f else 1f), MaterialTheme.colorScheme.surface.copy(alpha = if (FlarePreferences.glassmorphism.value) 0.12f else 1f), MaterialTheme.colorScheme.surface.copy(alpha = if (FlarePreferences.glassmorphism.value) 0.12f else 1f))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().displayCutoutPadding().padding(horizontal = 24.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Collapse player", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NOW PLAYING", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
                    Text("FlareMusic", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showQueue = true }) { Icon(Icons.Rounded.QueueMusic, "Queue", tint = MaterialTheme.colorScheme.onSurface) }
            }
            Spacer(Modifier.weight(.65f))
            Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 8.dp).clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (FlarePreferences.glassmorphism.value) 0.12f else 1f), MaterialTheme.colorScheme.surface.copy(alpha = if (FlarePreferences.glassmorphism.value) 0.12f else 1f)))).padding(10.dp)) {
                Artwork(track.artwork, Modifier.fillMaxSize().clip(RoundedCornerShape(23.dp)), fitArtwork = true)
            }
            Spacer(Modifier.weight(.65f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, modifier = Modifier.padding(top = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onToggleFavourite) { Icon(if (isFavourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favourite", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp)) }
            }
            Spacer(Modifier.height(22.dp))
            Slider(
                value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { if (duration > 0) onSeek((it * duration).toLong()) },
                colors = SliderDefaults.colors(
                    thumbColor = if (FlarePreferences.progressStyle.intValue == 1) MaterialTheme.colorScheme.primary.copy(alpha = .45f) else MaterialTheme.colorScheme.primary,
                    activeTrackColor = if (FlarePreferences.progressStyle.intValue == 2) MaterialTheme.colorScheme.primary.copy(alpha = .78f) else MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (FlarePreferences.glassmorphism.value) .42f else 1f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                ),
                modifier = Modifier.padding(vertical = if (FlarePreferences.progressStyle.intValue == 1) 0.dp else 2.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Text(formatTime(duration), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(31.dp)) }
                FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(76.dp), shape = RoundedCornerShape(26.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onSurface)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipNext, "Next", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(31.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { showSleepTimer = true }) {
                    Icon(Icons.Rounded.Bedtime, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (sleepTimerMinutes > 0) "Sleep timer: " + sleepTimerMinutes + " min" else "Sleep timer", color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.weight(.35f))
        }
        if (showQueue) AlertDialog(onDismissRequest = { showQueue = false }, title = { Text("Playing queue") }, text = {
            if (queue.isEmpty()) Text("The queue is empty.") else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(queue.size) { index -> val item = queue[index]
                    Row(Modifier.fillMaxWidth().clickable { onPlayQueueItem(index); showQueue = false }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(item.artwork, Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(item.title, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onRemoveQueueItem(index) }) { Icon(Icons.Rounded.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showQueue = false }) { Text("Done") } }, dismissButton = { TextButton(onClick = onClearQueue) { Text("Clear queue") } })
        if (showSleepTimer) AlertDialog(onDismissRequest = { showSleepTimer = false }, title = { Text("Sleep timer") }, text = { Column {
            Text("Pause playback after", color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(5, 10, 15, 30, 45, 60).forEach { minutes ->
                Row(Modifier.fillMaxWidth().clickable { sleepTimerMinutes = minutes }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = sleepTimerMinutes == minutes, onClick = { sleepTimerMinutes = minutes })
                    Text("$minutes minutes", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        } }, confirmButton = { TextButton(onClick = { if (sleepTimerMinutes > 0) onStartSleepTimer(sleepTimerMinutes); showSleepTimer = false }) { Text("Start") } }, dismissButton = { TextButton(onClick = { sleepTimerMinutes = 0; showSleepTimer = false }) { Text("Cancel") } })
    }
}

@Composable private fun HomeScreen(count: Int, loading: Boolean, error: String, accent: Color, openLibrary: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val homeSurface = colors.surface
    val homeCard = colors.surfaceVariant.copy(alpha = if (FlarePreferences.glassmorphism.value) 0.10f else 0.72f)
    Column(Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YOUR SOUND, YOUR SPACE", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                Text("Good music.\nGood moments.", color = colors.onBackground, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, modifier = Modifier.padding(top = 9.dp))
            }
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(17.dp)).background(colors.primaryContainer.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_flare_logo), contentDescription = "FlareMusic", modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().height(224.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.88f), colors.secondary.copy(alpha = 0.78f), colors.tertiary.copy(alpha = 0.68f)))).clickable { openLibrary() }) {
            Box(Modifier.align(Alignment.TopEnd).padding(16.dp).size(140.dp).clip(RoundedCornerShape(70.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .07f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(104.dp).clip(RoundedCornerShape(52.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.GraphicEq, null, tint = accent.copy(alpha = .72f), modifier = Modifier.size(58.dp)) }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                Text("MADE FOR THE MOMENT", color = accent.copy(alpha = .82f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Text("Let the music\ntake over.", color = MaterialTheme.colorScheme.onSurface, fontSize = 28.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 7.dp))
                Row(Modifier.padding(top = 13.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .16f)).padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Explore library", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(7.dp)); Icon(Icons.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(15.dp))
                }
            }
        }
        Spacer(Modifier.height(27.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your library", color = colors.onBackground, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text(if (loading) "Finding your music…" else "$count tracks ready when you are", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            TextButton(onClick = openLibrary) { Text("See all", color = accent, fontWeight = FontWeight.SemiBold) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            listOf(Triple(Icons.Rounded.MusicNote, "Songs", "All tracks"), Triple(Icons.Rounded.Album, "Albums", "Your collection")).forEach { item ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(homeCard).clickable { openLibrary() }.padding(16.dp)) {
                    Icon(item.first, null, tint = colors.primary, modifier = Modifier.size(24.dp))
                    Text(item.second, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 17.dp))
                    Text(item.third, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(homeCard).clickable { openLibrary() }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(colors.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.LibraryMusic, null, tint = colors.primary) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Pick up where you left off", color = colors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("Browse and play something you love", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable private fun SearchScreen(query: String, onQuery: (String) -> Unit, results: List<Track>, play: (Track) -> Unit, online: List<OnlineTrack>, searching: Boolean, searchOnline: (String) -> Unit, playOnline: (OnlineTrack) -> Unit, error: String) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 18.dp)) {
        Text("Discover", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 18.dp))
        Text("Find something for the moment.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
        OutlinedTextField(value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Track, artist or album") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(18.dp), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Violet, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant, focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, focusedLeadingIconColor = Violet))
        Spacer(Modifier.height(10.dp))
        Button(onClick = { searchOnline(query) }, enabled = query.isNotBlank() && !searching, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = MaterialTheme.colorScheme.onPrimary)) {
            if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Icon(Icons.Rounded.Public, null)
            Spacer(Modifier.width(9.dp)); Text(if (searching) "Searching…" else "Search online", fontWeight = FontWeight.Bold)
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 18.dp, bottom = 16.dp)) {
            item { Text("ON THIS DEVICE", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            if (results.isEmpty()) item { Text(if (query.isBlank()) "Search your downloaded music" else "No matching tracks in your library", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp)) }
            items(results, key = { it.id }) { TrackRow(it, onClick = { play(it) }) }
            item { Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text("ONLINE", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp); Spacer(Modifier.width(8.dp)); Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant)) } }
            if (online.isEmpty() && !searching) item { Text("Search online to discover more music", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp)) }
            items(online, key = { it.videoId }) { result -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { playOnline(result) }.padding(vertical = 9.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(result.thumbnail, Modifier.size(54.dp).clip(RoundedCornerShape(13.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(result.title, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text(result.author + " • " + result.duration, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1) }
                Icon(Icons.Rounded.PlayCircleFilled, null, tint = Violet, modifier = Modifier.size(30.dp))
            } }
        }
    }
}

@Composable private fun LibraryScreen(tracks: List<Track>, loading: Boolean, favouriteTracks: List<Track>, youtubePlaylists: List<YouTubePlaylist>, googleStatus: String, playlistLoading: Boolean, playlistError: String, selectedPlaylist: YouTubePlaylist?, selectedPlaylistTracks: List<YouTubePlaylistTrack>, selectedPlaylistLoading: Boolean, selectedPlaylistError: String, openPlaylist: (YouTubePlaylist) -> Unit, closePlaylist: () -> Unit, playPlaylistTrack: (YouTubePlaylistTrack) -> Unit, play: (Track) -> Unit, search: () -> Unit, refresh: () -> Unit, refreshPlaylists: () -> Unit, connectYouTube: () -> Unit) {
    val libraryContext = LocalContext.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Your library", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = MaterialTheme.colorScheme.onSurface); Text(tracks.size.toString() + " songs on this device", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            IconButton(onClick = refresh) { Icon(Icons.Rounded.Refresh, "Refresh", tint = Violet) }
            IconButton(onClick = search) { Icon(Icons.Rounded.Search, "Search", tint = Violet) }
        }
        if (selectedPlaylist != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = closePlaylist) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Violet) }
                Column(Modifier.weight(1f)) {
                    Text(selectedPlaylist.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("YouTube Music playlist", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                IconButton(onClick = { openPlaylist(selectedPlaylist) }, enabled = !selectedPlaylistLoading) { Icon(Icons.Rounded.Refresh, "Reload playlist", tint = Violet) }
            }
            if (selectedPlaylistLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Violet, trackColor = MaterialTheme.colorScheme.outlineVariant)
            if (selectedPlaylistError.isNotBlank()) Text(selectedPlaylistError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 18.dp)) {
                items(selectedPlaylistTracks, key = { it.videoId }) { item ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { playPlaylistTrack(item) }.padding(vertical = 8.dp, horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(item.thumbnail, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(item.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.artist.ifBlank { "YouTube Music" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                        }
                        Icon(Icons.Rounded.PlayCircleFilled, null, tint = Violet, modifier = Modifier.size(27.dp))
                    }
                }
            }
        } else {
        if (favouriteTracks.isNotEmpty()) {
            Text("FAVOURITES", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            LazyColumn(Modifier.heightIn(max = 230.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(favouriteTracks, key = { "fav:${it.id}" }) { TrackRow(it, onClick = { play(it) }) }
            }
        }
        Text("YOUTUBE MUSIC PLAYLISTS", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, modifier = Modifier.padding(top = 8.dp, bottom = 9.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (googleStatus.startsWith("Connected")) "Your online library" else "Connect your account to see playlists", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = if (googleStatus.startsWith("Connected")) refreshPlaylists else connectYouTube, enabled = !playlistLoading) {
                Text(if (playlistLoading) "Loading…" else if (googleStatus.startsWith("Connected")) "Refresh" else "Connect", color = Mint)
            }
        }
        if (playlistError.isNotBlank()) Text(playlistError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (youtubePlaylists.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
                items(youtubePlaylists, key = { it.id }) { playlist ->
                    Column(Modifier.width(142.dp).clip(RoundedCornerShape(16.dp)).background(if (FlarePreferences.glassmorphism.value) MaterialTheme.colorScheme.surface.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant).clickable { openPlaylist(playlist) }.padding(9.dp)) {
                        Artwork(playlist.thumbnail, Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(11.dp)))
                        Text(playlist.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        Text(if (playlist.itemCount > 0) "${playlist.itemCount} tracks" else playlist.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Violet, trackColor = MaterialTheme.colorScheme.outlineVariant)
        if (tracks.isEmpty() && !loading) Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Rounded.LibraryMusic, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(54.dp)); Text("No local songs yet", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp)); Text("Add audio to your device, then refresh.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp)) } }
        LazyColumn(contentPadding = PaddingValues(bottom = 18.dp)) { items(tracks, key = { it.id }) { TrackRow(it, onClick = { play(it) }) } }
        }
    }
}

@Composable private fun TrackRow(track: Track, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = if (FlarePreferences.compact.value) 4.dp else 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track.artwork, Modifier.size(50.dp).clip(RoundedCornerShape(13.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(track.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(Icons.Rounded.PlayCircleFilled, null, tint = Violet, modifier = Modifier.size(27.dp))
    }
}

private fun formatTime(ms: Long): String { val seconds = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(seconds / 60, seconds % 60) }
