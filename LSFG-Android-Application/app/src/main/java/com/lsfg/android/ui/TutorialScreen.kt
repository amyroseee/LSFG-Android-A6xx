package com.lsfg.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import com.lsfg.android.R
import com.lsfg.android.ui.components.IconBadge
import com.lsfg.android.ui.components.LsfgCard
import com.lsfg.android.ui.components.LsfgTopBar
import com.lsfg.android.ui.theme.LsfgPrimary

/**
 * One step of the tutorial. Add new entries to [tutorialSteps] below as you
 * drop screenshots into res/drawable-nodpi.
 */
private data class TutorialStep(
    val title: String,
    val description: String,
    @DrawableRes val image: Int?,
)

/**
 * Tutorial entries. Replace the placeholder list with your own steps:
 *
 *   TutorialStep(
 *       title = "Step 1 — Pick the DLL",
 *       description = "Long description shown under the screenshot.",
 *       image = R.drawable.tutorial_step_1,
 *   ),
 *
 * Drop the PNG/JPG files into LSFG-Android/app/src/main/res/drawable-nodpi/
 * (any density-agnostic folder works) and reference them via R.drawable.<name>.
 * Leave [image] = null to render a text-only step.
 */
private val tutorialSteps: List<TutorialStep> = listOf(
    // TODO: aggiungi qui i tuoi step. Esempio:
    // TutorialStep(
    //     title = "1. Seleziona la DLL",
    //     description = "Apri 'Select DLL' dalla home e scegli Lossless.dll dalla tua copia di Lossless Scaling.",
    //     image = R.drawable.tutorial_step_1,
    // ),

    TutorialStep(
        title = "1. Accessibility permissions — Step 1",
        description = "LSFG-Android needs accessibility permissions to drive the overlay and frame generation correctly. Open your device's Accessibility settings either directly from the system Settings app, or by tapping the accessibility icon in the top-right corner of the LSFG-Android home screen.",
        image = R.drawable.tutorial_step_1,
    ),

    TutorialStep(
        title = "1. Accessibility permissions — Step 2",
        description = "In the Accessibility settings list, find and tap \"LSFG Touch Passthrough\".",
        image = R.drawable.tutorial_step_2,
    ),

    TutorialStep(
        title = "1. Accessibility permissions — Step 3",
        description = "Toggle the accessibility permission on for LSFG-Android, exactly as shown in the screenshot.",
        image = R.drawable.tutorial_step_3,
    ),

    TutorialStep(
        title = "1. Accessibility permissions — Step 4",
        description = "If the toggle won't turn on, your device is likely blocking restricted settings for sideloaded apps. Open the Android app info screen for LSFG-Android and look at the top-right corner — you'll see a menu icon (highlighted by the red square in the screenshot). Tap it and enable the option that appears (usually \"Allow restricted settings\"). Once done, go back and repeat the previous step — the accessibility toggle will now turn on.",
        image = R.drawable.tutorial_step_4,
    ),

    TutorialStep(
        title = "2. Loading Lossless.dll — Step 1",
        description = "From the home screen, tap \"Select DLL\" and pick the Lossless.dll file from your own copy of Lossless Scaling.",
        image = R.drawable.tutorial_step_5,
    ),

    TutorialStep(
        title = "2. Loading Lossless.dll — Step 2",
        description = "Read the legal notice carefully. To use this app you must own a legitimate copy of Lossless Scaling purchased from Steam — the Lossless.dll you load must come from your own personal copy. The app does not ship the DLL and never will.",
        image = R.drawable.tutorial_step_6,
    ),

    TutorialStep(
        title = "2. Loading Lossless.dll — Step 3",
        description = "Use the system file picker to select Lossless.dll from your copy of Lossless Scaling.",
        image = R.drawable.tutorial_step_7,
    ),

    TutorialStep(
        title = "2. Loading Lossless.dll — Step 4",
        description = "Once the DLL has been processed, you should land on this screen — the shaders are now extracted and ready.",
        image = R.drawable.tutorial_step_8,
    ),

    TutorialStep(
        title = "3. Picking the target app — Step 1",
        description = "To choose which app frame generation should run on, open section 2 of the home menu — \"Target app\".",
        image = R.drawable.tutorial_step_9,
    ),

    TutorialStep(
        title = "3. Picking the target app — Step 2",
        description = "You'll see every app and game installed on your device. Use the search bar to quickly find the one you want, then tap it to set it as the target.",
        image = R.drawable.tutorial_step_10,
    ),

    TutorialStep(
        title = "4. Overlay & display — Step 1",
        description = "This section contains all the settings related to the LSFG overlay and how it's displayed on screen.",
        image = R.drawable.tutorial_step_11,
    ),

    TutorialStep(
        title = "4. Overlay & display — Step 2",
        description = "This setting controls how you reach the in-session overlay menu. The default is an icon button: tap it during a session to open the live settings drawer. Alternatively you can switch to a swipe-in Drawer (drag from the screen edge toward the center). Warning: on some Android devices the Drawer mode does not work reliably, leaving you unable to open the overlay menu or even close the overlay. If this happens, you may need to reboot the device to restore the icon button mode. Try the icon button first.",
        image = R.drawable.tutorial_step_12,
    ),

    TutorialStep(
        title = "4. Overlay & display — Step 3",
        description = "If your device supports the Drawer, you can use this setting to choose which screen edge it lives on (left, right, top or bottom).",
        image = R.drawable.tutorial_step_13,
    ),

    TutorialStep(
        title = "4. Overlay & display — Step 4",
        description = "Here you can enable the on-screen FPS counter and the frame-pacing graph. Note: these readouts are best-effort and may not be 100% accurate.",
        image = R.drawable.tutorial_step_14,
    ),

    TutorialStep(
        title = "4. Overlay & display — Step 5",
        description = "This option hosts the overlay through the accessibility service instead of a regular system overlay. If the standard overlay misbehaves on your device, try enabling this — it's more robust against aggressive OEM background killers.",
        image = R.drawable.tutorial_step_15,
    ),

    TutorialStep(
        title = "4. Overlay & display — Step 6",
        description = "Choose between MediaProjection capture (the default) and Shizuku-assisted capture. If you've already tried MediaProjection — both with and without the accessibility-hosted overlay from the previous step — and the overlay still doesn't work, switch to Shizuku here as a fallback.",
        image = R.drawable.tutorial_step_16,
    ),

    TutorialStep(
        title = "5. Frame generation & pacing — Step 1",
        description = "This section gathers every setting that controls frame generation behavior.",
        image = R.drawable.tutorial_step_17,
    ),

    TutorialStep(
        title = "5. Frame generation & pacing — Step 2",
        description = "These are the settings I consider optimal on top-tier devices such as the Snapdragon 8 Elite Gen 5 — though Flow Scale can safely be pushed up to 1 on those chips. Even on flagship devices I recommend sticking to the Performance variant of LSFG rather than the standard one. Anti-artifacts mode is experimental and I do not recommend enabling it.",
        image = R.drawable.tutorial_step_18,
    ),

    TutorialStep(
        title = "6. Starting the overlay — Step 1",
        description = "Once everything is configured, tap \"START SESSION\" to launch the target app together with the frame-generation overlay.",
        image = R.drawable.tutorial_step_19,
    ),

    TutorialStep(
        title = "6. Starting the overlay — Step 2",
        description = "On some Android devices a screen-sharing picker may appear at this point. When it does, choose to share the same app you already selected as the target inside LSFG-Android — not the entire screen.",
        image = R.drawable.tutorial_step_20,
    ),

    TutorialStep(
        title = "6. Starting the overlay — Step 3",
        description = "The target app will now launch with the LSFG overlay running on top, and frame generation will be active.",
        image = R.drawable.tutorial_step_21,
    ),

    TutorialStep(
        title = "7. Overlay menu — Step 1",
        description = "If you picked the icon button from the settings, this is how it appears on the side of the screen during a session — tap it to open the overlay menu.",
        image = R.drawable.tutorial_step_22,
    ),

    TutorialStep(
        title = "7. Overlay menu — Step 2",
        description = "If you picked the Drawer from the settings, this is how it appears on the screen edge you selected. To open the overlay menu, swipe the Drawer from the edge toward the center of the screen.",
        image = R.drawable.tutorial_step_23,
    ),

    TutorialStep(
        title = "7. Overlay menu — Step 3",
        description = "These are all the options available inside the overlay menu — every parameter listed here can be tweaked live during a session.",
        image = R.drawable.tutorial_step_24,
    ),
    TutorialStep(
        title = "7. Overlay menu — Step 4",
        description = "",
        image = R.drawable.tutorial_step_25,
    ),
    TutorialStep(
        title = "7. Overlay menu — Step 5",
        description = "",
        image = R.drawable.tutorial_step_26,
    ),
    TutorialStep(
        title = "7. Overlay menu — Step 6",
        description = "",
        image = R.drawable.tutorial_step_27,
    ),
)

