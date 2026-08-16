// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Motorcycle Link / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Minimal user-facing Settings activity.
 * Moves technical options and developer diagnostics to a dedicated sub-screen.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        findViewById<ImageView>(R.id.btn_settings_back).setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.card_garage).setOnClickListener {
            GarageActivity.start(this)
        }

        findViewById<View>(R.id.card_calibration).setOnClickListener {
            startActivity(Intent(this, ScreenMarginsActivity::class.java))
        }

        findViewById<View>(R.id.card_controls).setOnClickListener {
            startActivity(Intent(this, ControlsActivity::class.java))
        }

        findViewById<View>(R.id.card_diagnostics).setOnClickListener {
            DiagnosticsActivity.start(this)
        }

        findViewById<View>(R.id.card_about).setOnClickListener {
            AboutActivity.start(this)
        }
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
        }
    }
}
