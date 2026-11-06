# Chat Rabbit 固件逆向报告

> ## ⚠️ 阅读须知（2026-10-28 追加，先读这段）
>
> 本文件是**按时间累积的流水记录**，早期章节（§0–§33）里部分结论**已被后续实测推翻**。
> 阅读顺序：**先读 `HANDOVER.md` 的「已验证快照」+ `FW_ALARM_OPEN.md`（未决项），再看 §34 之后**。早期章节只当过程资料。
>
> 已知被推翻 / 已解决的关键项：
>
> | 早期结论（错误或过时） | 现在的事实（已验证） | 位置 |
> |---|---|---|
> | 「`operate=8` 固件未实现 → 闹钟确认无法实现 / 必须刷固件」 | **错误**。下行 `optCode 1003 + optValue.hb[]` 整表替换即可写设备闹钟，无需刷机 | §34 |
> | 「`wt` 字段未知，当占位忽略」 | **仍未解**，很可能是"铃声类型/响铃方式"，与每条闹钟自带铃声直接相关 → `FW_ALARM_OPEN.md` #1 | 待办 |
> | 「设备按键只能新增占位槽、无法删除」 | 设备本地确实不能编辑，但我们通过整表下发可完整增删改 | §34 |
> | 「点歌 / 闹铃播本机音乐做不到」 | **已实现**：`type:1 operate:1` + 无端口 URL + `mediaId` 递增；设备会 `GET /media/xxx.mp3` | §38 起 |
>
> **记录规则（防止再次自我误导）**
> 1. 新结论必须标 `✅已验证`（有日志/抓包/实测证据）或 `⚠️推测`；**推测禁止写成结论**。
> 2. 结论被推翻：**原文加删除线 + 注明推翻位置**，不要直接删（删了下次我会重复踩）。
> 3. 修复超过 3 轮没被推翻 → 视为"已验证通过"，写入 `HANDOVER.md` 快照表。
> 4. 未解问题一律进 `FW_ALARM_OPEN.md`，每轮收尾回看一次能不能推进。

分析对象：
- `chat rabbit\chat rabbit.bin`（8 MiB）
- `chat rabbit\chat rabbit-1.bin`（8 MiB）

分析方式：静态解析（未执行固件、未连接任何网络、未对设备做任何操作）。

---

## 0. 结论速览

| 问题 | 答案 |
|---|---|
| 两个固件有差别吗？ | **完全没有**。两个文件逐字节一致，SHA-256 相同，可判定为同一固件的重复下载/副本。 |
| 能提取网络连接接口吗？ | **能，部分**。取到 MQTT broker 域名、MQTT 主题/clientId 形态、微信绑定公众号 ID；HTTP/ASR/OTA 等 URL 位于**加密区**，未取到。 |
| 能提取登录信息吗？ | **能**。取到图灵 IOT 的 `apiKey`（32 hex）、`secretKey`（16 字符）、`deviceId`、设备 MAC，以及两枚云端下发的访问 token。 |

---

## 1. 两个文件的差异

| 项 | chat rabbit.bin | chat rabbit-1.bin |
|---|---|---|
| 大小 | 8 388 608 (0x800000) | 8 388 608 (0x800000) |
| SHA-256 | `5F7BFBE4803634879839DC84C63A4C4D7211FA8E96C6EC1A757B0A7FF84B41B8` | 完全相同 |
| 逐字节比较 | `da == db` → True | — |

差异字节数 = **0**。两份文件没有版本差、没有配置差；`-1` 后缀是浏览器/下载器重复落盘的典型表现。

---

## 2. 固件识别与布局

