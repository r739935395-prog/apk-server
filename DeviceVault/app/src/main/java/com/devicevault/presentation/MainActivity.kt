package com.devicevault.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.devicevault.presentation.home.HomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vaultScheme = darkColorScheme(
                primary = Color(0xFFD4AF37),
                onPrimary = Color(0xFF0B0B0F),
                background = Color(0xFF0B0B0F),
                surface = Color(0xFF121217),
                onSurface = Color(0xFFF5F5F7),
                secondary = Color(0xFF181820),
                onSecondary = Color(0xFFF5F5F7)
            )
            MaterialTheme(
                colorScheme = vaultScheme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        HomeScreen()
                    }
                }
            }
        }
    }
}
