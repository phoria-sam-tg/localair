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
 * Status goes back the same way a remote's commands come in: a bc/bind POST
 * carrying "nowPlaying" / "onStateChange" (reportPlayback), so the sender's
 * cast bar follows position and play/pause. The state itself is read from
 * the YouTube TV app's media session (YouTubeAppControl.watch).
 */
class LoungeClient(
    private val deviceName: String,
    private val onPlayVideo: (videoId: String, startSeconds: Double?) -> Unit,
    private val onControl: (LoungeControl) -> Unit = {},
    /** Latest playback state, for a sender's getNowPlaying; null when nothing is playing. */
    private val currentPlayback: () -> Playback? = { null },
) {
    private val deviceId = UUID.randomUUID().toString()
    private var screenId: String? = null
    private var loungeToken: String? = null
    @Volatile private var sid: String? = null
    @Volatile private var gsession: String? = null
    private var lastEventId: Int = 0
    private var commandOffset = 1
    private var rid = 1
    @Volatile private var videoId: String? = null
    @Volatile private var running = false
    private val sendLock = Any()

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
        synchronized(sendLock) { rid = 1; commandOffset = 1 }
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
                    Log.i(TAG, "$type payload: $data")
                    if (!videoId.isNullOrEmpty()) {
                        val startSeconds = data?.optString("currentTime")?.toDoubleOrNull()
                        this.videoId = videoId
                        onPlayVideo(videoId, startSeconds)
                    } else {
                        Log.w(TAG, "$type with no videoId: $payload")
                    }
                }
                "play" -> onControl(LoungeControl.Play)
                "pause" -> onControl(LoungeControl.Pause)
                "stopVideo" -> onControl(LoungeControl.Stop)
                "seekTo" -> payload.optJSONObject(1)?.optString("newTime")?.toDoubleOrNull()
                    ?.let { onControl(LoungeControl.Seek(it)) }
                "next" -> onControl(LoungeControl.Next)
                "previous" -> onControl(LoungeControl.Previous)
                "setVolume" -> payload.optJSONObject(1)?.optString("volume")?.toIntOrNull()
                    ?.let { onControl(LoungeControl.SetVolume(it.coerceIn(0, 100))) }
                "getNowPlaying" -> thread(name = "$TAG-np", isDaemon = true) {
                    reportPlayback(currentPlayback() ?: Playback.IDLE, nowPlaying = true)
                }
                "noop", "onAutoplayModeChanged", "onPlaylistModeChanged", "loungeStatus",
                "remoteConnected", "getSubtitlesTrack", "getVolume",
                "getPartyGamesMode", "getDiscoveryDeviceId" -> Unit
                else -> Log.d(TAG, "unhandled event: $type ($payload)")
            }
        }
    }

    /**
     * Tells connected senders where playback is. onStateChange moves their
     * scrubber and play/pause button; nowPlaying (also the answer to
     * getNowPlaying) names the video too. Blocking network call: call it off
     * the main thread. Silently skipped until the bind session exists.
     */
    fun reportPlayback(p: Playback, nowPlaying: Boolean = false) {
        if (sid == null || gsession == null) return
        val fields = linkedMapOf(
            "currentTime" to "%.3f".format(java.util.Locale.US, p.positionSeconds),
            "duration" to "%.3f".format(java.util.Locale.US, p.durationSeconds),
            "state" to p.state.code.toString(),
            "loadedTime" to "%.3f".format(java.util.Locale.US, p.positionSeconds),
            "seekableStartTime" to "0",
            "seekableEndTime" to "%.3f".format(java.util.Locale.US, p.durationSeconds),
            "cpn" to cpn,
        )
        if (nowPlaying) videoId?.let { fields["videoId"] = it }
        send(if (nowPlaying) "nowPlaying" else "onStateChange", fields)
    }

    private fun send(command: String, fields: Map<String, String>) = synchronized(sendLock) {
        rid++
        val body = buildMap {
            put("count", "1")
            put("ofs", (commandOffset++).toString())
            put("req0__sc", command)
            fields.forEach { (k, v) -> put("req0_$k", v) }
        }
        runCatching { post("$API_BASE/bc/bind?${commonParams()}&RID=$rid", body) }
            .onSuccess { Log.d(TAG, "sent $command $fields") }
            .onFailure { Log.w(TAG, "send $command failed: ${it.message}") }
    }

    /** Client playback nonce: senders expect one per screen session; any opaque token works. */
    private val cpn = UUID.randomUUID().toString().replace("-", "").take(16)

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

/** Transport commands a sender can send after playback has started. */
sealed class LoungeControl {
    object Play : LoungeControl() { override fun toString() = "play" }
    object Pause : LoungeControl() { override fun toString() = "pause" }
    object Stop : LoungeControl() { override fun toString() = "stop" }
    data class Seek(val seconds: Double) : LoungeControl()
    object Next : LoungeControl() { override fun toString() = "next" }
    object Previous : LoungeControl() { override fun toString() = "previous" }
    /** 0-100, the sender's scale. */
    data class SetVolume(val percent: Int) : LoungeControl()
}

/** What the sender's cast bar shows. Lounge state codes are YouTube player states. */
data class Playback(val state: State, val positionSeconds: Double, val durationSeconds: Double) {
    enum class State(val code: Int) { UNSTARTED(-1), ENDED(0), PLAYING(1), PAUSED(2), BUFFERING(3) }

    companion object { val IDLE = Playback(State.UNSTARTED, 0.0, 0.0) }
}
