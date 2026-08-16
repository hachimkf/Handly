// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Motorcycle Link / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto.navigation

import android.content.Context
import android.provider.Settings

/**
 * Main facade orchestrating motorcycle navigation across multiple providers
 * (Android Auto projection, Google Maps, Waze, and future native offline maps).
 */
object NavigationLauncher {

    val androidAuto = AndroidAutoProvider()
    val googleMaps = GoogleMapsProvider()
    val waze = WazeProvider()
    val mapLibre = MapLibreProvider()

    val allProviders: List<NavigationProvider> = listOf(
        androidAuto,
        googleMaps,
        waze,
        mapLibre,
    )

    @Volatile
    var activeProvider: NavigationProvider = androidAuto

    fun openAndroidAuto(context: Context): Boolean {
        return androidAuto.launch(context)
    }

    fun openGoogleMaps(
        context: Context,
        destination: String? = null,
        lat: Double? = null,
        lon: Double? = null,
        label: String? = null,
    ): Boolean {
        return googleMaps.launch(context, destination, lat, lon, label)
    }

    fun openWaze(
        context: Context,
        destination: String? = null,
        lat: Double? = null,
        lon: Double? = null,
        label: String? = null,
    ): Boolean {
        return waze.launch(context, destination, lat, lon, label)
    }

    fun navigate(context: Context, destination: String): Boolean {
        return activeProvider.launch(context, destination = destination)
    }

    fun navigateLatLon(
        context: Context,
        lat: Double,
        lon: Double,
        label: String? = null,
    ): Boolean {
        return activeProvider.launch(context, lat = lat, lon = lon, label = label)
    }

    fun canLaunchFromBackground(context: Context): Boolean =
        Settings.canDrawOverlays(context)
}
