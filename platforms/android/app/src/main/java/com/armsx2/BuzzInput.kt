package com.armsx2

import kr.co.iefriends.pcsx2.NativeApp
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The one way buzzer presses reach the game, from the Thor's screen ([BuzzPad]) and from phones
 * ([BuzzPhones]) alike.
 *
 * Every press and every release is held for at least [MIN_STATE_MS] before the next change to that
 * button goes through. The game only reads the buzzers once per frame or so; a quick tap whose
 * press and release land between two reads is never seen at all. Over Wi-Fi that is easy, since
 * the network tends to deliver a phone's "down" and "up" together in one burst. A real buzzer
 * cannot be pressed and released in under a few milliseconds either, so this costs nothing that
 * a real controller would give you.
 *
 * Changes to one button are applied strictly in order, each no sooner than [MIN_STATE_MS] after
 * the one before. Different buttons are independent, so one player's mashing never delays another.
 */
object BuzzInput {
    /** About four frames at 60 fps: comfortably more than one read of the device. */
    private const val MIN_STATE_MS = 60L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "BuzzInput").apply { isDaemon = true }
    }

    /** Per bind id: the earliest time (ms) the next change may be applied. Scheduler thread only
     *  reads it via [set], which runs under this lock. */
    private val nextAllowed = LongArray(BuzzPad.PLAYERS * 5)
    private val lock = Any()

    /** Press or release [bind] (player * 5 + button) on the Buzz device on [port]. */
    fun set(port: Int, bind: Int, down: Boolean) {
        if (port !in 0..1 || bind !in nextAllowed.indices) return
        val now = System.nanoTime() / 1_000_000
        val delay: Long
        synchronized(lock) {
            val at = maxOf(now, nextAllowed[bind])
            nextAllowed[bind] = at + MIN_STATE_MS
            delay = at - now
        }
        val apply = Runnable { runCatching { NativeApp.usbLightgunButton(port, bind, down) } }
        if (delay <= 0) scheduler.execute(apply)
        else scheduler.schedule(apply, delay, TimeUnit.MILLISECONDS)
    }
}
