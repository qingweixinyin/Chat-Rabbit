# 杰理 AC791N（chat rabbit）固件 bank 结构 + OTA 响应解析结论

> 分析对象：`chat rabbit-puya.bin`（8 MiB 完整 flash dump）与已解密明文 `dev_app_plain.bin`
> 分析方式：纯静态（未执行固件、未连接设备、未联网）
> 所有中间产物在 `work\bank\` 目录
> 日期：2026-10-26

---

## 0. 结论速览（先看这段）

| 问题 | 结论 | 置信度 |
|---|---|---|
| 设备固件是"多 bank bankcb 容器（23×32B 描述符）"吗？ | **不是**（至少本镜像不是）。设备镜像是标准 **JLFS 文件系统**：flash 头 + 5 个 32 字节文件条目；"app 区"是其中两个 **A/B 双槽**（每个槽 = 9 条目 JLFS 子目录 + app.bin + cfg_tool.bin，各 0x1ED423 字节）。**没有** 736 字节/23×32B 描述符表这样的结构 | 高（结构逐字段解析 + 与 VM 区实际内容吻合 + A/B 两槽独立验证） |
| 之前"字符串在另一个 bank、找不到引用"的原因？ | **地址基址算错了 0x20 字节**。正确映射是 `运行时地址 = 0x2000100 + dev_app_plain.bin 偏移`（不是 0x2000120）。按正确基址，`firmwareUrl` 等字符串**都有绝对引用** | 高（两处独立字符串验证） |
| 云端 OTA 响应必须包含哪些 JSON 字段？ | 顶层必须有 `payload` 对象；`payload` 内**必须**有 `firmwareUrl`（下载地址）；**很可能**还需要 `net_upgrade`(1) 与版本号 `softVer`（新版本必须高于设备当前 `softVersion`，否则日志 `[updata]softVer is low` 直接放弃） | 字段名 高 / 结构 高 / 必需性 中高 |
| 厂商固件还需要 SDK 的 `updateStatus/agree/nextVersion` 吗？ | **固件里根本不存在这三个字符串**（`updateStatus`=0、`nextVersion`=0、`agree`=0 次命中）→ 厂商解析器不是 SDK 那套。建议"超集"发送（多给不报错） | 高（字符串全量检索） |

---

## 1. Flash / 文件系统结构（顶层）

`chat rabbit-puya.bin` 开头是标准 JLFS2（与 `datafmt_jlfs.md` 逐字段吻合）：

```
0x000000  JLFS flash header (32B)
          hdr_crc=0xD36C  burner_size=0x1DBF  vid="1.00"  flash_size=0x67DC31F0
          fs_ver=0xDE block_align=0xAD resvd=0xFF special_opt=0x68 pid="AC79_PUBLIC"
0x000020  JLFS 文件条目表（每 32B 一条，index 非 0 表示最后一条）
0x0000C0  uboot.boot         data 0x3588
0x0003648 isd_config.ini     data 0x2BB
0x0004020 app_dir_head       data 0x4000   attr=0x81  ← app 区 A（槽 A）
0x03DB020 app_dir_head2      data 0x3DB000 attr=0x81  ← app 区 B（槽 B）
0x07FF000 key_mac            data 0x1000   attr=0x12
```

顶层文件条目（用「单元 LFSR 流、初值 key=0xFFFF」解出，明文正确、字段自洽）：

| # | 条目地址 | 名字 | data offset(flash) | size | attr | index |
|---|---|---|---|---|---|---|
| 0 | 0x020 | `uboot.boot` | 0xC0 | 0x3588 | 0x00 | 0 |
| 1 | 0x040 | `isd_config.ini` | 0x3648 | 0x2BB | 0x02 | 0 |
| 2 | 0x060 | `app_dir_head` | 0x4020 | 0x4000 | 0x81 | 0 |
| 3 | 0x080 | `app_dir_head2` | 0x3DB020 | 0x3DB000 | 0x81 | 0 |
| 4 | 0x0A0 | `key_mac` | 0x7FF000 | 0x1000 | 0x12 | 0xFFFF |

> 注：条目里 `app_dir_head` 的 size=0x4000 只是"目录保留区"大小，真正的 app 区大小在其子目录的
> `app_area_head` 条目里（0x1ED423）。

### 1.1 app 区（槽 A / 槽 B）内部结构 —— 这就是"bank 表"

`app_dir_head`(flash 0x4020) 的内容是一个 **JLFS 子目录**，第一条就是 `app_area_head`，
它给出 **加载地址（运行时基址）**：

| # | +off | 名字 | off 字段 | size | attr | 备注 |
|---|---|---|---|---|---|---|
| 0 | 0x000 | `app_area_head` | **0x02000120** (运行时地址) | **0x001ED423** | 0x83 | 整个 app 区的描述符 |
| 1 | 0x020 | `app.bin` | 0x00000120 (相对目录头) | 0x1ED184 | 0x82 | 代码，运行时 0x2000120 |
| 2 | 0x040 | `cfg_tool.bin` | 0x001ED2A4 | 0x17F | 0x82 | 配置工具 |
| 3 | 0x060 | `VM` | 0x007B3000 (绝对 flash) | 0x8000 | 0x12 | VM/键值区 |
| 4 | 0x080 | `BTIF` | 0x007BB000 | 0x1000 | 0x92 | 蓝牙配对信息 |
| 5 | 0x0A0 | `PROFILE` | 0x007BC000 | 0x1000 | 0x92 | VM 镜像副本 |
| 6 | 0x0C0 | `WKS` | 0x007BD000 | 0x1000 | 0x92 | 唤醒词/令牌 |
| 7 | 0x0E0 | `EXIF` | 0x007BE000 | 0x1000 | 0x92 | |
| 8 | 0x100 | `BES` | 0x007BF000 | 0x40000 | 0x92 | 设备状态 JSON（闹钟等） |

- 目录区共 9 条 = 0x120 字节，紧跟其后就是 `app.bin` 数据（目录头 + 0x120）。
- 槽 A：flash **0x4020**，明文区间 0x4020..0x1F1443（= 0x1ED423）。
- 槽 B：flash **0x3DB020**，结构完全相同、同样映射到运行时 0x2000000（典型 A/B 双槽 OTA）。
- 这两个槽就是 **OTA 写入目标**：升级会写另一槽，再切换启动。
- VM 区解析结果与 `REPORT.md §3` 完全对得上（VM@0x7B3000、MAC@0x7BB000、副本@0x7BC000、
  token@0x7BD000、闹钟 JSON@0x7BF000）——反向验证了本表正确。

**槽 A 与槽 B 的代码不是逐字节相同**（约 56.7% 字节一致），但字符串池/布局完全一致，
且版本串同为 `AC79NN_V1.0.319_UKITOS_240905`；推测为两次编译产物（rebuild）而非两个版本。

---

## 2. ENC 加密模型（最终版，已完全解释此前所有矛盾）

1. **每个 JLFS 数据单元（文件/目录区）独立加扰**，同一单元内连续。
2. 单元内：块大小 32 字节；设单元 flash 起点 `S`、运行时装载地址 `L`，
   对 flash 地址 `F` 所在块：
   ```c
   key_idx = ((L + (F - S)) >> 2) & 0xFFFF;      // 每 32B 步进 8
   key     = 0xFFFF ^ key_idx;
   for (j = 0; j < 32; j++) { data[j] ^= key & 0xFF;
                              key = (key<<1) ^ ((key & 0x8000) ? 0x1021 : 0); }
   ```
3. 验证：`dev_app_plain.bin` 起于 flash 0x4120 = 槽 A 偏移 0x100，
   `key_idx = ((0x2000000 + 0x100) >> 2) & 0xFFFF = 0x40` —— 正是此前经验值 `p=0x40`。
   全 2,020,387 字节逐块反解密钥流，相位严格线性（`idx = 0x40 + 8*block`），**单一单元确认**。
4. 由相位反推各区域装载基址（"bank"实质 = 各加密单元的运行时基址）：

| flash 区间 | 单元 | 运行时基址（由相位反推） | 说明 |
|---|---|---|---|
| 0x4020..0x1F1443 | app_area_head (A) | **0x2000000** (app.bin @0x2000120) | 代码主体 |
| 0x3DB020..0x7xxxxx | app_area_head (B) | **0x2000000** | 第二槽 |
| 0x7B3000..0x7FF000 | VM/BTIF/PROFILE/WKS/EXIF/BES | 特殊区（多为明文或独立单元） | 状态区 |
| 其它（0x1F1543..0x2CD000、音频资源等） | 各自文件单元 | 各自地址 | 音频/模型资源 |

---

## 3. ⚠️ 重要修正：运行时地址基址是 0x2000100，不是 0x2000120

之前 `wrap_elf2.py` 把 `dev_app_plain.bin` 映射到 ELF 虚拟地址 **0x2000120**，高了 0x20 字节。
正确关系（两条独立证据）：

| 字符串 | dev_app_plain 偏移 | 代码里引用的地址 | 差值 |
|---|---|---|---|
| `seceneCodes` | 0x17FFE0 | 0x21800E0 | 0x2000100 |
| `firmwareUrl` | 0x180370 | 0x2180470 | 0x2000100 |

即：
```
运行时地址 = 0x2000100 + dev_app_plain.bin 偏移
flash 地址  = 0x4120     + dev_app_plain.bin 偏移
⇒ 运行时地址 = flash + 0x1FFBFE0
```
`dev_app_plain.bin + 0x20` 才是 app.bin 的真正起点（运行时 0x2000120，与 `sdk.ld` 的
`rom ORIGIN = 0x2000120` 一致）。

**这正是此前"字符串找不到引用"的唯一原因**：按 0x2000120 基址去搜 `0x2180490`，
而代码里实际引用的是 `0x2180470`（差 0x20 = 一个 32B 块）。
按正确基址复查：`firmwareUrl`、`net_upgrade`、`softVersion`、`upgradeMode`、`hardVersion`
等**全部都有代码引用**（见 §5）。

> 建议：以后重跑反汇编时把 `wrap_elf2.py` 的 `.text sh_addr` 改成 0x2000100
> （或把明文前面补 0x20 字节 0 再按 0x2000120 映射），xref 才会全部对齐。

---

## 4. 厂商固件的版本/来源（顺带发现，可用于版本比对）

明文中的编译期路径串（拷贝 B 中被代码直接引用）：

```
D:\liang_work_2024\qingning\T001\code_1v2\AC79NN_V1.0.319_UKITOS_240905\ukit_sdk\...
   ukitapp\app_root.c, ukitapp\iTuRing\app_iTROS.c, ukitapp\record\app_record.c,
   ukitdrv\drv_rtc\drv_rtc.c, ukitdrv\drv_misc\drv_misc.c, ukitcommon\bps\bps_flash.c,
   ukitsys\src\ukit_listitem.c, ukitlib_src\NingJu\app_nj_flash.c
