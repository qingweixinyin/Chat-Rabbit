# 聊天兔闹钟 —— R8/ProGuard 规则
# 默认 release 关闭了 minifyEnabled；若开启混淆，下面规则已够用。

# ---------- 基础 ----------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- OkHttp / Okio ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# ---------- org.json（Android 平台实现，仅防反射告警） ----------
-dontwarn org.json.**

# ---------- Kotlin / 协程 ----------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlin.Metadata { *; }

# ---------- 本 App 数据模型（DataStore JSON 里按字段名读写） ----------
-keep class com.chatrabbit.alarm.data.** { *; }

# ---------- 四大组件（清单里已声明，避免被裁剪） ----------
-keep class com.chatrabbit.alarm.alarm.AlarmReceiver { *; }
-keep class com.chatrabbit.alarm.alarm.BootReceiver { *; }
-keep class com.chatrabbit.alarm.alarm.AlarmRingService { *; }
-keep class com.chatrabbit.alarm.alarm.AlarmActivity { *; }
-keep class com.chatrabbit.alarm.MainActivity { *; }
-keep class com.chatrabbit.alarm.ChatRabbitApp { *; }
