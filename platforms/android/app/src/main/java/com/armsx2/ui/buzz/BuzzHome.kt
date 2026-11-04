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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.armsx2.ui.settings.SettingsControllerNav
import com.armsx2.ui.settings.controllerFocusable
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
import com.armsx2.BuzzDisplay
import com.armsx2.BuzzPad
import com.armsx2.BuzzPhones
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.armsx2.BuzzEdition
import androidx.compose.foundation.verticalScroll
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

    // Continue: the Buzz! game with the newest autosave, looked up again whenever a game ends.
    val emuState = MainActivityRuntime.eState.value
    var resume by remember { mutableStateOf<Pair<GameInfo, Long>?>(null) }
    LaunchedEffect(games, emuState) {
        if (emuState != com.armsx2.EmuState.STOPPED) return@LaunchedEffect
        resume = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bySerial = games.filter { it.serial != null }.associateBy { it.serial!!.uppercase() }
                com.armsx2.ui.saves.SaveCatalog.scan(context)
                    .filter { !it.isBackup && it.id.kind == com.armsx2.ui.saves.SaveKind.Autosave }
                    .sortedByDescending { it.modified }
                    .firstNotNullOfOrNull { e -> bySerial[e.serial]?.let { it to e.modified } }
            }.getOrNull()
        }
    }

    // Controller shortcuts on this screen (MainActivityRuntime routes Start, Select and Y here).
    val activity = context as? android.app.Activity
    DisposableEffect(Unit) {
        BuzzNav.openSettings = { showSettings = true }
        BuzzNav.quit = { quitApp(activity) }
        onDispose {
            BuzzNav.openSettings = null
            BuzzNav.quit = null
            BuzzNav.showJoin.value = false
        }
    }

    val phoneVersion = BuzzPhones.version.intValue
    val players = BuzzEdition.players.intValue
    val onPhones = remember(phoneVersion) { BuzzPhones.phoneSeats().size }
    val pad = com.armsx2.ui.common.PadInput.active.value

    Box(Modifier.fillMaxSize().background(BuzzColors.Ground)) {
        Column(
            Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 28.dp, bottom = if (pad) 0.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BuzzWordmark(size = 26.sp, dot = 36.dp)
                Spacer(Modifier.weight(1f))
                PlayersChip(players, onPhones)
                RoundIconButton("⚙", "Settings", "buzz.gear") { showSettings = true }
                RoundIconButton("⏻", "Close Buzz Launcher", "buzz.quit") { quitApp(activity) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    games.isNotEmpty() -> GameShelf(
                        games,
                        customCovers,
                        resume,
                        onContinue = { game ->
                            val ok = MainActivityRuntime.launchGameFromAutosave(game, 1) { loaded ->
                                if (!loaded) android.widget.Toast.makeText(
                                    context, "Couldn't load the save, so the game started fresh.", android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                            if (!ok) viewModel.launch(game)
                        },
                    ) { viewModel.launch(it) }
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
                        BuzzSecondaryButton("Open settings", navId = "buzz.opensettings") { showSettings = true }
                    }
                }
            }
            if (pad) HintsBar() else if (games.isNotEmpty()) {
                Text(
                    "Tap a game to play",
                    color = BuzzColors.Dim,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (BuzzNav.showJoin.value) JoinCodeOverlay { BuzzNav.showJoin.value = false }
    }
}

/** Close the app: phones are told nothing more is coming, and the task leaves recents. */
private fun quitApp(activity: android.app.Activity?) {
    BuzzPhones.stop()
    activity?.finishAndRemoveTask()
}

@Composable
private fun PlayersChip(players: Int, onPhones: Int) {
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(50)).background(BuzzColors.Surface).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF2EAF4B)))
        val text = buildString {
            append(if (players == 1) "1 player" else "$players players")
            if (onPhones > 0) append(" · $onPhones on ${if (onPhones == 1) "a phone" else "phones"}")
        }
        Text(text, color = BuzzColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** Shown along the bottom while a controller is in use. */
@Composable
private fun HintsBar() {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp),
        horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Hint(listOf("A"), "Play")
        Hint(listOf("L1", "R1"), "Players")
        if (BuzzEdition.phonesEnabled.value) Hint(listOf("Y"), "Join code")
        Hint(listOf("Start"), "Settings")
        Hint(listOf("Select"), "Close")
    }
}

