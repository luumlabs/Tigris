package com.leslie.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/*
 * The shared pieces of the optional home-screen widgets (weather and Top 10): their motion curves,
 * a small HTTP and picture cache, and the Liquid Glass pane each widget sits on. Nothing here runs
 * unless a widget is switched on in Settings.
 */

/** Springs and curves for the widgets, kept apart from [Motion] so the rest of the home screen keeps its feel. */
object WidgetMotion {
    /** Focus: a quick spring that overshoots by a hair (about 3 %) and settles, as tvOS does. */
    val spring = Spring(0.76f)
    /** Lifting a focused poster: a firm spring whose overshoot (about 1 %) reads as weight, not bounce. */
    val lift = Spring(0.84f)
    /** A spring with no visible overshoot, for things that should glide rather than bounce. */
    val soft = Spring(0.95f)
    /** Scrolling and page changes: fast out of the gate, a long gentle landing. */
    val emphasized: android.view.animation.Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** Leaving the screen: picks up speed and is gone. */
    val exit: android.animation.TimeInterpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
}

/**
 * A damped spring from 0 to 1, as a time curve: [damping] 1 is critically damped (no overshoot),
 * lower values overshoot and settle. The stiffness is chosen so the motion has died down to a
 * thousandth of its travel by the end of the animation's duration.
 */
class Spring(damping: Float = 0.8f) : android.animation.TimeInterpolator {
    private val zeta = damping.coerceIn(0.3f, 1f).toDouble()
    // e^(-ζω) = 0.001 at t = 1.
    private val omega = 6.9 / zeta
    private val omegaD = omega * kotlin.math.sqrt((1 - zeta * zeta).coerceAtLeast(0.0))

    override fun getInterpolation(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val x = t.toDouble()
        val decay = kotlin.math.exp(-zeta * omega * x)
        val v = if (omegaD < 1e-3) 1 - decay * (1 + omega * x)
        else 1 - decay * (kotlin.math.cos(omegaD * x) + zeta * omega / omegaD * kotlin.math.sin(omegaD * x))
        return v.toFloat()
    }
}

/** The capsule of an idle control: a sliver of clear glass with a hairline rim lit from above. */
fun glassCapsule(ctx: Context, radius: Float, fill: Int = Color.argb(40, 255, 255, 255)): Drawable =
    GradientDrawable().apply {
        cornerRadius = radius
        colors = intArrayOf(Color.argb((Color.alpha(fill) * 1.35f).toInt().coerceAtMost(255), 255, 255, 255), fill)
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setStroke(ctx.dp(1), Color.argb(52, 255, 255, 255))
    }

/** Small HTTP helpers for the widgets: short timeouts, a browser user agent, HTTPS only. */
object Net {
    private const val UA = "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    fun get(url: String, headers: Map<String, String> = emptyMap()): ByteArray = request(url, null, headers)

    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): ByteArray =
        request(url, body.toByteArray(), headers + ("Content-Type" to "application/json"))

    private fun request(url: String, body: ByteArray?, headers: Map<String, String>): ByteArray {
        require(url.startsWith("https://"))
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "en")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.outputStream.use { it.write(body) }
            }
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Downloads (or reads from [dir] in the cache) a picture, decoded to at most about [maxWidth]
     * px wide; [rgb565] halves the memory of pictures with no transparency. Blocking; null on failure.
     */
    fun image(ctx: Context, url: String, dir: String, maxWidth: Int, rgb565: Boolean = false): Bitmap? {
        val folder = File(ctx.cacheDir, dir).apply { mkdirs() }
        val f = File(folder, Integer.toHexString(url.hashCode()) + ".img")
        if (!f.exists()) try {
            val tmp = File(folder, f.name + ".part")
            tmp.writeBytes(get(url))
            tmp.renameTo(f)
            folder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(120)?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w("Net", "image $url failed: ${e.message}")
            return null
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            if (rgb565) inPreferredConfig = Bitmap.Config.RGB_565
        })?.also { it.prepareToDraw() }
    }
}

