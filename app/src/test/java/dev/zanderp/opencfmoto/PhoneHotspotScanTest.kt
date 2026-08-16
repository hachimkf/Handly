package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class PhoneHotspotScanTest {
    @Test
    fun prefersApPrefixedInterfacesAndExcludesCellular() {
        val ap = iface("ap0", "192.168.43.1", 24)
        val cellular = iface("rmnet_data0", "10.113.98.161", 30)
        val swlan = iface("swlan0", "192.168.50.1", 24)
        val subnets = PhoneHotspotScan.tetheringSubnets(listOf(cellular, swlan, ap))
        assertEquals(2, subnets.size)
        assertTrue(subnets.none { it.interfaceName == "rmnet_data0" })
        assertEquals("ap0", subnets.first().interfaceName)
        assertEquals("swlan0", subnets[1].interfaceName)
    }

    @Test
    fun candidateHostsPreferNearPhone() {
        val subnet = PhoneHotspotScan.Subnet(
            localAddress = InetAddress.getByName("192.168.43.1") as Inet4Address,
            prefixLength = 24,
            interfaceName = "ap0",
        )
        val hosts = PhoneHotspotScan.candidateHosts(subnet, limit = 5)
        assertEquals(5, hosts.size)
        assertEquals("192.168.43.2", hosts[0].hostAddress)
        assertTrue(hosts.none { it.hostAddress == "192.168.43.1" })
    }

    private fun iface(name: String, ip: String, prefix: Int) =
        PhoneHotspotScan.InterfaceSnapshot(
            name = name,
            isUp = true,
            isLoopback = false,
            isPointToPoint = false,
            addresses = listOf(InetAddress.getByName(ip) to prefix),
        )
}
