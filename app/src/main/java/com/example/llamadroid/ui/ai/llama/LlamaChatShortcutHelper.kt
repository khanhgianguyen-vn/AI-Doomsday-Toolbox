package com.example.llamadroid.ui.ai.llama

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import com.example.llamadroid.MainActivity
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.ui.navigation.Screen

object LlamaChatShortcutHelper {
    fun requestPinShortcut(context: Context, chat: LlamaChatEntity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(
                context,
                context.getString(R.string.llama_shortcut_unavailable),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported) {
            Toast.makeText(
                context,
                context.getString(R.string.llama_shortcut_unavailable),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, Screen.LlamaChat.createRoute(chat.id, -1))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val shortcut = ShortcutInfo.Builder(context, "llama_chat_${chat.id}")
            .setShortLabel(chat.title.take(24).ifBlank { context.getString(R.string.llama_chat_shortcut_label) })
            .setLongLabel(chat.title.ifBlank { context.getString(R.string.llama_chat_shortcut_label) })
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()

        shortcutManager.requestPinShortcut(shortcut, null)
        Toast.makeText(
            context,
            context.getString(R.string.llama_shortcut_requested),
            Toast.LENGTH_SHORT
        ).show()
    }
}