32 字节头部与杰理（JieLi）JLFS flash header 结构逐字节吻合（[JLFS 格式文档](https://github.com/kagaimiq/jielie/blob/main/datafmt/jlfs.md)）：

```
00: 6C D3        u16 hdr_crc
02: BF 1D        u16 burner_size
04: "1.00"       vid
08: F0 31 DC 67  疑似加扰（非 0x800000）
0C: DE AD FF 68  fs_ver/align/rsvd/special_opt
10: "AC79_PUBLIC" pid[16]  ← isd_config.ini 的 PID 字段
```

- 芯片方案：**杰理 AC79 / WL82（AC791N）WiFi+BT 二合一 AIoT SDK**（[官方 AC79 SDK](https://gitee.com/Jieli-Tech/fw-AC79_AIoT_SDK)）。
- `AC79_PUBLIC` 是烧录配置里的 **PID 字符串**，不是加密常量。
- 这是一份**整片 flash 读回镜像**（含运行期数据），不是纯 OTA 包。

镜像区块（按熵与 0xFF/0x00 填充划分）：

| 范围 | 大小 | 性质 |
|---|---|---|
| 0x000000–0x2CD000 | 2.75 MiB | 加密/高熵区 #1（代码 + 资源），熵 7.99–8.00 |
| 0x2CD000–0x3DB000 | 1.06 MiB | 擦除填 0xFF |
| 0x3DB000–0x6A0000 | 2.76 MiB | 加密/高熵区 #2（另一镜像或资源区） |
| 0x6A0000–0x7B3000 | 1.06 MiB | 擦除填 0xFF |
| **0x7B3000–0x7C0100** | **~50 KiB** | **明文运行期数据区（VM / 参数区）← 关键** |

明文区之后到 0x800000 全部 0xFF。

---

## 3. 明文区结构（0x7B3000 起）

- `0x7B3000`：VM 记录头 `55 AA AA 55 | 21 02 | 10 00 | 01 2E 07 80 09`，随后是 NUL 分隔的字段。
- `0x7B32C4` 起：WiFi 记录槽位，每条约 0x65 字节，共 10 条历史记录。
- `0x7BB000`：MAC 记录（`14:40:31:DD:06:A2`，6 字节原始形态）。
- `0x7BC000`：上述字段的**第二份副本**（VM 冗余写）。
- `0x7BD000` / `0x7BD800`：两枚 1120 字符的云端 token。
- `0x7BF000`：设备状态 JSON（闹钟 / 任务 / 上报缓存）。

---

## 4. 网络连接接口

| # | 接口 | 值 | 位置 | 说明 |
|---|---|---|---|---|
| 1 | MQTT broker | `mqtt.ai.turingapi.com` | 0x7B30A9 | 图灵机器人（turingapi.com）AI 集群 MQTT 域名 |
| 2 | MQTT 主题 | `iot/<apiKey>/<deviceId>` = `iot/69043ca347f34348b1e043c5429fef16/AI91144031DD06A2` | 0x7B316B | 下行指令主题 |
| 3 | MQTT 身份串 | `<apiKey>@<deviceId>` = `69043ca347f34348b1e043c5429fef16@AI91144031DD06A2` | 0x7B3123 | 与图灵 `clientId = GID_device@@@<apiKey>@<deviceId>` 的尾部完全同构 |
| 4 | 微信绑定入口 | `gh_ningju240506` | 0x7B3067 | 微信公众号原始 ID（家长端绑定/配网走公众号或小程序） |
| 5 | 端口/编号候选 | `40506` | 0x7B3050 | 明文数字串，可能是 MQTT 端口或产品/机器人编号（**未确证**，见 §7） |

对照图灵官方 IOT 文档（[设备接入](https://docs.turingos.cn/1.0/iot/device_doc/)），设备正常流程是 `POST https://iot-ai.turingapi.com/public/mqttManager/getMQTTConnectInfo` 换取 `account/password/topic/clientId/endpoint`；本固件把 **host + topic + clientId 模板直接落在 VM 里**，属于厂商自实现的直连方式。

---

## 5. 登录 / 认证信息

| 字段 | 值 | 位置 | 判定依据 |
|---|---|---|---|
| `apiKey` | `69043ca347f34348b1e043c5429fef16` | 0x7B300D（副本 0x7B30DA、0x7BC000） | 32 位小写 hex，与图灵 `apiKey` 定义一致 |
| `secretKey` | `ix6hy5975d228rrv` | 0x7B302E（副本 0x7BC021） | **恰好 16 个可打印 ASCII**，与图灵 `secretKey`（16 位，兼作 AES-CBC 的 key 和 IV）定义一致 |
| `deviceId` | `AI91144031DD06A2` | 0x7B303F（副本 0x7BC032） | 16 位字母数字、`AI` 开头，与图灵"deviceId 常以 ai 开头"一致 |
| MAC | `14:40:31:DD:06:A2` | 0x7B3098 / 0x7BB000 | ASCII 形态 0x7B3098；0x7BB000 为 6 字节原始形态 |
| 访问 token #1 | 见下 | 0x7BD000（1120 字符） | `base64url({"raw_access":"<256B>"})` + `.` + `<480B 签名/证明>` |
| 访问 token #2 | 见下 | 0x7BD800（1120 字符） | 同上，业务数据不同 |

token 结构（实测解码）：

```
seg0 = base64url( {"raw_access": "<256 字节不透明数据>"} )   → 359 B
seg1 = 480 字节二进制（前 48 字节两枚 token 完全相同，其余全不同）
```

推断：这是云端下发的**短期访问凭据 + 签名/证明**（`raw_access` 为 256B 会话密钥材料或密文）。用 `secretKey`、`apiKey` 的多种 AES-CBC/ECB 组合尝试解密均未得到明文（printable ≤ 0.41），说明其密钥不在本固件明文区，属于云端会话级。

> 敏感性提示：`apiKey + secretKey + deviceId` 三者组合即等价于该设备的"账号密码"，泄露后可被用来冒充设备接入图灵平台、订阅/下发该设备的主题消息。本文档仅供自有设备分析留存，请勿外传。

---

## 6. WiFi 凭据

| 项 | 值 |
|---|---|
| SSID | `Avalon-2.4GHz`（记录中可见 `Avalon` 与 `-2.4GHz` 两段，以 NUL 分隔；部分副本首字符被 0x00 覆盖） |
| 密码 | `Huxin20110127` |
| 位置 | 0x7B32C4 起 10 条记录，每条以 `57 B4/B5/8A B6 10 06` 之类 4 字节头 + 明文密码 + SSID 组成 |

即：设备配网时保存了手机热点（或路由器）的 2.4GHz SSID 与 PSK。

---

## 7. 未取到 / 存疑

| 项 | 状态 |
|---|---|
| HTTP API、ASR/TTS、OTA 升级 URL | **未取到**：位于 0x0–0x6A0000 的加密区（熵 8.0），无密钥无法解出 |
| `40506` 的真实含义 | 存疑。可能是 MQTT 端口（图灵常用 1883/8883，40506 属非标端口）、产品编号或机器人 ID；无代码侧符号可对照 |
| `6853`、`ad96f463`、`V200`、`aabbccdd` | 未确定语义（分别疑似型号/校验值/版本号/占位默认值） |
| token 内 `raw_access` 明文 | 未解出（密钥在云端） |
| 代码区加密算法 | 公开资料只覆盖旧系列（`key = chipkey ^ (addr>>2)` 的 32 字节块加扰）；AC79 新格式是否同法无公开定论，本镜像头部 `1.00`/`AC79_PUBLIC` 为明文而 0x20 起即密文 |

---

## 8. 复现步骤

```powershell
cd 'E:\Claude official\chat rabbit\work'
python struct2.py        # 结构、token、分区对比
python nv_detail.py      # NV 记录 / WiFi 槽位 / 状态 JSON
python wifi_layout.py    # WiFi 记录逐条字段
python token_dec2.py     # token 解密尝试（cryptography）
```

产出：`strings_ascii.tsv`、`strings_net.tsv`、`tail_ascii.txt`、`nv.txt`、`tok2.txt` 等。

---

## 9. 风险与建议

1. 该固件明文保存 **WiFi 密码**与**设备密钥**——拿到 flash 的人可直接读走，属于设计缺陷（应放 OTP/加密 VM）。
2. 若设备已连过家庭网络，`Huxin20110127` 即家庭/热点 WiFi 密码，建议评估是否需要改密。
3. `apiKey/secretKey` 泄露可冒充该设备，建议在厂商侧解绑/重置设备以轮换凭据。
4. 若要继续深挖代码区 URL，可行路径是动态侧：UART 日志、uboot 强升级模式读 flash、或在 1883 明文端口抓 MQTT 报文——都需要对自有设备操作。

---

## 11. 路由器端部署与协议实测（2026-10-22 下午）

### 11.1 路由器端已上线

单个静态 Go 二进制（`router/`），装到 `/koolshare/chatrabbit/`：

| 端口 | 作用 |
|---|---|
| `:8081` | 假图灵 HTTP + `/media/` 音频（设备硬编码连 80 → iptables 按设备 MAC 重定向到 8081） |
| `:1883` / `:40506` | 假 MQTT broker |
| `:8898` | 控制 API + **自带控制页** `http://192.168.6.1:8898/` |

- DNS：`dnsmasq.conf.add` 里 `address=/turingapi.com/192.168.6.1`
- 开机自启：`/koolshare/init.d/V99chatrabbit.sh`、`T99chatrabbit.sh`
- 设备已从电脑切到路由器（`getMQTTConnectInfo` → `tcp://192.168.6.1:1883`，`clients=1` 稳定）

### 11.2 编译踩坑（重要）

- 本机 Go 1.19：`E:\tools\go1.19\go\bin\go.exe`
- **必须 `GOARM=5`**：AC68U 的 BCM4708 内核没开 VFP，`GOARM=7` 的二进制一跑就 `Illegal instruction`（exit 132）
- 路由器 dropbear **没有 sftp-server**：部署要用 `cat > 文件` 走 SSH stdin，并用 md5 校验
- shell 脚本**不能带 UTF-8 BOM**（busybox sh 会把 BOM 当命令名）
- `:8899` 被 kongke 的面板占用 → 本服务改用 `:8898`，并让绑定失败时打日志

### 11.3 已确认可用的能力

| 能力 | 结论 |
|---|---|
| 音频播放 | ✅ WAV / MP3（实测 32KB~11.3MB）；**FLAC 不支持**（设备读 `fLaC` 魔数后主动断开，伪装 MIME/后缀也识破） |
| 转码 | ✅ `flac2mp3.py`（soundfile+lameenc，无需 ffmpeg） |
| 呼吸灯 / 低电提醒 | ✅ `type=2 operate=2/3/4/5`，设备会回 `/iot/status/notify` |
| 状态回读 | ✅ `POST /iot/status/notify`：`vol`(0-100 但设备只用 0-4 五档) / `battery` / `bln` / `lbi` / `play` / `charging` / `tcard` |
| 时间同步 | ✅ 设备会 `GET /time_now`，**必须返回秒**（返回毫秒 → 设备日期变 2070） |
| 音量 | ❌ 云控 `operate=0/1` 无效（`vol` 不变），只能物理键；设备 5 档 = 0振动/1静音/2音量1/3音量2/4音量3 |

### 11.4 闹钟（未攻克，记录所有已试格式）

设备 VM 里闹钟存储格式（从老 flash dump 解出，23 槽，**全部 `s:0` 关闭**）：

```json
{"hb":[{"na":"闹钟","th":10,"tm":10,"sd":1781884800,"wk":63,"wt":0,"s":0}, ...]}
```

- `na` 名字 / `th` 时 / `tm` 分 / `sd` 日期(秒) / `wk` 星期掩码(63=每天) / `wt` 未知 / `s` 状态
- 设备按键只能"新增"，不能选择/删除 → 槽位不断增长（现场已 29 条）

**已试过但都不生效的下发格式**（`type=2 operate=8`）：
1. 官方文档字段对象：`{id,deviceId,title,time(ms),repeat:"",status:1}`
2. 同上但 `arg` 为 JSON 字符串
3. 同上但 `time` 用秒
4. 数组字符串 `"[{...}]"`（**这次触发了设备语音"闹钟已全部删除"，但列表未变**）
5. `{"alarm":[...]}` / `{"list":[...]}` 包装
6. 所有值转字符串
7. `hb` 结构对象 / 字符串（`s:1`、`wk=63`/`0`）
8. 按 id 删除：`{"id":27,"status":0}` / `{"id":"27",...}` / `{"id":27}` / `arg=27`
9. 清空：`{"hb":[]}` / 一条空槽 / `arg` 为 `"{\"hb\":[]}"`

现象：设备对 `operate=2/3`（灯）0.4 秒内回状态，说明**消息确实被处理**；但 `operate=8` 既不回状态、也不改列表（只有一次语音提示）。

**下一步方向**：读当前 flash（只读）拿到 29 条真实内容，或从 App 侧抓一次真实下发（若还能拿到 APK/小程序包）。

### 11.5 闹钟最终结论（不可控）

在设备/路由器均在线、消息确凿送达（QoS1 + PUBACK）的前提下，试过 **12 类以上载荷**均无效：

- 官方字段对象/字符串：`{id,deviceId,title,time(ms/秒/"HH:mm"/分钟数),repeat(""/"1,2,3"/数组),status(1/"1")}`
- 数组字符串 `"[{...}]"`（唯一一次触发语音"闹钟已全部删除"，但列表未变）
- `{"alarm":[...]}` / `{"list":[...]}` / `arg` 为裸数字
- 按 id 删除：`{"id":27,"status":0}` / `{"id":"27",…}` / `{"id":27}` / `arg=27`
- 设备持久化结构 `{"hb":[{na,th,tm,sd,wk,wt,s}]}`（对象/字符串；`s:1`、`wk=63/0`）
- 清空形态：`{"hb":[]}`、单空槽、`"{\"hb\":[]}"`
- 前置 `type=3 operate=2`（绑定成功）后再发
- QoS0 与 QoS1 两种下发

对照实验：同一通道、同一时刻，`type=2 operate=2/3`（灯）**0.4 秒内**设备回 `POST /iot/status/notify`；`operate=8` 则完全无响应、无状态上报、无列表变化。

**结论：该固件（`AC791N_NJ_1V2_V2.0`）的 `operate=8` 闹钟写入未接入 MQTT 下发路径**（或使用了厂商私有编码）。设备按键只能"新增占位槽"，无法删除，因此现场累积到 29 条且全部 `s:0`（从未启用），这也是这些闹钟历史上从未响过的原因。

剩余唯一可行线索：解包**微信小程序/App**，看它构造闹钟请求的真实代码（需要用户提供 `.wxapkg` 或 APK）。

### 11.6 回滚方法（如需恢复原状）

```sh
# 1) 停服务（会自动删除 iptables 重定向规则）
/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh stop

# 2) 恢复 DNS（删掉劫持行或改回原值）
sed -i '/turingapi/d' /jffs/configs/dnsmasq.conf.add
service restart_dnsmasq

# 3) 移除开机自启
rm -f /koolshare/init.d/V99chatrabbit.sh /koolshare/init.d/T99chatrabbit.sh

# 4) （可选）彻底删除
rm -rf /koolshare/chatrabbit /koolshare/webs/Module_chatrabbit.asp
```

注意：回滚后设备会连不上已失效的官方云（保持现状即可，不影响本地按键/蓝牙配网）。

### 11.7 闹钟最后一轮尝试（全部失败，结论固化）

在 11.5 基础上又试了两条新通道：

| 通道 | 做法 | 结果 |
|---|---|---|
| **HTTP 响应回带指令** | 设备 `POST /iot/status/notify` 时，服务在响应体里回带 `{"type":2,"message":{"operate":8,"arg":{…}}}`（同时带 `payload`/`cmd`/`data` 三个键名） | 设备收到响应后**无任何反应** |
| **时间注入** | 新增 `/api/time` 运行时偏移旋钮，给设备"拨快 1 小时"后重启，观察闹钟时间是否跟着变 | 设备走 NTP（`s2*.time.edu.cn`，未劫持），`/time_now` 不影响其时钟；闹钟也不变 |
| **同载荷复现** | 重发曾触发"闹钟已全部删除"语音的数组字符串载荷 | 无语音、无变化 → 上次语音属偶然 |

**闹钟最终结论**：`operate=8` 在该固件（`AC791N_NJ_1V2_V2.0`）上无法通过 MQTT 下行或 HTTP 响应注入生效。设备本地只能"按键新增占位槽"，无删除/编辑能力。要突破只能拿到**真实云端的下发抓包**（手机侧抓包需云端可用，已不可行）或**解密固件代码区**（JLFS 加密，未破解）。

### 11.8 中文文件名播放修复

设备 HTTP 客户端不能处理未编码的中文/空格 URL。`media` 播放 URL 现在用 `url.PathEscape` 百分号编码：

```
修复前 http://192.168.6.1:8081/media/金刚萨埵心咒 静心安神 - 鬼谷天音阁.mp3   → 设备只回 /v2/iot/audio，不来下载
修复后 http://192.168.6.1:8081/media/%E9%87%91%E5%88%9A…mp3                    → 设备正常 GET 并播放
```

### 11.9 实验旋钮（保留，便于以后复现）

| 接口 | 作用 |
|---|---|
| `POST /api/time {"offset": 秒}` | 给设备的"当前时间"加偏移（实验用，默认 0） |
| `POST /api/httpcmd <JSON>` | 设置/清除状态响应里回带的指令（POST `null` 清除） |
| `POST /api/raw` | 直接下发任意 JSON（每行一条，按 gap 节流） |
| `GET /api/status` `GET /api/alarms` `GET /api/list` | 状态 / 本地闹钟记录 / 媒体列表 |

### 11.10 新发现：厂商自有域名（已停服）与 /api/v2

设备 DNS 查询记录里发现除图灵外的两个域名：

| 域名 | 真实解析 | 状态 |
|---|---|---|
| `chatrabbit.shenzhenbenwo.com`（厂商「深圳本我」） | 124.222.187.226（腾讯云） | **HTTP 80 可达，页面标题「抱歉，站点已暂停」；443 未开放** |
| `ws-api.turingapi.com` | 图灵 WebSocket API | 设备在闹铃时会查询它 |
| `easybox.iflyos.cn` | 讯飞 | 少见 |

厂商域名已加入劫持（`address=/shenzhenbenwo.com/192.168.6.1`），等设备下次轮询（之前观察到 13:57 / 14:05 / 14:54 / 15:08，间隔 10~50 分钟）即可抓到它的请求路径（HTTP 明文，可完整捕获）。

**闹铃触发的 `/api/v2` 实验**：

- 15:09/15:13 两次闹铃：设备在整点秒触发 `GET /api/v2`（无参数、无 UA），15:13 那次没有再请求（低频/一次性）
- 我们尝试在 `/api/v2` 响应里返回音频 URL（`payload{url}` 与顶层 `{url}` 两种）→ 设备**未下载音频**，仍只播放本地默认提示音
- 结论：`/api/v2` 更像"事件上报/心跳"，不是内容下发；闹铃音频无法通过它替换

**新能力（已验证）**：闹铃响起时可用 `type=1 operate=2`（停止播放）远程静音，设备会回 PUBACK 并停止。

**厂商后端已停服**是微信小程序无法登录的直接原因，也意味着原厂云不存在恢复可能。

### 11.11 决定性发现：真正的控制通道是 WebSocket

设备在**闹铃触发的那一刻**，会向 `ws-api.turingapi.com` 发起 **WebSocket 升级**，路径 `/api/v2`，随后发一帧文本报文：

```json
{"key":"69043ca347f34348b1e043c5429fef16",
 "timestamp":"1792654800240",
 "data":"<592 字节密文的 base64>"}
```

- 密文用 `secretKey` / `apiKey` / `md5` 系列共 30+ 种 key/IV 组合 + 严格 PKCS5 校验均**无法解开** → 请求方向是 RSA 或设备独有密钥
- 若服务器**不回复**（或回复格式不对）：设备闹铃**静音**（但表还走、闹钟仍"触发"）
- 我们已能：捕获该报文、以任意内容回复（明文 JSON / `{"data":AES(...)}` / 镜像信封）
- 试过的回复形态均未能让设备恢复发声：`{"code":0,"desc":"success"}` 明文、`{"code":0,"payload":{"url":...}}`、`{"data": AES(...)}`、`{"key","timestamp","data"}` 镜像信封、`data` 为纯 base64 明文

**结论**：闹钟的"设置/删除"极可能也走这条 WS 通道（服务器在连接上推下发），但**响应/指令的加密协议未知**，且请求方向解不开，目前无法伪造有效指令。

**已加的开关**（`/koolshare/chatrabbit/chatrabbit.conf`）：

| 配置 | 作用 |
|---|---|
| `ws_accept`（默认 `false`） | 是否接受设备 WS 升级。**默认拒绝 → 设备回退本地提示音（闹铃恢复正常响）**；做实验时用 `POST /api/wsaccept {"accept":true}` 打开 |
| `POST /api/wsauto <JSON>` | 设置收到设备 WS 帧后的自动回复明文（内部用 secretKey 加密后包成 `{"data":...}`） |
| `POST /api/ws <text>` | 立即向所有活动 WS 连接推送一帧文本 |

### 11.12 最终突破：闹铃内容完全可控 + 图灵云透明代理

#### 关键发现链

1. 设备闹铃时会向 `ws-api.turingapi.com` 发起 **WebSocket** 升级，路径 `/api/v2`
2. 发出的帧是 `{"key":apiKey,"timestamp":毫秒,"data":"<592 字节密文>"}`（强加密，无法解开）
3. 但**响应方向是明文 JSON**！用真服务器实测（图灵云仍在线）得到真实响应：

```json
{"code":210,"done":false,"message":"参数初始化成功"}
{"code":220,"done":false,"message":"参数上传完成，正在请求nlp/tts"}
{"code":200,"done":true,"message":"success",
 "nlpResponse":{"intent":{"code":50101},"results":[{"groupType":0,"resultType":"text",
   "values":{"ttsUrl":["http://turing-iot-tts-oss.turingapi.com/tts-cache/.../xxx.mp3"],
             "text":"闹钟时间已到"}}]}}
```

**闹铃流程真相**：闹铃响 → 设备连 WS 问云端"我该播什么" → 云端回 `ttsUrl` 音频链接（内容"闹钟时间已到"）→ 设备下载播放。厂商后端停服后无人回复，所以闹铃静音/只剩本地蜂鸣。

#### 已实现的两个能力

| 能力 | 接口 | 说明 |
|---|---|---|
| **自定义闹铃语音** | 页面「闹铃语音」/ `POST /api/alarmvoice {"file":"x.mp3"}` | 我们按图灵格式回 `ttsUrl` 指向路由器上的任意音频（音乐/人声 TTS）。**实测 16:13 播放了自定义语音 "闹钟时间到了，起床啦，现在是下午四点"** |
| **图灵云透明代理** | `POST /api/wsproxy {"enable":true}` | 设备↔路由器↔真实图灵云（`ws-api.turingapi.com`，解析走公共 DNS 绕开本机劫持）双向转发帧。**实测链路打通**，可让闹铃恢复官方 TTS，并为"语音对话复活"打基础 |

#### WS 相关 API 一览

| 接口 | 作用 |
|---|---|
| `POST /api/wsaccept {"accept":bool}` | 是否接受设备 WS 升级（false=拒绝→设备回退本地蜂鸣），持久化到 conf |
| `POST /api/alarmvoice {"file":"..."}` | 闹铃播放的音频文件（持久化） |
| `POST /api/wsproxy {"enable":bool}` | 开启/关闭图灵云代理（自动开启 ws_accept，持久化） |
| `POST /api/wsauto <text>` | 自定义 WS 明文回复；以 `PLAIN ` 前缀直接回原文，否则 AES(secretKey) 包成 `{"data":...}` |
| `POST /api/wsbin <file>` | 收到设备帧后推送二进制音频（实验用） |
| `POST /api/ws <text>` | 立即向所有活动 WS 连接推一帧 |

#### 结论

- **闹铃内容：已完全接管** ✅（可放任意音乐/语音）
- **原厂功能：可通过代理完整恢复** ✅（图灵云仍在运营）
- **闹钟增删**：仍未实现——设备本身不通过 WS 上报/接收闹钟列表，其闹钟是纯本地数据；原厂 App 是通过厂商后端（已停服）经 MQTT 下发的，而该固件的 `operate=8` 处理未生效。**这一项确认无法实现**。

---

## 12. 2026-10-22 晚间总结

### 12.1 已实现（全部在路由器上，持久化）

| 能力 | 接口/入口 |
|---|---|
| 音乐播放（中文名 OK） | 控制页 / `POST /api/play` |
| 灯 / 低电提醒 | `POST /api/cmd {"operate":2|3|4|5}` |
| 状态回读 | `GET /api/status` |
| 闹铃**播放自定义音频** | 页面「闹铃语音」/ `POST /api/alarmvoice` |
| 闹铃**原厂语音**（图灵云代理） | 页面「原厂模式」/ `POST /api/wsproxy {"enable":true}` |
| 闹铃默认提示音 | `POST /api/wsaccept {"accept":false}` |
| 远程静音 | `POST /api/stop`（type=1 operate=2） |

闹铃播放链路（已实测成功）：设备闹铃 → WS `/api/v2` 发加密请求 → 服务端回**明文 JSON**，其中 `nlpResponse.results[0].values.ttsUrl` 指向音频 → 设备下载播放。

### 12.2 关键修复（踩坑记录）

1. **iptables 劫持范围**：最初"设备所有 80 端口流量"会劫走图灵语音 CDN（导致闹铃无声），已改为 `-d <路由器IP> -m mac <设备MAC> --dport 80`。两种流量都必须排除，否则 ①路由器管理页打不开 ②图灵 CDN 音频取不到。
2. **DNS 只劫持设备端点**：`iot-ai / ws-api / mqtt.ai .turingapi.com`，**不能**劫持整个 `turingapi.com`，否则 `turing-iot-tts-oss.turingapi.com`（语音 CDN）也被劫持。
3. 路由器重启后：iptables 由 `chatrabbit_ctl.sh start` 重建、dnsmasq 配置在 `/jffs` 持久化、init.d 钩子自动拉起服务——实测全部正常。

### 12.3 今日未攻克（闹钟增删）与排除的方向

| 方向 | 结论 |
|---|---|
| MQTT `operate=8` 各种格式 | ❌ 明文/加密(AES secretKey)/数组/对象/字符串/hb 结构/按 id 删除/整表 29~30 槽 —— 全部无反应 |
| MQTT `operate=7` 工厂重置 | ❌ 无反应；operate 6~24 全扫描也没有触发重置 |
| HTTP 响应回带指令 | ❌ 无反应 |
| `GET /api/v2`（HTTP）返回音频/跳转 | ❌ 设备不采纳 |
| WS 二进制帧推音频 | ❌ 无反应 |
| **BLE 配网通道** | ❌ 仅 2 个服务（0x1800、0xFFFF），FF01 只因配网三字段(SSID/密码/openid)响应，其它类型被无视并断开；正常模式不广播 |
| 时间操纵（NTP 屏蔽 + 时间拨到 2027） | ❌ 设备走硬编码 IP 的 NTP，屏蔽 DNS 与 UDP/123 均无效；`/time_now` 不是其时钟来源 |
| 厂商后端 | 已停服（所有 API 404，仅"站点已暂停"页） |
| 微信小程序 | 登录依赖厂商后端 token，无法恢复 |

### 12.4 闹钟记录现状与计划

- 设备本地已有 **50 条**闹钟记录（按键只增不减），上限未知
- **明天计划（路径 A）**：拆机 + 编程器读 flash（只读）→ 解析 VM 区真实闹钟 JSON → 生成清空版镜像 → 写回 VM 区 → 验证
- **清理后关键验证**：重新尝试 MQTT `operate=8` 创建闹钟（之前怀疑"表满导致拒绝"）
- 闹钟存储格式（已在旧 dump 中确认）：

```json
{"hb":[{"na":"闹钟","th":8,"tm":30,"sd":1781884800,"wk":63,"wt":0,"s":1}, ...]}
```

---

## 13. 2026-10-23 进展（闹钟清空、芯片库扩充、远程增删结论）

### 13.1 闹钟记录已清空（意外达成）

写入"未擦除"导致的记录损坏（新旧数据按位与）后，设备开机**自己重写默认设置**：

- 闹钟记录 **50 → 0 条** ✅
- Wi-Fi 配置保留（自动重连路由器）
- 电量/音量/联网/音乐/闹铃功能正常

**结论**：设备固件在设置 JSON 解析失败时会重建默认记录。这为"清空闹钟"提供了一条免擦除路径（只需把记录首字节改坏）。

### 13.2 XTW2PRO 编程器支持 Puya P25Q64H（并扩充 137 个型号）

- 芯片：**Puya P25Q64H**，8MB SPI NOR，JEDEC `85 60 17`
- XTW2 芯片库为二进制 `XTW2.Dat`，849 条 × 68 字节，记录格式：

```
[0..48)  "SPI_FLASH,厂商,型号"
[48]     容量码（bytes = 1 << 容量码）
[49]     类型码    [50] 厂商码
[52..56) 容量字节数 LE32
[57..59) 页大小 0x0100   [60..62) 延时 1000
```

- 已新增 `SPI_FLASH,PUYA,P25Q64H`（85 60 17）与 `P25Q64H2`（85 60 18）
- 又从 flashrom 官方芯片表批量导入 **137 个型号**（MXIC/ATMEL/EON/ISSI/XMC/BOYA 等 41 厂商），数据库 851 → 988 条
- 备份：`XTW2.Dat.bak-20261023-092329`、`XTW2.Dat.bak-20261023-093229`
- 工具脚本：`work/add_puya_chip.py`、`work/import_flashchips.py`

### 13.3 远程增删闹钟：最终结论

| 途径 | 结果 |
|---|---|
| MQTT `operate=8`（明文/AES/各种结构） | ❌ 空表状态下依然无效 → 固件未实现该指令 |
| 设备自愈重写 VM | ⚠️ 只能"清空"，不能"新增"，且需要物理写坏记录 |
| OTA 升级下发镜像 | ⚠️ 未验证：设备 OTA 请求为 `{apiKey,deviceId,currentVersion}`，响应需 `version/url/md5/force`；**镜像格式与校验未知**，盲试有变砖风险 |
| BLE 配网通道 | ❌ 无闹钟/文件读写能力 |

**结论**：闹钟只能通过设备按键添加；远程增删不可行。若需批量配置，只能通过 flash 写入 VM 区（需要拆焊或 SOIC 夹具）。

### 13.4 flash 写入前置条件（重要）

- 写入前**必须擦除**（本次写入失败即因未擦除，Flash 只能 1→0）
- 擦除需要：正确芯片型号（Puya 已支持）+ 足够供电（建议外部 3.3V）
- 若要频繁改动 VM，建议焊一次 **SOIC-8 测试夹/排针**，后续可免拆焊直接夹取

---

## 14. 2026-10-23 下午：固件差分、真实 broker 探测、通道终局

### 14.1 两次 dump 全片差分（chat rabbit.bin ↔ chat rabbit-puya.bin）

- 大小均 8,388,608；差异 **10,249 字节 / 55 个区间**
- **固件代码区（0x0-0x7B3000，7.7MB）逐字节完全相同** → 代码没有变化，只有状态变化
- 差异只在：闹钟记录区 7,978 B、VM/配置区 2,271 B

### 14.2 闹钟记录结构（关键结论）

```
地址      0x7BF000（固定）
内容      NUL 结尾的 JSON，无任何 CRC/长度头
尾部      JSON + 0x00，再补 64 字节 0x00，其后全 0xFF（写入前已擦除）
旧(23条)  4,224 字节
新(1条)   377 字节
```

**没有任何校验字段** → 之前生成的 `chat rabbit-2alarms-flash.bin` 格式是正确的，
只差"能擦写的编程器"。设备上次清空闹钟也是因为 JSON 非法触发重建，与 CRC 无关。

### 14.3 VM / 配置区结构

- 起始 `0x7B3000`，魔数 `55 AA AA 55`，其后为键值/字符串混合记录
- 保存内容：apiKey、secretKey、deviceId、端口(40506)、`gh_ningju240506`、`V200`、`6853`、
  `aabbccdd`、MQTT 账号/密码、clientId、topic、openid、WiFi 扫描列表、两条 `raw_access` 令牌（各 1KB）
- 旧 dump 中该区保存的是 `mqtt.ai.turingapi.com` + 密码 `ad96f463`；
  新 dump 已被我们的 mock 覆盖为 `192.168.6.1` + `Signature|local|fake`
  → **证明设备会把云端下发的服务器地址/账号持久化到 flash**
- 配置区在约 `0x7BBFF3` 处存在一份镜像副本

### 14.4 真实图灵/厂商 broker（47.93.166.55:10883）

- 明文 MQTT，设备 CONNECT 参数（抓包获得）：
  `clientId=169043ca...@AI91144031DD06A2 user=69043ca... pass=ad96f463`
- 用该凭据可从 PC 连上（rc=0），订阅设备主题无任何保留消息/下行
- 通过真实 broker 向设备主题 PUBLISH（type=3 operate=0 / 闹钟格式）：设备**未收到**
  （路由器中继日志只出现 PINGREQ/PINGRESP）→ **broker 有 ACL，第三方发布被丢弃**

### 14.5 小程序不可用的根因

```
chatrabbit.shenzhenbenwo.com 证书 NotAfter = 2026-02-15（已过期）
域名注册至 2027-09-16（无法抢注）
```
小程序 TLS 握手即失败，无法登录/配对；手机无法安装用户 CA（S25U 无 root），
PC 侧证书也不受控 → 小程序通道永久不可用。

### 14.6 BLE 完整枚举（配网模式）

```
服务 0x1800 (GAP)       仅设备名
服务 0xFFFF (厂商配网)  FF01 写 / FF02 通知
```
无 RCSP、无其它服务，只支持 SSID/密码/openid 三种帧，不承载闹钟。

### 14.7 固件加密现状

- 高熵区（0x0-0x2CC000、0x400000-0x600000、0x600000-0x7B3000）确认加密/压缩
- 无 zstd/lz4/gzip/xz/PNG 等任何标准头；lz4 帧头爆破 0 命中
- 无 A/B 双分区（差分匹配 0.5%）
- 头部候选密钥/IV × AES-ECB/CBC × 多偏移全部失败
- `JLFS` 明文出现在 0xF3D9F（加密区内部），疑似明文文件系统头 / 加密块中的残留
- 官方 SDK（fw-AC79_AIoT_SDK）与文档确认 UFW 格式存在"加密偏移/长度"字段，但未公开算法与密钥
- **结论：按镜像/芯片加密，密钥不在镜像内；软件层无法再推进，需 SWD/UART 硬件调试**

### 14.8 通道总表（终局）

| 通道 | 可达性 | 能否写闹钟 |
|---|---|---|
| 我们的 mock MQTT | ✅ | ❌ 固件不处理 operate=8 |
| 真实 broker 10883 | 连接✅ / 下发❌(ACL) | ❌ |
| HTTP（状态/OTA/时间） | ✅ 我们控制 | 仅时间/状态 |
| WS（闹铃 TTS） | ✅ | 仅语音 |
| BLE 配网 | ✅ | ❌ 仅配网 |
| 厂商后端/小程序 | ❌ 证书过期 | ❌ |
| flash 写入 | 需可擦写的编程器 | ✅（格式已验证无 CRC） |
| SWD/UART | 需硬件 | 待验证 |

---

## 15. 2026-10-23 晚：固件加密破解（重大突破）

### 15.1 关键资料来源

GitHub 杰理逆向知识库 **kagaimiq/jielie**（JLFS/加密/ISP 文档）：

- `periph/enc.md`：flash 加密 = **16 位 LFSR 流密码**（CRC16-CCITT 式扰码，非 AES）
- `periph/sfc.md`：数据按 **32 字节块**扰乱，密钥与地址相关（`addr >> 2`），16 位密钥每 256KB 回绕
- `datafmt/jlfs.md`：JLFS2 文件系统结构（flash header + 32 字节文件条目 + CRC16）
- `datafmt/bankcb.md`：uboot/app 数据的 BANKCB 容器 + LZ4 压缩（可带 BootROM 字典）

### 15.2 实测结论（已验证）

1. 固件镜像**部分明文、部分加密**：
   - `isd_config.ini`（0x3668 起）在原始 dump 中就是**明文**
   - 文件表与代码区为加密
2. 加密算法实测：**每个 32 字节块独立执行 `jl_crypt(block, 0xFFFF)`** 即可正确解密
   - LFSR：`key = (key<<1) ^ (0x1021 if key&0x8000 else 0)`，`data ^= key & 0xFF`
3. 解密后 JLFS 文件表 CRC 全部校验通过：

```
uboot.boot      off=0x000000C0 size=0x3588
isd_config.ini  off=0x00003648 size=0x02BB
app_dir_head    off=0x00004020 size=0x4000
app_dir_head2   off=0x003DB020 size=0x3DB000
key_mac         off=0x007FF000 size=0x1000
```

4. `isd_config.ini` 明文解析成功（39 项）：SPI 2_1_0_0、OSC 24MHz、SYS_CLK 320MHz、
   SDRAM 8MB/时序参数、RESET PB01_08_0、VLVD=4、UTBD=1MHz —— **无加密 key 条目**
   （说明密钥由硬件/OTP 提供，镜像里不含）
5. 代码数据区（app_area_head，0x1ED423 字节）为 **LZ4 压缩**，解开需 BootROM 字典
6. 由此可推出的两条路：
   - 直接写 flash 时，必须按相同算法**加密后再写**（否则设备读到乱码）
   - 若能构造合法 OTA 包，可**远程刷入自定义固件**，从而实现真正的闹钟控制

### 15.3 正在进行的闹钟穷举结果（22 组）

- C2（`repeat:""`）→ 目标时刻触发"闹钟已全部删除"语音
- C7（`time` 用秒而非毫秒）→ 同样触发删除语音
- 其余 20 组（含官方标准格式 C1、hb 各种字段变体、外层信封变体）→ **完全无反应**
- 结论：`operate=8` 通道被固件解析但**未实现写入**，非法条目会被清理并语音提示

### 15.4 下一步候选

| 方案 | 说明 | 难度 |
|---|---|---|
| A. 解压 app.bin（LZ4 + BootROM 字典） | 拿到真正的代码，定位闹钟处理函数，改造成可远程写闹钟 | 中高 |
| B. 构造合法 OTA 包远程刷机 | 需要 UFW 格式 + 加密 + CRC；成功后彻底解决 | 高 |
| C. 修好编程器擦除（CH341A） | 直接写 VM 记录（格式已验证无 CRC） | 低 |
| D. 继续 operate=8 穷举 | 已验证 20+ 格式无效，收益低 | 低 |

### 15.5 A/B 双线进展（16:20）

**A. 解压/破解代码区**
- 已确认：文件表与 BANKCB 头部区用「每 32 字节块 jl_crypt(key)」模型可正确解密
- 用 BANKCB 的 `data_crc` 作为判据，对 65536 个 16 位密钥做 GF(2) 线性攻击（LFSR+CRC 均线性），
  搜索到的匹配（0x05A2 / 0x46F3）解出的数据熵仍为 7.99，属 CRC16 空间碰撞（65536 候选必有 ~1 个假匹配）→ **模型不成立**
- 结论：**代码/数据区使用了与头部不同的密钥或不同机制**（可能在 uboot 或芯片 OTP 中），
  纯离线推算暂时无解；需运行时调试（SWD）或 BootROM/uboot 明文

**B. OTA 远程刷机**
- 设备只会 `POST /iot/v2/ota/initVersion`，**从不主动查询** `/iot/v2/ota/version`
- 图灵文档也说明：设备需要"在下次更新时通过版本查询接口获取"或由云端推送触发
- 我们已完全控制 initVersion 响应，但设备收到后**不发下载请求**
- 结论：OTA 无法由设备端自动触发，除非找到云端推送 OTA 的信令（未发现）

**共同堵点**：代码区加密密钥（16 位，但按区域不同；头部区恰好为 0xFFFF）

### 15.6 关键突破：加密算法完全确认 + 官方工具链打通

**1) 拿到官方 SDK 工具链**（cpu/wl82/tools）：
`isd_download.exe`、`ufw_maker.exe`、`fw_add.exe`、`uboot.boot`、`ota.bin`、
`sd_update2.bin`、`usb_update2.bin`、`wl82loader.bin`、`download.c`、`isd_config_rule.c`

**2) 用官方工具成功生成固件镜像**（无需设备）：
按 SDK 模板构造完整 `isd_config.ini`（[EXTRA_CFG_PARAM]/[SYS_CFG_PARAM]/[RESERVED_CONFIG]…），
运行 `isd_download.exe` 得到 `jl_isd.bin`（0x105000）与 `jl_isd.fw`，
日志全程正常：`Device offline, only package the file.`

**3) 加密算法彻底确认**（用 jl_isd.bin + 已知 uboot 明文求密钥流）：
- 密钥流 = **LFSR 连续流，初始 key = 0xFFFF**，`key=(key<<1)^(key&0x8000?0x1021:0)`，`data ^= key&0xFF`
- 与模型 **11532/11532 字节 100% 匹配**
- 每个"写入单元"从各自起点重新开始该流：
  - 文件头（32 字节条目）：每条约独立单元
  - 文件数据：整块为一个单元（连续流）
