package com.chatrabbit.alarm.data

import android.content.Context
import com.chatrabbit.alarm.settings.SettingsRepository

/**
 * 极简手写依赖容器（避免引入 Hilt/Koin 带来的额外注解处理与版本风险）。
 * 由 [com.chatrabbit.alarm.ChatRabbitApp] 持有，Activity / Compose / Receiver 共用。
 */
class AppContainer(context: Context) {

    val context: Context = context.applicationContext

    val settingsRepository: SettingsRepository = SettingsRepository(this.context)

    val alarmRepository: AlarmRepository = AlarmRepository(this.context)

    val deviceRepository: DeviceRepository =
        DeviceRepository(this.context, settingsRepository, alarmRepository)

    /** 路由器地址探测 / 校验（自动检测、修改地址、首次启动自动配置）
     *  传入 context：自动检测要按手机当前 Wi-Fi 的网关/网段生成候选。 */
    val routerDiscovery: RouterDiscovery = RouterDiscovery(this.context, settingsRepository)
}
