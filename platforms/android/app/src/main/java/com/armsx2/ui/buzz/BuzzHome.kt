package com.armsx2.ui.buzz

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.armsx2.BuzzEdition
import com.armsx2.CustomCovers
import com.armsx2.GameInfo
import com.armsx2.navigation.AppRoute
import com.armsx2.navigation.UiNavigator
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.home.HomeViewModel
import com.armsx2.ui.onboarding.OnboardingViewModel

/**
 * The game picker, replacing ARMSX2's library: only Buzz! games, as big tiles, tap to play. A gear
 * in the corner opens [BuzzSettings]; nothing else is on the screen.
 */
@Composable
fun BuzzHome(viewModel: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val state = viewModel.state.value
    val directories = MainActivityRuntime.romsDirs.value
    val nativeReady = MainActivityRuntime.nativeReady.value
    var showSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { BuzzEdition.applyUi(context) }
    LaunchedEffect(directories, nativeReady) { viewModel.load(directories, nativeReady) }
    val refreshRequest = com.armsx2.data.library.LibraryRefresh.requests.intValue
    LaunchedEffect(refreshRequest, nativeReady, directories) {
        if (nativeReady) viewModel.onRefreshRequested(refreshRequest, directories)
    }

    if (showSettings) {
        BuzzSettings(onBack = { showSettings = false })
        return
    }

    val games = remember(state.allGames) {
        state.allGames.filter(BuzzEdition::isBuzzGame).sortedBy { it.title.lowercase() }
    }
    val coverVersion = CustomCovers.version.value
    val customCovers = remember(coverVersion) { CustomCovers.loadAll(context) }

    Column(
        Modifier.fillMaxSize().background(BuzzColors.Ground).padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BuzzWordmark(size = 26.sp, dot = 36.dp)
            Spacer(Modifier.weight(1f))
            GearButton { showSettings = true }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                games.isNotEmpty() -> GameShelf(games, customCovers) { viewModel.launch(it) }
                state.scanning || !state.initialized -> CenterMessage {
                    CircularProgressIndicator(color = BuzzColors.Yellow, modifier = Modifier.size(44.dp))
                    Text("Looking for your Buzz! games…", color = BuzzColors.Text, fontSize = 18.sp)
                }
                else -> CenterMessage {
                    Text("No Buzz! games found", color = BuzzColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Put your Buzz! game files in your games folder, or choose a different folder in Settings.",
                        color = BuzzColors.Dim,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 520.dp),
                    )
                    BuzzSecondaryButton("Open settings") { showSettings = true }
                }
            }
        }
        if (games.isNotEmpty()) {
            Text(
                "Tap a game to play",
                color = BuzzColors.Dim,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * One row of game tiles that scrolls sideways, four to a screen. When there are more than fit, the
 * edges fade with a small arrow on the side there is more, and a slim track under the row shows
 * where in the list you are. Neither shows when every game fits.
 */
@Composable
private fun GameShelf(
    games: List<GameInfo>,
    customCovers: Map<String, java.io.File>,
    onPlay: (GameInfo) -> Unit,
) {
    val listState = rememberLazyListState()
    val gap = 20.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val trackSpace = 18.dp
        val tileWidth = (maxWidth - gap * 3) / 4
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(games, key = { it.uri.toString() }) { game ->
                        GameTile(
                            game,
                            CustomCovers.matchIn(customCovers, game),
                            Modifier.width(tileWidth).fillMaxHeight(),
                        ) { onPlay(game) }
                    }
                }
                val back = listState.canScrollBackward
                val forward = listState.canScrollForward
                if (back) EdgeHint(Alignment.CenterStart, pointsRight = false)
                if (forward) EdgeHint(Alignment.CenterEnd, pointsRight = true)
            }
            if (listState.canScrollBackward || listState.canScrollForward) {
                Spacer(Modifier.height(trackSpace - 4.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ScrollTrack(listState, games.size)
                }
            }
        }
    }
}

