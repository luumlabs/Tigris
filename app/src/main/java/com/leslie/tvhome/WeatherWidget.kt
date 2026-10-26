package com.leslie.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The forecast behind the weather widget: now, the next six hours and the next six days, from
 * Open-Meteo for the city chosen in Settings ([Weather.City]; never located automatically).
 * Refreshed every half hour at most while the widget is on; the last answer is kept on disk for
 * up to [Weather.MAX_CACHE_MS], so the widget has something to show straight after a boot.
 */
object Forecast {
    data class Hour(val label: String, val code: Int, val day: Boolean, val temp: Int, val pop: Int)
    data class Day(val label: String, val code: Int, val hi: Int, val lo: Int, val pop: Int)
    data class Now(
        val city: String, val temp: Int, val feels: Int, val code: Int, val day: Boolean, val hi: Int, val lo: Int,
        val humidity: Int, val wind: Int, val windUnit: String,
        val hours: List<Hour>, val days: List<Day>, val at: Long,
    )

    private const val BACKOFF = 10 * 60_000L
    @Volatile private var memo: Pair<String, Now>? = null
    @Volatile private var failedAt = 0L

    private fun key(city: Weather.City, fahrenheit: Boolean) = "${city.name}|${city.latitude}|${city.longitude}|$fahrenheit"

    private fun file(ctx: Context) = File(ctx.filesDir, "weather-forecast.json")

    /** The last forecast known for [city], without waiting: memory, then disk. */
    fun cached(ctx: Context, city: Weather.City, fahrenheit: Boolean, clock24: Boolean): Now? {
        val k = key(city, fahrenheit)
        memo?.let { (mk, n) -> if (mk == k && System.currentTimeMillis() - n.at < Weather.MAX_CACHE_MS) return n }
        return runCatching {
            val f = file(ctx)
            if (!f.isFile) return null
            val o = JSONObject(f.readText())
            if (o.optString("tigris_key") != k) return null
            val at = o.optLong("tigris_at")
            if (System.currentTimeMillis() - at !in 0 until Weather.MAX_CACHE_MS) return null
            parse(o, clock24).also { memo = k to it }
        }.getOrNull()
    }

    /** Blocking. A fresh forecast when the last is older than half an hour (or [force]); the last one offline. */
    fun load(ctx: Context, city: Weather.City, fahrenheit: Boolean, clock24: Boolean, force: Boolean = false): Now? {
        val last = cached(ctx, city, fahrenheit, clock24)
        if (!force && last != null && System.currentTimeMillis() - last.at < Weather.FRESH_MS) return last
        if (!force && System.currentTimeMillis() - failedAt < BACKOFF) return last
        if (!city.latitude.isFinite() || !city.longitude.isFinite() ||
            city.latitude !in -90.0..90.0 || city.longitude !in -180.0..180.0) return last
        return try {
            val unit = if (fahrenheit) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else ""
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${city.latitude}&longitude=${city.longitude}" +
                "&current=temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m,wind_speed_10m" +
                "&hourly=temperature_2m,weather_code,is_day,precipitation_probability" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=7$unit"
            val o = JSONObject(Net.get(url).decodeToString())
            o.put("tigris_key", key(city, fahrenheit)).put("tigris_city", city.name.substringBefore(',').trim())
                .put("tigris_at", System.currentTimeMillis()).put("tigris_f", fahrenheit)
            file(ctx).writeText(o.toString())
            parse(o, clock24).also { memo = key(city, fahrenheit) to it; failedAt = 0L }
        } catch (e: Exception) {
            android.util.Log.w("Forecast", "load failed: ${e.message}")
            failedAt = System.currentTimeMillis()
            last
        }
    }

