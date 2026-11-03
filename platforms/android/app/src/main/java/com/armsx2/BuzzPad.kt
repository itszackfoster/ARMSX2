package com.armsx2

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.armsx2.input.UsbDevices
import com.armsx2.runtime.MainActivityRuntime

/**
 * Buzz! buzzers on the second screen (Ayn Thor bottom panel and similar).
 *
 * Layout: one area per player, 1 to 4 of them (USB Devices settings), minus any players who have
 * joined on a phone ([BuzzPhones]). 1 to 3 sit side by side, 4 make a 2x2 grid. A slim bar along
 * the bottom holds the Phones button (left) and the force-colours button (right), so neither eats
 * into a player's buttons.
 *  - By default each player shows only the big red buzzer.
 *  - Pressing a player's buzzer loads that player's coloured buttons in (blue, orange, green,
 *    yellow, top to bottom, as on the real controller), with a small red buzzer kept above them.
 *  - Coloured buttons fold back to the buzzer [COLLAPSE_DELAY_MS] after the player last let go
 *    of one, so the next question starts from the buzzer again.
 *  - Force colours keeps every player's coloured buttons on, phones included, for rounds and
 *    menus that need colours without a buzz first. Tap it again to go back to buzzers.
 *  - Phones shows a QR code and address for phones to join as buzzers. Tap anywhere to close it.
 *
 * With no game running the panel is the lobby instead: how many players use this screen, and the
 * QR code for phones, so everyone is set up before the game starts.
 *
 * Input goes straight into the emulated Buzz USB device through NativeApp.usbLightgunButton,
 * which despite its name presses any bind on whatever device is on that USB port. Bind ids are
 * the core's BuzzControlID enum (pcsx2/USB/usb-pad/usb-buzz.h): player * 5 + button, where
 * button is red 0, blue 1, orange 2, green 3, yellow 4.
 *
 * One View drawing everything, rather than twenty Buttons, so that multitouch is handled in one
 * place: several people mashing at once must each get their own press and their own release, and
 * a release must always go to the bind that was pressed even if the layout changed under the
 * finger (the buzzer turning into colour buttons mid-press is exactly that case).
 */
@SuppressLint("ViewConstructor")
class BuzzPad(context: Context, private val usbPort: Int) : View(context) {

