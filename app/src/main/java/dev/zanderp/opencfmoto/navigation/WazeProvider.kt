// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Motorcycle Link / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.zanderp.opencfmoto.LogBus
import java.util.Locale

/**
 * Waze navigation provider.
 * Dispatches deep links to Waze which feeds the Android Auto projection stream.
 */
class WazeProvider : NavigationProvider {
    override val id: String = "waze"
    override val displayName: String = "Waze"

    private val wazePkg = "com.waze"

    override fun isAvailable(context: Context): Boolean {
        return runCatching { context.packageManager.getPackageInfo(wazePkg, 0) }.isSuccess
    }

    override fun launch(
        context: Context,
        destination: String?,
        lat: Double?,
        lon: Double?,
        label: String?,
    ): Boolean {
        val uri = when {
            lat != null && lon != null -> {
                Uri.parse("https://waze.com/ul?ll=${String.format(Locale.US, "%.6f,%.6f", lat, lon)}&navigate=yes")
            }
            !destination.isNullOrBlank() -> {
                Uri.parse("https://waze.com/ul?q=${Uri.encode(destination.trim())}&navigate=yes")
            }
            else -> {
                Uri.parse("https://waze.com/ul")
            }
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(wazePkg)
            ?: Intent(Intent.ACTION_VIEW, uri)
        launchIntent.data = uri
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            context.startActivity(launchIntent)
            LogBus.log("[NAV] Dispatched Waze navigation ($uri)")
            true
        }.getOrDefault(false)
    }
}
