package com.mobileagent.app

import android.app.Application
import com.mobileagent.app.service.AgentForegroundService
import kotlinx.coroutines.launch

class MobileAgentApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        AgentForegroundService.createChannel(this)
        graph.appScope.launch { graph.bootstrap() }
    }
}
