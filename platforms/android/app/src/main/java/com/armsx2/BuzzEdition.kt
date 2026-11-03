package com.armsx2

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.armsx2.runtime.MainActivityRuntime

/**
 * Buzz Launcher: this build of ARMSX2 is a single-purpose app for the Buzz! quiz games.
 *
 * Everything a player would otherwise configure is set here instead: the Buzz device on USB
 * port 1, the bottom-screen buzzer panel, and phone buzzers. The screens that replace ARMSX2's
 * own live in com.armsx2.ui.buzz (setup, game picker, settings, pause menu); [ENABLED] is the one
 * switch that routes the app to them.
 */
object BuzzEdition {
    const val ENABLED = true

    private const val PREF_PHONES = "buzz.phones"

    /** Phones may join as buzzers. On unless turned off in Settings. */
    val phonesEnabled = mutableStateOf(true)

    /**
     * Settings that must be in place before the emulator core starts. Called from startup just
     * before the USB devices are read, so the core boots with the buzzers already plugged in.
     */
    fun applyPrefs() {
        if (!ENABLED) return
        val prefs = runCatching { MainActivityRuntime.prefs }.getOrNull() ?: return
        phonesEnabled.value = prefs.getBoolean(PREF_PHONES, true)
        if (prefs.getString("usb.port0.type", null) != BuzzPad.DEVICE_TYPE) {
            prefs.edit()
                .putString("usb.port0.type", BuzzPad.DEVICE_TYPE)
                .putInt("usb.port0.subtype", 0)
                .apply()
        }
        // The second set of buzzers, on port 2, only while the game is set up for 5-8 players.
        val second = if (BuzzPad.playerCount() > BuzzPad.PER_SET) BuzzPad.DEVICE_TYPE else "None"
        if ((prefs.getString("usb.port1.type", "None") ?: "None") != second) {
            prefs.edit().putString("usb.port1.type", second).putInt("usb.port1.subtype", 0).apply()
        }
    }

    /** Settings that need a screen: the bottom-screen panel. Safe to call on every screen. */
    fun applyUi(context: Context) {
        if (!ENABLED) return
        applyPrefs()
        if (!SecondScreen.enabled.value) runCatching { SecondScreen.set(context, true) }
    }

    fun setPhonesEnabled(on: Boolean) {
        phonesEnabled.value = on
        runCatching { MainActivityRuntime.prefs.edit().putBoolean(PREF_PHONES, on).apply() }
        if (on) BuzzPhones.start() else BuzzPhones.stop()
        SecondScreen.rebuild()
    }

    /** Whether a library entry is a Buzz! game. GameDB titles of every release start "Buzz!",
     *  and a disc it does not know falls back to its file name, which nearly always says it too. */
    fun isBuzzGame(game: GameInfo): Boolean =
        game.title.contains("buzz", ignoreCase = true) ||
            game.titleEn.contains("buzz", ignoreCase = true)
}
