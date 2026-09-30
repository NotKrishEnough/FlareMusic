package com.xin.flaremusic

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * NewPipe Extractor adapter. It resolves YouTube's changing player formats and
 * signature handling; it does not bypass DRM, account restrictions, or paid access.
 */
object NewPipeAudioResolver {
    private val client = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile private var initialized = false

    @Synchronized private fun initialize() {
        if (initialized) return
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val bodyBytes = request.dataToSend()
                val body = bodyBytes?.toRequestBody("application/octet-stream".toMediaType())
                val builder = okhttp3.Request.Builder()
                    .url(request.url())
                    .method(request.httpMethod(), body)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0.0.0 Mobile Safari/537.36")
                request.headers().forEach { (name, values) ->
                    builder.removeHeader(name)
                    values.forEach { value -> builder.addHeader(name, value) }
                }
                client.newCall(builder.build()).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException("Extractor HTTP ${response.code}: ${response.message}")
                    }
                    return Response(
                        response.code,
                        response.message,
                        response.headers.toMultimap(),
                        responseBody,
                        response.request.url.toString()
                    )
                }
            }
        }, Localization("en", "US"))
        initialized = true
    }

    suspend fun resolve(videoId: String): String = withContext(Dispatchers.IO) {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Invalid YouTube video ID" }
        initialize()
        val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
        val audio = info.audioStreams
            .filter { it.isUrl && it.content.startsWith("https://") }
            .maxByOrNull { it.averageBitrate }
            ?: throw IOException("NewPipe found no direct audio-only stream for this video")
        audio.content
    }
}