    /** Reads an Open-Meteo answer (with the fields [load] adds to it). */
    private fun parse(o: JSONObject, clock24: Boolean): Now {
        val cur = o.getJSONObject("current")
        val hourly = o.getJSONObject("hourly")
        val daily = o.getJSONObject("daily")
        val f = o.optBoolean("tigris_f", false)
        val times = hourly.getJSONArray("time")
        val nowHour = cur.getString("time").take(13)
        var start = 0
        for (k in 0 until times.length()) if (times.getString(k).take(13) == nowHour) { start = k; break }
        // Hours past the saved answer's own "now" are what is left of the forecast after a while offline.
        val savedAt = o.optLong("tigris_at", 0L)
        val elapsed = if (savedAt <= 0L) 0 else ((System.currentTimeMillis() - savedAt) / 3_600_000L).toInt().coerceAtLeast(0)
        start = (start + elapsed).coerceAtMost((times.length() - 6).coerceAtLeast(0))
        val locale = Locale.getDefault()
        val hourFormat = java.text.SimpleDateFormat(
            android.text.format.DateFormat.getBestDateTimePattern(locale, if (clock24) "HH" else "ha"), locale)
        val hours = (start until minOf(start + 6, times.length())).map { k ->
            val t = times.getString(k)
            val h = t.substring(11, 13).toIntOrNull() ?: 0
            val label = if (k == start) I18n.text(R.string.i18n_widgets_now)
            else hourFormat.format(java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, 0) }.time)
            Hour(
                label,
                hourly.getJSONArray("weather_code").optInt(k),
                hourly.getJSONArray("is_day").optInt(k, 1) == 1,
                hourly.getJSONArray("temperature_2m").optDouble(k).roundToInt(),
                hourly.optJSONArray("precipitation_probability")?.optInt(k) ?: 0,
            )
        }
        val dTimes = daily.getJSONArray("time")
        val weekday = java.text.SimpleDateFormat("EEE", locale)
        val days = (0 until dTimes.length()).map { k ->
            val date = runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dTimes.getString(k)) }.getOrNull()
            Day(
                if (k == 0) I18n.text(R.string.i18n_widgets_today) else date?.let { weekday.format(it) } ?: "",
                daily.getJSONArray("weather_code").optInt(k),
                daily.getJSONArray("temperature_2m_max").optDouble(k).roundToInt(),
                daily.getJSONArray("temperature_2m_min").optDouble(k).roundToInt(),
                daily.optJSONArray("precipitation_probability_max")?.optInt(k) ?: 0,
            )
        }.take(6)
        return Now(
            city = o.optString("tigris_city"),
            temp = cur.getDouble("temperature_2m").roundToInt(),
            feels = cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m")).roundToInt(),
            code = cur.optInt("weather_code"),
            day = cur.optInt("is_day", 1) == 1,
            hi = days.firstOrNull()?.hi ?: 0,
            lo = days.firstOrNull()?.lo ?: 0,
            humidity = cur.optInt("relative_humidity_2m"),
            wind = cur.optDouble("wind_speed_10m").roundToInt(),
            windUnit = if (f) "mph" else "km/h",
            hours = hours, days = days, at = savedAt,
        )
    }
}

/**
 * A weather glyph drawn in the style of Apple's symbols: multicolour (a golden sun, a pale moon,
 * white clouds, blue rain, a yellow bolt) or, with [mono], all in white for clear glass, the parts
 * set apart by opacity. Scales to any size, so no image files.
 */
