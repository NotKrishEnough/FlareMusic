package com.xin.flaremusic

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

private val RenovationGlassBorder = Color.White.copy(alpha = .13f)

@Composable
private fun RenovationCard(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    shape: RoundedCornerShape = RoundedCornerShape(
        when (FlarePreferences.cornerStyle.intValue.coerceIn(0, 2)) {
            0 -> 24.dp
            1 -> 16.dp
            else -> 6.dp
        }
    ),
    content: @Composable BoxScope.() -> Unit
) {
    val surface = MaterialTheme.colorScheme.surface
    Box(
        modifier.clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = .09f),
                        surface.copy(alpha = .68f),
                        accent.copy(alpha = .07f)
                    )
                )
            )
            .border(1.dp, RenovationGlassBorder, shape),
        content = content
    )
}

@Composable
private fun RenovationArtwork(source: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(source) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(source) {
        bitmap = withContext(Dispatchers.IO) {
            if (source.isNullOrBlank()) return@withContext null
            val candidates = buildList {
                add(source)
                if (source.contains("ytimg.com/vi/")) {
                    add(source.replace(Regex("/(default|mqdefault|hqdefault|sddefault|maxresdefault)\\.jpg"), "/maxresdefault.jpg"))
                    add(source.replace(Regex("/(default|mqdefault|hqdefault|sddefault|maxresdefault)\\.jpg"), "/sddefault.jpg"))
                }
            }.distinct()
            candidates.firstNotNullOfOrNull { url ->
                runCatching {
                    if (url.startsWith("content://") || url.startsWith("file://")) {
                        context.contentResolver.openInputStream(Uri.parse(url))?.use { BitmapFactory.decodeStream(it) }
                    } else {
                        URL(url).openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }
                            .getInputStream().use { BitmapFactory.decodeStream(it) }
                    }
                }.getOrNull()
            }
        }
    }
    if (bitmap != null) {
        val scale = if (FlarePreferences.artworkStyle.intValue == 1) ContentScale.Fit else ContentScale.Crop
        if (FlarePreferences.artworkStyle.intValue == 2) {
            Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))) {
                Image(bitmap!!.asImageBitmap(), "Artwork", Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Crop)
            }
        } else {
            Image(bitmap!!.asImageBitmap(), "Artwork", modifier, contentScale = scale)
        }
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_flare_logo), "FlareMusic", Modifier.fillMaxSize().padding(10.dp))
        }
    }
}

@Composable
private fun RenovationHeader(title: String, subtitle: String) {
    Text(title, fontSize = 35.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp)
    Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
}

