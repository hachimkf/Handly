// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Handly / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import kotlin.concurrent.thread

/**
 * Single high-level connection facade orchestrating the motorcycle Carbit/EasyConnect/PXC lifecycle:
 *
 *   1. Scan / Parse QR ([QrData])
 *   2. Bike Profile Selection ([BikeProfiles])
 *   3. P2P Discovery / Wi-Fi SoftAP Connection ([BikeWifiP2p], [BikeWifi])
 *   4. Network Interface Binding ([BikeWifi.rebindProcessToBike])
 *   5. PXC Control Socket (:10922) & Video Stream (:10920)
 *   6. Protocol Handshake & Sn Check ([PxcHandshake])
 *   7. Heartbeat Maintenance (0x70000000)
 *   8. Session State & Diagnostics ([ConnectionState], [ConnectionTrace], [DiagnosticsStore])
 *
 * ARCHITECTURAL RULE:
 * This connection manager has ZERO dependencies on Navigation (Google Maps, Waze) or Media (Spotify).
 * It ONLY handles establishing and maintaining the motorcycle link.
 */
object CarbitConnectionManager {

    private const val TAG = "[CarbitConn]"

    @Volatile
    private var lastQr: QrData? = null

    @Volatile
    var prober: EasyConnProber? = null
        private set

    val isConnected: Boolean
        get() = ConnectionState.phase == Phase.STREAMING || ConnectionState.phase == Phase.MIRRORING

    val currentPhase: Phase
        get() = ConnectionState.phase

    val currentDetail: String
        get() = ConnectionState.detail

    /**
     * Connect to the motorcycle using the scanned [QrData].
     */
    fun connect(context: Context, qr: QrData) {
        lastQr = qr
        val appCtx = context.applicationContext

        LogBus.log("$TAG connect() initiated for '${qr.name ?: qr.ssid}' (modelId=${qr.modelId}, mac=${qr.mac})")
        ConnectionTrace.reset()
        ConnectionTrace.transition(ConnectionTrace.Step.QR_AVAILABLE)
        ConnectionTrace.transition(
            ConnectionTrace.Step.QR_PARSED,
            "modelId=${qr.modelId}, mac=${qr.mac}, action=${qr.action}, ssid=${qr.ssid}",
        )

        // Clean up previous sockets and groups before starting a new link
        cleanupPreviousSession(appCtx)

        // Select bike profile
        val profile = BikeProfiles.selectByQr(qr, appCtx)
        BikeProfileHolder.active = profile
        val raw = qr.rawString.ifBlank { "http://www.carbit.com.cn/?modelid=${qr.modelId}&action=${qr.action}&bm=${qr.mac}" }
        BikeMemory.save(appCtx, raw, qr)

        val bikeName = BikeMemory.lastBikeName(appCtx) ?: qr.name ?: qr.ssid
        ConnectionState.set(Phase.JOINING_WIFI, "Finding motorcycle...")

        val pr = EasyConnProber(appCtx, LogBus::log)
        prober = pr
        BikeLink.prober = pr

        val isPhoneHotspot = qr.supportsPhoneHotspot || (qr.action and 128) != 0
        val isP2p = qr.supportsP2p || (qr.action and 8) != 0

        val transportName = when {
            isPhoneHotspot -> "PHONE_HOTSPOT"
            isP2p -> "WIFI_DIRECT_P2P"
            else -> "SOFTAP"
        }

        ConnectionTrace.transition(
            ConnectionTrace.Step.QR_PARSED,
            "action=${qr.action} transport=$transportName bm=${qr.mac ?: "none"}",
        )

        when {
            isPhoneHotspot -> {
                connectPhoneHotspot(appCtx, qr, pr)
            }
            isP2p -> {
                connectP2p(appCtx, qr, pr)
            }
            else -> {
                connectSoftAp(appCtx, qr, pr)
            }
        }
    }

    /**
     * Reconnect to the last known motorcycle.
     */
    fun reconnect(context: Context): Boolean {
        val qr = lastQr ?: BikeMemory.selected(context)?.qr ?: BikeMemory.lastQr(context) ?: return false
        connect(context, qr)
        return true
    }

    private fun cleanupPreviousSession(context: Context?) {
        try {
            prober?.stop()
            prober = null
            BikeLink.prober = null
            BikeWifiP2p.stop(LogBus::log)
            if (context != null) {
                BikeWifi.leave(context.applicationContext, LogBus::log)
                WifiGate.cancelNotification(context.applicationContext)
            }
        } catch (e: Exception) {
            LogBus.log("$TAG cleanup error: ${e.message}")
        }
    }

