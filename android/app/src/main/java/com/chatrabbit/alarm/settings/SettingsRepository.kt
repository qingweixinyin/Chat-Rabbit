package com.chatrabbit.alarm.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chatrabbit.alarm.data.DEFAULT_ROUTER_BASE_URL
import com.chatrabbit.alarm.data.normalizeBaseUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

// 注意：每个 DataStore 必须是文件内唯一的顶层委托属性
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * App 设置（DataStore Preferences）：
 * - 路由器地址（支持只存 IP，写入前统一 normalize）
 * - “首次自动检测已执行”标记
 * - 当前选中的设备 id（多设备；空 = 服务端默认设备）
 */
class SettingsRepository(context: Context) {

    private val dataStore: DataStore<Preferences> = context.applicationContext.settingsDataStore

    private val preferences: Flow<Preferences> = dataStore.data.catch { throwable ->
        if (throwable is IOException) emit(emptyPreferences()) else throw throwable
    }

    val baseUrl: Flow<String> = preferences.map { it[KEY_BASE_URL] ?: DEFAULT_BASE_URL }

    suspend fun baseUrlOnce(): String = baseUrl.first()

    suspend fun setBaseUrl(rawUrl: String) {
        dataStore.edit { preferences -> preferences[KEY_BASE_URL] = normalizeBaseUrl(rawUrl) }
    }

    /** 首次启动自动检测是否已经跑过（只跑一次，避免每次冷启动都探测 5 个地址）。 */
    suspend fun isAutoDetectDoneOnce(): Boolean =
        preferences.first()[KEY_AUTO_DETECT_DONE] ?: false

    suspend fun markAutoDetectDone() {
        dataStore.edit { preferences -> preferences[KEY_AUTO_DETECT_DONE] = true }
    }

    // ==================== 多设备（v1.10.0） ====================

    /** 当前选中的设备 id；null/空 = 用服务端默认设备（旧行为）。 */
    val currentDeviceId: Flow<String?> = preferences.map { prefs ->
        prefs[KEY_CURRENT_DEVICE]?.takeIf { it.isNotBlank() }
    }

    suspend fun currentDeviceIdOnce(): String? = currentDeviceId.first()

    /**
     * 切换当前设备；传 null/空 = 回到服务端默认设备。
     * 选择会持久化，重启 App 保持。
     */
    suspend fun setCurrentDeviceId(deviceId: String?) {
        dataStore.edit { preferences ->
            if (deviceId.isNullOrBlank()) {
                preferences.remove(KEY_CURRENT_DEVICE)
            } else {
                preferences[KEY_CURRENT_DEVICE] = deviceId
            }
        }
    }

    companion object {
        /** 默认路由器地址（自建 mock 云端） */
        const val DEFAULT_BASE_URL: String = DEFAULT_ROUTER_BASE_URL

        private val KEY_BASE_URL = stringPreferencesKey("router_base_url")
        private val KEY_AUTO_DETECT_DONE = booleanPreferencesKey("router_auto_detect_done")
        private val KEY_CURRENT_DEVICE = stringPreferencesKey("current_device_id")
    }
}
