// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Motorcycle Link / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import dev.zanderp.opencfmoto.navigation.AndroidAutoProvider
import dev.zanderp.opencfmoto.navigation.GoogleMapsProvider
import dev.zanderp.opencfmoto.navigation.MapLibreProvider
import dev.zanderp.opencfmoto.navigation.NavigationLauncher
import dev.zanderp.opencfmoto.navigation.WazeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NavigationProviderTest {

    @Test
    fun testProviderRegistration() {
        val providers = NavigationLauncher.allProviders
        assertEquals(4, providers.size)

        val aa = providers.find { it is AndroidAutoProvider }
        val gmaps = providers.find { it is GoogleMapsProvider }
        val waze = providers.find { it is WazeProvider }
        val maplibre = providers.find { it is MapLibreProvider }

        assertNotNull(aa)
        assertNotNull(gmaps)
        assertNotNull(waze)
        assertNotNull(maplibre)

        assertEquals("android_auto", aa?.id)
        assertEquals("google_maps", gmaps?.id)
        assertEquals("waze", waze?.id)
        assertEquals("maplibre_native", maplibre?.id)
    }

    @Test
    fun testDefaultProviderIsAndroidAuto() {
        assertEquals("android_auto", NavigationLauncher.activeProvider.id)
    }
}