@Composable
fun RenovatedHomeScreen(count: Int, loading: Boolean, error: String, accent: Color, openLibrary: () -> Unit) {
    // Establish an explicit readable foreground so bare Text/Icon composables do not
    // inherit the platform's black LocalContentColor on dark glass surfaces.
    val homeText = if (FlarePreferences.darkMode.value) Color.White else MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(LocalContentColor provides homeText) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 150.dp),
        verticalArrangement = Arrangement.spacedBy(if (FlarePreferences.homeLayout.intValue == 1) 10.dp else 17.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("FLAREMUSIC", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.3.sp)
                    Text("Made for the way\nyou listen.", color = homeText, fontSize = 37.sp, lineHeight = 39.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.7).sp, modifier = Modifier.padding(top = 7.dp))
                    Text(
                        if (loading) "Scanning your library…" else if (error.isNotBlank()) error else count.toString() + " songs ready",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 9.dp)
                    )
                }
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(19.dp)).background(accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_flare_logo), "FlareMusic", Modifier.size(39.dp))
                }
            }
        }
        item {
            RenovationCard(Modifier.fillMaxWidth().height(if (FlarePreferences.homeLayout.intValue == 1) 160.dp else if (FlarePreferences.homeLayout.intValue == 2) 190.dp else 222.dp).clickable { openLibrary() }, accent) {
                Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(accent.copy(alpha = .30f), Color.Transparent), radius = 520f)))
                Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clip(RoundedCornerShape(50.dp)).background(Color.White.copy(alpha = .10f)).padding(horizontal = 11.dp, vertical = 6.dp)) {
                            Text("YOUR MUSIC", color = homeText, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Rounded.ArrowOutward, null, tint = homeText)
                    }
                    Column {
                        Text("Press play.\nDisappear.", color = homeText, fontSize = 31.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Everything you listen to, in one calm space.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        item {
            Text("QUICK ACCESS", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                RenovationCard(Modifier.weight(1f).clickable { openLibrary() }, accent) {
                    Column(Modifier.padding(17.dp)) {
                        Icon(Icons.Rounded.MusicNote, null, tint = accent)
                        Text("Songs", color = homeText, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 21.dp))
                        Text(count.toString() + " tracks", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                RenovationCard(Modifier.weight(1f).clickable { openLibrary() }, accent) {
                    Column(Modifier.padding(17.dp)) {
                        Icon(Icons.Rounded.Favorite, null, tint = accent)
                        Text("Favourites", color = homeText, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 21.dp))
                        Text("Saved for later", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    }
}

@Composable
fun RenovatedSearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    localResults: List<Track>,
    onLocalPlay: (Track) -> Unit,
    onlineResults: List<OnlineTrack>,
    searching: Boolean,
    onSearch: () -> Unit,
    onOnlinePlay: (OnlineTrack) -> Unit,
    onAdd: (OnlineTrack) -> Unit,
    error: String
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        RenovationHeader("Search", "YouTube + YouTube Music")
        RenovationSearchField(query, onQuery, { onSearch() })
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        LazyColumn(contentPadding = PaddingValues(top = 14.dp, bottom = 150.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (localResults.isNotEmpty()) {
                item { Text("ON DEVICE", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 5.dp, bottom = 2.dp)) }
                items(localResults.take(8), key = { "local-" + it.id }) { track ->
                    RenovationTrackRow(track.title, track.artist, track.artwork, null) { onLocalPlay(track) }
                }
            }
            if (onlineResults.isNotEmpty()) {
                item { Text("ONLINE", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)) }
                items(onlineResults, key = { it.videoId }) { item ->
                    RenovationTrackRow(item.title, item.author, item.thumbnail, item.duration) { onOnlinePlay(item) }
                }
            } else if (!searching && query.isBlank()) {
                item { RenovationEmpty("Search millions of tracks", "Type a song, artist or album above.") }
            }
        }
    }
    }
}

@Composable
private fun RenovationSearchField(query: String, onQuery: (String) -> Unit, onSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 17.dp).height(57.dp)
            .clip(RoundedCornerShape(29.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .72f))
            .border(1.dp, RenovationGlassBorder, RoundedCornerShape(29.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 17.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
            decorationBox = { inner ->
                if (query.isBlank()) Text("Search songs, artists, playlists…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                inner()
            }
        )
        IconButton(onClick = onSearch) { Icon(Icons.Rounded.ArrowForward, "Search", tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
fun RenovatedLibraryScreen(
    tracks: List<Track>,
    loading: Boolean,
    favourites: List<Track>,
    playlists: List<YouTubePlaylist>,
    status: String,
    playlistLoading: Boolean,
    playlistError: String,
    selectedPlaylist: YouTubePlaylist?,
    selectedPlaylistTracks: List<YouTubePlaylistTrack>,
    selectedPlaylistLoading: Boolean,
    selectedPlaylistError: String,
    openPlaylist: (YouTubePlaylist) -> Unit,
    closePlaylist: () -> Unit,
    playPlaylistItem: (YouTubePlaylistTrack) -> Unit,
    playLocal: (Track) -> Unit,
    openSearch: () -> Unit,
    rescan: () -> Unit,
    sync: () -> Unit,
    connect: () -> Unit
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    if (selectedPlaylist != null) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 150.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = closePlaylist) { Icon(Icons.Rounded.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(selectedPlaylist.title, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("YouTube Music playlist", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                RenovationCard(Modifier.fillMaxWidth().height(205.dp), MaterialTheme.colorScheme.primary) {
                    RenovationArtwork(selectedPlaylist.thumbnail, Modifier.fillMaxSize())
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                        Text(selectedPlaylist.title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                        Text(if (selectedPlaylistTracks.isEmpty()) "Loading tracks…" else selectedPlaylistTracks.size.toString() + " tracks", color = Color.White.copy(alpha = .78f), fontSize = 12.sp)
                    }
                }
            }
            if (selectedPlaylistError.isNotBlank()) item { Text(selectedPlaylistError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            if (selectedPlaylistLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(selectedPlaylistTracks, key = { it.videoId }) { item ->
                RenovationTrackRow(item.title, item.artist, item.thumbnail, null) { playPlaylistItem(item) }
            }
        }
        return@CompositionLocalProvider
    }

    var favouritesMode by remember { mutableStateOf(false) }
    val shown = if (favouritesMode) favourites else tracks
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 150.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            RenovationHeader("Library", tracks.size.toString() + " songs · " + playlists.size.toString() + " playlists")
            Row(Modifier.padding(top = 17.dp).fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)).padding(4.dp)) {
                listOf("Songs", "Favourites").forEachIndexed { index, label ->
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).background(if ((favouritesMode && index == 1) || (!favouritesMode && index == 0)) MaterialTheme.colorScheme.surface else Color.Transparent).clickable { favouritesMode = index == 1 }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (status.startsWith("Connected") && playlists.isNotEmpty()) {
            item { Text("YOUR PLAYLISTS", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(playlists.take(12), key = { it.id }) { playlist ->
                        Column(Modifier.width(140.dp).clickable { openPlaylist(playlist) }) {
                            RenovationArtwork(playlist.thumbnail, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(19.dp)))
                            Text(playlist.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                            Text(playlist.itemCount.toString() + " tracks", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else if (!status.startsWith("Connected")) {
            item {
                RenovationCard(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.primary) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("Connect YouTube Music", fontWeight = FontWeight.Bold)
                            Text("Sync your playlists into the library.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = connect) { Text("Connect") }
                    }
                }
            }
        }
        if (playlistError.isNotBlank()) item { Text(playlistError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (playlistLoading || loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (shown.isEmpty()) {
            item { RenovationEmpty(if (favouritesMode) "No favourites yet" else "Your library is empty", if (favouritesMode) "Tap the heart to save songs." else "Add music to your device and rescan.") }
        } else {
            items(shown, key = { it.id }) { track ->
                RenovationTrackRow(track.title, track.artist, track.artwork, null) { playLocal(track) }
            }
        }
    }
    }
}

@Composable
private fun RenovationTrackRow(title: String, artist: String, artwork: String?, duration: String?, onClick: () -> Unit) {
    val compact = FlarePreferences.playlistStyle.intValue == 2
    val cards = FlarePreferences.playlistStyle.intValue == 1
    val rowShape = RoundedCornerShape(if (cards) 24.dp else 18.dp)
    RenovationCard(Modifier.fillMaxWidth().clickable { onClick() }, MaterialTheme.colorScheme.primary, rowShape) {
        Row(Modifier.padding(if (compact) 5.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RenovationArtwork(artwork, Modifier.size(if (compact) 43.dp else if (cards) 64.dp else 53.dp).clip(RoundedCornerShape(if (cards) 18.dp else 14.dp)))
            Column(Modifier.weight(1f).padding(horizontal = if (compact) 8.dp else 11.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(artist, fontSize = if (compact) 11.sp else 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (!duration.isNullOrBlank()) Text(duration, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(Icons.Rounded.PlayCircleFilled, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (compact) 26.dp else if (cards) 34.dp else 31.dp).padding(end = 3.dp))
        }
    }
}

@Composable
private fun RenovationEmpty(title: String, subtitle: String) {
    RenovationCard(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.primary) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun RenovatedSettingsScreen(
    amoled: Boolean,
    onAmoledChange: (Boolean) -> Unit,
    googleStatus: String,
    playlists: List<YouTubePlaylist>,
    loading: Boolean,
    error: String,
    onConnect: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        val context = LocalContext.current
        val prefs = context.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE)
        val darkMode = FlarePreferences.darkMode.value
        val glassEffects = FlarePreferences.glassEffects.value
        val animations = FlarePreferences.animations.value

        fun save(key: String, value: Any) {
            val e = prefs.edit()
            when (value) {
                is Boolean -> e.putBoolean(key, value)
                is Int -> e.putInt(key, value)
                is Float -> e.putFloat(key, value)
            }
            e.apply()
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { RenovationHeader("Settings", "A cleaner FlareMusic, your way.") }

            item { RenovationSection("APPEARANCE") }
            item { RenovationSetting("Dark appearance", "Cinematic dark surfaces", Icons.Rounded.DarkMode, darkMode) {
                FlarePreferences.darkMode.value = it
                save("dark_mode", it)
            } }
            item { RenovationSetting("Glass surfaces", "Frosted navigation, cards and player", Icons.Rounded.BlurCircular, glassEffects) {
                FlarePreferences.glassEffects.value = it
                save("glass_effects", it)
            } }
            item { RenovationSetting("Animations", "Smooth transitions and motion", Icons.Rounded.AutoAwesome, animations) {
                FlarePreferences.animations.value = it
                save("animations", it)
            } }
            item { RenovationSetting("Dynamic colours", "Use your system wallpaper palette", Icons.Rounded.Palette, FlarePreferences.dynamicColors.value) {
                FlarePreferences.dynamicColors.value = it
                save("dynamic_colors", it)
            } }

            item { RenovationSection("AMOLED") }
            item { RenovationSetting("Pure black", "Use true black backgrounds", Icons.Rounded.BrightnessHigh, amoled, onAmoledChange) }

            item { RenovationSection("HOME SCREEN") }
            item {
                RenovationChoiceCard(
                    "Home layout",
                    listOf("Showcase", "Compact", "Stats first"),
                    FlarePreferences.homeLayout.intValue
                ) { v ->
                    FlarePreferences.homeLayout.intValue = v
                    save("home_layout", v)
                }
            }

            item { RenovationSection("NAVIGATION") }
            item {
                RenovationChoiceCard(
                    "Navigation style",
                    listOf("Pill", "Flat", "Floating"),
                    FlarePreferences.navStyle.intValue
                ) { v ->
                    FlarePreferences.navStyle.intValue = v
                    save("nav_style", v)
                }
            }
            item { RenovationSetting("Navigation labels", "Show Home, Search, Library and Settings labels", Icons.Rounded.Label, FlarePreferences.showNavLabels.value) {
                FlarePreferences.showNavLabels.value = it
                save("show_nav_labels", it)
            } }

            item { RenovationSection("PLAYER") }
            item { RenovationSetting("Mini player", "Keep playback controls above navigation", Icons.Rounded.MusicNote, FlarePreferences.showMiniPlayer.value) {
                FlarePreferences.showMiniPlayer.value = it
                save("show_mini_player", it)
            } }
            item {
                RenovationChoiceCard(
                    "Artwork style",
                    listOf("Crop", "Fit", "Soft glass"),
                    FlarePreferences.artworkStyle.intValue
                ) { v ->
                    FlarePreferences.artworkStyle.intValue = v
                    save("artwork_style", v)
                }
            }
            item {
                RenovationChoiceCard(
                    "Full player style",
                    listOf("Classic", "Immersive", "Vinyl", "Minimal"),
                    FlarePreferences.playerStyle.intValue
                ) { v ->
                    FlarePreferences.playerStyle.intValue = v
                    save("player_style", v)
                }
            }
            item {
                RenovationChoiceCard(
                    "Player background",
                    listOf("Theme", "Artwork blur", "Gradient", "Dark glass"),
                    FlarePreferences.playerBackground.intValue
                ) { v ->
                    FlarePreferences.playerBackground.intValue = v
                    save("player_background", v)
                }
            }
            item {
                RenovationChoiceCard(
                    "Player animation",
                    listOf("Subtle", "Morph", "Pulse", "Ambient"),
                    FlarePreferences.playerAnimation.intValue
                ) { v ->
                    FlarePreferences.playerAnimation.intValue = v
                    save("player_animation", v)
                }
            }
            item {
                RenovationChoiceCard(
                    "Progress bar",
                    listOf("Classic", "Thin", "Glow"),
                    FlarePreferences.progressStyle.intValue
                ) { v ->
                    FlarePreferences.progressStyle.intValue = v
                    save("progress_style", v)
                }
            }
            item { RenovationSetting("Swipe to minimize", "Swipe the full player down to close it", Icons.Rounded.SwipeDown, FlarePreferences.swipeToMinimize.value) {
                FlarePreferences.swipeToMinimize.value = it
                save("gesture_minimize", it)
            } }
            item { RenovationSetting("Swipe to change tracks", "Swipe the player artwork to change songs", Icons.Rounded.Swipe, FlarePreferences.swipeToChangeTracks.value) {
                FlarePreferences.swipeToChangeTracks.value = it
                save("gesture_tracks", it)
            } }

            item { RenovationSection("LIBRARY") }
            item {
                RenovationChoiceCard(
                    "Playlist style",
                    listOf("List", "Cards", "Compact"),
                    FlarePreferences.playlistStyle.intValue
                ) { v ->
                    FlarePreferences.playlistStyle.intValue = v
                    save("playlist_style", v)
                }
            }
            item { RenovationSetting("Compact mode", "Tighter spacing and smaller controls", Icons.Rounded.ViewCompact, FlarePreferences.compact.value) {
                FlarePreferences.compact.value = it
                save("compact", it)
            } }

            item { RenovationSection("TYPOGRAPHY & SHAPE") }
            item {
                RenovationCard(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.primary) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.FormatSize, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text("Text size", fontWeight = FontWeight.SemiBold)
                                Text(String.format("%.0f%%", FlarePreferences.fontScale.floatValue * 100f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Slider(
                            value = FlarePreferences.fontScale.floatValue,
                            onValueChange = {
                                FlarePreferences.fontScale.floatValue = it
                                save("font_scale", it)
                            },
                            valueRange = .85f..1.2f,
                            steps = 6
                        )
                    }
                }
            }
            item {
                RenovationChoiceCard(
                    "Corner radius",
                    listOf("Rounded", "Medium", "Sharp"),
                    FlarePreferences.cornerStyle.intValue
                ) { v ->
                    FlarePreferences.cornerStyle.intValue = v
                    save("corner_style", v)
                }
            }

            item { RenovationSection("ACCENT") }
            item {
                RenovationCard(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.primary) {
                    LazyRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(FlarePreferences.accents.size) { index ->
                            val selected = FlarePreferences.accentIndex.intValue == index
                            Box(
                                Modifier
                                    .size(if (selected) 42.dp else 36.dp)
                                    .clip(RoundedCornerShape(50.dp))
                                    .background(FlarePreferences.accents[index])
                                    .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .9f), RoundedCornerShape(50.dp))
                                    .clickable {
                                        FlarePreferences.accentIndex.intValue = index
                                        save("accent_index", index)
                                    }
                            )
                        }
                    }
                }
            }

            item { RenovationSection("YOUTUBE MUSIC") }
            item {
                RenovationCard(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.primary) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(if (googleStatus.startsWith("Connected")) "YouTube Music connected" else "Not connected", fontWeight = FontWeight.Bold)
                                Text(playlists.size.toString() + " playlists synced", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(Modifier.padding(top = 13.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onConnect, modifier = Modifier.weight(1f)) { Text(if (googleStatus.startsWith("Connected")) "Reconnect" else "Connect") }
                            OutlinedButton(onClick = onSync, modifier = Modifier.weight(1f), enabled = !loading) { Text("Sync") }
                        }
                        if (googleStatus.startsWith("Connected")) TextButton(onClick = onDisconnect, modifier = Modifier.align(Alignment.End)) { Text("Disconnect") }
                        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RenovationChoiceCard(title: String, options: List<String>, selected: Int, onSelected: (Int) -> Unit) {
    RenovationCard(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.primary) {
        Column(Modifier.padding(15.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            // Wrap choices instead of clipping them off-screen on narrow devices.
            Column(
                Modifier.padding(top = 11.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.chunked(2).forEach { rowOptions ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowOptions.forEach { label ->
                            val index = options.indexOf(label)
                            FilterChip(
                                selected = selected == index,
                                onClick = { onSelected(index) },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            )
                        }
                        if (rowOptions.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RenovationSection(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 11.dp, bottom = 2.dp))
}

@Composable
private fun RenovationSetting(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, checked: Boolean, onChecked: (Boolean) -> Unit) {
    RenovationCard(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.primary) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(43.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChecked)
        }
    }
}

@Composable
fun RenovatedFullPlayer(
    track: Track,
    playing: Boolean,
    position: Long,
    duration: Long,
    isFavourite: Boolean,
    queue: List<Track>,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleFavourite: () -> Unit,
    onPlayQueueItem: (Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    swipeToMinimize: Boolean,
    swipeToChangeTracks: Boolean
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        var dragY by remember { mutableFloatStateOf(0f) }
        var showQueue by remember { mutableStateOf(false) }
        val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val style = FlarePreferences.playerStyle.intValue.coerceIn(0, 3)
        val background = FlarePreferences.playerBackground.intValue.coerceIn(0, 3)
        val animationStyle = FlarePreferences.playerAnimation.intValue.coerceIn(0, 3)
        val animationsEnabled = FlarePreferences.animations.value
        val infinite = rememberInfiniteTransition(label = "playerAnimations")
        val ambient = if (animationsEnabled && animationStyle != 0) infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(if (animationStyle == 3) 4200 else 2600), RepeatMode.Reverse), label = "ambient").value else 0f
        val pulse = if (animationsEnabled && animationStyle == 2) infinite.animateFloat(.97f, 1.03f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "pulse").value else 1f

        Box(
            Modifier.fillMaxSize()
                .pointerInput(swipeToMinimize) {
                    detectDragGestures(
                        onDragEnd = { if (swipeToMinimize && dragY > 120f) onClose(); dragY = 0f },
                        onDragCancel = { dragY = 0f }
                    ) { _, amount -> if (amount.y > 0) dragY += amount.y }
                }
        ) {
            // Player backdrops: artwork tint, dynamic gradient, or dark glass. Keep the backdrop API-only for broad Compose compatibility.
            when (background) {
                1 -> {
                    RenovationArtwork(track.artwork, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .48f)))
                }
                2 -> {
                    RenovationArtwork(track.artwork, Modifier.fillMaxSize())
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = .40f),
                                    MaterialTheme.colorScheme.background.copy(alpha = .88f),
                                    MaterialTheme.colorScheme.background
                                )
                            )
                        )
                    )
                }
                3 -> {
                    RenovationArtwork(track.artwork, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .86f)))
                    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .18f), Color.Transparent))))
                }
                else -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            }

            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Minimize", modifier = Modifier.size(31.dp)) }
                    Spacer(Modifier.weight(1f))
                    Text("NOW PLAYING", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.1.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showQueue = true }) { Icon(Icons.Rounded.QueueMusic, "Queue") }
                }

                if (style == 1) {
                    Spacer(Modifier.height(12.dp))
                    RenovationArtwork(track.artwork, Modifier.fillMaxWidth(.90f).aspectRatio(1f).graphicsLayer { val s = if (animationStyle == 1 && animationsEnabled) 1f + ambient * .035f else pulse; scaleX = s; scaleY = s; rotationZ = if (animationStyle == 1 && animationsEnabled) (ambient - .5f) * 1.2f else 0f }.clip(RoundedCornerShape(34.dp)))
                    Spacer(Modifier.height(18.dp))
                } else if (style == 2) {
                    Spacer(Modifier.height(18.dp))
                    RenovationArtwork(track.artwork, Modifier.fillMaxWidth(.72f).aspectRatio(1f).graphicsLayer { scaleX = pulse; scaleY = pulse }.clip(androidx.compose.foundation.shape.CircleShape))
                    Spacer(Modifier.height(18.dp))
                } else if (style == 3) {
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RenovationArtwork(track.artwork, Modifier.size(132.dp).clip(RoundedCornerShape(28.dp)))
                        Column(Modifier.weight(1f).padding(start = 18.dp)) {
                            Text(track.title, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                } else {
                    Spacer(Modifier.height(20.dp))
                    Box(
                        Modifier.fillMaxWidth().widthIn(max = 360.dp).aspectRatio(1f)
                            .padding(horizontal = 8.dp).clip(RoundedCornerShape(30.dp))
                            .border(1.dp, RenovationGlassBorder, RoundedCornerShape(30.dp)).padding(7.dp)
                    ) { RenovationArtwork(track.artwork, Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))) }
                    Spacer(Modifier.height(22.dp))
                }

                if (style != 3) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(track.title, fontSize = if (style == 2) 21.sp else 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                        IconButton(onClick = onToggleFavourite) { Icon(if (isFavourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favourite", tint = MaterialTheme.colorScheme.primary) }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = onToggleFavourite) { Icon(if (isFavourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favourite", tint = MaterialTheme.colorScheme.primary) }
                    }
                }

                if (FlarePreferences.progressStyle.intValue == 1) {
                    Slider(value = progress, onValueChange = { if (duration > 0) onSeek((it * duration).toLong()) }, modifier = Modifier.fillMaxWidth().height(24.dp).padding(top = 5.dp))
                } else {
                    Slider(value = progress, onValueChange = { if (duration > 0) onSeek((it * duration).toLong()) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(position), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatTime(duration), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().padding(top = 13.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous", modifier = Modifier.size(31.dp)) }
                    FilledIconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.size(if (style == 2) 74.dp else 68.dp),
                        shape = if (style == 2) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(23.dp)
                    ) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", modifier = Modifier.size(34.dp)) }
                    IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipNext, "Next", modifier = Modifier.size(31.dp)) }
                }
                Spacer(Modifier.height(8.dp))
                Text("Swipe down to minimize", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline.copy(alpha = .72f))
            }

            if (showQueue) {
                AlertDialog(onDismissRequest = { showQueue = false }, title = { Text("Queue") }, text = {
                    if (queue.isEmpty()) Text("The queue is empty.") else LazyColumn(Modifier.heightIn(max = 430.dp)) {
                        items(queue.size) { index ->
                            val item = queue[index]
                            Row(Modifier.fillMaxWidth().clickable { onPlayQueueItem(index); showQueue = false }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                RenovationArtwork(item.artwork, Modifier.size(43.dp).clip(RoundedCornerShape(10.dp)))
                                Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.artist, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { onRemoveQueueItem(index) }) { Icon(Icons.Rounded.Close, "Remove") }
                            }
                        }
                    }
                }, confirmButton = { TextButton(onClick = { showQueue = false }) { Text("Done") } }, dismissButton = { TextButton(onClick = onClearQueue) { Text("Clear") } })
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return (total / 60).toString() + ":" + (total % 60).toString().padStart(2, '0')
}
