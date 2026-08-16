// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Lists what OpenCfMoto needs and offers Install / Grant / Settings actions.
 *
 * Kept deliberately light on launch: we must not block auto-connect or reconnect just because
 * Camera isn't granted (only needed to scan a new QR). Wi‑Fi-off is handled by [WifiGate], not here.
 */
object DependencyPrompt {

    private const val REQ_PERMS = 91
    @Volatile private var shownThisProcess = false

    data class Issue(
        val id: String,
        val title: String,
        val detail: String,
        val required: Boolean,
        val action: Action,
    )

    enum class Action {
        INSTALL_ANDROID_AUTO,
        INSTALL_PLAY_SERVICES,
        GRANT_PERMISSIONS,
        ENABLE_WIFI,
        OPEN_OVERLAY,
        OPEN_SETUP,
    }

    /** Audit dependencies required ONLY for Carbit connection (Bluetooth, Location, Wi-Fi, Camera). */
    fun auditConnection(ctx: android.content.Context, forScan: Boolean = false): List<Issue> = buildList {
        val missingPerms = SetupHelper.missingConnectPermissions(ctx).toMutableList()
        if (forScan || !BikeMemory.hasSaved(ctx)) {
            missingPerms += SetupHelper.missingScanPermissions(ctx)
        }
        val missing = missingPerms.distinct()
        if (missing.isNotEmpty()) {
            add(
                Issue(
                    id = "perms",
                    title = "Connection permissions",
                    detail = "Still needed: ${missing.joinToString(", ") { permLabel(it) }}.",
                    required = true,
                    action = Action.GRANT_PERMISSIONS,
                ),
            )
        }
        if (!WifiGate.isWifiEnabled(ctx)) {
            add(
                Issue(
                    id = "wifi",
                    title = "Phone Wi‑Fi is off",
                    detail = "The motorcycle link uses Wi‑Fi. Turn Wi‑Fi on before Connect.",
                    required = true,
                    action = Action.ENABLE_WIFI,
                ),
            )
        }
    }

    /** Audit dependencies required ONLY for Android Auto Navigation / Projection. */
    fun auditNavigation(ctx: android.content.Context): List<Issue> = buildList {
        if (!SetupHelper.isAndroidAutoInstalled(ctx)) {
            add(
                Issue(
                    id = "aa",
                    title = "Google Android Auto",
                    detail = "Required to project Maps / Waze to the dash. Install it from the Play Store.",
                    required = true,
                    action = Action.INSTALL_ANDROID_AUTO,
                ),
            )
        }
        if (!SetupHelper.isPlayServicesPresent(ctx)) {
            add(
                Issue(
                    id = "gms",
                    title = "Google Play services",
                    detail = "Android Auto needs Play services on this phone. Install or update them from the Play Store.",
                    required = true,
                    action = Action.INSTALL_PLAY_SERVICES,
                ),
            )
        }
    }

    fun audit(ctx: android.content.Context, forScan: Boolean = false): List<Issue> =
        auditConnection(ctx, forScan) + auditNavigation(ctx)

    fun requiredMissing(ctx: android.content.Context, forScan: Boolean = false): List<Issue> =
        auditConnection(ctx, forScan).filter { it.required }

    fun showOnLaunchIfNeeded(activity: Activity): Boolean {
        return false
    }

    /** Block Connect / Scan only when a required CONNECTION permission or Wi-Fi is missing. */
    fun showForConnect(activity: Activity, forScan: Boolean = false): Boolean {
        val missing = auditConnection(activity, forScan).filter { it.required }
        if (missing.isEmpty()) return false
        val permIssue = missing.firstOrNull { it.action == Action.GRANT_PERMISSIONS }
        if (permIssue != null) {
            fix(activity, permIssue)
            return true
        }
        show(activity, missing, alsoOptional = false)
        return true
    }

    /** Block Navigation when Android Auto or Play Services is missing. */
    fun showForNavigation(activity: Activity): Boolean {
        val missing = auditNavigation(activity).filter { it.required }
        if (missing.isEmpty()) return false
        show(activity, missing, alsoOptional = false)
        return true
    }