    /**
     * Cleanly disconnect and release all sockets, listeners, and P2P groups.
     */
    fun disconnect(context: Context? = null) {
        LogBus.log("$TAG disconnect() called")
        cleanupPreviousSession(context)
        ConnectionState.set(Phase.STOPPED)
    }

    private fun connectPhoneHotspot(context: Context, qr: QrData, pr: EasyConnProber) {
        LogBus.log("$TAG Starting Phone Hotspot flow for MAC=${qr.mac} (action=${qr.action})...")

        thread(name = "hotspot-flow", isDaemon = true) {
            val deadline = System.currentTimeMillis() + 60_000L
            var notifiedSettings = false
            var btProvisionAttempted = false

            while (System.currentTimeMillis() < deadline) {
                val subnets = PhoneHotspotScan.tetheringSubnets()
                val isHotspotActive = subnets.isNotEmpty()
                val activeSubnet = subnets.firstOrNull()

                LogBus.log(
                    "$TAG [HOTSPOT DIAGNOSTICS] " +
                        "HOTSPOT_STATE=${if (isHotspotActive) "ON" else "OFF"} " +
                        "HOTSPOT_INTERFACE=${activeSubnet?.interfaceName ?: "NONE"} " +
                        "HOTSPOT_SSID=<hidden> " +
                        "HOTSPOT_GATEWAY=${activeSubnet?.localAddress?.hostAddress ?: "NONE"} " +
                        "HOTSPOT_SUBNET=${activeSubnet?.let { "${it.localAddress.hostAddress}/${it.prefixLength}" } ?: "NONE"} " +
                        "HOTSPOT_CLIENT_COUNT=UNAVAILABLE",
                )

                if (!isHotspotActive || activeSubnet == null) {
                    if (!notifiedSettings) {
                        notifiedSettings = true
                        LogBus.log("$TAG [HOTSPOT] Wi-Fi tethering interface not found — requesting user to enable Mobile Hotspot")
                        ConnectionTrace.transition(
                            ConnectionTrace.Step.PHONE_HOTSPOT_REQUIRED,
                            "Turn on Mobile Hotspot in Android settings",
                        )
                        ConnectionState.set(Phase.JOINING_WIFI, "Turn on Mobile Hotspot")
                        PhoneHotspotAssist.openHotspotSettings(context)
                    }
                    Thread.sleep(2000L)
                    continue
                }

                val subnet = activeSubnet
                ConnectionTrace.transition(
                    ConnectionTrace.Step.PHONE_HOTSPOT_ENABLED,
                    "Local IP=${subnet.localAddress.hostAddress}, iface=${subnet.interfaceName}",
                )
                ConnectionState.set(Phase.JOINING_WIFI, "Waiting for motorcycle...")

                // Concurrently attempt Bluetooth SDP AP provisioning if MAC is present
                val btMac = qr.mac
                if (!btProvisionAttempted && !btMac.isNullOrBlank()) {
                    btProvisionAttempted = true
                    val creds = PhoneHotspotAssist.loadCreds(context)
                    thread(name = "bt-provision", isDaemon = true) {
                        CarbitBtBridge.sendApInfo(
                            context = context,
                            btMac = btMac,
                            ssid = creds.ssid.ifBlank { "Mobile Hotspot" },
                            pwd = creds.pwd,
                            phoneIp = subnet.localAddress.hostAddress ?: "192.168.43.1",
                            log = { LogBus.log("$TAG $it") },
                        )
                    }
                }

                // Scan for motorcycle peer joining the hotspot
                val peer = PhoneHotspotScan.findEasyConnPeer(subnet) { LogBus.log("$TAG $it") }
                    ?: EasyConnDiscovery.discoverNsd(context, LogBus::log)?.host

                if (peer != null) {
                    LogBus.log("$TAG [HOTSPOT] *** Motorcycle connected to hotspot at ${peer.hostAddress} ***")
                    ConnectionTrace.transition(
                        ConnectionTrace.Step.MOTORCYCLE_JOINED_HOTSPOT,
                        "Dash IP=${peer.hostAddress}",
                    )
                    ConnectionTrace.transition(
                        ConnectionTrace.Step.PHONE_IP_DISCOVERED,
                        subnet.localAddress.hostAddress ?: "",
                    )
                    ConnectionTrace.transition(
                        ConnectionTrace.Step.DASH_IP_DISCOVERED,
                        peer.hostAddress ?: "",
                    )
                    ConnectionTrace.transition(
                        ConnectionTrace.Step.NETWORK_AVAILABLE,
                        "Phone=${subnet.localAddress.hostAddress}, Dash=${peer.hostAddress}",
                    )
                    ConnectionState.set(Phase.PXC_CONNECTING, "Establishing Carbit link...")

                    try {
                        pr.start(
                            network = null,
                            gatewayOverride = peer,
                            bindIpOverride = subnet.localAddress,
                        )
                    } catch (e: Exception) {
                        LogBus.log("$TAG prober start failed: ${e.message}")
                        ConnectionTrace.fail(
                            failedStep = ConnectionTrace.Step.PXC_SERVER_10922_BOUND,
                            reason = "PXC socket start failed: ${e.message}",
                            dashIp = peer.hostAddress,
                            port = 10922,
                        )
                        ConnectionState.set(Phase.ERROR, "PXC connection failed")
                    }
                    return@thread
                }

                Thread.sleep(1500L)
            }

            LogBus.log("$TAG [HOTSPOT] Hotspot connection timeout — motorcycle did not join")
            ConnectionTrace.fail(
                failedStep = ConnectionTrace.Step.PHONE_HOTSPOT_ENABLED,
                reason = "Motorcycle did not join Mobile Hotspot within 60s",
            )
            ConnectionState.set(Phase.ERROR, "Motorcycle did not join hotspot")
        }
    }

