package com.rafat.whatshub.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rafat.whatshub.WhatsAppHubApp

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val app = context.applicationContext as? WhatsAppHubApp ?: return
            val anyPaired = app.repository.database.getAccounts().any { it.hasSavedSession }
            if (anyPaired) {
                WhatsAppHubService.start(context)
            }
        }
    }
}
