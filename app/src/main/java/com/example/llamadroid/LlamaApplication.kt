package com.example.llamadroid

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.example.llamadroid.R
import com.example.llamadroid.data.AppContainer
import com.example.llamadroid.data.DefaultAppContainer
import com.example.llamadroid.service.AgentForegroundService
import com.example.llamadroid.service.AiRuntimeJobStore
import com.example.llamadroid.service.UnifiedNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class LlamaApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        instance = this  // Safe: Application lives for entire app lifecycle
        container = DefaultAppContainer(this)
        UnifiedNotificationManager.init(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (AiRuntimeJobStore.getActiveJobs(this@LlamaApplication).isNotEmpty()) {
                AgentForegroundService.start(
                    this@LlamaApplication,
                    getString(R.string.agent_runtime_recovering_jobs)
                )
            }
        }
        
        // Request native libs installation immediately (Simulate Fast-Follow)
        // REMOVED: Managed by MainActivity failsafe to avoid double-prompting and race conditions
        // com.example.llamadroid.util.DynamicFeatureManager.installAllFeatures(this)
    }
    
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(updateLocale(base))
        com.google.android.play.core.splitcompat.SplitCompat.install(this)
    }
    
    companion object {
        /**
         * Application instance for global access.
         * Safe because Application lives for entire app lifecycle.
         * Use this instead of storing Activity references.
         */
        lateinit var instance: LlamaApplication
            private set
        
        fun updateLocale(context: Context): Context {
            val prefs = context.getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
            val languageCode = prefs.getString("selected_language", "system") ?: "system"
            
            val locale = when (languageCode) {
                "system" -> Locale.getDefault()
                "en" -> Locale.ENGLISH
                "es" -> Locale("es")
                else -> Locale(languageCode)
            }
            
            Locale.setDefault(locale)
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            
            return context.createConfigurationContext(config)
        }
    }
}