    private fun show(activity: Activity, required: List<Issue>, alsoOptional: Boolean) {
        val optional = if (alsoOptional) {
            audit(activity).filter { !it.required }
        } else {
            emptyList()
        }
        val body = buildString {
            append("OpenCfMoto needs a few things on this phone before it can talk to your bike:\n")
            for (issue in required) {
                append("\n• ").append(issue.title)
                append("\n  ").append(issue.detail)
            }
            if (optional.isNotEmpty()) {
                append("\n\nRecommended:")
                for (issue in optional) {
                    append("\n• ").append(issue.title)
                    append("\n  ").append(issue.detail)
                }
            }
        }
        val primary = required.firstOrNull() ?: optional.firstOrNull()
        val positiveLabel = when (primary?.action) {
            Action.INSTALL_ANDROID_AUTO -> "Install Android Auto"
            Action.INSTALL_PLAY_SERVICES -> "Install Play services"
            Action.GRANT_PERMISSIONS -> "Grant permissions"
            Action.ENABLE_WIFI -> "Wi‑Fi settings"
            Action.OPEN_OVERLAY -> "Enable overlay"
            Action.OPEN_SETUP, null -> "Open Setup"
        }
        try {
            AlertDialog.Builder(activity)
                .setTitle("Missing dependencies")
                .setMessage(body)
                .setPositiveButton(positiveLabel) { _, _ ->
                    primary?.let { fix(activity, it) } ?: SetupActivity.start(activity)
                }
                .setNeutralButton("Open Setup") { _, _ -> SetupActivity.start(activity) }
                .setNegativeButton("Later", null)
                .show()
        } catch (e: Exception) {
            LogBus.log("[deps] dialog failed: $e")
            Toast.makeText(activity, "Open Setup to finish prerequisites", Toast.LENGTH_LONG).show()
            SetupActivity.start(activity)
        }
    }

    fun fix(activity: Activity, issue: Issue) {
        when (issue.action) {
            Action.INSTALL_ANDROID_AUTO -> openPlay(activity, SetupHelper.GEARHEAD_PACKAGE)
            Action.INSTALL_PLAY_SERVICES -> openPlay(activity, SetupHelper.PLAY_SERVICES_PACKAGE)
            Action.GRANT_PERMISSIONS -> {
                val mic = Manifest.permission.RECORD_AUDIO
                val needMic = ContextCompat.checkSelfPermission(activity, mic) !=
                    PackageManager.PERMISSION_GRANTED
                val missing = (
                    SetupHelper.missingConnectPermissions(activity) +
                        SetupHelper.missingScanPermissions(activity) +
                        if (needMic) listOf(mic) else emptyList()
                    ).distinct()
                if (missing.isEmpty()) {
                    openAppSettings(activity)
                } else {
                    ActivityCompat.requestPermissions(activity, missing.toTypedArray(), REQ_PERMS)
                }
            }
            Action.ENABLE_WIFI -> WifiGate.openWifiSettings(activity)
            Action.OPEN_OVERLAY -> openOverlaySettings(activity)
            Action.OPEN_SETUP -> SetupActivity.start(activity)
        }
    }

    private fun permLabel(perm: String): String = when (perm) {
        Manifest.permission.CAMERA -> "Camera (scan QR)"
        Manifest.permission.ACCESS_FINE_LOCATION -> "Location (join bike Wi‑Fi)"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
        Manifest.permission.RECORD_AUDIO -> "Microphone"
        else -> perm.substringAfterLast('.')
    }

    private fun openPlay(activity: Activity, pkg: String) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        try {
            activity.startActivity(market)
        } catch (_: Exception) {
            try {
                activity.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$pkg"),
                    ),
                )
            } catch (_: Exception) {
                Toast.makeText(activity, "Couldn't open the Play Store", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openAppSettings(activity: Activity) {
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null),
                ),
            )
        } catch (_: Exception) {
            Toast.makeText(activity, "Couldn't open app settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openOverlaySettings(activity: Activity) {
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", activity.packageName, null),
                ),
            )
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(activity, "Couldn't open overlay settings", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
