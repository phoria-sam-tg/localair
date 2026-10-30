package com.localair.airplay.dial

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.concurrent.thread

/**
 * The receiver (screen) side of YouTube's Lounge API — undocumented, no
 * Google cert needed (see ticket #1019). Protocol verified by hand against
 * the real servers before writing this: pairing/generate_screen_id,
 * pairing/get_lounge_token_batch, bc/bind connect (POST, returns a
 * length-prefixed chunked body carrying "c" (SID) and "S" (gsessionid)
 * events), then bc/bind subscribe (GET, TYPE=xmlhttp) — which is a
 * long-lived STREAMING response, not a single request/response; it must be
 * read incrementally or commands are never seen.
 *
 * A setPlaylist command from a sender arrives on our subscribe stream as a
 * "playlistModified" event carrying videoId — not literally "setPlaylist".
 *
 * NOT implemented: sending our own status back (nowPlaying/onStateChange),
 * so from the sender's UI this receiver looks mute even once it's playing.
 * Good enough for the closed-loop test harness; real UI polish is later.
 */
class LoungeClient(
    private val deviceName: String,
    private val onPlayVideo: (videoId: String) -> Unit,
) {
    private val deviceId = UUID.randomUUID().toString()
    private var screenId: String? = null
    private var loungeToken: String? = null
    private var sid: String? = null
    private var gsession: String? = null
    private var lastEventId: Int = 0
    private var commandOffset = 1
    @Volatile private var running = false

    /** Non-blocking — null until ensureScreen()/startIfNeeded() has actually run once. */
    fun screenIdOrNull(): String? = screenId

    /** Registers our own screen_id (for a sender that reads it straight off the DIAL status XML, bypassing pairing codes) and gets us a token — call once at startup, independent of any particular cast attempt. */
    fun ensureScreen(): String {
        screenId?.let { return it }
        val id = get("$API_BASE/pairing/generate_screen_id").trim()
        screenId = id
        loungeToken = fetchLoungeToken(id)
        Log.i(TAG, "screen ready: $id")
        return id
    }

    /** Links a DIAL launch's pairingCode to our screen_id, so the real YouTube app (which only knows the code, not our screen_id) can find us via pairing/get_screen. */
    fun registerPairingCode(pairingCode: String) {
        val id = ensureScreen()
        post(
            "$API_BASE/pairing/register_pairing_code", mapOf(
                "access_type" to "permanent",
                "app" to "lb-v4",
                "screen_id" to id,
                "screen_name" to deviceName,
                "pairing_code" to pairingCode,
                "device_id" to deviceId,
            )
        )
        Log.i(TAG, "registered pairing code for screen $id")
        startIfNeeded()
    }

    fun startIfNeeded() {
        if (running) return
        running = true
        thread(name = TAG, isDaemon = true) {
            ensureScreen()
            runLoop()
        }
    }

    fun stop() {
        running = false
    }

    private fun runLoop() {
        while (running) {
            try {
                if (!connect()) {
                    Thread.sleep(5000); continue
                }
                subscribeStream()
            } catch (e: Exception) {
                Log.w(TAG, "lounge loop error, retrying", e)
            }
            Thread.sleep(2000)
        }
    }

    private fun connect(): Boolean {
        val body = formEncode(
            mapOf(
                "app" to "web",
                "mdx-version" to "3",
                "name" to deviceName,
                "id" to deviceId,
                "device" to "LOUNGE_SCREEN",
                "capabilities" to "",
                "magnaKey" to "cloudPairedDevice",
                "ui" to "",
                "deviceContext" to "user_agent=dunno&window_width_points=&window_height_points=&os_name=android&ms=",
                "theme" to "cl",
                "loungeIdToken" to (loungeToken ?: return false),
            )
        )
        val text = post("$API_BASE/bc/bind?RID=1&VER=8&CVER=1&auth_failure_option=send_error", body)
        processChunks(text)
        return sid != null && gsession != null
    }

    private fun subscribeStream() {
        val url = URL("$API_BASE/bc/bind?${commonParams()}&RID=rpc&CI=0&TYPE=xmlhttp")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 65_000
        }
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            readChunksStreaming(reader) { events -> processEventArray(events) }
        }
    }

    private fun readChunksStreaming(reader: BufferedReader, onChunk: (JSONArray) -> Unit) {
        while (running) {
            val lengthLine = reader.readLine() ?: break
            if (lengthLine.isBlank()) continue
            val length = lengthLine.trim().toIntOrNull() ?: continue
            val sb = StringBuilder()
            while (sb.length < length) {
                val line = reader.readLine() ?: break
                sb.append(line).append('\n')
            }
            val chunk = sb.toString().take(length)
            runCatching { JSONArray(chunk) }.onSuccess(onChunk)
        }
    }

    private fun processChunks(text: String) {
        val reader = text.reader().buffered()
        readChunksStreaming(reader) { events -> processEventArray(events) }
    }

    private fun processEventArray(events: JSONArray) {
        for (i in 0 until events.length()) {
            val event = events.getJSONArray(i)
            lastEventId = event.getInt(0)
            val payload = event.getJSONArray(1)
            val type = payload.getString(0)
            when (type) {
                "c" -> sid = payload.getString(1)
                "S" -> gsession = payload.getString(1)
                "playlistModified", "setPlaylist" -> {
                    val data = payload.optJSONObject(1)
                    val videoId = data?.optString("videoId")
                    if (!videoId.isNullOrEmpty()) {
                        Log.i(TAG, "$type: videoId=$videoId")
                        onPlayVideo(videoId)
                    } else {
                        Log.w(TAG, "$type with no videoId: $payload")
                    }
                }
                "noop", "onAutoplayModeChanged", "onPlaylistModeChanged", "loungeStatus",
                "remoteConnected", "getNowPlaying", "getSubtitlesTrack", "getVolume",
                "getPartyGamesMode", "getDiscoveryDeviceId" -> Unit
                else -> Log.d(TAG, "unhandled event: $type ($payload)")
            }
        }
    }

    private fun commonParams(): String = listOf(
        "name" to deviceName,
        "loungeIdToken" to (loungeToken ?: ""),
        "SID" to (sid ?: ""),
        "AID" to lastEventId.toString(),
        "gsessionid" to (gsession ?: ""),
        "device" to "LOUNGE_SCREEN",
        "app" to "youtube-desktop",
        "VER" to "8",
        "v" to "2",
    ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    private fun fetchLoungeToken(screenId: String): String {
        val text = post("$API_BASE/pairing/get_lounge_token_batch", mapOf("screen_ids" to screenId))
        val screen = JSONObject(text).getJSONArray("screens").getJSONObject(0)
        return screen.getString("loungeToken")
    }

    private fun formEncode(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    private fun get(urlString: String): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply { requestMethod = "GET" }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun post(urlString: String, params: Map<String, String>): String = post(urlString, formEncode(params))

    private fun post(urlString: String, body: String): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    companion object {
        private const val TAG = "LoungeClient"
        private const val API_BASE = "https://www.youtube.com/api/lounge"
    }
}
