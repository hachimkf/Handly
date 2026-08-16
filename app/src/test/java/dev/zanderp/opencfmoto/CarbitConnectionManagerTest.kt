// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Handly / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarbitConnectionManagerTest {

    @Test
    fun testTargetBikeQrParsingAndProfile() {
        val rawUrl = "http://www.carbit.com.cn/down6/645/644/_ylqxos?modelid=40603&sn=JzFX&action=128&bm=DD%3A0D%3A30%3A5A%3A1E%3A71"
        val qr = QrData.parse(rawUrl)
        assertNotNull(qr)

        assertEquals("40603", qr!!.modelId)
        assertEquals(128, qr.action)
        assertTrue((qr.action and 128) != 0)
        assertEquals("dd:0d:30:5a:1e:71", qr.mac)
        assertEquals("", qr.ssid)
        assertFalse(qr.supportsP2p)
        assertTrue(qr.supportsPhoneHotspot)

        val profile = BikeProfiles.selectByQr(qr)
        assertNotNull(profile)
        assertEquals("Generic Carbit / EasyRide (Sware v1.7 / Hware v1.5)", profile.name)
    }

    @Test
    fun testP2pConnectedVsConnectedStateProgression() {
        ConnectionTrace.reset()

        // 1. P2P Connected state is an intermediate transport step
        ConnectionTrace.transition(ConnectionTrace.Step.P2P_CONNECTED, "GO=192.168.49.1")
        assertEquals(ConnectionTrace.Step.P2P_CONNECTED, ConnectionTrace.currentStep)
        assertFalse(ConnectionTrace.currentStep == ConnectionTrace.Step.CONNECTED)

        // 2. Handshake & Client Info
        ConnectionTrace.transition(ConnectionTrace.Step.PXC_SERVER_10922_BOUND, "192.168.49.1:10922")
        ConnectionTrace.transition(ConnectionTrace.Step.CLIENT_INFO_SENT)
        ConnectionTrace.transition(ConnectionTrace.Step.CLIENT_INFO_RECEIVED, "HUID=12345, Sware=V1.7, Hware=V1.5")
        ConnectionTrace.transition(ConnectionTrace.Step.HEARTBEAT_STARTED)
        ConnectionTrace.transition(ConnectionTrace.Step.MEDIA_CHANNEL_OPEN, "192.168.49.1:10920")
        ConnectionTrace.transition(ConnectionTrace.Step.PROJECTION_READY)

        // 3. True Carbit Connected state only after PXC session is ready
        ConnectionTrace.transition(ConnectionTrace.Step.CONNECTED, "Dashboard connected")
        assertEquals(ConnectionTrace.Step.CONNECTED, ConnectionTrace.currentStep)
    }

    @Test
    fun testFailureDiagnosticReporting() {
        ConnectionTrace.reset()
        ConnectionTrace.transition(ConnectionTrace.Step.P2P_DISCOVERY_STARTED)
        ConnectionTrace.fail(
            failedStep = ConnectionTrace.Step.P2P_DEVICE_FOUND,
            reason = "P2P_DEVICE_NOT_FOUND: EC_DC0D305A1E71 not visible",
            dashIp = "192.168.49.1",
            port = 10922,
        )

        assertEquals(ConnectionTrace.Step.FAILED, ConnectionTrace.currentStep)
        val fail = ConnectionTrace.lastFailure
        assertNotNull(fail)
        assertEquals(ConnectionTrace.Step.P2P_DEVICE_FOUND, fail?.failedStep)
        assertTrue(fail?.reason?.contains("P2P_DEVICE_NOT_FOUND") == true)
        assertEquals("192.168.49.1", fail?.dashIp)
        assertEquals(10922, fail?.port)
    }

    @Test
    fun testDisconnectLifecycle() {
        ConnectionState.set(Phase.STREAMING, "Test Bike")
        assertTrue(CarbitConnectionManager.isConnected)

        CarbitConnectionManager.disconnect()
        assertEquals(Phase.STOPPED, CarbitConnectionManager.currentPhase)
        assertFalse(CarbitConnectionManager.isConnected)
    }
}
