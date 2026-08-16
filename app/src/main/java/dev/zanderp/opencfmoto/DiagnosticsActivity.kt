// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Motorcycle Link / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.util.Locale

/**
 * Real-time diagnostics screen displaying actual telemetry across:
 * Connection Metadata, Network Transport, PXC Protocol, Media / Video, Input, and Live Events.
 * Includes a sanitized diagnostic report export function.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var connText: TextView
    private lateinit var networkText: TextView
    private lateinit var pxcText: TextView
    private lateinit var mediaText: TextView
    private lateinit var inputText: TextView
    private lateinit var eventsText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_diagnostics)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.diagnostics_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        connText = findViewById(R.id.diag_conn_text)
        networkText = findViewById(R.id.diag_network_text)
        pxcText = findViewById(R.id.diag_pxc_text)
        mediaText = findViewById(R.id.diag_media_text)
        inputText = findViewById(R.id.diag_input_text)
        eventsText = findViewById(R.id.diag_events_text)

        findViewById<ImageView>(R.id.btn_diag_back).setOnClickListener {
            finish()
        }

        findViewById<MaterialButton>(R.id.btn_diag_export).setOnClickListener {
            exportDiagnostics()
        }

        DiagnosticsStore.listener = {
            handler.post { render() }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (DiagnosticsStore.listener != null) {
            DiagnosticsStore.listener = null
        }
    }

    private fun render() {
        val c = DiagnosticsStore.connection
        val n = DiagnosticsStore.network
        val p = DiagnosticsStore.pxc
        val m = DiagnosticsStore.media
        val i = DiagnosticsStore.input
        val evs = DiagnosticsStore.getRecentEvents()

        connText.text = buildString {
            val fail = ConnectionTrace.lastFailure
            if (fail != null) {
                appendLine("=== LAST FAILURE ===")
                appendLine("Stage:      ${fail.failedStep.label}")
                appendLine("Reason:     ${fail.reason}")
                if (fail.dashIp != null) appendLine("Dash IP:    ${fail.dashIp}")
                if (fail.port != null) appendLine("Port:       ${fail.port}")
                if (fail.elapsedMs != null) appendLine("Elapsed:    ${fail.elapsedMs} ms")
                appendLine("====================")
            }
            appendLine("Status:     ${c.status}")
            appendLine("Dashboard:  ${c.dashName}")
            appendLine("HUID:       ${c.huid}")
            appendLine("Channel:    ${c.channel}")
            appendLine("Software:   ${c.sware}")
            appendLine("Hardware:   ${c.hware}")
            appendLine("SDK:        ${c.sdkVersion}")
            appendLine("Package:    ${c.packageName}")
            append("Version:    ${c.versionName}")
        }

        networkText.text = buildString {
            appendLine("Transport:  ${n.transport}")
            appendLine("Wi-Fi:      ${n.wifiState}")
            appendLine("Phone IP:   ${n.phoneIp}")
            appendLine("Dash IP:    ${n.dashIp}")
            appendLine("Gateway:    ${n.gateway}")
            appendLine("Binding:    ${n.networkBinding}")
            append("Latency:    ${n.latencyMs?.let { "${it} ms" } ?: "Unavailable"}")
        }

        pxcText.text = buildString {
            appendLine("Protocol:   ${p.protocolVersion}")
            appendLine("Session:    ${p.sessionState}")
            appendLine("Handshake:  ${p.handshakeState}")
            appendLine("Heartbeat:  ${p.heartbeatState}")
            appendLine("RX Packets: ${p.rxPackets} (${formatBytes(p.rxBytes)})")
            appendLine("TX Packets: ${p.txPackets} (${formatBytes(p.txBytes)})")
            append("Last Packet:${p.lastPacketTimeMs?.let { "${System.currentTimeMillis() - it} ms ago" } ?: "Never"}")
        }

        mediaText.text = buildString {
            appendLine("State:      ${m.state}")
            appendLine("Resolution: ${m.resolution}")
            appendLine("Frame Rate: ${String.format(Locale.US, "%.1f fps", m.fps)}")
            appendLine("Bitrate:    ${String.format(Locale.US, "%.2f Mbps", m.bitrateBps.toDouble() / 1_000_000.0)}")
            appendLine("Dropped:    ${m.droppedFrames} frames")
            append("Port:       ${m.port?.toString() ?: "Unavailable"}")
        }

        inputText.text = buildString {
            appendLine("Touch:      ${i.touchState}")
            appendLine("Handlebar:  ${i.handlebarState}")
            appendLine("Total Evts: ${i.totalEvents}")
            append("Last Event: ${i.lastEventType} - ${i.lastEventDetail}")
        }

        if (evs.isEmpty()) {
            eventsText.text = "(No recent events recorded)"
        } else {
            eventsText.text = evs.takeLast(15).joinToString("\n") { "${it.timestamp}  ${it.description}" }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun exportDiagnostics() {
        try {
            val report = DiagnosticsStore.exportSanitizedReport(this)
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, report)
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, "Export Sanitized Diagnostics")
            startActivity(shareIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, DiagnosticsActivity::class.java))
        }
    }
}
