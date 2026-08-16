// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Handly / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context

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
        ConnectionState.set(Phase.JOINING_WIFI, bikeName)

        val pr = EasyConnProber(appCtx, LogBus::log)
        prober = pr
        BikeLink.prober = pr

        val hasMac = !qr.mac.isNullOrBlank()
        val isCarbitEcDevice = qr.ssid.startsWith("EC_", ignoreCase = true) ||
            qr.name?.startsWith("EC_", ignoreCase = true) == true
        val preferP2pByMac = hasMac && (qr.supportsP2p || isCarbitEcDevice || qr.pwd.isEmpty())

        if (preferP2pByMac) {
            connectP2p(appCtx, qr, pr)
        } else {
            connectSoftAp(appCtx, qr, pr)
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

    private fun connectP2p(context: Context, qr: QrData, pr: EasyConnProber) {
        LogBus.log("$TAG Starting Wi-Fi Direct (P2P) flow for MAC=${qr.mac}...")
        ConnectionTrace.transition(ConnectionTrace.Step.P2P_DISCOVERY_STARTED, "mac=${qr.mac}")

        BikeWifiP2p.connect(
            context = context,
            qr = qr,
            onConnected = { bindIp, gatewayIp ->
                WifiGate.cancelNotification(context)
                LogBus.log("$TAG P2P connected: phone=${bindIp.hostAddress} dash=${gatewayIp.hostAddress}")
                ConnectionState.set(Phase.PXC_CONNECTING)
                ConnectionTrace.transition(ConnectionTrace.Step.PXC_SOCKET_OPEN, "${gatewayIp.hostAddress}:10922")
                ConnectionTrace.transition(ConnectionTrace.Step.PXC_HANDSHAKE_STARTED)

                try {
                    pr.start(
                        network = null,
                        gatewayOverride = gatewayIp,
                        bindIpOverride = bindIp,
                    )
                } catch (e: Exception) {
                    LogBus.log("$TAG prober start failed: ${e.message}")
                    ConnectionTrace.fail(
                        failedStep = ConnectionTrace.Step.PXC_SOCKET_OPEN,
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
                        failedStep = ConnectionTrace.Step.P2P_CONNECTION_STARTED,
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
                ConnectionState.set(Phase.PXC_CONNECTING)
                ConnectionTrace.transition(ConnectionTrace.Step.PXC_SOCKET_OPEN, ":10922")
                ConnectionTrace.transition(ConnectionTrace.Step.PXC_HANDSHAKE_STARTED)

                try {
                    pr.start(network ?: BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    LogBus.log("$TAG prober start failed: ${e.message}")
                    ConnectionTrace.fail(
                        failedStep = ConnectionTrace.Step.PXC_SOCKET_OPEN,
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
