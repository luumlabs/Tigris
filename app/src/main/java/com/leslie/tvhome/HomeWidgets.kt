package com.leslie.tvhome

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.util.concurrent.Executor

/**
 * The optional widgets above the dock: the weather ([Prefs.weatherEnabled], for the city chosen in
 * Settings) and the week's Top 10 ([Prefs.chartsEnabled]), each on a pane of Liquid Glass, with
 * the forecast sheet, the full charts and the "where to watch" chooser they open. Both are off by
 * default; while off, nothing here loads, bakes or asks the network for anything.
 *
 * [HomeActivity] owns the screen and calls in: [resume] / [pause], [refresh] from its minute
 * ticker, [scrolled] and [shelf] as the dock moves, [glass] when the wallpaper (or a video frame)
 * changes, and [back] for the Back key.
 */
class HomeWidgets(
    private val home: HomeActivity,
    private val root: FrameLayout,
    private val repo: AppRepo,
    private val io: Executor,
    private val net: Executor,
    private val callbacks: Callbacks,
) {
    /** What the widgets need from the home screen. */
    interface Callbacks {
        fun showSheet(sheet: Sheet, restoreView: View?)
        fun setGridBlocked(blocked: Boolean)
        /** Back down to the dock tile the user came up from. */
        fun focusDock()
        /** Up to the buttons in the top-right corner. */
        fun focusHeader()
    }

    private val prefs = Prefs(home)
    private val main = Handler(Looper.getMainLooper())
    private val source = GlassSource()
    private val sw = home.resources.displayMetrics.widthPixels
    private val sh = home.resources.displayMetrics.heightPixels
    private val h = home.dp(140)
    val weather = WeatherWidget(home, source, home.dp(330), h)
    val charts = ChartsWidget(home, source, home.dp(470), h)
    val row = LinearLayout(home).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }
    var enabled = false
        private set
    @Volatile private var loading = false
    private var weatherSheet: WeatherSheet? = null
    private var chartsView: ChartsView? = null
    /** The still wallpaper the glass is baked from; null while the wallpaper is a video. */
    @Volatile private var base: Bitmap? = null
    @Volatile private var bakeGen = 0
    private var scrolledAway = false
    private var shelfShown = false

    /** The full-screen overlay a widget opened (forecast or charts), which holds focus while it is up. */
    val overlay: View? get() = weatherSheet?.root ?: chartsView?.root

    init {
        row.addView(weather, LinearLayout.LayoutParams(weather.w, h))
        row.addView(charts, LinearLayout.LayoutParams(charts.w, h).apply { marginStart = home.dp(20) })
        // The glass is baked for where the widgets end up, once they are laid out (or switched on or off).
        row.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (l != ol || t != ot || r != or || b != ob) row.post { bake() }
        }
        for (w in listOf<WidgetCard>(weather, charts)) w.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { callbacks.focusDock(); true }
                KeyEvent.KEYCODE_DPAD_UP -> { callbacks.focusHeader(); true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val list = shown()
                    val next = list.getOrNull(list.indexOf(w) + if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    if (next != null) next.requestFocus() else Motion.bump(w.body, if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1, 0)
                    true
                }
                else -> false
            }
        }
        weather.setOnClickListener { openWeather() }
        charts.setOnClickListener { openCharts() }
    }

    /** Puts the row into [scene], [above] px over the top of the dock, starting at [left]. */
    fun attach(scene: FrameLayout, left: Int, dockTop: Int) {
        scene.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h).apply {
            leftMargin = left; topMargin = dockTop - home.dp(26) - h
        })
        // What was known last time, straight away; fresh data follows on resume.
        io.execute {
            val city = city()
            val forecast = city?.let { Forecast.cached(home, it, prefs.weatherFahrenheit, prefs.clock24) }
            val list = Charts.cached(home)
            main.post {
                if (home.isDestroyed) return@post
                forecast?.let { if (weather.now == null) weather.show(it) }
                list?.let { if (charts.charts.isEmpty()) charts.show(it) }
            }
        }
    }

    private fun city(): Weather.City? = prefs.weatherCity.takeIf { it.isNotBlank() }
        ?.let { Weather.City(it, prefs.weatherLatitude, prefs.weatherLongitude) }

    /** Picks up the settings: which widgets are on. */
    fun apply() {
        weather.visibility = if (prefs.weatherEnabled) View.VISIBLE else View.GONE
        charts.visibility = if (prefs.chartsEnabled) View.VISIBLE else View.GONE
        enabled = prefs.weatherEnabled || prefs.chartsEnabled
        updateVisibility()
        if (prefs.weatherEnabled && city() == null) weather.show(null, I18n.text(R.string.weather_weather_pending))
    }

    fun resume() {
        apply()
        refresh()
        if (chartsView == null && prefs.chartsEnabled) charts.start()
    }

    /** Leaving for an app or standby: the rotation stops; what is open stays for the way back. */
    fun pause() {
        charts.stop()
    }

    /** Closes the forecast or the full Top 10 (Home pressed, the intro starting). */
    fun closeOverlays() {
        weatherSheet?.dismiss()
        chartsView?.close()
    }

    fun destroy() {
        bakeGen++
        charts.stop()
    }

    /** Fetches what is stale (the weather after half an hour, the charts after six), off the main thread. */
    fun refresh(force: Boolean = false) {
        // The home screen's executors are shut down with it.
        if (!enabled || loading || home.isDestroyed) return
        loading = true
        val city = city()
        val fahrenheit = prefs.weatherFahrenheit
        val clock24 = prefs.clock24
        net.execute {
            val forecast = if (prefs.weatherEnabled && city != null) Forecast.load(home, city, fahrenheit, clock24, force) else null
            val list = if (prefs.chartsEnabled) Charts.load(home, force) else null
            val country = if (prefs.chartsEnabled) Charts.country(home) else ""
            main.post {
                loading = false
                if (home.isDestroyed) return@post
                if (prefs.weatherEnabled) when {
                    city == null -> weather.show(null, I18n.text(R.string.weather_weather_pending))
                    forecast != null -> if (forecast !== weather.now) weather.show(forecast)
                    weather.now == null -> weather.show(null, I18n.text(R.string.weather_weather_unavailable))
                }
                if (prefs.chartsEnabled) {
                    charts.country = country
                    if (list != null && list !== charts.charts) charts.show(list)
                    else if (list == null && charts.charts.isEmpty()) charts.show(null, failed = true)
                }
            }
        }
    }

    // ---- placement ----

    /** The dock scrolled by [off] px: the widgets ride up with it and give way to the rows. */
    fun scrolled(off: Float, expansion: Float) {
        row.translationY = -off
        if (!shelfShown) row.alpha = (1f - expansion * 1.6f).coerceIn(0f, 1f)
        val away = off > 0f
        if (away != scrolledAway) {
            scrolledAway = away
            updateVisibility()
            if (!away) bake()
        }
    }

    /** Top Shelf content fills the space above the dock: the widgets step aside meanwhile. */
    fun shelf(visible: Boolean) {
        if (visible == shelfShown) return
        shelfShown = visible
        if (!enabled) return
        if (!visible && !scrolledAway) row.visibility = View.VISIBLE
        row.animate().alpha(if (visible || scrolledAway) 0f else 1f).setDuration(if (visible) 260 else 420)
            .withEndAction { updateVisibility(); if (!shelfShown) bake() }.start()
    }

    private fun updateVisibility() {
        row.visibility = when {
            !enabled -> View.GONE
            scrolledAway || shelfShown -> View.INVISIBLE
            else -> View.VISIBLE
        }
    }

    /** The widgets showing, left to right. */
    fun shown(): List<WidgetCard> =
        if (!enabled || row.visibility != View.VISIBLE || row.alpha < 0.5f) emptyList()
        else listOf<WidgetCard>(weather, charts).filter { it.visibility == View.VISIBLE }

    /** The widget closest across the screen to [from], if any is showing. */
    fun nearest(from: View): WidgetCard? {
        val list = shown().ifEmpty { return null }
        val at = IntArray(2)
        from.getLocationOnScreen(at)
        val x = at[0] + from.width / 2
        return list.minByOrNull { w -> w.getLocationOnScreen(at); kotlin.math.abs(at[0] + w.width / 2 - x) }
    }

    /** Whether focus is on a widget or in something it opened, which a reload must leave alone. */
    fun holdsFocus() = row.hasFocus() || overlay != null

    fun setBlocked(blocked: Boolean) {
        row.descendantFocusability = if (blocked) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    // ---- glass ----

    /**
     * What is behind the widgets changed: [blurred] for their live glass, and [still], the full
     * picture, to bake clear glass from (null while the wallpaper is a video, whose glass stays live).
     */
    fun glass(blurred: Bitmap?, still: Bitmap?) {
        source.wall = blurred
        base = still
        if (still == null) {
            bakeGen++
            weather.glass.baked = null
            charts.glass.baked = null
        } else bake()
    }

    /** Bakes Liquid Glass for each widget over exactly what is behind it; until then the glass is live. */
    private fun bake() {
        val still = base ?: return
        if (!enabled || scrolledAway || shelfShown || home.isDestroyed) return
        val at = IntArray(2)
        val jobs = shown().mapNotNull { w ->
            if (w.width == 0) return@mapNotNull null
            w.getLocationInWindow(at)
            w to Rect(at[0], at[1], at[0] + w.w, at[1] + w.h)
        }.ifEmpty { return }
        val radius = home.dp(26).toFloat()
        val gen = ++bakeGen
        io.execute {
            if (gen != bakeGen) return@execute
            // A light frost: the widgets are clear glass, so the picture behind reads through.
            val baked = jobs.map { (w, r) ->
                w to LiquidGlass.bake(still, sw, sh, r, radius, frostStrength = 0.35f).also { it.prepareToDraw() }
            }
            main.post {
                if (gen != bakeGen || home.isDestroyed) return@post
                for ((w, b) in baked) w.glass.baked = b
            }
        }
    }

    /** The home screen is hidden: pictures that can be decoded again go, the ones showing stay. */
    fun trim() {
        ChartImages.trim()
        if (chartsView == null) charts.trim()
    }

    // ---- what the widgets open ----

    /** Back while a widget or what it opened has focus; false when there is nothing of ours to close. */
    fun back(): Boolean = when {
        weatherSheet != null -> { weatherSheet?.dismiss(); true }
        chartsView != null -> { chartsView?.close(); true }
        row.hasFocus() -> { callbacks.focusDock(); true }
        else -> false
    }

    private fun openWeather() {
        val n = weather.now ?: run {
            Toast.makeText(home, I18n.text(if (city() == null) R.string.weather_weather_pending else R.string.weather_weather_unavailable),
                Toast.LENGTH_SHORT).show()
            refresh(force = city() != null)
            return
        }
        callbacks.setGridBlocked(true)
        // The home scene as drawn for the glass: a video wallpaper's frame isn't in a software draw.
        val backdrop = WidgetGlass.frostedSnapshot(root) { c -> if (!home.drawGlassScene(c)) root.draw(c) }
        weatherSheet = WeatherSheet(root, n, backdrop).onDismiss {
            weatherSheet = null
            callbacks.setGridBlocked(false)
            weather.requestFocus()
        }.show()
    }

    /** OK on the Top 10 widget: where to watch the title showing, or the full charts. */
    private fun openCharts() {
        val t = charts.charts.getOrNull(charts.current)?.items?.getOrNull(charts.item)
        if (t == null) {
            Toast.makeText(home, I18n.text(R.string.i18n_charts_loading), Toast.LENGTH_SHORT).show()
            refresh(force = true)
            return
        }
        showWatchOptions(t) { openChartsView() }
    }

    /**
     * Asks where to watch [t]: the installed apps that can open it (streaming services at the
     * title where they take its link, Nuvio and Stremio at the title by its IMDb id), each with its
     * icon, and [browse] (the full Top 10) when given. Only options that resolve on this TV appear.
     */
    private fun showWatchOptions(t: Charts.Title, browse: (() -> Unit)? = null) {
        if (home.isDestroyed) return
        io.execute {
            val apps = repo.allApps()
            val options = WatchOptions.forTitle(home, t, apps)
            val elsewhere = WatchOptions.elsewhere(t, options, apps)
            main.post {
                if (home.isDestroyed || chooser?.isShowing == true) return@post
                val message = when {
                    options.isEmpty() && elsewhere.isEmpty() -> I18n.text(R.string.i18n_charts_nowhere)
                    options.isEmpty() -> I18n.text(R.string.i18n_charts_not_installed, elsewhere.take(4).joinToString(", "))
                    elsewhere.isNotEmpty() -> I18n.text(R.string.i18n_charts_choose_also, elsewhere.take(3).joinToString(", "))
                    else -> I18n.text(R.string.i18n_charts_choose)
                }
                val sheet = Sheet(root).title(t.name).message(message)
                // The sheet doesn't scroll: the first few, services first, are what fits on screen.
                for (o in options.take(if (browse != null) 4 else 5)) sheet.appButton(o.label, o.icon) { WatchOptions.open(home, o) }
                browse?.let { b -> sheet.button(I18n.text(R.string.i18n_charts_see_all)) { b() } }
                sheet.button(I18n.text(R.string.i18n_charts_cancel)) {}
                chooser = sheet
                // From the widget, focus returns to it; over the full charts, to them.
                callbacks.showSheet(sheet, charts)
            }
        }
    }

    /** The chooser showing: a second OK while the apps were being looked up must not stack another. */
    private var chooser: Sheet? = null

    private fun openChartsView() {
        val list = charts.charts
        if (list.isEmpty() || chartsView != null) return
        callbacks.setGridBlocked(true)
        charts.stop()
        chartsView = ChartsView(home, root, list, charts.current, charts.item, charts.country, repo, { t -> showWatchOptions(t) }) {
            chartsView = null
            callbacks.setGridBlocked(false)
            charts.requestFocus()
            if (prefs.chartsEnabled) charts.start()
        }.show()
    }
}
