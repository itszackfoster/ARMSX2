package com.armsx2.ui.buzz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.ui.settings.controllerFocusable

/** The Buzz Launcher look: a dark ground, one yellow action colour, and the buzzer red. */
internal object BuzzColors {
    val Ground = Color(0xFF0B0D12)
    val Surface = Color(0xFF161A22)
    val Ring = Color(0xFF2A2F3A)
    val Text = Color(0xFFE6EAF0)
    val Dim = Color(0xFF9AA0A6)
    val Yellow = Color(0xFFF4D21F)
    val Red = Color(0xFFE0201E)
    val Error = Color(0xFFFF8A80)
    val Hairline = Color(0x1FFFFFFF)
}

/** Red buzzer dot and the app name. */
@Composable
internal fun BuzzWordmark(size: TextUnit, dot: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dot * 0.3f)) {
        Box(
            Modifier.size(dot).clip(CircleShape).background(BuzzColors.Ring),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(dot * 0.82f).clip(CircleShape).background(BuzzColors.Red))
        }
        Text("Buzz Launcher", color = BuzzColors.Text, fontSize = size, fontWeight = FontWeight.Black)
    }
}

/**
 * Controller hooks for the Buzz screens. Every control registers with ARMSX2's controller
 * registry (Modifier.controllerFocusable), which the Thor's D-pad, sticks and A already drive.
 * [back] is what B does on top of that: set while a sub-screen (Settings) is open.
 */
object BuzzNav {
    @Volatile var back: (() -> Unit)? = null

    /** Set while the games screen is showing: Start opens settings, Select quits. */
    @Volatile var openSettings: (() -> Unit)? = null
    @Volatile var quit: (() -> Unit)? = null

    /** The join code shown large on the top screen (Y on the games screen). */
    val showJoin = androidx.compose.runtime.mutableStateOf(false)

    /** B on a Buzz screen: close the join code or the sub-screen if one is open. True when it was handled. */
    fun handleBack(): Boolean {
        if (showJoin.value) {
            showJoin.value = false
            return true
        }
        val b = back ?: return false
        b()
        return true
    }
}

/** A controller button drawn as a small key cap, for the hints bar. */
@Composable
internal fun KeyCap(label: String) {
    Box(
        Modifier
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BuzzColors.Ring)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = BuzzColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Black)
    }
}

/** The one primary action on a screen: a yellow pill. [navId] puts it in the controller order. */
@Composable
internal fun BuzzPrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    navId: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .controllerFocusable(navId, RoundedCornerShape(50), onConfirm = onClick)
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(50))
            .background(BuzzColors.Yellow)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 44.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = BuzzColors.Ground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** A secondary action: a dark rounded block, or an outlined one for leaving. */
@Composable
internal fun BuzzSecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    navId: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .controllerFocusable(navId, shape, onConfirm = onClick)
            .heightIn(min = 56.dp)
            .clip(shape)
            .then(
                if (outlined) Modifier.border(2.dp, Color(0x26FFFFFF), shape)
                else Modifier.background(BuzzColors.Surface),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = BuzzColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}
