package com.chatrabbit.alarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.chatrabbit.alarm.ui.AppRoot
import com.chatrabbit.alarm.ui.theme.ChatRabbitTheme

/**
 * 唯一的主界面 Activity。
 *
 * targetSdk 36 起系统强制 edge-to-edge，这里显式调用 enableEdgeToEdge()，
 * 所有系统栏（状态栏 / 导航栏 / 输入法）都由 Compose 的 Scaffold + WindowInsets 处理。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChatRabbitTheme {
                AppRoot()
            }
        }
    }
}
