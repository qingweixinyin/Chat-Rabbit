# chatrabbit 交接与状态快照（HANDOVER）

> 最后更新：**2026-11-01 20:45**（服务端 **v1.7.3**：多玩具 + 免 MAC 本地云；1.7.0 TuringIP 通用重定向 → 1.7.1 媒体 URL 修复 → 1.7.2 按 MAC 派生设备身份 → 1.7.3 1883 重定向）
> 这份文件是**唯一权威的"当前状态"**。`REPORT.md` 是历史流水（含已被推翻的旧结论），`FW_ALARM_OPEN.md` 是固件未决项。
> **续聊第一件事：读本文件 + `../work/FW_ALARM_OPEN.md`。** 用户使用说明见 `../README.md`。

---

## 0. 一句话状态

玩具（杰理 AC791N，原厂固件，未刷机）已能被局域网内自建服务完全接管：
**闹钟增删改 / 每条闹钟独立铃声 / 点歌 / 音量 / 时间同步 / 6 个内置铃声**全部实测可用；
服务端跑在路由器插件里（koolcenter 标准包），App 为 Android 16（纯设备闹钟，手机不响）。

**2026-11-01：多玩具 + 免手工登记已完成并实测**（同 LAN 两台玩具同时在线，各自独立闹钟/铃声）。

### 0.1 多玩具 / 免 MAC 架构（v1.7.0–1.7.3，✅已验证）

| 版本 | 改动 | 原因 |
|---|---|---|
| 1.7.0 | 新增 `turing_ip`（默认 `198.18.0.1`）：dns-fix 把 `turingapi.com` 指到它，`chatrabbit_ctl.sh` 按**目的地址**做通用 REDIRECT（80/8883/10883→本机） | 原来按设备 MAC 写规则，新玩具加入必须手工 `addmac` |
| 1.7.1 | 设备侧媒体/铃声 URL 改 `http://198.18.0.1/media/...`（`mediaHostURL()`） | 用 `http://LANIP/...` 会打到 ASUS httpd（80），新玩具取不到 MP3 → 点歌没反应 |
| 1.7.2 | 设备身份按 MAC 派生：`deviceId=AI91+MAC`、`apiKey=md5(deviceId)`（`ensureDeviceForIP`），设备请求查不到即自动登记 | 新玩具请求落到"默认设备"，两台拿到同一 apiKey/deviceId → MQTT 同 clientId 互相顶号，后台只剩一台 |
| 1.7.3 | 1883 端口也纳入重定向（通用 + 按 MAC/IP） | 玩具把 MQTT endpoint 存 flash，换路由器后仍连旧地址（如 AX89X 的 `192.168.6.1:1883`）→ 静默失败、服务器无任何日志 |

排障顺序：`arp/leases`（在不在 LAN）→ `conntrack`（连的哪个地址）→ `iptables -t nat -L PREROUTING -v`（劫持是否命中）→ `serve.log` 的 `MQTT CONNECT`/`自动登记` → `/api/devices`。

限制：**一台服务器只管自己 LAN**；跨路由器需把玩具挪到同一 LAN，或等 App「设备级服务器映射」。同类固件（turing 配网 + `turingapi.com`）数量不设限。

---

## 1. 产物与版本

| 产物 | 版本 | 大小 | 路径 |
|---|---|---|---|
| 路由器插件包（**双架构** armv7+arm64） | **1.7.3** | 4.58 MB | `router\dist\chatrabbit-1.7.3.tar.gz`（另有 `-ax89x` 版，内含 **armv7** 2.47MB） |
| Android App（debug） | **1.10.6**（versionCode 20） | 11.34 MB | `dist\ChatRabbitAlarm-1.10.6-debug.apk` |

> 包名带版本号是**故意的**：早期用固定名 `chatrabbit.tar.gz` 反复上传旧包，导致"软件中心是新版 / 页面是旧版"的假故障。

## 2. 端口与网络（避开 kongke 与华硕自带服务）

