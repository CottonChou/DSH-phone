package com.solace.dshphone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.solace.dshphone.notify.Notifier
import com.solace.dshphone.ui.CapabilityCheckScreen
import com.solace.dshphone.ui.ChatScreen
import com.solace.dshphone.ui.SettingsScreen
import com.solace.dshphone.ui.TaskCenterScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel by lazy { ChatViewModel(application as DshPhoneApp) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannels(this)
        setContent {
            DshPhoneTheme {
                RootNav()
            }
        }
    }

    @Composable
    private fun RootNav() {
        val nav = rememberNavController()
        NavHost(navController = nav, startDestination = "chat") {
            composable("chat") {
                val uiState by viewModel.uiState.collectAsState()
                ChatScreen(
                    messages = uiState.messages,
                    running = uiState.running,
                    statusText = uiState.status,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable("settings") {
                val app = application as DshPhoneApp
                SettingsScreen(
                    initialProvider = app.settings.providerId,
                    initialBaseUrl = app.settings.baseUrl,
                    initialModel = app.settings.modelName,
                    initialKey = app.settings.apiKey,
                    initialAutonomous = app.settings.approvalMode == com.solace.dshphone.core.action.ApprovalMode.AUTONOMOUS,
                    engineLabel = app.engineHost.describe(),
                    safeMode = app.engineHost.safeMode(),
                    onSave = { provider, baseUrl, model, key, autonomous ->
                        app.settings.providerId = provider
                        app.settings.baseUrl = baseUrl
                        app.settings.modelName = model
                        app.settings.apiKey = key
                        app.settings.approvalMode =
                            if (autonomous) com.solace.dshphone.core.action.ApprovalMode.AUTONOMOUS
                            else com.solace.dshphone.core.action.ApprovalMode.ASK
                        nav.popBackStack()
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("check") { CapabilityCheckScreen(onBack = { nav.popBackStack() }) }
            composable("tasks") { TaskCenterScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun DshPhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