    private fun connectP2p(context: Context, qr: QrData, pr: EasyConnProber) {
        LogBus.log("$TAG Starting Wi-Fi Direct (P2P) flow for MAC=${qr.mac}...")
        ConnectionTrace.transition(ConnectionTrace.Step.P2P_DISCOVERY_STARTED, "mac=${qr.mac}")

        BikeWifiP2p.connect(
            context = context,
            qr = qr,
            onConnected = { bindIp, gatewayIp ->
                WifiGate.cancelNotification(context)
                LogBus.log("$TAG P2P connected: phone=${bindIp.hostAddress} dash=${gatewayIp.hostAddress}")
                ConnectionState.set(Phase.PXC_CONNECTING, "Establishing Carbit link...")

                try {
                    pr.start(
                        network = null,
                        gatewayOverride = gatewayIp,
                        bindIpOverride = bindIp,
                    )
                } catch (e: Exception) {
                    LogBus.log("$TAG prober start failed: ${e.message}")
                    ConnectionTrace.fail(
                        failedStep = ConnectionTrace.Step.PXC_SERVER_10922_BOUND,
                        reason = "PXC socket start failed: ${e.message}",
                        dashIp = gatewayIp.hostAddress,
                        port = 10922,
                    )
                    ConnectionState.set(Phase.ERROR, "PXC connection failed")
                }
            },
            onFailed = { reason ->
                LogBus.log("$TAG P2P failed: $reason")
                if (qr.pwd.isNotEmpty() && !qr.ssid.startsWith("PHONE-HOTSPOT", ignoreCase = true)) {
                    LogBus.log("$TAG falling back to SoftAP connection...")
                    connectSoftAp(context, qr, pr)
                } else {
                    ConnectionTrace.fail(
                        failedStep = ConnectionTrace.Step.P2P_CONNECTION_REQUESTED,
                        reason = "P2P_CONNECTION_FAILED: $reason",
                        dashIp = "192.168.49.1",
                    )
                    ConnectionState.set(Phase.ERROR, "Wi-Fi Direct connection failed")
                }
            },
            log = LogBus::log,
        )
    }

    private fun connectSoftAp(context: Context, qr: QrData, pr: EasyConnProber) {
        LogBus.log("$TAG Starting Wi-Fi SoftAP flow for SSID '${qr.ssid}'...")
        BikeWifi.reuseOrJoin(
            context = context,
            ssid = qr.ssid,
            psk = qr.pwd,
            onAvailable = { network ->
                WifiGate.cancelNotification(context)
                LogBus.log("$TAG SoftAP Wi-Fi bound; starting EasyConn PXC flow...")
                ConnectionState.set(Phase.PXC_CONNECTING, "Establishing Carbit link...")

                try {
                    pr.start(network ?: BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    LogBus.log("$TAG prober start failed: ${e.message}")
                    ConnectionTrace.fail(
                        failedStep = ConnectionTrace.Step.PXC_SERVER_10922_BOUND,
                        reason = "PXC socket start failed: ${e.message}",
                        port = 10922,
                    )
                    ConnectionState.set(Phase.ERROR, "PXC connection failed")
                }
            },
            onLost = {
                LogBus.log("$TAG bike network lost")
                ConnectionState.set(Phase.STOPPED)
            },
            log = LogBus::log,
        )
    }
}
