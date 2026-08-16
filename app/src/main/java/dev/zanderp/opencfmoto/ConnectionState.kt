package dev.zanderp.opencfmoto

import androidx.annotation.StringRes

/**
 * Coarse, process-global view of what the app is currently doing, so the UI can show a single
 * human-readable status line (instead of making users read the raw log) and so the reconnect
 * logic ([EasyConnProber] / [MainActivity]) can react to link drops.
 *
 * Lives as a process global like [AaVideoBridge] / [BikeProfileHolder]: the foreground service,
 * the prober, and the activity all publish transitions here and the activity observes them.
 *
 * [labelRes] is what the UI shows (localized). [logLabel] stays English for shared logs / support.
 */
enum class Phase(@StringRes val labelRes: Int, val logLabel: String, val busy: Boolean) {
    IDLE(R.string.conn_ready, "Ready", false),
    STARTING_AA(R.string.conn_starting_aa, "Starting Android Auto…", true),
    AA_VIDEO_LIVE(R.string.conn_aa_video_live, "Android Auto live — joining bike…", true),
    JOINING_WIFI(R.string.conn_joining_wifi, "Connecting to bike Wi-Fi…", true),
    PXC_CONNECTING(R.string.conn_pxc_connecting, "Linking to dashboard…", true),
    STREAMING(R.string.conn_streaming, "Connected — projecting to dash", false),
    MIRRORING(R.string.conn_mirroring, "Mirroring screen to dash", false),
    RECONNECTING(R.string.conn_reconnecting, "Link dropped — reconnecting…", true),
    WAITING_FOR_BIKE(R.string.conn_waiting_for_bike, "Bike out of range — waiting…", true),
    STOPPED(R.string.conn_stopped, "Stopped", false),
    ERROR(R.string.conn_error, "Error — see logs", false),
}

object ConnectionState {
    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    /** Extra detail appended to the phase label (e.g. bike name, retry count). */
    @Volatile
    var detail: String = ""
        private set

    data class DashMetadata(
        val huid: String = "",
        val huName: String = "",
        val channel: String = "",
        val sware: String = "",
        val hware: String = "",
        val sdkVersion: String = "",
        val packageName: String = "",
        val versionName: String = "",
    )

    @Volatile
    var dashMetadata: DashMetadata = DashMetadata()
        private set

    fun updateDashMetadata(meta: DashMetadata) {
        dashMetadata = meta
        DiagnosticsStore.updateConnection(meta = meta)
        DiagnosticsStore.recordEvent("Dashboard metadata updated: ${meta.huName.ifBlank { meta.huid }} (Sware: ${meta.sware}, Hware: ${meta.hware})")
    }

    /** Observer (MainActivity) — receives (phase, detail) on every transition, on any thread. */
    @Volatile
    var listener: ((Phase, String) -> Unit)? = null

    /**
     * Move to [newPhase]. Pass [newDetail] to change the trailing detail, or leave it null to keep
     * the existing detail (e.g. the bike name set when the connection started, so a background
     * component can flip to [Phase.STREAMING] without knowing which bike it is).
     */
    fun set(newPhase: Phase, newDetail: String? = null) {
        phase = newPhase
        if (newDetail != null) detail = newDetail
        val d = detail
        val text = if (d.isBlank()) newPhase.logLabel else "${newPhase.logLabel} — $d"
        LogBus.log("[state] $text")
        DiagnosticsStore.updateConnection(status = newPhase.logLabel)
        DiagnosticsStore.recordEvent("Connection phase: $text")

        when (newPhase) {
            Phase.IDLE, Phase.STOPPED -> ConnectionTrace.transition(ConnectionTrace.Step.APP_STARTED)
            Phase.STARTING_AA -> ConnectionTrace.transition(ConnectionTrace.Step.QR_AVAILABLE, d)
            Phase.JOINING_WIFI -> ConnectionTrace.transition(ConnectionTrace.Step.P2P_CONNECTION_STARTED, d)
            Phase.AA_VIDEO_LIVE -> ConnectionTrace.transition(ConnectionTrace.Step.P2P_CONNECTED, d)
            Phase.PXC_CONNECTING -> ConnectionTrace.transition(ConnectionTrace.Step.PXC_SOCKET_OPEN, d)
            Phase.STREAMING, Phase.MIRRORING -> ConnectionTrace.transition(ConnectionTrace.Step.CONNECTED, d)
            Phase.ERROR -> ConnectionTrace.fail(ConnectionTrace.currentStep, if (d.isNotBlank()) d else "Unexpected error")
            else -> {}
        }

        try {
            listener?.invoke(newPhase, d)
        } catch (_: Exception) {
        }
    }
}