```
- 固件版本：**AC79NN V1.0.319 / UKITOS / 2024-09-05**（厂商：`NingJu`、`qingning`）
- 云侧：`iot-ai.turingapi.com`（图灵 IoT），旧 OTA 还硬编码了 `120.76.63.67:7890`

---

## 5. OTA 相关字符串全表（偏移 → 运行时地址）

（`flash off = 0x4120 + 文件偏移`；`va = 0x2000100 + 文件偏移`）

| dev_app_plain 偏移 | 运行时 va | 内容 |
|---|---|---|
| 0x17FFA4 | 0x21800A4 | `upgradeMode` |
| 0x17FFB0 | 0x21800B0 | `hardVersion` |
| 0x17FFBC | 0x21800BC | `softVersion`（= 日志里 `softVer` 的前缀） |
| 0x17FFC8 | 0x21800C8 | `cmd_upgrade` |
| 0x180364 | 0x2180464 | `net_upgrade` |
| 0x180370 | 0x2180470 | `firmwareUrl` |
| 0x180490 | 0x2180590 | `http_get_url` |
| 0x180A62 | 0x2180B62 | `AC79NN_OTA.ini` |
| 0x180A71 | 0x2180B71 | `AC79NN_OTA.ufw` |
| 0x180A8F | 0x2180B8F | `currentVersion` |
| 0x180ACB | 0x2180BCB | `update-ota.ufw` |
| 0x1836F7 | 0x21837F7 | `\n(%s)([err]L%d):payload is null\n`（完整格式串起点） |
| 0x183EA9 | 0x2183FA9 | `\n(%s)([err]L%d):[payload]is null\n` |
| 0x183D55 / 0x183D77 | 0x2183E55 / 0x2183E77 | `... upgradeMode null` / `... hardVersion null` |
| 0x186154 | 0x2186254 | `\n(%s)([err]L%d):[net_payload]is null \n` |
| 0x187F53 | 0x2188053 | `\n(%s)([err]L%d):[OTA]get firmwareUrl FAIL\n` |
| 0x188760 | 0x2188860 | `... [OTA]get flashVersion FAIL` |
| 0x188F73 | 0x2189073 | `\n(%s)([err]L%d):[updata]softVer is low:%s=%s\n` |
| 0x1891C9 | 0x21892C9 | `... result_json Create JSON fail` |
| 0x1891F7 | 0x21892F7 | `... PayloadJson Create JSON fail` |
| 0x1893C3 | 0x21894C3 | `\n(%s)([err]L%d):[OTA]net_firmware_check FAIL\n` |
| 0x1893F1 | 0x21894F1 | `\n(%s)([err]L%d):[OTA]get firmwareUrl is FAIL\n` |
| 0x18A609 | 0x218A709 | `http://iot-ai.turingapi.com/iot/v2/ota/initVersion` |
| 0x18A5D6 | 0x218A6D6 | `http://iot-ai.turingapi.com/app-author/bind_status` |
| 0x18C1B6 | 0x218C2B6 | `http://120.76.63.67:7890/robot/flash_download.form?token=` |
| 0x18BDA8 | 0x218BEA8 | `http://120.76.63.67:7890/sdk/auth.form?secret=%s&uuid=%s` |

> 说明：日志类字符串在池里是**一条完整格式串**（以 `\n(%s)([err]L%d):` 开头），
> 上表给的是该完整串起点；代码引用的是整串地址。

**关键否定结论**：全镜像检索 `updateStatus` = 0 次、`nextVersion` = 0 次、`agree` = 0 次。
→ 厂商固件的 initVersion 响应解析**不是** SDK v1.2.0 的 `ota_update_version_info()` 那套。

---

## 6. 已定位的 OTA 相关代码（拷贝 B 反汇编，`unitB_disasm.txt`）

> 厂商代码用「池基址寄存器 + 偏移」访问字符串：先把 `r13 = 0x217E530`（字符串池基址）装入，
> 再 `rX = r13 + 0xNNN`。这就是"直接搜绝对立即数搜不到"的第二个原因（尤其日志串）。

| 地址 | 行为 |
|---|---|
| **0x20CCADA** | **OTA 固件下载执行器**：`cfg_get("upgrade")`（引用串 0x217F63C）→ 值==1 → `cfg_get("firmwareUrl")`(0x2180470) → `r7=[r0+16]` 取出字符串 → memcpy 到缓冲；失败打 `[OTA]get firmwareUrl FAIL`（引用点 0x20CCD14）。随后交 HTTP 下载（`http_get_url`） |
| 0x20CCB56 | 用 `http://120.76.63.67:7890/robot/flash_download.form?token=` 拼旧 OTA 下载请求 |
| 0x20CD302 | 用 `http://120.76.63.67:7890/sdk/auth.form?secret=%s&uuid=%s` 做题权 |
| 0x200B244 | 以 `net_upgrade`(0x2180464) 为名注册下载任务（`call 0x4004A62`，r1=5/r2=2048 栈），并引用 0x20CCADA |
| 0x2037B10 | `/iot/v2/ota/initVersion` **请求**构造与状态机：`app_task_handler+0x1E4`=取 apiKey、`0x201DD5C`=取 deviceId、`app_version_check+0x26`=取版本；snprintf 成 body 后 POST；响应回调走 0x4003976 |
| 0x2037C24.. | 响应分派：`switch` 状态 1..7（`tbh` 跳转表）→ 各 case 处理 |
| 0x2008594 | 从 syscfg 读 `upgradeMode`(0x21800A4)/`hardVersion`(0x21800B0)/`softVersion`(0x21800BC) → 组装版本上报串 |
| 0x2034000 区 | uALib/cJSON HTTP 通用 JSON 响应解析层（`[ukit_cJSON_Parse]fail`、`PayloadJson/result_json Create JSON fail`、`payload is null` 引用点 0x2034646） |
| 0x20D5xxx | 读 `readprofile`/`device_info`/`engine_type`/`active_time`（设备属性上报） |

### 6.1 关键反汇编片段（拷贝 B，`unitB_disasm.txt`）

**(a) 槽 A 目录已独立验证**（flash 0x4020 按 phase=0 解密，9 条目与槽 B 完全一致，
仅 `hdr_crc`/`data_crc` 不同 → 证明两槽同构、同装载地址 0x2000000）。

**(b) "firmwareUrl 被作为配置键读取"（0x20CCB76..0x20CCBE8）**：

```
20ccb76: r1 = r13 + 1581          ; r13 = 0x217E530 (字符串池基址) → 键 A
20ccb7e: r5 = 0x2182540
20ccb88: r3 = [r0+20]
20ccb8a: if (r3 != 200) ...       ; 类型校验
20ccb8e: r1 = r13 + 1586          ; 键 B
20ccb9e: r1 = r13 + 4364          ; → 0x217F63C = "upgrade"
20ccba2: call <cfg_get>
20ccbaa: r3 = [r0+20]
20ccbac: if (r3 != 1) ...         ; upgrade 必须 == 1
20ccbb0: r1 = r13 + 8000          ; → 0x2180470 = "firmwareUrl"   ★
20ccbb4: r0 = r7
20ccbb6: call <cfg_get>           ; 读 firmwareUrl
20ccbba: if (r0 == 0) goto ...    ; 读失败
20ccbbe: r7 = [r0+16]             ; value 指针
20cccbe: ...
20ccbdc: r2 = r0 + 1              ; strlen+1
20ccbe2: call <memcpy>            ; 把 URL 拷到缓冲
```
同函数内失败分支引用 `\n(%s)([err]L%d):[OTA]get firmwareUrl FAIL\n`（0x2188053，引用点 0x20CCD14）
→ **firmwareUrl 这个键名是"配置键/JSON 键"双重角色**，云端响应里必须有它。

**(c) 版本比较相关日志**（存在但未定位到确切比较指令）：

```
\n(%s)([err]L%d):[updata]softVer is low:%s=%s\n     (0x2189073)
\n(%s)([err]L%d):[OTA]net_firmware_check FAIL\n     (0x21894C3)
\n(%s)([err]L%d):[OTA]get firmwareUrl is FAIL\n     (0x21894F1)
```

**数据流（从代码结构推断，置信度高）**：

```
POST /iot/v2/ota/initVersion   (apiKey, deviceId, version)
        ↓ 响应 JSON
  解析 payload（"payload is null" 检查）→ 取 firmwareUrl / net_upgrade / softVer
        ↓ 写入 VM(syscfg)
  VM 键: upgrade=1, firmwareUrl=<url>
        ↓
  任务 "net_upgrade" 启动 → 函数 0x20CCADA
        ↓ 读 VM(upgrade, firmwareUrl)
  http_get_url(firmwareUrl) → 校验(softVer 必须更高, "[updata]softVer is low")
        ↓
  写另一个 A/B 槽(0x3DB020 或 0x4020, 0x1ED423 字节) → 复位
```

---

## 7. ★ 云端 OTA 响应必须包含的 JSON 字段（最终结论）

### 7.1 结构

必须是 **两层**：顶层 `payload` 对象（固件里明确判空 `payload is null`；另有 `net_payload` 次级判空）。
字段名是**驼峰**、大小写敏感（`firmwareUrl`、`net_upgrade`、`softVer`）。

### 7.2 必需字段（按置信度）

| 字段 | 层级 | 类型 | 置信度 | 依据 |
|---|---|---|---|---|
| `payload` | 顶层 | object | **高** | 字符串 `payload is null` + `[payload]is null` 被解析器引用 |
| `firmwareUrl` | payload | string | **高** | 独占字符串 + VM 键 + 下载器读取点(0x20CCBB0) + 失败日志 `[OTA]get firmwareUrl FAIL` |
| `net_upgrade` | payload | int/1 | 中高 | 独占字符串，被下载任务注册处引用(0x200B244) |
| `softVer` | payload | string | 中高 | 版本比较日志 `[updata]softVer is low:%s=%s`（新版本必须 > 设备 `softVersion`，否则放弃） |
| `updateStatus` / `agree` / `id` / `nextVersion` / `currentVersion` | payload | - | **低（厂商固件无这些串）** | 但 SDK v1.2.0 源码 `turing_iot.c:607-653` 强制要求；为兼容建议一并带上 |
| `fileSize` / `md5` | payload | - | 低/未证实 | `FileSize` 只出现在日志格式串里；`md5` 只出现在 mbedTLS |

### 7.3 建议下发的响应（超集，最稳）

