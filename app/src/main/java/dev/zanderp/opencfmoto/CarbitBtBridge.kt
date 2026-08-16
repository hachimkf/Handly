// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Handly / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Reverse-engineered Bluetooth Classic SDP bridge from official Carbit Ride (`qm.o` / `SdpBluetoothUtil`).
 *
 * Used for `action=128` (Phone Hotspot mode with `bm=<bluetooth_mac>`):
 *   1. Connects to the motorcycle via Bluetooth RFCOMM socket on SDP UUID `9f03b326-5d75-46f1-9a39-b71f144d1d97`.
 *   2. Sends `EBT_CONN_TYPE_P2C` (`0x00080000` / 524288).
 *   3. Sends `EBT_P2C_CLIENT_INFO` (`0x00080010` / 524304).
 *   4. Reads bike response (`status: 2` = request phone hotspot).
 *   5. Sends `EBT_P2C_NOTIFY_AP_INFO` (`0x00080040` / 524352) with JSON payload:
 *      `{"ssid":"<hotspot_ssid>","pwd":"<hotspot_pwd>","auth":"WPA2","ip":"<phone_ip>"}`
 *   6. The motorcycle receives the AP credentials and connects its Wi-Fi STA to the phone's Hotspot!
 */
object CarbitBtBridge {

    val SDP_UUID: UUID = UUID.fromString("9f03b326-5d75-46f1-9a39-b71f144d1d97")

    const val CMD_CONN_TYPE_P2C     = 524288   // 0x00080000
    const val CMD_CLIENT_INFO        = 524304   // 0x00080010
    const val CMD_REQUEST_BUILD_NET  = 524320   // 0x00080020
    const val CMD_NOTIFY_AP_INFO     = 524352   // 0x00080040

    data class Frame(val cmd: Int, val payload: ByteArray) {
        val jsonString: String get() = String(payload, StandardCharsets.UTF_8)
    }

    fun encodeFrame(cmd: Int, payload: ByteArray): ByteArray {
        val head = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(cmd)
        head.putInt(payload.size)
        head.putInt(cmd xor payload.size)
        head.putInt(0)
        return head.array() + payload
    }

    fun readFrame(input: InputStream): Frame? {
        val headBuf = ByteArray(16)
        var readTotal = 0
        while (readTotal < 16) {
            val count = input.read(headBuf, readTotal, 16 - readTotal)
            if (count < 0) return null
            readTotal += count
        }
        val head = ByteBuffer.wrap(headBuf).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = head.getInt(0)
        val len = head.getInt(4)
        val magic = head.getInt(8)
        if ((cmd xor len) != magic || len < 0 || len > 65536) {
            return null
        }
        val body = ByteArray(len)
        var bodyTotal = 0
        while (bodyTotal < len) {
            val count = input.read(body, bodyTotal, len - bodyTotal)
            if (count < 0) return null
            bodyTotal += count
        }
        return Frame(cmd, body)
    }

    private fun bytesToHex(bytes: ByteArray, maxBytes: Int = 32): String {
        val take = bytes.take(maxBytes)
        val hex = take.joinToString("") { "%02X".format(it) }
        return if (bytes.size > maxBytes) "$hex... (${bytes.size}B)" else "$hex (${bytes.size}B)"
    }

    data class ResolvedBtDevice(
        val device: BluetoothDevice,
        val qrBmMac: String,
        val derivedMac: String,
        val candidateName: String,
        val isBonded: Boolean,
    )

    fun resolveDevice(adapter: BluetoothAdapter, rawBm: String): ResolvedBtDevice? {
        val qrBmMac = rawBm.trim().uppercase()
        val derivedMac = if (qrBmMac.startsWith("DD:")) "DC:" + qrBmMac.substring(3) else qrBmMac
        val candidateName = "EC_${derivedMac.replace(":", "")}"
        val altCandidateName = "EC_${qrBmMac.replace(":", "")}"

        // 1. Search bonded devices first
        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        for (dev in bonded) {
            val addr = dev.address.uppercase()
            val name = dev.name.orEmpty()
            if (addr == derivedMac || addr == qrBmMac ||
                name.equals(candidateName, ignoreCase = true) ||
                name.equals(altCandidateName, ignoreCase = true)
            ) {
                return ResolvedBtDevice(
                    device = dev,
                    qrBmMac = qrBmMac,
                    derivedMac = derivedMac,
                    candidateName = candidateName,
                    isBonded = true,
                )
            }
        }

        // 2. Resolve via valid Bluetooth MAC address
        val targetMac = when {
            BluetoothAdapter.checkBluetoothAddress(derivedMac) -> derivedMac
            BluetoothAdapter.checkBluetoothAddress(qrBmMac) -> qrBmMac
            else -> null
        } ?: return null

        val device = runCatching { adapter.getRemoteDevice(targetMac) }.getOrNull() ?: return null
        return ResolvedBtDevice(
            device = device,
            qrBmMac = qrBmMac,
            derivedMac = derivedMac,
            candidateName = candidateName,
            isBonded = device.bondState == BluetoothDevice.BOND_BONDED,
        )
    }

