package com.chatrabbit.alarm.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * 给“一次性表单页”（新建/编辑闹钟）一个独立的 ViewModelStore。
 * 页面离开 composition 时清空，保证每次打开都是干净的表单状态，
 * 同时不会在 Activity 的 ViewModelStore 里堆积无用 ViewModel。
 */
@Composable
fun rememberScopedViewModelStoreOwner(): ViewModelStoreOwner {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}