```json
{
  "payload": {
    "updateStatus": 0,
    "agree": 1,
    "id": 12345,
    "net_upgrade": 1,
    "softVer": "1.0.320",
    "nextVersion": "1.0.320",
    "currentVersion": "<设备当前版本>",
    "firmwareUrl": "http://<host>/update.ufw",
    "fileSize": <ufw 字节数>,
    "md5": "<ufw 的 md5 hex>"
  }
}
```

要点：
1. `softVer` 必须**大于**设备 VM 里的 `softVersion`（否则 `[updata]softVer is low` 直接跳过）。
2. `firmwareUrl` 指向的必须是 **合法 `.ufw`**（文件名/格式参照 `AC79NN_OTA.ufw`）；
   固件会校验 ufw 头（`net_firmware_check`）。
3. `updateStatus` 只应在"检查版本"响应里给 **0**；`agree`=1。
4. 若设备仍不下载，优先怀疑：① `net_upgrade` 缺/不为 1；② `softVer` 未高于当前；
   ③ URL 不是 `.ufw` 或 IP/端口不可达；④ 设备把 URL 写进 VM 后需要**重启/任务轮询**才触发。

---

## 8. 仍未确定 / 待验证

1. **厂商解析器读到 `firmwareUrl` 后是如何写进 VM 的**：我没能定位到"从 JSON 取 firmwareUrl → `syscfg_bin_write("firmwareUrl")`"的那条指令。
   可能经由通用 key/value→VM 映射表（键名以池基址+偏移比较，或表在 RAM 中动态构建）。
   但 `firmwareUrl` 作为 VM 键 + 下载器读取 + 失败日志是**确定的**，所以响应里必须有该字段这一点不受影响。
2. `net_upgrade` 的确切语义（开关或版本号）未定；仅在任务注册处见到引用。
3. `softVer` vs `softVersion` 哪一个是响应字段、哪一个是 VM 键：两者同一个字符串池前缀，
   `[updata]softVer is low` 的日志说明比较逻辑确实存在，但字段名可能为 `softVer`（日志用）或 `softVersion`（VM 键）。
   **建议两个都发**（`softVer` 与 `softVersion` 同值），成本为零。
   附注：VM 区（0x7B3000，魔数 `55 AA AA 55`）是**数字 ID 键值对**，不存键名，
   所以无法从 dump 里直接读出设备当前 `softVersion` 字符串；需按 ID 解 VM 或直接实测。
4. 拷贝 A / 拷贝 B 谁是当前运行镜像未定（两者都映射到 0x2000000，代码有 rebuild 差异）；
   对字段名结论无影响，但如果要做补丁，需要确认运行槽（可对比启动后 VM 内容或代码校验和）。
5. `dev_app_plain.bin` 只覆盖槽 A 的 0x1ED423 字节；槽 B 我已解出（`work\bank\unitB.bin`，2.9 MB，
   flash 0x3DB020..0x6A0000），可直接复用分析。

---

## 9. 复跑 / 产物清单（`work\bank\`）

| 文件 | 说明 |
|---|---|
| `01_ks_probe.py` | 由 flash XOR 明文反解密钥流，验证相位线性 |
| `04_jlfs_table.py` | 解密 JLFS 头 + 文件条目表（单元 LFSR 模型） |
| `23_copies.py` | 解密/对比 A、B 两个 app 区 |
| `24_phase_map.py` | 大图模型扫描各区域加密相位 → 加密单元图 |
| `25_copyB_compare.py` / `29_wrapB.py` | 解出槽 B 全量明文 + 打包 ELF 反汇编 |
| `31_base_offset_refs.py` | 「池基址+偏移」字符串引用扫描（关键工具） |
| `36_log_refs.py` | 指定 OTA 日志串的精确定位与引用点 |
| `unitB.bin` | 槽 B 解密明文（0x3DB020..0x6A0000） |
| `unitB.elf` / `unitB_disasm.txt` | 槽 B 的可反汇编 ELF 与 52 MB 反汇编 |
| `xrefs_str.txt` / `xrefs_exact.txt` / `refs_ascii.txt` | 引用表（早期版本，基址未修正，仅供参考） |

反汇编工具链不变：`C:\JL\pi32\bin\llvm-objdump.exe` + SDK 的 `cpu\wl82\tools\sdk.elf` 作模板
（**注意本节 §3 的基址修正：.text sh_addr 用 0x2000100**）。

---

## 10. 补充（2026-10-26 第二轮）：升级触发调用链 —— 为什么 initVersion 响应不触发下载

### 10.1 结论（先看）

| 问题 | 答案 |
|---|---|
| 谁写 `"upgrade"`/`"firmwareUrl"`？ | **没有任何代码把它们写进 VM/持久化配置**。它们不是持久键，而是**HTTP 响应 JSON 解析出的临时 key-value 树**里的字段。全镜像对 `"firmwareUrl"` 只有 1 处引用（就是读取它的下载器）；`"upgrade"` 只有 3 处引用（检查函数、轮询主循环、下载器），全是"读"，没有"写"。 |
| initVersion 响应会被解析吗？ | 会，但只由 turing IoT 协议层用 `code` + `payload`（account/password/topic/clientId/endpoint/desc）处理 **MQTT 连接信息**；**不涉及任何 OTA 字段**，也没有任何路径连到下载任务。且固件里**不存在** `/iot/v2/ota/version` 字符串（SDK 的版本检查端点），也没有 `updateStatus/agree/nextVersion` → initVersion 只是版本上报。 |
| 真正的 OTA 触发链 | **老版图灵 "robot" 接口（硬编码 IP `120.76.63.67:7890`）**：app 主循环 → `net_check_upgrade()` 同步 GET `/robot/flash_check.form?token=...` → 解析 JSON → 要求 `code==200` 且 `body.upgrade==1` → 创建任务 **"net_upgrade"**（入口 0x20CCADA）→ 该任务 GET `/robot/flash_download.form?token=...` → 解析 JSON → 要求 `code==200`、`body.upgrade==1`、`body.firmwareUrl` 为非空字符串 → 才真正下载该 URL。 |
| 有 MQTT 触发升级吗？ | 没有发现。引用 `"upgrade"` 的函数全部属于上述老接口模块；MQTT 命令分发不引用它。`cmd_upgrade` 是 netdl 下载库的内部命令名，不是 MQTT 命令。 |
| 版本比较用什么？ | check 响应里的 `body.flashVersion`（字符串）被读出；设备自身版本串来自 cfg `softVersion`/`hardVersion`/`upgradeMode`（读点 0x2008594）。"新版本太低"只体现在日志 `[updata]softVer is low:%s=%s`（0x2189073）。 |

### 10.2 关键地址与调用链（槽 B 反汇编，运行时地址）

```
[app 主任务]  0x200A15C (含 0x200A7D4 段)  —— 周期性循环：
    0x200B21A  call 0x2003EE0            ; 事件/锁
    0x200B21E  r0 = 300 ; call 0x4003446 ; os_sleep(300)
    0x200B228  r0 = [net_ctx+948]        ; 网络上下文，未连网则跳过
    0x200B22E  call 0x2009194            ; net_check_upgrade()
    0x200B232  if (r0 != 0) 继续循环      ; 返回 0 表示"有升级"
    0x200B23E  entry = 0x20CCADA
    0x200B244  name  = "net_upgrade"
    0x200B252  call 0x4004A62            ; 创建任务(prio5, stack 2048)

[net_check_upgrade]  0x2009194
    0x20091E4  sprintf(url, "%s%s%%7C%s%%7C%s",
                        "http://120.76.63.67:7890/robot/flash_check.form?token=", <ctx字段>, <ctx字段>)
    0x20091F8  call 0x20090AA            ; 同步 HTTP GET → 响应体写入缓冲（760B ctx + 读 body）
    0x20091FE  call 0x200843A            ; 响应体 JSON → key-value 树，返回 root
    0x2009206  cfg_get(root, "code")     → 要求 node->int == 200
    0x200921C  body = cfg_get(root, "body")
    0x2009228  cfg_get(body, "upgrade")  → 要求 node->int == 1
    返回 0 = 有升级；非 0 = 无

[下载任务 "net_upgrade"]  0x20CCADA
    0x20CCB0A  call 0x2009194            ; 先复查一次
    0x20CCB52  sprintf(r14, "%s%s%%7C%s%%7C%s",
                       "http://120.76.63.67:7890/robot/flash_download.form?token=", ...)
    0x20CCB66  call 0x20090AA(r14, r9)   ; 同步 HTTP GET
    0x20CCB6C  call 0x200843A(r9)        ; JSON → 树
    0x20CCB76  cfg_get(root, "code")     → 必须 == 200
    0x20CCB8E  body = cfg_get(root, "body")
    0x20CCB9E  cfg_get(body, "upgrade")  → 必须 == 1
    0x20CCBB0  cfg_get(body, "firmwareUrl") → 取字符串 (node+16)
    0x20CCD64  call 0x4004A62            ; 再起工作线程 0x20CD78C(prio25, stack1024)
    0x20CD78C: 调 0x20CD266 拼
               "http://120.76.63.67:7890/sdk/auth.form?secret=%s&uuid=%s"
               (secret 硬编码 "***TURING_SECRET***") → 取 token/授权 → 下载/写 flash
```

补充：
- `0x200A15C` 内另一段同构逻辑（0x200A610..0x200A692）在 `flash_check` 响应里再读
  `body.flashVersion`（0x2180875）复制出来，用于与设备自身版本比较。
- 旧时间同步：`/api/ntp.form`（0x2186018），响应 `code`/`body.time1`/`body.time2`/`body.timestamp`
  （处理函数 0x20CD4B0）。
- 任务名来自池偏移：`0x2037B10` 任务名 = "iTuring mqtt"（initVersion 所在任务），
  `0x20CCADA` 任务名 = "net_upgrade"。

### 10.3 字段类型/层级（实测）

- 顶层：`code`（**JSON 数字，必须 200**；代码读 key-value 节点 +20 处的整数比较）。
- `body`：对象；其中 `upgrade`（**JSON 数字 1**）、`firmwareUrl`（**JSON 字符串**，取节点 +16 指针）。
- `body.flashVersion`（字符串）出现在 **check** 响应里，用于版本比较。
- 与 SDK 的 `payload.updateStatus/agree/id/nextVersion/currentVersion` **无关**；固件中无这些字符串、
  也无 `/iot/v2/ota/version` 端点。

### 10.4 为什么之前的 mock 全失败

