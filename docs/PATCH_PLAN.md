# PATCH_PLAN — 补丁方案（任务 1/2/3）

> 生成时间：第 7 轮（bank-analyst）
> 所有地址：va = `0x2000100 + dev_app_plain.bin 偏移`；app.bin 偏移 = `va - 0x2000120`
> 设备 dump：`chat rabbit\chat rabbit-puya.bin`（8 MiB）

---

## 任务 1：关于 `[auth_isok]` —— **这个门在本固件里不存在（结论：不要打这个补丁）**

### 1.1 实证：`auth_isok` / `auth_len` 日志串**没有任何代码引用**

字符串位置（unitB / slotA 一致）：

| 串 | va | 说明 |
|---|---|---|
| `\n(%s)([wrn]L%d):[aec_channel]:%d\n` | 0x218433F | |
| `\n(%s)([wrn]L%d):[aec_gain]   :%d\n` | 0x2184361 | |
| `\n(%s)([wrn]L%d):[auth_len]   :%d\n` | **0x2184383** | **代码中零引用** |
| `\n(%s)([wrn]L%d):[auth_isok]  :%d\n` | **0x21843A5** | **代码中零引用** |
| `\n(%s)([wrn]L%d):[auth_addr]:0x%x\n` | **0x21843C7** | 仅 **1 处**引用 |
| `\n(%s)([err]L%d):lenght(%d)!=1024\n` | 0x21843E9 | 1 处引用 |

检索方式（全部零命中）：绝对立即数 `[lo][hi][21][02]`、u32 字面量、以及 5 个常用池基址
（0x217E530 / 0x217E57C / 0x2182540 / 0x2186540 / 0x218A540 / 0x218E520）的 base+imm 组合。

唯一命中的引用：
```
0x20D564C:  r0 = r0 + 7849      ; base 0x2182540 → 0x21843E9  ([err]lenght(%d)!=1024)
0x20D51D8:  r0 = r5 + 7815      ; base 0x2182540 → 0x21843C7  ([auth_addr])
```
⟹ **编译期就把 `auth_len` / `auth_isok` 两条日志去掉了**，也就不存在"`if (auth_isok != 1) skip`"这样的可补丁分支。

### 1.2 真正存在的"引擎/会话启动"门（可补丁候选）

函数 **0x20D5198**（语音/AI 会话 worker，`[auth_addr]`、`lenght!=1024` 都在这里）：

| 地址 | 指令 | 含义 |
|---|---|---|
| **0x20D54DA** | `r0 = b[r14+7988]; if (r0 != 1) goto 0x20D555A` | **关键分支**：`b[0x406D060+7988]` 标志决定走哪条启动路径（该标志 = 授权/配置就绪） |
| 0x20D54DE..0x20D5558 | 打日志 + `b[+7988]=1` + `call 0x20D43C8(1)` | 路径 B（标志=1 → 直接启动） |
| **0x20D55BE** | `os_task_create(entry=0x20E0A64, prio=11, stack=0xA000, name=?)` | 路径 A（标志≠1 → 起引擎任务） |
| 0x20D560C–0x20D5632 | `call 0x200FFE0`（读队列）→ 1024 字节 → `call 0x201000C`（**投 AI 请求队列**） | 语音数据上行到 iTROS 会话 |
| 0x20D5658 | `r0 = b[r6+1]; if (r0 != 2) goto ...` | 消息类型门 |
| 0x20CDDAA | `ai-kws-off / ai-tts-off / ai-dgc-off / ai-ssp-off / ai-vadnn-off …` 配置键映射器（9 个配置结构体，基址 0x4070EE0，步长 672），调用者 `0x20D0CE8 / 0x20D512E / 0x20E12CA` | **AI 子引擎开关** |

### 1.3 如果一定要打补丁（不推荐，风险高）

候选（**保持指令长度不变**，需在目标机上用 `llvm-objdump -d` 复核编码后再写）：

```
A) va 0x20D54DA  app.bin off 0x0D53BA  原始: 00 e1 cf c3   （4 字节，条件分支）
   目标: 让 if 恒不成立（永远走 0x20D54DE 路径 B）→ 改成 "nop" 类指令
   ⚠ 该指令族是 `if (r0 != 1) goto ...`，直接 nop 会顺序落到 0x20D54DE，
     但 0x20D54DE 段依赖 r0 已有值，必须同时确认 r0=1（或在前面加 r0=1）
B) 更保险的做法：修补 **0x20D54D4 附近把 b[0x406D060+7988] 置 1 的那条 store**，
   或直接在 boot 流程里强制写 0x406D060+7988=1（找一条 `b[rX+7988] = 1` 的指令复制）
```

