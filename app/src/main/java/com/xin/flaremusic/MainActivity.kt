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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
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

object FlarePreferences {
    val progressStyle = mutableIntStateOf(0)
    val darkMode = mutableStateOf(true)
    val accentIndex = mutableIntStateOf(0)
    val animations = mutableStateOf(true)
    val compact = mutableStateOf(false)
    val dynamicColors = mutableStateOf(false)
    val dynamicAccent = mutableStateOf(Color(0xFFFF6B4A))
    val swipeToMinimize = mutableStateOf(true)
    val swipeToChangeTracks = mutableStateOf(true)
    val showMiniPlayer = mutableStateOf(true)
    val showNavLabels = mutableStateOf(true)
    val homeLayout = mutableIntStateOf(0) // 0: showcase, 1: compact, 2: stats first
    val artworkStyle = mutableIntStateOf(0) // 0: crop, 1: fit, 2: soft glass
    val navStyle = mutableIntStateOf(0) // 0: pill, 1: flat, 2: floating
    val fontScale = mutableFloatStateOf(1f)
    val cornerStyle = mutableIntStateOf(0) // 0: rounded, 1: medium, 2: sharp
    val glassEffects = mutableStateOf(true)
    val playlistStyle = mutableIntStateOf(0) // 0: list, 1: cards, 2: compact
    val playerStyle = mutableIntStateOf(0) // 0: classic, 1: immersive, 2: vinyl, 3: minimal
    val playerBackground = mutableIntStateOf(0) // 0: theme, 1: blur, 2: gradient, 3: dark glass
    val playerAnimation = mutableIntStateOf(0) // 0: subtle, 1: morph, 2: pulse, 3: ambient
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
    private var youtubeAccountName by mutableStateOf<String?>(null)
    private var youtubePlaylists by mutableStateOf(emptyList<YouTubePlaylist>())
    private var playlistLoading by mutableStateOf(false)
    private var playlistError by mutableStateOf("")
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showApp()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = getSharedPreferences("flare_settings", MODE_PRIVATE)
        FlarePreferences.darkMode.value = settings.getBoolean("dark_mode", true)
        FlarePreferences.glassEffects.value = settings.getBoolean("glass_effects", true)
        FlarePreferences.animations.value = settings.getBoolean("animations", true)
        amoledMode = settings.getBoolean("amoled", false)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // Material theme controls system bar icon contrast; do not force dark-mode icons here.
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
                        youtubeAccountName = YouTubeAccount.fetchDisplayName(cookieHeader)
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
                val valid = YouTubeSessionVerifier.verify(savedCookies)
                if (valid) {
                    googleStatus = "Connected to YouTube Music"
                    youtubeAccountName = YouTubeAccount.fetchDisplayName(savedCookies)
                    playlistLoading = true
                    playlistError = ""
                    try {
                        youtubePlaylists = YouTubePlaylists.fetchFromMusicSession(savedCookies)
                        playlistError = if (youtubePlaylists.isEmpty()) "No playlists found in your YouTube Music library." else ""
                    } catch (e: Exception) {
                        playlistError = e.message ?: "Couldn't load YouTube Music playlists."
                    } finally {
                        playlistLoading = false
                    }
                } else {
                    YouTubeSessionStore.clear(this@MainActivity)
                    googleStatus = "Not connected"
                    youtubePlaylists = emptyList()
                    playlistError = "Your YouTube Music session needs to be connected again."
                }
            }
        }
        amoledMode = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("amoled", false)
        FlarePreferences.darkMode.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("dark_mode", true)
        FlarePreferences.progressStyle.intValue = getSharedPreferences("flare_settings", MODE_PRIVATE).getInt("progress_style", 0).coerceIn(0, 2)
        FlarePreferences.accentIndex.intValue = getSharedPreferences("flare_settings", MODE_PRIVATE).getInt("accent_index", 0).coerceIn(0, FlarePreferences.accents.lastIndex)
        FlarePreferences.animations.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("animations", true)
        FlarePreferences.compact.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("compact", false)
        FlarePreferences.dynamicColors.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("dynamic_colors", false)
        FlarePreferences.swipeToMinimize.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("gesture_minimize", true)
        FlarePreferences.swipeToChangeTracks.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("gesture_tracks", true)
        FlarePreferences.showMiniPlayer.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("show_mini_player", true)
        FlarePreferences.showNavLabels.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("show_nav_labels", true)
        FlarePreferences.homeLayout.intValue = getSharedPreferences("flare_settings", MODE_PRIVATE).getInt("home_layout", 0).coerceIn(0, 2)
        FlarePreferences.artworkStyle.intValue = getSharedPreferences("flare_settings", MODE_PRIVATE).getInt("artwork_style", 0).coerceIn(0, 2)
        FlarePreferences.navStyle.intValue = getSharedPreferences("flare_settings", MODE_PRIVATE).getInt("nav_style", 0).coerceIn(0, 2)
        FlarePreferences.fontScale.floatValue = getSharedPreferences("flare_settings", MODE_PRIVATE).getFloat("font_scale", 1f).coerceIn(.85f, 1.2f)
        FlarePreferences.cornerStyle.intValue = getSharedPreferences("flare_settings", MODE_PRIVATE).getInt("corner_style", 0).coerceIn(0, 2)
        FlarePreferences.glassEffects.value = getSharedPreferences("flare_settings", MODE_PRIVATE).getBoolean("glass_effects", true)
        FlarePreferences.playlistStyle.intValue = getSharedPreferences("flare_settings", MODE_PRIVATE).getInt("playlist_style", 0).coerceIn(0, 2)
        FlarePreferences.playerStyle.intValue = getSharedPreferences("flare_settings", MODE_PRIVATE).getInt("player_style", 0).coerceIn(0, 3)
        FlarePreferences.playerBackground.intValue = getSharedPreferences("flare_settings", MODE_PRIVATE).getInt("player_background", 0).coerceIn(0, 3)
        FlarePreferences.playerAnimation.intValue = getSharedPreferences("flare_settings", MODE_PRIVATE).getInt("player_animation", 0).coerceIn(0, 3)
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
        setContent { FlareTheme(amoledMode, FlarePreferences.dynamicColors.value, FlarePreferences.darkMode.value) { FlareApp(activePlayer, ::loadTracks, amoledMode, googleStatus, youtubeAccountName, youtubePlaylists, playlistLoading, playlistError, ::connectGoogle, ::syncYouTubePlaylists, ::disconnectYouTube) { enabled -> amoledMode = enabled; getSharedPreferences("flare_settings", MODE_PRIVATE).edit().putBoolean("amoled", enabled).apply() } } }
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
            youtubeAccountName = if (valid) YouTubeAccount.fetchDisplayName(savedCookies!!) else null
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
        youtubeAccountName = null
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


