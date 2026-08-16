// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Handly / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTraceTest {

    @Test
    fun testTraceProgression() {
        ConnectionTrace.reset()
        ConnectionTrace.transition(ConnectionTrace.Step.APP_STARTED)
        assertEquals(ConnectionTrace.Step.APP_STARTED, ConnectionTrace.currentStep)

        ConnectionTrace.transition(ConnectionTrace.Step.PERMISSIONS_READY)
        ConnectionTrace.transition(ConnectionTrace.Step.QR_AVAILABLE)
        ConnectionTrace.transition(ConnectionTrace.Step.QR_PARSED, "modelId=40603")

        ConnectionTrace.transition(ConnectionTrace.Step.P2P_DISCOVERY_STARTED)
        ConnectionTrace.transition(ConnectionTrace.Step.P2P_DEVICE_FOUND, "EC_DC0D305A1E71")
        ConnectionTrace.transition(ConnectionTrace.Step.P2P_CONNECTION_STARTED)
        ConnectionTrace.transition(ConnectionTrace.Step.P2P_CONNECTED)
        ConnectionTrace.transition(ConnectionTrace.Step.NETWORK_AVAILABLE)
        ConnectionTrace.transition(ConnectionTrace.Step.DASH_IP_DISCOVERED, "192.168.49.1")

        ConnectionTrace.transition(ConnectionTrace.Step.PXC_SOCKET_OPEN, "192.168.49.1:10922")
        ConnectionTrace.transition(ConnectionTrace.Step.PXC_HANDSHAKE_STARTED)
        ConnectionTrace.transition(ConnectionTrace.Step.CLIENT_INFO_SENT)
        ConnectionTrace.transition(ConnectionTrace.Step.CLIENT_INFO_RESPONSE_RECEIVED)
        ConnectionTrace.transition(ConnectionTrace.Step.NOTIFY_RECEIVED, "0x103a0")
        ConnectionTrace.transition(ConnectionTrace.Step.NOTIFY_ACK_SENT, "0x103a1")
        ConnectionTrace.transition(ConnectionTrace.Step.HEARTBEAT_STARTED)
        ConnectionTrace.transition(ConnectionTrace.Step.MEDIA_CHANNEL_OPEN, "192.168.49.1:10920")
        ConnectionTrace.transition(ConnectionTrace.Step.PROJECTION_READY)
        ConnectionTrace.transition(ConnectionTrace.Step.CONNECTED)

        assertEquals(ConnectionTrace.Step.CONNECTED, ConnectionTrace.currentStep)

        val history = ConnectionTrace.getHistory()
        assertTrue(history.size >= 15)

        val summary = ConnectionTrace.formattedTimingSummary()
        assertTrue(summary.contains("Connection timing:"))
    }

    @Test
    fun testFailureTransition() {
        ConnectionTrace.reset()
        ConnectionTrace.transition(ConnectionTrace.Step.P2P_DISCOVERY_STARTED)
        ConnectionTrace.fail(
            failedStep = ConnectionTrace.Step.P2P_CONNECTION_STARTED,
            reason = "Group formation timeout",
            dashIp = "192.168.49.1",
            port = 10922,
        )

        assertEquals(ConnectionTrace.Step.FAILED, ConnectionTrace.currentStep)
        assertEquals("Group formation timeout", ConnectionTrace.lastFailureReason)
        assertNotNull(ConnectionTrace.lastFailure)
        assertEquals(ConnectionTrace.Step.P2P_CONNECTION_STARTED, ConnectionTrace.lastFailure?.failedStep)
        assertEquals("192.168.49.1", ConnectionTrace.lastFailure?.dashIp)
        assertEquals(10922, ConnectionTrace.lastFailure?.port)
    }
}
