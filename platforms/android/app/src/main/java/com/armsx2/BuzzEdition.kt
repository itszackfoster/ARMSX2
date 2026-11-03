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

    /** How many players are in the game, for screens that show it (BuzzPad.playerCount is the
     *  stored value; this mirrors it as Compose state). */
    val players = androidx.compose.runtime.mutableIntStateOf(4)

    /**
     * Settings that must be in place before the emulator core starts. Called from startup just
     * before the USB devices are read, so the core boots with the buzzers already plugged in.
     */
    fun applyPrefs() {
        if (!ENABLED) return
        val prefs = runCatching { MainActivityRuntime.prefs }.getOrNull() ?: return
        phonesEnabled.value = prefs.getBoolean(PREF_PHONES, true)
        players.intValue = BuzzPad.playerCount()
        if (prefs.getString("usb.port0.type", null) != BuzzPad.DEVICE_TYPE) {
            prefs.edit()
                .putString("usb.port0.type", BuzzPad.DEVICE_TYPE)
                .putInt("usb.port0.subtype", 0)
                .apply()
        }
        // Autosave on by default, so the games screen's Continue tile always has something to pick
        // up from: a save whenever a game is closed, and one every five minutes while playing (in
        // case the Thor is switched off mid-game). Only set when never chosen, so a change made in
        // the emulator settings sticks.
        val edit = prefs.edit()
        if (!prefs.contains("autoSaveOnExit")) edit.putBoolean("autoSaveOnExit", true)
        if (!prefs.contains(MainActivityRuntime.KEY_AUTOSAVE_INTERVAL_MIN))
            edit.putInt(MainActivityRuntime.KEY_AUTOSAVE_INTERVAL_MIN, 5)
        edit.apply()
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

/**
 * Picture settings and TV output for Buzz Launcher's settings screen.
 *
 * The AYN Thor has two built-in screens; a TV over USB-C is a third display. ARMSX2's
 * bottom-screen panel goes to whichever display the app is not on, so with a TV connected it could
 * land on either. [buzzersOnTv] picks one by ignoring the other (SecondScreen's per-display
 * opt-out). The built-in bottom screen is told apart from a TV by name: it is remembered the
 * first time it is the only extra display.
 */
object BuzzDisplay {
    private const val PREF_BOTTOM = "buzz.bottomScreenName"

    /** Internal resolution choices: multiplier and label. */
    val resolutions = listOf(
        1.0f to "Native (480p)",
        2.0f to "2x (720p)",
        3.0f to "3x (1080p)",
        4.0f to "4x (1440p)",
    )

    /** Aspect ratio choices: EmuCore/GS/AspectRatio index and label. */
    val aspects = listOf(
        1 to "Auto (4:3)",
        3 to "Widescreen (16:9)",
        0 to "Stretch to fill",
    )

    private fun settings() = com.armsx2.config.ConfigStore.loadGlobal()

    fun resolutionIndex(): Int {
        val current = runCatching { settings().output.upscaleFloat }.getOrDefault(1f)
        return resolutions.indices.minByOrNull { kotlin.math.abs(resolutions[it].first - current) } ?: 0
    }

    fun setResolution(index: Int) {
        val (value, _) = resolutions[index.coerceIn(resolutions.indices)]
        runCatching {
            val s = settings()
            com.armsx2.config.ConfigStore.saveGlobal(s.copy(output = s.output.copy(upscaleFloat = value)))
        }
    }

    fun aspectIndex(): Int {
        val current = runCatching { settings().output.aspectRatio }.getOrDefault(1)
        return aspects.indexOfFirst { it.first == current }.coerceAtLeast(0)
    }

    fun setAspect(index: Int) {
        val (value, _) = aspects[index.coerceIn(aspects.indices)]
        runCatching {
            val s = settings()
            com.armsx2.config.ConfigStore.saveGlobal(s.copy(output = s.output.copy(aspectRatio = value)))
        }
    }

    /** Displays other than the one the app is on. */
    private fun extraDisplays(context: Context): List<android.view.Display> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            ?: return emptyList()
        @Suppress("DEPRECATION")
        val host = runCatching {
            val activity = MainActivityRuntime.instance as? android.app.Activity
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                activity?.display?.displayId
            else
                activity?.windowManager?.defaultDisplay?.displayId
        }.getOrNull() ?: android.view.Display.DEFAULT_DISPLAY
        return dm.displays.filter { it.displayId != host }
    }

    /** Remember the bottom screen's name while it is the only extra display. */
    fun learnBottomScreen(context: Context) {
        val extra = extraDisplays(context)
        if (extra.size == 1) {
            runCatching { MainActivityRuntime.prefs.edit().putString(PREF_BOTTOM, extra[0].name).apply() }
        }
    }

    private fun bottomName(context: Context, extra: List<android.view.Display>): String? =
        runCatching { MainActivityRuntime.prefs.getString(PREF_BOTTOM, null) }.getOrNull()
            ?.takeIf { name -> extra.any { it.name == name } }
            // Never seen alone (a TV was plugged in from the start): the built-in screen is the
            // older one, so the lower display id.
            ?: extra.minByOrNull { it.displayId }?.name

    /**
     * Called whenever displays change (SecondScreen.refresh): learn the bottom screen, and the
     * first time a TV shows up with no choice made, keep the buzzers on the Thor.
     */
    fun onDisplaysChanged(context: Context) {
        learnBottomScreen(context)
        val extra = extraDisplays(context)
        val tv = tvName(context) ?: return
        val bottom = bottomName(context, extra) ?: return
        val ignored = SecondScreen.ignoredDisplays.value
        if (tv !in ignored && bottom !in ignored) SecondScreen.ignoreQuietly(tv)
    }

    /** The connected TV's display name, or null when there is no TV. */
    fun tvName(context: Context): String? {
        val extra = extraDisplays(context)
        if (extra.size < 2) return null
        val bottom = bottomName(context, extra)
        return extra.firstOrNull { it.name != bottom }?.name
    }

    /** Buzzers currently set to show on the TV rather than the Thor's bottom screen. */
    fun buzzersOnTv(context: Context): Boolean {
        val extra = extraDisplays(context)
        val bottom = bottomName(context, extra) ?: return false
        return bottom in SecondScreen.ignoredDisplays.value
    }

    fun setBuzzersOnTv(context: Context, onTv: Boolean) {
        val extra = extraDisplays(context)
        val bottom = bottomName(context, extra) ?: return
        val tv = tvName(context)
        if (onTv) {
            tv?.let { SecondScreen.allowDisplay(it) }
            SecondScreen.ignoreDisplay(bottom)
        } else {
            SecondScreen.allowDisplay(bottom)
            tv?.let { SecondScreen.ignoreDisplay(it) }
        }
    }
}