1. mock 只接管了 `turingapi.com` 域；OTA 走的是**硬编码 IP `120.76.63.67:7890`**，DNS 劫持无效，必须 iptables DNAT。
2. 响应结构用了 `payload` + `firmwareUrl`；实际 OTA 通道要的是 `body` + `upgrade` + `code:200`，
   且下载 URL 来自**第二个**接口 `flash_download.form` 的响应，而不是 check 响应。
3. initVersion 响应即使完全正确，也不会走到下载任务（无代码路径）。

### 10.5 建议的 mock（老接口，优先验证）

```text
iptables -t nat -A PREROUTING -s <设备IP> -d 120.76.63.67 -p tcp --dport 7890 \
         -j DNAT --to-destination <路由器IP>:8898

GET /sdk/auth.form?secret=***TURING_SECRET***&uuid=<deviceId>
    -> {"code":200,"body":{"token":"0123456789abcdef0123456789abcdef"}}

GET /robot/flash_check.form?token=...        (token 值形如 a|b|c，URL 里编码为 %7C)
    -> {"code":200,"body":{"upgrade":1,"flashVersion":"AC791N_NJ_1V2_V9.9"}}

GET /robot/flash_download.form?token=...
    -> {"code":200,"body":{"upgrade":1,"firmwareUrl":"http://<路由器IP>:8898/ota_probe.ufw"}}

GET /api/ntp.form     (时间同步，可能是前置条件)
    -> {"code":200,"body":{"time1":<unix>,"time2":<unix>,"timestamp":<unix>}}
```

排查顺序：
1. 先在路由器上抓/看日志：设备是否请求 `/sdk/auth.form`、`/robot/flash_check.form`、
   `/robot/flash_download.form`、`/api/ntp.form`？
   - 一个都没有 → 老接口通道没被激活（IP 不可达/被拦）→ 先 DNAT 并确认可达；
   - 有 flash_check 但没有 flash_download → check 响应不满足（code≠200 或 body.upgrade≠1）；
   - 有 flash_download 但没有真正下载 → download 响应的 `body.firmwareUrl` 缺失/非字符串。
2. `code` 必须是 JSON 数字 `200`（不是字符串 "200"）。
3. `upgrade` 必须是 JSON 数字 `1`。
4. `firmwareUrl` 必须是 JSON 字符串（http/https 均可，后续交给 netdl 下载）。

### 10.6 本轮仍未确定

1. 老接口请求里 `token=` 后拼接的 2~3 段内容（来自 net_ctx 字段）具体是什么；
   以及 auth.form 响应里 token 的确切 key 名（0x20CD266 只显式引用 `"code"`，其余 cfg_get 的键未逐条还原）。
2. app 主任务进入"升级轮询状态"的前置条件（是否必须先由老接口 auth 成功或收到某个事件）。
3. `body.flashVersion` 与设备版本的比较方式（字符串/数字）未定位到确切指令。
4. 若设备确实完全不访问 `120.76.63.67`，则说明该通道在该固件配置下被关闭（需进一步确认是否有开关位）。

### 10.7 细节修订（同一轮的补充确认，重要）

1. **`0x20090AA` = 同步 HTTP 客户端**：分配 760 字节 ctx（URL 存在 +8、连接/接收超时各 30 000ms
   存在 +48/+52、回调指针在 +28），调用底层 `0x2008E40` 发起请求，超时/长度取 +56，再把响应体
   分块读入调用者给的缓冲。⇒ 确实是在 `net_check_upgrade` / 下载任务里**同步发 HTTP 请求**。
2. **`0x200843A` = `CMLoadFromBuffer`（文本配置解析器）**，不是 base64：
   - 尾部 `0x2007E1A` = 跳过空白（byte ≤ 32）的 tokenizer；
   - `0x2007E4A` = 数字解析（十进制 + `A-F/a-f` 十六进制字符）；
   - `0x2008148` = 真正的解析器：按 4/5 字节关键字表比较、处理 `"`(0x22) 引号、调用内存/flash 读取；
   ⟹ **响应体是纯文本/JSON 即可，不需要 base64 或 AES**。
3. **key-value 节点布局（由用法反推）**：`+16` = 字符串值指针（`firmwareUrl`/`flashVersion` 用）；
   `+20` = 整数值（`code`、`upgrade` 用）。
   ⟹ `code` 必须是 **JSON 数字 200**、`upgrade` 必须是 **JSON 数字 1**，不能给字符串 `"200"`/`"1"`。
4. `0x2009194` 的 URL 是 `sprintf("%s%s%%7C%s%%7C%s", "http://.../flash_check.form?token=", <ctx字段1>, <ctx字段2>)`，
   即 token 参数形如 `a|b|c`（`%7C` 就是 `|`）；这些字段来自网络上下文 `[ctx+948]`，不是 VM 键。
5. app 主任务循环里 `0x200B228` 用 `[net_ctx+948]` 判空 → **必须先联网成功**才做升级检查；
   `0x200B21E` 的 `os_sleep(300)` 说明检查是**周期性轮询**（≈每 300ms/或 300 tick 一次）。
6. `0x20090AA` 的调用点还有一处值得注意：`0x20CCB0A` 处下载任务**先调用 `0x2009194` 复查**，
   通过后才去请求 `flash_download.form`。所以 mock 两个接口都必须满足，缺一不可。

### 10.8 解析器能力确认（决定性证据）

`CMLoadFromBuffer`(0x2008148..0x20083B6) 内部对以下字节有显式比较，证明它是**完整 JSON 解析器**：

| 字节 | 值 | 出现在 |
|---|---|---|
| `"` | 34 | 0x2007EAA / 0x2007EC6 / 0x2007EF8 / 0x2008006 / 0x20081AA |
| `\` | 92 | 0x2007EC2 / 0x2007F64 / 0x2007FF6 / 0x2008010（转义） |
| `{` | 123 | 0x20081E2 / 0x2008266 |
| `}` | 125 | 0x200828C / 0x20083A2 |
| `[` | 91 | 0x20081E6 / 0x20081FC |
| `]` | 93 | 0x2008222 / 0x2008306 |
| `,` | 44 | 0x2008302 / 0x200839E |
| `:` | 58 | 0x2008366 |

⟹ **mock 直接返回普通 JSON 即可**（不需要 base64 / AES / 任何编码）。
配合 §10.7 第 3 条：`code`、`upgrade` 用 JSON 数字，`firmwareUrl`/`flashVersion` 用 JSON 字符串。

---

## 11. 闹钟（alarm）协议分析

### 11.1 结论速览

| 问题 | 结论 | 置信度 |
|---|---|---|
| 闹钟存在哪？ | **BES 区 flash 0x7BF000（0x40000 字节）**，是一个 JSON，键 `alarm` 的值是**内嵌 JSON 字符串**，里面 `hb` 是闹钟数组 | **高**（实测 dump 直接可见） |
| 单条闹钟字段 | `na` 名称(str)、`th` 时(0-23)、`tm` 分(0-59)、`sd` 起始日期(unix 秒，本地零点)、`wk` 星期掩码(63=每天)、`wt` 响铃类型(0)、`s` 使能(0/1) | **高**（实测 dump） |
| 时钟 | `sd=1792684800` = 2026-10-23 00:00 (UTC+8) | 高 |
| 谁读写 BES | 启动时 **0x200D4F2** 读 0x40000 字节 → `CMLoadFromBuffer` 解析 → 按 key 分发：`alarm`→**0x200CCB6**(闹钟表)、`hp`→0x200CE1A、`rp`→0x200CF8E、`task_sinfo`→0x200D112、`hp_sinfo`→0x200D252、`rp_sinfo`→0x200D3A2 | 高 |
| 单条记录 JSON 序列化 | **0x2004B5C**（struct→JSON，键 `na/th/tm/sd/wk/wt`，字段偏移 +24/+0/+4/+8/+12/+16） | 高 |
| 命令入口 | **uQDS 请求回调 0x203C37A**（`r2==129` 请求 / `r2==130` 拆除），内部按 `code`/`opt_code` 分发；闹钟分支读 `parameters.*` | 中高 |
| 云端要发什么 | `parameters` 里 `action` ∈ {"add","delete"} + `time`("H:M:S") + `startDate`("Y-M-D") + `cycleType`(0..4) + `alarmType` + `memoContent` + `alarmId`；回包 `opt_code` = `alarm_set`/`alarm_del`/`alarm_err` | 中（字段名来自代码，外层封装未完全确认） |

### 11.2 存储格式（实测，最重要）

`chat rabbit-puya.bin` @ flash **0x7BF000**（BES 区，0x40000）：

```json
{
  "bank_time": 0,
  "alarm_up": 1,
  "alarm": "{\n\t\"hb\":\t[{\n\t\t\t\"na\":\t\"<名称>\",\n\t\t\t\"th\":\t18,\n\t\t\t\"tm\":\t18,\n\t\t\t\"sd\":\t1792684800,\n\t\t\t\"wk\":\t63,\n\t\t\t\"wt\":\t0,\n\t\t\t\"s\":\t0\n\t\t}]\n}",
  "hp": "{\"typeid\":0,\"ar\":[]}",
  "rp": "{\"rp\":[]}",
  "task_sinfo": "{\"bval\":[]}",
  "hp_sinfo": "{\"bval\":[]}",
  "rp_sinfo": "{\"rval\":[]}"
}
```

- `hb` = 闹钟数组（多条目）。`wk`: 位掩码，bit0=周一 … bit6=周日（63=每天）。
- `sd` = 起始/生效日期，**unix 秒**、本地时区零点（实测 1792684800 → 2026-10-23 00:00 UTC+8）。
- `s` = 使能位；`wt` = 响铃方式（0=铃声）。
- 同区还有 `hp`/`rp`/`task_sinfo` 等（喝水/休息提醒、任务提醒），结构类似。

### 11.3 代码位置（槽 B 反汇编）

| 地址 | 作用 |
|---|---|
| 0x200D4F2 | BES 区加载器（malloc 0x40000 → 读 flash → 解析 → 分发） |
| 0x200CCB6 | `alarm` 值解析 → 闹钟表（读 `hb` → 逐条读 `na/th/tm/sd/wk/wt`） |
| 0x2004B5C | 单条闹钟 struct → JSON（写 `na/th/tm/sd/wk/wt`）；字段偏移 +24(名字) +0(th) +4(tm) +8(sd) +12(wk) +16(wt) |
| 0x203C37A | uQDS 请求分发器（闹钟命令处理在这里） |
| 0x203C290 | "时间/日期"字符串解析器（`H:M:S`、`Y-M-D` → 整数） |
| 0x200B5DE | 引用中文「闹钟」字符串（语音意图解析路径） |
| 0x2036AAA 附近 | 引用「凌晨/早上/中午/下午/晚上」（语音时间关键词） |

字符串池（base 0x217E530 / 0x217E542）：
`na`=0x217E5BD, `th`=0x217E5A2, `tm`=0x217E5A5, `sd`=0x217E5D5, `wk`=0x217E5B7,
`wt`=0x217E5A8, `hb`=0x217E59F, `bank_time`=0x217FB3B, `alarm_up`=0x217F84A,
`alarm`=0x217ECDD, `alarmType`=0x217FB6D, `alarmId`=0x217F47B,
`alarm_set`=0x217FB81, `alarm_del`=0x217FB77, `alarm_err`=0x217FAEB,
`cycleType`=0x217FB8B, `startDate`=0x217FB95, `titleData`=0x217FAF5, `innerData`=0x217FAFF,
`所有闹钟`=0x2180653, `全部闹钟`=0x2180660, `闹钟`=0x217F065。

### 11.4 命令协议（uQDS 请求）

分发器 0x203C37A 的入参：`r0`=请求 JSON 文本缓冲、`r2`=消息类型（**129=请求**，130=拆除）。
内部流程：
1. `CMLoadFromBuffer(r0)` → 解析成 key-value 树（普通 JSON）。
2. 读顶层 `code`(int)、`done`(int)，组回包（`system:"UkitOS V2.1"`、`OsTick`、`request_id`）。
3. 按 `code`/`opt_code` 分发；闹钟分支读 **`parameters`** 对象：
   - `parameters.action`：字符串，`"add"` / `"delete"`（strcmp 比较）
   - `parameters.time`：字符串，形如 `18:18` / `18:18:00`（由 0x203C290 解析成 时/分/秒）
   - `parameters.startDate`：字符串，形如 `2026-10-23`（解析成 年/月/日，再算星期）
   - `parameters.cycleType`：整数 0..4（`tbb` 跳转表，0=单次 1=每天 …）
   - `parameters.alarmType` / `parameters.alarmId` / `parameters.memoContent` / `parameters.timeLen`
   - 删除全部：内部字符串「所有闹钟」/「全部闹钟」（0x203CD88/0x203CD9E）
4. 回包 `opt_code` ∈ {`alarm_set`, `alarm_del`, `alarm_err`}。

### 11.5 两种触发路径（推测 + 依据）

1. **App/云端 → 设备（首选验证）**：通过设备已连的图灵 IoT 通道下发一条 uQDS 请求 JSON（含 `code`/`opt_code`/`parameters`），设备处理后把闹钟写进 BES 区并回 `opt_code=alarm_set`。
   **未完全确认外层封装**（MQTT topic / WS 帧格式没最终定位到），但 `parameters.{action,time,startDate,cycleType,alarmType,memoContent,alarmId}` 这些字段名是代码里实打实读的。
2. **本地语音意图**：`闹钟` + `凌晨/早上/中午/下午/晚上` + 中文数字（二十/三十…）+ `所有闹钟`/`全部闹钟` → 本地关键词匹配后走同一套闹钟 API（0x200B5DE 引用「闹钟」；0x2036AAA 引用时段词）。

### 11.6 「type=2 operate=8」那条路

固件里**没有** `operate` 这个字符串（0 次命中），也没有 SDK 那套 `{"type":2,"operate":8}` 的处理分支；`opt_code` 才是本固件的操作码字段名（0x217F880）。所以之前实测无效是符合预期的——**这条路在这个固件里不存在**。

### 11.7 建议的验证步骤（给 lead）

1. 保持现有 mock 云在线，让设备正常联网（MQTT 已通）。
2. 先发最小变更量：只改 `alarm` 存储来验证 UI 效果 → 需要能写设备 flash（OTA 通道可以刷入自定义 `app.bin`，或后续用 ufw 打包注入初始闹钟）。
3. 若走命令通道，按下面顺序试（每步观察回包 `opt_code` 与设备 BES 区是否变化）：
   - `{"code":200,"done":0,"opt_code":"alarm_set","parameters":{"action":"add","time":"18:18","startDate":"2026-10-23","cycleType":1,"alarmType":1,"memoContent":"起床"}}`
   - `{"code":200,"done":0,"opt_code":"alarm_del","parameters":{"action":"delete","alarmId":1}}`
   - `{"code":200,"done":0,"opt_code":"alarm_set","parameters":{"action":"delete","memoContent":"全部闹钟"}}`
4. 判定标准：设备回包出现 `"opt_code":"alarm_set"`；随后 flash 0x7BF000 的 `alarm.hb` 数组长度变化。

### 11.8 仍未确定

1. uQDS 请求的**外层封装**（MQTT topic/WS 帧/HTTP 路径）与 `opt_code` 的**数值编码**（代码里比较的是整数如 200710/201401，也可能是 `code` 的取值）——需要抓一次真实 App 下发才能定死。
2. `alarmType` 语义（0/1/2：单次/每天/工作日？）与 `cycleType` 到 `wk` 掩码的映射细节（`tbb` 表 0..4 已定位，但各 case 的最终 wk 值未逐条读出）。
3. 闹钟触发时的上报格式（`msh_nj_alarm` 相关，可能只在响铃时上云）。

---

## 12. uQDS 分发器的传输通道（第三轮补充）+ ufw 打包材料状态

### 12.1 结论：**WebSocket**（`ws://ws-api.turingapi.com/api/v2`），不是 MQTT / 不是 HTTP 响应

