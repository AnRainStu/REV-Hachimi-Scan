package com.scanner.app.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

import com.scanner.app.ui.camera.CameraScreen
import com.scanner.app.ui.crop.CropScreen
import com.scanner.app.ui.review.ReviewScreen
import com.scanner.app.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Camera : Screen("camera")
    object Crop : Screen("crop/{pageId}") {
        fun createRoute(pageId: String) = "crop/$pageId"
    }
    object Review : Screen("review")
    object Settings : Screen("settings")
}

@Composable
fun DocScannerNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Review.route,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Screen.Camera.route) {
            CameraScreen(
                onNavigateToReview = { navController.popBackStack(Screen.Review.route, false) },
                onNavigateToCrop = { pageId -> navController.navigate(Screen.Crop.createRoute(pageId)) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }
        
        composable(
            route = Screen.Crop.route,
            arguments = listOf(navArgument("pageId") { type = NavType.StringType })
        ) { backStackEntry ->
            val pageId = backStackEntry.arguments?.getString("pageId") ?: ""
            CropScreen(
                pageId = pageId,
                onConfirm = {
                    navController.navigate(Screen.Review.route) {
                        popUpTo(Screen.Review.route)
                        launchSingleTop = true
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }
        
        composable(Screen.Review.route) {
            ReviewScreen(
                onNavigateToCamera = {
                    navController.navigate(Screen.Camera.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToCrop = { pageId -> navController.navigate(Screen.Crop.createRoute(pageId)) }
            )
        }
        
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