| 服务 | 端口 | 说明 |
|---|---|---|
| 设备 HTTP（假图灵云） | **28081** | 设备硬编码 80；iptables 按设备 MAC（+源 IP 兜底）把 80/7890 重定向到这里 |
| 控制 API / 网页 | **8890**（v1.6.0 起） | `/api/*`、`/` 控制页；**同时尽力监听 8898 兼容旧 App**，被占用则跳过 |
| MQTT broker | **1883** | 设备订阅 `iot/<apiKey>/<deviceId>` |
| 备用 MQTT | **24056** | 设备若用 8883/10883 → 重定向到 1883 |
| NTP | **123** | 内建 SNTP（UTC 应答） |

### 2.1 与 kongke 插件的端口对照（2026-10-29 解包 kongke-2.3.5 得）

| 端口 | kongke 用途 | 我们 | 结论 |
|---|---|---|---|
| 8897 | kongke adminctl 面板 | — | 不冲突 |
| **8898** | **kongke 劫持面板（DNSCTL）** | v1.5.0 及以前是我们的 API | **冲突** → API 迁到 8890；8898 改成"尽力兼容监听"，bind 失败自动跳过 |
| 8899 | kongke 控制面板 | — | 不冲突 |
| 50003 / 5222 / 15001 / 15002 / 9123 | kongke 协议 / XMPP / tap / 同步 | — | 不冲突 |
| 8080 / 8081 | kongke HTTP 辅助 / 下载大师 | — | 不冲突（我们放弃了这些） |

> kongke 的透明劫持规则都带 `-s <插座IP>`，默认只劫 9123/50003，**不会碰玩具流量**。
> 若有人执行 `kongke_ctl.sh tap-on <玩具IP> all`，玩具出网 TCP 会被劫走 → 玩具离线。
> 排查：`iptables -t nat -L PREROUTING -n | grep REDIRECT`。

- DNS 劫持：`address=/turingapi.com/<LAN IP>`（`ctl.sh dns-fix`），只增 dnsmasq 条目，不改全局
- 设备 iptables 规则：80/7890 → 28081；8883/10883 → 1883；UDP/TCP 53 → 路由器；UDP 123 → 路由器 SNTP
- **LAN IP 必须由 `nvram get lan_ipaddr` 注入**（`CHATRABBIT_LANIP` 环境变量），否则程序会误取 WAN 口 IP

## 3. 构建 / 部署 / 校验（照抄即可）

```powershell
# 1) 编译（Go 1.19，静态）。v1.6.0 起出两个架构：armv7（老机型/兼容）+ arm64（AX89X 等 aarch64）
cd 'E:\Claude official\chat rabbit\router'
$GO = 'E:\Claude official\chat rabbit\work\tools\go\bin\go.exe'
powershell -ExecutionPolicy Bypass -File .\build.ps1 -GoExe $GO -Out 'dist\chatrabbit_armv7' -GOARCH arm   -GOARM 5
powershell -ExecutionPolicy Bypass -File .\build.ps1 -GoExe $GO -Out 'dist\chatrabbit_arm64' -GOARCH arm64

# 2) 部署（会 gzip 覆盖 bin/chatrabbit.gz + 删 raw + 重启 + 打印实际运行版本）
$env:ROUTER_PASS="Abcd1234"     # 另需 ROUTER_HOST（默认 192.168.6.1）
python deploy.py

# 3) 自检（关键：确认"真正跑起来的"是哪个版本/端口）
curl http://<LANIP>:8890/api/diag   # version / api_ports / sntp_ok / tz_hours / once_mode
```