- 设备 uboot 数据区用该模型解密后，与官方 uboot 结构完全对应
  （前 32 字节中仅地址/长度数值不同，属不同编译版本）

**4) 官方镜像 vs 设备镜像**：头部密文块多处**逐字节相同**（0x30-0x3F、0x50-0x5F），
证明厂商使用**默认密钥/默认加密**，未做二次保护

**5) 剩余工作**：
- app 区（0x1ED423 字节）为 LZ4 压缩的 BANKCB 数据，需按单元解密后解压
- 已知：app_dir_head / app_dir_head2 两个入口，app_area_head 指向 0x2000120（映射地址）
- 目标：解出 app.bin → 定位 operate=8 处理函数 → 改造实现远程闹钟

### 15.7 加密算法最终确认（连续 LFSR，key=0xFFFF）

用 `jl_isd.bin`（官方工具生成）+ 已知 `uboot.boot` 明文反推密钥流：

```
密钥流 = LFSR 连续流，初始 key = 0xFFFF
  byte:  data ^= key & 0xFF
         key = (key<<1) ^ ((key & 0x8000) ? 0x1021 : 0)
匹配度: 11532 / 11532 字节 = 100%
```

- 文件数据整体为一个"加密单元"，流从数据起点开始（连续，不按 32B 重置）
- 32 字节文件条目各自为独立单元（流各自重置）
- 密钥流的 LFSR 序列存在 0x40000（256KB）周期，导致多个偏移出现相同前缀
- 设备 uboot 数据区用该模型解密后与官方 uboot 结构完全吻合（仅地址数值不同）