证据链（全部为反汇编实证，槽 B）：

1. 在 **0x20194CE .. 0x2019512** 有一段**回调表初始化**，把函数指针写进结构体
   `r4 = 0x403E3F0`（SDRAM 全局）的偏移 `+3804` 起：
   ```
   0x20194CE: r0 = 0x201931A ; [r4+3804+0x00] = r0
   0x20194DA: r0 = 0x20C3E8E ; [r4+3804+0x04] = r0
   0x20194E2: r0 = 0x20C3CB8 ; [r4+3804+0x08] = r0
   0x20194F2: r0 = 0x20C40C8 ; [r4+3804+0x10] = r0
   0x20194FA: r0 = 0x20C45F2 ; [r4+3804+0x0C] = r0
   0x2019502: r0 = 0x203C37A ; [r4+3804+0x1C] = r0   ★ uQDS 请求分发器
   0x201950C: r0 = 0x203D11A ; [r4+3804+0x20] = r0
   ```
2. 同一结构体在 **`[r4+56]`** 存入字符串 **`ws://ws-api.turingapi.com/api/v2`**
   （0x2019518: `r0 = 0x2183A8A` → `[r4+56]`）→ 该结构体属于 **WebSocket 客户端模块**。
3. 全镜像只有一处代码引用 0x403E3F0：**0x203C382**（就在分发器内部，用它取 `request_id` 缓冲），
   说明分发器与该 WS 模块共享同一上下文。
4. 分发器入参：`r0` = 帧数据缓冲、`r2` = 消息类型（**129=请求/数据帧**，**130=拆除/释放**），
   入口先 `CMLoadFromBuffer`（0x200843A，文本 JSON 解析器）→ 再 `cfg_get`。

⟹ **闹钟命令只能通过设备主动连接的这条 WebSocket 帧下发**（设备是 client，服务端＝我们的
mock；`ws://` 明文、端口 80，DNS 劫持即可接管；与 initVersion 的 `iot-ai.turingapi.com` 不同域名：
`ws-api.turingapi.com`）。

### 12.2 帧格式（从分发器反推）

```json
{
  "code": 200,
  "done": 1,
  "request_id": "1",
  "nlpResponse": {
    "intent": {
      "code": 200710,
      "parameters": {
        "action": "add",
        "time": "18:18",
        "startDate": "2026-10-23",
        "cycleType": 1,
        "alarmType": 1,
        "memoContent": "起床"
      }
    }
  }
}
```
- 顶层 `code`(int)：分发器初筛 60/61/62/200/230/240；`done`(int) 非 0 表示最终结果。
- `nlpResponse.intent.code`(int) = **意图码**，闹钟分支是 **200710**（0x203C6E8 起的比较链）；
  其它已见码：50101、100000、100302、200101、200201、200205、200209、200401、201204、201401、
  201501、201601、201711。
- `nlpResponse.intent.parameters` = 槽位参数（闹钟字段见 §11.4）。
- 回包字段 `opt_code` ∈ {asrRsp, ttsRsp, playback, dialog, invalid, echo, custFan, playerCtrl,
  alarm_set, alarm_del, alarm_err …}（0x217F880 = "opt_code"；值是字符串）。

### 12.3 关于「type=2 operate=8」

固件内确无 `operate` 字符串，也没有该分支（0 次命中）；操作码字段名是 `opt_code`。
之前实测无效符合预期。

### 12.4 ufw 打包材料（详见 `work\ufw\README.md`）

产物：`work\ufw\out\update.ufw`（2,355,648，SD/USB）、`update-ota.ufw`（2,021,889，OTA 双备份），
脚本 `work\ufw\make_ufw.ps1`（`-Mode full|ota|both`）。已离线验证官方工具链可跑通（26 秒内出全部产物）。

关键实测结论：
1. 设备 `isd_config.ini`（flash 0x3648，699B）**只有 [SYS_CFG_PARAM] 的二进制 TLV**，
   没有 `FLASH_SIZE`/`NEW_FLASH_FS`/预留区配置 → 只能用 SDK 模板改写（8M/双备份/PID=AC79_PUBLIC/VID=1.00）。
2. 生成镜像的 flash 头与设备**完全一致**（VID/PID/flash_size/fs_ver/block_align/special_opt），
   但 **app 区目录位置不同**（生成 0x4000 vs 设备 0x4020）、VM 起点不同 → 布局不一致，
   OTA 校验大概率失败（官方 note 明确警告预留区/VM 配置不一致会导致文件校验失败）。
3. **厂商 uboot.boot 的明文被 isd_download 拒绝**（`Failed to generate flash bin`），
   SDK uboot 可通过 → 该工具对 uboot 有版本/结构约束；故本次打包用 SDK uboot
   （⚠️ 含 uboot 的完整包跨版本有风险，**优先用不含 uboot 的 OTA 包**）。
4. `-update_files normal` 产物 = code+res，**不含 VM/BES** ⟹ **OTA 无法预置闹钟**；
   预置闹钟需改 app.bin 的默认 BES 模板（va 0x218FDDE / app.bin 偏移 0x18FCBE）或编程器直写 BES。

---

## 13. 第四轮深挖：WS/KWS 启动条件 + 小程序闹钟真实链路 + NTP 格式

### 13.1 ★ 结论先行：**WebSocket 客户端在本固件里是"死代码"，永远不会启动**

这不是"没找到入口"，而是有正面证据：

