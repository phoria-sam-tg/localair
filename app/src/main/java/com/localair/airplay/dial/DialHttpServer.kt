package com.localair.airplay.dial

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The HTTP half of DIAL: serves the UPnP device description SsdpResponder
 * points senders at, then handles the YouTube app's app-launch POST.
 *
 * GET  {descriptionPath}   -> device description XML, with the
 *                             "Application-URL" header DIAL requires —
 *                             that's what tells the sender where /apps/ is.
 * POST /apps/YouTube       -> launch. Body is form-encoded
 *                             (pairingCode=...&theme=cl&dialLaunch=watch).
 *                             We hand the pairing code to onLaunch and
 *                             answer 201 with a Location for the instance.
 * GET  /apps/YouTube       -> status poll. XML state: stopped|running.
 *
 * No Lounge API calls happen here — this class only speaks DIAL. Pairing
 * code handling (talking to youtube.com/api/lounge/) is the next piece.
 */
class DialHttpServer(
    private val uuid: String,
    private val friendlyName: String,
    private val descriptionPath: String,
    private val screenId: () -> String?,
    private val onLaunch: (pairingCode: String) -> Unit,
) {
    private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var appRunning = false

    val port: Int get() = server?.localPort ?: 0

    fun start(): Int {
        val sock = ServerSocket(0)
        server = sock
        running = true
        thread(name = TAG, isDaemon = true) {
            Log.i(TAG, "listening on ${sock.localPort}")
            while (running) {
                val client = try { sock.accept() } catch (e: Exception) {
                    if (running) Log.e(TAG, "accept failed", e); break
                }
                thread(isDaemon = true) { runCatching { handle(client) }.onFailure { Log.e(TAG, "client failed", it) } }
            }
        }
        return sock.localPort
    }

    fun stop() {
        running = false
        server?.let { runCatching { it.close() } }
        server = null
    }

    private fun handle(client: Socket) {
        client.use { c ->
            val input = c.getInputStream().bufferedReader(Charsets.UTF_8)
            val requestLine = input.readLine() ?: return
            val (method, path) = requestLine.split(" ").let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }

            var contentLength = 0
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                input.read(buf, 0, contentLength)
                String(buf)
            } else ""

            Log.i(TAG, "$method $path${if (body.isNotEmpty()) " body=$body" else ""}")
            val out = c.getOutputStream()
            when {
                method == "GET" && path == descriptionPath -> out.write(descriptionResponse())
                method == "POST" && path == "/apps/YouTube" -> out.write(launchResponse(body))
                method == "GET" && path.startsWith("/apps/YouTube") -> out.write(statusResponse())
                else -> out.write(notFoundResponse())
            }
            out.flush()
        }
    }

    private fun descriptionResponse(): ByteArray {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <specVersion><major>1</major><minor>0</minor></specVersion>
              <device>
                <deviceType>urn:schemas-upnp-org:device:tvdevice:1</deviceType>
                <friendlyName>$friendlyName</friendlyName>
                <manufacturer>localair</manufacturer>
                <modelName>localair</modelName>
                <UDN>uuid:$uuid</UDN>
              </device>
            </root>
        """.trimIndent()
        return httpResponse(200, "OK", "text/xml", xml, extraHeaders = listOf(
            "Application-URL: http://${localIpv4Address()}:$port/apps/"
        ))
    }

    private fun launchResponse(body: String): ByteArray {
        val pairingCode = body.split("&")
            .firstOrNull { it.startsWith("pairingCode=") }
            ?.substringAfter("=")
        if (pairingCode != null) {
            appRunning = true
            onLaunch(pairingCode)
        }
        return httpResponse(201, "Created", "text/plain", "", extraHeaders = listOf(
            "Location: /apps/YouTube/run"
        ))
    }

    private fun statusResponse(): ByteArray {
        val state = if (appRunning) "running" else "stopped"
        // additionalData/screenId: not required by the DIAL spec, but some
        // senders (and our own test harness) read it straight off here to
        // skip the pairing-code round trip entirely — see LoungeClient.
        val id = screenId()
        val additional = if (id != null) "<additionalData><screenId>$id</screenId></additionalData>" else ""
        val xml = """<?xml version="1.0" encoding="UTF-8"?><service xmlns="urn:dial-multiscreen-org:schemas:dial"><name>YouTube</name><state>$state</state>$additional</service>"""
        return httpResponse(200, "OK", "text/xml", xml)
    }

    private fun notFoundResponse() = httpResponse(404, "Not Found", "text/plain", "")

    private fun httpResponse(
        code: Int, reason: String, contentType: String, body: String,
        extraHeaders: List<String> = emptyList(),
    ): ByteArray {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            extraHeaders.forEach { append("$it\r\n") }
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        return headers + bodyBytes
    }

    companion object {
        private const val TAG = "DialHttpServer"
    }
}
