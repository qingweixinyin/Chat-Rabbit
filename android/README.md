# 聊天兔闹钟（ChatRabbit Alarm）

原生 Android 控制 App（Kotlin + Jetpack Compose / Material3），用来管理家里的「聊天兔」玩具设备：

- **闹钟是纯设备闹钟**：App 把闹钟表同步到 **家里路由器上跑的自建 mock 云端**，由它下发给玩具，
  到点由**玩具自己**响铃 —— 手机不调度本地闹钟、不响铃、不通知、不亮屏（v1.7.0 起）；
- 编辑闹钟时可以直接选 **设备端铃声**（列 media 目录里已有的 MP3，或现场上传），铃声只给玩具播；
- 还能在 App 里做玩具的 **响铃测试**、设置 **响铃时长**、查看/替换 **设备当前铃声**、**点歌 / 播放 / 停止**、删除媒体文件。
  （聊天兔固件已裁掉音量/灯光控制，App 不再提供这两个入口。）
- 新玩具第一次联网可以用 **BLE 配网**：手机通过 BLE 把家里 WiFi 的 SSID / 密码直接写进玩具，不用拆机、不用串口。
- 路由器地址可修改 / 自动检测（换 WiFi、换网段后不用重装）。

> 目标平台：Android 16（API 36），同时兼容到 Android 8.0（API 26）。

---

## 1. 功能一览

