package com.xin.flaremusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object YouTubeSessionVerifier {
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private const val endpoint = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"

    suspend fun verify(cookieHeader: String): Boolean = withContext(Dispatchers.IO) {
        val cookies = cookieHeader.split(';').mapNotNull {
            val part = it.trim()
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
        }.toMap()
        val sapisid = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: return@withContext false
        val timestamp = System.currentTimeMillis() / 1000
        val origin = "https://music.youtube.com"
        val digest = MessageDigest.getInstance("SHA-1").digest("$timestamp $sapisid $origin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", "1.20260304.03.00")
                .put("hl", "en").put("gl", "US")))
            .put("browseId", "FEmusic_home").toString()
        val request = Request.Builder().url(endpoint)
            .post(body.toRequestBody(jsonType))
            .header("Cookie", cookieHeader)
            .header("Authorization", "SAPISIDHASH ${timestamp}_$digest")
            .header("Origin", origin)
            .header("X-Origin", origin)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val json = JSONObject(response.body?.string().orEmpty())
                val tracking = json.optJSONObject("responseContext")?.optJSONArray("serviceTrackingParams")
                var loggedIn = false
                if (tracking != null) for (i in 0 until tracking.length()) {
                    val params = tracking.optJSONObject(i)?.optJSONArray("params") ?: continue
                    for (j in 0 until params.length()) {
                        val item = params.optJSONObject(j)
                        if (item?.optString("key") == "logged_in" && item.optString("value") == "1") loggedIn = true
                    }
                }
                loggedIn || json.toString().contains("\"musicAccountMenuRenderer\"")
            }
        } catch (_: Exception) { false }
    }
}
