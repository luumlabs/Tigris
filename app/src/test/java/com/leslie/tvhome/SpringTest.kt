package com.leslie.tvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpringTest {
    private fun samples(s: Spring) = (0..400).map { s.getInterpolation(it / 400f) }

    @Test fun startsAtRestAndLandsExactlyOnTarget() {
        for (d in listOf(0.5f, 0.76f, 0.95f, 1f)) {
            val s = Spring(d)
            assertEquals(0f, s.getInterpolation(0f), 0f)
            assertEquals(1f, s.getInterpolation(1f), 0f)
            assertEquals(1f, s.getInterpolation(1.2f), 0f)
        }
    }

    @Test fun focusSpringOvershootsByAHairOnly() {
        // The damping Motion.spring uses; Motion itself needs the Android runtime.
        val peak = samples(Spring(0.76f)).max()
        assertTrue("peak $peak", peak > 1.005f && peak < 1.05f)
    }

    @Test fun softSpringNeverVisiblyOvershoots() {
        assertTrue(samples(Spring(0.95f)).all { it <= 1.002f })
    }

    @Test fun mostOfTheTravelHappensInTheFirstThird() {
        assertTrue(Spring(0.76f).getInterpolation(1 / 3f) > 0.8f)
    }
}