object WidgetGlass {
    /**
     * What [v] shows right now, at [scale] of its size, blurred and made a little more vivid: the
     * frosted backdrop behind an overlay. Captured once, so it costs nothing per frame. Null if the
     * view isn't laid out or can't be drawn in software.
     */
    fun frostedSnapshot(v: View, scale: Float = 0.125f, blur: Int = 3, draw: (Canvas) -> Unit = { v.draw(it) }): Bitmap? {
        if (v.width == 0 || v.height == 0) return null
        val w = (v.width * scale).toInt().coerceAtLeast(1)
        val h = (v.height * scale).toInt().coerceAtLeast(1)
        return try {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(Color.BLACK)
            c.scale(scale, scale)
            draw(c)
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            repeat(3) { LiquidGlass.boxBlur(px, w, h, blur) }
            for (k in px.indices) {
                val p = px[k]
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
                val l = r * 0.3f + g * 0.59f + bl * 0.11f
                px[k] = Color.rgb(clamp(l + (r - l) * 1.35f), clamp(l + (g - l) * 1.35f), clamp(l + (bl - l) * 1.35f))
            }
            b.setPixels(px, 0, w, 0, 0, w, h)
            b
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun clamp(v: Float) = v.toInt().coerceIn(0, 255)
}

/**
 * The edge of a glass card: a hairline that catches the light, bright on the top-left corner and
 * fainter where it reflects at the bottom right, drawn over artwork. [strength] brightens it on focus.
 */
class GlassRim(private val radius: Float, private val stroke: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
    private val rect = RectF()
    private var shader: Shader? = null

    var strength = 0.5f
        set(v) { if (field != v) { field = v; invalidateSelf() } }

    override fun onBoundsChange(b: Rect) {
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.argb(235, 255, 255, 255), Color.argb(46, 255, 255, 255), Color.argb(14, 255, 255, 255), Color.argb(120, 255, 255, 255)),
            floatArrayOf(0f, 0.32f, 0.68f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val s = shader ?: return
        rect.set(bounds)
        rect.inset(stroke / 2, stroke / 2)
        paint.shader = s
        paint.alpha = (255 * strength.coerceIn(0f, 1f)).toInt()
        val r = (radius - stroke / 2).coerceAtLeast(0f)
        c.drawRoundRect(rect, r, r, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * What lies behind the widgets, kept by the home screen: the wallpaper (or the video's latest
 * frame) blurred, shown centre-cropped. Each pane paints its share of it, so the glass shows what
 * is actually behind it without capturing the screen.
 */
class GlassSource {
    var wall: Bitmap? = null
        set(v) { field = v; wallShader = v?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }; changed() }
    internal var wallShader: BitmapShader? = null
    internal val panels = ArrayList<GlassPanel>()

    fun changed() { for (p in panels) p.invalidateSelf() }
}

/**
 * A pane of Liquid Glass for a widget: its share of the blurred wallpaper, lifted and made more
 * vivid, a faint veil, a sheen across the top and a lit rim; or, once the home screen has baked it
 * over a still wallpaper, [baked] clear glass that refracts at the rim. [lit] (0 to 1) brightens it
 * on focus.
 */
class GlassPanel(private val owner: View, private val radius: Float, private val source: GlassSource) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val m = Matrix()
    private val loc = IntArray(2)
    private val tone = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(1.4f)
        postConcat(ColorMatrix(floatArrayOf(0.84f, 0f, 0f, 0f, 26f, 0f, 0.84f, 0f, 0f, 26f, 0f, 0f, 0.84f, 0f, 26f, 0f, 0f, 0f, 1f, 0f)))
    })
    private var sheen: Shader? = null
    private var rim: Shader? = null

    var lit = 0f
        set(v) { if (field != v) { field = v; invalidateSelf() } }

    /** Glass baked over the still wallpaper behind this pane; null while the wallpaper moves (video). */
    var baked: Bitmap? = null
        set(v) { field = v; dim = v?.let(::dimFor) ?: 0; invalidateSelf() }

    /** A dark veil under the content where the glass sits on something bright, so white text reads. */
    private var dim = 0

    private fun dimFor(b: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(b, 12, 6, true)
        var sum = 0f
        for (y in 0 until 6) for (x in 0 until 12) {
            val c = small.getPixel(x, y)
            sum += (Color.red(c) * 0.3f + Color.green(c) * 0.59f + Color.blue(c) * 0.11f) / 255f
        }
        if (small !== b) small.recycle()
        val luma = sum / 72f
        return (((luma - 0.42f) / 0.4f).coerceIn(0f, 1f) * 120).toInt()
    }

    /** Follows the source's pictures while attached. */
    fun attach() { if (this !in source.panels) source.panels += this }
    fun detach() { source.panels -= this }

    override fun onBoundsChange(b: Rect) {
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        sheen = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(Color.argb(64, 255, 255, 255), Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(40, 0, 0, 0)),
            floatArrayOf(0f, 0.45f, 0.7f, 1f), Shader.TileMode.CLAMP)
        rim = LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.WHITE, Color.argb(50, 255, 255, 255), Color.argb(26, 255, 255, 255), Color.argb(150, 255, 255, 255)),
            floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        rect.set(bounds)
        val r = radius.coerceAtMost(minOf(rect.width(), rect.height()) / 2)
        path.reset(); path.addRoundRect(rect, r, r, Path.Direction.CW)
        baked?.let { drawBaked(c, it, r); return }
        val save = c.save()
        c.clipPath(path)
        val screen = owner.rootView
        owner.getLocationInWindow(loc)
        val sw = screen.width.toFloat()
        val sh = screen.height.toFloat()
        // The owner may be scaled (focus lift): map through its scale so the picture stays put.
        val sx = owner.scaleX
        val sy = owner.scaleY
        source.wall?.let { b ->
            val s = maxOf(sw / b.width, sh / b.height)
            m.setScale(s, s)
            m.postTranslate((sw - b.width * s) / 2 - loc[0], (sh - b.height * s) / 2 - loc[1])
            m.postScale(1 / sx, 1 / sy)
            source.wallShader?.setLocalMatrix(m)
            paint.shader = source.wallShader
            paint.colorFilter = tone
            paint.alpha = 255
            c.drawRect(rect, paint)
            paint.colorFilter = null
        } ?: run { paint.shader = null; paint.color = Color.argb(150, 30, 32, 40); c.drawRect(rect, paint) }
        paint.shader = null
        paint.color = Color.argb((26 + 46 * lit).toInt(), 255, 255, 255)
        c.drawRect(rect, paint)
        paint.shader = sheen
        paint.alpha = (200 + 55 * lit).toInt()
        c.drawRect(rect, paint)
        c.restoreToCount(save)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = owner.resources.displayMetrics.density * (1f + 0.7f * lit)
        paint.shader = rim
        paint.alpha = (140 + 115 * lit).toInt()
        rect.inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
        c.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }

    /** The baked glass, a veil where it is bright, and on focus a brighter body and rim. */
    private fun drawBaked(c: Canvas, b: Bitmap, r: Float) {
        val save = c.save()
        c.clipPath(path)
        paint.shader = null
        paint.alpha = 255
        c.drawBitmap(b, null, rect, paint)
        if (dim > 0) { paint.color = Color.argb(dim, 0, 0, 0); c.drawRect(rect, paint) }
        if (lit > 0f) { paint.color = Color.argb((34 * lit).toInt(), 255, 255, 255); c.drawRect(rect, paint) }
        c.restoreToCount(save)
        if (lit > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = owner.resources.displayMetrics.density * 1.4f
            paint.shader = rim
            paint.alpha = (230 * lit).toInt()
            rect.inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
            c.drawRoundRect(rect, r, r, paint)
            paint.shader = null
            paint.alpha = 255
            paint.style = Paint.Style.FILL
        }
    }

    override fun getOutline(outline: Outline) = outline.setRoundRect(bounds, radius.coerceAtMost(minOf(bounds.width(), bounds.height()) / 2f))
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A home-screen widget: a glass pane of [w]×[h] px holding its content in [body]. Focused, it
 * lifts on a spring over a soft shadow and its glass lights up, like a focused tile.
 */
