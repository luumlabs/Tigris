package com.leslie.tvhome

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Remote keys that some firmwares leave to the home screen instead of acting on them themselves:
 * their own launcher answers them, so with Lumen in its place they did nothing. Android acts on
 * the power key before any app sees it, so a power key that reaches an app at all is one the
 * system passed on; answering it here never doubles what the TV does by itself.
 *
 * Keys Lumen sends itself through local debugging (`input keyevent`) come from the virtual
 * keyboard; when one of those comes straight back, the firmware passes it on too, and it is not
 * answered again.
 */
object RemoteKeys {
    private val POWER = setOf(
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_SOFT_SLEEP,
    )

    /** The remote's dedicated HDMI keys, by port. */
    val HDMI = mapOf(
        KeyEvent.KEYCODE_TV_INPUT_HDMI_1 to 1, KeyEvent.KEYCODE_TV_INPUT_HDMI_2 to 2,
        KeyEvent.KEYCODE_TV_INPUT_HDMI_3 to 3, KeyEvent.KEYCODE_TV_INPUT_HDMI_4 to 4,
    )

    /** Keys that open live TV on the stock launcher. */
    val LIVE_TV = setOf(
        KeyEvent.KEYCODE_TV, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_LAST_CHANNEL,
    )

    fun isPower(code: Int) = code in POWER

    private val downs = HashMap<Int, Long>()

    /**
     * True on the release of a whole press: one whose press this screen saw too. Some firmwares
     * pass the power key on only while the TV is awake, so the release of the press that woke it
     * arrives alone, and must not put it straight back to sleep. Call with every event of the key;
     * main thread only.
     */
    fun pressed(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount == 0) downs[e.keyCode] = e.downTime
            return false
        }
        val down = downs.remove(e.keyCode)
        return e.action == KeyEvent.ACTION_UP && !e.isCanceled && down == e.downTime
    }

    private fun injected(e: KeyEvent) = e.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD

    // ---- power ----

    @Volatile private var inFlight = false
    @Volatile private var returnedAt = 0L
    @Volatile private var sent: String? = null

    /**
     * Puts the TV to sleep through local debugging, as its power key would; with "Full power off"
     * set, the keeper service then powers it down as the screen goes off. [onFail] runs on the main
     * thread, so the caller can offer another way: with false when local debugging can't be
     * reached, with true when it was but the keys sent came straight back (the firmware passes
     * those on too). Main thread only.
     */
    fun standby(ctx: Context, e: KeyEvent, onFail: (reached: Boolean) -> Unit) {
        val back = injected(e) && (inFlight || SystemClock.uptimeMillis() - returnedAt < BOUNCE_MS)
        // A second press while the first is still on its way is the same wish.
        if (!back && inFlight) return
        // KEYCODE_SLEEP is Android's own "go to sleep". If that is the key that reached us, or the
        // one we sent came back, the firmware passes it on, and the power key is tried once.
        val key = when {
            !back -> if (e.keyCode == KeyEvent.KEYCODE_SLEEP || e.keyCode == KeyEvent.KEYCODE_SOFT_SLEEP) "KEYCODE_POWER" else "KEYCODE_SLEEP"
            sent == "KEYCODE_SLEEP" -> "KEYCODE_POWER"
            else -> {
                sent = null
                returnedAt = 0L
                onFail(true)
                return
            }
        }
        sent = key
        inFlight = true
        val app = ctx.applicationContext
        Thread {
            val ok = LocalAdb.shell(app, "input keyevent $key") != null
            Handler(Looper.getMainLooper()).post {
                inFlight = false
                returnedAt = SystemClock.uptimeMillis()
                if (!ok) { sent = null; onFail(false) }
            }
        }.start()
    }

    // ---- HDMI ----

    @Volatile private var hdmiSentAt = 0L

    /** Called just before Lumen sends an HDMI key through local debugging. */
    fun sendingHdmiKey() { hdmiSentAt = SystemClock.uptimeMillis() }

    /**
     * Whether [e] is an HDMI key Lumen just sent, come back because the firmware passes it on
     * instead of switching: answering it would send it again, and again.
     */
    fun isHdmiEcho(e: KeyEvent) = injected(e) && SystemClock.uptimeMillis() - hdmiSentAt < HDMI_ECHO_MS

    private const val BOUNCE_MS = 2500L
    /** Covers the local-debugging connection, which can take a few seconds on a busy TV. */
    private const val HDMI_ECHO_MS = 8000L
}
