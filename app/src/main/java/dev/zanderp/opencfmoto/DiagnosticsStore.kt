// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Motorcycle Link / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/**
 * Aggregates real-time telemetry from the connection, transport, PXC protocol, video pipeline,
 * and input systems for display on the Diagnostics screen and export in sanitized support reports.
 */
object DiagnosticsStore {

    data class ConnectionInfo(
        val status: String = "DISCONNECTED",
        val dashName: String = "Unavailable",
        val huid: String = "Unavailable",
        val channel: String = "Unavailable",
        val sware: String = "Unavailable",
        val hware: String = "Unavailable",
        val sdkVersion: String = "Unavailable",
        val packageName: String = "Unavailable",
        val versionName: String = "Unavailable",
    )

    data class NetworkInfo(
        val transport: String = "Unavailable",
        val wifiState: String = "DISCONNECTED",
        val phoneIp: String = "Unavailable",
        val dashIp: String = "Unavailable",
        val gateway: String = "Unavailable",
        val networkBinding: String = "Unavailable",
        val latencyMs: Long? = null,
    )

    data class PxcInfo(
        val protocolVersion: String = "1.0.2",
        val sessionState: String = "IDLE",
        val handshakeState: String = "IDLE",
        val heartbeatState: String = "IDLE",
        val rxPackets: Long = 0L,
        val txPackets: Long = 0L,
        val rxBytes: Long = 0L,
        val txBytes: Long = 0L,
        val lastPacketTimeMs: Long? = null,
    )

    data class MediaInfo(
        val state: String = "IDLE",
        val resolution: String = "Unavailable",
        val fps: Double = 0.0,
        val bitrateBps: Long = 0L,
        val droppedFrames: Long = 0L,
        val port: Int? = null,
    )

    data class InputInfo(
        val touchState: String = "AVAILABLE",
        val handlebarState: String = "AVAILABLE",
        val lastEventType: String = "None",
        val lastEventDetail: String = "None",
        val lastEventTimeMs: Long? = null,
        val totalEvents: Long = 0L,
    )

    data class LiveEvent(
        val timestamp: String,
        val description: String,
    )

    @Volatile var connection: ConnectionInfo = ConnectionInfo()
        private set
    @Volatile var network: NetworkInfo = NetworkInfo()
        private set
    @Volatile var pxc: PxcInfo = PxcInfo()
        private set
    @Volatile var media: MediaInfo = MediaInfo()
        private set
    @Volatile var input: InputInfo = InputInfo()
        private set

    private val maxEvents = 50
    private val events = LinkedList<LiveEvent>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile var listener: (() -> Unit)? = null

    @Synchronized
    fun recordEvent(description: String) {
        val now = timeFmt.format(Date())
        val safeDesc = LogRedactor.redact(description)
        if (events.size >= maxEvents) {
            events.removeFirst()
        }
        events.addLast(LiveEvent(now, safeDesc))
        notifyChange()
    }

    @Synchronized
    fun getRecentEvents(): List<LiveEvent> = ArrayList(events)

    fun updateConnection(
        status: String? = null,
        meta: ConnectionState.DashMetadata? = null,
    ) {
        val current = connection
        connection = current.copy(
            status = status ?: current.status,
            dashName = meta?.huName?.ifBlank { null } ?: current.dashName,
            huid = meta?.huid?.ifBlank { null } ?: current.huid,
            channel = meta?.channel?.ifBlank { null } ?: current.channel,
            sware = meta?.sware?.ifBlank { null } ?: current.sware,
            hware = meta?.hware?.ifBlank { null } ?: current.hware,
            sdkVersion = meta?.sdkVersion?.ifBlank { null } ?: current.sdkVersion,
            packageName = meta?.packageName?.ifBlank { null } ?: current.packageName,
            versionName = meta?.versionName?.ifBlank { null } ?: current.versionName,
        )
        notifyChange()
    }