open class WidgetCard(ctx: Context, source: GlassSource, val w: Int, val h: Int) : FrameLayout(ctx) {
    private val radius = dp(26).toFloat()
    val body = FrameLayout(ctx).apply { clipChildren = false }
    val glass = GlassPanel(body, radius, source)
    private val pad = dp(28)
    private val shadow = ImageView(ctx).apply {
        setImageBitmap(LiquidGlass.shadow(w, h, radius, pad))
        alpha = 0f
    }
    private val lift = Tween()

    init {
        isFocusable = true
        isClickable = true
        clipChildren = false
        clipToPadding = false
        addView(shadow, LayoutParams(w + pad * 2, h + pad * 2))
        shadow.translationX = -pad.toFloat()
        shadow.translationY = -pad.toFloat()
        body.background = glass
        body.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        body.clipToOutline = true
        addView(body, LayoutParams(w, h))
        setOnFocusChangeListener { _, f -> onFocus(f) }
    }

    protected open fun onFocus(f: Boolean) {
        val s0 = body.scaleX
        val s1 = if (f) 1.06f else 1f
        val a0 = shadow.alpha
        val y0 = shadow.translationY
        val l0 = glass.lit
        translationZ = if (f) 1f else 0f
        lift.start(if (f) 520 else 280, if (f) WidgetMotion.spring else WidgetMotion.soft) { p ->
            val e = p.coerceAtMost(1f)
            body.scaleX = lerp(s0, s1, p); body.scaleY = body.scaleX
            shadow.scaleX = body.scaleX; shadow.scaleY = body.scaleX
            shadow.alpha = lerp(a0, if (f) 0.85f else 0f, e)
            shadow.translationY = lerp(y0, -pad + if (f) dp(16).toFloat() else 0f, e)
            glass.lit = lerp(l0, if (f) 1f else 0f, e)
        }
    }

    override fun setPressed(pressed: Boolean) {
        val was = isPressed
        super.setPressed(pressed)
        if (pressed == was || !isFocused) return
        body.tweenScale(if (pressed) 1.0f else 1.06f, if (pressed) 110 else 380, if (pressed) Motion.ease else WidgetMotion.spring)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass.attach()
    }

    override fun onDetachedFromWindow() {
        glass.detach()
        super.onDetachedFromWindow()
    }
}
