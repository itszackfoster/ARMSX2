package com.armsx2.ui.buzz

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.BuzzEdition
import com.armsx2.BuzzPad
import com.armsx2.BuzzPhones

/**
 * "[name] has buzzed in": a small label in the bottom-left corner of the game, for about two
 * seconds, whenever anyone presses their red buzzer (on the Thor or on a phone). Design B: quiet
 * enough not to cover the game, and off entirely with Settings > Buzz-in alerts.
 */
object BuzzAlerts {
    data class Alert(val id: Long, val slot: Int, val name: String, val at: Long = android.os.SystemClock.uptimeMillis())

    val current = mutableStateOf<Alert?>(null)
    private var next = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Called for every red-buzzer press, from any thread. */
    fun onBuzz(slot: Int) {
        if (!BuzzEdition.alertsEnabled.value) return
        val name = BuzzPhones.phoneSeats().firstOrNull { it.slot == slot }?.name ?: "Player ${slot + 1}"
        main.post { current.value = Alert(++next, slot, name) }
    }
}

private const val SHOW_MS = 2000L

@Composable
fun BuzzAlertOverlay() {
    val alert = BuzzAlerts.current.value
    var visible by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<BuzzAlerts.Alert?>(null) }
    LaunchedEffect(alert?.id) {
        // A buzz from the lobby (a phone testing its buzzer) is not news by the time a game starts.
        if (alert == null || android.os.SystemClock.uptimeMillis() - alert.at > SHOW_MS) return@LaunchedEffect
        shown = alert
        visible = true
        kotlinx.coroutines.delay(SHOW_MS)
        visible = false
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        AnimatedVisibility(
            visible = visible && shown != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut(),
            modifier = Modifier.padding(20.dp),
        ) {
            val a = shown ?: return@AnimatedVisibility
            Row(
                Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xB30B0D12))
                    .padding(start = 6.dp, end = 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(Color(BuzzPad.seatColour(a.slot))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${a.slot + 1}", color = BuzzColors.Ground, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append(a.name) }
                        append(" has buzzed in")
                    },
                    color = BuzzColors.Text,
                    fontSize = 15.sp,
                )
            }
        }
    }
}
