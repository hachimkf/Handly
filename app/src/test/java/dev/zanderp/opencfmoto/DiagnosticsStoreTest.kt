// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Motorcycle Link / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsStoreTest {

    @Test
    fun testConnectionMetricsUpdate() {
        val meta = ConnectionState.DashMetadata(
            huid = "CARB998877",
            huName = "VOGE DS800",
            channel = "CAR_CTRL",
            sware = "1.7",
            hware = "1.5",
            sdkVersion = "2.4.0",
            packageName = "net.easyconn.easyride.wws",
            versionName = "2.4.0",
        )
        DiagnosticsStore.updateConnection(status = "CONNECTED", meta = meta)

        val conn = DiagnosticsStore.connection
        assertEquals("CONNECTED", conn.status)
        assertEquals("VOGE DS800", conn.dashName)
        assertEquals("CARB998877", conn.huid)
        assertEquals("1.7", conn.sware)
        assertEquals("1.5", conn.hware)
        assertEquals("net.easyconn.easyride.wws", conn.packageName)
    }

    @Test
    fun testPxcPacketCounters() {
        DiagnosticsStore.updatePxc(
            sessionState = "ACTIVE",
            handshakeState = "COMPLETE",
            addRxPackets = 10,
            addTxPackets = 8,
            addRxBytes = 1024,
            addTxBytes = 512,
        )

        val pxc = DiagnosticsStore.pxc
        assertEquals("ACTIVE", pxc.sessionState)
        assertEquals("COMPLETE", pxc.handshakeState)
        assertTrue(pxc.rxPackets >= 10)
        assertTrue(pxc.txPackets >= 8)
        assertTrue(pxc.rxBytes >= 1024)
        assertTrue(pxc.txBytes >= 512)
    }

    @Test
    fun testLiveEventRingBuffer() {
        for (i in 1..60) {
            DiagnosticsStore.recordEvent("Event $i with sensitive password=secret$i")
        }

        val events = DiagnosticsStore.getRecentEvents()
        assertEquals(50, events.size)
        // Redaction should have stripped password
        assertFalse(events.last().description.contains("secret60"))
        assertTrue(events.last().description.contains("password=«redacted»"))
    }
}
