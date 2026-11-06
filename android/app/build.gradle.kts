plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 起 Compose 编译器以独立 Gradle 插件形式提供
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.chatrabbit.alarm"
    // Android 16（API 36）：需要 AGP 8.9.1+，本工程用 8.12.2
    compileSdk = 36

    defaultConfig {
        applicationId = "com.chatrabbit.alarm"
        minSdk = 26
        targetSdk = 36
        versionCode = 22
        versionName = "1.10.8"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 为了“开箱即可编译/直接可装”默认关闭 R8；
            // 如需缩小体积，把下面两行改成 true 即可（proguard-rules.pro 已备好规则）。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.kotlin_module"
        }
    }

    lint {
        // 不因为 lint 警告而中断打包（release 仍会跑 lintVital，但不会让构建失败）
        abortOnError = false
    }
}

dependencies {
    // Compose BOM：2025.06.01 与 Kotlin 2.1.20 的 Compose 编译器插件兼容
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)

    // 核心 & 生命周期（支持 compileSdk / targetSdk 36 的版本）
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    // Compose (Material3)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // material-icons 系列已冻结在 1.7.8（不再随 BOM 更新），显式给版本最稳
    implementation("androidx.compose.material:material-icons-core:1.7.8")

    // 本地存储：DataStore Preferences（存闹钟 JSON 列表与路由器地址）
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // 网络：OkHttp + org.json（不引入 Retrofit / kotlinx-serialization）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
