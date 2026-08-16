// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Motorcycle Link / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto.navigation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.zanderp.opencfmoto.AndroidAutoService
import dev.zanderp.opencfmoto.LogBus

/**
 * Android Auto navigation provider (active default).
 * Projects Android Auto directly onto the motorcycle TFT dashboard.
 */
class AndroidAutoProvider : NavigationProvider {
    override val id: String = "android_auto"
    override val displayName: String = "Android Auto"

    override fun isAvailable(context: Context): Boolean {
        val pm = context.packageManager
        val gearhead = runCatching { pm.getPackageInfo("com.google.android.projection.gearhead", 0) }.isSuccess
        val gms = runCatching { pm.getPackageInfo("com.google.android.gms", 0) }.isSuccess
        return gearhead || gms
    }

    override fun launch(
        context: Context,
        destination: String?,
        lat: Double?,
        lon: Double?,
        label: String?,
    ): Boolean {
        LogBus.log("[NAV] Launching Android Auto projection stream")
        AndroidAutoService.start(context)
        return true
    }
}