@Composable
private fun Hint(keys: List<String>, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.forEach { KeyCap(it) }
        Text(text, color = BuzzColors.Dim, fontSize = 14.sp, modifier = Modifier.padding(start = 2.dp))
    }
}

/** The phone join code, large on the top screen, for when the top screen is on a TV. */
@Composable
private fun JoinCodeOverlay(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val url = remember { BuzzPhones.url() }
    val qr = remember(url) { url?.let { BuzzPad.makeQrBitmap(it, 512)?.asImageBitmap() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF20B0D12))
            .clickable(role = Role.Button, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (qr != null) {
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
                    Image(qr, contentDescription = "Join code", modifier = Modifier.size(260.dp))
                }
                Text("Scan to join as a buzzer", color = BuzzColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("or go to ${com.armsx2.BuzzMdns.ADDRESS}  ·  same Wi-Fi as the Thor", color = BuzzColors.Dim, fontSize = 16.sp)
            } else {
                Text("No Wi-Fi connection", color = BuzzColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Connect the Thor to Wi-Fi, or turn on its hotspot.", color = BuzzColors.Dim, fontSize = 16.sp)
            }
            Text("Press B or tap to close", color = BuzzColors.Dim, fontSize = 14.sp)
        }
    }
}

/**
 * One row of game tiles that scrolls sideways, four to a screen. When there are more than fit, the
 * edges fade with a small arrow on the side there is more, and a slim track under the row shows
 * where in the list you are. Neither shows when every game fits.
 *
 * A plain scrolling Row rather than a LazyRow on purpose: the controller moves between tiles by
 * their on-screen positions, so every tile has to exist, including the ones scrolled out of view.
 * A library of Buzz! games is a handful of tiles, so composing them all costs nothing.
 */