### 15.8 当前结论

- **加密完全可以自由加解密**（Alice: 可生成合法固件）
- 官方 isd_download.exe 可在无设备时打包，已产出 jl_isd.bin/jl_isd.fw
- app 区定位仍在进行：官方镜像中 app.bin（1MB 全零）对应"连续密钥流"区域，
  需确定其单元起点（候选 0x44000 前 16 字节对齐但后续不符，疑似有 BANKCB 头）

### 15.9 app 区解密尝试（16:40）

- 差分法：第二次构建（app.bin 填 0x55）失败 —— 官方工具会校验 app.bin 是否为
  真实编译产物（报"文件app_core不存在"），不能用任意填充做差分
- 用官方镜像扫描"解密后全零"区域（app.bin 为 1MB 全零）：**未找到**
  → 说明 app.bin 数据不是简单"连续流加密"存放（可能为硬件式按 cache line 加密 + 压缩）
- 对设备 app 区候选起点（0x4020/0x4040/0x4080/0x3DB020/0x3DB040）
  爆破 65536 个初始 LFSR 状态：**无合理候选**
  → app 区加密模型与文件表/uboot 数据区不同

### 15.10 结论（当前）

已完全掌握：
1. 加密算法（LFSR，key=0xFFFF，连续/单元重置两种模式）
2. JLFS/BANKCB/isd_config 结构
3. 官方工具链（可本地生成合法镜像与 OTA 包）
4. 设备 uboot 已解密并与官方版本对应

仍缺：app 区的精确加密模型（硬件 cache-line 级，可能含地址项）

但重要的是：**即使解出 app 代码，也无法写入设备** —— 因为编程器擦除失败。
因此现在真正的瓶颈是 flash 写入能力（CH341A）或 SWD 调试，不是破解本身。

---

## 16. 2026-10-23 17:00 —— 固件加密彻底破解 ✅

### 16.1 官方工具链安装完成

- Code::Blocks → `C:\Program Files\CodeBlocks`
- 杰理工具链 2.5.3 (pi32v2 clang) → `C:\JL\pi32\bin\clang.exe`
- SDK v1.2.0 完整源码 → `work/sdkv12/`
- **成功编译** `apps/demo/demo_hello` → 真实 `app.bin`(243,344B) + `jl_isd.bin`(256KB)

### 16.2 ENC 加密算法最终确认（100% 验证）

```c
// 每个 32 字节块：
key = (0xFFFF ^ (((load_addr + block_offset) >> 2) & 0xFFFF)) & 0xFFFF;
for (i = 0; i < 32; i++) {
    data[i] ^= key & 0xFF;
    key = (key << 1) ^ ((key & 0x8000) ? 0x1021 : 0);
}
```

验证：用官方镜像 + 已知 app.bin 明文 → **243344 / 243344 字节 = 100.00% 匹配**

关键点：地址项是 **16 位**（`&0xFFFF`），且 `>>2` 用**加载地址+块内偏移**。

### 16.3 设备固件解密成果

- 文件表、uboot、isd_config：全部正确解出
- app 区（0x4120 起，load=0x2000120）解密后找到真实字符串：
  - **闹钟** @ flash 0x1871A5
  - **turing** @ 0x194145
  - **mqtt** @ 多处（0x183846/0x187848/0x18a548…）
  - 还看到 `UnitId`、`://`、`play`、`answer`、"的"、"大"、"三"、"十" 等 UI 文案碎片
- 但明文呈"片段化"（约 4-8 字节正确、随后乱码）→ **因为 app 区包含多个 bank（overlay），每个 bank 有不同的 load 地址**，
  用单一 load 地址解密只能解对主 bank。

### 16.4 下一步（明确）

1. 解析 app 区开头的 **bank 表**（官方镜像前 736 字节 100% 与 app.bin 一致，即 23 个 32 字节 bank 描述符）
2. 取得每个 bank 的 load 地址
3. 按 bank 分段用各自的 load 地址解密 → 得到完整明文
4. 搜索 `operate` 处理逻辑 → 定位闹钟增删实现

### 16.5 设备 app 区解密现状（17:10）

用「32 字节块 + 16 位地址项」算法解密设备 app 区（0x4120 起，load=0x2000120）：

- 成功解出**真实字符串**（已确认非随机）：
  - `UnitId` @ flash 0x183046
  - `闹钟` @ flash 0x183085
  - `turing` @ flash 0x194145
  - `mqtt` @ flash 0x183846 / 0x194... 多处
  - 另有 `answer`、`play`、`content`、`continue`、`的`、`三`、`大`、`最`、`heig(h)t`、`wifi`、`VID` 等 UI 文案片段
- 但明文呈「块首 4-6 字节正确、其余乱码」的模式
  → 说明设备固件的 app 区由**多个 bank（overlay）**组成，每个 bank 有不同的 load 地址，
    用单一 load 地址只能解对主 bank 的字符串区片段；其余 bank 需各自的 load 地址。

### 16.6 下一步

1. 解析 app 区开头的 bank 表（官方镜像前 736 字节 = 23 个 32 字节描述符，与 app.bin 100% 一致）
2. 取得每个 bank 的 load 地址
3. 按 bank 分别解密 → 完整明文
4. 定位 `operate` 处理函数 / 闹钟数据结构

---

## 17. 2026-10-23 17:10 —— **设备固件 app 区 100% 解密成功** 🎉

### 17.1 最终算法（全部验证）

```c
// 块大小 32 字节；相位 p = ((load_addr >> 2) - 8*首块索引) & 0xFFFF
// 实际使用 p = 0x0040（即 load 地址 ≡ 0x100 mod 0x40000）
for (i = 0; i < nblocks; i++) {
    key = 0xFFFF ^ ((p + 8*i) & 0xFFFF);
    for (j = 0; j < 32; j++) {
        data[i*32+j] ^= key & 0xFF;
        key = (key << 1) ^ ((key & 0x8000) ? 0x1021 : 0);
    }
}
```

- 官方镜像验证：**243344 / 243344 字节 = 100%**
- 设备固件：用相位爆破（p=0x40 得分 214，次高 82）得到**完整可读明文**
- 产物：`work/dev_app_plain.bin`（2,020,387 字节，设备 app 区完整明文）

### 17.2 解密后发现的协议/功能字符串

```
JSON 字段: message / operate / optCode / alarmId / payload / del_all
           alarm_up / bank_time / fromUser / openId / uniqueId
           repeat / playOp / action / dialog / ttsTxt / asrTxt
           apiKey / bookId / unitId / source / typeid / version
           mqtt:// / tcp:// / iTuring mqtt / aigcResponse
中文 UI:   闹钟 / 所有闹钟 / 全部闹钟 / 暂停播放 / 继续播放 / 停止播放
           打开绘本 / 我要读书 / 绘本模式 / 打开灯光 / 打开台灯 / ...
其他:      reboot / updata / syscfg / wifi / gdtime / rtcvdd / rec_wr
           tuling / answer / screen / offset / finger / GDEVID
```

### 17.3 下一步（代码定位）

- 字符串没有被绝对地址直接引用 → 代码用 PC 相对/查表方式访问
- 需要用 `C:\JL\pi32\bin\llvm-objdump.exe`（pi32v2 反汇编器）分析
- 方案：把 app 明文包装成 ELF → llvm-objdump 反汇编 → 找 `operate`/`del_all` 表与处理函数
- 找到后即可确认为什么 `operate=8` 不写闹钟，并给出改造/替代方案

### 17.4 反汇编链路打通（17:15）

- 找到官方编译产物 `sdk.elf`（ELF32-pi32v2，e_machine=0x00F1，.text 起始 0x2000120）
- 手工修改 ELF 节表把解密后的设备固件写入 .text → `dev_wrap.elf`
- `llvm-objdump -d` 反汇编成功 → `dev_app_disasm.txt`（41,485,113 字节）

关键发现：
- 代码通过**绝对地址立即数**加载 rodata 指针，例如
  `r5 = 0x217E5D4`、`r9 = 0x217E898`、`r1 = 0x217E530`
