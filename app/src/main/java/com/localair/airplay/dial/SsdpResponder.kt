package com.localair.airplay.dial

import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Answers DIAL discovery. The YouTube app finds cast targets by sending an
 * SSDP M-SEARCH to the UPnP multicast group and expects a unicast reply
 * naming a device-description URL — this is a different mechanism from
 * AirPlay's mDNS (see MdnsAdvertiser): SSDP over UDP 1900, not NSD.
 *
 * Request we answer (anything else on 1900 is ignored):
 *   M-SEARCH * HTTP/1.1
 *   MAN: "ssdp:discover"
 *   ST: urn:dial-multiscreen-org:service:dial:1
 *
 * Reply (unicast, straight back to the sender's address:port):
 *   HTTP/1.1 200 OK
 *   LOCATION: http://<host>:<httpPort><descriptionPath>
 *   ST: urn:dial-multiscreen-org:service:dial:1
 *   USN: uuid:<uuid>::urn:dial-multiscreen-org:service:dial:1
 */
class SsdpResponder(
    private val uuid: String,
    private val httpPort: Int,
    private val descriptionPath: String,
) {
    private var socket: MulticastSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = TAG, isDaemon = true) {
            try {
                val group = InetAddress.getByName(MULTICAST_ADDR)
                val sock = MulticastSocket(SSDP_PORT).apply {
                    reuseAddress = true
                    joinGroup(InetSocketAddress(group, SSDP_PORT), findInterface())
                }
                socket = sock
                val buf = ByteArray(2048)
                Log.i(TAG, "listening on $MULTICAST_ADDR:$SSDP_PORT")
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    handle(sock, packet)
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "responder died", e)
            }
        }
    }

    fun stop() {
        running = false
        socket?.let { runCatching { it.close() } }
        socket = null
    }

    private fun handle(sock: MulticastSocket, packet: DatagramPacket) {
        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
        if (!text.startsWith("M-SEARCH", ignoreCase = true)) return
        val st = text.lineSequence()
            .firstOrNull { it.startsWith("ST:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
        if (st != DIAL_ST) return

        val host = localIpv4Address() ?: return
        val location = "http://$host:$httpPort$descriptionPath"
        val reply = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=1800\r\n")
            append("EXT:\r\n")
            append("LOCATION: $location\r\n")
            append("SERVER: Android/localair UPnP/1.0 localair/1.0\r\n")
            append("ST: $DIAL_ST\r\n")
            append("USN: uuid:$uuid::$DIAL_ST\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)

        val reader = DatagramPacket(reply, reply.size, packet.address, packet.port)
        sock.send(reader)
        Log.i(TAG, "M-SEARCH from ${packet.address.hostAddress} -> $location")
    }

    private fun findInterface(): NetworkInterface? =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .firstOrNull { ni ->
                ni.isUp && !ni.isLoopback &&
                    ni.inetAddresses.asSequence().any { it is java.net.Inet4Address }
            }

    companion object {
        private const val TAG = "SsdpResponder"
        private const val MULTICAST_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        const val DIAL_ST = "urn:dial-multiscreen-org:service:dial:1"
    }
}
