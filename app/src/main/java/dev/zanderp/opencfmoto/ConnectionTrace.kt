// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Handly / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/**
 * Complete connection state machine logging every stage of the real motorcycle connection:
 *
 *   APP_STARTED
 *   PERMISSIONS_READY
 *   QR_AVAILABLE
 *   QR_PARSED
 *   P2P_DISCOVERY_STARTED
 *   P2P_DEVICE_FOUND
 *   P2P_CONNECTION_STARTED
 *   P2P_CONNECTED
 *   NETWORK_AVAILABLE
 *   DASH_IP_DISCOVERED
 *   PXC_SOCKET_OPEN
 *   PXC_HANDSHAKE_STARTED
 *   CLIENT_INFO_SENT
 *   CLIENT_INFO_RESPONSE_RECEIVED
 *   NOTIFY_RECEIVED
 *   NOTIFY_ACK_SENT
 *   HEARTBEAT_STARTED
 *   MEDIA_CHANNEL_OPEN
 *   PROJECTION_READY
 *   CONNECTED
 */
object ConnectionTrace {

    enum class Step(val label: String) {
        APP_STARTED("APP_STARTED"),
        PERMISSIONS_READY("PERMISSIONS_READY"),
        QR_AVAILABLE("QR_AVAILABLE"),
        QR_PARSED("QR_PARSED"),
        P2P_DISCOVERY_STARTED("P2P_DISCOVERY_STARTED"),
        P2P_DEVICE_FOUND("P2P_DEVICE_FOUND"),
        P2P_CONNECTION_REQUESTED("P2P_CONNECTION_REQUESTED"),
        P2P_CONNECTED("P2P_CONNECTED"),
        NETWORK_AVAILABLE("NETWORK_AVAILABLE"),
        DASH_IP_DISCOVERED("DASH_IP_DISCOVERED"),
        PHONE_IP_DISCOVERED("PHONE_IP_DISCOVERED"),
        PXC_SERVER_10920_BOUND("PXC_SERVER_10920_BOUND"),
        PXC_SERVER_10921_BOUND("PXC_SERVER_10921_BOUND"),
        PXC_SERVER_10922_BOUND("PXC_SERVER_10922_BOUND"),
        EASYCONN_DISCOVERY_STARTED("EASYCONN_DISCOVERY_STARTED"),
        EASYCONN_NSD_RESULT("EASYCONN_NSD_RESULT"),
        EASYCONN_10930_RESULT("EASYCONN_10930_RESULT"),
        MDNS_PROBE_SENT("MDNS_PROBE_SENT"),
        MDNS_PROBE_RESPONSE("MDNS_PROBE_RESPONSE"),
        BIKE_CALLBACK_10922("BIKE_CALLBACK_10922"),
        BIKE_CALLBACK_10921("BIKE_CALLBACK_10921"),
        BIKE_CALLBACK_10920("BIKE_CALLBACK_10920"),
        PXC_HANDSHAKE_STARTED("PXC_HANDSHAKE_STARTED"),
        CLIENT_INFO_SENT("CLIENT_INFO_SENT"),
        CLIENT_INFO_RECEIVED("CLIENT_INFO_RECEIVED"),
        NOTIFY_RECEIVED("NOTIFY_RECEIVED"),
        NOTIFY_ACK_SENT("NOTIFY_ACK_SENT"),
        HANDSHAKE_COMPLETE("HANDSHAKE_COMPLETE"),
        HEARTBEAT_STARTED("HEARTBEAT_STARTED"),
        MEDIA_CHANNEL_OPEN("MEDIA_CHANNEL_OPEN"),
        PROJECTION_READY("PROJECTION_READY"),
        CONNECTED("CONNECTED"),
        FAILED("FAILED"),
    }

    data class TraceEntry(
        val step: Step,
        val timestampMs: Long,
        val formattedTime: String,
        val detail: String = "",
    )

    data class FailureDetail(
        val failedStep: Step,
        val reason: String,
        val dashIp: String? = null,
        val port: Int? = null,
        val elapsedMs: Long? = null,
    )

    @Volatile var currentStep: Step = Step.APP_STARTED
        private set
    @Volatile var lastFailure: FailureDetail? = null
        private set

    val lastFailureReason: String? get() = lastFailure?.reason

    private val entries = LinkedList<TraceEntry>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var connectStartTime: Long = 0L

    @Volatile var listener: (() -> Unit)? = null

    @Synchronized
    fun transition(step: Step, detail: String = "") {
        val now = System.currentTimeMillis()
        val formatted = timeFmt.format(Date(now))
        currentStep = step

        if (step == Step.APP_STARTED || step == Step.P2P_DISCOVERY_STARTED || step == Step.QR_PARSED) {
            if (connectStartTime == 0L) connectStartTime = now
        }

        if (entries.size >= 120) entries.removeFirst()
        entries.addLast(TraceEntry(step, now, formatted, detail))

        LogBus.log("[TRACE] $step" + if (detail.isNotBlank()) " ($detail)" else "")
        DiagnosticsStore.recordEvent("Trace: ${step.label}" + if (detail.isNotBlank()) " - $detail" else "")
        notifyChange()
    }

    @Synchronized
    fun fail(
        failedStep: Step,
        reason: String,
        dashIp: String? = null,
        port: Int? = null,
    ) {
        val now = System.currentTimeMillis()
        val elapsed = if (connectStartTime > 0L) now - connectStartTime else null
        lastFailure = FailureDetail(
            failedStep = failedStep,
            reason = reason,
            dashIp = dashIp,
            port = port,
            elapsedMs = elapsed,
        )

        val detail = "FAILED_AT = ${failedStep.label} | Reason: $reason" +
            (if (dashIp != null) " | IP: $dashIp" else "") +
            (if (port != null) " | Port: $port" else "") +
            (if (elapsed != null) " | Elapsed: ${elapsed}ms" else "")

        transition(Step.FAILED, detail)
    }

    fun formattedTimingSummary(): String {
        val now = System.currentTimeMillis()
        val elapsed = if (connectStartTime > 0L) now - connectStartTime else 0L
        return "Connection timing: ${elapsed}ms total elapsed (Current: ${currentStep.label})"
    }

    @Synchronized
    fun getHistory(): List<TraceEntry> = ArrayList(entries)

    @Synchronized
    fun reset() {
        connectStartTime = 0L
        lastFailure = null
    }

    private fun notifyChange() {
        try {
            listener?.invoke()
        } catch (_: Exception) {}
    }
}