@Composable
private fun GameShelf(
    games: List<GameInfo>,
    customCovers: Map<String, java.io.File>,
    resume: Pair<GameInfo, Long>?,
    onContinue: (GameInfo) -> Unit,
    onPlay: (GameInfo) -> Unit,
) {
    val scroll = rememberScrollState()
    val gap = 20.dp
    // Start on Continue, or the first game, so A gets going straight away on a controller.
    LaunchedEffect(resume != null) {
        SettingsControllerNav.focusWhenRegistered(null, if (resume != null) "buzz.continue" else "buzz.game.0")
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tileWidth = (maxWidth - gap * 3) / 4
        val viewport = maxWidth
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(
                    Modifier.fillMaxSize().horizontalScroll(scroll),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    resume?.let { (game, savedAt) ->
                        ContinueTile(
                            game,
                            CustomCovers.matchIn(customCovers, game),
                            savedAt,
                            Modifier.width(tileWidth * 2 + gap).fillMaxHeight(),
                        ) { onContinue(game) }
                    }
                    games.forEachIndexed { index, game ->
                        GameTile(
                            game,
                            CustomCovers.matchIn(customCovers, game),
                            Modifier.width(tileWidth).fillMaxHeight(),
                            navId = "buzz.game.$index",
                        ) { onPlay(game) }
                    }
                }
                if (scroll.value > 0) EdgeHint(Alignment.CenterStart, pointsRight = false)
                if (scroll.value < scroll.maxValue) EdgeHint(Alignment.CenterEnd, pointsRight = true)
            }
            if (scroll.maxValue > 0) {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ScrollTrack(scroll, viewport)
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

/** A slim track with a thumb showing which part of the row is on screen. */
@Composable
private fun ScrollTrack(scroll: ScrollState, viewport: Dp) {
    val density = LocalDensity.current
    val viewportPx = with(density) { viewport.toPx() }
    val contentPx = viewportPx + scroll.maxValue
    val thumbFraction = (viewportPx / contentPx).coerceIn(0.1f, 1f)
    val startFraction = if (scroll.maxValue > 0)
        scroll.value.toFloat() / scroll.maxValue * (1f - thumbFraction) else 0f
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
private fun GameTile(
    game: GameInfo,
    customCover: java.io.File?,
    modifier: Modifier,
    navId: String,
    onClick: () -> Unit,
) {
    val title = game.displayTitle(false)
    Column(
        modifier
            .controllerFocusable(navId, RoundedCornerShape(18.dp), onConfirm = onClick)
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
private fun RoundIconButton(icon: String, description: String, navId: String, onClick: () -> Unit) {
    Box(
        Modifier
            .controllerFocusable(navId, RoundedCornerShape(50), onConfirm = onClick)
            .size(48.dp)
            .clip(CircleShape)
            .background(BuzzColors.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(icon, color = BuzzColors.Dim, fontSize = 22.sp)
    }
}

/** The last game played, picking up from its newest autosave. Twice a tile's width. */
@Composable
private fun ContinueTile(
    game: GameInfo,
    customCover: java.io.File?,
    savedAt: Long,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val title = game.displayTitle(false)
    val shape = RoundedCornerShape(18.dp)
    val ago = remember(savedAt) {
        android.text.format.DateUtils.getRelativeTimeSpanString(
            savedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
        ).toString().replaceFirstChar { it.lowercase() }
    }
    Row(
        modifier
            .controllerFocusable("buzz.continue", shape, onConfirm = onClick)
            .clip(shape)
            .background(BuzzColors.Surface)
            .border(3.dp, BuzzColors.Yellow, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Continue $title" }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.fillMaxHeight().aspectRatio(0.72f).clip(RoundedCornerShape(10.dp)).background(BuzzColors.Ring),
            contentAlignment = Alignment.Center,
        ) {
            val model: Any? = customCover ?: game.coverUrl
            if (model != null) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Column(
            Modifier.fillMaxHeight().weight(1f).padding(bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            Text("CONTINUE", color = BuzzColors.Yellow, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
            Text(title, color = BuzzColors.Text, fontSize = 19.sp, fontWeight = FontWeight.Black, lineHeight = 23.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("Picks up from your last save, $ago", color = BuzzColors.Dim, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/**
 * The only settings a player should need: phones on or off, and which folder the games are in.
 * Everything else is one step further, in ARMSX2's own settings, for troubleshooting.
 */
@Composable
private fun BuzzSettings(onBack: () -> Unit, setup: OnboardingViewModel = viewModel()) {
    BackHandler(onBack = onBack)
    // B on a controller closes this screen (MainActivityRuntime asks BuzzNav first).
    DisposableEffect(onBack) {
        BuzzNav.back = onBack
        onDispose { if (BuzzNav.back === onBack) BuzzNav.back = null }
    }
    LaunchedEffect(Unit) { SettingsControllerNav.focusWhenRegistered(null, "buzz.settings.phones") }
    val folder = MainActivityRuntime.romsDirs.value.firstOrNull()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(setup::changeGameFolder)
    }
    val phones = BuzzEdition.phonesEnabled.value
    val context = LocalContext.current
    var resolution by remember { mutableStateOf(BuzzDisplay.resolutionIndex()) }
    var aspect by remember { mutableStateOf(BuzzDisplay.aspectIndex()) }
    // Whether a TV is connected, kept current as one is plugged in or out.
    var tvName by remember { mutableStateOf(BuzzDisplay.tvName(context)) }
    var onTv by remember { mutableStateOf(BuzzDisplay.buzzersOnTv(context)) }
    DisposableEffect(Unit) {
        val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = update()
            override fun onDisplayRemoved(displayId: Int) = update()
            override fun onDisplayChanged(displayId: Int) = Unit
            fun update() {
                tvName = BuzzDisplay.tvName(context)
                onTv = BuzzDisplay.buzzersOnTv(context)
            }
        }
        dm.registerDisplayListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { dm.unregisterDisplayListener(listener) }
    }
    fun stepResolution(delta: Int) {
        resolution = (resolution + delta).mod(BuzzDisplay.resolutions.size)
        BuzzDisplay.setResolution(resolution)
    }
    fun stepAspect(delta: Int) {
        aspect = (aspect + delta).mod(BuzzDisplay.aspects.size)
        BuzzDisplay.setAspect(aspect)
    }
    fun toggleScreen() {
        onTv = !onTv
        BuzzDisplay.setBuzzersOnTv(context, onTv)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(BuzzColors.Ground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier
                    .controllerFocusable("buzz.settings.back", RoundedCornerShape(50), onConfirm = onBack)
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
                detail = "Let phones on the same Wi-Fi join as any player",
                navId = "buzz.settings.phones",
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
            val alerts = BuzzEdition.alertsEnabled.value
            SettingsRow(
                title = "Buzz-in alerts",
                detail = "Show who buzzed in over the game, for a couple of seconds",
                navId = "buzz.settings.alerts",
                onClick = { BuzzEdition.setAlertsEnabled(!alerts) },
            ) {
                Switch(
                    checked = alerts,
                    onCheckedChange = { BuzzEdition.setAlertsEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = BuzzColors.Yellow,
                        checkedThumbColor = BuzzColors.Ground,
                    ),
                )
            }
            if (phones) {
                SettingsRow(
                    title = "Phone address",
                    detail = "For phones that can't scan the code: type this into the browser",
                    navId = "buzz.settings.address",
                    onClick = {},
                ) {
                    Text(com.armsx2.BuzzMdns.ADDRESS, color = BuzzColors.Yellow, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            SettingsRow(
                title = "Games folder",
                detail = folder?.let(::folderName) ?: "Not chosen",
                navId = "buzz.settings.folder",
                onClick = { picker.launch(null) },
            ) { Text("Change", color = BuzzColors.Yellow, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            SettingsRow(
                title = "Emulator settings",
                detail = "Graphics, performance and fixes, for troubleshooting only",
                navId = "buzz.settings.emulator",
                onClick = { UiNavigator.navigate(AppRoute.Settings()) },
            ) { Text("›", color = BuzzColors.Dim, fontSize = 26.sp) }
        }
        SectionLabel("Picture")
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsRow(
                title = "Resolution",
                detail = "Higher is sharper, especially on a TV. Takes effect when a game starts.",
                navId = "buzz.settings.resolution",
                onClick = { stepResolution(1) },
                onLeft = { stepResolution(-1) },
                onRight = { stepResolution(1) },
            ) { Stepper(BuzzDisplay.resolutions[resolution].second) }
            SettingsRow(
                title = "Picture shape",
                detail = "Buzz! is made for 4:3. Widescreen or stretch fill a TV.",
                navId = "buzz.settings.aspect",
                onClick = { stepAspect(1) },
                onLeft = { stepAspect(-1) },
                onRight = { stepAspect(1) },
            ) { Stepper(BuzzDisplay.aspects[aspect].second) }
        }
        tvName?.let { name ->
            SectionLabel("TV connected")
            Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsRow(
                    title = "Buzzers show on",
                    detail = "Put the buzzers on the Thor for players, or on $name so everyone can watch them",
                    navId = "buzz.settings.tvbuzzers",
                    onClick = { toggleScreen() },
                    onLeft = { toggleScreen() },
                    onRight = { toggleScreen() },
                ) { Stepper(if (onTv) "TV" else "Thor bottom screen") }
            }
        }
        Text("Choose how many players on the bottom screen, or with L1 / R1 on the games screen", color = BuzzColors.Dim, fontSize = 13.sp)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = BuzzColors.Dim, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
}

/** A value you step through: tap or A for the next, Left / Right on a controller. */
@Composable
private fun Stepper(value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("‹", color = BuzzColors.Dim, fontSize = 22.sp)
        Text(value, color = BuzzColors.Yellow, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("›", color = BuzzColors.Dim, fontSize = 22.sp)
    }
}

@Composable
private fun SettingsRow(
    title: String,
    detail: String,
    navId: String,
    onClick: () -> Unit,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .controllerFocusable(navId, RoundedCornerShape(16.dp), onConfirm = onClick, onLeft = onLeft, onRight = onRight)
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
            Text(detail, color = BuzzColors.Dim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** A readable name for a picked folder: the last part of its path, not the raw content URI. */
private fun folderName(uri: String): String =
    android.net.Uri.decode(uri).substringAfterLast(':').substringAfterLast('/').ifBlank { uri }