- 字符串池区域位于 0x217EF80 之后（对应 flash 0x182F80）
- 但代码引用的地址（0x217E5xx）比字符串池低约 0xA00，
  说明 **app 区的 flash→内存映射不是单一线性关系**（多 bank 各有 load 地址）

### 17.5 下一步

1. 解析 app 区 bank 表，确定每个 bank 的 flash→内存映射
2. 用正确映射重新反汇编，定位 JSON 字段表与 operate 分发逻辑
3. 找到 operate=8 的处理分支，确认为何不写闹钟
4. 决定方案：patch 固件 / 或改用其它注入途径

### 17.6 映射校正 + 新路线（17:25）

通过指针表反推：真实映射 `mem = flash + 0x1FF9000`（此前假设 0x1FFC000，差 0x3000）。
即 app 数据真实起点 = flash 0x7120（不是 0x4120）；解密时相位 p=0x40 已补偿该偏移。

字符串池（如 operate/del_all/闹钟）在明文里存在，但**没有绝对地址引用** →
这些字符串由云端 SDK 库通过索引/表结构访问，需进一步逆向。

### 17.7 重要新思路：设备自带 USB/SD 升级能力

SDK 编译日志显示固件支持：
```
* testbox UART upgrade
* SD card upgrade
* USB upgrade
* USB hid upgrade
* custom UART upgrade
```
设备固件同源（同 SDK 家族），**很可能也支持 U 盘/SD 卡升级**。
→ 这意味着：可以制作升级包放 U 盘，插到设备上触发升级，从而**写 flash，无需编程器**。

### 17.8 完整可行路径

1. 从解密镜像提取厂商的 uboot.boot / isd_config / app_dir 内容
2. 用我们的解密器得到厂商 app.bin 明文
3. 修改 app.bin（加入自定义闹钟写入逻辑），用已破解算法重新加密
4. 用官方 isd_download 重新打包 → jl_isd.bin → fw_add → ufw_maker → update.ufw
5. 放 U 盘，插设备触发 USB 升级
6. 设备自行擦写 flash → 写入自定义固件

这条路径完全不需要编程器和 SWD。

---

## 18. 2026-10-23 17:35 —— **决定性发现：operate=8 在 SDK 里根本未实现**

### 18.1 图灵云 SDK 源码（lib/net/turing/）确认

`turing_mqtt_cmd.c` 中 `mqtt_message_control()` 的 operate 枚举：

```c
enum {
    CONCROL_VOL_DOWN = 0x0,
    CONCROL_VOL_UP,          // 1
    CONCROL_LED_CLOSE,       // 2
    CONCROL_LED_OPEN,        // 3
    CONCROL_LOW_POWER_OPEN,  // 4
    CONCROL_LOW_POWER_CLOSE, // 5
    CONCROL_STROAGE_FORMAT,  // 6
    CONCROL_DEFAULT,         // 7
    CONCROL_SET_CLOCK,       // 8  ← 我们一直在试的 operate=8
    CONCROL_CALL_PHONE,      // 9
    CONCROL_SLEEP_TIME,      // 10
    CONCROL_SET_UP,          // 11
    CONCROL_SET_CLOSE_PLAYING,// 12
};
```

**实现情况**：
- 0/1 → 音量调节（有实现）
- 2~12 → 全部落到 `default: printf("no support")` —— **没有实现**
- 因此 `operate=8`（SET_CLOCK）对设备的唯一效果就是打印

这完全解释了此前 22 组测试：**无论什么格式都不会有反应**（删除语音来自别的路径）。

### 18.2 真正的云端闹钟协议

`turing.c` 中 `turing_cb()` 的 `TURING_EVENT_FUN_ALARM_CTL = 20025`：

```c
// 删除全部：
{"code": 20025, "func": {"action": "delete"}}   → turing_alarm_del_all()

// 设置：
{"code": 20025, "func": {"action": "...",
                        "endDate": "2026-10-24",
                        "time": "07:00:00",
                        "cycleType": "1",
                        "timeLen": "..."}}       → turing_alarm_set()
```

而 `turing_alarm.c` 实现是**定时器**（sys_timeout/sys_timer），**不持久、不写 VM、没有列表概念**。

### 18.3 结论

- 云端协议**不支持"添加持久闹钟"**，只支持"一次性定时器"和"全部删除"
- 设备 VM 里的闹钟列表（`{"hb":[{"na":"闹钟","th":...}]}`）由**本地固件 app 模块**维护
- 因此通过云端/MQTT 实现"增删闹钟列表"**在协议层面不可能** —— 这是固件设计限制，不是我们格式不对

### 18.4 真正的可行方案（重新排序）

1. **直接写 VM**（需能擦写 flash）→ 编程器（CH341A ¥15）
2. **patch 固件**：把 `CONCROL_SET_CLOCK` 实现为写 VM 闹钟列表 → 需刷机（同上）
3. 本地触发：设备按键/语音添�加闹钟（已支持）

---

## 19. 2026-10-23 17:40 —— 厂商固件自带 alarm_set / alarm_del 接口

在解密后的厂商 app 区（flash 0x183B50-0x183BC0）发现：

```
0x183B54  {"hb":[]}        ← 默认 VM 闹钟 JSON
0x183B60  bank_time
0x183B66  {"rp":[]}
0x183B98  alarm_del        ← 删除接口字段值
0x183B9E  alarm_set        ← 设置接口字段值
0x183BA6  cycleType
0x183BB0  startDate
0x183B10  alarm_err
0x183B14  titleData / innerData
```

结论：
1. 厂商固件**有自己的闹钟设置/删除逻辑**（不是 SDK v1.2.0 的定时器实现）
2. 云端消息里应使用 `action: "alarm_set" / "alarm_del"` + `startDate` + `cycleType` 等字段
3. 此前我们用的图灵官方格式（operate:8 + id/deviceId/title/time/repeat/status）
   **字段名完全不对**，所以不生效（只有个别非法组合触发了清空/语音）
4. 这些字符串没有被绝对地址直接引用 → 说明它们位于**非主 bank**（overlay bank），
   需要 bank 表才能定位引用代码；但消息字段名已经足够做协议测试

### 19.1 下一步：自建 App + 自有云端控制（可行）

架构（全部已具备）：
```
手机网页/App  →  路由器 Web API (:8898)  →  mock MQTT broker(:1883)  →  玩具
```

需要先做一轮**协议格式测试**（观察设备反应）：
- action = "alarm_set" / "alarm_del"
- startDate 格式候选: "2026-10-24 07:00:00" / "2026-10-24-07:00:00" / 时间戳
- cycleType 候选: 1 / "1" / "1,2,3" / 127
- 外层: {"type":2,"fromUser":"<openid>","message":{...}}

一旦某组生效，就把该格式封装成 Web App 的"添加/删除闹钟"按钮。

---

## 20. 2026-10-23 17:30 —— 本轮收尾记录（改天继续）

### 20.1 消息格式矩阵测试结果（17:21-17:27）

通过 mock 云 `/api/raw` 向设备 topic 推送 8 条候选消息，全部 PUBACK 正常，
设备**无任何语音/提示反应**：

| 时间 | 消息 |
|---|---|
| 17:21:29 | type=2 + action=alarm_del（阳性对照） |
| 17:21:39 | type=0 + action=alarm_del |
| 17:22:19 | type=1 + action=alarm_del |
| 17:22:59 | type=3 + action=alarm_del |
| 17:23:39 | type=5 + action=alarm_del |
| 17:24:19 | type=2 + action=delete |
| 17:24:59 | 裸包 {"code":20025,"func":{"action":"delete"}} |
| 17:25:39 | type=2 + operate=8 + arg.action=alarm_del |
| 17:26:19 | type=2 + action=alarm_del + id/alarmId |

结论：厂商固件收到明文 JSON 后**未进入闹钟处理分支**。
可能原因：
1. 消息需要按图灵协议做 AES-CBC 封装（`{"data":"<base64>"}`），设备先解密再解析
2. 厂商固件的 message type / 字段与 SDK v1.2.0 不同
3. 设备只处理来自 HTTP 响应（NLP）的 func，不处理 MQTT 下发的控制消息

### 20.2 固件中的云端接口清单（解密后提取）

```
/api/equipment_reporting
/api/ntp.form
/iot/audio
/iot/bind_from_sinvoice
/iot/message/send
/iot/status/notify
/iot/v2/ota/initVersion
/v2/asr
/v2/iot/audio
/v2/ota/initVersion
/v2/tts
```

其中 `/iot/message/send`、`/iot/audio`、`/v2/tts`、`/v2/asr` 是设备**上报/请求**接口；
`/iot/v2/ota/initVersion` 是 OTA 版本上报（其响应即 OTA 触发入口，格式已确认）。

### 20.3 已验证的 OTA 触发条件（源码级）

`lib/net/turing/turing_iot.c: ota_update_version_info()` 要求响应为：

```json
{"payload":{
  "updateStatus":0,
  "agree":1,
  "id":<非0整数>,
  "nextVersion":"<与 currentVersion 不同的版本号>",
  "currentVersion":"<必须等于设备当前版本>",
  "firmwareUrl":"<固件下载地址>"
}}
```

满足后设备调用 `get_update_data(url)` 下载并升级，成功后再 POST `/iot/v2/ota/updateStatus`。
→ 只要 mock 云正确返回该 JSON 且固件包合法，即可**远程刷机**（无需编程器）。

### 20.4 待办（下次继续）

1. **OTA 远程刷机验证**：让 mock 云在 `/iot/v2/ota/initVersion` 返回上述 payload，
   firmwareUrl 指向路由器上的合法 `.ufw`（先用官方 demo 包测试设备是否下载/校验）
2. **消息加密封装测试**：若 OTA 路线受阻，改测 MQTT 加密信封
   （AES-128-CBC，key=IV=`ix6hy5975d228rrv`，base64 放 `data` 字段）
3. **固件 patch 方案**：把 `CONCROL_SET_CLOCK` 实现为写 VM 闹钟列表，重新打包刷入
4. **编程器方案（兜底）**：CH341A 擦写 flash 直接改 VM

---

## 21. 2026-10-25 —— App 可行性结论 + 环境变更记录（改天继续）

### 21.1 用户需求

"根据完全解密的固件，做个 App 实现配对、闹铃、音乐播放、对讲功能"

### 21.2 可行性结论（基于已解密固件 + SDK 源码）

| 功能 | 可行性 | 依据 |
|---|---|---|
| 配对（配网+绑定） | ✅ 可行 | `apps/common/ble/le_net_cfg_turing.c` 实现 BLE 配网：服务 0xFFFF（FF01 写/FF02 通知），分片传 SSID/pwd/openid；云端 bind 接口可模拟 |
| 音乐播放 | ✅ 可行 | MQTT type=1(audio): {arg,url,mediaId,operate:1} 播放；type=0(chat): {url} 直接播 |
| 闹铃 | ⚠️ 需固件补丁 | operate=8 固件未实现；图灵云闹钟=一次性定时器（不持久/不写 VM）。App 控制需 OTA 刷补丁固件 |
| 对讲 | ❌ 原生不支持 | 固件无 intercom/rtp/webrtc 音频流协议；opus/aec 仅用于 ASR/TTS。只能做半双工语音留言 |

### 21.3 建议架构与分期

```
Flutter App → 路由器自有云(Go) → MQTT(:1883)/HTTP(:8081) → 玩具
```

- 第一期（不改固件）：设备状态、音乐点播、语音播报/留言、OTA 触发
- 第二期（OTA patch 后）：云端增删闹钟（把 operate=8 实现为写 VM 列表）
- 第三期（大量固件开发）：实时对讲

### 21.4 ⚠️ 环境已变更（2026-10-25 检查）

| 项 | 原状态 | 现状态 |
|---|---|---|
| 路由器 | ASUS AC68U（asus/Abcd1234，armv7） | **RT-AX89X**（SSH 密码不对，arm64 需确认 uname -m） |
| mock 云 :8898 | 运行中，设备在线 | **不在运行** |
| 玩具 192.168.6.106 | 在线 | **离线** |
| 本机 IP | 192.168.6.x | 192.168.6.157（以太网 3） |

### 21.5 环境恢复清单（明天先做这个）

1. 登录新路由器（RT-AX89X，确认 SSH 账号/端口/密码；`uname -m` 确认 aarch64/armv7）
2. 重新交叉编译 mock 云（Go：如果 aarch64 → `GOARCH=arm64 GOOS=linux`）
3. 部署到 `/koolshare/chatrabbit/bin/chatrabbit`，用 ctl 脚本启动
4. 重建重定向规则（iptables PREROUTING→本地 8081/1883；DNS 劫持 turingapi.com）
5. 玩具上电联网；如果连不上，需 BLE 重新配网（或确认它是否仍指向旧网关）
6. 验证 `curl http://<新路由IP>:8898/api/status` 显示 device_online=true

### 21.6 明天第一件事

按 21.5 恢复环境 → 跑 OTA 远程刷机验证（HANDOVER.md 第五节）→ 再开始 App 一期开发。

---

## 22. 2026-10-26 —— 设备完全接入自有云（重大进展）

### 22.1 环境恢复与踩坑

- 路由器仍是 ASUS AC68U（KoolShare Merlin, armv7l, 内核 2.6.36）
- 本机 Go 工具链丢失 → winget 装 Go 1.27 → 编译出的二进制在 2.6.36 上 **SIGILL 崩溃**
  → 改用 **Go 1.19.13**（原二进制正是 go1.19.13 编译）+ `GOARM=5` 成功
- 路由器 dropbear 不支持 SFTP → 改用 **SSH stdin 管道** 传输（`cat > /tmp/x` + stdin.write）
- 重编译脚本：`work/tools/go/bin/go.exe` + `router/build.ps1 -GOARM 5`

### 22.2 新增能力（mock 云改造）

