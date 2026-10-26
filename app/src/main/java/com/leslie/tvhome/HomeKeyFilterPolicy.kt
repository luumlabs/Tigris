package com.leslie.tvhome

/**
 * Decide whether the home guard should request ALL remote-control key events.
 *
 * Android <= 11 normally needs Home interception to avoid a long background-activity
 * launch delay. Coocaa / Skyworth Android 11 firmware reported in issue #15 consumes
 * Home before AccessibilityService.onKeyEvent; asking for the entire key stream instead
 * risks losing a volume KEY_UP and causing runaway volume changes.
 *
 * Limit the exception to this manufacturer AND OS version until other devices have
 * been tested. Window-state-based home detection remains enabled.
 *
 * While Tigris is the default home, Home already comes to it and the guard lets every key
 * through (HomeGuardService.onKeyEvent), so the stream is not asked for at all: each key
 * would only detour through the service, and in some apps a press then reads as held and
 * moves twice.
 */
internal object HomeKeyFilterPolicy {
    fun shouldRequestFilter(
        apiLevel: Int,
        isCoocaa: Boolean,
        homeFilterRequested: Boolean,
        isDefaultHome: Boolean = false,
    ): Boolean {
        if (isDefaultHome) return false
        if (isCoocaa && apiLevel == 30) return false // Android 11 / API 30
        return homeFilterRequested || apiLevel <= 30
    }
}