    fun updateNetwork(
        transport: String? = null,
        wifiState: String? = null,
        phoneIp: String? = null,
        dashIp: String? = null,
        gateway: String? = null,
        networkBinding: String? = null,
        latencyMs: Long? = null,
    ) {
        val current = network
        network = current.copy(
            transport = transport ?: current.transport,
            wifiState = wifiState ?: current.wifiState,
            phoneIp = phoneIp ?: current.phoneIp,
            dashIp = dashIp ?: current.dashIp,
            gateway = gateway ?: current.gateway,
            networkBinding = networkBinding ?: current.networkBinding,
            latencyMs = latencyMs ?: current.latencyMs,
        )
        notifyChange()
    }

    fun updatePxc(
        sessionState: String? = null,
        handshakeState: String? = null,
        heartbeatState: String? = null,
        addRxPackets: Long = 0L,
        addTxPackets: Long = 0L,
        addRxBytes: Long = 0L,
        addTxBytes: Long = 0L,
    ) {
        val current = pxc
        val now = System.currentTimeMillis()
        pxc = current.copy(
            sessionState = sessionState ?: current.sessionState,
            handshakeState = handshakeState ?: current.handshakeState,
            heartbeatState = heartbeatState ?: current.heartbeatState,
            rxPackets = current.rxPackets + addRxPackets,
            txPackets = current.txPackets + addTxPackets,
            rxBytes = current.rxBytes + addRxBytes,
            txBytes = current.txBytes + addTxBytes,
            lastPacketTimeMs = if (addRxPackets > 0 || addTxPackets > 0) now else current.lastPacketTimeMs,
        )
        notifyChange()
    }

    fun updateMedia(
        state: String? = null,
        resolution: String? = null,
        fps: Double? = null,
        bitrateBps: Long? = null,
        droppedFrames: Long? = null,
        port: Int? = null,
    ) {
        val current = media
        media = current.copy(
            state = state ?: current.state,
            resolution = resolution ?: current.resolution,
            fps = fps ?: current.fps,
            bitrateBps = bitrateBps ?: current.bitrateBps,
            droppedFrames = droppedFrames ?: current.droppedFrames,
            port = port ?: current.port,
        )
        notifyChange()
    }

    fun updateInput(
        touchState: String? = null,
        handlebarState: String? = null,
        lastEventType: String? = null,
        lastEventDetail: String? = null,
        incrementCount: Boolean = false,
    ) {
        val current = input
        val now = System.currentTimeMillis()
        input = current.copy(
            touchState = touchState ?: current.touchState,
            handlebarState = handlebarState ?: current.handlebarState,
            lastEventType = lastEventType ?: current.lastEventType,
            lastEventDetail = lastEventDetail ?: current.lastEventDetail,
            lastEventTimeMs = if (lastEventType != null) now else current.lastEventTimeMs,
            totalEvents = if (incrementCount) current.totalEvents + 1 else current.totalEvents,
        )
        notifyChange()
    }

    private fun notifyChange() {
        try {
            listener?.invoke()
        } catch (_: Exception) {
        }
    }