打包（插件包）流程：`pkg\ksp\` → 复制进 `chatrabbit\` 顶层 → `tar -czf dist\chatrabbit-<ver>.tar.gz chatrabbit`。
包内 `bin/` 放 **两个** 二进制：`chatrabbit_armv7.gz` + `chatrabbit_arm64.gz`（install.sh 按 `uname -m` 选）。
**包结构要求（踩过的坑）**：顶层文件夹必须叫 `chatrabbit/`；`.valid` 写多平台标记
（`hnd`、`qca`、`arm384`、`arm386`、`arm388`，每行一个）；必须带 `res/icon-chatrabbit.png`（否则 404）；
`install.sh` 里**禁止出现** `detect_package`/`ks_tar_install` 字样。

## 4. 已验证功能矩阵（✅ = 有实测日志/人耳确认）

| 功能 | 状态 | 关键实现 |
|---|---|---|
| 设备上线（MQTT） | ✅ | 假 broker + `getMQTTConnectInfo` 返回 `tcp://LANIP:1883` |
| 闹钟整表下发 | ✅ | MQTT `optCode:1003`，`optValue.hb[]`，字段 `na/th/tm/sd/wk/wt/s` |
| 任意星期组合 | ✅ | `wk` 位掩码：bit0=周一…bit6=周日；63=每天；0=仅一次(+`sd`) |
| 每条闹钟独立铃声 | ✅ | 服务端 `rv` 字段（不下发设备），按"最接近当前时间的闹钟"选铃声 |
| 点歌 / 播放 / 停止 | ✅ | `type:1 operate:1|2`，`mediaId` 必须递增，URL 不带端口 |
| 音量设置 | ✅ | `type:2 operate:1 arg=N`，**N∈0..4**（>4 会让设备重启！） |
| 时间同步 | ✅ | `GET /api/ntp.form` 返回**纯文本 10 位秒**（毫秒会溢出成 2037） |
| 设备按键终止 → 服务端感知 | ⚠️部分 | 已解析 `status.play==0` / MQTT 上行 `"play":0` → 开闸门+补发停止；**该固件是否真上报待实测** |
| 上传音乐（网页/App 同一目录） | ✅ | `POST /api/upload?name=` / `/api/ringtone/upload?name=` → 保留原名、同名 `_1` |
| 删除音乐 | ✅ | `POST /api/file/delete {"name"}` |
| 内置 6 个铃声 | ✅ | 装机自动补齐（只补不覆盖）：classic/morning/dingdong/urgent/birds/chime |
| 插件安装（koolcenter） | ✅ | 图标、页面、dbus 注册、开机自启、`running binary version` 自检 |
| App：纯设备闹钟 | ✅ | 无 AlarmManager 调度、无本地响铃，只调 API |
| 每条闹钟独立铃声 | ✅ | 记录 `rv` 字段（不下发设备）；按"最接近当前时间的闹钟"选铃声 |
| 多设备（多台玩具） | ✅ | 每台独立闹钟/铃声/状态/MQTT；页面下拉切换；`?dev=` / `X-Device-Id` |
| 仅一次（自动星期位 + 自动清理） | ✅ | `once:true` + `wk=日期星期位` + janitor 30 分钟后删 |

## 5. 已验证快照（"修完多轮没再提"的修复，直接信任，不要重做）