private fun Modifier.nativeBlur(radius: Float): Modifier = graphicsLayer {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        renderEffect = android.graphics.RenderEffect
            .createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP)
            .asComposeRenderEffect()
    }
}

@Composable
private fun GlassAmbientLayer(
    modifier: Modifier = Modifier,
    artwork: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    radius: Float = 26f
) {
    Box(modifier.clip(RoundedCornerShape(24.dp))) {
        if (artwork != null) {
            Artwork(artwork, Modifier.matchParentSize().nativeBlur(radius))
        } else {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.radialGradient(
                            listOf(accent.copy(alpha = .48f), MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .82f))
                        )
                    )
                    .nativeBlur(radius)
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = .46f))
        )
    }
}

@Composable private fun FlareTheme(amoled: Boolean, dynamicColors: Boolean, darkMode: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemPalette = if (dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { if (darkMode) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) } else null
    val accent = systemPalette?.primary ?: FlarePreferences.accents[FlarePreferences.accentIndex.intValue.coerceIn(0, FlarePreferences.accents.lastIndex)]
    val darkFallback = darkColorScheme(
        primary = accent, secondary = accent.copy(alpha = .85f), tertiary = Mint,
        background = Color(0xFF0B0D12), surface = Color(0xFF151922), surfaceVariant = Color(0xFF242B36),
        onPrimary = Color.White, onSecondary = Color(0xFF101116), onTertiary = Color(0xFF101116),
        onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Color(0xFFE1E3EA),
        inverseSurface = Color(0xFFE1E3EA), inverseOnSurface = Color(0xFF17191F)
    )
    val lightFallback = lightColorScheme(
        primary = accent, secondary = accent.copy(alpha = .85f), tertiary = accent,
        background = Color(0xFFFFF8F3), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFF2E8E1),
        onPrimary = Color.White, onSecondary = Color.White, onTertiary = Color.White,
        onBackground = Color(0xFF171821), onSurface = Color(0xFF171821), onSurfaceVariant = Color(0xFF555966),
        outlineVariant = Color(0xFFE4D6CC)
    )
    val wallpaperScheme = systemPalette ?: if (darkMode) darkFallback else lightFallback
    SideEffect {
        if (dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            FlarePreferences.dynamicAccent.value = wallpaperScheme.primary
        }
        val controller = WindowCompat.getInsetsController((context as ComponentActivity).window, (context as ComponentActivity).window.decorView)
        controller.isAppearanceLightStatusBars = !darkMode
        controller.isAppearanceLightNavigationBars = !darkMode
    }
    // Dynamic palettes can occasionally return an unusable dark-mode onSurface
    // on vendor ROMs. Keep the expressive wallpaper colors, but guarantee readable
    // foreground colors in both appearances.
    val expressiveScheme = if (darkMode) {
        wallpaperScheme.copy(
            onPrimary = Color.White,
            onSecondary = Color.White,
            onTertiary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFD7D9E2),
            outline = Color(0xFF9EA1AD),
            outlineVariant = Color(0xFF3A3D47)
        )
    } else {
        wallpaperScheme.copy(
            onBackground = Color(0xFF171821),
            onSurface = Color(0xFF171821),
            onSurfaceVariant = Color(0xFF5A5D68),
            onPrimary = Color.White,
            onSecondary = Color.White,
            onTertiary = Color.White
        )
    }
    val baseDensity = LocalDensity.current
    val customDensity = Density(baseDensity.density, FlarePreferences.fontScale.floatValue)
    CompositionLocalProvider(LocalDensity provides customDensity) {
        MaterialTheme(
            colorScheme = if (amoled && darkMode) expressiveScheme.copy(background = Color.Black, surface = Color.Black, surfaceContainer = Color(0xFF080808)) else expressiveScheme,
            content = content
        )
    }
}