| 页面 | 能力 |
| --- | --- |
| **闹钟列表**（主页） | 设备闹钟的**本机镜像**（时间/名称/重复规则/开关/**本条铃声**）；FAB 新建；开关切换（只切换设备端生效与否）；点击编辑；**点「铃声：xxx」可快速改本条铃声**；删除（带确认）；右上「⋮」菜单：刷新（从设备读取）/**同步到设备**（重新下发）、**清空设备闹钟**（二次确认） |
| **新建 / 编辑闹钟** | 系统 TimePickerDialog 选时间；名称输入；重复模式：**每天 / 指定星期（周一~周日多选）/ 仅一次（指定日期）**；**本条闹钟独立铃声**（跟随全局 / media 目录 MP3 / ＋上传新音频）；「启用（仅影响玩具设备）」开关 |
| **设备** | **连接信息卡**（路由器地址可点击修改 / device_online / clients / **device_ip** / last_connect + 「自动检测」「修改地址」「重新检测」）；离线时给出可操作提示；**音乐 / 点歌卡**（media 文件列表：播放 `POST /api/play`、设为默认 `POST /api/music`、删除 `POST /api/file/delete`、停止 `POST /api/stop`，显示 `music_file`）；响铃测试（`POST /api/ringtest`）；响铃时长 10/20/30/60 秒（`POST /api/ring`）；上传设备铃声（`POST /api/ringtone/upload?name=xxx`） |
| **配网** | BLE 扫描（优先显示广播带 `0xFFFF` 服务的设备，5 秒无结果自动退化为全量扫描）；选设备 → 填 WiFi SSID/密码 → 分片写入 FF01 → 解析 FF02 通知；实时进度日志、成功/失败原因；成功后自动轮询 `GET /api/status` 最多 60 秒确认上线 |
| **设置** | 路由器地址（支持**只填 IP**，自动补 `http://` 和 `:8890`；「验证并保存」会先请求 `GET /api/status`，失败给出超时/404/非 JSON 等具体原因且不写入）；闹钟行为说明；关于 |

**闹钟 = 纯设备闹钟**：本机不做任何调度（没有 AlarmManager、没有响铃服务、没有通知、不会亮屏）。
保存/开关/删除闹钟只做一件事：**增量**同步到设备（一条记录 + `wk` 位掩码，详见 §8.1 的「三条操作语义表」）。
路由器上的闹钟表是唯一真源：App 启动 / 进入列表 / 每次操作后都会 `GET /api/dev/list` 整表刷新本地镜像；
只有「清空设备闹钟」按钮会调用 `/api/dev/clear`；添加/编辑/删除**绝不** clear。

---

## 2. 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Android Studio | **Meerkat (2024.3.1) 或更新**（Ladybug/Narwhal 亦可，只要支持 AGP 8.12.2） | 低于 Meerkat 的版本可能不认识 AGP 8.12.x |
| JDK | **17**（必须） | Android Studio 自带 JBR 17 即可，`JAVA_HOME` 指向 JDK 17 |
| 编译 SDK | **Android SDK Platform 36（Android 16）** | SDK Manager → SDK Platforms → 勾选 "Android 16 (API 36)" |
| Build Tools | 35.0.0+（随 AGP 8.12 自动下载） | 无需手动安装 |
| Gradle | **8.13**（`gradle/wrapper/gradle-wrapper.properties` 已指定） | AGP 8.12.x 要求 Gradle 8.13+ |
| AGP | **8.12.2** | `build.gradle.kts`；官方支持 compileSdk 36 |
| Kotlin | **2.1.20** + `org.jetbrains.kotlin.plugin.compose` 2.1.20 | Compose 编译器随 Kotlin 独立发版 |
| Compose BOM | **2025.06.01**（Compose 1.8.x / Material3 1.3.x） | 与 Kotlin 2.1.20 的 compose 编译器兼容 |

其它关键依赖：`core-ktx 1.16.0`、`activity-compose 1.10.1`、`lifecycle 2.9.1`、`material-icons-core 1.7.8`（图标库已冻结，显式给版本）、`datastore-preferences 1.1.7`、`okhttp 4.12.0`、`kotlinx-coroutines-android 1.10.2`。

依赖刻意保持精简：**OkHttp + org.json**（不用 Retrofit / kotlinx-serialization）、**DataStore Preferences 存 JSON 字符串**（不用 Room），减少注解处理与版本冲突的风险。

另外：**BLE 配网要求手机支持蓝牙 BLE**（Android 8+ 机型基本都支持）；不支持的机型仍可正常使用闹钟与设备控制（清单里 `bluetooth_le` 是 `required="false"`）。

### 关于 compileSdk 36 与 AGP 版本

- **AGP 8.12.2 官方支持 compileSdk 36**，因此本工程**不需要** `android.suppressUnsupportedCompileSdk` 之类的开关。
- 反过来，如果把它降级到 AGP 8.7.x，会直接构建失败并报
  `compileSdk 36 requires Android Gradle Plugin 8.9.1 or newer`（AGP 8.7 的上限是 compileSdk 35）。
- AGP 8.12.x 要求 **Gradle 8.13+ / JDK 17 / Android Studio Meerkat (2024.3.1)+**，三者都满足即可一次构建通过。

---

## 3. 从 Android Studio 打开到安装到手机

### 3.1 准备（一次性）

1. 安装 **Android Studio Meerkat (2024.3.1) 或更新**（自带 JBR 17，`JAVA_HOME` 指向 JDK 17）；
2. **SDK Manager → SDK Platforms** → 勾选 **Android 16 (API 36)**；
3. **SDK Manager → SDK Tools** → 确认已装 *Android SDK Build-Tools 35+* 与 *Android SDK Platform-Tools*（含 adb）。

### 3.2 打开工程

1. **File → Open**，选择本目录（`chat rabbit/android/`，即 `settings.gradle.kts` 所在目录），信任工程。
2. 首次打开 Android Studio 会自动：
   - 生成 `local.properties`（写入 `sdk.dir=...`）——**这个文件不要提交**；
   - 按 `gradle/wrapper/gradle-wrapper.properties` 下载 Gradle 8.13；
   - 同步依赖（需要联网，仓库为 google() + mavenCentral()）。
3. 如果提示 “Gradle wrapper 缺少 jar”：
   - 本工程**故意没有提供 `gradle-wrapper.jar`**（二进制文件无法手写）；
   - Android Studio 打开带 wrapper.properties 的工程时会自动补齐；手动方式是在工程根目录执行一次（本机已装 Gradle 8.13 时）：

     ```powershell
     gradle wrapper --gradle-version 8.13
     ```

     或者：**File → Sync Project with Gradle Files**，IDE 也会补上。
4. `Build → Make Project`（或 `Ctrl+F9`）确认编译通过。

### 3.3 安装到手机

```powershell
# 1) 手机开启「开发者选项 → USB 调试」，USB 连接电脑，adb 能看到设备
adb devices

# 2) 方式 A：Android Studio 右上角设备下拉选择手机 → 点 Run ▶（自动编译 + 安装 + 启动）

# 3) 方式 B：命令行出 debug 包并安装
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk

# 4) 首次启动后按 §5 配置路由器地址、授予三项权限
```

---

## 4. 打包

```powershell
# Debug（可直接安装）
.\gradlew.bat assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk

# Release（未签名，需要自己的 keystore；本工程 release 默认未开混淆，便于直接出包）
.\gradlew.bat assembleRelease
# 产物：app\build\outputs\apk\release\app-release-unsigned.apk

# AAB（上架用）
.\gradlew.bat bundleRelease

# 安装到已连接设备
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

签名示例（自建 keystore）：

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore chatrabbit-release.jks `
  -alias chatrabbit -keyalg RSA -keysize 2048 -validity 10000

& "$env:JAVA_HOME\bin\apksigner.bat" sign --ks chatrabbit-release.jks `
  --out app-release-signed.apk app\build\outputs\apk\release\app-release-unsigned.apk
```

> 想开 R8：把 `app/build.gradle.kts` 里 release 的 `isMinifyEnabled` / `isShrinkResources` 改成 `true`，
> `app/proguard-rules.pro` 已经写好 OkHttp/Kotlin/四大组件/数据模型的保留规则。

---

## 5. 首次配置（重要）

### 5.1 路由器地址（可只填 IP）

1. 手机与路由器在同一局域网；
2. 打开 App → 底部 **设置** → 「IP 或完整地址」，三种写法都行：
   - 只填 IP：`192.168.3.1` → 自动补成 `http://192.168.3.1:8890`
   - 换端口：`192.168.3.1:9000` → `http://192.168.3.1:9000`
   - 完整地址：`http://192.168.3.1:8890`
3. 点 **验证并保存**：App 会先请求 `GET /api/status`，通过才写入设置；
   失败会给出具体原因（连接超时 / HTTP 404 / 响应不是 JSON / 不是聊天兔服务），**地址不会被改掉**。
   想跳过验证可以直接点 **直接保存**。

也可以用「设备」页顶部的 **自动检测**：依次探测 `192.168.6.1 / 192.168.3.1 / 192.168.1.1 / 192.168.31.1 / 192.168.0.1` 的 8890 端口（1.5 秒/个），
第一个可用的地址会自动保存并提示「已切换到 …」。**首次启动时 App 还会在后台自动跑一次**（只跑一次；仅当地址仍是默认值时才覆盖，不会冲掉你手填的地址）。

地址保存在 DataStore（`files/datastore/settings.preferences_pb`），升级 App 不会丢。

> 路由器是 `http://` 明文地址，Manifest 里已开启 `android:usesCleartextTraffic="true"`（targetSdk 28+ 默认禁止明文 HTTP），
> 这样内网 HTTP 接口才能访问。如果你的实现改成 https，可以把它关掉。

### 5.2 权限说明（v1.7.0 起大幅精简）

闹钟改为**纯设备闹钟**后，App 不再需要任何与本地响铃相关的权限。现在只保留：

| 权限 | 用途 |
| --- | --- |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 访问路由器 REST 接口 |
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`（Android 12+） | BLE 配网（`neverForLocation`） |
| `BLUETOOTH` / `BLUETOOTH_ADMIN`（≤ Android 11） | 旧系统上的 BLE 扫描开关 |
| `ACCESS_FINE_LOCATION`（≤ Android 11） | 旧系统上 BLE 扫描的系统要求 |
| `ACCESS_WIFI_STATE` | 配网页预填当前 WiFi SSID |

> 已移除：`SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`、`POST_NOTIFICATIONS`、`USE_FULL_SCREEN_INTENT`、
> `FOREGROUND_SERVICE*`、`WAKE_LOCK`、`VIBRATE`、`RECEIVE_BOOT_COMPLETED`，以及所有 Receiver / Service / 响铃 Activity。
> 所以不会再出现「通知权限 / 精确闹钟权限 / 全屏通知权限」相关的授权弹窗，也不会因后台限制漏响。

### 5.3 闹钟 = 纯设备闹钟（手机不响）

- **手机端不做任何事**：没有 AlarmManager 调度、没有前台服务、没有通知、不会亮屏；
  到点由**玩具自己**按设备端闹钟表响铃。
- **闹钟数据**：本机 DataStore 只是设备闹钟表的**镜像/缓存**，用于展示与编辑；保存/开关/删除都只是把变化增量同步到设备。
- **音铃**：v1.9.0 起**每条闹钟有独立铃声**（服务端 `ring`/`rv`）：编辑页可「跟随全局铃声」或单独选一个
  media 目录里的 MP3（含内置铃声音），保存时随 `add`/`upsert` 下发；列表页点「铃声：xxx」可快速改
  （`POST /api/dev/ring`，不重排、不动时间）。**闹钟响时**路由器按命中的那条闹钟选铃声，为空则用全局 `alarm_voice`。
  铃声只存在路由器侧，手机不播放；可选文件只显示 MP3，上传非 MP3 会提示「玩具只支持 MP3」。
- **验证**：设备页「让设备播放铃声」（`/api/ringtest`）可以立刻试听设备端铃声；`/api/files` 会列出 media 目录里的 mp3。
- 设备固件不支持远程音量/灯光控制（`/api/ctl` 实测无反应），所以 App 没有这两个入口。

### 5.4 第一次给玩具配网（BLE）

1. **让玩具进入配网模式**：断电重启。
   （若底部「配网」页一直搜不到设备，说明玩具还记得旧 WiFi —— 先长按复位键清除 WiFi 配置，或把它换到新网络后重启。）
2. 手机打开 **蓝牙**，App → 底部 **配网** → 首次会申请权限：
   - Android 12+：**附近的设备**（`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`）；
   - Android 11 及以下：**位置信息**（`ACCESS_FINE_LOCATION`，系统用它管控 BLE 扫描）。
   拒绝了会显示「授予权限 / 打开应用设置」两个按钮。
3. 等设备列表出现（带「配网服务」标记的置顶）→ 点选它。
4. 填 **WiFi 名称**（有定位权限时会自动填入当前手机连的 SSID，可改）和 **WiFi 密码**。
   > 玩具只支持 **2.4GHz** WiFi，5GHz 会连不上。
5. 点 **开始配网**，进度日志会依次显示：连接 → 发现服务 → 协商 MTU → 发送 SSID → 发送密码 → 发送 openid → 等待结果。
6. 成功：显示「配网成功」，App 会自动轮询 `GET /api/status` 最多 60 秒，确认后提示「设备已上线」；
   失败：会显示原因（设备报告 WiFi 连不上 / 等待结果超时 / 未发现配网服务等），可改密码后重试。

### 5.5 多网段 / 换 IP 后如何改地址

玩具换过 WiFi、路由器重启、或换了新路由器后，服务地址（默认 `192.168.6.1:8890`）可能变了，App 就会显示「设备离线」。
**App 只连路由器上的服务，不绑定玩具 IP**，所以只需要把地址改成新网段的网关：

1. 打开底部 **设备** 页，看最上面「连接信息」卡里的 **路由器地址**；
2. 点 **自动检测**：App 依次探测 `http://192.168.6.1:8890`、`192.168.3.1`、`192.168.1.1`、`192.168.31.1`、`192.168.0.1`（每个 1.5 秒，界面显示进度条），
   第一个返回合法 `status` 的地址会自动保存，并提示「已切换到 …」；
3. 自动检测也没找到，就点 **修改地址**（或直接点卡片里的「修改」）手动填：
   - 只填 IP：`192.168.3.1`（自动补 `http://` 和 `:8890`）
   - 或完整地址：`http://192.168.3.1:8890`
   点「保存并验证」，失败会显示超时 / HTTP 404 / 非 JSON 等具体原因，**不验证不写入**；
4. 保存后点 **重新检测**，确认「连接状态」变成 **在线**；
5. 如果你自己改了服务端口，把 `:8890` 换成实际端口即可。

> 换 WiFi 后玩具 IP 变了没关系——App 不直接连玩具；但路由器返回的 `device_ip` 是它记录的**最近一次连接**，可能滞后，仅作参考。
> 如果 `device_online=false` 但地址没错，问题在玩具侧：确认它开机、连的是 2.4GHz 的同一个 WiFi，必要时重新配网（§5.4）。

---

## 6. 工程结构

```
android/
├── settings.gradle.kts                  # 仓库 + 模块
├── build.gradle.kts                     # 插件版本（AGP 8.12.2 / Kotlin 2.1.20 / compose plugin）
├── gradle.properties                    # AndroidX、nonTransitiveRClass、JVM 参数
├── gradle/wrapper/gradle-wrapper.properties   # Gradle 8.13（无 jar，见 §3）
├── README.md
└── app/
    ├── build.gradle.kts                 # compileSdk/targetSdk 36, minSdk 26, Compose, 依赖
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml          # 权限（网络 + BLE）/ 仅一个 Activity / edge-to-edge 主题
        ├── java/com/chatrabbit/alarm/
        │   ├── ChatRabbitApp.kt         # Application：依赖容器 + 首次地址探测
        │   ├── MainActivity.kt          # enableEdgeToEdge() + Compose 根
        │   ├── data/
        │   │   ├── ApiResult.kt         # Ok / Err 封装
        │   │   ├── UrlUtils.kt          # base url 规范化 + 默认地址
        │   │   ├── AlarmModels.kt       # Alarm / RepeatMode / JSON 编解码 / wk 掩码
        │   │   ├── AlarmRepository.kt   # DataStore 存闹钟 JSON 列表（设备闹钟镜像）
        │   │   ├── ApiClient.kt         # OkHttp + org.json，路由器 REST 契约
        │   │   ├── DeviceModels.kt      # DeviceStatus / DeviceAlarm / operate 常量
        │   │   ├── DeviceRepository.kt  # 增量同步 / 铃声 / 点歌 / 文件删除
        │   │   ├── OkHttpProvider.kt    # 共享 OkHttpClient（请求 / 探测两个 client）
        │   │   ├── RouterDiscovery.kt   # 自动检测 + 地址校验（GET /api/status 探活）
        │   │   └── AppContainer.kt      # 手写依赖容器
        │   ├── alarm/
        │   │   └── AlarmTime.kt         # 时间文案 + 下次触发时间（仅用于展示/校验，不调度）
        │   ├── settings/
        │   │   └── SettingsRepository.kt # DataStore：路由器 base url
        │   ├── ble/
        │   │   ├── LeNetCfgProtocol.kt  # 图灵配网协议：帧构造 / CRC / 通知解析
        │   │   ├── BleProvisioner.kt    # 扫描 + GATT 状态机（Flow/StateFlow 回调化）
        │   │   ├── BlePermissions.kt    # Android 12+/11- 的 BLE 权限差异
        │   │   └── WifiInfo.kt          # 当前 WiFi SSID（预填用，取不到则手输）
        │   └── ui/
        │       ├── AppRoot.kt           # 单一 Scaffold + 底部四 Tab + Insets
        │       ├── theme/Theme.kt       # Material3 主题（Android 12+ 动态取色）
        │       ├── alarms/              # AlarmListScreen + AlarmListViewModel
        │       ├── edit/                # AlarmEditScreen（含设备铃声选择对话框）+ VM
        │       ├── device/              # DeviceScreen + DeviceViewModel
        │       ├── components/RouterAddressDialog.kt  # 「修改路由器地址」对话框
        │       ├── provision/           # ProvisionScreen + ProvisionViewModel（BLE 配网）
        │       ├── settings/            # SettingsScreen + SettingsViewModel
        │       └── util/                # 格式化、ScopedViewModelStore
        └── res/
            ├── values/ strings.xml colors.xml themes.xml
            ├── values-night/themes.xml
            ├── drawable/ ic_stat_alarm.xml ic_launcher_foreground.xml ic_tab_provision.xml
            ├── mipmap-anydpi-v26/ ic_launcher.xml ic_launcher_round.xml
            └── xml/ data_extraction_rules.xml backup_rules.xml
```

---

## 7. Android 16（API 36）合规要点

1. **强制 edge-to-edge**
   - Activity 调用 `enableEdgeToEdge()`；
   - 不使用 `statusBarColor` / `navigationBarColor` / `setSystemUiVisibility` 等旧 API（主题里也没有）；
   - 全局只有 `AppRoot` 里一个 `Scaffold`，`TopAppBar` / `NavigationBar` 自带 WindowInsets，内容统一用 `Modifier.padding(innerPadding)`；编辑页额外用 `imePadding()`。

2. **没有后台组件（v1.7.0）**
   - 只有一个 `MainActivity`；**没有** Receiver / Service / 响铃 Activity，清单里也不再有它们；
   - 因此不触发 Android 14+ 的前台服务类型、Android 12+ 精确闹钟、Android 13+ 通知、
     Android 14+ 全屏通知等限制 —— 这些权限与代码已全部移除，App 是纯粹的「配置 / 同步」工具。

3. **无废弃 API**
   - 不使用 `AlarmManager` 旧接口、`Notification.Builder`、`Vibrator`（旧）等；
   - 时间计算全部用 `java.time`（API 26 起内置），不用 `Calendar`。

4. **BLE 权限模型（配网）**
   - Android 12+（API 31+）：`BLUETOOTH_SCAN`（带 `neverForLocation`，不做位置推断，无需定位权限）+ `BLUETOOTH_CONNECT`，运行时申请；
   - Android 11 及以下：`BLUETOOTH` / `BLUETOOTH_ADMIN`（`maxSdkVersion=30`，安装即授予）+ `ACCESS_FINE_LOCATION`（运行时申请，系统用它管控 BLE 扫描）；
   - 声明 `<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />`，不支持 BLE 的手机仍可正常使用闹钟与控制功能；
   - 权限被永久拒绝时引导到 `ACTION_APPLICATION_DETAILS_SETTINGS`。

5. **明文 HTTP**
   - 路由器是 `http://` 内网地址，Manifest 开启 `android:usesCleartextTraffic="true"`；
     若改成 https，可换成 `networkSecurityConfig` 精确放行内网段。

---

## 8. 接口协议（路由器 REST + BLE 配网）

### 8.1 路由器 REST API

Base URL 可配置，默认 `http://192.168.6.1:8890`。所有响应都是 JSON，成功含 `"ok": true`。

**多设备（v1.5.0+）**：所有接口都支持 `?dev=<deviceId>` 或 `X-Device-Id: <deviceId>` 头；
不带时 = 服务端的「默认设备」（旧行为完全不变）。App 通过 `X-Device-Id` 头统一注入当前设备。

```http
GET  /api/devices
  → {"ok":true,"default":"AI91144031DD06A2",
     "devices":[{"id":"AI91144031DD06A2","name":"默认设备","api_key":"...","online":true,
                 "ip":"192.168.3.106","last_connect":"00:47:31","alarm_count":1,
                 "alarm_voice":"alarm_classic.mp3","music_file":"custom_ring.mp3"}]}
POST /api/devices/select {"id":"<deviceId>"}   # 只改服务端“默认设备”
GET  /api/dev/list?dev=AI9TEST00000002
POST /api/dev/add?dev=AI9TEST00000002   {...}
GET  /api/status?dev=...
POST /api/play?dev=... /api/stop?dev=...

GET  /api/dev/list
  → {"ok":true,"list":[{"na":"起床","th":7,"tm":0,"sd":1712345678,"wk":63,"wt":0,"s":1,
                        "rv":"alarm_classic.mp3","once":false}]}
     th=时 tm=分 sd=起始unix秒 wk=星期掩码(bit0=周一,63=每天) s=开关(1开)
     rv=这条闹钟**独立**的铃声文件名；空/缺省 = 跟随全局 alarm_voice（v1.9.0）
     once=服务端标记“仅一次”（v1.4.8+；仅一次的 wk = 该日期的单个星期位）

POST /api/dev/add
  # 重复闹钟：一条记录 + wk 位掩码（bit0=周一 … bit6=周日），路由器 v1.2.5+ 不再需要拆多条
  {"hour":7,"minute":30,"name":"起床","enabled":true,"wk":31,"ring":"alarm_classic.mp3"}
  {"hour":7,"minute":0,"name":"每天","enabled":true,"wk":63}      # 不带 ring = 跟随全局
  # 也接受 "days":[1,2,3,4,5]（1=周一…7=周日）；wk / days 优先于旧的 weekday 字段
  # 仅一次：只带 onceDate
  {"hour":8,"minute":15,"name":"一次性","enabled":true,"onceDate":"2026-10-28"}
  → {"ok":true}      # 服务端写一条 DevAlarm(WK=掩码, RV=ring)

POST /api/dev/upsert
  # 原子更新：match 用「旧名称 + 旧时 + 旧分」定位，rest 是新值（含 ring）
  {"hour":8,"minute":0,"name":"起床（改）","enabled":true,"wk":63,
   "ring":"alarm_birds.mp3","match":{"name":"起床","hour":7,"minute":0}}
  → {"ok":true,"replaced":true,"index":2,"total":3}

POST /api/dev/ring     {"index":2,"file":"alarm_chime.mp3"} → {"ok":true}
  # 只改某条已存在闹钟的铃声，不重排、不动时间；file 为空串 = 改回跟随全局

POST /api/dev/del      {"index":0}  → {"ok":true}
POST /api/dev/toggle   {"index":0}  → {"ok":true}
POST /api/dev/clear    {}           → {"ok":true}
POST /api/dev/push     {}           → {"ok":true}   重新下发给设备

POST /api/ringtest     {}              → {"ok":true}   让设备立刻播放当前铃声
POST /api/ring         {"seconds":20}  → {"ok":true}   设置响铃时长（1~120 秒）
POST /api/ringtone/upload?name=a.mp3   body=音频原始字节 (Content-Type: audio/mpeg)
                                       → {"ok":true,"file":"实际保存名","size":12345}
  # 路由器 v1.4.2+：保留原文件名（URL 编码，支持中文/空格/括号），同名自动加 _1 序号不覆盖，
  # 保存后自动设为全局 alarm_voice；失败时返回 {"ok":false,"err":"…"}，App 原样显示
POST /api/alarmvoice   {"file":"tone.mp3"} → {"ok":true}   设置**全局默认铃声**（协议兼容保留；App 现在用每条闹钟的 ring）
GET  /api/files        → {"files":["tone.mp3",...]}     media 目录内的 mp3 列表
POST /api/play         {"file":"xxx.mp3"} → {"ok":true}   点歌：让设备立刻播放
POST /api/stop         {}                 → {"ok":true}   停止播放
POST /api/music        {"file":"xxx.mp3"} → {"ok":true}   设为默认音乐（设备自动播放用）
POST /api/file/delete  {"name":"xxx.mp3"} → {"ok":true,"deleted":"xxx.mp3","freed":1100}
                                          失败 → {"ok":false,"error":"invalid name"|"not found"}
  # 文件名只放在 JSON body（中文 / 空格 / 括号无需 URL 编码，App 不会把它拼进 URL）
  # 删除的如果是设备当前铃声 / 默认音乐，服务端会分别把 alarm_voice / music_file 置空
GET  /api/status       → {"device_online":true,"clients":1,"alarm_voice":"custom_ring.mp3",
                          "ring_seconds":20,"device_ip":"192.168.3.106","last_connect":"17:20:31",
                          "music_file":"lullaby.mp3",...}
                          # device_ip / last_connect 是路由器记录的“最近一次玩具连接”，可能滞后
                          # App 的“自动检测 / 地址校验”标准：2xx + 合法 JSON + 含 ok 或 device_online

POST /api/ctl          {"operate":1}   → {"ok":true}   协议保留，但聊天兔固件未实现（实测无反应），App 不再调用
  operate: 0=音量减 1=音量加 2=关灯 3=开灯 7=查询状态
```

**本 App 的映射规则（v1.5.0：一条闹钟 = 一条设备记录；v1.9.3 起仅一次带星期位）**

| App 里的闹钟 | 发给设备的请求 |
| --- | --- |
| 每天 | `wk = 63` |
| 指定星期（多选） | `wk = 位掩码`：`31` = 周一~周五，`127` = 周一~周日，周六 = bit5，周日 = bit6 |
| 仅一次（指定日期） | `onceDate = "yyyy-MM-dd"` + **该日期对应的单个星期位**（周四 → `wk = 8`）<br>固件把 `wk` 当星期选择掩码，`wk=0` 表示一天都没选、**永不触发**，所以不能发 0 |

**三条操作语义表（v1.8.0：添加只添加、删除只删除、全部删除只清空）**

> 固件只有「整表替换」命令，所以**路由器上的闹钟表是唯一真源**；
> App 的本地 DataStore 只是它的镜像。App 启动 / 进入列表 / 每次操作后都会 `GET /api/dev/list` 整表刷新镜像。

| 操作 | 调什么接口 | 绝不做什么 |
| --- | --- | --- |
| **添加**（新建闹钟） | 只 `POST /api/dev/add {"hour","minute","name","enabled","wk"}`（单条追加） | ❌ 不 `clear`、❌ 不重发整表 |
| **编辑**（含启用/关闭开关） | 原子 `POST /api/dev/upsert { ...新字段..., "match":{"name","hour","minute"} }` → `{"ok":true,"replaced":true,"index":2,"total":3}` | ❌ 不再 del+add 两步（无短暂缺条窗口）、❌ 不 `clear` |
| **删除**（单条） | `POST /api/dev/del {"index": <最新一次 /api/dev/list 的下标>}` | ❌ 不 `clear`、❌ 不用本地下标 |
| **全部删除** | 仅 ⋮ →「清空设备闹钟」二次确认后 `POST /api/dev/clear` | —— 这是**唯一**允许 clear 的地方 |
| 每次操作收尾 | `POST /api/dev/push`（冗余一次无害）+ `GET /api/dev/list` 回读刷新 UI | —— |
| ⋮ →「刷新（从设备读取）」 | `GET /api/dev/list` 整表刷新镜像 | —— |
| ⋮ →「同步到设备（重新下发）」 | 只 `POST /api/dev/push`（不增删） | ❌ 不 clear |

**为什么以前“添加一条会把设备闹钟清空”**：旧实现只把本地 DataStore 里那几条按整表下发，设备侧原有的闹钟会被整表替换抹掉。
现在本地镜像 = 路由器全表，整表下发永远不会丢别人的闹钟。

> 一次性闹钟的 body 只带 `onceDate`；重复闹钟发 `wk` 掩码（`Alarm.toDeviceAlarmPayload()` 统一计算）。
> 若服务端后来又改字段（例如只认 `days` 数组），改 `ApiClient.buildAlarmBody()` 里对应分支即可。

**铃声选择流程（v1.9.0：每条闹钟独立铃声）**

```
编辑闹钟 / 列表页点「铃声：xxx」→ 打开同一套选择器
   ├─ 第一项「跟随全局铃声」          → ring = 空（用 /api/status.alarm_voice）
   ├─ GET /api/files                  → media 目录里的 MP3（含路由器内置铃声音）
   ├─ 「＋ 上传新音频到路由器」        → SAF(audio/mpeg) → 非 MP3 提示「玩具只支持 MP3」
   │                                    → POST /api/ringtone/upload?name=xxx.mp3
   │                                    → 响应里的 "file" 就是实际保存名（可能带 _1）
   │                                    → **立刻刷新候选列表并自动选中刚上传的文件**
   ├─ 编辑页选中 → 随「保存」走 add/upsert 的 "ring" 字段
   └─ 列表页选中 → POST /api/dev/ring {"index":n,"file":"xxx.mp3"}（不重排、不动时间）
```

`GET /api/dev/list` 的 `rv` 就是这条闹钟的铃声；App 列表里显示 `rv`（空则显示「默认」）。
**闹钟响时怎么选铃声**：路由器按「当前时间命中的那条闹钟」取其 `ring`/`rv`；
若为空则用全局 `alarm_voice`。铃声只存在路由器侧，不会下发给设备，手机也不播放。

### 8.2 BLE 配网协议（图灵 `apps/common/ble/le_net_cfg_turing.c`）

App 侧实现：`ble/LeNetCfgProtocol.kt`（帧构造 / CRC / 通知解析）+ `ble/BleProvisioner.kt`（扫描 → 连接 → 写帧状态机）。

**GATT**

| 项 | UUID / 属性 |
| --- | --- |
| Service | `0000FFFF-0000-1000-8000-00805F9B34FB` |
| Char FF01（写入） | `0000FF01-0000-1000-8000-00805F9B34FB`，WRITE_NO_RESPONSE |
| Char FF02（通知） | `0000FF02-0000-1000-8000-00805F9B34FB`，NOTIFY |
| Char 2A00 | 设备名（只读） |

**手机 → 设备（写 FF01，数据帧）**

```
[0]    0xA0 固定
[1]    类型：0x01=SSID 0x02=密码 0x03=openid
[2]    本次序号 seq（从 0 开始）
[3]    总序号 total（最后一帧的 seq；单帧时 seq=0,total=0）
[4]    本帧数据长度 len
[5..]  数据
[last] CRC = (0x100 - (前面所有字节之和 and 0xFF)) and 0xFF
```

- 开场帧（可选，清空设备端状态）：`B0 01 00 00 4F`
- openid 允许空数据帧：`A0 03 00 00 00 5D`
- 分片：`chunk = mtu - 3 - 6`（申请 MTU 247 → 每帧 238 字节；协商失败用 mtu=23 → 每帧 14 字节）
- 帧间隔：`WRITE_NO_RESPONSE` 没有 `onCharacteristicWrite` 回调，统一固定 **40ms** 串行发送
- 设备判定“该类数据收完”的条件是 `seq == 0 || seq == total`，三类数据都收完后才触发 WiFi 连接

**设备 → 手机（FF02 通知）**

| 收到内容 | 含义 | App 处理 |
| --- | --- | --- |
| `A0 01 00 00 5F` | 数据全部收到，开始连 WiFi | 写日志，继续等最终结果 |
| `A0 00 01 00 5F` | 要求重发 SSID | 重发 type=1 |
| `A0 00 02 00 5E` | 要求重发密码 | 重发 type=2 |
| `A0 00 03 00 5D` | 要求重发 openid | 重发 type=3（空数据帧） |
| `B0 ... buf[6]==0x50` | 配网失败（WiFi 连不上） | 结束并提示失败原因 |
| `B0 01 01 ...` | 成功，后续分片发 mac / api_key / deviceid | 结束并提示成功（忽略后续内容，仅日志） |

**实现细节（`BleProvisioner`）**

- 扫描：`ScanFilter.setServiceUuid(...)` + `SCAN_MODE_LOW_LATENCY`，15 秒自动停；5 秒内 0 个结果时自动退化为全量扫描。
- 连接：`connectGatt(context, false, callback, TRANSPORT_LE)`，等 `onConnectionStateChange` → `discoverServices()`。
- 通知：`setCharacteristicNotification(true)` + 写 CCCD `00002902-...`（API 33+ 用 `writeDescriptor(desc, value)`）。
- MTU：`requestMtu(247)`，3 秒超时或失败回落到 23。
- 写帧：API 33+ 用 `writeCharacteristic(char, bytes, WRITE_TYPE_NO_RESPONSE)`；
  低版本用 `characteristic.value + writeCharacteristic(char)`（`@Suppress("DEPRECATION")`，无替代 API）。
- 线程模型：所有 GATT 调用都在主线程串行；Binder 线程的通知回调写入 `Channel<DeviceEvent>`，由配网协程消费。

---

## 9. 关键实现点速查

- **纯设备闹钟（v1.7.0）**：本机**没有** AlarmManager 调度、没有 Receiver/Service/响铃 Activity/通知；
  DataStore 只是设备闹钟表的镜像，保存/开关/删除仅触发设备增量同步。到点由玩具自己响。
- **一次性闹钟的过去日期**：编辑页保存前用 `AlarmTime.nextTriggerMillis()` 试算（仅校验用），已过期直接提示，不会存进去。
- **设备铃声选择**：编辑页「选择设备铃声」→ `GET /api/files` 过滤出 MP3 + `GET /api/status.alarm_voice` 标「当前使用中」；
  点文件 `POST /api/alarmvoice {"file":…}`；「＋ 上传新音频」走 SAF（`audio/mpeg`）→ 非 MP3 直接提示「玩具只支持 MP3」
  → `POST /api/ringtone/upload?name=…` → 自动刷新列表。铃声只给玩具用，手机不播放。
- **响铃时长**：设备页的 10/20/30/60 秒按钮点一下即发 `POST /api/ring {"seconds":N}`，成功后同时刷新页面上的“设备当前”值（协议允许 1~120，App 侧做了钳制）。
- **设备铃声展示**：设备页显示 `alarm_voice`，并用 `GET /api/files` 列出 media 目录里的 mp3；上传时把 SAF 返回的 `DISPLAY_NAME` 作为 `?name=` 参数，让服务端用原文件名保存。
- **操作语义（v1.8.0）**：添加只 `devAdd`、编辑/开关只 `devUpsert`（match 旧名称+时+分）、删除只 `devDel{index}`（index 来自最新 `dev/list`）、
  清空只 `devClear`；每次变更后 `devPush` + `devList` 回读刷新本地镜像。本地 DataStore 永远由 `DeviceAlarm.toLocalAlarm()` 从路由器表生成。
- **点歌 / 音乐**：`POST /api/play|stop|music` 三个接口；`music_file` 来自 `/api/status`；播放/设默认期间该行显示进度、其它行按钮禁用（`musicActionFile`），删除复用 `/api/file/delete`，删掉默认音乐后服务端置空并自动刷新。
- **删除铃声 / 音乐文件**：`ApiClient.deleteFile()` 把文件名放进 JSON body（不拼 URL，中文/空格/括号天然安全）；`DeviceUiState.deletingName` 做「单飞」防重复点击 + 行内进度；成功后一次 `loadStatus()` 同时刷新 `files` 与 `alarm_voice`/`music_file`，失败原样展示后端 `error`。
- **地址自动检测**：`RouterDiscovery.detect()` 依次探测 5 个常见网段（1.5s/个，`Call.timeout()` 精确控制），首个「2xx + 合法 JSON + 含 ok/device_online」的地址自动写入 DataStore；「修改地址」与设置页「验证并保存」复用同一个 `probe()`，因此超时/404/非 JSON 的错误文案完全一致。
- **只填 IP 也能用**：`normalizeBaseUrl()` 统一补齐 `http://` 与 `:8890`（已有端口就保留），存储层只保存规范化后的完整 URL。
- **首次启动自动配置**：`ChatRabbitApp.onCreate` 里后台跑一次 `autoConfigureOnce()`（DataStore 标记只跑一次，且仅当地址仍为默认值时才覆盖，避免冲掉手填地址）。
- **音量 / 灯光**：聊天兔固件不支持，App 已移除相关按钮；`ApiClient.ctl()` 仅作为协议兼容保留。
- **BLE 配网串行写**：`WRITE_NO_RESPONSE` 没有写完成回调，所以每帧后固定 `delay(40)`，保证 238 字节/帧时也不会把 BLE 发送队列打爆；重发请求通过 `Channel` 排队，配网协程逐条处理。
- **BLE 配网诊断**：进度日志会打印每一步、MTU 值、每类数据的字节数/帧数，以及设备返回通知的原始 HEX，方便定位是“没进配网模式”还是“WiFi 连不上”。
- **并发/线程**：网络与 DataStore 全在 `Dispatchers.IO`；UI 通过 `StateFlow` + `collectAsStateWithLifecycle()` 订阅；BLE 的 GATT 调用串在主线程、回调写 Channel。

---

## 10. 常见问题

**Q: 到点手机完全没反应，是坏了吗？**
A: 不是。v1.7.0 起闹钟是**纯设备闹钟**：手机端不调度、不响铃、不发通知、不亮屏，到点由**玩具自己**响。
   手机没反应是预期行为。要让玩具响：① 设备页确认在线；② 闹钟已同步（列表页 ⋮ →「同步到设备」）；
   ③ 设备铃声已选（编辑页「选择设备铃声」）。

**Q: 玩具到点没响？**
A: 按顺序查：① 设备页「连接状态」是否在线；② 闹钟表是否已同步到设备（⋮ →「同步到设备」）；
   ③ 编辑页确认「启用（仅影响玩具设备）」是开启的、时间/重复规则正确；
   ④ 设备铃声是否已设置（未设置时用玩具默认铃声，可能是静音或很小声）；
   ⑤ 玩具是否开机、连的是同一 WiFi。

**Q: 设置页点「验证并保存」提示“网络请求失败 / 超时 / HTTP 404”？**
A: 手机不在该网段、路由器服务没启动、端口不对，或路由器防火墙拦了。可先点设备页的 **自动检测**；
   或用浏览器打开 `http://192.168.x.1:8890/api/status` 确认。错误文案会区分「连接超时」「HTTP 404」「响应不是 JSON」「不是聊天兔服务」，
   失败时**不会**写入设置，所以不会把能用的地址改坏。

**Q: 设备页显示“设备离线”，但路由器网页能打开？**
A: 说明 App 连的地址不是这台路由器（常见于换 WiFi / 换路由器后 IP 变了），或玩具没连上。
   → 设备页点 **自动检测**（1.5 秒/个，共 5 个常见网段）；找不到就点 **修改地址** 手填 IP（自动补 `http://` 和 `:8890`）。
   地址正确但仍 `device_online=false` 时，问题在玩具侧：确认开机、连的是 2.4GHz 的同一个 WiFi。详见 §5.5。

**Q: 点歌后设备没反应 / 提示播放失败？**
A: ① 先确认 `device_online=true`（不在线时指令会排在下发队列里）；② 文件必须已上传到设备 media 目录
   （`GET /api/files` 里能看到），且是设备能解码的格式（mp3）；③ `POST /api/play` 只下发播放指令，
   玩具正在响闹钟时可能排队。失败时 Snackbar 会显示路由器返回的 `error`。

**Q: 我点了「清空设备闹钟」，本机列表怎么还在？**
A: 本机列表只是设备闹钟表的**镜像**：「清空设备闹钟」只清路由器上的表（`/api/dev/clear`），
   不会删本机闹钟记录。之后编辑任意闹钟时，会以增量方式把对应条目重新 add 回设备。
   要把本机镜像也清掉，请在列表里逐条删除（每条都会同步删设备端条目）。

**Q: 铃声列表里看不到我上传的音乐？**
A: ① 只显示 **MP3**（玩具只支持 MP3，其它格式会被过滤/拒绝上传）；② 点「刷新列表」重新拉 `GET /api/files`；
   ③ 确认路由器 media 目录里确实有该文件（可在路由器网页上查看）。

**Q: 删除铃声提示 `not found` / `invalid name`？**
A: 这两个都是路由器返回的原始 `error`：
   - `not found`：文件已经不在了（可能刚在路由器网页/其它手机删过）→ 点「重新检测」刷新列表即可；
   - `invalid name`：文件名带了路径分隔符或非法字符，本 App 只传 `GET /api/files` 返回的原始文件名，正常不会出现；
     若出现，检查路由器 media 目录里是否有奇怪的符号名文件。
   删的如果是**设备当前铃声**，路由器会自动把 `alarm_voice` 置空，App 会刷新成「未设置」，
   需要重新选择并上传一个铃声。删除不可恢复，App 弹框里有二次确认。

**Q: 配网页一直搜不到玩具？**
A: ① 玩具没进配网模式 → 断电重启；② 玩具还记得旧 WiFi → 先长按复位键清除 WiFi 配置，或换到新网络后重启；
③ 蓝牙没开 / 权限没给（Android 12+ 是「附近的设备」，Android 11 及以下要定位权限）；④ 有些 ROM 需要打开「位置信息」总开关才能扫 BLE。

**Q: 配网失败 / 一直等到超时？**
A: 90% 是 WiFi 本身的问题：确认 SSID 是 **2.4GHz**（玩具不支持 5GHz）、密码正确、玩具离路由器别太远。
如果日志里能看到 `设备已收齐数据，开始连接 WiFi…` 但最后超时，说明数据已正确写入，问题在玩具连 AP；改密码或换 2.4GHz 后重试即可。
如果卡在「未发现 0xFFFF 配网服务」，说明连上的不是配网固件（可能是普通 BLE 设备），重新选带「配网服务」标记的那台。

**Q: 编译报 `compileSdk 36 requires ...`？**
A: 本工程用的是 AGP 8.12.2（官方支持 compileSdk 36）。如果手动降级 AGP，会报
`compileSdk 36 requires Android Gradle Plugin 8.9.1 or newer` —— 请保持 AGP ≥ 8.9.1，
推荐直接用工程里的 8.12.2 + Gradle 8.13（见 §2）。

**Q: 没有 `gradle-wrapper.jar` 报错？**
A: 见 §3.2 第 3 点，用 Android Studio 同步或本机 `gradle wrapper --gradle-version 8.13` 生成。
   注意 AGP 8.12.2 需要 Gradle 8.13+，不要用更老的 Gradle。

---

## 11. 版本
- **v1.10.1（versionCode 15）**：API 端口 8898 → **8890**（8898 被 kongke 插件劫持面板占用）。
  1. 默认 baseUrl 改为 `http://<路由器IP>:8890`；只填 IP 时自动补 `:8890`。
  2. 自动检测顺序：每个常见网段先探测 **8890**，再探测 **8898**（旧服务端兼容），命中哪个就把该端口 URL 存下来。
  3. 设置页显示**当前生效的完整 baseUrl**；若仍是旧端口 8898，显示提示「旧端口，建议升级服务端到 v1.6.0」。
  4. 所有请求逻辑不变（仍统一带 `X-Device-Id`）。

- **v1.10.0（versionCode 14）**：**多设备支持**（服务端 v1.5.0）。
  1. 新增 `DeviceInfo(id,name,online,ip,alarmCount,alarmVoice…)` 与 `ApiClient.devices()` → `GET /api/devices`。
  2. 当前设备持久化在 SettingsRepository（`currentDeviceId`，空 = 服务端默认设备）。
  3. **所有接口自动带当前设备**：`ApiClient` 统一注入 `X-Device-Id` 头（服务端所有接口都支持），
     status/dev/list/add/upsert/ring/del/play/stop/files/upload/… 无需逐个改。
  4. 闹钟列表页 / 设备页顶部新增**设备切换下拉**（显示「设备名（在线/离线）」），切换后立即刷新且 toast「已切换到 xxx」；
     设置页新增**设备管理**卡（全部设备 + 在线状态 + 闹钟条数 + 刷新列表 + 切换 + 恢复默认设备）。
  5. 兜底：`/api/devices` 404/失败/只有 1 台时**静默退化**，不显示切换器，行为与 v1.9.3 一致；
     当前设备被删除时自动回落到服务端默认设备。
- **v1.9.3（versionCode 13）**：仅一次改为「星期自动识别」（固件把 `wk` 当星期选择掩码，`wk=0` 永不触发）。
  1. **下发**：仅一次 = `onceDate` + **该日期对应的单个星期位**（`1 shl (date.dayOfWeek.value - 1)`，
     例 2026-10-29 周四 → `wk=8`）；不再发 `wk:0`。
  2. **回读校验放宽**：期望 `wk` == 该日期星期位，**或** `wk==0`（兼容旧服务端），或条目带 `once:true`；
     同时 `sd` 本地日期要等于选中日期；失败文案带「期望 wk=8（周四）实际 wk=xx」+ 设备表片段。
  3. **列表/编辑显示**：仅一次显示为 **「仅一次 · 2026-10-29（周四）」**，不会显示成「每周四」。
     `DeviceAlarm` 解析服务端 `once` 标记（v1.4.8+）。
- **v1.9.2（versionCode 12）**：修两个 bug（仅一次不生效 / 切页偶发网络错误）。
  1. **仅一次写入后回读校验**：`addAlarm`/`upsertAlarm` 成功后 `GET /api/dev/list` 找到刚写的那条，
     校验 `wk == 0` 且 `sd` 的本地日期 == 用户选的日期；不满足返回
     `仅一次未生效（设备返回 wk=xx sd=yy，期望日期 …）；设备表：…` 并写入 logcat。
  2. 一次性闹钟 body **同时发 `wk:0`**（服务端 `onceDate` 优先，兼容不同版本）。
  3. **后台刷新失败静默**：启动/进入列表/切页触发的 `/api/dev/list` 失败不弹提示；只有保存/删除/播放/上传等
     用户主动操作才提示错误。
  4. **网络请求 1 次重试**（网络类异常，间隔 300ms）；OkHttp 连接超时 5s→**8s**、读超时 15s→**20s**、
     `retryOnConnectionFailure(true)`。
  5. **切页刷新去重**：同一时刻只跑一个刷新（Repository 端 `Mutex` + VM 端在途 Job 判断）。
- **v1.9.1（versionCode 11）**：修复「上传音乐后列表刷不出来」+ 文案统一。
  1. 上传接口按路由器 v1.4.2 契约解析：`{"ok":true,"file":"实际保存名","size":N}`
     （保留原文件名、同名自动 `_1` 序号、上传后自动设为全局 `alarm_voice`）。
  2. 编辑页上传成功后**立刻刷新铃声候选列表并自动选中刚上传的文件**（用响应里的 `file`）；
     列表页快速改铃声对话框保持不提供上传。
  3. 上传失败时**原样显示服务端 `err` 原文**（`handleResponse` 增加 `err` 字段解析）。
  4. 文案统一为「上传到路由器」：设备页「选择音频并上传到路由器」、编辑/选择器「上传新音频到路由器」；
     设备页说明补充「和网页上传是同一个目录」。
- **v1.9.0（versionCode 10）**：**每条闹钟独立铃声**（路由器侧已支持 `ring`/`rv`）。
  1. `DeviceAlarm` 解析 `rv`；`Alarm` 新增 `ringFile`（本地镜像/JSON 兼容）；`buildAlarmBody` 带 `ring`，
     `add`/`upsert` 一起下发。
  2. 编辑页「本条闹钟的铃声」改为三选一：**跟随全局铃声**（ring 空）/ media 目录 MP3（含内置铃声音）/ ＋上传新音频。
  3. 列表页每条闹钟显示自己的铃声（`rv` 或「默认」）；点一下弹同一套选择器 → `POST /api/dev/ring {"index":n,"file":…}`
     （不重排、不动时间），改完回读 `dev/list` 刷新。
  4. 新增可复用 `AlarmRingPickerDialog`（编辑页与列表页共用）；README 增加 `rv`/`ring` 契约与「闹钟响时如何选铃声」。
- **v1.8.0（versionCode 9）**：**闹钟三个操作彻底分开：添加只添加、删除只删除、全部删除只清空。**
  1. **路由器闹钟表为唯一真源**：启动 / 进入列表 / 每次操作后 `GET /api/dev/list` 整表刷新本地镜像
     （`DeviceAlarm.toLocalAlarm()`，id 由「下标 + 内容指纹」派生）；新增 ⋮ →「刷新（从设备读取）」。
  2. **添加**：只 `POST /api/dev/add`（单条追加），**绝不** clear。
  3. **编辑 / 启用开关**：改用原子 `POST /api/dev/upsert`（`match` 用旧名称+旧时+旧分），不再 del+add。
  4. **删除**：只 `POST /api/dev/del {"index":…}`，index 来自最新一次 `dev/list`。
  5. **全部删除**：仅 ⋮ →「清空设备闹钟」二次确认 → `POST /api/dev/clear`，之后立刻回读刷新镜像（手机端列表同步清空）。
  6. 每次变更后 `devPush` + `devList` 回读，保证 UI 与设备侧一致；修复「添加一条会把设备原有闹钟清空」的问题。
- **v1.7.0（versionCode 8）**：**闹钟彻底与本地闹铃解耦，只对玩具设备生效。**
  1. **删除全部本地调度**：移除了 `AlarmScheduler` / `AlarmReceiver` / `AlarmRingService` / `AlarmActivity` /
     `BootReceiver` / `NotificationChannels` / `DeviceAlarmNotifier` / `RingScreen` / 权限工具，共 9 个文件；
     清单里不再有任何 Receiver / Service / 响铃 Activity，也移除了精确闹钟、通知、全屏通知、
     前台服务、唤醒锁、开机广播等 9 个权限。到点手机**不响铃、不通知、不亮屏**。
  2. **闹钟 = 设备闹钟镜像**：DataStore 仅用于展示与编辑，保存/开关/删除仍走 v1.5.0 的增量同步
     （一条记录 + `wk` 掩码 + `/api/dev/add|del|push|clear`）。
     编辑页启用开关改为「**启用（仅影响玩具设备）**」，副标题「关闭后设备端这条闹钟不生效；手机不会响铃」。
  3. **删除 v1.6.0 的「闹钟响铃方式」设置卡片与 `ringOnDeviceOnly` 相关代码**；设置页改为「路由器地址 + 闹钟行为说明 + 关于」。
  4. **铃声选择改为设备铃声选择**：编辑页「选择设备铃声」→ `GET /api/files` 列出 media 目录里的 MP3
     （标「当前使用中」）+ 第一项「＋ 上传新音频」→ 选中后 `POST /api/alarmvoice {"file":…}`；
     只显示 MP3，上传非 MP3 直接提示「玩具只支持 MP3」；铃声是给玩具播的，手机不播放。
- **v1.6.0（versionCode 7）**：闹钟不再让手机“自己播放”。
  新增设置项 **「闹钟只在玩具响（手机不播放）」**（默认开启）：开启时本地 AlarmManager 到点只做两件事 ——
  ① 发静音系统通知（新渠道 `alarm_device`，无声音无震动不弹全屏）② 调 `POST /api/play` 让玩具播放设备铃声
  （`alarm_voice` 为空时退回 `POST /api/ringtest`）；关闭时才恢复「前台服务 + 全屏 + MediaPlayer」。
  闹钟数据照常同步到设备（一条记录 + `wk` 掩码）；编辑页铃声卡片文案改为「铃声（给设备用的）」。**（v1.7.0 已整体删除）**
- **v1.5.0（versionCode 6）**：闹钟同步改为「**一条记录 + 星期位掩码**」——
  `POST /api/dev/add` 发 `wk`（bit0=周一…bit6=周日；每天 63、周一~周五 31），不再按 weekday 拆多条，
  修掉「选周一~周五保存一条，设备上出现 5 条」的问题；仅一次仍只发 `onceDate`。
  编辑仍按旧记录 index 倒序 del 后再发单条 add；旧版本拆出来的重复记录会在下次编辑时一并清掉。
- **v1.4.0（versionCode 5）**：
  1. **闹钟保存改为增量同步**（不再 clear 整表）：新增 → `POST /api/dev/add`；编辑/关闭/删除 → 先按「名称+时+分」
     定位旧条目 `POST /api/dev/del`（倒序），需要时再 add；最后 `POST /api/dev/push`。
     新增独立「**清空设备闹钟**」按钮（闹钟页 ⋮ 菜单 + 二次确认，唯一调用 `/api/dev/clear` 的地方，本机闹钟保留）。
     同步失败时本地编辑保留，提示「已保存到手机，同步设备失败」。
  2. **新增「音乐 / 点歌」卡片**：media 文件列表 + 播放（`POST /api/play`）/ 停止（`POST /api/stop`）/
     设为默认（`POST /api/music`）/ 删除（`POST /api/file/delete`），并显示 `/api/status` 的 `music_file`。
  3. **删除音频文件**沿用 v1.3.0 能力，入口移到音乐卡片，每行「播放 / 默认 / 删除」三个按钮。
- **v1.3.0（versionCode 4）**：设备页「设备铃声」卡片支持**删除 media 目录里的铃声文件**——
  每行右侧垃圾桶按钮 → 确认框「删除 xxx.mp3？删除后不可恢复」→ `POST /api/file/delete`（文件名只放 JSON body，中文/空格/括号无需编码）；
  删除中该行显示进度并禁用其它删除按钮（`deletingName` 防重复点击）；成功后刷新 `/api/files` 与 `/api/status`（删的是当前铃声则显示「未设置」），
  失败原样展示后端 `error` 文案。
- **v1.2.0（versionCode 3）**：不再绑死固定 IP —— 设备页新增「连接信息卡」（地址可点击修改 / device_online / clients / **device_ip** / last_connect），
  支持 **自动检测**（依次探测 5 个常见网段，1.5s/个）与 **修改地址**（对话框内校验后保存）；离线时给出可操作提示；
  地址输入支持**只填 IP**（自动补 `http://` 与 `:8898`）；设置页改为「验证并保存」（失败不写入并给出超时/404/非 JSON 具体原因）；
  首次启动后台自动检测一次；`DeviceStatus` 新增 `deviceIp` / `lastConnect`。
- **v1.1.0（versionCode 2）**：新增 **BLE 配网**（底部第 4 个 Tab）—— 图灵 `le_net_cfg_turing` 协议：
  BLE 扫描（0xFFFF 服务优先 / 全量兜底）、GATT 连接、MTU 协商、FF01 分片写帧（CRC / 40ms 串行）、
  FF02 通知解析（收齐 / 重发 / 成功 / 失败）、配网进度日志、成功后自动轮询 `/api/status`；
  新增 `BLUETOOTH_SCAN`（neverForLocation）/ `BLUETOOTH_CONNECT` / `ACCESS_FINE_LOCATION(≤30)` 权限申请与引导。
- v1.0.0（versionCode 1）：闹钟列表/新增/编辑/删除 + 三种重复模式（每天 / 指定星期 / 仅一次）+ 精确闹钟 + 前台响铃 + 全屏通知 + 开机恢复 + 闹钟表同步到设备 + 手机侧铃声选择 + 设备页（状态 / 响铃测试 / 响铃时长 / 铃声上传与列表）。
- 当前版本号在 App 内「设置 → 关于」页脚显示（由 `BuildConfig.VERSION_NAME` 生成）。
