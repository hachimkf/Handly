// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.math.abs

/**
 * Find the dash when the **phone** hosts the Wi‑Fi (Zontes / Carbit `action=128` QRs with no
 * ssid/pwd). Android does not let a third-party app create a hotspot with the SSID/password the
 * dash prints, so the rider turns tethering on by hand; we only locate the tethering iface and
 * scan for EasyConn on that subnet.
 *
 * Topology (inverted vs SoftAP): phone = gateway on e.g. `192.168.43.1/24`; dash = DHCP client
 * somewhere on that subnet — no fixed `.1` peer and no [android.net.Network] to bind.
 */
object PhoneHotspotScan {

    private val EXCLUDED_INTERFACE_PREFIXES = listOf(
        "rmnet", "ccmni", "pdp", "wwan", "clat", "tun", "tap", "ppp",
        "dummy", "lo", "sit", "ip6tnl", "oem", "nm_", "ifb", "bond", "wlan0",
    )
    private val KNOWN_HOTSPOT_PREFIXES = listOf(
        "ap", "swlan", "softap", "wlan1", "wlan2", "tether_wlan", "wigig", "rndis", "bt-pan", "tether",
    )
    private const val PROBE_CONNECT_MS = 250
    private val PROBE_PORTS = listOf(10930, 10920, 10921, 10922)

    data class Subnet(
        val localAddress: Inet4Address,
        val prefixLength: Int,
        val interfaceName: String,
    )

    data class InterfaceSnapshot(
        val name: String,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val isPointToPoint: Boolean,
        val addresses: List<Pair<InetAddress, Int>>,
    )

    fun snapshotInterfaces(): List<InterfaceSnapshot> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().map { ni ->
                InterfaceSnapshot(
                    name = ni.name.orEmpty(),
                    isUp = runCatching { ni.isUp }.getOrDefault(false),
                    isLoopback = runCatching { ni.isLoopback }.getOrDefault(false),
                    isPointToPoint = runCatching { ni.isPointToPoint }.getOrDefault(false),
                    addresses = ni.interfaceAddresses.orEmpty().mapNotNull { ia ->
                        ia.address?.let { it to ia.networkPrefixLength.toInt() }
                    },
                )
            }.toList()
        }.getOrDefault(emptyList())

    fun tetheringSubnets(
        interfaces: List<InterfaceSnapshot> = snapshotInterfaces(),
        excluding: Set<InetAddress> = emptySet(),
    ): List<Subnet> =
        interfaces
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .filter { candidate ->
                val lowered = candidate.name.lowercase()
                val isExcluded = EXCLUDED_INTERFACE_PREFIXES.any { lowered.startsWith(it) }
                val isKnownHotspot = KNOWN_HOTSPOT_PREFIXES.any { lowered.startsWith(it) }
                !isExcluded || isKnownHotspot
            }
            .flatMap { candidate ->
                candidate.addresses.mapNotNull { (address, prefixLength) ->
                    val ipv4 = address as? Inet4Address ?: return@mapNotNull null
                    if (ipv4 in excluding) return@mapNotNull null
                    if (ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress || ipv4.isAnyLocalAddress) {
                        return@mapNotNull null
                    }
                    if (!ipv4.isSiteLocalAddress) return@mapNotNull null
                    if (prefixLength < 24 || prefixLength > 30) return@mapNotNull null
                    // Exclude carrier CGNAT ranges (100.64.0.0/10) and cellular private ranges if interface is suspicious
                    val host = ipv4.hostAddress ?: ""
                    if (host.startsWith("100.") || (host.startsWith("10.") && !KNOWN_HOTSPOT_PREFIXES.any { candidate.name.lowercase().startsWith(it) })) {
                        return@mapNotNull null
                    }
                    Subnet(ipv4, prefixLength, candidate.name)
                }
            }
            .sortedBy { rank(it.interfaceName) }

    private fun rank(interfaceName: String): Int {
        val lowered = interfaceName.lowercase()
        val index = KNOWN_HOTSPOT_PREFIXES.indexOfFirst(lowered::startsWith)
        return if (index < 0) KNOWN_HOTSPOT_PREFIXES.size else index
    }

    /** Hosts a dash could hold on [subnet], nearest the phone first (DHCP pool usually follows). */
    fun candidateHosts(subnet: Subnet, limit: Int = 48): List<Inet4Address> {
        val local = subnet.localAddress.address
        val hostBits = 32 - subnet.prefixLength
        val size = 1 shl hostBits
        val localValue = local.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        val mask = if (hostBits == 32) 0 else (-1 shl hostBits)
        val network = localValue and mask
        return (1 until size - 1)
            .map { offset -> network + offset }
            .filter { it != localValue }
            .sortedBy { abs(it - localValue) }
            .take(limit)
            .map { value ->
                InetAddress.getByAddress(
                    byteArrayOf(
                        (value ushr 24).toByte(),
                        (value ushr 16).toByte(),
                        (value ushr 8).toByte(),
                        value.toByte(),
                    ),
                ) as Inet4Address
            }
    }

    /**
     * Probe nearby hosts for an open EasyConn-ish port. Returns the first responsive peer, or null.
     */
    fun findEasyConnPeer(subnet: Subnet, log: (String) -> Unit): Inet4Address? {
        val hosts = candidateHosts(subnet)
        log(
            "[HOTSPOT] scanning ${hosts.size} hosts on ${subnet.localAddress.hostAddress}/" +
                "${subnet.prefixLength} (${subnet.interfaceName}) for EasyConn…",
        )
        for (host in hosts) {
            for (port in PROBE_PORTS) {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, port), PROBE_CONNECT_MS)
                        log("[HOTSPOT] peer ${host.hostAddress}:$port open")
                        return host
                    }
                } catch (_: Exception) {
                }
            }
        }
        log("[HOTSPOT] no EasyConn peer found on tether subnet")
        return null
    }
}