@Composable private fun FlareApp(player: Player, scan: suspend () -> List<Track>, amoled: Boolean, googleStatus: String, youtubeAccountName: String?, youtubePlaylists: List<YouTubePlaylist>, playlistLoading: Boolean, playlistError: String, onConnectGoogle: () -> Unit, onSyncPlaylists: () -> Unit, onDisconnectYouTube: () -> Unit, onAmoledChange: (Boolean) -> Unit) {
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
    var trackToAdd by remember { mutableStateOf<OnlineTrack?>(null) }
    var selectedPlaylist by remember { mutableStateOf<YouTubePlaylist?>(null) }
    var selectedPlaylistTracks by remember { mutableStateOf(emptyList<YouTubePlaylistTrack>()) }
    var selectedPlaylistLoading by remember { mutableStateOf(false) }
    var selectedPlaylistError by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var playlistSearchResults by remember { mutableStateOf(emptyList<OnlineTrack>()) }
    var playlistSearchLoading by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var lyricsLoading by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<SyncedLyrics?>(null) }
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
    LaunchedEffect(current?.id) {
        val track = current
        if (track == null) {
            lyrics = null
            lyricsLoading = false
        } else {
            showLyrics = false
            lyrics = null
            lyricsLoading = true
            lyrics = runCatching { LyricsRepository.fetch(track) }.getOrNull()
            lyricsLoading = false
        }
    }
    LaunchedEffect(Unit) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                current = mediaItem?.let { item ->
                    queueTracks[item.mediaId] ?: Track(
                        id = item.mediaId.hashCode().toLong(),
                        title = item.mediaMetadata.title?.toString() ?: "Unknown",
                        artist = item.mediaMetadata.artist?.toString() ?: "Unknown artist",
                        album = item.mediaMetadata.albumTitle?.toString() ?: "",
                        uri = item.localConfiguration?.uri ?: Uri.EMPTY,
                        duration = 0L,
                        artwork = item.mediaMetadata.artworkUri?.toString()
                    )
                }
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

    fun searchPlaylistSongs(term: String) {
        if (term.isBlank()) {
            playlistSearchResults = emptyList()
            return
        }
        scope.launch {
            playlistSearchLoading = true
            playlistSearchResults = runCatching { innerTube.search(term) }.getOrDefault(emptyList())
            playlistSearchLoading = false
        }
    }

    fun addOnlineToSelectedPlaylist(result: OnlineTrack) {
        val playlist = selectedPlaylist ?: return
        scope.launch {
            try {
                val cookies = YouTubeSessionStore.read(context)
                    ?: throw IllegalStateException("Connect YouTube Music first.")
                YouTubePlaylists.addToPlaylist(cookies, playlist.id, result.videoId)
                playlistSearchResults = emptyList()
                openYouTubePlaylist(playlist)
                onSyncPlaylists()
            } catch (e: Exception) {
                selectedPlaylistError = e.message ?: "Couldn't add song to playlist."
            }
        }
    }

    fun removeFromSelectedPlaylist(item: YouTubePlaylistTrack) {
        val playlist = selectedPlaylist ?: return
        scope.launch {
            try {
                val cookies = YouTubeSessionStore.read(context)
                    ?: throw IllegalStateException("Connect YouTube Music first.")
                YouTubePlaylists.removeFromPlaylist(cookies, playlist.id, item.videoId)
                selectedPlaylistTracks = selectedPlaylistTracks.filterNot { it.videoId == item.videoId }
                onSyncPlaylists()
            } catch (e: Exception) {
                selectedPlaylistError = e.message ?: "Couldn't remove song from playlist."
            }
        }
    }

    fun renameSelectedPlaylist(title: String) {
        val playlist = selectedPlaylist ?: return
        scope.launch {
            try {
                val cookies = YouTubeSessionStore.read(context)
                    ?: throw IllegalStateException("Connect YouTube Music first.")
                YouTubePlaylists.renamePlaylist(cookies, playlist.id, title)
                selectedPlaylist = playlist.copy(title = title.trim())
                onSyncPlaylists()
            } catch (e: Exception) {
                selectedPlaylistError = e.message ?: "Couldn't rename playlist."
            }
        }
    }

    fun deleteSelectedPlaylist() {
        val playlist = selectedPlaylist ?: return
        scope.launch {
            try {
                val cookies = YouTubeSessionStore.read(context)
                    ?: throw IllegalStateException("Connect YouTube Music first.")
                YouTubePlaylists.deletePlaylist(cookies, playlist.id)
                selectedPlaylist = null
                selectedPlaylistTracks = emptyList()
                onSyncPlaylists()
            } catch (e: Exception) {
                selectedPlaylistError = e.message ?: "Couldn't delete playlist."
            }
        }
    }

    fun createNewPlaylist(title: String) {
        scope.launch {
            try {
                val cookies = YouTubeSessionStore.read(context)
                    ?: throw IllegalStateException("Connect YouTube Music first.")
                YouTubePlaylists.createPlaylist(cookies, title)
                onSyncPlaylists()
            } catch (e: Exception) {
                selectedPlaylistError = e.message ?: "Couldn't create playlist."
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
    // Back/gesture should always close the top-most in-app surface first.
    // Lyrics is an overlay on top of the player, so it MUST consume Back before
    // the player itself is minimized.
    BackHandler(
        enabled = showLyrics || playerExpanded || selectedPlaylist != null || tab != "Home"
    ) {
        when {
            showLyrics -> showLyrics = false
            playerExpanded -> playerExpanded = false
            selectedPlaylist != null -> {
                selectedPlaylist = null
                selectedPlaylistTracks = emptyList()
                selectedPlaylistError = ""
            }
            tab != "Home" -> selectTab("Home")
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.fillMaxSize().padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
            label = "page",
            transitionSpec = {
                if (FlarePreferences.animations.value) {
                    (fadeIn(animationSpec = androidx.compose.animation.core.tween(260)) + slideInVertically(animationSpec = androidx.compose.animation.core.spring(dampingRatio = .86f, stiffness = 420f)) { it / 14 }) togetherWith
                        (fadeOut(animationSpec = androidx.compose.animation.core.tween(150)) + slideOutVertically(animationSpec = androidx.compose.animation.core.tween(180)) { -it / 20 })
                } else androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            }
        ) { page ->
            when(page) {
                "Home" -> RenovatedHomeScreen(tracks.size, loading, error, Violet, youtubeAccountName) { selectTab("Library") }
                "Search" -> RenovatedSearchScreen(query, { query = it }, tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }, ::play, onlineResults, searching, { searchOnline(query) }, ::playOnline, { trackToAdd = it }, error)
                "Library" -> RenovatedLibraryScreen(tracks, loading, tracks.filter { it.id.toString() in favouriteIds }, youtubePlaylists, googleStatus, playlistLoading, playlistError, selectedPlaylist, selectedPlaylistTracks, selectedPlaylistLoading, selectedPlaylistError, ::openYouTubePlaylist, { selectedPlaylist = null; selectedPlaylistTracks = emptyList() }, { item -> playYouTubePlaylistQueue(item) }, ::play, { selectTab("Search") }, { tracks = emptyList(); loading = true }, onSyncPlaylists, onConnectGoogle, ::searchPlaylistSongs, playlistSearchResults, playlistSearchLoading, ::addOnlineToSelectedPlaylist, ::removeFromSelectedPlaylist, ::renameSelectedPlaylist, ::deleteSelectedPlaylist, ::createNewPlaylist)
                else -> RenovatedSettingsScreen(amoled, onAmoledChange, googleStatus, youtubePlaylists, playlistLoading, playlistError, onConnectGoogle, onSyncPlaylists, onDisconnectYouTube)
            }
        }

        // Overlay the floating player/navigation on top of the page. Scaffold.bottomBar
        // reserves layout height even when the bar is visually floating, which caused
        // the large empty strip above the navigation bar.
        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            if (current != null && FlarePreferences.showMiniPlayer.value) {
                val miniProgress = if (totalDuration > 0) (position.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f) else 0f
                val miniColors = MaterialTheme.colorScheme
                val miniShape = RoundedCornerShape(50.dp)
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp)
                        .height(60.dp)
                        .clip(miniShape)
                        .clickable { playerExpanded = true }
                ) {
                    // Keep the mini-player glassy regardless of the global glass toggle.
                    // The artwork is blurred beneath a translucent tint for a frosted-glass pill.
                    GlassAmbientLayer(
                        Modifier.matchParentSize(),
                        artwork = current!!.artwork,
                        accent = miniColors.primary,
                        radius = 22f
                    )
                    Box(
                        Modifier.matchParentSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color.White.copy(alpha = .12f),
                                        miniColors.surface.copy(alpha = .10f),
                                        miniColors.primary.copy(alpha = .10f)
                                    )
                                )
                            )
                    )
                    Box(
                        Modifier.matchParentSize()
                            .border(1.dp, Color.White.copy(alpha = .22f), miniShape)
                    ) {}
                    Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            Artwork(current!!.artwork, Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)))
                            Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(7.dp).clip(RoundedCornerShape(50.dp)).background(miniColors.primary))
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp, end = 6.dp)) {
                            Text(current!!.title, color = miniColors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                            Text(current!!.artist, color = miniColors.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        }
                        IconButton(onClick = { if (playing) player.pause() else player.play() }, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(50.dp)).background(miniColors.primary)) {
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = miniColors.onPrimary, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { player.seekToNextMediaItem(); player.play() }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Rounded.SkipNext, null, tint = miniColors.onSurface, modifier = Modifier.size(23.dp))
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(if (FlarePreferences.progressStyle.intValue == 1) 1.dp else if (FlarePreferences.progressStyle.intValue == 2) 4.dp else 2.dp).clip(RoundedCornerShape(50.dp)).background(miniColors.onSurface.copy(alpha = 0.10f))) {
                        Box(Modifier.fillMaxWidth(miniProgress).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(miniColors.primary))
                    }
                    Spacer(Modifier.height(0.dp))
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (FlarePreferences.navStyle.intValue == 2) 22.dp else 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                BoxWithConstraints(
                    Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val pillWidth = maxWidth.coerceAtMost(if (FlarePreferences.navStyle.intValue == 2) 420.dp else 500.dp)
                    val itemWidth = pillWidth / 4
                    val navHeight = if (FlarePreferences.navStyle.intValue == 1) 46.dp else if (FlarePreferences.navStyle.intValue == 2) 50.dp else 48.dp
                    val navRadius = if (FlarePreferences.navStyle.intValue == 1) 16.dp else 24.dp
                    val selectedIndex = listOf("Home", "Search", "Library", "Settings").indexOf(tab).coerceAtLeast(0)
                    val indicatorX by animateDpAsState(
                        targetValue = itemWidth * selectedIndex + 8.dp,
                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                        label = "navIndicator"
                    )

                    Box(
                        Modifier
                            .width(pillWidth)
                            .height(navHeight)
                            .clip(RoundedCornerShape(navRadius))
                            .background(
                                if (FlarePreferences.glassEffects.value) Color.Transparent
                                else MaterialTheme.colorScheme.surface.copy(alpha = .96f)
                            )
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.30f),
                                RoundedCornerShape(navRadius)
                            )
                    ) {
                        if (FlarePreferences.glassEffects.value) {
                            Box(
                                Modifier.matchParentSize()
                                    .clip(RoundedCornerShape(navRadius))
                            ) {
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .background(
                                            Brush.radialGradient(
                                                listOf(
                                                    Violet.copy(alpha = .30f),
                                                    MaterialTheme.colorScheme.surface.copy(alpha = .70f)
                                                )
                                            )
                                        )
                                        .nativeBlur(28f)
                                )
                                Box(
                                    Modifier.matchParentSize()
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = .38f))
                                )
                            }
                        }
                        // DA-Tunes-style animated selection capsule.
                        Box(
                            Modifier
                                .offset(x = indicatorX, y = 3.dp)
                                .width(itemWidth - 16.dp)
                                .height(navHeight - 6.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Brush.linearGradient(listOf(Color.White.copy(alpha = .20f), Violet.copy(alpha = .22f), Color.White.copy(alpha = .08f))))
                                .border(1.dp, Color.White.copy(alpha = .20f), RoundedCornerShape(if (FlarePreferences.navStyle.intValue == 1) 12.dp else 20.dp))
                        )

                        Row(Modifier.fillMaxSize()) {
                            listOf(
                                "Home" to Icons.Rounded.Home,
                                "Search" to Icons.Rounded.Search,
                                "Library" to Icons.Rounded.LibraryMusic,
                                "Settings" to Icons.Rounded.Settings
                            ).forEach { (item, icon) ->
                                val selected = tab == item
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable { selectTab(item) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Icon(
                                            icon,
                                            contentDescription = item,
                                            tint = if (selected) Violet
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                            modifier = Modifier.size(if (selected) 21.dp else 20.dp)
                                        )
                                        if (FlarePreferences.showNavLabels.value) {
                                            Text(
                                                item,
                                                fontSize = 7.sp,
                                                lineHeight = 8.sp,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (selected) Violet else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (trackToAdd != null) {
        AlertDialog(
            onDismissRequest = { trackToAdd = null },
            title = { Text("Add to YouTube Music") },
            text = {
                Column {
                    Text(trackToAdd?.title.orEmpty())
                    if (youtubePlaylists.isEmpty()) Text("Connect YouTube Music and refresh playlists first.")
                    youtubePlaylists.forEach { playlist ->
                        TextButton(onClick = {
                            val selected = trackToAdd ?: return@TextButton
                            scope.launch {
                                try {
                                    val cookies = YouTubeSessionStore.read(context)
                                        ?: throw IllegalStateException("Connect YouTube Music first.")
                                    YouTubePlaylists.addToPlaylist(cookies, playlist.id, selected.videoId)
                                    trackToAdd = null
                                    onSyncPlaylists()
                                } catch (e: Exception) { error = e.message ?: "Couldn't add song." }
                            }
                        }) { Text(playlist.title) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { trackToAdd = null }) { Text("Close") } }
        )
    }
    AnimatedVisibility(visible = playerExpanded && current != null, modifier = Modifier.fillMaxSize(), enter = fadeIn() + slideInVertically { it / 6 }, exit = fadeOut() + slideOutVertically { it / 6 }) {
        current?.let { track ->
            val queue = (0 until player.mediaItemCount).mapNotNull { index -> queueTracks[player.getMediaItemAt(index).mediaId] }
            RenovatedFullPlayer(track, playing, position, totalDuration,
                isFavourite = track.id.toString() in favouriteIds, queue = queue,
                onClose = { playerExpanded = false },
                onPlayPause = { if (playing) player.pause() else player.play() },
                onSeek = { player.seekTo(it) },
                onPrevious = { player.seekToPreviousMediaItem(); player.play() },
                onNext = { player.seekToNextMediaItem(); player.play() },
                onToggleFavourite = { toggleFavourite(track) },
                onShowLyrics = { showLyrics = true },
                onPlayQueueItem = { index -> player.seekTo(index, 0L); player.play() },
                onRemoveQueueItem = { if (it in 0 until player.mediaItemCount) player.removeMediaItem(it) },
                onClearQueue = { if (player.mediaItemCount > 0) player.clearMediaItems() },
                onStartSleepTimer = { minutes -> scope.launch { delay(minutes * 60_000L); player.pause() } },
                swipeToMinimize = FlarePreferences.swipeToMinimize.value,
                swipeToChangeTracks = FlarePreferences.swipeToChangeTracks.value
            )
        }
    }
    if (showLyrics && current != null) {
        LyricsSheet(
            track = current!!,
            lyrics = lyrics,
            loading = lyricsLoading,
            positionMs = position,
            onClose = { showLyrics = false },
            onSeek = { player.seekTo(it) },
            onRefresh = {
                scope.launch {
                    lyricsLoading = true
                    lyrics = runCatching { LyricsRepository.fetch(current!!) }.getOrNull()
                    lyricsLoading = false
                }
            }
        )
    }
    }
}

@Composable private fun SettingsScreen(amoled: Boolean, onAmoledChange: (Boolean) -> Unit, googleStatus: String, playlists: List<YouTubePlaylist>, loading: Boolean, error: String, onConnect: () -> Unit, onSync: () -> Unit, onDisconnectYouTube: () -> Unit) {
    val settingsContext = LocalContext.current
    val updateScope = rememberCoroutineScope()
    var checkingUpdates by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var latestRelease by remember { mutableStateOf<FlareRelease?>(null) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
        Text("Settings", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = MaterialTheme.colorScheme.onSurface)
        Text("Make FlareMusic yours.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp, bottom = 24.dp))
        Text("APPEARANCE", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = Modifier.padding(bottom = 9.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)).border(1.dp, Color.White.copy(alpha = .13f), RoundedCornerShape(20.dp)).clickable {
            FlarePreferences.darkMode.value = !FlarePreferences.darkMode.value
            settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("dark_mode", FlarePreferences.darkMode.value).apply()
        }.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.outlineVariant), contentAlignment = Alignment.Center) {
                Icon(if (FlarePreferences.darkMode.value) Icons.Rounded.DarkMode else Icons.Rounded.LightMode, null, tint = Mint)
            }
            Column(Modifier.weight(1f).padding(start = 13.dp)) {
                Text("Dark mode", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text("Use the light or dark appearance", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Switch(checked = FlarePreferences.darkMode.value, onCheckedChange = {
                FlarePreferences.darkMode.value = it
                settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("dark_mode", it).apply()
            })
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)).border(1.dp, Color.White.copy(alpha = .13f), RoundedCornerShape(20.dp)).clickable { onAmoledChange(!amoled) }.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.outlineVariant), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.DarkMode, null, tint = Mint) }
            Column(Modifier.weight(1f).padding(start = 13.dp)) { Text("AMOLED mode", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold); Text("Pure black backgrounds", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp)) }
            Switch(checked = amoled, onCheckedChange = onAmoledChange)
        }
        Text("Material You colours adapt to your wallpaper on supported Android versions.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 14.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)).border(1.dp, Color.White.copy(alpha = .13f), RoundedCornerShape(20.dp)).clickable {
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
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)).border(1.dp, Color.White.copy(alpha = .13f), RoundedCornerShape(20.dp)).padding(17.dp)) {
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
        Text("INTERFACE", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = Modifier.padding(top = 20.dp, bottom = 9.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)).border(1.dp, Color.White.copy(alpha = .13f), RoundedCornerShape(20.dp)).padding(17.dp)) {
            Text("Home screen layout", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text("Choose how much space the home screen uses.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp, bottom = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("Showcase", "Compact", "Stats first").forEachIndexed { index, label ->
                    val selected = FlarePreferences.homeLayout.intValue == index
                    TextButton(onClick = {
                        FlarePreferences.homeLayout.intValue = index
                        settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putInt("home_layout", index).apply()
                    }, modifier = Modifier.weight(1f)) { Text(label, color = if (selected) Violet else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 11.sp) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text("Player artwork", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text("Crop, fit, or soften artwork presentation.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("Crop", "Fit", "Glass").forEachIndexed { index, label ->
                    val selected = FlarePreferences.artworkStyle.intValue == index
                    TextButton(onClick = {
                        FlarePreferences.artworkStyle.intValue = index
                        settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putInt("artwork_style", index).apply()
                    }, modifier = Modifier.weight(1f)) { Text(label, color = if (selected) Violet else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 11.sp) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text("Navigation bar style", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text("Change the shape and density of the bottom navigation.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("Pill", "Flat", "Slim").forEachIndexed { index, label ->
                    val selected = FlarePreferences.navStyle.intValue == index
                    TextButton(onClick = {
                        FlarePreferences.navStyle.intValue = index
                        settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putInt("nav_style", index).apply()
                    }, modifier = Modifier.weight(1f)) { Text(label, color = if (selected) Violet else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 11.sp) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text("Playlist cards", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text("Choose the density and shape of YouTube Music playlist rows.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("List", "Cards", "Compact").forEachIndexed { index, label ->
                    val selected = FlarePreferences.playlistStyle.intValue == index
                    TextButton(onClick = {
                        FlarePreferences.playlistStyle.intValue = index
                        settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putInt("playlist_style", index).apply()
                    }, modifier = Modifier.weight(1f)) { Text(label, color = if (selected) Violet else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 11.sp) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text("Font size", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("Small" to .85f, "Default" to 1f, "Large" to 1.2f).forEach { (label, scale) ->
                    val selected = kotlin.math.abs(FlarePreferences.fontScale.floatValue - scale) < .01f
                    TextButton(onClick = {
                        FlarePreferences.fontScale.floatValue = scale
                        settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putFloat("font_scale", scale).apply()
                    }, modifier = Modifier.weight(1f)) { Text(label, color = if (selected) Violet else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 11.sp) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text("Corner radius", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("Round", "Medium", "Sharp").forEachIndexed { index, label ->
                    val selected = FlarePreferences.cornerStyle.intValue == index
                    TextButton(onClick = {
                        FlarePreferences.cornerStyle.intValue = index
                        settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putInt("corner_style", index).apply()
                    }, modifier = Modifier.weight(1f)) { Text(label, color = if (selected) Violet else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 11.sp) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Glass effects", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    Text("Use translucent surfaces for a softer glass-like UI", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Switch(checked = FlarePreferences.glassEffects.value, onCheckedChange = {
                    FlarePreferences.glassEffects.value = it
                    settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("glass_effects", it).apply()
                })
            }
        }
        Text("PLAYER & NAVIGATION", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = Modifier.padding(top = 20.dp, bottom = 9.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)).border(1.dp, Color.White.copy(alpha = .13f), RoundedCornerShape(20.dp)).padding(17.dp)) {
            Text("Progress bar style", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text("Change how playback progress looks in the full player.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp, bottom = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Classic", "Minimal", "Bold").forEachIndexed { index, label ->
                    val selected = FlarePreferences.progressStyle.intValue == index
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (selected) Violet.copy(alpha = .18f) else MaterialTheme.colorScheme.background)
                            .clickable {
                                FlarePreferences.progressStyle.intValue = index
                                settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putInt("progress_style", index).apply()
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (selected) Violet else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mini player", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    Text("Show the player card above the navigation bar", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Switch(checked = FlarePreferences.showMiniPlayer.value, onCheckedChange = {
                    FlarePreferences.showMiniPlayer.value = it
                    settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("show_mini_player", it).apply()
                })
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Navigation labels", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    Text("Show Home, Search, Library and Settings text", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Switch(checked = FlarePreferences.showNavLabels.value, onCheckedChange = {
                    FlarePreferences.showNavLabels.value = it
                    settingsContext.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("show_nav_labels", it).apply()
                })
            }
        }
        Text("PLAYER GESTURES", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = Modifier.padding(top = 20.dp, bottom = 9.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)).border(1.dp, Color.White.copy(alpha = .13f), RoundedCornerShape(20.dp)).padding(17.dp)) {
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
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)).border(1.dp, Color.White.copy(alpha = .13f), RoundedCornerShape(20.dp)).padding(17.dp)) {
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
        Text("UPDATES", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = Modifier.padding(top = 20.dp, bottom = 9.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)).border(1.dp, Color.White.copy(alpha = .13f), RoundedCornerShape(20.dp)).clickable(enabled = !checkingUpdates) {
            checkingUpdates = true
            updateScope.launch {
                try {
                    latestRelease = withContext(Dispatchers.IO) { FlareUpdater.latestRelease() }
                    updateMessage = null
                } catch (e: Exception) {
                    updateMessage = e.message ?: "Couldn't check for updates."
                } finally {
                    checkingUpdates = false
                }
            }
        }.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Check for updates", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text("Check GitHub for the latest FlareMusic release", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            }
            if (checkingUpdates) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
        }
        if (updateMessage != null) Text(updateMessage!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        latestRelease?.let { release ->
            AlertDialog(
                onDismissRequest = { latestRelease = null },
                title = { Text("FlareMusic ${release.tag}") },
                text = { Text(release.notes.take(3500)) },
                confirmButton = {
                    TextButton(onClick = {
                        settingsContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url)))
                        latestRelease = null
                    }) { Text("View release / download APK") }
                },
                dismissButton = { TextButton(onClick = { latestRelease = null }) { Text("Later") } }
            )
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
            // Keep the original high-resolution artwork intact.
            // Compose handles cropping/fitting at render time, so the bitmap itself is never
            // permanently cropped (important for YouTube's 16:9 thumbnails).
            decoded

        }
    }
    if (bitmap != null) Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "Album art", modifier = modifier, contentScale = if (fitArtwork) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) { Image(painterResource(R.drawable.ic_flare_logo), contentDescription = "Album art", modifier = Modifier.fillMaxSize().padding(5.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    }.background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface)))) {
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
            Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 8.dp).clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface))).padding(10.dp)) {
                Artwork(track.artwork, Modifier.fillMaxSize().clip(RoundedCornerShape(23.dp)))
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
            val progressStyle = FlarePreferences.progressStyle.intValue
            if (progressStyle == 1) {
                // Minimal: thin line, no visible thumb.
                Slider(
                    value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { if (duration > 0) onSeek((it * duration).toLong()) },
                    thumb = {},
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            thumbTrackGapSize = 0.dp,
                            drawStopIndicator = null,
                            colors = SliderDefaults.colors(
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(20.dp)
                )
            } else if (progressStyle == 2) {
                // Bold: thicker track with a prominent thumb.
                Slider(
                    value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { if (duration > 0) onSeek((it * duration).toLong()) },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth().height(34.dp)
                )
            } else {
                // Classic: normal Material slider.
                Slider(
                    value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { if (duration > 0) onSeek((it * duration).toLong()) },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
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
 val homeMode = FlarePreferences.homeLayout.intValue
 val radius = when (FlarePreferences.cornerStyle.intValue) { 1 -> 18.dp; 2 -> 8.dp; else -> 30.dp }
 val cardRadius = when (FlarePreferences.cornerStyle.intValue) { 1 -> 16.dp; 2 -> 8.dp; else -> 22.dp }
 val colors=MaterialTheme.colorScheme
 Column(Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal=22.dp)) {
  Row(Modifier.fillMaxWidth().padding(top=25.dp),verticalAlignment=Alignment.CenterVertically) {
   Column(Modifier.weight(1f)) {
    Text("GOOD EVENING",color=colors.onSurfaceVariant,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp)
    Text("Feel the\nfrequency.",color=colors.onBackground,fontSize=39.sp,lineHeight=42.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=(-1.8).sp,modifier=Modifier.padding(top=8.dp))
   }
   Box(Modifier.size(54.dp).clip(RoundedCornerShape(19.dp)).background(accent.copy(alpha=.13f)),contentAlignment=Alignment.Center) { Image(painterResource(R.drawable.ic_flare_logo),contentDescription="FlareMusic",modifier=Modifier.size(38.dp)) }
  }
  Spacer(Modifier.height(if (homeMode == 1) 14.dp else 25.dp))
  if (homeMode != 2) Box(Modifier.fillMaxWidth().height(if (homeMode == 1) 190.dp else 238.dp).clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(Color(0xFFFF7658),Color(0xFFB65C91),Color(0xFF6157B9)))).clickable{openLibrary()}) {
   Box(Modifier.align(Alignment.TopEnd).padding(18.dp).size(150.dp).clip(RoundedCornerShape(75.dp)).background(Color.White.copy(alpha=.09f)),contentAlignment=Alignment.Center) { Icon(Icons.Rounded.GraphicEq,null,tint=Color.White.copy(alpha=.9f),modifier=Modifier.size(78.dp)) }
   Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
    Text("YOUR MUSIC. YOUR MOMENT.",color=Color.White.copy(alpha=.82f),fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.7.sp)
    Text("Press play.\nDisappear.",color=Color.White,fontSize=31.sp,lineHeight=34.sp,fontWeight=FontWeight.ExtraBold,modifier=Modifier.padding(top=8.dp))
    Row(Modifier.padding(top=13.dp).clip(RoundedCornerShape(30.dp)).background(Color.White.copy(alpha=.18f)).padding(horizontal=14.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically) { Text("Open your music",color=Color.White,fontSize=12.sp,fontWeight=FontWeight.Bold); Spacer(Modifier.width(8.dp)); Icon(Icons.Rounded.ArrowOutward,null,tint=Color.White,modifier=Modifier.size(15.dp)) }
   }
  }
  Spacer(Modifier.height(27.dp))
  Row(verticalAlignment=Alignment.CenterVertically) {
   Column(Modifier.weight(1f)) { Text("Your space",color=colors.onBackground,fontSize=23.sp,fontWeight=FontWeight.Bold); Text(if(loading)"Scanning your device…" else "A library made for you",color=colors.onSurfaceVariant,fontSize=13.sp,modifier=Modifier.padding(top=3.dp)) }
   TextButton(onClick=openLibrary){Text("View all ↗",color=accent,fontWeight=FontWeight.Bold)}
  }
  Spacer(Modifier.height(12.dp))
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
   Column(Modifier.weight(1f).clip(RoundedCornerShape(cardRadius)).background(colors.surfaceVariant).clickable{openLibrary()}.padding(17.dp)) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha=.15f)),contentAlignment=Alignment.Center){Icon(Icons.Rounded.MusicNote,null,tint=accent)}
    Text(count.toString(),color=colors.onSurface,fontSize=27.sp,fontWeight=FontWeight.ExtraBold,modifier=Modifier.padding(top=16.dp))
    Text("Songs on device",color=colors.onSurfaceVariant,fontSize=12.sp)
   }
   Column(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(colors.surfaceVariant.copy(alpha = .42f)).border(1.dp, Color.White.copy(alpha = .13f), RoundedCornerShape(22.dp)).clickable{openLibrary()}.padding(17.dp)) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF9D8AF3).copy(alpha=.16f)),contentAlignment=Alignment.Center){Icon(Icons.Rounded.Headphones,null,tint=Color(0xFF9D8AF3))}
    Text(if(loading)"…" else "Ready",color=colors.onSurface,fontSize=27.sp,fontWeight=FontWeight.ExtraBold,modifier=Modifier.padding(top=16.dp))
    Text("For your next replay",color=colors.onSurfaceVariant,fontSize=12.sp)
   }
  }
  if(error.isNotBlank()) Text(error,color=colors.error,fontSize=12.sp,modifier=Modifier.padding(top=14.dp))
  Spacer(Modifier.height(22.dp))
  Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(cardRadius)).background(colors.surface).clickable{openLibrary()}.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
   Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha=.13f)),contentAlignment=Alignment.Center){Icon(Icons.Rounded.Explore,null,tint=accent,modifier=Modifier.size(25.dp))}
   Column(Modifier.weight(1f).padding(start=13.dp)){Text("Explore your collection",color=colors.onSurface,fontWeight=FontWeight.Bold,fontSize=14.sp);Text("Find a track for right now",color=colors.onSurfaceVariant,fontSize=12.sp,modifier=Modifier.padding(top=3.dp))}
   Icon(Icons.Rounded.ChevronRight,null,tint=colors.onSurfaceVariant)
  }
  Spacer(Modifier.height(18.dp))
 }
}
@Composable private fun SearchScreen(query: String, onQuery: (String) -> Unit, results: List<Track>, play: (Track) -> Unit, online: List<OnlineTrack>, searching: Boolean, searchOnline: (String) -> Unit, playOnline: (OnlineTrack) -> Unit, addOnline: (OnlineTrack) -> Unit, error: String) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 18.dp)) {
        Text("Discover", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 18.dp))
        Text("Find something for the moment.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
        OutlinedTextField(value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Track, artist or album") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(18.dp), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Violet, unfocusedBorderColor = Color.White.copy(alpha = .22f), focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = .34f), unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = .28f), focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, focusedLeadingIconColor = Violet))
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
                Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { addOnline(result) }) { Icon(Icons.Rounded.PlaylistAdd, "Add to playlist", tint = Mint) }; Icon(Icons.Rounded.PlayCircleFilled, null, tint = Violet, modifier = Modifier.size(30.dp)) }
            } }
        }
    }
}

