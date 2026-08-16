// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Handly / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class CarbitBtBridgeTest {

    @Test
    fun testFrameEncodingAndDecoding() {
        val payload = """{"status":2,"phoneApFrequency":0}""".toByteArray(StandardCharsets.UTF_8)
        val frameBytes = CarbitBtBridge.encodeFrame(CarbitBtBridge.CMD_REQUEST_BUILD_NET, payload)

        assertEquals(16 + payload.size, frameBytes.size)

        val input = ByteArrayInputStream(frameBytes)
        val decoded = CarbitBtBridge.readFrame(input)

        assertNotNull(decoded)
        assertEquals(CarbitBtBridge.CMD_REQUEST_BUILD_NET, decoded!!.cmd)
        assertEquals(payload.size, decoded.payload.size)
        assertEquals("""{"status":2,"phoneApFrequency":0}""", decoded.jsonString)
    }

    @Test
    fun testExactCmdConstants() {
        assertEquals(0x00080000, CarbitBtBridge.CMD_CONN_TYPE_P2C)
        assertEquals(0x00080010, CarbitBtBridge.CMD_CLIENT_INFO)
        assertEquals(0x00080020, CarbitBtBridge.CMD_REQUEST_BUILD_NET)
        assertEquals(0x00080040, CarbitBtBridge.CMD_NOTIFY_AP_INFO)
        assertEquals("9f03b326-5d75-46f1-9a39-b71f144d1d97", CarbitBtBridge.SDP_UUID.toString())
    }

    @Test
    fun testMacDerivation() {
        val rawBm = "DD:0D:30:5A:1E:71"
        val qrBmMac = rawBm.trim().uppercase()
        val derivedMac = if (qrBmMac.startsWith("DD:")) "DC:" + qrBmMac.substring(3) else qrBmMac
        val candidateName = "EC_${derivedMac.replace(":", "")}"

        assertEquals("DD:0D:30:5A:1E:71", qrBmMac)
        assertEquals("DC:0D:30:5A:1E:71", derivedMac)
        assertEquals("EC_DC0D305A1E71", candidateName)
    }
}