**建议**：跳过任务 1。理由：①`auth_isok` 不是门；②即使强开引擎，语音识别仍需真实麦克风输入 +
云端 ASR，无法用 mock 直接触发闹钟；③下面的"任务 2"方案更短、更可靠。

---

## 任务 2：BES 闹钟注入 —— **两条路，推荐 2A（直接改 BES 区，无需改任何代码）**

### 2A（推荐）：直接改 flash 的 BES 区 `0x7BF000`

**实证**：设备 BES 区是**明文 JSON**（无加密、无 CRC），当前内容 377 字节 + NUL 填充：

```
0x7BF000: {"bank_time":0,"alarm_up":1,"alarm":"{\n\t\"hb\":\t[{\n\t\t\t\"na\":\t\"闹钟\",
           \"th\":\t18,\"tm\":\t18,\"sd\":\t1792684800,\"wk\":\t63,\"wt\":\t0,\"s\":\t0}]}",
           "hp":"{\"typeid\":0,...}","rp":"...","task_sinfo":"","hp_sinfo":"","rp_sinfo":""}
```
- 现有 1 条闹钟，但 **`s:0`（未启用）** → 这就是为什么屏幕闹钟数看起来是 0
- 区大小 0x40000（0x7BF000..0x7FF000），其后是 `key_mac`（全 FF）

**可执行步骤**（已给你现成脚本 `work\bank\F0_bes_patch.py`）：
```powershell
cd "E:\Claude official\chat rabbit\work\bank"
python F0_bes_patch.py "你的整片dump.bin" "dump_patched.bin"          # 追加 2 条示例闹钟
python F0_bes_patch.py "你的整片dump.bin" "dump_patched.bin" --keep-existing  # 保留原有
```
脚本行为：在 0x7BF000 写入新 JSON（`alarm_up=1`、`s=1`），NUL 填充到 0x7C0000，其余字节不动。
→ 用 CH341A 把 `dump_patched.bin` 整片写回即可，**开机闹钟表里立刻有记录**。

闹钟内层格式（与固件现有格式完全一致）：
```json
{"hb":[{"na":"起床","th":7,"tm":30,"sd":1793030400,"wk":127,"wt":0,"s":1}]}
```
- `th` 时 / `tm` 分 / `sd` 起始日 unix 秒（本地零点）/ `wk` 位掩码（127=每天）/ `s` 启用
- 外层：`bank_time`、`alarm_up=1`、`alarm`、`hp`、`rp`、`task_sinfo`、`hp_sinfo`、`rp_sinfo`

### 2B（如果你坚持走 OTA 而不是整片刷）：默认 BES 模板等长替换

- 模板位置：**va 0x218FDDE = app.bin 偏移 0x18FCBE**，长度 **99 字节**（0x18FCBE..0x18FD21，0 结尾）
- 原文：`{"bank_time":0,"alarm_up":0,"alarm":"","hp":"","rp":"","task_sinfo":"","hp_sinfo":"","rp_sinfo":""}`
- ⚠ **该模板在代码中未找到任何引用**（绝对立即数/池基址全扫过）→ 很可能不被使用；
  靠它注入闹钟**大概率无效**（BES 是运行时从 flash 读、由 `alarm` 结构回写的）
- 若仍要试：下面是与原文**等长 99 字节**的替换字节（含 1 条 07:30 每天闹钟，尾部 2 空格填充）：

```
offsets/hex（app.bin 0x18FCBE 起，共 99 字节）:
7b 22 62 61 6e 6b 5f 74 69 6d 65 22 3a 30 2c 22 61 6c 61 72 6d 5f 75 70 22 3a 31 2c
22 61 6c 61 72 6d 22 3a 22 7b 5c 22 68 62 5c 22 3a 5b 7b 5c 22 74 68 5c 22 3a 37 2c
5c 22 74 6d 5c 22 3a 30 2c 5c 22 73 64 5c 22 3a 30 2c 5c 22 77 6b 5c 22 3a 31 32 37 2c
5c 22 73 5c 22 3a 31 7d 5d 7d 22 7d 20 20
```
（2 条闹钟需要 147 字节，**放不进 99 字节**，只能 1 条）

---

