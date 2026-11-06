package com.chatrabbit.alarm

import android.app.Application
import android.util.Log
import com.chatrabbit.alarm.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ChatRabbitApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 首次启动：后台探测常见网段的聊天兔服务地址（不阻塞 UI，只跑一次）。
        // 仅当用户还没改过地址（仍是默认值）时才会覆盖，避免冲掉自定义地址。
        appScope.launch {
            runCatching { container.routerDiscovery.autoConfigureOnce() }
                .onFailure { Log.w(TAG, "首次自动检测路由器地址失败", it) }
        }
    }

    private companion object {
        const val TAG = "ChatRabbitApp"
    }
}