/** A soft fade over the row's edge with a small arrow, saying "more this way". */
@Composable
private fun BoxScope.EdgeHint(alignment: Alignment, pointsRight: Boolean) {
    val fade = if (pointsRight) listOf(Color.Transparent, BuzzColors.Ground) else listOf(BuzzColors.Ground, Color.Transparent)
    Box(
        Modifier
            .align(alignment)
            .fillMaxHeight()
            .width(56.dp)
            .background(Brush.horizontalGradient(fade)),
        contentAlignment = if (pointsRight) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(Color(0xCC161A22)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (pointsRight) "›" else "‹", color = BuzzColors.Dim, fontSize = 22.sp)
        }
    }
}

/** A slim track with a thumb showing which part of the list is on screen. */
@Composable
private fun ScrollTrack(listState: androidx.compose.foundation.lazy.LazyListState, total: Int) {
    val info = listState.layoutInfo
    val visibleCount = info.visibleItemsInfo.size.coerceAtLeast(1)
    val first = listState.firstVisibleItemIndex
    val thumbFraction = (visibleCount.toFloat() / total).coerceIn(0.1f, 1f)
    val maxStart = (total - visibleCount).coerceAtLeast(1)
    val startFraction = (first.toFloat() / maxStart).coerceIn(0f, 1f) * (1f - thumbFraction)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth(0.3f)
            .height(4.dp)
            .clip(RoundedCornerShape(50))
            .background(BuzzColors.Hairline),
    ) {
        Box(
            Modifier
                .padding(start = maxWidth * startFraction)
                .width(maxWidth * thumbFraction)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(BuzzColors.Dim),
        )
    }
}

@Composable
private fun CenterMessage(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) { content() }
}

@Composable
private fun GameTile(game: GameInfo, customCover: java.io.File?, modifier: Modifier, onClick: () -> Unit) {
    val title = game.displayTitle(false)
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(BuzzColors.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Play $title" }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp)).background(BuzzColors.Ring),
            contentAlignment = Alignment.Center,
        ) {
            val model: Any? = customCover ?: game.coverUrl
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(title, color = BuzzColors.Dim, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(10.dp))
            }
        }
        Text(
            title,
            color = BuzzColors.Text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GearButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(BuzzColors.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Settings" },
        contentAlignment = Alignment.Center,
    ) {
        Text("⚙", color = BuzzColors.Dim, fontSize = 24.sp)
    }
}

/**
 * The only settings a player should need: phones on or off, and which folder the games are in.
 * Everything else is one step further, in ARMSX2's own settings, for troubleshooting.
 */
@Composable
private fun BuzzSettings(onBack: () -> Unit, setup: OnboardingViewModel = viewModel()) {
    BackHandler(onBack = onBack)
    val folder = MainActivityRuntime.romsDirs.value.firstOrNull()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(setup::changeGameFolder)
    }
    val phones = BuzzEdition.phonesEnabled.value
    Column(
        Modifier.fillMaxSize().background(BuzzColors.Ground).padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(BuzzColors.Surface)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) { Text("‹", color = BuzzColors.Text, fontSize = 30.sp) }
            Text("Settings", color = BuzzColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Black)
        }
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsRow(
                title = "Phone buzzers",
                detail = "Let phones on the same Wi-Fi join as players",
                onClick = { BuzzEdition.setPhonesEnabled(!phones) },
            ) {
                Switch(
                    checked = phones,
                    onCheckedChange = { BuzzEdition.setPhonesEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = BuzzColors.Yellow,
                        checkedThumbColor = BuzzColors.Ground,
                    ),
                )
            }
            SettingsRow(
                title = "Games folder",
                detail = folder?.let(::folderName) ?: "Not chosen",
                onClick = { picker.launch(null) },
            ) { Text("Change", color = BuzzColors.Yellow, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            SettingsRow(
                title = "Emulator settings",
                detail = "Graphics, performance and fixes, for troubleshooting only",
                onClick = { UiNavigator.navigate(AppRoute.Settings()) },
            ) { Text("›", color = BuzzColors.Dim, fontSize = 26.sp) }
        }
        Text("Player count lives on the bottom screen", color = BuzzColors.Dim, fontSize = 13.sp)
    }
}

@Composable
private fun SettingsRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(BuzzColors.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = BuzzColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = BuzzColors.Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** A readable name for a picked folder: the last part of its path, not the raw content URI. */
private fun folderName(uri: String): String =
    android.net.Uri.decode(uri).substringAfterLast(':').substringAfterLast('/').ifBlank { uri }
