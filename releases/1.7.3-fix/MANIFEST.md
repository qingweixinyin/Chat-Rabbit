# 发布产物 1.7.3-fix（服务端）+ 1.10.8（App）

**这是当前最新稳定版**（1.7.3-fix 为 2026-11-04 的在 1.7.3 基础上的修正包）。

归档日期：2026-11-06

## 路由器插件包

| 文件 | 版本 | 适用平台 | 大小 | md5 |
|---|---|---|---|---|
| `chatrabbit-1.7.3-fix.tar.gz` | 1.7.3-fix | 双架构（armv7 + arm64），`.valid` = `hnd` `qca` `arm384` `arm386` `arm388`；安装脚本按**用户态位数**自动选二进制 | 4.58 MB | `64f34b83598988392dfa81b93e032bb6` |

> 说明：AX89X（qca / 32 位用户态）**用同一个包即可**——`install.sh` 会按 `ELF32/ELF64 用户态` 自动挑 `chatrabbit_armv7.gz`，因此不再单独提供 `-ax89x` 单架构包。

安装：
1. 路由器后台 →「软件中心」→ 离线安装 → 选 `chatrabbit-1.7.3-fix.tar.gz`
2. 装完出现 **ChatRabbit** 图标，点进去是网页控制台（也可直接开 `http://<路由器IP>:8898/`）
3. 升级后建议重启服务：`/koolshare/scripts/chatrabbit_ctl.sh restart`

## Android App

| 文件 | 版本 | 包名 | 大小 | md5 |
|---|---|---|---|---|
| `ChatRabbitAlarm-1.10.8-debug.apk` | 1.10.8（versionCode 22） | `com.chatrabbit.alarm` | 11.34 MB | `9e5c8e94b2c75abed9cd7295be4fa136` |

安装：`adb install -r ChatRabbitAlarm-1.10.8-debug.apk`（debug 签名，可直接覆盖旧 debug 版）。

## 源码与构建

- `router/src`（Go 服务端）、`router/pkg`（安装脚本/铃声/网页）、`router/build.ps1`（交叉编译）
- `android/`（App 源码，Kotlin + Gradle）
- 仓库内 `router/src/main.go` 的 `const Version` 可能领先于本归档包（开发中版本），
  **以本目录归档的 `1.7.3-fix` 为最新稳定版**；不要拿开发中版本当稳定版发布。

## 变更要点（1.7.x）

- 1.7.0 `turing_ip` 通用重定向（`turingapi.com` → `198.18.0.1`，按目的地址劫持 80/8883/10883/1883）
- 1.7.1 设备侧媒体/铃声 URL 改为 `http://198.18.0.1/media/...`（否则新玩具取不到 MP3）
- 1.7.2 设备身份按 MAC 派生（`deviceId=AI91+MAC`、`apiKey=md5(deviceId)`），多台玩具不互相顶号
- 1.7.3 1883 端口纳入重定向（玩具把 MQTT endpoint 存 flash，换路由器后仍连旧地址）
- **1.7.3-fix**：在 1.7.3 基础上的修正包（最新稳定版）

## 免责声明

本项目仅用于自有设备的本地化研究与自托管；协议分析内容源自自有固件与自有设备抓包。