1. **SNTP 服务器**（UDP:123）：设备每次开机都卡在 NTP（RTC 丢失），现秒回标准应答
2. **MAC 强制重定向**（不依赖 DNS）：设备会绕过 DNS 直连真实云 IP，改为按 MAC 劫持：
   - `80 → 8081`（HTTP）
   - `10883 / 8883 → 1883`（MQTT）
   - `53 → 53`（DNS）
   - 已注入 `chatrabbit_ctl.sh` 的 `nat_add()`，服务重启后自动恢复
3. **NTP 域名劫持**：`time.edu.cn` / `s2a~c.time.edu.cn` / `ntp.aliyun.com` / `pool.ntp.org`
4. **OTA 响应改造**：按设备源码要求返回
   `payload.{updateStatus,agree,id,nextVersion,currentVersion,firmwareUrl,softVer,version,url,...}`

### 22.3 已验证可用

- 设备 HTTP + MQTT **全部连回自有云**（`device_online=true`）
- `getMQTTConnectInfo` / `status/notify` / `initVersion` 均正常往返
- **音乐播放链路打通**：发送 MQTT `type=1` 音频消息后，设备
  - 回 PUBACK
  - **主动 GET `/media/tone.mp3`**（真实下载音频）
  - 之后 POST `/v2/iot/audio` 上报
- 设备本机闹钟活着（测试期间播过"闹钟时间到了，现在是下午4点"）

### 22.4 未打通：OTA 固件下载

`initVersion` 已收到并返回完整升级响应，但设备**不发起下载**。
- 厂商固件 OTA 字段：`softVer` / `firmwareUrl` / `net_upgrade` / `md5`
- 这些字符串属于**另一个 bank**，绝对地址引用搜不到 → 需要先做 bank 映射才能定位解析逻辑
- 可能还缺字段（md5/文件大小/版本格式），待用正确 bank 映射反汇编确认

### 22.5 下一步

1. 用已解密的固件做 **bank 表映射**（多 bank 加载地址），定位 OTA 响应解析函数
2. 或直接暴力穷举 OTA 字段组合（每次需等 token 过期 + 踢连接触发 initVersion）
3. 音乐/语音功能已可用 → 可开始做 App 一期（状态/点播/播报）
4. 闹钟仍需 OTA 刷补丁固件或编程器写 VM

---

## 23. 2026-10-26 10:06 —— **云端闹钟增删改全部打通（App 可用）**

### 23.1 结论

固件不提供"云端管理闹钟列表"的协议（SDK operate=8 未实现；厂商本机闹钟存 VM）。
因此改用 **云端闹钟**：路由器按计划推送 MQTT 音频消息，设备播放铃声。
用户需求"云端和 App 的闹钟添加/删除/修改"已全部实现。

### 23.2 实现内容（mock 云 v1.1.0+）

| 接口 | 功能 |
|---|---|
| `GET  /alarms` | 手机网页控制台（添加/修改/删除/开关/试听/停止） |
| `GET  /api/alarms` | 闹钟列表 |
| `POST /api/alarms/add` | 添加（`time` HH:MM + `repeat` [1-7] 或 `at` Unix 秒一次性） |
| `POST /api/alarms/update` | 修改（time/repeat/enabled/at） |
| `POST /api/alarms/toggle` | 开关 |
| `POST /api/alarms/remove` | 删除 |
| `POST /api/alarms/clear` | 清空 |
| `POST /api/play` `{file}` | 立即播放 media 目录音频 |
| `POST /api/stop` | 停止播放 |

调度器每 5 秒检查：到点 → 推送 `type=1` 音频消息 → 设备下载播放 →
`ring_seconds`（默认 20s）后自动发送停止（`operate=2`）。

### 23.3 实测验证（10:06）

```
10:06:21 [alarm] 触发闹钟 id=1 time="10:06"
10:06:21 [send] type=1 音频 tone.mp3
10:06:21 [HTTP] media "tone.mp3"     ← 设备下载并播放
10:06:41 [alarm] 响铃 20 秒结束，已发送停止
剩余闹钟: id=1 enabled=false fired_at=...
```

增/改/删/清空/开关 API 全部返回 ok，列表状态正确。

### 23.4 已知限制

- 设备**本机闹钟列表**（VM 0x7BF000）无法读写 → 设备自身闹钟仍可能响（来自历史 operate=8）
- 铃声文件：`tone.mp3`（MPEG2 L3 11025Hz 128kbps mono），实测可播放
- 设备每播完一首会请求 `/v2/iot/audio` 下一首 → 现在返回 `music_file`（alarm_voice.mp3）
- 访问地址：`http://192.168.6.1:8898/alarms`（同一 WiFi 手机可开）

### 23.5 下一步

1. 用户实测网页增删改
2. 可选：美化铃声（上传更好听的 mp3，通过 `/api/alarmvoice` 设置）
3. 保留 OTA/原生闹钟作为后续攻坚项

---

## 24. 2026-10-26 10:46 —— bank 分析重大突破（子任务 bank-analyst）

### 24.1 运行时基址修正（关键！）

- **运行时基址 = 0x2000100**（此前误用 0x2000120）
- 正确映射：`运行时地址 = 0x2000100 + dev_app_plain.bin 偏移`
- 即 `mem = flash + 0x1FFBFE0`
- `dev_app_plain.bin + 0x20` 才是 app.bin 真正起点（运行时 0x2000120，与 sdk.ld `rom ORIGIN=0x2000120` 一致）
- 证据：`firmwareUrl`(偏移 0x180370) 实际被引用为 **0x2180470**（不是 0x2180490）
- 另一个坑：厂商代码用**池基址寄存器 r13=0x217E530 + 偏移**访问字符串，只搜绝对立即数会漏掉日志串

### 24.2 bank/分区结构定论

不是 23×32B 描述符表，而是标准 JLFS + **A/B 双槽**：
- 顶层 5 条目：uboot.boot@0xC0、isd_config.ini@0x3648、app_dir_head@0x4020、app_dir_head2@0x3DB020、key_mac@0x7FF000
- app 区 = 9 条目 JLFS 子目录，槽 A @0x4020、槽 B @0x3DB020，各 0x1ED423 字节，运行时基址 0x2000000
- 条目：app_area_head(load 0x2000120) / app.bin / cfg_tool.bin / VM / BTIF / PROFILE / WKS / EXIF / BES
- 槽 B 已完整解出：`work/bank/unitB.bin`（+ 反汇编 unitB_disasm.txt）
- 固件版本来源：`AC79NN_V1.0.319_UKITOS_240905`（厂商 NingJu）

### 24.3 **OTA 响应 JSON 必需结构（最重要产出）**

```json
{"payload": {
  "net_upgrade": 1,                 // 必须=1（任务名/net_upgrade 标志）
  "firmwareUrl": "http://.../x.ufw",// 必须，且应为合法 .ufw
  "softVer": "<高于设备当前版本>",    // 否则 [updata]softVer is low 直接放弃
  "softVersion": "<同上>",
  "updateStatus": 0, "agree": 1, "id": 12345,   // SDK 字段，建议超集一起发
  "currentVersion": "<设备上报的版本>", "nextVersion": "<新版本>",
  "fileSize": <字节数>, "md5": "<hex>"
}}
```

- 厂商固件里**不存在** `updateStatus`/`agree`/`nextVersion` 字符串（0 次命中）→ 厂商解析器不是 SDK 那套
- 关键代码地址（槽 B 反汇编）：
  - `0x20CCADA` = OTA 下载执行器（读 `cfg_get("upgrade")==1` → `cfg_get("firmwareUrl")` → http_get_url）
  - `0x200B244` = 注册 "net_upgrade" 下载任务
  - `0x2037B10` = `/iot/v2/ota/initVersion` 请求构造/状态机
  - `0x2034000` 区 = uALib/cJSON HTTP 响应解析层（payload 判空等）
- 排查顺序：① net_upgrade 缺/≠1；② softVer 未高于当前；③ URL 不是合法 .ufw（过 `net_firmware_check`）；④ URL 写入 VM 后需重启/任务轮询触发

### 24.4 mock 云已配置（等待设备触发验证）

```
ota_url    = http://192.168.6.1:8081/media/ota_probe.ufw
ota_md5    = <28 字节探测文件 md5>
ota_format = map（payload 为对象）
ota_extra  = {net_upgrade:1, softVer/softVersion:AC791N_NJ_1V2_V3.0,
              firmwareUrl:..., fileSize:28, md5:..., upgrade:1, upgradeMode:1}
```

### 24.5 下一步

1. 设备触发 initVersion → 观察是否 GET `/media/ota_probe.ufw`
2. 若触发 → 制作合法 .ufw（先用原厂/槽 A 镜像，再用补丁固件）
3. 刷入补丁固件 = 本机闹钟增删改彻底解决
4. 兜底：CH341A 编程器写 VM

---

## 25. 2026-10-26 11:31 —— **OTA 通道彻底打通（里程碑）**

### 25.1 真正的 OTA 触发链（子任务 bank-analyst 逆向确认）

设备**不是**走 `iot-ai.turingapi.com` 的 OTA，而是**老版图灵 "robot" 接口 + 硬编码 IP**：

```
主循环 → net_check_upgrade
  GET http://120.76.63.67:7890/robot/flash_check.form?token=<apiKey>_<deviceId>|<softVer>|<V2.0>
      → 解析 JSON：code==200 且 body.upgrade==1  才继续
  → 创建任务 "net_upgrade"
      GET http://120.76.63.67:7890/robot/flash_download.form?token=...
          → 要求 code==200、body.upgrade==1、body.firmwareUrl 非空
      GET http://120.76.63.67:7890/sdk/auth.form?secret=***TURING_SECRET***&uuid=<MAC无冒号>
          → 取授权 token
      → 下载 firmwareUrl（netdl 库）
  另有 GET /api/ntp.form 做时间同步
```

响应必须是**纯 JSON**，`code` / `upgrade` 必须是**数字**（不是字符串、不是 base64/AES）：
`{"code":200,"body":{"upgrade":1,"firmwareUrl":"http://..."}}`

### 25.2 为什么之前全部失败

| 之前的做法 | 失败原因 |
|---|---|
| DNS 劫持 turingapi.com | 设备用**硬编码 IP** 120.76.63.67:7890，DNS 无效 |
| 响应给 `payload` 结构（含 updateStatus/agree/nextVersion/softVer…） | 这些字段在老接口里**完全不存在**，解析器只认 `code`/`body` |
| 给 `/iot/v2/ota/initVersion` 加字段 | 该响应**只用于建 MQTT 连接**，与 OTA 无关 |

### 25.3 实测证据（11:31 与 11:33 两次）

```
[robot] ntp.form
[robot] flash_check.form token=...|AC791N_NJ_1V2|V2.0
[robot] flash_check.form  (下载前复查)
[robot] flash_download.form
[HTTP] media "ota_probe.ufw"        ← 设备下载了我们提供的固件
```
用户确认设备进入 **OTA 升级过程**。DNAT 计数 5 包。探测文件 28 字节非法内容 → 验证失败、未写入 flash（设备安全）。

### 25.4 mock 云新增实现（router/src/main.go）

- `GET /robot/flash_check.form` → `{"code":200,"body":{"upgrade":<0|1>,"flashVersion":...,"flashUrl":...}}`
- `GET /robot/flash_download.form` → `{"code":200,"body":{"upgrade":<0|1>,"firmwareUrl":...}}`
- `GET /sdk/auth.form` → `{"code":200,"body":{"token":"..."}}`
- `GET /api/ntp.form` → `{"code":200,"body":{"time1":..,"time2":..,"timestamp":..}}`
- **OTA 开关** `cfg.OTAEnable`（`POST /api/ota {"Enable":false}`），默认关闭，防止设备反复重试升级
- DNAT：`iptables -t nat -A PREROUTING -s 192.168.6.106 -d 120.76.63.67 -p tcp --dport 7890 -j DNAT --to-destination 192.168.6.1:8898`

### 25.5 设备状态

- 升级失败后设备正常重启恢复（OTA 验证在写入前失败，**不会变砖**）
- 关闭 OTA 后 `flash_check` 返回 `upgrade:0`，设备恢复正常运行（MQTT + status/notify + auth.form）

### 25.6 下一步

1. 制作**合法 ufw**（用厂商 isd_config + 明文 uboot/app + 官方 isd_download/ufw_maker 打包），先做"同版本刷写"验证整条链
2. 子任务正在用修正基址（0x2000100）定位**闹钟协议**（alarm_set/alarm_del/startDate/cycleType），若成功可免刷机直接控制本机闹钟
3. 若闹钟协议需改代码 → 用 OTA 刷入补丁固件

---

## 26. 2026-10-26 13:10 —— 唤醒词/绑定/闹钟通道的最终结论

### 26.1 设备实际只调用 5 个云接口

从 mock 云日志统计（全部历史）：

```
52  POST /iot/status/notify
26  POST /v2/iot/audio
20  POST /public/mqttManager/getMQTTConnectInfo
11  POST /iot/v2/ota/initVersion
 2  GET  /time_now
```

**从未调用**：`/app-author/bind`、`/app-author/bind_status`、`/iot/bind_from_sinvoice`、`/open/device/bind-wechat-user`、`ai-off-acti.hivoice.cn/rest/v1/device/activate`、`ws-api.turingapi.com/api/v2`

原因：VM(0x7B3000) 里已有绑定凭据（`local|fake` openid、apiKey、topic），设备认为自己"已绑定"，跳过绑定流程。

### 26.2 唤醒功能（KWS）现状

固件内含完整云知声 SDK：

```
UNISOUND_KWS_GRAMMAR v3.2 / UNISOUND_KWS_AM
唤醒词：小菲 / 小星 / 小乐 / 你好小乐
KWSCreateOnBuffer / KWSSetOptionString / Engine has started
ai-kws-off / ai-tts-off / ai-dgc-off / ai-ssp-off（云端功能开关）
ai-off-acti.hivoice.cn/rest/v1/device/activate（激活接口）
本地激活失败
```

