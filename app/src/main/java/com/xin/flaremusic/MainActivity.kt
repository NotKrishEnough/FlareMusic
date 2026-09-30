package com.xin.flaremusic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val Ink = Color(0xFF101014)
private val Panel = Color(0xFF1C1B22)
private val Violet = Color(0xFFB9A2FF)
private val Mint = Color(0xFF9DE5D0)

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.READ_MEDIA_AUDIO)
        setContent { FlareTheme { FlareApp() } }
    }
}

@Composable private fun FlareTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, primary = Violet, secondary = Mint), content = content)
}

@Composable private fun FlareApp() {
    var tab by remember { mutableStateOf("Home") }
    var query by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("Nothing playing yet") }
    val tabs = listOf("Home", "Search", "Library")
    Scaffold(containerColor = Ink, bottomBar = {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(22.dp)).background(Panel).clickable { playing = !playing }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Violet, Mint))), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.GraphicEq, null, tint = Ink) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1); Text("Tap to play or pause", color = Color.LightGray, fontSize = 12.sp) }
                IconButton(onClick = { playing = !playing }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Violet) }
            }
            NavigationBar(containerColor = Ink) {
                tabs.forEach { item -> NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { Icon(when(item) { "Home" -> Icons.Rounded.Home; "Search" -> Icons.Rounded.Search; else -> Icons.Rounded.LibraryMusic }, null) }, label = { Text(item) }) }
            }
        }
    }) { padding ->
        AnimatedContent(tab, modifier = Modifier.padding(padding), label = "page") { page ->
            when(page) { "Home" -> HomeScreen(); "Search" -> SearchScreen(query, { query = it }, { title = it; playing = true }); else -> LibraryScreen() }
        }
    }
}

@Composable private fun HomeScreen() {
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Text("FLARE", color = Violet, fontWeight = FontWeight.Black, letterSpacing = 5.sp, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        Text("Sound,\nwithout limits.", fontSize = 38.sp, lineHeight = 43.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Your music, your way.", color = Color.LightGray)
        Spacer(Modifier.height(28.dp))
        Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(Color(0xFF59468D), Color(0xFF24665F)))), contentAlignment = Alignment.BottomStart) {
            Column(Modifier.padding(22.dp)) { Text("A NEW WAY TO LISTEN", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp); Text("Find your next\nfavorite sound.", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            Icon(Icons.Rounded.GraphicEq, null, Modifier.align(Alignment.TopEnd).padding(24.dp).size(64.dp), tint = Color.White.copy(alpha = .8f))
        }
        Spacer(Modifier.height(24.dp))
        Text("Made for your library", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("Local audio and online discovery are coming together here.", color = Color.LightGray, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable private fun SearchScreen(query: String, onQuery: (String) -> Unit, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Discover", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Search the sound you're looking for.", color = Color.LightGray, modifier = Modifier.padding(top = 5.dp, bottom = 20.dp))
        OutlinedTextField(value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Songs, artists, albums") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(20.dp), singleLine = true)
        Spacer(Modifier.height(20.dp))
        if (query.isNotBlank()) Text("Online search is being wired up.", color = Mint)
        else Text("TRENDING", color = Violet, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    }
}

@Composable private fun LibraryScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Your library", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Everything you keep close.", color = Color.LightGray, modifier = Modifier.padding(top = 5.dp, bottom = 24.dp))
        listOf("Songs" to Icons.Rounded.MusicNote, "Albums" to Icons.Rounded.Album, "Artists" to Icons.Rounded.Person, "Playlists" to Icons.Rounded.QueueMusic).forEach { (name, icon) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(Panel).clickable { }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Violet, modifier = Modifier.size(25.dp))
                Text(name, modifier = Modifier.weight(1f).padding(start = 16.dp), fontWeight = FontWeight.SemiBold)
                Icon(Icons.Rounded.ChevronRight, null, tint = Color.Gray)
            }
        }
    }
}