class WeatherIcon(private var code: Int = 0, private var day: Boolean = true, private val mono: Boolean = false) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val tmp = Path()

    fun set(code: Int, day: Boolean) { this.code = code; this.day = day; invalidateSelf() }

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        if (s <= 0f) return
        // The knockout around a cloud clears pixels: only ever inside a layer of the icon's own.
        val knock = mono && (code == 1 || code == 2)
        if (knock) c.saveLayer(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), null) else c.save()
        c.translate(b.left + (b.width() - s) / 2, b.top + (b.height() - s) / 2)
        c.scale(s, s)
        when (code) {
            0 -> if (day) sun(c, 0.5f, 0.5f, 0.22f) else moon(c, 0.5f, 0.5f, 0.3f)
            1, 2 -> {
                if (day) sun(c, 0.62f, 0.36f, 0.16f) else moon(c, 0.64f, 0.34f, 0.2f)
                if (mono) knockout(c, 0.08f, 0.4f, 0.74f)
                cloud(c, 0.08f, 0.4f, 0.74f, if (code == 1) 0.85f else 1f)
            }
            3 -> { cloud(c, 0.22f, 0.24f, 0.62f, 0.55f); cloud(c, 0.06f, 0.34f, 0.84f, 1f) }
            45, 48 -> { cloud(c, 0.1f, 0.16f, 0.8f, 1f); lines(c) }
            51, 53, 55, 56, 57 -> { cloud(c, 0.1f, 0.12f, 0.8f, 1f); drops(c, short = true) }
            61, 63, 65, 66, 67, 80, 81, 82 -> { cloud(c, 0.1f, 0.12f, 0.8f, 1f); drops(c, short = false) }
            71, 73, 75, 77, 85, 86 -> { cloud(c, 0.1f, 0.12f, 0.8f, 1f); flakes(c) }
            95, 96, 99 -> { cloud(c, 0.1f, 0.1f, 0.8f, 1f); bolt(c) }
            else -> cloud(c, 0.1f, 0.3f, 0.8f, 1f)
        }
        c.restore()
    }

    private fun sun(c: Canvas, x: Float, y: Float, r: Float) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = r * 0.28f
        p.shader = null
        p.color = if (mono) Color.WHITE else Color.rgb(255, 204, 0)
        for (k in 0 until 8) {
            val a = Math.PI * k / 4
            val c1 = cos(a).toFloat(); val s1 = sin(a).toFloat()
            c.drawLine(x + c1 * r * 1.42f, y + s1 * r * 1.42f, x + c1 * r * 1.85f, y + s1 * r * 1.85f, p)
        }
        p.style = Paint.Style.FILL
        p.shader = if (mono) null else RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.4f, Color.rgb(255, 232, 92), Color.rgb(255, 176, 0), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
        p.shader = null
    }

    private fun moon(c: Canvas, x: Float, y: Float, r: Float) {
        path.reset(); tmp.reset()
        path.addCircle(x, y, r, Path.Direction.CW)
        tmp.addCircle(x + r * 0.55f, y - r * 0.35f, r * 0.85f, Path.Direction.CW)
        path.op(tmp, Path.Op.DIFFERENCE)
        p.style = Paint.Style.FILL
        if (mono) { p.shader = null; p.color = Color.WHITE }
        else p.shader = LinearGradient(x - r, y - r, x + r, y + r, Color.rgb(255, 250, 225), Color.rgb(214, 210, 190), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
    }

    private fun cloudPath(x: Float, y: Float, w: Float) {
        val h = w * 0.56f
        path.reset(); tmp.reset()
        path.addRoundRect(RectF(x, y + h * 0.48f, x + w, y + h), h * 0.26f, h * 0.26f, Path.Direction.CW)
        tmp.addCircle(x + w * 0.32f, y + h * 0.52f, h * 0.34f, Path.Direction.CW); path.op(tmp, Path.Op.UNION); tmp.reset()
        tmp.addCircle(x + w * 0.58f, y + h * 0.38f, h * 0.42f, Path.Direction.CW); path.op(tmp, Path.Op.UNION); tmp.reset()
        tmp.addCircle(x + w * 0.8f, y + h * 0.66f, h * 0.28f, Path.Direction.CW); path.op(tmp, Path.Op.UNION)
    }

    /** A cloud filling [w] from ([x], [y]); [shade] dims a cloud set behind another. */
    private fun cloud(c: Canvas, x: Float, y: Float, w: Float, shade: Float) {
        cloudPath(x, y, w)
        val h = w * 0.56f
        p.style = Paint.Style.FILL
        if (mono) {
            // A cloud in front of a sun or moon covers it; one behind another is set back by opacity.
            p.shader = null
            p.color = Color.argb((255 * shade * shade).toInt().coerceIn(90, 255), 255, 255, 255)
        } else {
            val top = Color.rgb((255 * shade).toInt(), (255 * shade).toInt(), (255 * shade).toInt())
            val bottom = Color.rgb((214 * shade).toInt(), (224 * shade).toInt(), (236 * shade).toInt())
            p.shader = LinearGradient(0f, y, 0f, y + h, top, bottom, Shader.TileMode.CLAMP)
        }
        c.drawPath(path, p)
        p.shader = null
    }

    /** Clears a thin outline around the cloud so a white cloud stands apart from a white sun. */
    private fun knockout(c: Canvas, x: Float, y: Float, w: Float) {
        cloudPath(x, y, w)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.07f
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        c.drawPath(path, p)
        p.xfermode = null
        p.style = Paint.Style.FILL
    }

    private fun drops(c: Canvas, short: Boolean) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 0.07f
        p.color = if (mono) Color.argb(210, 255, 255, 255) else Color.rgb(90, 200, 250)
        for (k in 0 until 3) {
            val x = 0.3f + k * 0.2f
            if (short) { c.drawPoint(x - 0.02f, 0.78f, p); c.drawPoint(x - 0.06f, 0.9f, p) }
            else c.drawLine(x, 0.68f, x - 0.07f, 0.9f, p)
        }
    }

    private fun flakes(c: Canvas) {
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        for (k in 0 until 3) c.drawCircle(0.3f + k * 0.2f, 0.78f + (k % 2) * 0.08f, 0.045f, p)
    }

    private fun bolt(c: Canvas) {
        path.reset()
        path.moveTo(0.52f, 0.6f); path.lineTo(0.38f, 0.8f); path.lineTo(0.5f, 0.8f)
        path.lineTo(0.44f, 0.97f); path.lineTo(0.64f, 0.74f); path.lineTo(0.52f, 0.74f); path.lineTo(0.6f, 0.6f); path.close()
        p.style = Paint.Style.FILL
        p.color = if (mono) Color.WHITE else Color.rgb(255, 204, 0)
        c.drawPath(path, p)
    }

    private fun lines(c: Canvas) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 0.06f
        p.color = if (mono) Color.argb(180, 255, 255, 255) else Color.argb(220, 210, 216, 226)
        c.drawLine(0.16f, 0.74f, 0.84f, 0.74f, p)
        c.drawLine(0.26f, 0.88f, 0.74f, 0.88f, p)
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * The weather on the home screen, in the layout of Apple's medium Weather widget on clear Liquid
 * Glass: the city, the temperature in light figures, the condition and the day's high and low,
 * and the next hours along the bottom, all in white with monochrome symbols. OK opens the
 * six-day forecast.
 */
