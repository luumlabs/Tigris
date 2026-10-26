package com.leslie.tvhome

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Number): Int = context.dp(v)

fun rounded(color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = radius }

fun View.clipRounded(radiusPx: Float) {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) =
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
    }
    clipToOutline = true
}

object Motion {
    val ease = DecelerateInterpolator(2f)
    /**
     * Focus: a quick spring that overshoots by a hair (about 3 %) and settles, as tvOS does,
     * instead of OvershootInterpolator's bounce past the target and straight stop.
     */
    val spring = Spring(0.76f)
    /** A firm spring whose overshoot (about 1 %) reads as weight, not bounce. */
    val lift = Spring(0.84f)
    /** A spring with no visible overshoot, for things that should glide rather than bounce. */
    val soft = Spring(0.95f)

    /**
     * tvOS "bump" when focus cannot move any further in that direction: a shove against the edge
     * that springs back with one small rebound.
     */
    fun bump(v: View, dx: Int, dy: Int) {
        v.cameraDistance = v.width * 12f
        v.animate().cancel()
        val t = v.getTag(R.id.motion_bump) as? Tween ?: Tween().also { v.setTag(R.id.motion_bump, it) }
        val px = v.dp(6)
        t.start(380, android.view.animation.LinearInterpolator()) { p ->
            val f = (kotlin.math.sin(p * Math.PI * 2.2) * (1 - p) * (1 - p)).toFloat()
            v.rotationY = dx * 6f * f
            v.rotationX = -dy * 6f * f
            v.translationX = dx * px * f
            v.translationY = dy * px * f
        }
    }
}

/**
 * A damped spring from 0 to 1, as a time curve: [damping] 1 is critically damped (no overshoot),
 * lower values overshoot and settle. The stiffness is chosen so the motion has died down to a
 * thousandth of its travel by the end of the animation's duration, which therefore sets how fast
 * it feels: most of the travel happens in the first third, the rest is the settle.
 */
class Spring(private val damping: Float = 0.8f) : android.animation.TimeInterpolator {
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

/**
 * A frame-by-frame animation that still plays where the TV's animation scale is 0 ("animations
 * off", common on Chinese firmwares and in speed-up guides), which makes every ValueAnimator and
 * ViewPropertyAnimator jump straight to its end: focus would change with no zoom at all. Slowed
 * or sped-up scales still apply, down to half speed, so a TV set to 0.5 looks as it always did.
 */
class Tween : android.view.Choreographer.FrameCallback {
    private var startNanos = -1L
    private var durationNanos = 1L
    private var interp: android.animation.TimeInterpolator = Motion.ease
    private var update: ((Float) -> Unit)? = null
    private var end: (() -> Unit)? = null
    var running = false
        private set

    fun start(ms: Long, interpolator: android.animation.TimeInterpolator = Motion.ease, end: (() -> Unit)? = null, update: (Float) -> Unit) {
        cancel()
        this.interp = interpolator
        this.update = update
        this.end = end
        durationNanos = (ms * scale() * 1_000_000).toLong().coerceAtLeast(1)
        startNanos = -1L
        running = true
        update(interpolator.getInterpolation(0f))
        android.view.Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (startNanos < 0) startNanos = frameTimeNanos
        val t = ((frameTimeNanos - startNanos).toFloat() / durationNanos).coerceIn(0f, 1f)
        update?.invoke(interp.getInterpolation(t))
        if (t >= 1f) {
            running = false
            end?.invoke()
        } else android.view.Choreographer.getInstance().postFrameCallback(this)
    }

    fun cancel() {
        if (!running) return
        running = false
        android.view.Choreographer.getInstance().removeFrameCallback(this)
    }

    companion object {
        @Volatile var systemScale = 1f

        /** Read once per start of the home screen; the setting can only change in system settings. */
        fun readScale(ctx: Context) {
            systemScale = runCatching {
                android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }.getOrDefault(1f)
        }

        fun scale() = if (systemScale <= 0f) 0.5f else systemScale.coerceIn(0.5f, 1.5f)
    }
}

/** Linear interpolation, for animating between a start and an end value. */
fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

/** Focus zoom: scales the view to [s] (and lifts it to [z]) with a [Tween] kept on the view. */
fun View.tweenScale(s: Float, ms: Long, interp: android.animation.TimeInterpolator = Motion.ease, z: Float? = null, onFrame: (() -> Unit)? = null) {
    animate().cancel()
    val t = getTag(R.id.motion_focus) as? Tween ?: Tween().also { setTag(R.id.motion_focus, it) }
    val s0 = scaleX
    val z0 = translationZ
    t.start(ms, interp) { f ->
        scaleX = lerp(s0, s, f)
        scaleY = scaleX
        if (z != null) translationZ = lerp(z0, z, f)
        onFrame?.invoke()
    }
}

const val FONT_BOLD = "sans-serif-medium"

/**
 * Inter (variable, OFL) stands in for Apple's SF: Roboto's digits are what
 * make a screen read as "Android" at a glance. Chinese falls back to the system font.
 */
object Fonts {
    private val cache = HashMap<Int, Typeface>()

