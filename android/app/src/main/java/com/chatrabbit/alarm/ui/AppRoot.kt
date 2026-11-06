package com.chatrabbit.alarm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.chatrabbit.alarm.ChatRabbitApp
import com.chatrabbit.alarm.R
import com.chatrabbit.alarm.ui.alarms.AlarmListScreen
import com.chatrabbit.alarm.ui.device.DeviceScreen
import com.chatrabbit.alarm.ui.edit.AlarmEditScreen
import com.chatrabbit.alarm.ui.provision.ProvisionScreen
import com.chatrabbit.alarm.ui.settings.SettingsScreen

/** 底部四个主 Tab */
private enum class MainTab(val title: String) {
    ALARMS("闹钟"),
    DEVICE("设备"),
    PROVISION("配网"),
    SETTINGS("设置"),
}

/** Tab 图标（配网用自带 WiFi 矢量图，其余用 material-icons-core 内置图标） */
@Composable
private fun TabIcon(tab: MainTab) {
    when (tab) {
        MainTab.ALARMS -> Icon(Icons.Default.Notifications, contentDescription = null)
        MainTab.DEVICE -> Icon(Icons.Default.Build, contentDescription = null)
        MainTab.PROVISION -> Icon(painterResource(R.drawable.ic_tab_provision), contentDescription = null)
        MainTab.SETTINGS -> Icon(Icons.Default.Settings, contentDescription = null)
    }
}

/**
 * App 根界面。
 *
 * 只有一个 Scaffold：状态栏 / 导航栏 / 输入法 Insets 全部交给它处理，
 * 各页面只负责内容，避免重复叠加 WindowInsets 导致留白或内容被系统栏遮挡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as ChatRabbitApp).container }
    val snackbarHostState = remember { SnackbarHostState() }

    var currentTabName by remember { mutableStateOf(MainTab.ALARMS.name) }
    var editorVisible by remember { mutableStateOf(false) }
    var editingAlarmId by remember { mutableStateOf<String?>(null) }

    if (editorVisible) {
        BackHandler { editorVisible = false }
        AlarmEditScreen(
            container = container,
            alarmId = editingAlarmId,
            onClose = { editorVisible = false },
        )
        return
    }

    val currentTab = MainTab.entries.firstOrNull { it.name == currentTabName } ?: MainTab.ALARMS
    val openEditor: (String?) -> Unit = { id ->
        editingAlarmId = id
        editorVisible = true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(currentTab.title) })
        },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTabName = tab.name },
                        icon = { TabIcon(tab) },
                        label = { Text(tab.title) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (currentTab == MainTab.ALARMS) {
                ExtendedFloatingActionButton(
                    onClick = { openEditor(null) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("新建闹钟") },
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (currentTab) {
                MainTab.ALARMS -> AlarmListScreen(
                    container = container,
                    snackbarHostState = snackbarHostState,
                    onEditAlarm = { alarm -> openEditor(alarm.id) },
                    modifier = Modifier.fillMaxSize(),
                )

                MainTab.DEVICE -> DeviceScreen(
                    container = container,
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.fillMaxSize(),
                )

                MainTab.PROVISION -> ProvisionScreen(
                    container = container,
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.fillMaxSize(),
                )

                MainTab.SETTINGS -> SettingsScreen(
                    container = container,
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
