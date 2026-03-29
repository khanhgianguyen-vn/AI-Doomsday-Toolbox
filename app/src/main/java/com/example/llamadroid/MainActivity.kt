package com.example.llamadroid

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import com.example.llamadroid.ui.LlamaApp
import com.example.llamadroid.util.getParcelableExtraCompat

/**
 * Shared file data from intent
 */
data class SharedFileData(
    val uri: Uri,
    val mimeType: String
)

class MainActivity : ComponentActivity() {
    
    // Share intent data
    private val sharedFileData = mutableStateOf<SharedFileData?>(null)
    private val isDeployingBinaries = mutableStateOf(true)
    private val deploymentStatusId = mutableStateOf(R.string.deployment_adjusting)
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Permission result - notifications will work if granted
    }
    
    /**
     * Override to apply locale setting to the Activity context
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LlamaApplication.updateLocale(newBase))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


// ... inside onCreate ...
        // Enable Edge-to-Edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start binary deployment with Failsafe
        lifecycle.coroutineScope.launch {
            val repo = com.example.llamadroid.data.binary.BinaryRepository(this@MainActivity)
            deploymentStatusId.value = R.string.deployment_checking
            
            // Initial checks
            var binariesReady = repo.deployAllBinaries()
            var assetsReady = com.example.llamadroid.util.AssetPackManagerUtil.areAllPacksReady(this@MainActivity)
            
            if (!binariesReady || !assetsReady) {
                // If failed, likely modules missing or not extracted.
                // Trigger download/install of all features AND asset packs
                deploymentStatusId.value = R.string.deployment_downloading
                
                if (!binariesReady) {
                    com.example.llamadroid.util.DynamicFeatureManager.installAllFeatures(this@MainActivity)
                }
                
                if (!assetsReady) {
                    launch {
                        com.example.llamadroid.util.AssetPackManagerUtil.downloadAllPacks(this@MainActivity).collect { 
                            // Just consume flow to keep it active until done
                        }
                    }
                }
                
                // Retry loop (poll for completion)
                // We give it some time to download/install
                var attempt = 0
                while ((!binariesReady || !assetsReady) && attempt < 45) { // 45 * 2s = 90s (approx)
                    kotlinx.coroutines.delay(2000)
                    if (!binariesReady) binariesReady = repo.deployAllBinaries()
                    if (!assetsReady) assetsReady = com.example.llamadroid.util.AssetPackManagerUtil.areAllPacksReady(this@MainActivity)
                    attempt++
                }
                
                if (!binariesReady || !assetsReady) {
                    deploymentStatusId.value = R.string.deployment_failed
                    kotlinx.coroutines.delay(3000) 
                }
            }
            
            isDeployingBinaries.value = false
        }
        
        // Handle share intent
        handleShareIntent(intent)
        
        setContent {
            LlamaDroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isDeployingBinaries.value) {
                         androidx.compose.foundation.layout.Box(
                             modifier = Modifier
                                .fillMaxSize()
                                .safeDrawingPadding(),
                             contentAlignment = androidx.compose.ui.Alignment.Center
                         ) {
                             androidx.compose.foundation.layout.Column(
                                 horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                 verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                                 modifier = Modifier.padding(16.dp)
                             ) {
                                 androidx.compose.material3.CircularProgressIndicator()
                                 androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))
                                 androidx.compose.material3.Text(
                                     text = androidx.compose.ui.res.stringResource(id = deploymentStatusId.value),
                                     style = MaterialTheme.typography.titleMedium,
                                     textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                 )
                                 androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                                 androidx.compose.material3.Text(
                                     text = androidx.compose.ui.res.stringResource(id = R.string.deployment_explanation),
                                     style = MaterialTheme.typography.bodyMedium,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                                     textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                 )
                             }
                         }
                    } else {
                        LlamaApp(
                            sharedFileData = sharedFileData.value,
                            onSharedFileHandled = { sharedFileData.value = null }
                        )
                    }
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }
    
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
            val mimeType = intent.type ?: ""
            
            if (uri != null && mimeType.isNotEmpty()) {
                sharedFileData.value = SharedFileData(uri, mimeType)
            }
        }
    }
}