1. WS 客户端模块 = **`wws` 模块**（任务名实证）：
   - 模块上下文结构体：SDRAM 全局 **0x403E3F0**
   - 初始化函数 **0x201945A** 做三件事：
     a) 填回调表（`r1 = r4 + 3804`，按索引写 8 个函数指针）：
        `+0x00=0x201931A  +0x04=0x20C3E8E  +0x08=0x20C3CB8  +0x0C=0x20C45F2`
        `+0x10=0x20C40C8  +0x18=0x201893E  +0x20=0  +0x1C=0x203C37A(分发器)  +0x24=0x203D11A`
     b) 写 WS 服务器地址：`r0 = 0x2183A8A`（`ws://ws-api.turingapi.com/api/v2`）→ `[r4+56]`
     c) **调用 `os_task_create`(0x4004A62) 创建两个任务**：
        - **`wws_recv`**（va 0x217F877），优先级 **18**，栈 **4096**，入口 **0x20C40C8**
        - **`wws_heart`**（va 0x217FB59），优先级 **19**，栈 **2048**，入口 **0x20C45F2**
2. **对 0x201945A 的引用数 = 0**：全镜像（含密文解密后的 slot A/B、含 u32 指针表、含
   绝对立即数 `[lo][hi][21][02]` 形式）搜索 0x2019450..0x2019460 全部 0 命中；
   而它要安装的回调指针（0x20C40C8/0x20C45F2/0x203C37A…）在整个镜像里**只出现在这段初始化代码内部**，
   没有任何静态函数指针表持有它们。
   ⟹ **该初始化函数从未被调用 ⟹ `wws_recv`/`wws_heart` 任务从未创建 ⟹ WS 客户端不可能启动。**
   与实测（`ws_count=0`、mock 的 :8081 从未收到 WS 握手）完全一致。
3. 即使任务被创建，`wws_recv` 任务体（0x20C40C8）第一件事是
   `if (h[r12+16] != 1) goto 等待/退出`，还需要"使能=1"才会跑。
4. **uQDS 分发器 0x203C37A 就挂在这个死模块的回调表里** ⟹ 它同样不会被调用
   ⟹ *任何*通过 uQDS 报文（`nlpResponse.intent.code=200710`）设置闹钟的方案在本固件都无效。

**给 lead 的直接结论（Q1）**：不存在"满足某条件就会连 WS"的路径。要它连 WS 只能改固件（把 0x201945A
挂进启动流程 / 在 app 里调用它）。mock 侧再怎么配也**不可能**触发 WS。

### 13.2 Q2：KWS/AI 开关
- `KWSCreateOnBuffer` / `KWSSetOptionString` / `websocket_valid!=WSS_CONNECT_ISOK` 这些字符串位于
  **ukit 的调试符号名表**（基址 0x218A540 附近，形式如 `int32_t KWSCreateOnBuffer(const char *, const char *)`），
  它们只是**符号签名串**（供日志/断言用），不代表运行时路径被走到。
- `ai-tts-off / ai-kws-off / ai-dgc-off / ai-ssp-off / ai-vadnn-off / ai-ssp-car-off /
  ai-ssp-bike-off / ai-dictation-off / ai-tts-off-g1`（0x217FFE8、0x21808A9、0x218118C、0x21813C5…）
  是 **AI 子引擎的"关闭开关"配置项名称**，由映射函数 **0x20CDDAA** 转成 9 个配置结构体之一
  （数组基址 0x4070EE0，步长 672）；调用者：**0x20D0CE8**（命令/配置处理）、**0x20D512E**、**0x20E12CA**。
  ⟹ 它们是**配置键名**（不是 URL 前缀、不是型号变体）；某台设备是否启用 KWS 取决于这些键的取值。
- `engine is start ok`（0x218603E）与 `ntp.form` 的 URL 在同一字符串池（0x21860xx），说明
  "NTP + AI 引擎启动日志"属于同一个（AI/云）模块；但本机从未出现 KWS 运行迹象，且从不请求
  `ai-off-acti.hivoice.cn/rest/v1/device/activate` ⟹ **本机 AI/语音唤醒能力未启用**（大概率
  出厂配置即关闭，或该模块也只被链接未启动）。

### 13.3 Q3：小程序设置闹钟的真实链路
设备实际建立的入站通道只有两类：**MQTT（已连接、已订阅）** 和 **HTTP 响应（设备主动请求）**。WS 已排除。所以：

**小程序 → 厂商后端 → 图灵云 → MQTT PUBLISH 到设备订阅的 topic → 设备解析**（唯一可行路径）。

可照做的 mock 条件：
1. `/public/mqttManager/getMQTTConnectInfo` 的响应里，设备读的字段已实证为
   **`endpoint`**（va 0x217F80B）和 **`clientId`**（va 0x217F802），并用它们拼 `mqtt://...` URL
   （func 0x2034120 里 `call strchr(':')`/`strchr(',')` 解析 host/port）。
   ⟹ **把这个响应改成指向你自己的 broker**，设备就会连你的 broker；topic 也由该响应决定，
   你在自己的 broker 上就能看到设备 SUBSCRIBE 的确切 topic。
2. 向该 topic PUBLISH（下行）：
   ```json
   {"code":200,"done":1,"request_id":"1",
    "nlpResponse":{"intent":{"code":200710,
      "parameters":{"action":"add","time":"18:18","startDate":"2026-10-23",
                    "cycleType":1,"alarmType":1,"memoContent":"起床"}}}}
   ```
3. 另有一个**带 11 路跳转表**的命令入口：函数 **0x203B204** 对 `code-8002` 做 `tbh` 跳转
   （即 code=**8002..8012**），并构造含 `type` / `optCode` / `fromUser` 的 JSON。
   若上面的 nlpResponse 无效，可试 `{"type":2,"optCode":8002,...}` 或 `{"optCode":8002,"payload":{...}}`。

⚠️ 注意：由于 0x203C37A（唯一解析 `nlpResponse` 的代码）挂在死模块上，**方案 2 很可能也无效**。
如果 MQTT 全部试完都无效，结论就是：**这台设备的闹钟只能由固件内部逻辑设置**，
那就必须走"改 app.bin 预置 BES 默认数据 + OTA 注入"的路线（见 §11.8 / ufw README §2.3）。

### 13.4 附：`/api/ntp.form` 响应格式（诊断）
- URL 真实起始 va = **0x2186018**（0x2186030 只是其中的 `/api/ntp.form` 部分），
  同一池还有 `):calloc http_ctx fail.` 与 `[engine is start ok]` ⟹ 该请求由 **AI/云模块的 http_ctx 代码**发出。
- 我没能定位到它的**响应解析代码**：该区段在 objdump 线性扫描中失步（见 13.5），
  基址+立即数、绝对立即数、u32 指针表三种 xref 方式均未命中。
- 但**现象可以用数值反推**：设备显示 **2070/01/01 08:00（UTC+8）**
  = **unix 3155760000 秒** = 恰好 **36525 天 = 100 年**（1970→2070）。
  判定公式：如果 mock 发出的时间值是 **V**，且 **`V mod 2^32 == 3155760000`**，
  就 100% 是"**毫秒当成秒 / 64 位被截成 32 位**"导致的（例如 V=1,794,157,122,432 ms → 截断即 3155760000）。
  另一个等价可能：设备把**解析失败/空值**当作 0，再加上一个固定基准偏移 3155760000。
- **建议 mock 试法（按顺序）**：
  1. `{"code":200,"body":{"time":<当前秒>}}`（int，秒，10 位）
  2. `{"code":200,"body":{"timestamp":<当前秒>}}`
  3. 直接返回裸秒数文本 `1792000000`（不是 JSON）
  4. 把 `/time_now`（`http://easybox.iflyos.cn/time_now`，va 0x21842B6）也 mock 上——
     该 URL 在 func **0x20CD684** 里被使用（构造 http_ctx：`timeout=3000`、`buf=4096`，
     随后 `call 0x20CD108` 取响应），**很可能才是真正的校时来源**（讯飞 easybox 时间接口）。

### 13.5 工具限制（重要，影响后续结论的可信边界）
objdump 对整段 app.bin 做线性反汇编时在 **0x20185E0..0x203130C（约 100 KB）** 处失步，
把该段当数据输出；实测把该段按 0x200 字节分块、每块单独起一个 ELF 反汇编，可以 **100% 解出指令
（每块 ~3400 条、0 条 raw data）**。说明：
- 该 100 KB **是真实代码**（很可能是 ukit/uQDS/AI 库，含 wTuring/ntp 相关逻辑）；
- 主扫描仍有局部失步会导致**个别字符串引用查不到**（13.4 的 NTP 解析代码就属于这种情况）；
- 已做分块重扫（199 块）并重建合并索引 `merged2.txt`（1,175,889 条指令），本轮结论基于该索引。

---

## 14. ★ 找到存活的下行消息通道与闹钟命令格式（推翻 §11/§12 的外层假设）

### 14.1 关键发现：真正的入站 JSON 分发器 = **0x201BA04**（此前的 0x203C37A 是死 WS 分支）

（0x201BA04 位于之前 objdump 失步的 0x20185E0..0x203130C 区内，靠 0x200 字节分块重反汇编才解出来）

调用链（全部实证）：

1. **注册**：turing 云模块初始化 **0x203201C**（引用 `smartdevice.ai.turingapi.com`）→
   `call 0x201DD8C(0x201BA04)`（site 0x2032260）→ 把 0x201BA04 存进全局
   `0x406D060 + 3261*4`（= 0x406D060+13044）。
2. **投递**：消息处理函数（turing 模块，0x20218xx）组好 JSON 文本后：
   ```
   0x20218BC: r0 = 3261; r5 = [r12+r0<<2]      ; r5 = 注册的分发器 0x201BA04
   0x20218CE: r2 = strlen(json)
   0x20218D0: r0 = r9        ; r9 = 消息类型
   0x20218D2: r1 = r6        ; r6 = JSON 文本
   0x20218D4: call r5        ; 0x201BA04(type, json, len)
   ```
3. **分发器 0x201BA04**：
   ```
   0x201BA04: ... call CMLoadFromBuffer(r1)      ; 直接解析 JSON 文本
   0x201BA28: cfg_get(tree, 0x217F483 = "payload")
   0x201BA3E: cfg_get(tree, 0x217F880 = "opt_code")
   ...
   0x201BA5C 起：strcmp(值, <命令名>) 长链分发
   ```

### 14.2 消息格式（**这就是答案**）

```json
{ "opt_code": "<命令名字符串>", "payload": { ...参数... } }
```
- `opt_code`：**字符串命令名**（不是数字、不是 nlpResponse）
- `payload`：对象，命令参数

