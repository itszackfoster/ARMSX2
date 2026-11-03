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
import com.armsx2.BuzzEdition
import com.armsx2.BuzzPad
import com.armsx2.BuzzPhones
import com.armsx2.ui.settings.controllerFocusable
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
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
      Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        if (com.armsx2.BuzzEdition.phonesEnabled.value) PlayersPanel()
      }
    }
}

/**
 * Who is in the game and where they play. A phone player can be moved back onto the Thor's
 * screen (players 1-4 only, the screen holds no more) or removed, and a latecomer can scan in.
 */
@Composable
private fun PlayersPanel() {
    val version = BuzzPhones.version.intValue
    val count = BuzzEdition.players.intValue
    val phones = remember(version) { BuzzPhones.phoneSeats().associateBy { it.slot } }
    val screen = remember(version, count) { BuzzPhones.screenSlots(count).toSet() }
    val url = remember { BuzzPhones.url() }
    val qr = remember(url) { url?.let { BuzzPad.makeQrBitmap(it, 256)?.asImageBitmap() } }
    Column(
        Modifier
            .width(420.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(BuzzColors.Surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Players", color = BuzzColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Column(
            Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (slot in 0 until count) {
                val seat = phones[slot]
                val onThor = slot in screen
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BuzzColors.Ground)
                        .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val waiting = seat == null && !onThor
                    Box(
                        Modifier.size(26.dp).clip(CircleShape)
                            .background(if (waiting) BuzzColors.Ring else Color(BuzzPad.seatColour(slot))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${slot + 1}", color = if (waiting) BuzzColors.Dim else BuzzColors.Ground, fontSize = 12.sp, fontWeight = FontWeight.Black)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            seat?.name ?: if (onThor) "Player ${slot + 1}" else "Waiting for a phone",
                            color = if (waiting) BuzzColors.Dim else BuzzColors.Text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        val weak = seat != null && (!seat.connected || seat.quality == BuzzPhones.Quality.WEAK)
                        Text(
                            seat?.where ?: if (onThor) "Thor screen" else "Free seat",
                            color = if (weak) Color(0xFFFFB37A) else BuzzColors.Dim,
                            fontSize = 12.sp,
                        )
                    }
                    if (seat != null) {
                        if (slot < BuzzPad.SCREEN_MAX) {
                            SmallButton("Move to Thor", "buzz.pause.move.$slot") { BuzzPhones.removePlayer(slot, moved = true) }
                        }
                        SmallButton("Remove", "buzz.pause.remove.$slot", outlined = true) { BuzzPhones.removePlayer(slot, moved = false) }
                    }
                }
            }
        }
        Row(
            Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(5.dp)) {
                if (qr != null) Image(qr, contentDescription = "Join code", modifier = Modifier.fillMaxSize())
            }
            Text(
                if (url != null) "Someone arriving late? Scan to take a free seat." else "Connect the Thor to Wi-Fi to let phones join.",
                color = BuzzColors.Dim,
                fontSize = 14.sp,
                lineHeight = 19.sp,
            )
        }
    }
}

@Composable
private fun SmallButton(label: String, navId: String, outlined: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .controllerFocusable(navId, shape, onConfirm = onClick)
            .heightIn(min = 36.dp)
            .clip(shape)
            .then(if (outlined) Modifier.border(2.dp, Color(0x26FFFFFF), shape) else Modifier.background(BuzzColors.Ring))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = BuzzColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