    companion object {
        /** USB device type name the core registers the buzzers under (BuzzDevice::TypeName). */
        const val DEVICE_TYPE = "BuzzDevice"

        /** Buzzers in one set: one emulated Buzz device. */
        const val PER_SET = 4

        /** Most players in a game: two sets, on USB ports 1 and 2. Arrays are sized for this. */
        const val PLAYERS = 8

        /** Most players on the Thor's own screen. Players past this join on phones. */
        const val SCREEN_MAX = 4
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

        /** Panel-wide id for one button: player 0..7, button 0..4. */
        fun bindId(player: Int, button: Int) = player * 5 + button

        /** Press or release [button] for [slot] (0..7) in the game. Players 1-4 are the set on
         *  USB port 1, players 5-8 the set on port 2. */
        fun pressSlot(slot: Int, button: Int, down: Boolean) {
            if (slot !in 0 until PLAYERS || button !in 0..4) return
            val port = slot / PER_SET
            if (UsbDevices.storedType(port) != DEVICE_TYPE) return
            BuzzInput.set(port, (slot % PER_SET) * 5 + button, down)
        }

        private const val PREF_PLAYERS = "buzz.players"

        /** How many players are in the game, 1 to 8, on the Thor's screen and on phones. */
        fun playerCount(): Int = runCatching {
            com.armsx2.runtime.MainActivityRuntime.prefs.getInt(PREF_PLAYERS, PLAYERS)
        }.getOrDefault(PLAYERS).coerceIn(1, PLAYERS)

        fun setPlayerCount(count: Int) {
            val n = count.coerceIn(1, PLAYERS)
            runCatching {
                com.armsx2.runtime.MainActivityRuntime.prefs.edit().putInt(PREF_PLAYERS, n).apply()
            }
            // Players 5-8 need the second set of buzzers; plug it in only when they are used, so a
            // game for up to four never sees a set it was not expecting. USB devices are checked
            // when a game boots, which is why this is chosen in the lobby.
            BuzzEdition.players.intValue = n
            val second = if (n > PER_SET) DEVICE_TYPE else UsbDevices.NONE
            if (UsbDevices.storedType(1) != second) runCatching { UsbDevices.setType(1, second) }
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

        // Touch targets other than buzzers.
        private const val UI_NONE = 0
        private const val UI_FORCE = 1
        private const val UI_PHONES = 2
        private const val UI_CLOSE_QR = 3
        private const val UI_COUNT = 4 // + n - 1 for player count n

        private const val POLL_MS = 400L
    }

    private val dp = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    // ---- State ----------------------------------------------------------------------------------

    /** Player slots shown on this screen, in order. Changes as phones join and leave. */
    private var visible = BuzzPhones.screenSlots(playerCount())

    /** Per slot: true while their coloured buttons are showing (ignoring the force toggle). */
    private val revealed = BooleanArray(PLAYERS)

    /** 0 = buzzer only, 1 = colours fully in. Animated, and what drawing actually uses. */
    private val reveal = FloatArray(PLAYERS)
    private val animators = arrayOfNulls<ValueAnimator>(PLAYERS)

    private var forceColours = false
    private var showQr = false
    private var qr: Bitmap? = null
    private var qrUrl: String? = null

    /** Pointer id -> bind id it is holding down. Releases go here, never to a fresh hit test. */
    private val held = HashMap<Int, Int>()

    /** Pointer on a bar button (or the QR overlay), and which one. */
    private var uiPointer = -1
    private var uiTarget = UI_NONE

    private val collapse = Array(PLAYERS) { p -> Runnable { setRevealed(p, false) } }

    private val phonesChanged: () -> Unit = { onPhonesChanged() }

    /** No game running: the lobby shows instead of buzzers. */
    private var lobby = isLobbyNow()
    private var setupDone = MainActivityRuntime.setupComplete.value
    private var pollCount = 0
    private val poll = object : Runnable {
        override fun run() {
            val nowLobby = isLobbyNow()
            val nowSetup = MainActivityRuntime.setupComplete.value
            if (nowLobby != lobby || nowSetup != setupDone) {
                lobby = nowLobby
                setupDone = nowSetup
                releaseAll()
                showQr = false
                if (lobby) refreshQr()
                relayout()
                invalidate()
            } else if (lobby && BuzzEdition.phonesEnabled.value && ++pollCount % 10 == 0) {
                // The Wi-Fi may have come up (or changed) since: keep the address current.
                if (BuzzPhones.url() != qrUrl) { refreshQr(); invalidate() }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun isLobbyNow(): Boolean =
        MainActivityRuntime.eState.value == com.armsx2.EmuState.STOPPED

    // ---- Geometry (recomputed in relayout) ------------------------------------------------------

    private val cell = Array(PLAYERS) { RectF() }
    /** [slot][button] hit/draw rect for the coloured layout; index RED is the small buzzer. */
    private val colourRects = Array(PLAYERS) { Array(5) { RectF() } }
    private val gridRect = RectF()
    private val forceRect = RectF()
    private val phonesRect = RectF()
    private val countRects = Array(PLAYERS) { RectF() }
    private val lobbyQrRect = RectF()

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
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE6EAF0.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 15 * dp
    }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA0A6.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 13 * dp
    }
    private val tmp = RectF()
    private val para = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA0A6.toInt()
        textSize = 15 * dp
    }

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
        setBackgroundColor(0xFF0B0D12.toInt())
    }