@Composable
fun TutorialScreen(nav: NavHostController) {
    var zoomedImage by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        LsfgTopBar(
            title = stringResource(R.string.nav_tutorial),
            onBack = { nav.popBackStack() },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
            ) {
                IconBadge(icon = Icons.Filled.School, tint = LsfgPrimary, size = 56.dp)
            }

            LsfgCard(accent = true) {
                Text(
                    text = stringResource(R.string.tutorial_subtitle).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = LsfgPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.tutorial_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (tutorialSteps.isEmpty()) {
                LsfgCard {
                    Text(
                        text = stringResource(R.string.tutorial_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                tutorialSteps.forEachIndexed { index, step ->
                    TutorialStepCard(
                        index = index + 1,
                        step = step,
                        onImageClick = { res -> zoomedImage = res },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    zoomedImage?.let { res ->
        Dialog(
            onDismissRequest = { zoomedImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { zoomedImage = null },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = res),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentScale = ContentScale.Fit,
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(20.dp)
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { zoomedImage = null },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun TutorialStepCard(
    index: Int,
    step: TutorialStep,
    onImageClick: (Int) -> Unit,
) {
    LsfgCard {
        Text(
            text = "STEP ${index.toString().padStart(2, '0')}",
            style = MaterialTheme.typography.labelSmall,
            color = LsfgPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = step.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (step.image != null) {
            Spacer(Modifier.height(12.dp))
            Image(
                painter = painterResource(id = step.image),
                contentDescription = step.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onImageClick(step.image) },
            )
        }
        if (step.description.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = step.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
