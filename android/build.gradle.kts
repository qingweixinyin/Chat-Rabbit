// 根构建脚本：仅声明插件版本，不在此处应用。
//
// 版本要求（compileSdk = 36 / Android 16）：
//   AGP 8.12.2 官方支持 compileSdk 36，需要 Gradle 8.13+ 与 JDK 17。
//   AGP 8.7.x 只支持到 compileSdk 35，会造成 “compileSdk 36 requires AGP 8.9.1+” 构建失败。
plugins {
    id("com.android.application") version "8.12.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}
