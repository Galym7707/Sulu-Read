package com.example.sulu_read.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.sulu_read.domain.model.UserProfile
import com.example.sulu_read.domain.repository.SuluReadRepository
import com.example.sulu_read.ui.screens.ProgressScreen
import com.example.sulu_read.ui.screens.ProgressViewModel
import com.example.sulu_read.ui.screens.ProfileScreen
import com.example.sulu_read.ui.screens.ReaderScreen
import com.example.sulu_read.ui.screens.ScreeningScreen
import com.example.sulu_read.ui.screens.ScreeningViewModel
import com.example.sulu_read.ui.screens.SuluReadViewModelFactory
import com.example.sulu_read.ui.screens.TrainingScreen
import com.example.sulu_read.ui.screens.TrainingViewModel
import com.example.sulu_read.ui.screens.UiState
import com.example.sulu_read.ui.screens.MainViewModel
import com.example.sulu_read.R

private enum class SuluDestination(val route: String, val labelRes: Int) {
    Reader("reader", R.string.nav_reader),
    Training("training", R.string.nav_training),
    Screening("screening", R.string.nav_screening),
    Progress("progress", R.string.nav_progress),
    Settings("settings", R.string.nav_settings)
}

@Composable
fun SuluReadNavGraph(repository: SuluReadRepository, languageCode: String) {
    val navController = rememberNavController()
    val factory = remember(repository) { SuluReadViewModelFactory(repository) }
    val mainViewModel: MainViewModel = viewModel(factory = factory)
    val userState by mainViewModel.userState.collectAsStateWithLifecycle()
    val user = (userState as? UiState.Success<UserProfile>)?.data
    var trainingSourceWords by remember { mutableStateOf<List<String>>(emptyList()) }

    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route ?: SuluDestination.Reader.route
            NavigationBar {
                SuluDestination.entries.forEach { destination ->
                    val label = stringResource(destination.labelRes)
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = when (destination) {
                                    SuluDestination.Reader -> Icons.AutoMirrored.Filled.MenuBook
                                    SuluDestination.Training -> Icons.Default.FitnessCenter
                                    SuluDestination.Screening -> Icons.Default.TrackChanges
                                    SuluDestination.Progress -> Icons.Default.Analytics
                                    SuluDestination.Settings -> Icons.Default.Settings
                                },
                                contentDescription = label
                            )
                        },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = SuluDestination.Reader.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(SuluDestination.Reader.route) {
                ReaderScreen(
                    repository = repository,
                    languageCode = languageCode,
                    onCreateTraining = { words ->
                        trainingSourceWords = words
                        navController.navigate(SuluDestination.Training.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(SuluDestination.Training.route) {
                val viewModel: TrainingViewModel = viewModel(factory = factory)
                TrainingScreen(
                    userId = user?.userId,
                    isProfileLoading = userState is UiState.Loading,
                    profileErrorResId = (userState as? UiState.Error)?.messageResId,
                    onRetryProfile = mainViewModel::ensureUser,
                    sourceWords = trainingSourceWords,
                    languageCode = languageCode,
                    viewModel = viewModel
                )
            }
            composable(SuluDestination.Screening.route) {
                val viewModel: ScreeningViewModel = viewModel(factory = factory)
                ScreeningScreen(
                    userId = user?.userId,
                    viewModel = viewModel
                )
            }
            composable(SuluDestination.Progress.route) {
                val viewModel: ProgressViewModel = viewModel(factory = factory)
                ProgressScreen(
                    userId = user?.userId,
                    viewModel = viewModel
                )
            }
            composable(SuluDestination.Settings.route) {
                ProfileScreen(
                    user = user,
                    selectedLanguageCode = languageCode,
                    isBusy = userState is UiState.Loading,
                    authErrorResId = (userState as? UiState.Error)?.messageResId,
                    onLanguageSelected = { selectedCode ->
                        mainViewModel.changeLanguage(selectedCode)
                    },
                    onRegister = mainViewModel::register,
                    onLogin = mainViewModel::login
                )
            }
        }
    }
}
