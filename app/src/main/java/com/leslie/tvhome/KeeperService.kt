package com.leslie.tvhome

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import java.util.concurrent.Executors

/**
 * Keeps the launcher process alive, cleans memory when the TV goes to
 * standby (the remote's power key only turns the screen off) and on a timer.
 */
class KeeperService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private var foregroundStarted = false

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (prefs.shutdownOnPowerKey && prefs.adbAuthorized) {
                Cleaner.shutdown(applicationContext)
            } else if (prefs.cleanOnStandby) {
                runClean(spareForeground = false)
            }
        }
    }

    /** Xiaomi switches ADB debugging off on wake; with [Prefs.keepAdb] it goes straight back on. */
    private val adbObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = Guard.ensureAdb(this@KeeperService)
    }

    private val periodic = object : Runnable {
        override fun run() {
            runClean(spareForeground = true)
            schedule()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Foreground first, before anything that touches disk. A startForegroundService() start
        // that has not reached startForeground() after five seconds makes the system throw
        // RemoteServiceException and kill the launcher; a cold start on a slow TV can burn that
        // long in Application/Activity startup before this method even runs.
        startForeground(NOTIFICATION_ID, notification())
        foregroundStarted = true
        prefs = Prefs(this)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, adbObserver)
        Guard.ensure(this)
        // START_STICKY may revive us without rebinding the enabled accessibility service.
        main.postDelayed({ Guard.rebindIfStalled(this) }, 5_000)
        schedule()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, I18n.text(R.string.i18n_keeperservice_001), NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_memory)
            .setContentTitle(I18n.text(R.string.i18n_keeperservice_002))
            .setContentText(I18n.text(R.string.i18n_keeperservice_003))
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A second start can arrive after the system reclaimed the foreground state.
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification())
            foregroundStarted = true
        }
        if (intent?.action == ACTION_RESCHEDULE) schedule()
        return START_STICKY
    }

    private fun schedule() {
        main.removeCallbacks(periodic)
        val h = prefs.cleanIntervalHours
        if (h > 0) main.postDelayed(periodic, h * 3_600_000L)
    }

    private fun runClean(spareForeground: Boolean) {
        val pm = getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvhome:clean").apply { acquire(30_000) }
        worker.execute {
            try {
                val fg = if (spareForeground) Cleaner.foregroundPackage(this) else null
                Cleaner.clean(this, fg, automatic = true)
            } finally {
                if (wl.isHeld) wl.release()
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        contentResolver.unregisterContentObserver(adbObserver)
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CH = "keeper"
        private const val NOTIFICATION_ID = 1
        const val ACTION_RESCHEDULE = "reschedule"

        fun start(ctx: Context, action: String? = null) {
            val intent = Intent(ctx, KeeperService::class.java).setAction(action)
            // The home screen calls this while it is already in the foreground: a plain
            // startService() is enough and cannot miss the five-second foreground-service
            // deadline that a cold start on a slow TV can blow. Boot and the notification-listener
            // revival run in the background and still need startForegroundService().
            if (inForeground(ctx)) ctx.startService(intent) else ctx.startForegroundService(intent)
        }

        private fun inForeground(ctx: Context): Boolean {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return false
            val pid = Process.myPid()
            return am.runningAppProcesses?.any {
                it.pid == pid && it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
            } == true
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        KeeperService.start(c)
        Guard.ensure(c)
        // The guard waits for a confirmed stock HOME event. Boot alone must not steal an input.
    }
}