## 任务 3：ufw 布局与设备 dump 的一致性 —— **未达逐字节一致（差 0x20）**

### 3.1 已确认的事实

| 项 | 设备 dump | SDK 模板生成 | 差异 |
|---|---|---|---|
| 头 0xC0 字节 | vendor | 一致 | ✅ |
| uboot 数据 | 0xC0，长度 **0x3588**，末尾 0x3648 | 0xC0，长度 0x386C | ❌ 长度不同（SDK uboot 更大） |
| isd_config | 0x3648，长度 0x2BB（0x23 字节随机头 + 明文 TLV） | 0x392C 附近 | ⚠ |
| **app 区目录** | **0x4020** | **0x4000** | ❌ **差 0x20** |
| 0x3903..0x4000 | 全 `0xFF` | — | — |
| **0x4000..0x4020** | **32 字节非 FF 随机块** | 无 | ❌ 就是这 0x20 |
| app 区 ENC 单元起点 | **0x4020**（从 0x4020 用 phase 0 解出 `app_area_head`；从 0x4000 解出乱码） | 0x4000 | ❌ |

即：设备在 `0x4000..0x4020` 有一个**厂商工具写入的 32 字节非明文块**（疑似 per-area seed/key 或
`RESERVED_4K` 头），紧接着才是 `app_area_head` 的 ENC 单元 → 所以目录落在 0x4020。

### 3.2 为什么必须解决
SD/USB/OTA 升级包里的 JLFS 顶部目录记录的是**目标偏移**。若镜像写 0x4000 而设备按 0x4020 校验/挂载，
升级校验或挂载会失败（表现为"升级失败/黑屏/回滚"）。

### 3.3 两条可行路线（按风险排序）

**路线 A（推荐，零风险）**：不做重打包，直接用 **CH341A 整片写**。
- 你已有整片 dump + `F0_bes_patch.py` 生成的 `dump_patched.bin` → 直接写回，闹钟即生效
- 完全绕开 ufw 布局问题；CH341A 你已具备

**路线 B（要 OTA 才做）**：让 `isd_download` 产出目录在 0x4020。试法（按顺序）：
1. 用**厂商的 uboot.boot**（不是 SDK 的）——设备 uboot 数据长 0x3588，SDK 的是 0x386C；
   `isd_download` 只有用配套 uboot 才会把 app 目录推到 0x4020
   （已知：厂商 uboot 明文喂给 isd_download 会报 `Failed to generate flash bin`，
   需要先用 **ENC 加密成 flash 形态**再喂，或直接用 SDK 同版本 uboot 缩短长度）
2. 在 `isd_config_device.ini` 里增加/调整 `NEED_RESERVED_4K`、`RESERVED_OPT`、`SERIAL_SEND_KEY`、
   `KEY_MAC`、`NEW_FLASH_FS`，每改一次生成并比较 `app_dir` 偏移是否变为 0x4020
3. 若工具始终给 0x4000：**后处理平移**——把生成的 app 区整体 +0x20、把 0x4000..0x4020 填成厂商那 32 字节、
   并同步修正 JLFS 顶部目录里的 off 字段（0x20..0xC0 的条目）。此路需要 CRC 算法（JLFS 头/data CRC），
   目前未反出，风险中等

### 3.4 当前交付物
- `work\bank\F0_bes_patch.py` —— BES 闹钟注入（可用，已在你 dump 上验证）
- `work\bank\97_probe_gap.py`、`98_chunked.py` —— 100KB 失步区重反汇编（已产出 `merged2.txt`）
- `work\ufw\make_ufw.ps1` —— 一键生成 `out\update.ufw`（SD/USB）与 `out\update-ota.ufw`（OTA），
  但**布局仍是 0x4000**，未达逐字节一致 → 现阶段不建议用于真机刷写

---

## 一句话结论

1. **任务 1 放弃**：`auth_isok` 不是补丁点（日志串零引用），真正的门是 `b[0x406D060+7988]`（0x20D54DA）与 AI 配置键（0x20CDDAA）。
2. **任务 2 走 2A**：BES 区是明文 JSON，`F0_bes_patch.py` 直接注入闹钟，CH341A 整片写回即生效（现有闹钟 `s:0` 所以看起来是 0 条）。
3. **任务 3 未达成**：ufw 目录差 0x20（设备 0x4020 / 生成 0x4000），根因是厂商 uboot 长度与 0x4000 处 32 字节私有块；
   **现阶段请用 CH341A 整片写，不要用 ufw**。