    fun inter(ctx: Context, weight: Int): Typeface = cache.getOrPut(weight) {
        try {
            Typeface.Builder(ctx.assets, "inter.ttf")
                .setFontVariationSettings("'wght' $weight")
                .setWeight(weight)
                .setFallback("sans-serif")
                .build() ?: Typeface.DEFAULT
        } catch (e: Exception) {
            Typeface.create(if (weight >= 500) FONT_BOLD else "sans-serif", Typeface.NORMAL)
        }
    }
}

fun Context.text(sizeSp: Float, color: Int = Color.WHITE, bold: Boolean = false, weight: Int = if (bold) 600 else 400): TextView =
    TextView(this).apply {
        textSize = sizeSp
        setTextColor(color)
        typeface = Fonts.inter(this@text, weight)
        includeFontPadding = false
    }

/**
 * A pill button in tvOS style: translucent when idle, solid white with
 * dark text and a slight lift when focused.
 */
class PillButton(ctx: Context, label: String, iconRes: Int? = null, val onClick: () -> Unit) : LinearLayout(ctx) {
    private val tv = ctx.text(17f, bold = true).apply { text = label }
    private val icon: ImageView? = iconRes?.let { ImageView(ctx).apply { setImageResource(it) } }
    private val idle = rounded(Color.argb(46, 255, 255, 255), dp(14).toFloat())
    private val focus = rounded(Color.WHITE, dp(14).toFloat())

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        background = idle
        setPadding(dp(24), 0, dp(24), 0)
        icon?.let { addView(it, LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) }) }
        addView(tv)
        setOnClickListener { onClick() }
        setOnFocusChangeListener { _, f ->
            background = if (f) focus else idle
            tv.setTextColor(if (f) Color.BLACK else Color.WHITE)
            icon?.setColorFilter(if (f) Color.BLACK else Color.WHITE)
            tweenScale(if (f) 1.06f else 1f, 160, z = if (f) dp(10).toFloat() else 0f)
        }
    }
}

/**
 * Full-screen alert in the style of tvOS: dimmed backdrop, centred title,
 * message and a vertical stack of buttons. Back dismisses it.
 */
class Sheet(private val host: FrameLayout) {
    private val ctx = host.context
    val root = FrameLayout(ctx).apply {
        setBackgroundColor(Color.argb(200, 0, 0, 0))
        // Focused controls can scale past the bounds of their parent.
        clipChildren = false
        isClickable = true
        alpha = 0f
        // Above focused tiles, which lift themselves with translationZ; no shadow (see ControlCenter.scrim).
        translationZ = ctx.dp(200).toFloat()
        outlineProvider = null
    }
    private val col = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        // Pills grow to 1.06x on focus, beyond this wrap-content column.
        clipChildren = false
    }
    private val title = ctx.text(28f, bold = true).apply { gravity = Gravity.CENTER }
    private val message = ctx.text(16f, Color.argb(180, 255, 255, 255)).apply {
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.25f)
    }
    private var onDismiss: (() -> Unit)? = null

    init {
        col.addView(title)
        col.addView(message, LinearLayout.LayoutParams(ctx.dp(520), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(12); bottomMargin = ctx.dp(28)
        })
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { dismiss(); true } else false
        }
    }

    fun title(t: String) = apply { title.text = t }
    fun message(m: String?) = apply {
        message.text = m ?: ""
        message.visibility = if (m.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun button(label: String, icon: Int? = null, keepOpen: Boolean = false, action: () -> Unit) = apply {
        val b = PillButton(ctx, label, icon) {
            if (!keepOpen) dismiss()
            action()
        }
        col.addView(b, LinearLayout.LayoutParams(ctx.dp(420), ctx.dp(52)).apply { topMargin = ctx.dp(12) })
    }

    fun onDismiss(f: () -> Unit) = apply { onDismiss = f }

    fun show(): Sheet {
        host.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.animate().alpha(1f).setDuration(180).start()
        (0 until col.childCount).map { col.getChildAt(it) }.firstOrNull { it is PillButton }?.requestFocus()
        return this
    }

    val isShowing get() = root.parent != null

    fun dismiss() {
        if (!isShowing) return
        root.animate().alpha(0f).setDuration(140).withEndAction {
            host.removeView(root)
            onDismiss?.invoke()
        }.start()
    }

    /** Back is delivered to the focused child, so buttons forward it here. */
    fun handlesBack(): Boolean { dismiss(); return true }
}
