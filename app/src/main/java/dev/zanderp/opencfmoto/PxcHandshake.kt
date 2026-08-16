package dev.zanderp.opencfmoto

import org.json.JSONObject
import java.net.Socket
import java.util.UUID

/**
 * Server-side PXC control dispatcher. The phone is the SERVER: after we send the
 * ECP_PXC_MDNS_RESPOND probe to the bike, the bike connects back to our listening
 * ports and drives the handshake. We reply per cmd.
 *
 * Verified against cfmoto-tcp-v5.log (official app, standard Car PXC, JSON CLIENT_INFO):
 *   bike 0x10000 (CAR_CTRL select)  -> we 0x10001
 *   bike 0x20000 (CAR_DATA select)  -> we 0x20001
 *   bike 0x10010 CLIENT_INFO (JSON) -> we 0x10011 (our info + RSA pubkey + signed HUID)
 *   bike 0x10690 {usbSpeed,wifiSpeed} -> we 0x10691
 *   bike 0x103e0 {client_set,sn}    -> we 0x103e1, then 0x201c0 CHECK_SN_RESULT {isOk:true}
 *   bike 0x70000000 heartbeat        -> we 0x70000001
 */
class PxcHandshake(
    private val log: (String) -> Unit,
) {
    private val phoneUuid: String = UUID.randomUUID().toString()
    @Volatile var carHuid: String? = null
        private set
    @Volatile var lastClientInfo: JSONObject? = null
        private set
    /** Dashboard-variant strategy, selected from the bike's CLIENT_INFO. Read by the media plane too
     *  (via EasyConnProber.handshake.profile). Written once on the control thread, before any media
     *  socket exists. */
    @Volatile var profile: BikeProfile = BikeProfiles.legacy
        private set
    /** Rate-limit HU_TIME_SYNC log lines (bike sends ~every 2s). */
    @Volatile private var lastHuTimeSyncLogAt: Long = 0L
    @Volatile private var huTimeSyncCount: Int = 0
    /** X-Cape / Voge / Griffin never send 0x10600 — push one phone stamp after handshake. */
    @Volatile private var pushedHuTime: Boolean = false

    /**
     * Called when the bike selects a PXC channel on a :10922 socket (CAR_CTRL or CAR_DATA).
     * [EasyConnProber] starts a proactive 0x70000000 heartbeat on **each** of those sockets —
     * the 800NK (sdk 0.9.23.x) tears the whole session down after ~7s of silence on either one.
     */
    @Volatile var onPxcChannelSelected: ((Socket, String) -> Unit)? = null

    /** Dispatch one inbound frame on a given socket (ctrl or media). */
    fun handle(tag: String, frame: PxcFrame, socket: Socket) {
        val out = socket.getOutputStream()
        when (frame.cmd) {
            PxcFrame.CMD_CHANNEL_CAR_CTRL -> {
                log("[$tag] bike selected CAR_CTRL (0x10000) → ack 0x10001")
                DiagnosticsStore.updatePxc(sessionState = "CAR_CTRL ACTIVE")
                PxcFrame(PxcFrame.CMD_CHANNEL_CAR_CTRL + 1, ByteArray(0)).write(out)
                onPxcChannelSelected?.invoke(socket, "CAR_CTRL")
            }
            PxcFrame.CMD_CHANNEL_CAR_DATA -> {
                log("[$tag] bike selected CAR_DATA (0x20000) → ack 0x20001")
                DiagnosticsStore.updatePxc(sessionState = "CAR_DATA ACTIVE")
                PxcFrame(PxcFrame.CMD_CHANNEL_CAR_DATA + 1, ByteArray(0)).write(out)
                onPxcChannelSelected?.invoke(socket, "CAR_DATA")
            }
            PxcFrame.CMD_CLIENT_INFO -> {
                DiagnosticsStore.updatePxc(handshakeState = "CLIENT_INFO RECEIVED")
                ConnectionTrace.transition(ConnectionTrace.Step.CLIENT_INFO_SENT, "version=2, supportFunction=128")
                onClientInfo(tag, frame, out)
            }
            PxcFrame.CMD_QUERY_SPEED -> {
                log("[$tag] QUERY_SPEED ${frame.payload.asText()} → reply 0x10691")
                ConnectionTrace.transition(ConnectionTrace.Step.NOTIFY_RECEIVED, "QUERY_SPEED (0x10690)")
                PxcFrame(PxcFrame.CMD_QUERY_SPEED_RLY, ByteArray(0)).write(out)
                ConnectionTrace.transition(ConnectionTrace.Step.NOTIFY_ACK_SENT, "QUERY_SPEED_RLY (0x10691)")
            }
            PxcFrame.CMD_CHECK_SN -> {
                DiagnosticsStore.updatePxc(handshakeState = "COMPLETE")
                onCheckSn(tag, frame, out)
            }
            PxcFrame.CMD_HEARTBEAT -> {
                DiagnosticsStore.updatePxc(heartbeatState = "ACTIVE (RX)")
                ConnectionTrace.transition(ConnectionTrace.Step.HEARTBEAT_STARTED, "0x70000000 (RX)")
                PxcFrame(PxcFrame.CMD_HEARTBEAT_ACK, ByteArray(0)).write(out)
            }
            PxcFrame.CMD_HEARTBEAT_ACK -> {
                DiagnosticsStore.updatePxc(heartbeatState = "ACTIVE (ACK)")
            }
            PxcFrame.CMD_CHECK_SN_RESULT + 1 -> {
                // acks from the bike — nothing to do
            }
            // First-class: never empty-ack 0x10600 (→ 1970 / 00:00 on Morini/Voge/QJ).
            // Handled here so it cannot regress via profile / uncommitted-only paths.
            PxcFrame.CMD_HU_TIME_SYNC -> onHuTimeSync(tag, frame, out)
            PxcFrame.CMD_HU_QUERY_TIME -> onHuQueryTime(tag, frame, out)
            else -> {
                ConnectionTrace.transition(
                    ConnectionTrace.Step.NOTIFY_RECEIVED,
                    "0x${frame.cmd.toUInt().toString(16)} (${PxcFrame.nameOf(frame.cmd)})",
                )
                if (!profile.handleUnknownControl(tag, frame, out, log)) {
                    log("[$tag] cmd=0x${frame.cmd.toUInt().toString(16)} (${PxcFrame.nameOf(frame.cmd)}) " +
                        "len=${frame.payload.size} ${frame.payload.asText()}")
                }
            }
        }
    }

    private fun onHuQueryTime(tag: String, frame: PxcFrame, out: java.io.OutputStream) {
        val ack = HuQueryTime.ack()
        PxcFrame(PxcFrame.CMD_HU_QUERY_TIME_ACK, ack.payload).write(out)
        log("[$tag] HU_QUERY_TIME (0x10450) len=${frame.payload.size} → 0x10451 dateTime=${ack.dateTime}")
        ConnectionTrace.transition(ConnectionTrace.Step.NOTIFY_ACK_SENT, "0x10451 dateTime=${ack.dateTime}")
        pushPhoneHuTime(tag, out, "after QUERY_TIME")
    }

    private fun pushPhoneHuTime(tag: String, out: java.io.OutputStream, reason: String) {
        if (pushedHuTime) return
        pushedHuTime = true
        val ack = HuTimeSync.ack(ByteArray(0))
        PxcFrame(PxcFrame.CMD_HU_TIME_SYNC_ACK, ack.payload).write(out)
        log("[$tag] HU_TIME_SYNC push ($reason) → 0x10601 mode=${ack.mode} time=${ack.stamp}")
        ConnectionTrace.transition(ConnectionTrace.Step.NOTIFY_ACK_SENT, "0x10601 mode=${ack.mode} time=${ack.stamp}")
    }

    private fun onHuTimeSync(tag: String, frame: PxcFrame, out: java.io.OutputStream) {
        val ack = HuTimeSync.ack(frame.payload)
        PxcFrame(PxcFrame.CMD_HU_TIME_SYNC_ACK, ack.payload).write(out)
        val n = ++huTimeSyncCount
        val now = System.currentTimeMillis()
        if (n <= 3 || now - lastHuTimeSyncLogAt >= 30_000L) {
            lastHuTimeSyncLogAt = now
            log("[$tag] HU_TIME_SYNC #$n len=${frame.payload.size} → ack 0x10601 mode=${ack.mode} time=${ack.stamp}")
        }
        ConnectionTrace.transition(ConnectionTrace.Step.NOTIFY_ACK_SENT, "0x10601 (HU_TIME_SYNC_ACK)")
    }

    private fun onClientInfo(tag: String, frame: PxcFrame, out: java.io.OutputStream) {
        val text = frame.payload.asText()
        log("[$tag] *** CLIENT_INFO from bike *** $text")
        val json = try { JSONObject(text) } catch (e: Exception) {
            log("[$tag] CLIENT_INFO parse failed: $e"); return
        }
        lastClientInfo = json
        carHuid = json.optString("HUID").ifEmpty { json.optString("huid") }.ifEmpty { null }
        val huName = json.optString("HUName")
        val channel = json.optString("channel")
        val sware = json.optString("Sware").ifEmpty { json.optString("sware") }
        val hware = json.optString("Hware").ifEmpty { json.optString("hware") }
        val sdkVersion = json.optString("sdkVersion")
        val pkg = json.optString("package_name").ifEmpty { json.optString("package") }
        val versionName = json.optString("version_name")

        ConnectionTrace.transition(
            ConnectionTrace.Step.CLIENT_INFO_RESPONSE_RECEIVED,
            "HUID=$carHuid, Sware=$sware, Hware=$hware, pkg=$pkg",
        )

        ConnectionState.updateDashMetadata(
            ConnectionState.DashMetadata(
                huid = carHuid ?: "",
                huName = huName,
                channel = channel,
                sware = sware,
                hware = hware,
                sdkVersion = sdkVersion,
                packageName = pkg,
                versionName = versionName,
            )
        )
        log("[$tag] Dash Metadata: carHuid=$carHuid HUName=$huName channel=$channel Sware=$sware Hware=$hware sdk=$sdkVersion pkg=$pkg version=$versionName")

        profile = BikeProfiles.select(json, log)
        val early = BikeProfileHolder.active
        val startedSpec = BikeProfileHolder.aaVideo  // what Android Auto actually started / negotiated with
        // Keep a measured landscape panel (e.g. 800MT 1280×576) when CLIENT_INFO scoring would
        // flip to the near-square 800NK Advanced profile — that mis-route resized touch/margins
        // mid-session while AA stayed on the landscape stream (connect/drop flaps in field logs).
        val earlyPanel = early.panelSize
        val earlyIsWide = earlyPanel != null && earlyPanel.first >= earlyPanel.second * 3 / 2
        if (earlyIsWide && profile === Cfdl26NkTouchProfile) {
            log("[$tag] keeping landscape QR/measured profile '${early.name}' " +
                "(CLIENT_INFO preferred '${profile.name}' but panel ${earlyPanel!!.first}x${earlyPanel.second} is wide)")
            profile = early
        }
        if (early !== profile) {
            log("[$tag] profile refined from QR guess '${early.name}' → CLIENT_INFO '${profile.name}' " +
                "(AA already started at the QR-guess resolution)")
        }
        BikeProfileHolder.active = profile  // authoritative; QR modelId was only the early hint
        // Android Auto's video surface + decoder buffer were sized when AA started (from the QR guess)
        // and CANNOT be resized mid-session. If the refined profile wants a different resolution and no
        // explicit override is in effect, pin the spec to what AA is really running: otherwise the
        // compositor scales the live source (e.g. 800x480) as if it were the profile's (e.g. 720x1280),
        // producing a wildly wrong draw rect and a picture the dash rejects — which drops the link every
        // few seconds (the connect/drop flap). Known/consistent bikes hit no-op here.
        if (BikeProfileHolder.aaVideoOverride == null && BikeProfileHolder.aaVideo != startedSpec) {
            BikeProfileHolder.aaVideoOverride = startedSpec
            log("[$tag] pinned AA video to ${startedSpec.width}x${startedSpec.height} " +
                "(can't resize mid-session; refined profile wanted ${profile.aaVideo.width}x${profile.aaVideo.height})")
        }
        log("[$tag] *** BikeProfile selected: ${profile.name} ***")

        val reply = profile.buildClientInfoReply(json, carHuid, phoneUuid)
        log("[$tag] → CLIENT_INFO reply ${reply.toString().take(180)}…")
        PxcFrame(PxcFrame.CMD_CLIENT_INFO_RLY, reply.toString().toByteArray(Charsets.UTF_8)).write(out)
        pushPhoneHuTime(tag, out, "after CLIENT_INFO")
    }

    private fun onCheckSn(tag: String, frame: PxcFrame, out: java.io.OutputStream) {
        val text = frame.payload.asText()
        log("[$tag] CHECK_SN from bike: $text")
        val sn = try { JSONObject(text).optString("sn") } catch (e: Exception) { "" }
        // ack the request frame
        PxcFrame(PxcFrame.CMD_CHECK_SN_ACK, ByteArray(0)).write(out)
        // send the result
        val result = JSONObject().apply {
            put("isOk", true)
            put("errCode", 0)
            put("errMsg", "")
            put("id", sn)
            put("client_set", "easy_conn")
        }
        log("[$tag] → CHECK_SN_RESULT ${result}")
        PxcFrame(PxcFrame.CMD_CHECK_SN_RESULT, result.toString().toByteArray(Charsets.UTF_8)).write(out)
    }
}

private fun ByteArray.asText(): String =
    if (isEmpty()) "" else try { String(this, Charsets.UTF_8) } catch (e: Exception) { "<${size}b>" }