`opt_code` 支持的命令名（0x201BA04 里的比较链，均为字符串常量）：
| 命令名 | va | 说明 |
|---|---|---|
| `tts` | 0x217E6E3 | 播报 |
| `aud` | 0x217E6E7 | 音频 |
| `txtreq` | 0x217F0F1 | 文本请求 |
| `dialog` | 0x217F0CE | 对话 |
| `light` | 0x217ED19 | 灯 |
| `screen` | 0x217F00A | 屏幕 |
| `volume` | 0x217F192 | 音量 |
| `power_off` | 0x217FAD7 | 关机 |
| **`alarm_set`** | **0x217FB81** | **设置闹钟** |
| **`alarm_del`** | **0x217FB77** | **删除闹钟** |
| `error_code` / `intentType` / `commandUrl` 等 | 0x217FDD8 | 其它分支 |

### 14.3 `alarm_set` 参数（分支体 0x201C070，逐个 `cfg_get(payload, 键)` 实证）

| payload 键 | va | 类型/字节 | 说明 |
|---|---|---|---|
| `loop` | 0x217EA09 | int ([+20]) | 重复/循环标志 |
| `hour` | 0x217EA13 | 字节 ([+20]) | 时 0-23 |
| **`mintue`** | 0x217F0E3 | 字节 | **分**（固件里就是拼错的 `mintue`，不是 `minute`！） |
| `second` | 0x217F0EA | 字节 | 秒 |
| `year` | 0x217EA18 | 半字 ([+20] u16) | 年（如 2026） |
| `month` | 0x217ED0D | 字节 | 月 |
| `day` | 0x217E717 | 字节 | 日 |
| `event` | 0x217ED07 | 字符串 ([+16]) | 事件/名称文本 |

`alarm_del` 参数（分支体 0x201C182）：
| payload 键 | va | 类型 | 说明 |
|---|---|---|---|
| `alarmId` | 0x217F47B | int | **0 = 删除全部**；非 0 时按 `alarmId*0x168(360)` 索引清一条 360 字节记录（记录数组基址 0x40742A0+6528） |

### 14.4 最小可用下行（给 mock 直接照发）

```json
{"opt_code":"alarm_set","payload":{"loop":1,"hour":18,"mintue":18,"second":0,"year":2026,"month":10,"day":23,"event":"起床"}}
```
```json
{"opt_code":"alarm_del","payload":{"alarmId":0}}
```
（可选）带 request id 的形式：`{"request_id":"1","opt_code":"alarm_set","payload":{...}}`

### 14.5 与 §11/§12 的关系（重要修正）

| 项 | §11/§12 旧结论（错） | §14 新结论（对） |
|---|---|---|
| 外层字段 | `nlpResponse.intent.code` + `parameters` | **`opt_code` + `payload`** |
| 分发器 | 0x203C37A（挂在死 WS 模块回调表） | **0x201BA04**（turing 模块注册、由 0x20218xx 调用） |
| opt_code 语义 | 只是回包字段 | **就是命令名（字符串）** |

0x203C37A 仍存在，但它只被死掉的 wws 模块初始化使用；**真正的入站命令走 0x201BA04**。

### 14.6 Q5/Q6 附答

- **Q5（HTTP 响应能否下发命令）**：没有找到"响应体即命令"的分派代码；`/iot/status/notify`、`/v2/iot/audio`
  的响应解析在 turing 模块内，但未见其调用 0x201BA04 之外的命令通道。判定：**不能**（低-中置信）。
- **Q6（time_now / ntp 解析）**：仍未定位到解析函数（0x20CD684 是 *发请求* 的 http_ctx 包装：timeout 3000、
  buf 4096，然后 `call 0x20CD108` 取响应；解析在调用者里，未找到）。
  但 2070-01-01 08:00 = **3155760000 s = 36525 天 = 100 年**，判定公式：**若 mock 发出的值 V 满足 `V mod 2^32 == 3155760000`，
  就是 64→32 位截断（毫秒当秒）**。建议先试 `{"code":0,"body":{"time":<10 位秒>}}` 与裸秒文本。
  另外注意：本固件的 HTTP 响应解析普遍用 `code`/`body` 两键（字符串 `code`=0x217EB5C、`body`=0x217EB60 实证）。

### 14.7 仍需验证
1. MQTT topic 下行 payload 是否**原样**就是这段 JSON（还是外面又包了一层 JSON 字符串）。
   设备订阅 topic 来自 `getMQTTConnectInfo` 响应的 `endpoint`/`clientId` 等字段（§13.3）。
2. `loop` 的取值语义（0=单次/1=每天？）与 `wk` 掩码的对应关系。
3. 命令名比较是 `strcmp`（精确匹配）还是 `strstr`（包含即可）——分支里两种调用（0x20E58D0 / 0x20E5B32）交替出现，需逐分支确认。

---

## 15. 投递链路原文（第五轮，回答"到底什么消息才会被执行"）

### 15.1 完整调用链（三段代码全部实证）

```
传输层(文本帧/JSON)
   └─ 0x2022864  transport 组装器 (malloc 1024、分片解析、检查 code==200)
        └─ 0x2020242  router（按 JSON 的 "code" 数值大 switch）
             └─ 构造内部信封 {"opt_code":<命令名>, "payload":{...}}
                  └─ 0x201BA04 命令分发器（按 opt_code 字符串 strcmp）
                       └─ alarm_set 分支 0x201C070 / alarm_del 分支 0x201C182
```

### 15.2 投递函数原文（0x2021850..0x20218E4）

```
02021818: r0 = r4                       ; r4 = 解析出的 tree
0202181a: r1 = [sp+16]                  ; 某个 key
0202181c: call cfg_get
02021820: if (r0 == 0) goto 2021a14
02021824: r2 = [r0+16]
02021826: if (r2 == 0) goto 2021a14
0202182a: r1 = r15 + 2960               ; "ttsTxt"
0202182e: goto 2021a1c
...
02021830: r0 = 8548; r1 = r15 + r0      ; CJK 串①  0x2180694 = "设备解绑"
02021838: r0 = sp + 148
0202183c: call strstr
02021842: if (r0 != 0) goto 2021870
02021844: ... r15+8561 (0x21806a1 "解绑设备") strstr
02021856: ... r15+8574 (0x21806ae "解除绑定") strstr
0202186a: if (r0 == 0) goto 2021fe0     ; 都不是 → 另一分支
02021870: r1 = r15 + 4944               ; "opt_code"
02021874: r2 = r15 + 3016               ; "unbind"
02021878: r0 = r5                       ; r5 = 新建的 cJSON 对象
0202187a: call cJSON_AddStringToObject  ; {"opt_code":"unbind"}
0202187e: r0 = 10522; r2 = r15 + r0     ; CJK 串② 0x2180e4a "设备已解绑"
02021886: r1 = r15 + 3023               ; "tipTxt"
0202188c: call cJSON_AddStringToObject  ; {"tipTxt":"设备已解绑"}
02021890: if (r9 == 2) {                ; ★ r9 = 函数入参 type
02021894:   r1 = r15 + 4944             ; "opt_code"
02021898:   r2 = r15 + 3009             ; "txtreq"
0202189c:   r0 = r5
0202189e:   call cJSON_AddStringToObject;  {"opt_code":"txtreq"}
020218a2:   r1 = r15 + 3923             ; "payload"
020218a6:   r0 = r5
020218a8:   r2 = r14                    ; r14 = 参数对象
020218aa:   call cJSON_AddItemToObject  ;  {"payload": r14}
020218ae: r0 = r5
020218b0: call cJSON_PrintUnformatted   ; → r6 = 文本
020218b4: r6 = r0
020218b6: r0 = r5
020218b8: call cJSON_Delete
020218bc: r0 = 3261
020218c0: r5 = [r12 + r0<<2]            ; ★ r5 = 注册的命令分发器 = 0x201BA04
020218c4: if (r5 == 0) goto 20218d6
020218c6: r0 = r6
020218c8: call strlen
020218ce: r2 = r0                       ; arg2 = 长度
020218d0: r0 = r9                       ; ★ arg0 = type（原样透传）
020218d2: r1 = r6                       ; arg1 = JSON 文本
020218d4: call r5                       ; ★ 进入 0x201BA04 分发器
```

### 15.3 关键回答

**1) `r0=type`（r9）从哪来？** —— 来自**函数入参**，不是 JSON 字段：
- `0x2022868: r9 = r0`（transport 组装器 0x2022864 的第一个参数）
- `0x2022994: r0 = r9; r1 = r8; call 0x2020242`（透传给 router）
- 在 router 里只用于少量判断（如 `if (r9 == 2)` 才把 opt_code 改成 `txtreq`）；**它不是进入分发器的门槛**。
- 该 type 由 0x2022864 的调用者决定：`0x2022C0E / 0x20250D2 / 0x20323B6 / 0x2032530`（全在 turing 模块内）。

**2) 投递函数前面的调用者 & 条件**：
- 直接调用者：**0x2022864**（transport 组装器）。它 `malloc(1024)`，用 `strstr` 找文本帧标记（`r6+1471`=0x217EACF、`r6+4647`=0x217F757、`r6+2736`、`r6+2743`…），用 `0x20200AE` 解析分片，`memcpy` 拼成一个 JSON 缓冲。
- 然后在 **0x2022924** 检查 `if (r3 != 200)` —— **帧的 code 必须是 200** 才继续：
  ```
  02022928: r3 = [sp+4]
  0202292a: if (r3 != 200) goto 2022a06     ; 非 200 → 报错返回
  02022932: r1 = r12 + 1; call malloc
  02022954: call memcpy
  02022964: call 0x20200ae                  ; 分片解析
  02022978: call CMLoadFromBuffer           ; 解析 JSON
  02022982: r1 = r6 + 1581 ("code")
  02022986: call cfg_get → r6 = [r0+20]     ; 取 JSON 的 code 数值
  02022990: call cJSON_Delete
  02022994: r0 = r9; r1 = r8
  02022998: call 0x2020242                  ; → router(type, json)
  ```

**3) `r1=json` 的原始数据**：是上面 `memcpy` 拼出来的**完整 JSON 文本**（`CMLoadFromBuffer` 直接解析），**没有 AES / base64 步骤**（这段链路里只有 malloc/memcpy/strstr/分片解析）。

**4) 路由（router 0x2020242）**：`CMLoadFromBuffer` 后按 **`code`**（0x217EB5D）数值大 switch：
比较值：`200`(→ opt_code="tts")、`220`、`20092`、`20109`、`42000`、`43000`、`43010`、`43037`、`1000680`、`1000655`、`1000668`、`1000693`，以及 `r7-20000 ∈ [0,39]`（tbh 跳表，表在 SDRAM 0x40742A0，flash 里没有）。

