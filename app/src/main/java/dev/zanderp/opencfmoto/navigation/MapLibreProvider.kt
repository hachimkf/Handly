// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Motorcycle Link / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto.navigation

import android.content.Context
import dev.zanderp.opencfmoto.LogBus

/**
 * Placeholder for future native vector map navigation provider (MapLibre / offline engine).
 * Prepared as part of the Milestone 3 navigation abstraction foundation.
 */
class MapLibreProvider : NavigationProvider {
    override val id: String = "maplibre_native"
    override val displayName: String = "MapLibre Native (Offline)"

    override fun isAvailable(context: Context): Boolean = false

    override fun launch(
        context: Context,
        destination: String?,
        lat: Double?,
        lon: Double?,
        label: String?,
    ): Boolean {
        LogBus.log("[NAV] MapLibre Native provider is reserved for Milestone 4+")
        return false
    }
}