    // ---- Lifecycle -------------------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (BuzzEdition.phonesEnabled.value) BuzzPhones.start()
        BuzzPhones.addListener(phonesChanged)
        if (lobby) refreshQr()
        handler.post(poll)
        // A rebuilt panel starts with force off; make sure the phones agree.
        BuzzPhones.setForce(false)
        onPhonesChanged()
    }

    override fun onDetachedFromWindow() {
        // Never leave a buzzer stuck down in the game when the panel goes away. The phone server
        // keeps running: a panel rebuild should not kick everyone off their phones.
        BuzzPhones.removeListener(phonesChanged)
        handler.removeCallbacks(poll)
        releaseAll()
        for (p in 0 until PLAYERS) {
            handler.removeCallbacks(collapse[p])
            animators[p]?.cancel()
        }
        super.onDetachedFromWindow()
    }

    private fun onPhonesChanged() {
        val now = BuzzPhones.screenSlots(playerCount())
        if (!now.contentEquals(visible)) {
            // A phone took a player someone was pressing here: let go of it in the game.
            val gone = visible.filter { it !in now }.toSet()
            val drop = held.filterValues { it / 5 in gone }
            drop.keys.forEach { release(it) }
            visible = now
            relayout()
        }
        invalidate()
    }

    // ---- Layout ----------------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    private fun relayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = 6 * dp

        // Bottom bar.
        val barH = 46 * dp
        val barTop = h - barH - gap
        val btn = barH
        forceRect.set(w - gap - btn, barTop, w - gap, barTop + btn)
        if (BuzzEdition.phonesEnabled.value) phonesRect.set(gap, barTop, gap + 150 * dp, barTop + btn)
        else phonesRect.setEmpty()
        gridRect.set(0f, 0f, w, barTop)

        // Lobby: a row of player-count buttons, then the phone QR below it.
        val pad = 28 * dp
        val countTop = pad + 34 * dp
        val countGap = 8 * dp
        val countW = (w - pad * 2 - countGap * (PLAYERS - 1)) / PLAYERS
        for (i in 0 until PLAYERS) {
            val left = pad + i * (countW + countGap)
            countRects[i].set(left, countTop, left + countW, countTop + 64 * dp)
        }
        val qrTop = countTop + 64 * dp + 44 * dp
        val qrSize = minOf(190 * dp, h - qrTop - pad)
        lobbyQrRect.set(pad, qrTop, pad + qrSize, qrTop + qrSize)

        // Player cells above it (at most SCREEN_MAX): 1 to 3 in one row, 4 as 2x2.
        val n = visible.size
        if (n == 0) return
        val cols = if (n == 4) 2 else n
        val rows = if (n == 4) 2 else 1
        val cellW = (w - gap * (cols + 1)) / cols
        val cellH = (barTop - gap * (rows + 1)) / rows
        for ((i, slot) in visible.withIndex()) {
            val left = gap + (i % cols) * (cellW + gap)
            val top = gap + (i / cols) * (cellH + gap)
            val q = cell[slot]
            q.set(left, top, left + cellW, top + cellH)

            val inner = 8 * dp
            val contentTop = q.top + 26 * dp // room for the player label
            val redR = minOf((q.bottom - contentTop) * 0.22f, q.width() * 0.5f) / 2f
            val cx = q.centerX()
            colourRects[slot][RED].set(cx - redR, contentTop, cx + redR, contentTop + redR * 2)
            val barsTop = contentTop + redR * 2 + inner
            val barHeight = (q.bottom - inner - barsTop - inner * 3) / 4f
            for (b in 0 until 4) {
                val t = barsTop + b * (barHeight + inner)
                colourRects[slot][COLOUR_ORDER[b]].set(q.left + inner, t, q.right - inner, t + barHeight)
            }
        }
    }

    private fun buzzerRadius(slot: Int): Float {
        val q = cell[slot]
        return minOf(q.width(), q.height() - 26 * dp) * 0.40f
    }

    private fun buzzerCentreY(slot: Int) = cell[slot].centerY() + 10 * dp

    // ---- Drawing ---------------------------------------------------------------------------------

    private fun shown(slot: Int): Float = if (forceColours) 1f else reveal[slot]

    private fun isHeld(bind: Int) = held.containsValue(bind)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lobby) {
            drawLobby(canvas)
            return
        }
        if (visible.isEmpty()) {
            canvas.drawText("Every player is on a phone", gridRect.centerX(), gridRect.centerY(), body)
        }
        for (slot in visible) drawPlayer(canvas, slot)
        drawPhonesButton(canvas)
        drawForceToggle(canvas)
        if (showQr) drawQr(canvas)
    }

    private fun drawPlayer(canvas: Canvas, slot: Int) {
        val q = cell[slot]
        fill.color = 0xFF161A22.toInt()
        canvas.drawRoundRect(q, 18 * dp, 18 * dp, fill)
        canvas.drawText("PLAYER ${slot + 1}", q.centerX(), q.top + 19 * dp, label)

        val t = shown(slot)

        // Big buzzer, shrinking and fading as the colours come in.
        if (t < 1f) {
            val r = buzzerRadius(slot) * (1f - 0.35f * t)
            val alpha = ((1f - t) * 255).toInt()
            drawBuzzer(canvas, q.centerX(), buzzerCentreY(slot), r, isHeld(bindId(slot, RED)), alpha)
        }

        // Coloured layout, sliding up into place.
        if (t > 0f) {
            val alpha = (t * 255).toInt()
            val slide = (1f - t) * 24 * dp
            val red = colourRects[slot][RED]
            drawBuzzer(canvas, red.centerX(), red.centerY() + slide, red.width() / 2f,
                isHeld(bindId(slot, RED)), alpha)
            for (b in COLOUR_ORDER) {
                tmp.set(colourRects[slot][b])
                tmp.offset(0f, slide)
                val pressed = isHeld(bindId(slot, b))
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

    private fun drawLobby(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (!setupDone) {
            canvas.drawText("Finish setup on the top screen", w / 2f, h / 2f - 4 * dp, header(22f, Paint.Align.CENTER))
            canvas.drawText("Your buzzers will appear here as soon as a game starts", w / 2f, h / 2f + 26 * dp, dim)
            return
        }
        val pad = 28 * dp
        canvas.drawText("How many players?", pad, pad + 18 * dp, header(18f, Paint.Align.LEFT))
        val selected = playerCount()
        for (i in 0 until PLAYERS) {
            val r = countRects[i]
            val on = i + 1 == selected
            fill.color = if (on) FACE[YELLOW] else 0xFF161A22.toInt()
            canvas.drawRoundRect(r, 16 * dp, 16 * dp, fill)
            val p = header(26f, Paint.Align.CENTER)
            p.color = if (on) 0xFF0B0D12.toInt() else 0xFFE6EAF0.toInt()
            canvas.drawText("${i + 1}", r.centerX(), r.centerY() - (p.ascent() + p.descent()) / 2f, p)
        }
        val divY = countRects[0].bottom + 22 * dp
        fill.color = 0x1FFFFFFF
        canvas.drawRect(pad, divY, w - pad, divY + 1 * dp, fill)

        if (!BuzzEdition.phonesEnabled.value) {
            canvas.drawText("Phone buzzers are off. Turn them on in Settings.", w / 2f, divY + 60 * dp, dim)
            return
        }
        // QR on the left, how to join on the right.
        val q = lobbyQrRect
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(q, 14 * dp, 14 * dp, fill)
        val inset = 12 * dp
        val bmp = qr
        if (qrUrl != null && bmp != null) {
            tmp.set(q.left + inset, q.top + inset, q.right - inset, q.bottom - inset)
            canvas.drawBitmap(bmp, null, tmp, null)
        } else {
            val p = header(14f, Paint.Align.CENTER)
            p.color = 0xFF161A22.toInt()
            canvas.drawText("No Wi-Fi", q.centerX(), q.centerY(), p)
        }
        val textLeft = q.right + 22 * dp
        val textW = (w - pad - textLeft).toInt().coerceAtLeast(1)
        var y = q.top + 20 * dp
        canvas.drawText("Or play on your phone", textLeft, y, header(20f, Paint.Align.LEFT))
        y += 14 * dp
        val count = playerCount()
        val blurb = if (qrUrl != null && count > SCREEN_MAX)
            "Players ${SCREEN_MAX + 1} to $count play on phones. Scan with your camera; phones need to be on the same Wi-Fi as the Thor."
        else if (qrUrl != null)
            "Scan with your camera. Phones need to be on the same Wi-Fi as the Thor."
        else
            "Connect the Thor to Wi-Fi, or turn on its hotspot, and a code to scan appears here."
        val layout = StaticLayout.Builder.obtain(blurb, 0, blurb.length, para, textW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.3f)
            .build()
        canvas.save()
        canvas.translate(textLeft, y)
        layout.draw(canvas)
        canvas.restore()
        y += layout.height + 24 * dp
        qrUrl?.let {
            val p = header(15f, Paint.Align.LEFT)
            p.typeface = Typeface.DEFAULT
            canvas.drawText(it, textLeft, y, p)
            y += 30 * dp
        }
        // One chip per player who has joined on a phone.
        var x = textLeft
        val chip = header(13f, Paint.Align.LEFT)
        chip.color = 0xFF9CC3FF.toInt()
        for (slot in BuzzPhones.phoneSlots().sorted()) {
            val text = "Player ${slot + 1} on a phone"
            val cw = chip.measureText(text) + 24 * dp
            if (x + cw > w - pad) { x = textLeft; y += 36 * dp }
            fill.color = 0x331F6FDB
            tmp.set(x, y - 20 * dp, x + cw, y + 8 * dp)
            canvas.drawRoundRect(tmp, 14 * dp, 14 * dp, fill)
            canvas.drawText(text, x + 12 * dp, y - 1 * dp, chip)
            x += cw + 8 * dp
        }
    }

    /** A bold paint at [sizeSp] (scaled like the rest of the panel), freshly made so callers can tweak it. */
    private fun header(sizeSp: Float, align: Paint.Align) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE6EAF0.toInt()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = align
        textSize = sizeSp * dp
    }

    private fun refreshQr() {
        qrUrl = if (BuzzEdition.phonesEnabled.value) BuzzPhones.url() else null
        qr = qrUrl?.let { makeQr(it, 512) }
    }

    private fun drawPhonesButton(canvas: Canvas) {
        val r = phonesRect
        if (r.isEmpty) return
        fill.color = if (showQr) 0xFFFFFFFF.toInt() else 0xFF2A2F3A.toInt()
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
        val count = BuzzPhones.phoneSlots().size
        val text = if (count == 0) "Phones" else "Phones · $count"
        label.color = if (showQr) 0xFF161A22.toInt() else 0xCCFFFFFF.toInt()
        canvas.drawText(text, r.centerX(), r.centerY() - (label.ascent() + label.descent()) / 2f, label)
        label.color = 0xCCFFFFFF.toInt()
    }

    private fun drawForceToggle(canvas: Canvas) {
        val c = forceRect
        val r = c.height() / 2f
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

    private fun drawQr(canvas: Canvas) {
        val g = gridRect
        fill.color = 0xF20B0D12.toInt()
        canvas.drawRect(g, fill)
        val url = qrUrl
        if (url == null) {
            canvas.drawText("No Wi-Fi connection", g.centerX(), g.centerY() - 12 * dp, body)
            canvas.drawText("Connect the Thor to Wi-Fi, or turn on its hotspot", g.centerX(), g.centerY() + 12 * dp, dim)
            return
        }
        val size = minOf(g.width() * 0.6f, g.height() - 90 * dp)
        val left = g.centerX() - size / 2f
        val top = g.top + 16 * dp
        // White quiet zone behind the code; scanners need it on a dark screen.
        fill.color = 0xFFFFFFFF.toInt()
        tmp.set(left - 10 * dp, top - 10 * dp, left + size + 10 * dp, top + size + 10 * dp)
        canvas.drawRoundRect(tmp, 10 * dp, 10 * dp, fill)
        qr?.let { bmp ->
            tmp.set(left, top, left + size, top + size)
            canvas.drawBitmap(bmp, null, tmp, null)
        }
        val y = top + size + 34 * dp
        canvas.drawText("Scan to join as a buzzer", g.centerX(), y, body)
        canvas.drawText("$url  ·  same Wi-Fi as the Thor  ·  tap to close", g.centerX(), y + 20 * dp, dim)
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

    // ---- QR --------------------------------------------------------------------------------------

    private fun openQr() {
        refreshQr()
        showQr = true
        invalidate()
    }

    private fun makeQr(text: String, size: Int): Bitmap? = runCatching {
        val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 0)
        val m = com.google.zxing.qrcode.QRCodeWriter()
            .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size)
            px[y * size + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    // ---- Touch -----------------------------------------------------------------------------------

    /** Which bind a touch at (x, y) lands on, or -1. */
    private fun hitTest(x: Float, y: Float): Int {
        for (slot in visible) {
            if (!cell[slot].contains(x, y)) continue
            if (shown(slot) >= 0.5f) {
                for (b in 0..4) {
                    val rect = colourRects[slot][b]
                    // Small buzzer: circular hit area, a little generous.
                    if (b == RED) {
                        val dx = x - rect.centerX()
                        val dy = y - rect.centerY()
                        val rr = rect.width() / 2f + 8 * dp
                        if (dx * dx + dy * dy <= rr * rr) return bindId(slot, RED)
                    } else if (rect.contains(x, y)) {
                        return bindId(slot, b)
                    }
                }
                return -1
            }
            // Buzzer view: the whole cell is the buzzer. Easier to hit with several people
            // reaching across one small screen than an exact circle.
            return bindId(slot, RED)
        }
        return -1
    }

    private fun uiTargetAt(x: Float, y: Float): Int = when {
        lobby -> if (setupDone) countRects.indexOfFirst { it.contains(x, y) }.let { if (it < 0) UI_NONE else UI_COUNT + it } else UI_NONE
        forceRect.contains(x, y) -> UI_FORCE
        phonesRect.contains(x, y) -> UI_PHONES
        showQr -> UI_CLOSE_QR
        else -> UI_NONE
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                val x = event.getX(i)
                val y = event.getY(i)
                val target = uiTargetAt(x, y)
                if (target != UI_NONE) {
                    if (uiPointer < 0) { uiPointer = id; uiTarget = target }
                } else if (!lobby) {
                    val bind = hitTest(x, y)
                    if (bind >= 0) press(id, bind)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                if (id == uiPointer) {
                    val x = event.getX(i)
                    val y = event.getY(i)
                    when (uiTarget) {
                        UI_FORCE -> if (forceRect.contains(x, y)) toggleForce()
                        UI_PHONES -> if (phonesRect.contains(x, y)) {
                            if (showQr) { showQr = false; invalidate() } else openQr()
                        }
                        UI_CLOSE_QR -> { showQr = false; invalidate() }
                        else -> if (uiTarget >= UI_COUNT) {
                            val n = uiTarget - UI_COUNT
                            if (countRects[n].contains(x, y)) {
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                setPlayerCount(n + 1)
                            }
                        }
                    }
                    uiPointer = -1
                    uiTarget = UI_NONE
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
        val slot = bind / 5
        handler.removeCallbacks(collapse[slot])
        // Buzzed in: load this player's colours.
        if (bind % 5 == RED) setRevealed(slot, true)
        invalidate()
    }

    private fun release(pointer: Int) {
        val bind = held.remove(pointer) ?: return
        // Another finger may still hold the same button; only release it when the last one lifts.
        if (!held.containsValue(bind)) send(bind, false)
        val slot = bind / 5
        if (bind % 5 != RED && revealed[slot] && !forceColours) {
            handler.removeCallbacks(collapse[slot])
            handler.postDelayed(collapse[slot], COLLAPSE_DELAY_MS)
        }
        invalidate()
    }

    private fun releaseAll() {
        val binds = held.values.toSet()
        held.clear()
        uiPointer = -1
        uiTarget = UI_NONE
        binds.forEach { send(it, false) }
        invalidate()
    }

    private fun send(bind: Int, pressed: Boolean) = pressSlot(bind / 5, bind % 5, pressed)

    // ---- Reveal / force ----------------------------------------------------------------------------

    private fun setRevealed(slot: Int, on: Boolean) {
        if (revealed[slot] == on) return
        revealed[slot] = on
        animators[slot]?.cancel()
        animators[slot] = ValueAnimator.ofFloat(reveal[slot], if (on) 1f else 0f).apply {
            duration = REVEAL_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                reveal[slot] = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun toggleForce() {
        forceColours = !forceColours
        BuzzPhones.setForce(forceColours)
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
}
