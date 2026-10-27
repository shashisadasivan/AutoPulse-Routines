package com.example.androidautomate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.androidautomate.service.AutomationForegroundService
import com.example.androidautomate.ui.MainScreen
import com.example.androidautomate.ui.theme.AndroidAutomateTheme

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
