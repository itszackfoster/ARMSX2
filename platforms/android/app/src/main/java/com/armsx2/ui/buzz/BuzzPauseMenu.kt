package com.armsx2.ui.buzz

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.InGameOverlay

/**
 * The pause menu, replacing ARMSX2's in-game menu: resume, save, load, restart, quit. The game is
 * already paused by the time this shows (InGameOverlay.open), and every way out hands it back.
 */
@Composable
fun BuzzPauseMenu() {
    val resume = { InGameOverlay.toggle() }
    BackHandler(onBack = resume)
    // Open on Resume, so A straight away carries on playing.
    LaunchedEffect(Unit) {
        com.armsx2.ui.settings.SettingsControllerNav.focusWhenRegistered(null, "buzz.pause.resume")
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xD90B0D12))
            // Taps on the dimmed game do nothing, rather than reaching the game underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Paused",
                color = BuzzColors.Text,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            BuzzPrimaryButton("Resume", Modifier.fillMaxWidth(), navId = "buzz.pause.resume", onClick = resume)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BuzzSecondaryButton("Save", Modifier.weight(1f), navId = "buzz.pause.save") {
                    MainActivityRuntime.instance?.saveState()
                    resume()
                }
                BuzzSecondaryButton("Load", Modifier.weight(1f), navId = "buzz.pause.load") {
                    // Resumes once the state has actually loaded.
                    MainActivityRuntime.instance?.loadState { resume() }
                }
            }
            BuzzSecondaryButton("Restart game", Modifier.fillMaxWidth(), navId = "buzz.pause.restart") {
                // Stopping hides this menu (MainActivityRuntime.stop), as the stock menu relies on.
                MainActivityRuntime.restart()
            }
            BuzzSecondaryButton("Quit to games", Modifier.fillMaxWidth(), outlined = true, navId = "buzz.pause.quit") {
                MainActivityRuntime.closeGame()
            }
        }
    }
}