| # | 修复 | 证据 | 验证轮次 |
|---|---|---|---|
| 1 | BE3600 是 aarch64，统一用 armv7 二进制 | `install log: running binary version 1.4.6 (expect 1.4.6)` | 3+ |
| 2 | LAN IP 取到 WAN 口 IP → 用 nvram 注入 | `/api/status ip=192.168.3.1` | 5+ |
| 3 | 插件 404 → 缺 `res/icon-*.png` + `.valid` 要 `hnd` | 安装成功："完成！离线安装插件成功" | 10+ |
| 4 | 播放不触发 → `mediaId` 必须递增 + URL 不带端口 | `MEDIA LOG: GET /media/__play_xxx.mp3` | 6+ |
| 5 | 幽灵播放（断线期间旧命令补发）→ 命令过期 120s 丢弃 | `DROP(expired)` | 3+ |
| 6 | 停止后仍被拉起 → 播放闸门（停止后 10s 不回、60s 内最多 3 次） | `[audio] 闸门拦截` / 空 payload | 2+ |
| 7 | 时间 2037/2070 → `time_mode=10` 纯文本秒 + `tz_hours=8` | 玩具时间正确 | 3+ |
| 8 | 专属铃声不生效 → 时区差 8h（路由器 UTC） | `[ring] 闹钟到点 … -> 专属铃声` | 2+ |
| 9 | 上传文件被改名 `custom_ring.mp3` → 保留原名+去重 | `/api/files` 出现原文件名 | 1 |
| 10 | 页面闹钟每行铃声下拉只有"默认" → 先 `loadFiles()` 再 `loadAlarms()` | 页面 7 个候选 | 1 |
| 11 | **仅一次闹钟不生效** → 设备把 `wk` 当星期位掩码，`wk=0` 表示"一天都没选 → 永不触发"。改为 `wk=目标日期的星期位`（周三→4、周四→8），并加 `once:true` + 到点 30 分钟后自动删表（否则下周同日会再响） | 实测 15:37 用 wk=0 不响；16:48 用 wk=4 正常响 | 2 |
| 12 | **多设备支持** → 全局单例收敛进 `Device`（`src/device.go`），每台独立闹钟表/铃声/状态/MQTT 会话/播放闸门；MQTT clientId 解析设备身份；HTTP 用 `?dev=`/`X-Device-Id`/源 IP 归属；旧 `dev_alarms.json` 自动迁移 | AC68U 真机 40+ 断言全绿：单设备回归 13 接口、双设备隔离、MQTT 模拟第二台上线、删除保护 | 1 |
| 13 | **`wt` 实验入口** → 页面添加表单 + 列表每行可改 `wt`，`POST /api/dev/wt` | 服务端已通；**语义待实测**（见 FW_ALARM_OPEN #1） | — |
| 18 | **HND 机型装错架构**：BE3600/AX89X 是 64 位内核 + **32 位 armhf 用户态**，`uname -m` 报 aarch64 但只能跑 ELF32 → 按 uname 选 arm64 会 exec 失败。改为读 `/bin/busybox` 的 ELF class（1=ELF32→armv7，2=ELF64→arm64），AX89X 专包改为 armv7 | 静态判定逻辑自测 3 分支 + AC68U 部署 1.6.6 正常 | 1 |
| 17 | **与 kongke 插件共存**：旧版 kongke 的劫持面板占用 8898，与我们兼容监听抢端口。v1.6.4 起检测 `/koolshare/kongke` 存在即跳过 8898；实测 AC68U 上 8890(chatrabbit)/8899(kongke 面板)/8900(kkctl) 三服务并存正常 | `[api] 检测到 kongke 插件：跳过 8898 兼容监听`；三端口同时 200 | 1 |
| 16 | **夜间掉线无法定位** → 新增健康看门狗：每 60s 查本机 dnsmasq（127.0.0.1:53）解析 turingapi.com 是否指向 LAN IP、每台设备在线/MQTT连接/累计CONNECT/在线翻转，写 `[health]` 行；状态变化写 `[health!]`；`GET /api/health?n=60` 返回当前+历史（保留 4h/240 条）；页面新增 HEALTH 按钮 | AC68U 实测：`[health] dns=ok(192.168.6.1,2ms) api=[8890 8898] up=1m15s devs=[AI91…:off]` | 1 |
| 15 | **玩具反复断网（IoT 掉线重连）** → syslog 证实：`dns-fix` 每次部署都写 dnsmasq.conf.add 并 `restart_dnsmasq`，DNS 秒级中断 → 玩具/插座 WiFi 自愈重连。改为幂等（配置未变不重启）；AC68U 实测连续两次 dns-fix 均输出「跳过重启」，功能回归 12 项全 PASS | 10-28 早上 9 次掉线均在 dnsmasq 重启后 32–115s；1.6.2 后 dnsmasq 不再被动重启 | 2 |
| 14 | **手机端闹钟列表只显示 2 条/操作按钮不可见** → 7 列表格在 390px 宽度被挤爆，wt/状态/操作被挤出屏幕。加 `@media(max-width:780px)` 卡片式布局 + 每格 `data-label`，实测 390px 下 5 条全部完整可见、按钮可点 | Playwright 390×1500 截图：改前右侧列被裁、改后全可见；版本 1.6.1 | 1 |

