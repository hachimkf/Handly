// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Motorcycle Link / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.zanderp.opencfmoto.LogBus
import java.util.Locale

/**
 * Google Maps navigation provider.
 * Dispatches turn-by-turn routing to Google Maps which feeds the Android Auto projection stream.
 */
class GoogleMapsProvider : NavigationProvider {
    override val id: String = "google_maps"
    override val displayName: String = "Google Maps"

    private val mapsPkg = "com.google.android.apps.maps"

    override fun isAvailable(context: Context): Boolean {
        return runCatching { context.packageManager.getPackageInfo(mapsPkg, 0) }.isSuccess
    }

    override fun launch(
        context: Context,
        destination: String?,
        lat: Double?,
        lon: Double?,
        label: String?,
    ): Boolean {
        if (!destination.isNullOrBlank()) {
            val enc = Uri.encode(destination.trim())
            val intents = listOf(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$enc&travelmode=driving&dir_action=navigate"),
                ).setPackage(mapsPkg),
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$enc&mode=d")).setPackage(mapsPkg),
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc")).setPackage(mapsPkg),
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$enc")),
            )
            return startFirst(context, intents, destination)
        }

        if (lat != null && lon != null) {
            val coord = String.format(Locale.US, "%.6f,%.6f", lat, lon)
            val pin = if (!label.isNullOrBlank()) Uri.encode("$coord (${label.trim()})") else Uri.encode(coord)
            val intents = listOf(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$coord&travelmode=driving&dir_action=navigate"),
                ).setPackage(mapsPkg),
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$coord&mode=d")).setPackage(mapsPkg),
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coord?q=$pin")).setPackage(mapsPkg),
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$coord")),
            )
            return startFirst(context, intents, label ?: coord)
        }

        // Open app launcher
        val launchIntent = context.packageManager.getLaunchIntentForPackage(mapsPkg)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            LogBus.log("[NAV] Opened Google Maps app")
            true
        }.getOrDefault(false)
    }

    private fun startFirst(context: Context, intents: List<Intent>, desc: String): Boolean {
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                LogBus.log("[NAV] Dispatched Google Maps route: $desc")
                return true
            } catch (_: Exception) {}
        }
        return false
    }
}