    private val sessionStartTime = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())

    val sessionId: String get() = "Session: $sessionStartTime"

    /**
     * Generates a fully sanitized diagnostic report for support/export.
     * All sensitive tokens, Wi-Fi keys, and GPS entries are redacted.
     */
    fun exportSanitizedReport(ctx: Context): String = buildString {
        appendLine("==========================================")
        appendLine("OPEN MOTORCYCLE LINK - DIAGNOSTIC REPORT")
        appendLine("==========================================")
        appendLine(sessionId)
        appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("App Version: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        appendLine("Git Hash: ${BuildConfig.GIT_HASH}")
        appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Device Model: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine()

        appendLine("[CONNECTION PERFORMANCE & TIMINGS]")
        appendLine(ConnectionTrace.formattedTimingSummary())
        appendLine("Current Step:    ${ConnectionTrace.currentStep}")
        if (ConnectionTrace.lastFailureReason != null) {
            appendLine("Failure Detail:  ${ConnectionTrace.lastFailureReason}")
        }
        appendLine()

        appendLine("[CONNECTION TRACE TIMELINE]")
        val traceHistory = ConnectionTrace.getHistory()
        if (traceHistory.isEmpty()) {
            appendLine("  (No connection trace recorded)")
        } else {
            for (t in traceHistory) {
                appendLine("  ${t.formattedTime}  ${t.step.label}${if (t.detail.isNotBlank()) " — ${t.detail}" else ""}")
            }
        }
        appendLine()

        appendLine("[CONNECTION METADATA]")
        appendLine("Status:          ${connection.status}")
        appendLine("Dashboard Name:  ${connection.dashName}")
        appendLine("HUID:            ${connection.huid}")
        appendLine("Channel:         ${connection.channel}")
        appendLine("Software (Sware):${connection.sware}")
        appendLine("Hardware (Hware):${connection.hware}")
        appendLine("SDK Version:     ${connection.sdkVersion}")
        appendLine("Package Name:    ${connection.packageName}")
        appendLine("Version Name:    ${connection.versionName}")
        appendLine()

        appendLine("[NETWORK]")
        appendLine("Transport:       ${network.transport}")
        appendLine("Wi-Fi State:     ${network.wifiState}")
        appendLine("Phone IP:        ${network.phoneIp}")
        appendLine("Dash IP:         ${network.dashIp}")
        appendLine("Gateway:         ${network.gateway}")
        appendLine("Network Binding: ${network.networkBinding}")
        appendLine("Latency:         ${network.latencyMs?.let { "${it} ms" } ?: "Unavailable"}")
        appendLine()

        appendLine("[PXC PROTOCOL]")
        appendLine("Protocol:        ${pxc.protocolVersion}")
        appendLine("Session State:   ${pxc.sessionState}")
        appendLine("Handshake:       ${pxc.handshakeState}")
        appendLine("Heartbeat:       ${pxc.heartbeatState}")
        appendLine("RX Packets:      ${pxc.rxPackets}")
        appendLine("TX Packets:      ${pxc.txPackets}")
        appendLine("RX Bytes:        ${pxc.rxBytes}")
        appendLine("TX Bytes:        ${pxc.txBytes}")
        appendLine("Last Packet:     ${pxc.lastPacketTimeMs?.let { "${System.currentTimeMillis() - it} ms ago" } ?: "Never"}")
        appendLine()

        appendLine("[MEDIA / VIDEO]")
        appendLine("State:           ${media.state}")
        appendLine("Resolution:      ${media.resolution}")
        appendLine("Frame Rate:      ${String.format(Locale.US, "%.1f fps", media.fps)}")
        appendLine("Bitrate:         ${String.format(Locale.US, "%.2f Mbps", media.bitrateBps.toDouble() / 1_000_000.0)}")
        appendLine("Dropped Frames:  ${media.droppedFrames}")
        appendLine("Port:            ${media.port ?: "Unavailable"}")
        appendLine()

        appendLine("[INPUT & CONTROLS]")
        appendLine("Touch:           ${input.touchState}")
        appendLine("Handlebar:       ${input.handlebarState}")
        appendLine("Total Events:    ${input.totalEvents}")
        appendLine("Last Event:      ${input.lastEventType} - ${input.lastEventDetail}")
        appendLine()

        appendLine("[LIVE EVENT TIMELINE]")
        val evs = getRecentEvents()
        if (evs.isEmpty()) {
            appendLine("  (No recent events recorded)")
        } else {
            for (ev in evs) {
                appendLine("  ${ev.timestamp}  ${ev.description}")
            }
        }
        appendLine()

        appendLine("[RECENT LOGS]")
        appendLine(LogRedactor.redact(LogBus.snapshot().takeLast(32 * 1024)))
        appendLine("==========================================")
    }
}
