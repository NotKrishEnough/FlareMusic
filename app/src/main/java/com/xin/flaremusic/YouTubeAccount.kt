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

/** Reads the signed-in YouTube Music account name from the authenticated InnerTube session. */
object YouTubeAccount {
    private val http = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private const val endpoint = "https://music.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false"

    suspend fun fetchDisplayName(cookieHeader: String): String? = withContext(Dispatchers.IO) {
        try {
            val cookies = cookieHeader.split(';').mapNotNull {
                val part = it.trim()
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }.toMap()
            val sapisid = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: return@withContext null
            val timestamp = System.currentTimeMillis() / 1000
            val origin = "https://music.youtube.com"
            val digest = MessageDigest.getInstance("SHA-1")
                .digest("$timestamp $sapisid $origin".toByteArray())
                .joinToString("") { "%02x".format(it) }

            val body = JSONObject()
                .put("context", JSONObject().put("client", JSONObject()
                    .put("clientName", "WEB_REMIX")
                    .put("clientVersion", "1.20260304.03.00")
                    .put("hl", "en")
                    .put("gl", "US")))
                .toString()

            val request = Request.Builder()
                .url(endpoint)
                .post(body.toRequestBody(jsonType))
                .header("Cookie", cookieHeader)
                .header("Authorization", "SAPISIDHASH ${timestamp}_$digest")
                .header("Origin", origin)
                .header("X-Origin", origin)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val root = JSONObject(response.body?.string().orEmpty())
                val header = root.optJSONArray("actions")
                    ?.optJSONObject(0)
                    ?.optJSONObject("openPopupAction")
                    ?.optJSONObject("popup")
                    ?.optJSONObject("multiPageMenuRenderer")
                    ?.optJSONObject("header")
                    ?.optJSONObject("activeAccountHeaderRenderer")
                    ?: return@withContext null
                val accountName = header.optJSONObject("accountName")
                accountName?.optJSONArray("runs")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.takeIf { it.isNotBlank() }
                    ?: accountName?.optString("simpleText")?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }
}
