package com.voxislabs.chargeguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val bootAction = intent?.action ?: return
        if (bootAction != Intent.ACTION_BOOT_COMPLETED && bootAction != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }

        val monitorIntent = Intent(context, GuardService::class.java).apply {
            action = GuardService.ACTION_START_MONITORING
        }
        ContextCompat.startForegroundService(context, monitorIntent)
    }
}
