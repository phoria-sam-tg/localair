package com.localair.airplay.dial

import java.net.Inet4Address
import java.net.NetworkInterface

/** The device's own LAN IPv4 address — same interface SsdpResponder joins the multicast group on. */
fun localIpv4Address(): String? =
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull()
        ?.hostAddress
