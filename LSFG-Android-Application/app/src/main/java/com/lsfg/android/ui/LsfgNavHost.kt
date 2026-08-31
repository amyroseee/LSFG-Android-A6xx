package com.lsfg.android.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

object Routes {
    const val HOME = "home"
    const val LEGAL = "legal"
    const val DLL = "dll"
    const val PARAMS_FRAMEGEN_PACING = "params_framegen_pacing"
    const val PARAMS_IMAGE_QUALITY = "params_image_quality"
    const val OVERLAY_DISPLAY = "overlay_display"
    const val APP_PICKER = "app_picker"
    const val AUTOMATIC_OVERLAY = "automatic_overlay"
    const val TUTORIAL = "tutorial"
    const val BENCHMARK = "benchmark"
}

private const val ANIM_MS = 240

@Composable
fun LsfgNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = {
            slideInHorizontally(tween(ANIM_MS)) { it / 6 } + fadeIn(tween(ANIM_MS))
        },
        exitTransition = {
            slideOutHorizontally(tween(ANIM_MS)) { -it / 6 } + fadeOut(tween(ANIM_MS))
        },
        popEnterTransition = {
            slideInHorizontally(tween(ANIM_MS)) { -it / 6 } + fadeIn(tween(ANIM_MS))
        },
        popExitTransition = {
            slideOutHorizontally(tween(ANIM_MS)) { it / 6 } + fadeOut(tween(ANIM_MS))
        },
    ) {
        composable(Routes.HOME) { HomeScreen(navController) }
        composable(Routes.LEGAL) { LegalScreen(navController) }
        composable(Routes.DLL) { DllPickerScreen(navController) }
        composable(Routes.PARAMS_FRAMEGEN_PACING) { ParamsFrameGenPacingScreen(navController) }
        composable(Routes.PARAMS_IMAGE_QUALITY) { ParamsImageQualityScreen(navController) }
        composable(Routes.OVERLAY_DISPLAY) { OverlayDisplayScreen(navController) }
        composable(Routes.APP_PICKER) { AppPickerScreen(navController) }
        composable(Routes.AUTOMATIC_OVERLAY) { AutomaticOverlayScreen(navController) }
        composable(Routes.TUTORIAL) { TutorialScreen(navController) }
        composable(Routes.BENCHMARK) { BenchmarkScreen(navController) }
    }
}