    @SuppressLint("MissingPermission")
    fun sendApInfo(
        context: Context,
        rawBm: String,
        ifaceName: String,
        ssid: String,
        pwd: String,
        phoneIp: String,
        auth: String = "WPA2",
        log: (String) -> Unit = {},
    ): Boolean {
        log("[BT-BRIDGE] Starting Bluetooth AP provisioning for QR bm=$rawBm...")
        ConnectionTrace.transition(ConnectionTrace.Step.BLUETOOTH_PROVISION_STARTED, "QR_BM=$rawBm")

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            log("[BT-BRIDGE] Bluetooth adapter is disabled or unavailable")
            ConnectionTrace.fail(
                ConnectionTrace.Step.BLUETOOTH_PROVISION_STARTED,
                "Bluetooth is disabled or unavailable",
            )
            return false
        }

        val resolved = resolveDevice(adapter, rawBm)
        val qrBmMac = rawBm.trim().uppercase()
        val derivedMac = if (qrBmMac.startsWith("DD:")) "DC:" + qrBmMac.substring(3) else qrBmMac
        val candidateName = "EC_${derivedMac.replace(":", "")}"

        log(
            "[BT-BRIDGE] [DIAGNOSTICS] " +
                "QR_BM_MAC=$qrBmMac " +
                "DERIVED_BT_MAC=$derivedMac " +
                "BT_DEVICE_NAME=$candidateName " +
                "BT_DEVICE_FOUND=${resolved != null} " +
                "BT_BONDED=${resolved?.isBonded ?: false} " +
                "BT_RESOLVED_ADDRESS=${resolved?.device?.address ?: "NONE"}",
        )

        if (resolved == null) {
            val reason = "Bluetooth device not found for MAC '$rawBm' (derived '$derivedMac', name '$candidateName')"
            log("[BT-BRIDGE] $reason")
            ConnectionTrace.fail(
                ConnectionTrace.Step.BLUETOOTH_DEVICE_FOUND,
                reason,
            )
            return false
        }

        val device = resolved.device
        val targetMac = device.address
        log("[BT-BRIDGE] Bluetooth target selected: address='$targetMac' name='${device.name}' bonded=${resolved.isBonded}")
        ConnectionTrace.transition(
            ConnectionTrace.Step.BLUETOOTH_DEVICE_FOUND,
            "name=${device.name ?: candidateName}, MAC=$targetMac",
        )