### 15.4 ★ 闹钟命令的真正触发条件（router 内，实证）

```
020212f2: r1 = r15 + 1101        ; "func"
020212f6: r0 = r4                ; tree
020212f8: call cfg_get           ; func = tree.func
020212fe: r6 = r0; if (r6 == 0) goto 2021624
02021304: r1 = r15 + 2981        ; "action"
02021308: call cfg_get           ; action = func.action
02021312: r7 = [r0+16]           ; 字符串
0202131c: r1 = r15 + 483         ; "add"
02021322: call strcmp
02021328: if (r0 == 0) goto 202197c     ; ★ → 构造 opt_code="alarm_set"
0202132e: r1 = r15 + 2988        ; "delete"
02021334: call strcmp
0202133a: if (r0 == 0) goto 2021eee     ; ★ → 构造 opt_code="alarm_del"
```

alarm_set 内部信封构造（0x202197C 起）：
```
0202197c: r1 = r15 + 4944        ; "opt_code"
02021980: r2 = r15 + 5713        ; "alarm_set"
02021986: call cJSON_AddStringToObject
0202198a: r1 = r15 + 5723        ; "cycleType"
02021990: call cfg_get           ; cycleType 从 func 里读（字符串！）
020219a4: strcmp(cycleType,"0") → 20219fa
020219b2: strcmp(cycleType,"1") → 2021fd4
020219c4: strcmp(cycleType,"2") → 202204a
020219d6: strcmp(cycleType,"3") → 2022070
020219e8: strcmp(cycleType,"4") → 202207c
020219fa: r1 = r15 + 1241        ; "loop"
02021a0e: call cJSON_AddNumberToObject   ; payload.loop = 0..4
```
alarm_del 内部信封构造（0x2021EEE 起）：`opt_code = "alarm_del"`。

### 15.5 结论：网络侧消息格式

```json
{ "code": <数值>, "func": { "action": "add", "cycleType": "1" } }
```
- `code`：**数值**，决定走哪条业务分支；必须是"命令类"码（见 15.3 的候选值）
- `func.action`：字符串 `"add"` / `"delete"` —— **这才是闹钟的总开关**
- `func.cycleType`：**字符串** `"0".."4"`（不是数字！）→ 映射成内部 `payload.loop`
- 其余闹钟字段（hour/mintue/second/year/month/day/event）在 router 的 alarm 分支里没有全部读取（只看到 cycleType→loop），
  推测它们是在**下一层分发器 0x201BA04 的 alarm_set 分支（0x201C070）从 payload 读取**，
  而 payload 由上层把 `func` 的内容搬运过来——这一段尚未完整读出（见 15.6）。

### 15.6 仍未钉死的两点
1. **`code` 的确切取值**：闹钟分支所在的 case 由 `tbh` 跳表选择，表在 **SDRAM 0x40742A0**（运行时才有），
   flash 里没有该表；需要从设备运行时 dump SDRAM 0x40742A0（40×2 字节）才能得到 code 20000..20039 → 分支地址的映射。
   已知 case 值里 `200` 走的是 tts 分支（0x20209D4 里 `opt_code="tts"`），**不是**闹钟，别用 200。
2. `func` 里的 hour/mintue/… 是否直接被搬进 `payload`：需要再读 0x2021990..0x2021A10 之外的分支尾部。

### 15.7 给 lead 的下一步（按成本排序）
1. 用 **`{"code":20092,"func":{"action":"add","cycleType":"1","hour":18,"mintue":18,"second":0,"year":2026,"month":10,"day":23,"event":"起床"}}`**
   之类逐个 code 试：候选 `20092, 20109, 42000, 43000, 43010, 43037, 1000655, 1000668, 1000680, 1000693, 20000..20039`。
2. 同时把 `func` 内字段名按 §11/§14 的两套都放一份（`hour/mintue/second/year/month/day/event` + `alarmType/startDate/time/cycleType/alarmId`），
   反正多余键不会报错，能提高命中率。
3. 若要一步到位：从设备 SDRAM dump `0x40742A0` 那张 40 项跳表（或运行时读该地址），即可精确得到 code 值。

---

## 16. ★ 输入源钉死：`smartdevice.ai.turingapi.com:80` 的 **TCP 长连接（HTTP 风格帧）**

### 16.1 组装器 0x2022864 的调用者（4 处，全部是 socket 请求-响应）

| 调用点 | 传入 type | 所属函数 | 说明 |
|---|---|---|---|
| **0x2022C0E** | **5** | 0x2022AD8 | iTROS 云请求（`ReqAudIn/ReqTTS/ReqTxtIn`），host = `smartdevice.ai.turingapi.com` |
| 0x20250D2 | **3** | 0x202482A | 同协议另一类请求（该函数内也含 MQTT 相关日志） |
| 0x20323B6 | **2** | 0x203201C | turing IoT 模块请求 |
| 0x2032530 | **9** | 0x203201C | 同上 |

每个调用点前都有相同的**发送动作**（socket send）：
```
020250aa: call 0x201F960        ; socket send(str)
020250c6: call 0x201F960        ; socket send(str)
020250d0: r0 = 3                ; type
020250d2: call 0x2022864        ; ← 组装器（读响应）
```
错误串证据：`iTROS_cloud_socket_send Head fail`（0x218A670）、`iTROS_cloud_req_para fail`（0x2187DCE）。

### 16.2 连接目标：`smartdevice.ai.turingapi.com` **: 80**（TCP）

函数 **0x2022AD8**：
```
02022b7c: r4 = 0x21827E2            ; "smartdevice.ai.turingapi.com"（实际串起点 0x21827E3）
02022b84: call 0x201EF1A            ; DNS 解析 + 建 socket
02022b8c: [r7+3262*4] = r0          ; 保存 socket
02022b98..02022ba6: 0x201F0D8 / 0x201F68C / 0x201F8CE   ; socket 发送（Head / 正文）
02022c0c: r0 = 5
02022c0e: call 0x2022864            ; 读响应
```
DNS/连接函数 **0x201EF1A** 里构造 sockaddr：
```
0201ef82: r1 = 2
0201ef84: b[sp+37] = r1             ; AF_INET
0201ef88: r1 = 20480 (0x5000)
0201ef8c: h[sp+38] = r1             ; sin_port = htons(80)   ← ★ 端口 = 80
0201ef90: r1 = [r0+16]; r2 = [r0+12]; r1 = [r1+0]   ; 域名解析结果地址
0201efa6: call 0x201EDB0            ; connect()
```

⟹ **设备会主动 TCP 连接到 `smartdevice.ai.turingapi.com:80`**（DNS 劫持即可接管；注意是 **80 端口**，不是 8081）。

### 16.3 帧格式：HTTP 风格响应（这就是"帧 code==200"）

组装器 0x2022864 的解析过程（全部实证）：
```
020228b0: call 0x20200AE            ; socket recv(buf+off, remaining) → 返回字节数
020228bc: strstr(buf, 0x217EACF)    ; 等头部结束标记
020228cc: memset(sp+4, 0, 136)      ; 136 字节"响应头结构"
020228d8: strstr(buf, "HTTP/1.1")   ; 0x217F757
020228e6: sscanf(buf, "%*s %d.%*s %s", &struct[0])     ; 0x217EFE0 → HTTP 状态码
02022900: sscanf(buf, "%*s %s", ...)                   ; 0x217EFE7
0202291a: sscanf(buf, "%*s %ld", &struct[132])         ; 0x217F3AB → Content-Length
02022910: strstr(buf, "Content-Type:")                 ; 0x218094D
02022920: strstr(buf, "Content-Length:")               ; 0x2180E2A
02022928: r3 = [sp+4]; if (r3 != 200) → 失败          ; ★ HTTP 状态必须是 200
0202292e: r12 = struct[132]; if (r12 == 0) → 失败     ; Content-Length 必须 > 0
02022938: body = malloc(Content-Length + 1)
02022954: memcpy(body, 头部之后, min(...))            ; 拷 body
02022978: CMLoadFromBuffer(body)                      ; 解析 JSON
02022982: cfg_get(tree, "code") → 只用来判断流是否继续（==40000 时继续收）
02022998: call 0x2020242(type, body)                  ; → router
```

**结论**：
- `code` **不是包头字段**，而是 **HTTP 响应体（body）JSON 里的 `code` 键**
- 外层必须是**真正的 HTTP 响应头**：`HTTP/1.1 200 OK` + `Content-Length: N`（+ `Content-Type:` 建议）
- 0x20200AE 就是 socket recv（无魔数、无长度前缀，就是 HTTP 头部 + Content-Length 定界）

### 16.4 与 MQTT / topic 的关系
**无关**。MQTT 是另一条独立通道（getMQTTConnectInfo 那条），它不喂这个组装器；
设备订阅的 `iot/<apiKey>/<deviceId>` 与此命令通道无关。这也解释了为什么所有 MQTT 尝试都无反应。

### 16.5 mock 需要怎么做（精确到字节）

1. **DNS**：把 `smartdevice.ai.turingapi.com` 指到你的 mock 主机（和 iot-ai 一样做劫持）。
2. **监听 TCP 80**（不是 8081），接受设备连接，读它的请求（会看到 HTTP 风格请求行 + 自定义头 + 可能带 body）。
3. **回包**（关键格式）：
```
HTTP/1.1 200 OK\r\n
Content-Type: application/json\r\n
Content-Length: <body 字节数>\r\n
Connection: keep-alive\r\n
\r\n
{"code":1000680,"asrTxt":"设置闹钟","tts":"好的","ttsTxt":"好的","func":{"action":"add","cycleType":"1","hour":18,"mintue":18,"second":0,"year":2026,"month":10,"day":23,"event":"起床"}}
```
- 状态行必须是 `HTTP/1.1 200 OK`
- **Content-Length 必须与 body 字节数严格一致**（否则组装器读不满/读多，帧错位）
- body 里的 `code` 决定 router 分支（候选：1000655/1000668/1000680/1000693/20092/20109/42000/43000/43010/43037/20000..20039；`200` 是 tts 分支，别用）
- `func.action` = `"add"`/`"delete"`，`func.cycleType` = 字符串 `"0".."4"`
4. 同一连接是**长连接**，要保证多帧时严格按 Content-Length 定界。

### 16.6 请求侧参考（可选）
设备发出的 action 名（0x21801B8 附近的格式串 `%s%08X_%04X` + 动作名）：
`ReqAudIn` / `ReqTTS` / `ReqTxtIn` 等 → 可据此判断是哪类请求，决定回哪个 `code`。
