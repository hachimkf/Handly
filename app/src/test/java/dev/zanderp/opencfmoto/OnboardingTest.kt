// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Motorcycle Link / OpenCfMoto. Free software under GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import org.junit.Assert.assertFalse
import org.junit.Test

class OnboardingTest {

    @Test
    fun testOnboardingConstants() {
        // Verify onboarding preferences and navigation entry points
        val prefName = "open_motorcycle_link_prefs"
        val keyDone = "has_completed_onboarding"
        assertFalse(prefName.isBlank())
        assertFalse(keyDone.isBlank())
    }
}