@Composable private fun LibraryScreen(tracks: List<Track>, loading: Boolean, favouriteTracks: List<Track>, youtubePlaylists: List<YouTubePlaylist>, googleStatus: String, playlistLoading: Boolean, playlistError: String, selectedPlaylist: YouTubePlaylist?, selectedPlaylistTracks: List<YouTubePlaylistTrack>, selectedPlaylistLoading: Boolean, selectedPlaylistError: String, openPlaylist: (YouTubePlaylist) -> Unit, closePlaylist: () -> Unit, playPlaylistTrack: (YouTubePlaylistTrack) -> Unit, play: (Track) -> Unit, search: () -> Unit, refresh: () -> Unit, refreshPlaylists: () -> Unit, connectYouTube: () -> Unit) {
    val libraryContext = LocalContext.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Your library", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = MaterialTheme.colorScheme.onSurface) }
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
            LazyColumn(contentPadding = PaddingValues(bottom = 150.dp)) {
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
            LazyColumn(
                // Use the remaining library viewport instead of capping the playlist
                // section at 360dp, which leaves a large empty gap below short lists.
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.SpaceEvenly,
                contentPadding = PaddingValues(top = 8.dp, bottom = 18.dp)
            ) {
                items(youtubePlaylists, key = { it.id }) { playlist ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(if (FlarePreferences.playlistStyle.intValue == 1) 20.dp else 14.dp))
                            .background(if (FlarePreferences.playlistStyle.intValue == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .38f) else Color.Transparent)
                            .border(if (FlarePreferences.playlistStyle.intValue == 1) 1.dp else 0.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(20.dp))
                            .clickable { openPlaylist(playlist) }
                            .padding(horizontal = if (FlarePreferences.playlistStyle.intValue == 2) 2.dp else 8.dp, vertical = if (FlarePreferences.playlistStyle.intValue == 2) 4.dp else 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Artwork(playlist.thumbnail, Modifier.size(if (FlarePreferences.playlistStyle.intValue == 2) 58.dp else 72.dp).clip(RoundedCornerShape(14.dp)))
                        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                            Text(playlist.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (playlist.itemCount > 0) "${playlist.itemCount} tracks" else playlist.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = "Open playlist", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Violet, trackColor = MaterialTheme.colorScheme.outlineVariant)
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




