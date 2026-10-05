package com.xin.flaremusic

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.graphicsLayer
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
            // YouTube often hands us hqdefault (480x360). Never use that first
            // in the full player: prefer the largest available thumbnail, then fall
            // back through the supplied URL. This keeps the player artwork sharp
            // while the UI itself remains a strict 1:1 square.
            val candidates = buildList {
                if (source.contains("ytimg.com/vi/")) {
                    add(source.replace(Regex("/(default|mqdefault|hqdefault|sddefault|maxresdefault)\\.jpg"), "/maxresdefault.jpg"))
                    add(source.replace(Regex("/(default|mqdefault|hqdefault|sddefault|maxresdefault)\\.jpg"), "/sddefault.jpg"))
                    add(source.replace(Regex("/(default|mqdefault|hqdefault|sddefault|maxresdefault)\\.jpg"), "/hqdefault.jpg"))
                }
                add(source)
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
fun RenovatedHomeScreen(count: Int, loading: Boolean, error: String, accent: Color, accountName: String?, openLibrary: () -> Unit) {
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
                    Text(if (accountName.isNullOrBlank()) "Made for the way\nyou listen." else "Welcome, $accountName", color = homeText, fontSize = 37.sp, lineHeight = 39.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.7).sp, modifier = Modifier.padding(top = 7.dp))
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
    connect: () -> Unit,
    searchPlaylistSongs: (String) -> Unit,
    playlistSearchResults: List<OnlineTrack>,
    playlistSearchLoading: Boolean,
    addOnlineToPlaylist: (OnlineTrack) -> Unit,
    removeFromPlaylist: (YouTubePlaylistTrack) -> Unit,
    renamePlaylist: (String) -> Unit,
    deletePlaylist: () -> Unit,
    createPlaylist: (String) -> Unit
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    var showPlaylistActions by remember { mutableStateOf(false) }
    var showAddSongs by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(selectedPlaylist?.title.orEmpty()) }

    if (selectedPlaylist != null) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 150.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = closePlaylist) { Icon(Icons.Rounded.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(selectedPlaylist.title, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("YouTube Music playlist", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { showAddSongs = true }) { Icon(Icons.Rounded.PlaylistAdd, "Add songs") }
                    IconButton(onClick = { showPlaylistActions = true }) { Icon(Icons.Rounded.MoreVert, "Playlist options") }
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).clickable { playPlaylistItem(item) }) {
                        RenovationTrackRow(item.title, item.artist, item.thumbnail, null) { playPlaylistItem(item) }
                    }
                    IconButton(onClick = { removeFromPlaylist(item) }) {
                        Icon(Icons.Rounded.RemoveCircleOutline, "Remove from playlist", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (showPlaylistActions) {
            AlertDialog(
                onDismissRequest = { showPlaylistActions = false },
                title = { Text("Playlist") },
                text = {
                    Column {
                        TextButton(
                            onClick = {
                                showPlaylistActions = false
                                renameText = selectedPlaylist.title
                                showRename = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Rename playlist") }
                        TextButton(
                            onClick = {
                                showPlaylistActions = false
                                showDelete = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Delete playlist", color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = { TextButton(onClick = { showPlaylistActions = false }) { Text("Close") } }
            )
        }

        if (showRename) {
            AlertDialog(
                onDismissRequest = { showRename = false },
                title = { Text("Rename playlist") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("Playlist name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (renameText.isNotBlank()) {
                            renamePlaylist(renameText)
                            showRename = false
                        }
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
            )
        }

        if (showDelete) {
            AlertDialog(
                onDismissRequest = { showDelete = false },
                title = { Text("Delete playlist?") },
                text = { Text("This permanently removes “${selectedPlaylist.title}” from YouTube Music.") },
                confirmButton = {
                    TextButton(onClick = {
                        showDelete = false
                        deletePlaylist()
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } }
            )
        }

        if (showAddSongs) {
            PlaylistSongSearchDialog(
                results = playlistSearchResults,
                loading = playlistSearchLoading,
                onSearch = searchPlaylistSongs,
                onAdd = { result -> addOnlineToPlaylist(result); showAddSongs = false },
                onDismiss = { showAddSongs = false }
            )
        }
        return@CompositionLocalProvider
    }

    var favouritesMode by remember { mutableStateOf(false) }
    val shown = if (favouritesMode) favourites else tracks
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 150.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    RenovationHeader("Library", tracks.size.toString() + " songs · " + playlists.size.toString() + " playlists")
                }
                if (status.startsWith("Connected")) {
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Rounded.AddCircleOutline, "Create playlist", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
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

    if (showCreate) {
        var createName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = createName,
                    onValueChange = { createName = it },
                    singleLine = true,
                    label = { Text("Playlist name") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (createName.isNotBlank()) {
                        createPlaylist(createName.trim())
                        showCreate = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } }
        )
    }
    }
}

@Composable
private fun PlaylistSongSearchDialog(
    results: List<OnlineTrack>,
    loading: Boolean,
    onSearch: (String) -> Unit,
    onAdd: (OnlineTrack) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add songs") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text("Search YouTube Music") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onSearch(query) },
                        enabled = query.isNotBlank() && !loading
                    ) { Icon(Icons.Rounded.Search, "Search") }
                }
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(results, key = { it.videoId }) { result ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onAdd(result) }.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RenovationArtwork(result.thumbnail, Modifier.size(48.dp).clip(RoundedCornerShape(11.dp)))
                            Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                                Text(result.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(result.author, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onAdd(result) }) {
                                Icon(Icons.Rounded.PlaylistAdd, "Add")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
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
fun LyricsSheet(
    track: Track,
    lyrics: SyncedLyrics?,
    loading: Boolean,
    positionMs: Long,
    onClose: () -> Unit,
    onSeek: (Long) -> Unit,
    onRefresh: () -> Unit
) {
    val listState = rememberLazyListState()
    val activeIndex = lyrics?.lines?.indexOfLast { it.startMs <= positionMs } ?: -1

    LaunchedEffect(activeIndex, lyrics?.lines?.size) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.ArrowBack, "Close lyrics")
                }
                Column(Modifier.weight(1f)) {
                    Text("Lyrics", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text(
                        track.title + " · " + track.artist,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, "Refresh lyrics")
                }
            }

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (!loading && lyrics == null) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Rounded.Subtitles, null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Synced lyrics not found", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 14.dp))
                    Text(
                        "ArchiveTune-style providers will retry when you refresh.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                    TextButton(onClick = onRefresh) { Text("Try again") }
                }
            } else if (lyrics != null) {
                Text(
                    lyrics.source,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 34.dp),
                    verticalArrangement = Arrangement.spacedBy(17.dp)
                ) {
                    items(lyrics.lines.size) { index ->
                        val line = lyrics.lines[index]
                        val active = index == activeIndex
                        Text(
                            line.text,
                            fontSize = if (active) 25.sp else 20.sp,
                            lineHeight = if (active) 31.sp else 27.sp,
                            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
                            color = if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSeek(line.startMs) }
                        )
                    }
                }
            }
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
    onShowLyrics: () -> Unit,
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
        val motion = if (animationsEnabled && animationStyle != 0) {
            infinite.animateFloat(
                0f, 1f,
                infiniteRepeatable(
                    tween(if (animationStyle == 3) 5000 else 2800),
                    RepeatMode.Reverse
                ),
                label = "playerMotion"
            ).value
        } else 0f
        val pulse = if (animationsEnabled && animationStyle == 2) {
            infinite.animateFloat(
                .985f, 1.015f,
                infiniteRepeatable(tween(1400), RepeatMode.Reverse),
                label = "playerPulse"
            ).value
        } else 1f

        Box(
            Modifier.fillMaxSize()
                .pointerInput(swipeToMinimize) {
                    detectDragGestures(
                        onDragEnd = {
                            if (swipeToMinimize && dragY > 120f) onClose()
                            dragY = 0f
                        },
                        onDragCancel = { dragY = 0f }
                    ) { _, amount -> if (amount.y > 0) dragY += amount.y }
                }
        ) {
            // Background is independent from player layout so changing a style
            // never changes the backdrop geometry.
            when (background) {
                0 -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                1 -> {
                    RenovationArtwork(
                        track.artwork,
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = 1.18f
                            scaleY = 1.18f
                        }
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f)))
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(.22f), Color.Black.copy(.72f))
                            )
                        )
                    )
                }
                2 -> {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = .32f),
                                    MaterialTheme.colorScheme.background.copy(alpha = .96f),
                                    MaterialTheme.colorScheme.background
                                )
                            )
                        )
                    )
                    RenovationArtwork(
                        track.artwork,
                        Modifier.fillMaxSize().graphicsLayer {
                            alpha = .16f
                            scaleX = 1.08f
                            scaleY = 1.08f
                        }
                    )
                }
                else -> {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    RenovationArtwork(
                        track.artwork,
                        Modifier.fillMaxSize().graphicsLayer {
                            alpha = .12f
                            scaleX = 1.06f
                            scaleY = 1.06f
                        }
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.radialGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = .16f),
                                    Color.Transparent
                                )
                            )
                        )
                    )
                }
            }

            // One compact, shared chrome. The four player styles only control
            // the artwork/content arrangement below.
            Column(
                Modifier.fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Minimize", Modifier.size(29.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onShowLyrics, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Rounded.Subtitles, "Lyrics", Modifier.size(23.dp))
                    }
                    Text(
                        "NOW PLAYING",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showQueue = true }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.QueueMusic, "Queue", Modifier.size(24.dp))
                    }
                }

                when (style) {
                    0 -> {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier.fillMaxWidth().widthIn(max = 350.dp).aspectRatio(1f)
                                .graphicsLayer {
                                    val scale = if (animationStyle == 1 && animationsEnabled) 1f + motion * .012f else pulse
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .clip(RoundedCornerShape(30.dp))
                                .border(1.dp, RenovationGlassBorder, RoundedCornerShape(30.dp))
                                .padding(6.dp)
                        ) {
                            RenovationArtwork(track.artwork, Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)))
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    1 -> {
                        Spacer(Modifier.height(4.dp))
                        RenovationArtwork(
                            track.artwork,
                            Modifier.fillMaxWidth(.88f).widthIn(max = 360.dp).aspectRatio(1f)
                                .graphicsLayer {
                                    val scale = if (animationStyle == 1 && animationsEnabled) 1f + motion * .02f else pulse
                                    scaleX = scale
                                    scaleY = scale
                                    translationY = if (animationStyle == 3 && animationsEnabled) (motion - .5f) * 8f else 0f
                                }
                                .clip(RoundedCornerShape(36.dp))
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    2 -> {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier.fillMaxWidth(.86f).widthIn(max = 350.dp).aspectRatio(1f)
                                .graphicsLayer { scaleX = pulse; scaleY = pulse }
                                .clip(RoundedCornerShape(30.dp))
                                .border(1.dp, RenovationGlassBorder, RoundedCornerShape(30.dp))
                                .padding(5.dp)
                        ) {
                            RenovationArtwork(
                                track.artwork,
                                Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    else -> {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(26.dp))
                                .background(Color.Black.copy(alpha = .10f))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RenovationArtwork(
                                track.artwork,
                                Modifier.size(116.dp)
                                    .graphicsLayer { scaleX = pulse; scaleY = pulse }
                                    .clip(RoundedCornerShape(22.dp))
                            )
                            Column(
                                Modifier.weight(1f).padding(horizontal = 16.dp)
                            ) {
                                Text(track.title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(
                                    track.artist,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 5.dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            track.title,
                            fontSize = if (style == 2) 21.sp else 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            track.artist,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onToggleFavourite, modifier = Modifier.size(44.dp)) {
                        Icon(
                            if (isFavourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            "Favourite",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Slider(
                    value = progress,
                    onValueChange = { if (duration > 0) onSeek((it * duration).toLong()) },
                    modifier = Modifier.fillMaxWidth().height(if (FlarePreferences.progressStyle.intValue == 1) 24.dp else 32.dp)
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(position), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatTime(duration), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Keep playback controls visually attached to the seek area.
                // A weighted spacer was pushing them to the absolute bottom of
                // tall screens, creating the large empty gap seen in the player.
                Spacer(Modifier.height(64.dp))

                Row(
                    Modifier.fillMaxWidth().padding(top = 0.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(30.dp))
                    }
                    FilledIconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.size(if (style == 2) 72.dp else 66.dp),
                        shape = if (style == 2) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(22.dp)
                    ) {
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", Modifier.size(33.dp))
                    }
                    IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(30.dp))
                    }
                }

                Spacer(Modifier.height(3.dp))
                Text("Swipe down to minimize", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline.copy(alpha = .65f))
            }

            if (showQueue) {
                AlertDialog(
                    onDismissRequest = { showQueue = false },
                    title = { Text("Queue") },
                    text = {
                        if (queue.isEmpty()) Text("The queue is empty.")
                        else LazyColumn(Modifier.heightIn(max = 430.dp)) {
                            items(queue.size) { index ->
                                val item = queue[index]
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        onPlayQueueItem(index)
                                        showQueue = false
                                    }.padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RenovationArtwork(item.artwork, Modifier.size(43.dp).clip(RoundedCornerShape(10.dp)))
                                    Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(item.artist, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { onRemoveQueueItem(index) }) {
                                        Icon(Icons.Rounded.Close, "Remove")
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showQueue = false }) { Text("Done") } },
                    dismissButton = { TextButton(onClick = onClearQueue) { Text("Clear") } }
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return (total / 60).toString() + ":" + (total % 60).toString().padStart(2, '0')
}