class WeatherWidget(ctx: Context, source: GlassSource, w: Int, h: Int) : WidgetCard(ctx, source, w, h) {
    private val city = ctx.text(14f, Color.argb(235, 255, 255, 255), weight = 600).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val temp = ctx.text(38f, weight = 200).apply { includeFontPadding = false; letterSpacing = -0.03f }
    private val icon = WeatherIcon(mono = true)
    private val iconView = ImageView(ctx).apply { setImageDrawable(icon) }
    private val condition = ctx.text(13f, weight = 600).apply { gravity = Gravity.END; maxLines = 1 }
    private val range = ctx.text(13f, Color.argb(190, 255, 255, 255), weight = 500).apply { gravity = Gravity.END }
    private val hours = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val empty = ctx.text(14f, Color.argb(200, 255, 255, 255), weight = 500).apply { gravity = Gravity.CENTER }
    var now: Forecast.Now? = null
        private set

    init {
        contentDescription = I18n.text(R.string.i18n_widgets_weather)
        val p = dp(18)
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(city)
            addView(temp, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
        }
        body.addView(left, FrameLayout.LayoutParams(w / 2, -2).apply { leftMargin = p; topMargin = dp(13) })
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(iconView, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(condition, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            addView(range, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
        }
        body.addView(right, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { rightMargin = p; topMargin = dp(14) })
        body.addView(hours, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = p - dp(4); rightMargin = p - dp(4); bottomMargin = dp(12) })
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        // Clear glass can sit on a bright wallpaper: a soft shadow keeps white type legible.
        listOf(city, temp, condition, range).forEach { it.setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), Color.argb(90, 0, 0, 0)) }
        show(null, I18n.text(R.string.i18n_widgets_weather_loading))
    }

    /** Shows [n], or [message] while there is none (loading, no city chosen, unavailable). */
    fun show(n: Forecast.Now?, message: String = "") {
        now = n
        val has = n != null
        empty.text = message
        empty.visibility = if (has) View.GONE else View.VISIBLE
        for (v in listOf(city, temp, iconView, condition, range, hours)) v.visibility = if (has) View.VISIBLE else View.INVISIBLE
        if (n == null) return
        city.text = n.city
        temp.text = "${n.temp}°"
        icon.set(n.code, n.day)
        condition.text = Weather.label(n.code)
        range.text = I18n.text(R.string.i18n_widgets_range, n.hi, n.lo)
        hours.removeAllViews()
        for (hr in n.hours) {
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(context.text(11f, Color.argb(175, 255, 255, 255), weight = 600).apply { text = hr.label; gravity = Gravity.CENTER; maxLines = 1 })
                addView(ImageView(context).apply { setImageDrawable(WeatherIcon(hr.code, hr.day, mono = true)) }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { topMargin = dp(3) })
                addView(context.text(13f, weight = 600).apply {
                    text = "${hr.temp}°"; gravity = Gravity.CENTER
                    setShadowLayer(dp(4).toFloat(), 0f, 0f, Color.argb(80, 0, 0, 0))
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            }
            hours.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
}

/**
 * The forecast on OK: a frosted sheet in the manner of Apple's Weather app, with today in large
 * type, the hours ahead, and six days whose temperature bars sit on one scale for the week,
 * coloured from cool to warm. OK or Back closes it.
 */
class WeatherSheet(private val host: FrameLayout, private val n: Forecast.Now, backdrop: android.graphics.Bitmap?) {
    private val ctx = host.context
    val root = FrameLayout(ctx).apply {
        isClickable = true
        isFocusable = true
        alpha = 0f
        translationZ = ctx.dp(200).toFloat()
        outlineProvider = null
    }
    private val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private var onDismiss: (() -> Unit)? = null
    private var leaving = false

    init {
        root.addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(backdrop ?: WidgetGlass.frostedSnapshot(host)) },
            FrameLayout.LayoutParams(-1, -1))
        // Neutral, like the widget: the frosted screen dimmed, no sky colour.
        root.addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(110, 0, 0, 0), Color.argb(170, 0, 0, 0)))
        }, FrameLayout.LayoutParams(-1, -1))
        card.addView(ctx.text(30f, weight = 500).apply { text = n.city; gravity = Gravity.CENTER })
        card.addView(ctx.text(84f, weight = 150).apply { text = "${n.temp}°"; gravity = Gravity.CENTER; includeFontPadding = false })
        card.addView(ctx.text(19f, weight = 600).apply { text = Weather.label(n.code); gravity = Gravity.CENTER })
        card.addView(ctx.text(17f, Color.argb(220, 255, 255, 255), weight = 500).apply {
            text = I18n.text(R.string.i18n_widgets_details, n.hi, n.lo, n.feels, n.humidity, n.wind, n.windUnit)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(4) })
        val panelW = ctx.dp(620)
        val hoursRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            background = glassCapsule(ctx, ctx.dp(22).toFloat(), Color.argb(34, 255, 255, 255))
        }
        for (hr in n.hours) hoursRow.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ctx.text(13f, Color.argb(220, 255, 255, 255), weight = 600).apply { text = hr.label; maxLines = 1 })
            addView(ImageView(ctx).apply { setImageDrawable(WeatherIcon(hr.code, hr.day, mono = true)) }, LinearLayout.LayoutParams(ctx.dp(26), ctx.dp(26)).apply { topMargin = ctx.dp(6) })
            if (hr.pop >= 20) addView(ctx.text(11f, Color.argb(200, 255, 255, 255), weight = 700).apply { text = I18n.text(R.string.i18n_widgets_percent, hr.pop) })
            addView(ctx.text(16f, weight = 600).apply { text = "${hr.temp}°" }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(4) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(hoursRow, LinearLayout.LayoutParams(panelW, -2).apply { topMargin = ctx.dp(22) })
        val daysBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(10), ctx.dp(20), ctx.dp(10))
            background = glassCapsule(ctx, ctx.dp(22).toFloat(), Color.argb(34, 255, 255, 255))
        }
        daysBox.addView(ctx.text(12f, Color.argb(170, 255, 255, 255), weight = 700).apply {
            text = I18n.text(R.string.i18n_widgets_forecast); letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ctx.dp(4) })
        val weekLo = n.days.minOfOrNull { it.lo } ?: 0
        val weekHi = n.days.maxOfOrNull { it.hi } ?: 1
        n.days.forEachIndexed { k, d ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(ctx.text(17f, weight = 600).apply { text = d.label; maxLines = 1 }, LinearLayout.LayoutParams(ctx.dp(80), -2))
            row.addView(ImageView(ctx).apply { setImageDrawable(WeatherIcon(d.code, true, mono = true)) }, LinearLayout.LayoutParams(ctx.dp(26), ctx.dp(26)))
            row.addView(ctx.text(12f, Color.argb(200, 255, 255, 255), weight = 700).apply {
                text = if (d.pop >= 20) I18n.text(R.string.i18n_widgets_percent, d.pop) else ""; gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ctx.dp(44), -2))
            row.addView(ctx.text(17f, Color.argb(170, 255, 255, 255), weight = 600).apply { text = "${d.lo}°"; gravity = Gravity.END },
                LinearLayout.LayoutParams(ctx.dp(48), -2))
            row.addView(RangeBar(ctx, weekLo, weekHi, d.lo, d.hi, if (k == 0) n.temp else null),
                LinearLayout.LayoutParams(0, ctx.dp(6), 1f).apply { marginStart = ctx.dp(14); marginEnd = ctx.dp(14) })
            row.addView(ctx.text(17f, weight = 600).apply { text = "${d.hi}°" }, LinearLayout.LayoutParams(ctx.dp(48), -2))
            daysBox.addView(row, LinearLayout.LayoutParams(-1, ctx.dp(42)))
        }
        card.addView(daysBox, LinearLayout.LayoutParams(panelW, -2).apply { topMargin = ctx.dp(14) })
        root.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.setOnKeyListener { _, code, e ->
            val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (e.action == KeyEvent.ACTION_UP && (ok || code == KeyEvent.KEYCODE_BACK)) { dismiss(); true }
            // Nothing else here takes keys: keep them from reaching the home screen behind.
            else code != KeyEvent.KEYCODE_HOME && code != KeyEvent.KEYCODE_VOLUME_UP &&
                code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_MUTE
        }
    }

    fun onDismiss(f: () -> Unit) = apply { onDismiss = f }

    fun show(): WeatherSheet {
        host.addView(root, FrameLayout.LayoutParams(-1, -1))
        root.animate().alpha(1f).setDuration(220).start()
        card.scaleX = 0.92f; card.scaleY = 0.92f
        card.tweenScale(1f, 560, WidgetMotion.spring)
        for (k in 0 until card.childCount) card.getChildAt(k).apply {
            alpha = 0f; translationY = ctx.dp(16).toFloat()
            animate().alpha(1f).translationY(0f).setStartDelay(60L + k * 45L).setDuration(460).setInterpolator(WidgetMotion.emphasized).start()
        }
        root.requestFocus()
        return this
    }

    val isShowing get() = root.parent != null

    fun dismiss() {
        if (leaving || !isShowing) return
        leaving = true
        card.animate().scaleX(0.96f).scaleY(0.96f).alpha(0f).setDuration(160).setInterpolator(WidgetMotion.exit).start()
        root.animate().alpha(0f).setDuration(200).withEndAction { host.removeView(root); onDismiss?.invoke() }.start()
    }

    /** A day's range on the week's scale, cool blue to warm orange, with today's temperature as a dot. */
    private class RangeBar(ctx: Context, val weekLo: Int, val weekHi: Int, val lo: Int, val hi: Int, val now: Int?) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = h / 2
            p.shader = null
            p.color = Color.argb(60, 0, 0, 0)
            c.drawRoundRect(0f, 0f, w, h, r, r, p)
            val span = (weekHi - weekLo).coerceAtLeast(1).toFloat()
            val x0 = (lo - weekLo) / span * w
            val x1 = ((hi - weekLo) / span * w).coerceAtLeast(x0 + h)
            p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Color.rgb(94, 200, 250), Color.rgb(160, 220, 120), Color.rgb(255, 204, 0), Color.rgb(255, 140, 60)),
                null, Shader.TileMode.CLAMP)
            c.drawRoundRect(x0, 0f, x1, h, r, r, p)
            p.shader = null
            now?.let {
                val x = ((it - weekLo) / span * w).coerceIn(x0 + r, x1 - r)
                p.color = Color.argb(90, 0, 0, 0); c.drawCircle(x, r, r + 2f, p)
                p.color = Color.WHITE; c.drawCircle(x, r, r, p)
            }
        }
    }
}
