// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Motorcycle Link / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto.navigation

import android.content.Context

/**
 * Pluggable navigation provider interface.
 * Abstracts the UI and control layers from specific navigation engines
 * (Android Auto, Google Maps, Waze, MapLibre Native, etc.).
 */
interface NavigationProvider {
    val id: String
    val displayName: String

    fun isAvailable(context: Context): Boolean

    fun launch(
        context: Context,
        destination: String? = null,
        lat: Double? = null,
        lon: Double? = null,
        label: String? = null,
    ): Boolean
}
