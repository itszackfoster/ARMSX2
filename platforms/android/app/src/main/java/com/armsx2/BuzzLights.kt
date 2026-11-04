package com.armsx2

import android.os.Handler
import android.os.Looper
import com.armsx2.input.UsbDevices
import com.armsx2.runtime.MainActivityRuntime
import kr.co.iefriends.pcsx2.NativeApp

/**
 * The red lights in the buzzers, as the game sets them.
 *
 * A real Buzz! buzzer lights its red dome when the game tells it to: flashing them all to attract
 * attention, lighting the one whose turn it is, and so on. The emulated device keeps that state
 * (usb-buzz.cpp, BuzzLights); this reads it about 25 times a second while a game is running and
 * tells the Thor's screen ([BuzzPad]) and the phones ([BuzzPhones]) when it changes, so both light
 * up exactly when a real buzzer would.
 */
object BuzzLights {
    private const val POLL_MS = 40L

    /** Bit n = player n+1's buzzer lit; players 5-8 are the second set, on USB port 2. */
    @Volatile var mask = 0
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(Int) -> Unit>()
    private var running = false

    fun isLit(slot: Int): Boolean = slot in 0..7 && (mask shr slot) and 1 == 1

    fun addListener(l: (Int) -> Unit) { listeners += l }
    fun removeListener(l: (Int) -> Unit) { listeners -= l }

    /** Start polling. Idempotent; runs for the life of the app, and costs nothing between games. */
    fun start() {
        if (running) return
        running = true
        handler.post(poll)
    }

    private val poll = object : Runnable {
        override fun run() {
            val playing = MainActivityRuntime.eState.value != EmuState.STOPPED
            val now = if (!playing) 0 else runCatching {
                var m = 0
                for (port in 0..1) {
                    if (UsbDevices.storedType(port) == BuzzPad.DEVICE_TYPE)
                        m = m or ((NativeApp.buzzLights(port) and 0xF) shl (port * BuzzPad.PER_SET))
                }
                m
            }.getOrDefault(0)
            if (now != mask) {
                mask = now
                listeners.toList().forEach { runCatching { it(now) } }
            }
            handler.postDelayed(this, if (playing) POLL_MS else POLL_MS * 10)
        }
    }
}