设备**不请求激活接口**（已出厂激活或由 VM 配置决定），KWS 引擎未启动 → 叫唤醒词无反应。
劫持 `ai-off-acti.hivoice.cn` 后观察：无任何请求。

### 26.3 闹钟存储（可用，但只能物理写）

- BES 区 flash **0x7BF000**（0x40000），**明文 JSON**，`alarm` 键是内嵌 JSON 字符串
- 字段：`na/th/tm/sd/wk/wt/s`（名称/时/分/起始日期/星期掩码/响铃方式/使能）
- 现有一条：`闹钟 18:18 sd=1792684800 wk=63 s=0`
- 已交付离线编辑工具 `work\bes_alarm_edit.py`（list/add/del/set/clear）
- **写入只能靠编程器**：OTA 只含 code+res，不含 BES/VM

### 26.4 设备时间

- 路由器 TZ=UTC，此前 NTP/ntp.form 发的是 UTC → 设备显示错乱（2070 默认值）
- 已改为发送 **UTC+8 北京时间**，并对 ntp.form 返回多种字段别名（time/timestamp/date/datetime/ms 等）

### 26.5 结论

| 通道 | 状态 |
|---|---|
| 音频播放（云端点播/播报/告警） | ✅ 可用 |
| 云端闹钟（定时推音频） | ✅ 可用（App 可控增删改） |
| 设备本机闹钟（BES） | 🔒 需写 flash（编程器） |
| 语音唤醒（KWS） | 🔒 被激活/配置网关锁死，云端无法改 |
| OTA 升级 | ✅ 通道已通（robot 接口 + DNAT） |

下一步只有两条：**CH341A 编程器**（最稳）或 **同布局固件 + OTA**（有风险，且 OTA 不含 BES，只能刷代码补丁）。

---

## 27. 2026-10-26 14:10 —— **闹钟命令通道最终定论（图灵 TCP 长连接）**

### 27.1 完整链路（子任务 4 轮逆向，全部代码实证）

```
设备主动 TCP 连接 smartdevice.ai.turingapi.com:80（明文，非 8081）
  → 发送动作：ReqAudIn / ReqTTS / ReqTxtIn（iTROS_cloud_socket_send）
  → 读 HTTP 风格响应帧：
       strstr "HTTP/1.1" → sscanf 状态码（必须 == 200）
       strstr "Content-Type:" / "Content-Length:"（必须 >0）
       malloc + memcpy body → CMLoadFromBuffer(JSON)
  → router 0x2020242 按 body JSON 里的 code 数值分派
       func.action == "add"    → 造内部信封 {opt_code:"alarm_set", payload:{...}}
       func.action == "delete" → 造 {opt_code:"alarm_del", ...}
       → 分发器 0x201BA04 → alarm_set(0x201C070) / alarm_del(0x201C182)
```

响应体格式（正确网络格式，非内部信封）：
```json
{"code":<对话类码>,"asrTxt":"设置闹钟","tts":"好的","ttsTxt":"好的",
 "func":{"action":"add","cycleType":"1","hour":18,"mintue":18,"second":0,
         "year":2026,"month":10,"day":23,"event":"起床"}}
```
- `mintue` 是固件拼写错误
- `cycleType` 是**字符串** "0".."4"
- `code` 候选：1000655/1000668/1000680/1000693/20092/20109/42000/43000/43010/43037/20000..20039；**200 是 tts 分支**
- 无 AES/base64 层

### 27.2 为什么远程设闹钟始终失败（最终解释）

设备**只有在 AI 会话（语音交互）时才会建立这条 TCP 连接**（ReqAudIn 上传音频等）。
而 AI 会话需要 KWS 唤醒/ASR 授权 → `[auth_isok]` 由云知声激活状态决定 → 本机未激活 → **会话永不启动 → TCP 通道永不建立 → 闹钟命令永远没有入口**。

实测佐证：mock 日志显示设备**从未**请求 `smartdevice.ai.turingapi.com`（host 统计只有 iot-ai 134 次、easybox 8 次）。

### 27.3 已试过且确认无效的通道

| 尝试 | 结果 |
|---|---|
| MQTT `{"opt_code":...}` / 图灵信封 / AES | 无反应（MQTT 不喂该组装器） |
| MQTT `{"code":1000680,...,"func":{...}}` 6 个码 | 无反应 |
| `/iot/status/notify` 响应塞命令（httpcmd/httpraw） | 无反应 |
| 短按唤醒键触发会话 | 设备只做 status/notify + time_now，无 TCP 会话 |

### 27.4 结论

**本固件下，闹钟的远程控制通道被"语音授权"锁死，纯软件无解。** 要远程控制必须物理写入 flash：
1. 编程器 CH341A（推荐）：可直接改 BES 闹钟 / 修 auth 状态 / 刷补丁固件
2. 或改 app.bin 默认 BES 模板 + OTA（布局与原厂不一致，有风险）

### 27.5 今日已解决/交付
- ✅ 时间同步（讯飞 `/time_now` 裸文本时间戳）
- ✅ 云端闹钟（网页 App 增删改 + 定时推铃声，实测可用）
- ✅ OTA 通道（robot 接口 + DNAT，设备能下载固件）
- ✅ 闹钟存储格式与离线编辑器（bes_alarm_edit.py）
- ✅ 完整协议逆向（三套云 SDK 的关系、内部信封、TCP 会话协议）

---

## 28. 2026-10-26 15:00 —— OTA 刷补丁尝试与变砖

### 28.1 做了什么
1. 破解 OTA 容器格式（2 段 × 0x20 段表 + 0x40 起负载）
2. 生成"原厂等价包"（负载 = 设备自身 dump[0x4000:0x1F19C1]）→ 通过 mock OTA 下发
3. 设备下载、显示"升级成功"、关机
4. **之后再也无法开机**（完全无反应）

### 28.2 变砖原因
**OTA 负载写入基址判断错误**：设备并未把负载写到 0x4000，实际写坏了固件区。
（证据：第一次升级后设备再无任何网络请求；补丁包那次设备从未下载）

### 28.3 已确认可用的部分
- OTA 通道本身可用（设备会下载、校验、显示成功/失败）
- 补丁镜像 `work\dump_patched_code.bin` 已生成（221 字节改动，全部在 app 区）
- pi32v2 工具链 + 注入流水线全部打通
- 双编码调用图、字符串引用扫描、代码洞查找工具齐全

### 28.4 恢复方案（明天 341A）
见 `work\RECOVERY_341A.md`：
1. 读整片备份
2. 写回 `chat rabbit-puya.bin`（原厂）
3. 开机验证
4. 写 `dump_patched_code.bin`（补丁）
5. 能开机 → 测 App 控制；失败 → 写回原厂

### 28.5 待办
- [ ] 341A 到货 → 恢复原厂
- [ ] 补丁固件实测（App 控制闹钟）
- [ ] 若补丁崩溃：换更安全的 hook 点（MQTT 音频播放路径，而非 JSON 解析器）
- [ ] OTA 容器格式修正：需反汇编 uboot（加密形态，需先解密）或拿到真实厂商 OTA 文件
- [ ] 代码洞重新筛选：当前 0x20C40E8 区域有 2 个绝对指针（0x20EE3C3→0x20C4100、0x21AE6ED→0x20C40F0），非安全洞

### 28.6 关键教训
- **不要用未验证的 OTA 容器刷机**（会写坏固件区）
- 有编程器时优先整片写入，字节完全可控

---

## 29. 2026-10-27 14:00 —— 小程序逆向（重大突破）

### 29.1 拿到小程序代码包
- 电脑版微信 4.x 缓存路径：
  `%APPDATA%\Tencent\xwechat\radium\users\<hash>\applet\packages\wx799f9d2fc3461154\13\__APP__.wxapkg`
- 加密格式 `V1MMWX`，解密算法（实测可用）：
  ```
  key = PBKDF2-SHA1(appid, "saltiest", iterations=1000, dklen=32)
  前 1024 字节: AES-256-CBC, IV="the iv: 16 bytes"
  其余: 逐字节 XOR (key = appid 倒数第 2 个字符的 ASCII)
  输出 = 解密头[0:1023] + XOR 数据
  ```
- 解包布局：`0xBE` + u32(info) + u32(indexLen) + u32(bodyLen) + u8(0xED) + u32(文件数)
  + 每文件 [u32 名长][名][u32 偏移][u32 大小] + 数据体
- 解出 105 个文件，`app-service.js` 899 KB（完整业务逻辑）

### 29.2 小程序 API 全表（关键）
```
host: https://chatrabbit.shenzhenbenwo.com/api/
动作（POST/GET 同名）:
 add_or_update_habit   ← 闹钟/习惯（title, alarm_time, alarm_type, alarm_date, id）
 get_habit_info        ← 读取闹钟
 get_device / set_device / get_device_status / get_user_devices(_info)
 bind_device / unbind_device / add_devices / change_device_name / transfer_user_devices
 volume_change / set_voice / get_course_info / start_course / cancel_task ...
响应格式: {code:1, data:{...}, info:"..."}
```

### 29.3 决定性结论：**厂商后端已死**
- `chatrabbit.shenzhenbenwo.com` 域名可解析（124.222.187.226），但 **HTTPS 连接失败（HTTP 000）**
- 小程序登录必然失败 → 厂商云服务已下线
- ⟹ 任何"走厂商云"的方案都不可行；设备控制只能从**设备端**解决

### 29.4 对方案的最终影响
| 方案 | 结论 |
|---|---|
| 用厂商 App/小程序控制 | ❌ 云端已死，永久不可用 |
| 模拟厂商云（API+图灵中转） | ⚠️ 可模拟 API 给 App，但"云端→设备"的消息体是服务端逻辑，无样本 |
| 补丁固件（MQTT 音频路径 hook） | 🔍 唯一可行，但还需 hook 地址（子任务三轮未定位） |
| BES 注入 | ✅ 确定性可用（不能远程改） |
| 自研固件（SDK） | ⏳ 可行但无串口，盲刷风险大 |

### 29.5 产物
- `work\applet_dec.wxapkg`：解密后小程序包
- `work\applet_extract\`：解包文件（app-service.js 等）
- `work\dec_wxapkg.py`、`work\parse_wxapkg2.py`：解密/解包脚本
- `work\dig_js*.py`：API 与逻辑提取脚本

---

## 30. 2026-10-27 15:30 —— 服务器下发路径深挖（新证据）

### 30.1 否证：8002~8012 是"设备上报"代码，不是下行命令
`0x203B1F4` 是**消息构建器**：调用者传常量 8005/8008/8009 给它生成上报 JSON
（`0203B51E: r0 = 8005; call 0x203B1F4`）。外层按命令号 0~7 用 `tbh` 分派后逐项生成。
→ 之前用 type=8002~8012 发命令的方向是反的。

### 30.2 否证：设备从不主动发 MQTT
`[MQTT] PUBLISH` 日志计数 = 0（一整天）。设备上行只走 HTTP
（`/iot/status/notify` 71 次、`/v2/iot/audio` 30 次、`/iot/v2/ota/initVersion` 18 次）。
→ 下行通道只可能是 MQTT（type=1 音频已验证可下发）。

### 30.3 重大确认：**0x200843A 就是 JSON 解析器**
BES 加载函数 `0x200D4E2` 的真实调用序列：
```
malloc(0x40000)
读 flash 0x40000 字节到缓冲区
call 0x200843A            ← 解析 JSON（即被我们 hook 过的函数）
call 0x20084FE（取键值）: bank_time / alarm_up / hp_sinfo / rp_sinfo / task_sinfo
```
**之前的 hook 点其实是对的**（补丁崩溃原因是 stub 逻辑/栈，不是 hook 点错）。

### 30.4 闹钟模块地址表（池基址 0x217E530）
| 用途 | 偏移 | 地址 |
|---|---|---|
| alarm_up | +4890 | 0x217F84A |
| hp_sinfo | +4899 | 0x217F853 |
| rp_sinfo | +4908 | 0x217F85C |
| bank_time | +5643 | 0x217FB3B |
| task_sinfo | — | 0x217FE30 |
| 子解析器 | — | 0x200D102 / 0x200D242 / 0x200D392 |
| **BES 加载器** | — | **0x200D4E2** |

### 30.5 BES 默认模板（固件内，0x218FDE0）
```json
{"bank_time":0,"alarm_up":0,"alarm":"","hp":"","rp":"","task_sinfo":"","hp_sinfo":"","rp_sinfo":""}
```
（与实测 BES 内容完全一致，可作为写 BES 的权威模板）

### 30.6 下一步（精确目标）
1. 找 **BES 加载器 0x200D4E2 的调用者** → 闹钟 UI 模块
2. 在 UI 模块内找"新增/删除闹钟"函数（应含 cJSON 构建 hb 数组 + flash 写）
3. 拿到该函数后：hook MQTT 收包 → 调用它即可实现 App 控制
4. 另：`hb` 键 0x217FB32 暂无引用命中，说明写入侧可能用模板字符串 sprintf 而非 cJSON

---

## 31. 2026-10-27 16:00 —— 反汇编盲区修复 + WWS 模块真相

### 31.1 重大工具修正
之前 `dev_app_disasm2.txt` **缺少 0x2019000-0x201A000 等区域**（objdump 未覆盖），
导致我们把 `0x201945A` 误判为"零引用死代码"，并用它放 stub（final.bin 失败原因之一）。

**修复**：用 `.incbin` 把整个 app.bin 包成 ELF 后完整反汇编：
```
work\dis_full.s / dis_full.elf -> work\dev_app_disasm3.txt（753,893 条）
work\range_dis3.py   ← 改用新反汇编的查询工具
```

### 31.2 WWS 模块是**活代码**
`0x201944A` = WWS 初始化函数，**有 5 个调用点**：
```
0x201AFD8 / 0x202350A / 0x202369E / 0x2023F5C / 0x20240D8
```
它在 `[ctx+3804]` 处构建**命令 ops 表**：
| 偏移 | 处理函数 |
|---|---|
| +0 | 0x20C3E7E |
| +4 | 0x20C3CA8 |
| +8 | 0x201892E |
| **+12** | **0x203C36A = 闹钟命令处理器（alarm_set/alarm_del）** |
| +16 | 0x20C40B8 |
| +20 | r5（动态） |
| +24 | 0x20C45E2 |
| +28 | 0x203D10A |
| +32 | 0x2183A8A |

### 31.3 闹钟协议（完整字段，base=0x217E530）
```
opt_code: "alarm_set" / "alarm_del"
parameters: { action:"add"/"delete", alarmType, memoContent, timeLen, alarmId,
              cycleType, startDate, loop, time, hour, mintue, second, year, event }
