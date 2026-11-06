# 发布产物 1.7.11（服务端）+ 1.10.8（App）

归档日期：2026-11-06

## 路由器插件包

| 文件 | 版本 | 适用平台 | 大小 | md5 |
|---|---|---|---|---|
| `chatrabbit-1.7.11.tar.gz` | 1.7.11 | 双架构（armv7 + arm64）：AC68U / BE3600 / AX 系列 | 4.58 MB | `02c887d7d7dec8f0d646e155ce6a6a6f` |
| `chatrabbit-1.7.11-ax89x.tar.gz` | 1.7.11 | RT-AX89X（仅 armv7） | 2.47 MB | `8db5ed844eb560fcc0be7e7b7a5a14b0` |

安装：
1. 路由器后台 →「软件中心」→ 离线安装 → 选择对应 `tar.gz`
2. 装完出现 **ChatRabbit** 图标，点进去是网页控制台（也可直接开 `http://<路由器IP>:8898/`）
3. 升级后建议重启服务：`/koolshare/scripts/chatrabbit_ctl.sh restart`（或软件中心里停/启）

## Android App

| 文件 | 版本 | 包名 | 大小 | md5 |
|---|---|---|---|---|
| `ChatRabbitAlarm-1.10.8-debug.apk` | 1.10.8（versionCode 22） | `com.chatrabbit.alarm` | 11.34 MB | `9e5c8e94b2c75abed9cd7295be4fa136` |

安装：`adb install -r ChatRabbitAlarm-1.10.8-debug.apk`（debug 签名，可直接覆盖旧 debug 版）。

## 版本说明

- 服务端源码内 `const Version = "1.7.12"`，本目录归档的是**已打包的最新发布包 1.7.11**；
  1.7.12 尚未出包，如需使用请按 `router/build.ps1` 自行编译打包。
- 源码与打包物料在同仓库：`router/src`（Go）、`router/pkg`（安装脚本/铃声/网页）、
  `router/build.ps1`（交叉编译）、`android/`（App 源码，Kotlin + Gradle）。

## 变更要点（相对 1.5.0）

- 1.7.0 `turing_ip` 通用重定向（`turingapi.com` → `198.18.0.1`，按目的地址劫持 80/8883/10883/1883）
- 1.7.1 设备侧媒体/铃声 URL 改为 `http://198.18.0.1/media/...`（否则新玩具取不到 MP3）
- 1.7.2 设备身份按 MAC 派生（`deviceId=AI91+MAC`、`apiKey=md5(deviceId)`），多台玩具不互相顶号
- 1.7.3 / 1.7.11 1883 重定向、多玩具稳定性与其它修复
- App 1.10.x：BLE 配网、多设备切换、点歌/铃声/提醒管理

## 免责声明

本项目仅用于自有设备的本地化研究与自托管；协议分析内容源自自有固件与自有设备抓包。
