package com.armsx2.ui.buzz

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.armsx2.BuzzEdition
import com.armsx2.ui.onboarding.OnboardingViewModel

/**
 * First run, replacing ARMSX2's setup wizard: one folder holding the BIOS and the games, then
 * straight to the game picker. Storage stays in the app's own folder (the wizard's default), and
 * every other wizard page has a sensible default already.
 */
@Composable
fun BuzzSetupScreen(viewModel: OnboardingViewModel = viewModel()) {
    val context = LocalContext.current
    val state = viewModel.state.value
    LaunchedEffect(Unit) { BuzzEdition.applyUi(context) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::quickSetup)
    }
    Box(
        Modifier.fillMaxSize().background(BuzzColors.Ground).padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            BuzzWordmark(size = 44.sp, dot = 56.dp)
            Text(
                "Choose the folder that holds your PS2 BIOS and your Buzz! games. You only do this once.",
                color = BuzzColors.Dim,
                fontSize = 20.sp,
                lineHeight = 30.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 560.dp),
            )
            if (state.busy) {
                CircularProgressIndicator(color = BuzzColors.Yellow, modifier = Modifier.size(48.dp))
                Text("Looking for your BIOS and games…", color = BuzzColors.Text, fontSize = 18.sp)
            } else {
                BuzzPrimaryButton("Choose folder") { picker.launch(null) }
            }
            state.error?.let {
                Text(
                    it,
                    color = BuzzColors.Error,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 560.dp),
                )
            }
            Text(
                "Games and BIOS can be in the same folder or in subfolders",
                color = BuzzColors.Dim,
                fontSize = 14.sp,
            )
        }
    }
}