外层(0x2038802): fromUser, optValue, repeat, reset
```

### 31.4 结论：闹钟命令走 iTROS **TCP** 通道
调用 wws init 的函数（如 0x2023F4E，栈 2060 字节）是网络协议任务；
设备当前**从不建立** `smartdevice.ai.turingapi.com` 的 TCP 连接（mock 日志 0 次），
所以 MQTT 下发无法触达该 ops 表 → 所有 MQTT 尝试均无效。

### 31.5 下一步
1. 找"设备发起 iTROS TCP 连接"的触发条件（可能由 turing 响应或唤醒词触发）
2. 若能触发，让设备连到我们自建的 TCP 服务端（DNS 可控）
3. 用已解析的 optValue 协议直接下发 alarm_set/alarm_del → 实现 App 控制

---

## 32. 2026-10-27 16:30 —— 云端 TCP 连接路径定位

### 32.1 关键字符串
```
smartdevice.ai.turingapi.com  @0x21827E3（唯一引用 0x2022B6E）
iTROS / iTROS_task / iTROS_mqtt / iTROS_cloud  @0x21809xx-0x2180Exx
turingapi.com/api/v2、/v2/iot/audio、/iot/message/send、app-author/bind
```

### 32.2 连接函数
`0x2022AC8` = 解析 smartdevice 主机并建立连接的函数
- 调用点：`0x2025314`（所在函数 0x202481A）、`0x2032558`（所在函数 0x203200C）
- 这两个函数**无直接调用者** ⟹ 是以**函数指针注册**（任务/回调）方式启动

### 32.3 下一步
1. 找 0x202481A / 0x203200C 的注册点（任务创建表 / 回调注册）
2. 确定"何时启动 iTROS 云连接"（可能由 turing 响应、OTA、或唤醒流程触发）
3. 若能让设备连到我们自建 TCP 服务，即可用 §31.3 协议直接下发 alarm_set/alarm_del

---

## 33. 2026-10-27 17:00 —— 双通道与模式开关排查

### 33.1 固件内共存两套云通道
| 通道 | 地址/协议 | 状态 |
|---|---|---|
| 图灵 MQTT | `type/message` 格式 | ✅ 设备在用 |
| iTROS | `ws://ws-api.turingapi.com/api/v2`、`smartdevice.ai.turingapi.com`、`fromUser/optValue` 格式 | ❌ 从未启动 |

- `ws://ws-api.turingapi.com/api/v2` @0x2183A8B，唯一引用 **0x201950A**（即 WWS 初始化函数 0x201944A 内部）
- `smartdevice.ai.turingapi.com` @0x21827E3，引用 0x2022B6E（连接函数 0x2022AC8 内）
- `http://api.turingos.cn/activateDevice`、`iot.turingos.cn` 存在

### 33.2 任务创建链
```
app_iTROS_task 任务（名字串 "app_iTROS_task" 基址0x217E530+9583）
  创建于 0x200DBFA（函数 0x200DBBC）
     ← 0x200E7F8（函数 0x200DD2C）
     ← 0x20259A6（函数 0x2025922）
  两者上游 → 函数 0x2042954（有 1 调用点 0x2043536，指针引用 0x21E6804）
```

### 33.3 实测：设备不发起 iTROS 连接
- DNS 劫持 `smartdevice.ai.turingapi.com` → 本机，监听 80 端口
- 按键/唤醒/重启后：**DNS 无查询、TCP 无连接**

### 33.4 配置区检查
flash 0x7B3000-0x7BF000 仅见 WiFi 凭据/apiKey/deviceId/MQTT 参数，未发现云端模式开关字段

### 33.5 结论
iTROS 通道（含 alarm_set/alarm_del）在本固件中虽被编译进来，但**运行时不启动**；
需要找到"是否启用 iTROS"的判定条件（可能在产品配置/激活流程中），否则只能走固件补丁。

---

## 34. 2026-10-27 18:00 —— ★★★ 设备本机闹钟协议完全打通（无需刷固件）★★★

### 34.1 成功实测
通过 MQTT 下发，设备屏幕逐条正确执行：
```
push {"hb":[]}                                  → 全部删除 ✓
push {"hb":[{07:00 起床}]}                       → 更新为 1 条 7:00 ✓
push {"hb":[{07:00},{18:30 学习}]}               → 变为 2 条 ✓
```

### 34.2 完整协议（权威）
MQTT topic: `iot/<apiKey>/<deviceId>`
```json
{
  "fromUser": "o0LocalOpenid0000000000000000",
  "optCode": 1003,
  "optValue": {"hb": [
      {"na":"名称","th":7,"tm":0,"sd":<unix秒>,"wk":63,"wt":0,"s":1}
  ]},
  "repeat": 1,
  "reset": 0
}
```
- **optCode 必须是数字**，1001~1010 跳转表；**1003 = 设置闹钟整表**
- 1001/1002/1004/1005 为其它功能（实测不影响闹钟）
- `optValue.hb` 是**完整列表**（整表替换语义）
- 字段：na=名称, th=时, tm=分, sd=起始时间(unix秒), wk=星期掩码(63=每天,bit0=周一), wt=?, s=开关(1开)

### 34.3 固件内部链路（逆向确认）
```
MQTT 回调 0x2038DFA
  → 0x2038802 消息处理: cJSON_Parse(0x200843A) → 读 fromUser/optCode/optValue
  → r7 = optCode-1001; tbh[r7*2] 跳转表 @0x2038876
  → optCode 1003 → 0x20389BE
       cJSON_PrintUnformatted(optValue)
       → 0x200CCA6 解析 {"hb":[...]}（字段 hb/th/tm/na/sd/wk/wt/s）
       → 0x20389F0 call 0x200722A = BES 保存（写 flash bank）
```

### 34.4 交付物
- 路由器新增接口：
  - `GET  /api/dev/list` 读取列表
  - `POST /api/dev/add {hour,minute,name,enabled,weekday}` 添加并下发
  - `POST /api/dev/del {index}` / `POST /api/dev/toggle {index}`
  - `POST /api/dev/clear` / `POST /api/dev/push`
- 网页（手机可用）：**http://192.168.6.1:8898/dev-alarms**
- 列表持久化：`/koolshare/chatrabbit/dev_alarms.json`

### 34.5 结论
**原厂固件原生支持 App 设置/删除本机闹钟**，只需 MQTT 下发上述 JSON。
无需刷机、无需改固件、无需拆机。

### 34.6 旧结论修正
- 之前"闹钟命令只挂在 iTROS 通道"是**错误**的：MQTT 回调同样进入 0x2038802，只是消息结构（optCode 数字 + optValue.hb）一直没对上。
- `0x200843A` = cJSON 解析器，确实是 MQTT 消息解析入口。

---

## 35. 2026-10-27 19:30 —— 控制协议 + 铃声接口 + Android App

### 35.1 设备控制操作码（固件 turing_mqtt_cmd.c 还原）
消息格式：`{"type":2,"message":{"operate":N}}`
| operate | 功能 |
|---|---|
| 0 | 音量减 |
| 1 | 音量加 |
| 2 | 关灯 |
| 3 | 开灯 |
| 4/5 | 低功耗开/关 |
| 6 | 格式化存储 |
| 7 | 查询状态 |
| 8 | 设时钟 |
| 9 | 打电话 |
| 0xA | 睡眠时间 |
| 0xB | 设置 |
| 0xC | 关闭播放 |

### 35.2 路由器新增接口（已部署验证）
| 接口 | 说明 |
|---|---|
| `POST /api/ctl {operate}` | 设备控制（音量/灯光） |
| `POST /api/ringtest` | 立刻播放当前铃声 |
| `POST /api/ringtone/upload?name=x.mp3`（原始字节） | 上传自定义铃声音频，保存为 custom_ring.mp3 并设为 alarm_voice |
| `POST /api/dev/add {...,onceDate:"yyyy-MM-dd"}` | 一次性闹钟（sd=该日 00:00, wk=0） |
| `GET/POST /api/dev/list /api/dev/del /api/dev/toggle /api/dev/clear /api/dev/push` | 设备闹钟列表管理 |

实测：
- 音量加/关灯/开灯/响铃 均返回 ok
- 铃声上传 29675 字节 → custom_ring.mp3 → alarm_voice 切换成功

### 35.3 Android App
派队友 `android-dev` 在 `chat rabbit\android\` 创建 Android 16 (targetSdk 36) 工程：
Kotlin + Compose，含闹钟列表/一次性闹钟/铃声选择上传/音量灯光控制/设置页。
本机无 Android SDK，交付源码由用户用 Android Studio 构建。

---

## 36. 2026-10-27 20:05 —— 控制能力实测结论 + App 接口定稿

### 36.1 音量/灯光控制：固件未实现（已排除）
测试方式（全部返回 ok 但设备无反应）：
- `{"type":2,"message":{"operate":1}}` 音量加 ×10 → 音量无变化
- `{"type":2,"message":{"operate":0}}` 音量减 ×6 → 无变化
- `operate=2/3` 关灯/开灯 → 屏幕/灯光无变化（用户确认非熄屏）
- 变体测试：operate 字符串、arg+operate、fromUser、type=3、category、vol 字段 → 均无效
- 日志确认指令已入队下发（`[ctl] operate=N`）

**结论**：厂商固件裁掉了 turing SDK 的 control 通道（`$mqtt_message_control`），只保留音频播放。

### 36.2 唯一可用的设备控制 = 音频
- `{"type":1,"message":{"operate":1,"url":...}}` 播放音频 ✅
- 响铃测试播放 custom_ring.mp3 ✅（用户听到"闹钟时间到了，起床了"）
- 音量的替代方案：在**上传铃声前**调整音频响度（App/PC 端处理），或改用更响的铃声文件

### 36.3 App 最终接口契约（全部实测）
| 接口 | 状态 |
|---|---|
| GET /api/dev/list | ✅ |
| POST /api/dev/add（含 onceDate 一次性） | ✅ |
| POST /api/dev/del /toggle /clear /push | ✅ |
| POST /api/ringtest | ✅ 设备响铃 |
| POST /api/ring {"seconds":10..120} | ✅ |
| POST /api/ringtone/upload?name=x.mp3 | ✅ |
| GET /api/files | ✅ {"files":[...]} |
| GET /api/status | ✅ |
| POST /api/ctl | ⚠️ 接口存在但设备无响应（保留但 App 不用） |

### 36.4 App 功能定稿
- 闹钟列表 + 一次性闹钟 + 每天/星期几
- 本地精确闹钟（AlarmManager + 全屏通知 + 前台服务响铃）
- 铃声：SAF 选文件 → 上传设备 + 本地响铃
- 设备页：响铃测试、设备状态、响铃时长、当前铃声
- 设置页：路由器地址/权限

---

## 37. 2026-10-27 21:00 —— Android App 编译成功（APK 交付）

### 37.1 产物
```
dist\ChatRabbitAlarm-1.0.0-debug.apk   11.13 MB
包名: com.chatrabbit.alarm  版本: 1.0.0
compileSdk 36 / targetSdk 36 / minSdk 26（Android 16 规范）
应用名: 聊天兔闹钟
```

### 37.2 本机构建环境（已装好，可复用）
```
JDK 17:      C:\Program Files (x86)\Android\openjdk\jdk-17.0.14
Android SDK: C:\AndroidSdk （platforms;android-36, build-tools;36.0.0, platform-tools）
Gradle 8.13: C:\AndroidSdk\gradle-8.13
AGP 8.12.2 / Kotlin 2.1.20 / Compose BOM 2025.06.01
构建命令:
  set JAVA_HOME=<短路径 JDK17>
  set ANDROID_HOME=C:\AndroidSdk
  C:\AndroidSdk\gradle-8.13\bin\gradle.bat -p "E:\Claude official\chat rabbit\android" assembleDebug --no-daemon
```

### 37.3 功能
- 闹钟列表：增删改开关，实时同步到玩具（全量重建）
- 一次性闹钟（指定日期）、每天、指定星期
- 本地精确闹钟：setAlarmClock + 全屏通知 + 前台服务响铃（USAGE_ALARM）
- 铃声：SAF 选择音频 → 上传到路由器 → 设备响铃；本地响铃同 URI
- 设备页：响铃测试、响铃时长 10/20/30/60s、当前铃声、设备在线状态
- 设置页：路由器地址、连接测试、三项权限引导
- 限制说明：玩具固件不支持远程音量/灯光（已实测排除），App 中已注明

### 37.4 已静态+构建校验
- Gradle 真实编译通过（BUILD SUCCESSFUL in 12m3s，37 tasks）
- APK aapt2 校验：targetSdk 36、权限齐全、label 正确
- 仅 1 条 Kotlin 弃用警告（Icons.Filled.ArrowBack → AutoMirrored），不影响运行
