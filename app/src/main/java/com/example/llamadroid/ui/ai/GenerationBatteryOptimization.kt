package com.example.llamadroid.ui.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.llamadroid.R

class BatteryOptimizationGateState(
    private val context: Context,
    private val powerManager: PowerManager
) {
    private var pendingAction by mutableStateOf<(() -> Unit)?>(null)
    private var isBatteryOptimizationExempt by mutableStateOf(
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    )

    val showDialog: Boolean
        get() = pendingAction != null && !isBatteryOptimizationExempt

    fun runAfterCheck(action: () -> Unit) {
        pendingAction = action
        refreshBatteryOptimizationState()
    }

    fun refreshBatteryOptimizationState(): Boolean {
        val isExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        isBatteryOptimizationExempt = isExempt
        if (isExempt) {
            val action = pendingAction
            pendingAction = null
            action?.invoke()
        }
        return isExempt
    }

    fun dismiss() {
        pendingAction = null
    }

    fun continueAnyway() {
        val action = pendingAction
        pendingAction = null
        action?.invoke()
    }

    fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openDeviceSpecificFix() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

@Composable
fun rememberBatteryOptimizationGateState(): BatteryOptimizationGateState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val powerManager = remember(context) {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    val state = remember(context, powerManager) {
        BatteryOptimizationGateState(context.applicationContext, powerManager)
    }
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.refreshBatteryOptimizationState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return state
}

@Composable
fun BatteryOptimizationWarningDialog(state: BatteryOptimizationGateState) {
    if (!state.showDialog) return

    AlertDialog(
        onDismissRequest = state::dismiss,
        title = { Text(stringResource(R.string.generation_battery_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.generation_battery_dialog_message))
            }
        },
        confirmButton = {
            TextButton(onClick = state::openBatterySettings) {
                Text(stringResource(R.string.generation_battery_dialog_settings))
            }
        },
        dismissButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End
            ) {
                TextButton(onClick = state::openDeviceSpecificFix) {
                    Text(stringResource(R.string.generation_battery_dialog_oem_fix))
                }
                Row(horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = state::continueAnyway) {
                        Text(stringResource(R.string.generation_battery_dialog_continue))
                    }
                    TextButton(onClick = state::dismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    )
}
