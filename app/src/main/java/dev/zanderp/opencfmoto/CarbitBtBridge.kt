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

    const val CMD_CONN_TYPE_P2C = 524288   // 0x00080000
    const val CMD_CLIENT_INFO    = 524304   // 0x00080010
    const val CMD_NOTIFY_AP_INFO = 524352   // 0x00080040

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

    fun readFrame(input: InputStream, timeoutMs: Int = 3000): Frame? {
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

    @SuppressLint("MissingPermission")
    fun sendApInfo(
        context: Context,
        btMac: String,
        ssid: String,
        pwd: String,
        phoneIp: String,
        auth: String = "WPA2",
        log: (String) -> Unit = {},
    ): Boolean {
        log("[BT-BRIDGE] Starting Bluetooth AP provisioning to MAC=$btMac...")
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            log("[BT-BRIDGE] Bluetooth is disabled or unavailable")
            return false
        }

        val device = try {
            adapter.getRemoteDevice(btMac)
        } catch (e: Exception) {
            log("[BT-BRIDGE] Invalid Bluetooth MAC '$btMac': ${e.message}")
            return false
        }

        var socket: BluetoothSocket? = null
        try {
            log("[BT-BRIDGE] Connecting RFCOMM socket to $btMac (UUID=$SDP_UUID)...")
            socket = device.createRfcommSocketToServiceRecord(SDP_UUID)
            socket.connect()
            log("[BT-BRIDGE] RFCOMM socket connected!")

            val out = socket.outputStream
            val inp = socket.inputStream

            // 1. Send EBT_CONN_TYPE_P2C
            log("[BT-BRIDGE] -> EBT_CONN_TYPE_P2C (0x00080000)")
            out.write(encodeFrame(CMD_CONN_TYPE_P2C, ByteArray(0)))
            out.flush()

            // 2. Send EBT_P2C_CLIENT_INFO
            val clientInfoJson = JSONObject().apply {
                put("brand", android.os.Build.BRAND)
                put("model", android.os.Build.MODEL)
                put("version", "1.7.0")
            }
            log("[BT-BRIDGE] -> EBT_P2C_CLIENT_INFO: $clientInfoJson")
            out.write(encodeFrame(CMD_CLIENT_INFO, clientInfoJson.toString().toByteArray(StandardCharsets.UTF_8)))
            out.flush()

            // 3. Optional: Read response
            try {
                val resp = readFrame(inp, timeoutMs = 2000)
                if (resp != null) {
                    log("[BT-BRIDGE] <- Bike response: 0x${Integer.toHexString(resp.cmd)} ${resp.jsonString}")
                }
            } catch (e: Exception) {
                log("[BT-BRIDGE] Read response error (ignoring): ${e.message}")
            }

            // 4. Send EBT_P2C_NOTIFY_AP_INFO
            val apJson = JSONObject().apply {
                put("ssid", ssid)
                put("pwd", pwd)
                put("auth", auth)
                put("ip", phoneIp)
            }
            log("[BT-BRIDGE] -> EBT_P2C_NOTIFY_AP_INFO (0x00080040): $apJson")
            out.write(encodeFrame(CMD_NOTIFY_AP_INFO, apJson.toString().toByteArray(StandardCharsets.UTF_8)))
            out.flush()

            log("[BT-BRIDGE] *** Bluetooth AP provisioning successfully sent to motorcycle! ***")
            return true
        } catch (e: Exception) {
            log("[BT-BRIDGE] Bluetooth provisioning failed: ${e.message}")
            return false
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }
}
