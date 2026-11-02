package com.armsx2

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.armsx2.input.UsbDevices
import kr.co.iefriends.pcsx2.NativeApp

/**
 * Four Buzz! buzzers on the second screen (Ayn Thor bottom panel and similar).
 *
 * Layout: one area per player, 1 to 4 of them (USB Devices settings). 1 to 3 sit side by side,
 * 4 make a 2x2 grid.
 *  - By default each quadrant shows only the big red buzzer.
 *  - Pressing a player's buzzer loads that player's coloured buttons in (blue, orange, green,
 *    yellow, top to bottom, as on the real controller), with a small red buzzer kept above them.
 *  - Coloured buttons fold back to the buzzer [COLLAPSE_DELAY_MS] after the player last let go
 *    of one, so the next question starts from the buzzer again.
 *  - The round button in the bottom-right corner forces every player's coloured buttons on (and
 *    keeps them on) for rounds and menus that need colours without a buzz first. Tap it again to
 *    go back to buzzers.
 *
 * Input goes straight into the emulated Buzz USB device through NativeApp.usbLightgunButton,
 * which despite its name presses any bind on whatever device is on that USB port. Bind ids are
 * the core's BuzzControlID enum (pcsx2/USB/usb-pad/usb-buzz.h): player * 5 + button, where
 * button is red 0, blue 1, orange 2, green 3, yellow 4.
 *
 * One View drawing everything, rather than twenty Buttons, so that multitouch is handled in one
 * place: four people mashing at once must each get their own press and their own release, and a
 * release must always go to the bind that was pressed even if the layout changed under the
 * finger (the buzzer turning into colour buttons mid-press is exactly that case).
 */
@SuppressLint("ViewConstructor")
class BuzzPad(context: Context, private val usbPort: Int) : View(context) {

    companion object {
        /** USB device type name the core registers the buzzers under (BuzzDevice::TypeName). */
        const val DEVICE_TYPE = "BuzzDevice"

        /** Most buzzers the core's Buzz device has; arrays are sized for this. */
        const val PLAYERS = 4
        const val RED = 0
        const val BLUE = 1
        const val ORANGE = 2
        const val GREEN = 3
        const val YELLOW = 4

        /** How long coloured buttons stay after the last colour press is released. Long enough
         *  for a deliberate second press, short enough to be back on the buzzer for the next
         *  question. Ignored while the force toggle is on. */
        const val COLLAPSE_DELAY_MS = 1500L

        private const val REVEAL_MS = 180L

        fun bindId(player: Int, button: Int) = player * 5 + button

        private const val PREF_PLAYERS = "buzz.players"

        /** How many buzzers the panel shows, 1 to 4. Player 1 is always the first one shown. */
        fun playerCount(): Int = runCatching {
            com.armsx2.runtime.MainActivityRuntime.prefs.getInt(PREF_PLAYERS, PLAYERS)
        }.getOrDefault(PLAYERS).coerceIn(1, PLAYERS)

        fun setPlayerCount(count: Int) {
            runCatching {
                com.armsx2.runtime.MainActivityRuntime.prefs.edit()
                    .putInt(PREF_PLAYERS, count.coerceIn(1, PLAYERS)).apply()
            }
            // The panel lays itself out once, so rebuild it to pick up the new count.
            SecondScreen.rebuild()
        }

        /** The USB port with the buzzers on it, or -1 if neither port has them. */
        fun buzzPort(): Int =
            (0..1).firstOrNull { UsbDevices.storedType(it) == DEVICE_TYPE } ?: -1

        private val FACE = intArrayOf(
            0xFFE0201E.toInt(), // red
            0xFF1F6FDB.toInt(), // blue
            0xFFF2871C.toInt(), // orange
            0xFF2EAF4B.toInt(), // green
            0xFFF4D21F.toInt(), // yellow
        )
        private val COLOUR_ORDER = intArrayOf(BLUE, ORANGE, GREEN, YELLOW)
    }

    private val dp = resources.displayMetrics.density

    /** Players on screen this time round; a change rebuilds the whole panel. */
    private val players = playerCount()
    private val handler = Handler(Looper.getMainLooper())

    // ---- State ----------------------------------------------------------------------------------

    /** Per player: true while their coloured buttons are showing (ignoring the force toggle). */
    private val revealed = BooleanArray(PLAYERS)

    /** 0 = buzzer only, 1 = colours fully in. Animated, and what drawing actually uses. */
    private val reveal = FloatArray(PLAYERS)
    private val animators = arrayOfNulls<ValueAnimator>(PLAYERS)

    /** Bottom-right toggle: every player's colours on until turned off. */
    private var forceColours = false

    /** Pointer id -> bind id it is holding down. Releases go here, never to a fresh hit test. */
    private val held = HashMap<Int, Int>()

    /** Pointer id currently pressing the force toggle, or -1. */
    private var forcePointer = -1

    private val collapse = Array(PLAYERS) { p -> Runnable { setRevealed(p, false) } }

    // ---- Geometry (recomputed in onSizeChanged) -------------------------------------------------

