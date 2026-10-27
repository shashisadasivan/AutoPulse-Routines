package com.citrusd365.autopulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.citrusd365.autopulse.service.AutomationForegroundService
import com.citrusd365.autopulse.ui.MainScreen
import com.citrusd365.autopulse.ui.theme.AndroidAutomateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AutomationForegroundService.startOrUpdate(this)

        setContent {
            AndroidAutomateTheme {
                MainScreen()
            }
        }
    }
}
