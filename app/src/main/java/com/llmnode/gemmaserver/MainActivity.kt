package com.llmnode.gemmaserver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.llmnode.gemmaserver.ui.GemmaServerScreen

private val GemmaDarkColorScheme = darkColorScheme(
    primary = Color(0xFF58A6FF),
    secondary = Color(0xFF3FB950),
    tertiary = Color(0xFFD29922),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3)
)

class MainActivity : ComponentActivity() {

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme(colorScheme = GemmaDarkColorScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GemmaServerScreen()
                }
            }
        }
    }
}
