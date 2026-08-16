package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrDataTest {
    @Test
    fun parsesCarbitSsidPwd() {
        val raw =
            "http://www.carbit.com.cn/downsdk/657/658/_sdk?modelid=37426&sn=Vasr&action=1" +
                "&ssid=CFMOTO4288&pwd=e278450c&uid=abc"
        val qr = QrData.parse(raw)!!
        assertEquals("CFMOTO4288", qr.ssid)
        assertEquals("e278450c", qr.pwd)
        assertEquals(1, qr.action)
        assertTrue(qr.supportsAp)
        assertFalse(qr.supportsP2p)
    }

    @Test
    fun parsesMotoMoriniWifiHashFormat() {
        val raw =
            "http://admin.motomorini.com/app.html?Wifi=ML174167#12345678#dc0d30da1b6c" +
                "&MachineID=dc0d30da1b6c&ProductID=00297"
        val qr = QrData.parse(raw)
        assertNotNull(qr)
        assertEquals("ML174167", qr!!.ssid)
        assertEquals("12345678", qr.pwd)
        assertEquals("dc:0d:30:da:1b:6c", qr.mac)
        assertEquals("00297", qr.modelId)
        assertEquals("dc0d30da1b6c", qr.sn)
        assertTrue(qr.supportsAp)
        assertFalse(qr.supportsP2p)
    }

    @Test
    fun rejectsVehicleInfoQr() {
        assertNull(QrData.parse("code:&engine:&vin:&color:Fuji White"))
        val hint = QrData.parseFailureHint("code:&engine:&vin:&color:Fuji White")
        assertNotNull(hint)
        assertTrue(hint!!.contains("bike info", ignoreCase = true))
    }

    @Test
    fun parsesPhoneHotspotAction128() {
        val raw =
            "http://www.carbit.com.cn/down6/645/644/_ylqxos?modelid=21322&sn=t6J4&action=128" +
                "&bm=DD%3A0D%3A30%3A24%3A87%3A6D"
        val qr = QrData.parse(raw)
        assertNotNull(qr)
        assertTrue(qr!!.supportsPhoneHotspot)
        assertFalse(qr.supportsAp)
        assertFalse(qr.supportsP2p)
        assertEquals("dd:0d:30:24:87:6d", qr.mac)
        assertEquals("21322", qr.modelId)
        assertEquals(128, qr.action)
        assertEquals("", qr.ssid)
        assertTrue(qr.pwd.isEmpty())
    }

    @Test
    fun parsesCarbitTokenQr() {
        val qr = QrData.parse("CARBITDC0D3024876D")
        assertNotNull(qr)
        assertTrue(qr!!.supportsPhoneHotspot)
        assertEquals("dc:0d:30:24:87:6d", qr.mac)
    }

    @Test
    fun parsesP2pOnlyZontesSoftApQr() {
        val raw =
            "http://www.carbit.com.cn/down6/645/644/_ylqxos?modelid=34808&action=8" +
                "&ssid=ZT5Gcf3b&pwd=secret&auth=WPA2&mac=34%3A28%3A4a%3A04%3Acf%3A3b&name=ZT5Gcf3b"
        val qr = QrData.parse(raw)!!
        assertEquals("ZT5Gcf3b", qr.ssid)
        assertTrue(qr.supportsP2p)
        assertFalse(qr.supportsAp)
        assertFalse(qr.supportsPhoneHotspot)
        assertEquals("34:28:4a:04:cf:3b", qr.mac)
    }

    @Test
    fun parsesThinkerridePositionalSoftApQr() {
        val raw = "http://g.thinkerride.com?CQKY_5e1c6cb10&81316044&ap=1"
        val qr = QrData.parse(raw)
        assertNotNull(qr)
        assertEquals("CQKY_5e1c6cb10", qr!!.ssid)
        assertEquals("81316044", qr.pwd)
        assertEquals(1, qr.action)
        assertTrue(qr.supportsAp)
        assertFalse(qr.supportsP2p)
        assertEquals("CQKY_5e1c6cb10", qr.name)
    }

    @Test
    fun parsesBareThinkerrideCqkyToken() {
        val qr = QrData.parse("CQKY_5e1c6cb10&81316044&ap=1")
        assertNotNull(qr)
        assertEquals("CQKY_5e1c6cb10", qr!!.ssid)
        assertEquals("81316044", qr.pwd)
        assertTrue(qr.supportsAp)
    }

    @Test
    fun rejectsIncompleteThinkerrideQr() {
        assertNull(QrData.parse("http://g.thinkerride.com?CQKY_onlyssid&ap=1"))
        assertNull(QrData.parse("http://g.thinkerride.com?ap=1"))
        val hint = QrData.parseFailureHint("http://g.thinkerride.com/download")
        assertNotNull(hint)
        assertTrue(hint!!.contains("Thinkerride", ignoreCase = true) || hint.contains("Kove", ignoreCase = true))
    }
}