    private val quad = Array(PLAYERS) { RectF() }
    /** [player][button] hit/draw rect for the coloured layout; index RED is the small buzzer. */
    private val colourRects = Array(PLAYERS) { Array(5) { RectF() } }
    private val forceRect = RectF()

    // ---- Paint -----------------------------------------------------------------------------------

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * dp
        color = 0x40FFFFFF
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 15 * dp
    }
    private val tmp = RectF()

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
        setBackgroundColor(0xFF0B0D12.toInt())
    }

    // ---- Layout ----------------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val gap = 6 * dp
        // 1 to 3 players sit side by side in columns; 4 make a 2x2 grid.
        val cols = if (players == 4) 2 else players
        val rows = if (players == 4) 2 else 1
        val cellW = (w - gap * (cols + 1)) / cols
        val cellH = (h - gap * (rows + 1)) / rows
        for (p in 0 until players) {
            val c = p % cols
            val r = p / cols
            val left = gap + c * (cellW + gap)
            val top = gap + r * (cellH + gap)
            quad[p].set(left, top, left + cellW, top + cellH)
        }

        val forceSize = 52 * dp
        forceRect.set(w - forceSize - gap, h - forceSize - gap, w - gap.toFloat(), h - gap.toFloat())

        for (p in 0 until players) {
            val q = quad[p]
            val inner = 8 * dp
            val top = q.top + 26 * dp // room for the player label
            // Small red buzzer, centred at the top of the coloured layout.
            val redH = (q.bottom - top) * 0.22f
            val redR = minOf(redH, q.width() * 0.5f) / 2f
            val cx = q.centerX()
            colourRects[p][RED].set(cx - redR, top, cx + redR, top + redR * 2)

            // Four bars under it.
            val barsTop = top + redR * 2 + inner
            val barH = (q.bottom - inner - barsTop - inner * 3) / 4f
            for (i in 0 until 4) {
                val t = barsTop + i * (barH + inner)
                var right = q.right - inner
                // The bottom-right player's last bar would sit under the force toggle: stop it short.
                if (p == players - 1 && i == 3) right = minOf(right, forceRect.left - inner)
                colourRects[p][COLOUR_ORDER[i]].set(q.left + inner, t, right, t + barH)
            }
        }
    }

    private fun buzzerRadius(p: Int): Float {
        val q = quad[p]
        return minOf(q.width(), q.height() - 26 * dp) * 0.40f
    }

    private fun buzzerCentreY(p: Int) = quad[p].centerY() + 10 * dp

    // ---- Drawing ---------------------------------------------------------------------------------

    private fun shown(p: Int): Float = if (forceColours) 1f else reveal[p]

    private fun isHeld(bind: Int) = held.containsValue(bind)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (p in 0 until players) drawPlayer(canvas, p)
        drawForceToggle(canvas)
    }

    private fun drawPlayer(canvas: Canvas, p: Int) {
        val q = quad[p]
        fill.color = 0xFF161A22.toInt()
        canvas.drawRoundRect(q, 18 * dp, 18 * dp, fill)
        canvas.drawText("PLAYER ${p + 1}", q.centerX(), q.top + 19 * dp, label)

        val t = shown(p)

        // Big buzzer, shrinking and fading as the colours come in.
        if (t < 1f) {
            val r = buzzerRadius(p) * (1f - 0.35f * t)
            val alpha = ((1f - t) * 255).toInt()
            drawBuzzer(canvas, q.centerX(), buzzerCentreY(p), r, isHeld(bindId(p, RED)), alpha)
        }

        // Coloured layout, sliding up into place.
        if (t > 0f) {
            val alpha = (t * 255).toInt()
            val slide = (1f - t) * 24 * dp
            val red = colourRects[p][RED]
            drawBuzzer(canvas, red.centerX(), red.centerY() + slide, red.width() / 2f,
                isHeld(bindId(p, RED)), alpha)
            for (b in COLOUR_ORDER) {
                tmp.set(colourRects[p][b])
                tmp.offset(0f, slide)
                val pressed = isHeld(bindId(p, b))
                if (pressed) tmp.inset(3 * dp, 3 * dp)
                fill.color = withAlpha(if (pressed) darken(FACE[b]) else FACE[b], alpha)
                canvas.drawRoundRect(tmp, 14 * dp, 14 * dp, fill)
            }
        }
    }

    private fun drawBuzzer(canvas: Canvas, cx: Float, cy: Float, r: Float, pressed: Boolean, alpha: Int) {
        // Grey housing ring, then the red dome. Pressed = dome sinks a little and darkens.
        fill.color = withAlpha(0xFF2A2F3A.toInt(), alpha)
        canvas.drawCircle(cx, cy, r, fill)
        val dome = if (pressed) r * 0.78f else r * 0.84f
        fill.color = withAlpha(if (pressed) darken(FACE[RED]) else FACE[RED], alpha)
        canvas.drawCircle(cx, cy, dome, fill)
        if (!pressed) {
            // Highlight so it reads as a dome rather than a flat disc.
            fill.color = withAlpha(0x33FFFFFF, alpha)
            canvas.drawCircle(cx - dome * 0.25f, cy - dome * 0.3f, dome * 0.38f, fill)
        }
        stroke.alpha = (alpha * 0.25f).toInt()
        canvas.drawCircle(cx, cy, r, stroke)
    }

    private fun drawForceToggle(canvas: Canvas) {
        val c = forceRect
        val r = c.width() / 2f
        fill.color = if (forceColours) 0xFFFFFFFF.toInt() else 0xFF2A2F3A.toInt()
        canvas.drawCircle(c.centerX(), c.centerY(), r, fill)
        // Four coloured pips, the toggle's icon.
        val pip = r * 0.22f
        val off = r * 0.32f
        val pos = arrayOf(-off to -off, off to -off, -off to off, off to off)
        for (i in 0 until 4) {
            fill.color = FACE[COLOUR_ORDER[i]]
            canvas.drawCircle(c.centerX() + pos[i].first, c.centerY() + pos[i].second, pip, fill)
        }
        stroke.alpha = 0x40
        canvas.drawCircle(c.centerX(), c.centerY(), r, stroke)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        val a = ((color ushr 24) * alpha / 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun darken(color: Int): Int {
        val r = ((color shr 16 and 0xFF) * 0.7f).toInt()
        val g = ((color shr 8 and 0xFF) * 0.7f).toInt()
        val b = ((color and 0xFF) * 0.7f).toInt()
        return (color and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    // ---- Touch -----------------------------------------------------------------------------------

    /** Which bind a touch at (x, y) lands on, or -1. */
    private fun hitTest(x: Float, y: Float): Int {
        for (p in 0 until players) {
            if (!quad[p].contains(x, y)) continue
            if (shown(p) >= 0.5f) {
                for (b in 0..4) {
                    val rect = colourRects[p][b]
                    // Small buzzer: circular hit area, a little generous.
                    if (b == RED) {
                        val dx = x - rect.centerX()
                        val dy = y - rect.centerY()
                        val rr = rect.width() / 2f + 8 * dp
                        if (dx * dx + dy * dy <= rr * rr) return bindId(p, RED)
                    } else if (rect.contains(x, y)) {
                        return bindId(p, b)
                    }
                }
                return -1
            }
            // Buzzer view: the whole quadrant is the buzzer. Easier to hit with four people
            // reaching across one small screen than an exact circle.
            return bindId(p, RED)
        }
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                val x = event.getX(i)
                val y = event.getY(i)
                if (forceRect.contains(x, y)) {
                    forcePointer = id
                } else {
                    val bind = hitTest(x, y)
                    if (bind >= 0) press(id, bind)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                if (id == forcePointer) {
                    forcePointer = -1
                    if (forceRect.contains(event.getX(i), event.getY(i))) toggleForce()
                } else {
                    release(id)
                }
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun press(pointer: Int, bind: Int) {
        held[pointer] = bind
        send(bind, true)
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val player = bind / 5
        val button = bind % 5
        if (button == RED) {
            // Buzzed in: load this player's colours.
            handler.removeCallbacks(collapse[player])
            setRevealed(player, true)
        } else {
            handler.removeCallbacks(collapse[player])
        }
        invalidate()
    }

    private fun release(pointer: Int) {
        val bind = held.remove(pointer) ?: return
        // Another finger may still hold the same button; only release it when the last one lifts.
        if (!held.containsValue(bind)) send(bind, false)
        val player = bind / 5
        if (bind % 5 != RED && revealed[player] && !forceColours) {
            handler.removeCallbacks(collapse[player])
            handler.postDelayed(collapse[player], COLLAPSE_DELAY_MS)
        }
        invalidate()
    }

    private fun releaseAll() {
        val binds = held.values.toSet()
        held.clear()
        forcePointer = -1
        binds.forEach { send(it, false) }
        invalidate()
    }

    private fun send(bind: Int, pressed: Boolean) {
        if (usbPort !in 0..1) return
        runCatching { NativeApp.usbLightgunButton(usbPort, bind, pressed) }
    }

    // ---- Reveal / force ----------------------------------------------------------------------------

    private fun setRevealed(p: Int, on: Boolean) {
        if (revealed[p] == on) return
        revealed[p] = on
        animators[p]?.cancel()
        animators[p] = ValueAnimator.ofFloat(reveal[p], if (on) 1f else 0f).apply {
            duration = REVEAL_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                reveal[p] = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun toggleForce() {
        forceColours = !forceColours
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (!forceColours) {
            // Back to buzzers for everyone, including anyone who had buzzed in.
            for (p in 0 until PLAYERS) {
                handler.removeCallbacks(collapse[p])
                setRevealed(p, false)
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        // Never leave a buzzer stuck down in the game when the panel goes away.
        releaseAll()
        for (p in 0 until PLAYERS) {
            handler.removeCallbacks(collapse[p])
            animators[p]?.cancel()
        }
        super.onDetachedFromWindow()
    }
}