## 6. 坑位清单（每条都真实踩过，改代码前先扫一眼）

1. **别用 PowerShell 改 shell/ASP 源码**：`Set-Content -Encoding utf8` 会加 BOM（`#!/bin/sh: not found`），
   GBK 解码还会把中文注释后的换行吃掉（`BASE=` 变注释）。用 Edit/Write 工具。
2. **部署必须走 gz**：运行时是 `/tmp/chatrabbit.bin`（由 `bin/chatrabbit.gz` 解压）；只传 raw 会"部署了但没生效"。
3. **版本号单一来源**：二进制里 `const Version`、`pkg/ksp/version`、`install.sh VER=`、包名必须同步。
4. **路由器系统时区是 UTC**：任何"设备本地时间"比较都要加 `tz_hours`（默认 8）。
5. **设备音量只有 0~4**：发 8 会重启设备（已发生一次）。
6. **时间只能给 10 位秒**：13 位毫秒 → 32 位截断 → 2037 年。
7. **ARP 输出可能是 `? (192.168.3.106) at MAC`**：取 IP 要正则括号，否则 iptables 报 `host/network '?' not found`。
8. **插件页(80) 调 8898 是跨域**：API 必须处理 `OPTIONS` 预检，否则 `TypeError: Failed to fetch`。
9. **页面要 `Cache-Control: no-store`**：否则改了页面看不到（插件页由 Asus httpd 提供，需 Ctrl+F5）。
10. **日志按目录总量限 1MB**（不是单文件），超了删最旧、再超截断当前。
11. **`__play_*` 是播放别名符号链接**：文件列表要过滤，否则用户以为多了乱码文件。
12. **清空/添加别混用**：设备只有整表替换，本地表必须是"完整真源"，否则一推就抹掉设备原有闹钟。

## 7. 固件未决项（继续挖的入口）

见 `../work/FW_ALARM_OPEN.md`。最高优先：
1. **`wt` 字段语义**（很可能=铃声类型/响铃方式，能直接用设备内置铃声）
2. **唤醒键是否上报 `play=0`**（决定能否做"按键即停"）
3. **`optCode` 1001–1010 完整语义表**（跳转表 `0x2038876`，可能含单条增删/查询）

## 8. 续聊启动指令（把这段贴给下一个会话）

```
项目：E:\Claude official\chat rabbit
先读：router\HANDOVER.md（本文件）→ work\FW_ALARM_OPEN.md → work\REPORT.md 的开头"阅读须知"
产物：router\dist\chatrabbit-1.6.0.tar.gz（服务端，双架构）、dist\ChatRabbitAlarm-1.10.1-debug.apk（App）
硬规则：
1. 改 shell/ASP/Go 一律用 Edit/Write，禁止 PowerShell 改源码
2. 部署用 deploy.py（自动 gz + 自检版本）；确认 /api/diag 的 version
3. 结论标 ✅已验证/⚠️推测；推测不进结论，未解进 FW_ALARM_OPEN.md
4. 设备能力：只吃 MP3、音量0~4、时间只吃10位秒、闹钟整表1003、mediaId要递增、URL不带端口
5. 服务端时区=UTC，涉及"设备本地时间"必须用 tz_hours(8)
```

## 9. 上下文压缩约定（用户 2026-10-28 提出，已采纳）

1. **验证通过即入快照**：一个修复超过 3 轮没被推翻/复现 → 写进本文第 5 节，压缩后也不会丢。
2. **推翻要留痕**：旧结论不删除，加删除线 + 推翻位置（否则压缩后我会拿着旧错误结论重来）。
3. **未解只进 OPEN 文件**：逆向没看懂的，不许写成"未实现/无用"，放 `FW_ALARM_OPEN.md` 并写验证路径。
4. 每轮收尾回看 OPEN 文件，能推进的推进一项。
