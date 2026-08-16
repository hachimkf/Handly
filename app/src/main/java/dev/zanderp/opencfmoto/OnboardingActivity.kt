// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Motorcycle Link / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/**
 * Sequential, minimal permission onboarding flow.
 * Explains and requests genuine required Android permissions one at a time.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var iconView: ImageView
    private lateinit var titleView: TextView
    private lateinit var descView: TextView
    private lateinit var nextBtn: MaterialButton
    private lateinit var dot1: View
    private lateinit var dot2: View
    private lateinit var dot3: View

    private var currentStep = 1

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        advanceStep()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_onboarding)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.onboarding_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        iconView = findViewById(R.id.onboarding_icon)
        titleView = findViewById(R.id.onboarding_title)
        descView = findViewById(R.id.onboarding_description)
        nextBtn = findViewById(R.id.btn_onboarding_next)
        dot1 = findViewById(R.id.dot_1)
        dot2 = findViewById(R.id.dot_2)
        dot3 = findViewById(R.id.dot_3)

        nextBtn.setOnClickListener {
            handleStepAction()
        }

        renderStep()
    }

    private fun renderStep() {
        when (currentStep) {
            1 -> {
                iconView.setImageResource(R.drawable.ic_ride)
                titleView.text = "BLUETOOTH"
                descView.text = "Find and connect to your motorcycle."
                nextBtn.text = "CONTINUE"
                updateDots(1)
            }
            2 -> {
                iconView.setImageResource(R.drawable.ic_wifi)
                titleView.text = "NEARBY DEVICES"
                descView.text = "Discover your motorcycle dashboard over Wi-Fi."
                nextBtn.text = "CONTINUE"
                updateDots(2)
            }
            3 -> {
                iconView.setImageResource(R.drawable.ic_settings)
                titleView.text = "NOTIFICATIONS"
                descView.text = "Keep navigation and connection active while riding."
                nextBtn.text = "GET STARTED"
                updateDots(3)
            }
        }
    }

    private fun updateDots(activeDot: Int) {
        fun applyDot(dot: View, isActive: Boolean) {
            val params = dot.layoutParams
            if (params != null) {
                params.width = if (isActive) (24 * resources.displayMetrics.density).toInt() else (8 * resources.displayMetrics.density).toInt()
                dot.layoutParams = params
            }
            dot.setBackgroundResource(if (isActive) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive)
        }

        applyDot(dot1, activeDot == 1)
        applyDot(dot2, activeDot == 2)
        applyDot(dot3, activeDot == 3)
    }

    private fun handleStepAction() {
        when (currentStep) {
            1 -> requestBluetoothPermission()
            2 -> requestNearbyPermission()
            3 -> requestNotificationPermission()
        }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perms = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
            val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) {
                permissionLauncher.launch(needed.toTypedArray())
                return
            }
        }
        advanceStep()
    }

    private fun requestNearbyPermission() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
            return
        }
        advanceStep()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                return
            }
        }
        finishOnboarding()
    }

    private fun advanceStep() {
        if (currentStep < 3) {
            currentStep++
            renderStep()
        } else {
            finishOnboarding()
        }
    }

    private fun finishOnboarding() {
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_DONE, true)
            .apply()

        finish()
    }

    companion object {
        private const val PREF_NAME = "open_motorcycle_link_prefs"
        private const val KEY_ONBOARDING_DONE = "has_completed_onboarding"

        fun hasCompleted(context: Context): Boolean {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ONBOARDING_DONE, false)
        }

        fun start(context: Context) {
            context.startActivity(Intent(context, OnboardingActivity::class.java))
        }
    }
}
