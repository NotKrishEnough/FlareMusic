package com.xin.flaremusic

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MediaStore-backed device library; Android permission is requested by the activity. */
object MediaLibrary {
    suspend fun scan(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)
        context.contentResolver.query(collection, columns, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val key = c.getLong(id)
                result += Track(key, c.getString(title) ?: "Unknown", c.getString(artist) ?: "Unknown artist", c.getString(album) ?: "", ContentUris.withAppendedId(collection, key), c.getLong(duration))
            }
        }
        result
    }
}