        var socket: BluetoothSocket? = null
        try {
            log("[BT-BRIDGE] Connecting RFCOMM socket to $targetMac (SDP UUID=$SDP_UUID)...")
            socket = device.createRfcommSocketToServiceRecord(SDP_UUID)
            socket.connect()
            log("[BT-BRIDGE] RFCOMM socket connected!")
            log("[BT-BRIDGE] [DIAGNOSTICS] BT_RFCOMM_RESULT=SUCCESS")
            ConnectionTrace.transition(ConnectionTrace.Step.BLUETOOTH_RFCOMM_CONNECTED, "UUID=$SDP_UUID")

            val out = socket.outputStream
            val inp = socket.inputStream

            // 1. Send EBT_CONN_TYPE_P2C (0x00080000)
            val p2cFrame = encodeFrame(CMD_CONN_TYPE_P2C, ByteArray(0))
            log("[BT-BRIDGE] [BT-HEX-TX] EBT_CONN_TYPE_P2C: ${bytesToHex(p2cFrame)}")
            out.write(p2cFrame)
            out.flush()
            ConnectionTrace.transition(ConnectionTrace.Step.EBT_CONN_TYPE_P2C_SENT)

            // Read optional ACK
            try {
                val ack1 = readFrame(inp)
                if (ack1 != null) {
                    log("[BT-BRIDGE] [BT-HEX-RX] ACK 0x${Integer.toHexString(ack1.cmd)}: ${bytesToHex(ack1.payload)}")
                }
            } catch (e: Exception) {
                log("[BT-BRIDGE] Read ACK1 error (continuing): ${e.message}")
            }

            // 2. Send EBT_P2C_CLIENT_INFO (0x00080010)
            val clientInfoJson = JSONObject().apply {
                put("phoneType", 0)
                put("phoneID", targetMac)
                put("phoneName", android.os.Build.MODEL)
                put("packageName", "net.easyconn.easyride.wws")
                val netArr = org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", ifaceName)
                        put("addr", phoneIp)
                        put("mask", "255.255.255.0")
                    })
                }
                put("netInterface", netArr)
            }
            val clientInfoPayload = clientInfoJson.toString().toByteArray(StandardCharsets.UTF_8)
            val clientInfoFrame = encodeFrame(CMD_CLIENT_INFO, clientInfoPayload)
            log("[BT-BRIDGE] [BT-HEX-TX] EBT_P2C_CLIENT_INFO ($clientInfoJson): ${bytesToHex(clientInfoFrame)}")
            out.write(clientInfoFrame)
            out.flush()
            ConnectionTrace.transition(ConnectionTrace.Step.EBT_CLIENT_INFO_SENT)

            // Read bike response (status 2 = request AP info)
            var bikeStatus = -1
            try {
                val resp = readFrame(inp)
                if (resp != null) {
                    log("[BT-BRIDGE] [BT-HEX-RX] Bike response: 0x${Integer.toHexString(resp.cmd)} ${resp.jsonString}")
                    val json = JSONObject(resp.jsonString)
                    bikeStatus = json.optInt("status", -1)
                    log("[BT-BRIDGE] Bike status received: status=$bikeStatus")
                    ConnectionTrace.transition(ConnectionTrace.Step.EBT_HOTSPOT_REQUEST_RECEIVED, "status=$bikeStatus")
                }
            } catch (e: Exception) {
                log("[BT-BRIDGE] Read bike status response: ${e.message}")
            }

            // 3. Send EBT_P2C_NOTIFY_AP_INFO (0x00080040)
            val apJson = JSONObject().apply {
                put("ssid", ssid)
                put("pwd", pwd)
                put("auth", auth)
                put("ip", phoneIp)
            }
            val apJsonRedacted = JSONObject().apply {
                put("ssid", "<hidden>")
                put("pwd", "<hidden>")
                put("auth", auth)
                put("ip", phoneIp)
            }
            val apPayload = apJson.toString().toByteArray(StandardCharsets.UTF_8)
            val apFrame = encodeFrame(CMD_NOTIFY_AP_INFO, apPayload)
            log("[BT-BRIDGE] [BT-HEX-TX] EBT_P2C_NOTIFY_AP_INFO ($apJsonRedacted): length=${apFrame.size}B")
            out.write(apFrame)
            out.flush()
            ConnectionTrace.transition(ConnectionTrace.Step.EBT_AP_INFO_SENT)

            // Read final ACK
            try {
                val finalAck = readFrame(inp)
                if (finalAck != null) {
                    log("[BT-BRIDGE] [BT-HEX-RX] Final ACK 0x${Integer.toHexString(finalAck.cmd)}: ${bytesToHex(finalAck.payload)}")
                }
            } catch (_: Exception) {}

            log("[BT-BRIDGE] *** Motorcycle Wi-Fi AP provisioning complete! ***")
            ConnectionTrace.transition(ConnectionTrace.Step.MOTORCYCLE_WIFI_PROVISIONED, "SSID=<hidden>, IP=$phoneIp")
            return true
        } catch (e: Exception) {
            log("[BT-BRIDGE] Bluetooth provisioning failed: ${e.message}")
            ConnectionTrace.fail(
                ConnectionTrace.Step.BLUETOOTH_RFCOMM_CONNECTED,
                "Bluetooth RFCOMM error: ${e.message}",
            )
            return false
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }
}
