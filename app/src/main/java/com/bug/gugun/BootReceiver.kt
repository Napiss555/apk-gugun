package com.bug.gugun

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_REBOOT) {

            val i = Intent(context, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)

            val serviceIntent = Intent(context, MyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
