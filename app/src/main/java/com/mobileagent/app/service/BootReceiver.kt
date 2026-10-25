package com.mobileagent.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mobileagent.app.MobileAgentApplication
import kotlinx.coroutines.launch

/**
 * On boot, re-registers enabled schedules and marks interrupted tasks as paused. It never tries to
 * bypass Android's background restrictions.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val application = context.applicationContext as? MobileAgentApplication ?: return
        application.graph.appScope.launch {
            runCatching { application.graph.scheduler.restoreAll() }
            runCatching { application.graph.runtime.recoverInterrupted() }
        }
    }
}
