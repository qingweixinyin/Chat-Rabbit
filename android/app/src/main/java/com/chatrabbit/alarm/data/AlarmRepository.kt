package com.chatrabbit.alarm.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

// 每个 DataStore 必须是文件内唯一的顶层委托属性
private val Context.alarmDataStore: DataStore<Preferences> by preferencesDataStore(name = "alarms")

/**
 * 闹钟仓库：用 DataStore Preferences 保存一段 JSON 字符串，内容是 List<Alarm>。
 * 所有写操作都在 edit {} 内做 “读-改-写”，DataStore 本身保证原子性。
 */
class AlarmRepository(context: Context) {

    private val dataStore: DataStore<Preferences> = context.applicationContext.alarmDataStore
    private val alarmsKey = stringPreferencesKey("alarms_json")

    val alarms: Flow<List<Alarm>> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> AlarmJson.decode(preferences[alarmsKey]) }

    suspend fun getAlarmsOnce(): List<Alarm> = alarms.first()

    suspend fun getAlarm(id: String): Alarm? = getAlarmsOnce().firstOrNull { it.id == id }

    suspend fun save(alarm: Alarm) {
        dataStore.edit { preferences ->
            val current = AlarmJson.decode(preferences[alarmsKey]).toMutableList()
            val index = current.indexOfFirst { it.id == alarm.id }
            if (index >= 0) {
                current[index] = alarm
            } else {
                current.add(alarm)
            }
            preferences[alarmsKey] = AlarmJson.encode(current)
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { preferences ->
            val current = AlarmJson.decode(preferences[alarmsKey])
            preferences[alarmsKey] = AlarmJson.encode(current.filterNot { it.id == id })
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dataStore.edit { preferences ->
            val current = AlarmJson.decode(preferences[alarmsKey])
            val updated = current.map { alarm ->
                if (alarm.id == id) alarm.copy(enabled = enabled) else alarm
            }
            preferences[alarmsKey] = AlarmJson.encode(updated)
        }
    }

    suspend fun replaceAll(list: List<Alarm>) {
        dataStore.edit { preferences ->
            preferences[alarmsKey] = AlarmJson.encode(list)
        }
    }
}
