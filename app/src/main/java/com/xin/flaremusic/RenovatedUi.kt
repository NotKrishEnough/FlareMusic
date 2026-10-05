package com.xin.flaremusic

import android.graphics.BitmapFactory
import android.net.Uri
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
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
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
        Image(bitmap!!.asImageBitmap(), "Artwork", modifier, contentScale = ContentScale.Crop)
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
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 150.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("FLAREMUSIC", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.3.sp)
                    Text("Made for the way\\nyou listen.", fontSize = 37.sp, lineHeight = 39.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.7).sp, modifier = Modifier.padding(top = 7.dp))
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
            RenovationCard(Modifier.fillMaxWidth().height(222.dp).clickable { openLibrary() }, accent) {
                Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(accent.copy(alpha = .30f), Color.Transparent), radius = 520f)))
                Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clip(RoundedCornerShape(50.dp)).background(Color.White.copy(alpha = .10f)).padding(horizontal = 11.dp, vertical = 6.dp)) {
                            Text("YOUR MUSIC", fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Rounded.ArrowOutward, null)
                    }
                    Column {
                        Text("Press play.\nDisappear.", fontSize = 31.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold)
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
                        Text("Songs", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 21.dp))
                        Text(count.toString() + " tracks", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                RenovationCard(Modifier.weight(1f).clickable { openLibrary() }, accent) {
                    Column(Modifier.padding(17.dp)) {
                        Icon(Icons.Rounded.Favorite, null, tint = accent)
                        Text("Favourites", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 21.dp))
                        Text("Saved for later", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        return
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

@Composable
private fun RenovationTrackRow(title: String, artist: String, artwork: String?, duration: String?, onClick: () -> Unit) {
    RenovationCard(Modifier.fillMaxWidth().clickable { onClick() }, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RenovationArtwork(artwork, Modifier.size(53.dp).clip(RoundedCornerShape(14.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(artist, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (!duration.isNullOrBlank()) Text(duration, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(Icons.Rounded.PlayCircleFilled, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(31.dp).padding(end = 3.dp))
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
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("flare_settings", android.content.Context.MODE_PRIVATE)
    val darkMode = FlarePreferences.darkMode.value
    val glassEffects = FlarePreferences.glassEffects.value
    val animations = FlarePreferences.animations.value
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 150.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { RenovationHeader("Settings", "A cleaner FlareMusic, your way.") }
        item { RenovationSection("APPEARANCE") }
        item { RenovationSetting("Dark appearance", "Cinematic dark surfaces", Icons.Rounded.DarkMode, darkMode) {
            FlarePreferences.darkMode.value = it
            prefs.edit().putBoolean("dark_mode", it).apply()
        } }
        item { RenovationSetting("Glass surfaces", "Frosted navigation, cards and player", Icons.Rounded.BlurOn, glassEffects) {
            FlarePreferences.glassEffects.value = it
            prefs.edit().putBoolean("glass_effects", it).apply()
        } }
        item { RenovationSetting("Animations", "Smooth transitions and motion", Icons.Rounded.Animation, animations) {
            FlarePreferences.animations.value = it
            prefs.edit().putBoolean("animations", it).apply()
        } }
        item { RenovationSection("AMOLED") }
        item { RenovationSetting("Pure black", "Use true black backgrounds", Icons.Rounded.Contrast, amoled, onAmoledChange) }
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
        item { RenovationSection("ACCENT") }
        item {
            RenovationCard(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.primary) {
                LazyRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(1) { index ->
                        val selected = 0 == index
                        Box(Modifier.size(if (selected) 42.dp else 35.dp).clip(RoundedCornerShape(50.dp)).background(MaterialTheme.colorScheme.primary).border(if (selected) 3.dp else 0.dp, Color.White, RoundedCornerShape(50.dp)).clickable {
                            prefs.edit().putInt("accent_index", index).apply()
                        })
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
    var dragY by remember { mutableFloatStateOf(0f) }
    var showQueue by remember { mutableStateOf(false) }
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        Modifier.fillMaxSize().pointerInput(swipeToMinimize) {
            detectDragGestures(
                onDragEnd = { if (swipeToMinimize && dragY > 120f) onClose(); dragY = 0f },
                onDragCancel = { dragY = 0f }
            ) { _, amount -> if (amount.y > 0) dragY += amount.y }
        }.background(
            Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background))
        )
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Minimize", modifier = Modifier.size(31.dp)) }
                Spacer(Modifier.weight(1f))
                Text("NOW PLAYING", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.1.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showQueue = true }) { Icon(Icons.Rounded.QueueMusic, "Queue") }
            }
            Spacer(Modifier.weight(.35f))
            Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 5.dp).clip(RoundedCornerShape(32.dp)).border(1.dp, RenovationGlassBorder, RoundedCornerShape(32.dp)).padding(8.dp)) {
                RenovationArtwork(track.artwork, Modifier.fillMaxSize().clip(RoundedCornerShape(25.dp)))
            }
            Spacer(Modifier.weight(.34f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                IconButton(onClick = onToggleFavourite) { Icon(if (isFavourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favourite", tint = MaterialTheme.colorScheme.primary) }
            }
            Slider(value = progress, onValueChange = { if (duration > 0) onSeek((it * duration).toLong()) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(duration), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(top = 9.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous", modifier = Modifier.size(31.dp)) }
                FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(74.dp), shape = RoundedCornerShape(25.dp)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipNext, "Next", modifier = Modifier.size(31.dp)) }
            }
            Spacer(Modifier.height(12.dp))
            Text("Swipe down to minimize", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.weight(.18f))
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

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return (total / 60).toString() + ":" + (total % 60).toString().padStart(2, '0')
}
