# PATCH_INJECT — 可注入补丁方案（刷一次 → 之后 MQTT 控制闹钟）

> 第 8 轮 · bank-analyst · 数据源：`work\chat rabbit-puya.bin`（8 MiB 整片 dump）、`work\dev_app_plain.bin`

---

## 0. 已经打通的关键一环：**补丁注入流水线可用，且往返逐字节一致**

### 0.1 布局（实测确认，不是推测）

| 项 | 值 |
|---|---|
| app 区 flash 起址 | **0x4020**（JLFS 条目 off=0x4020，len=0x1ED423） |
| ENC 算法 | LFSR，32 字节块，`key_idx = (delta>>2)&0xFFFF`，`key = 0xFFFF ^ key_idx`，逐字节 `d ^= key&0xFF; key = ((key<<1)&0xFFFF) ^ (key&0x8000 ? 0x1021 : 0)`，**phase 0 于 0x4020** |
| 区明文 0x0000..0x00FF | 区域头：`app_area_head`(0x20) + 组表/信息(0xE0) |
| 区明文 0x0100 起 | = `dev_app_plain.bin`（va 0x2000100 起） |
| 区明文 0x0120 起 | = `app.bin`（va 0x2000120 起，即代码段起点） |
| 区外 | 0x1F1443 之后到 0x2CC6B4 非 FF（其它数据区），0x2CC6B4..0x3BC0A0 大段 FF |

### 0.2 工具（已交付并验证）

```powershell
cd "E:\Claude official\chat rabbit\work\bank"

# ① 用"改好的 dev_app_plain.bin"生成整片镜像（自动重新加密 app 区，其余字节不动）
python H0_flash_patch.py "chat rabbit-puya.bin" modified_dev_app_plain.bin out.bin

# ② 只往 BES 区注入闹钟（明文 JSON，无需改代码）
python F0_bes_patch.py out.bin out_final.bin            # 或加 --keep-existing
```

**验证结果**：把未改动的 `dev_app_plain.bin` 喂给 `H0_flash_patch.py`，输出与原始 dump
**逐字节完全一致（diffs = 0）** → ENC/布局模型精确，补丁只会改变你改动的那几个字节的密文。

---

## 1. 建议的 hook 点（最重要的一节）

### 1.1 为什么不能 hook iTROS 命令通道
`socket→0x2022864→router 0x2020242` 这条链只有在 **iTROS 会话已建立**（设备主动连
`smartdevice.ai.turingapi.com:80`）时才会跑；实测设备从不连该域名 ⟹ 这条链**永远不执行**，
hook 它没有意义（`0x202499A` 处注册 `0x201BA04` 的那条 imm32 可以改，但改了也不会被调用）。

### 1.2 推荐 hook：**JSON 解析器 `CMLoadFromBuffer` = 0x200843A**
理由：
- 设备能收 MQTT、能按 `type` 播音频 ⟹ MQTT 消息一定被 JSON 解析过；
- 厂商代码里 JSON 解析**统一走** `CMLoadFromBuffer`（我们已在 AT/OTA/云端多处见到该调用）；
- hook 它 = **对所有 JSON 入口一网打尽**，不需要先找到 MQTT 回调地址；
- 函数入口干净（`r0` = JSON 字符串指针），stub 里可以先 strstr 判断，再决定是否干预，最后原样调用原函数。

### 1.3 hook 方式（免手写机器码）
既然 `clang --target=pi32v2` 可用，**不要手写跳转编码**：
```c
// hook.c  编译到固定 va（例：0x20C3D00，见 §4 空闲代码区）
extern char *CMLoadFromBuffer(const char *js);
char *my_CMLoadFromBuffer(const char *js);   // 我们的入口

// 在 0x200843A 处把前 8/12 字节替换成"call my_CMLoadFromBuffer + 返回"，
// 由 clang 生成这 4~6 字节，无需人工推导 pi32v2 的 call 编码。
```
具体：用 clang 把 `void hook(void){ my_CMLoadFromBuffer(0); }` 编译，取那 4~6 字节 `call` 指令，
覆盖到 `0x200843A`；被覆盖的原指令在 stub 里先执行再跳回 `0x200843A+N`。
> 也可以更省事：把 `0x200843A` 前 4~6 字节覆盖成 `call <stub>`，stub 里保存/恢复寄存器后
> 调原函数体（原函数入口前几条指令是 `[--sp] = {rets,...}; sp += -N;`，在 stub 里重放即可）。

### 1.4 stub 逻辑（伪代码）
```c
char *my_CMLoadFromBuffer(const char *js) {
    if (js && strstr(js, "\"@ALARM\"") ) {          // 我们约定的标记
        const char *u = strstr(js, "\"url\"");      // 或直接找我们的 base64/hex 负载
        if (u) apply_alarm_from_payload(u);
        return NULL;                                // 吞掉这条消息
    }
    return CMLoadFromBuffer(js);                     // 其它消息原样放行
}
```
`apply_alarm_from_payload()` 内部（已确定路由，见 §2）：
```c
// 方案 A（最省事：直接走内部命令分发器，参数是设备自己的信封格式）
char buf[256];
strcpy(buf, "{\"opt_code\":\"alarm_set\",\"payload\":{\"loop\":1,\"hour\":7,"
            "\"mintue\":30,\"second\":0,\"year\":2026,\"month\":10,\"day\":27,"
            "\"event\":\"alarm\"}}");
((void (*)(int, char *, int))0x201BA04)(2, buf, strlen(buf));

// 方案 B（走 router，网络侧格式）
char buf2[256];
strcpy(buf2, "{\"code\":1000680,\"func\":{\"action\":\"add\",\"cycleType\":\"1\","
             "\"hour\":7,\"mintue\":30,\"second\":0,\"year\":2026,\"month\":10,"
             "\"day\":27,\"event\":\"alarm\"}}");
((void (*)(int, char *))0x2020242)(2, buf2);
```
> 方案 A 直接打到**分发器**（绕开 router 的 `code` switch，`code` 值未知的问题就不存在了）；
> 方案 B 更"正规"但要先定 `code`（未知，跳表在 SDRAM 0x40742A0）。
> **建议先用方案 A**。

⚠ 注意：`alarm_set` 分支（0x201C070）从 payload 只读 `loop`（由 `cycleType` 映射）等少量键，
其余键（hour/mintue/…）是否被读，§14 未完全钉死 → 若闹钟时间不对，直接改用方案 C：
**在 stub 里自己拼 BES JSON 并写 flash 0x7BF000**（见 §5，完全可控，不依赖固件内部逻辑）。

---

## 2. 函数地址表（全部经反汇编核对）

| 用途 | 地址 | 备注 |
|---|---|---|
| JSON 解析（hook 目标） | **0x200843A** | `CMLoadFromBuffer(const char*)` → cJSON* |
| JSON 取键（对象成员） | **0x20084FE** | `cfg_get(tree, const char*)` → item*（item+16=字符串, item+20=数值） |
| cJSON 建对象 | 0x2004954 | `cJSON_CreateObject()` |
| cJSON 加字符串 | 0x2004B56 | `cJSON_AddStringToObject(obj, k, v)` |
| cJSON 加子对象 | 0x2004C04 | `cJSON_AddItemToObject(obj, k, item)` |
| cJSON 打印 | 0x2005886 | `cJSON_PrintUnformatted(obj)` |
| cJSON 释放 | 0x200588C | `cJSON_Delete(obj)` |
| strstr | 0x20E5B32 | |
| strlen | 0x20E59DE | |
| memcpy | 0x20E549E | |
| memset | 0x20E5588 | |
| sprintf | 0x2003908 | `_sprintf(buf, maxlen, fmt, ...)` 形式 |
| malloc | 0x40018B6 | **SDRAM 段**（0x4000000 起，运行时有效） |
| free | 0x4001250 | SDRAM |
| **router** | **0x2020242** | `void router(int type, const char *json)` |
| **内部命令分发器** | **0x201BA04** | `void dispatch(int type, const char *json, int len)` |
| alarm_set 分支 | 0x201C070 | 分发器内部，由 `opt_code=="alarm_set"` 进入 |
| alarm_del 分支 | 0x201C182 | `opt_code=="alarm_del"` |
| 注册分发器 | 0x201DD8C | `register_handler(fn)`（存到 `0x406D060+3261*4`） |
| AI 请求队列发送 | 0x201000C / 0x200FFE0 | 队列句柄 `0x406D060+3509*4` |
| iTROS 会话任务 | 0x202482A | `app_iTROS_task` 入口（prio 22 / stack 4096） |
| 语音 worker | 0x20D5198 | 内含 `[auth_addr]`、`lenght!=1024` 日志 |
| msh shell 注册 | 0x20392C4 | 字符串命令分发（含 `mqttConn` 等） |
| BES 区 | **flash 0x7BF000**（0x40000） | **明文 JSON** |

---

## 3. 注入位置（可执行代码空间）

**坏消息**：app.bin（0x20 起 0x1ED403 字节）**没有任何 ≥256 字节的 0xFF 空洞**（全区段已满），
不能简单"追加到末尾"——追加会改变区长度（JLFS 条目 len、后续区偏移全变）。

**好消息**：固件里有大块**确认死亡**的代码可以直接覆盖（我们已证明它们从未被调用）：

| 死代码区（va） | 说明 | 依据 |
|---|---|---|
| **0x201945A .. 0x2019528** | `wws` 模块 init（填回调表 + URL + 建任务） | 全镜像对该地址**零引用**；它安装的回调指针（0x20C40C8/0x20C45F2/0x203C37A…）除了这段 init 之外**没有任何静态表持有** |
| **0x20C3CB8 .. 0x20C4xxx** | `wws` 客户端实现（recv/heart/编解码回调） | 仅被上面的死 init 引用 |
| 0x20C3E8E / 0x20C3CB8 / 0x20C40C8 / 0x20C45F2 | 上述回调本体 | 同上 |

⟹ **把 stub 放进 0x20C3D00 一带**（在死 wws 代码内），最安全；剩余死代码还够放 1~2 KB。

> 若你想要更大的空间：`0x218FDDE` 之后的连续 rodata 区（含 `D:\liang_work_2024\...` 一批
> 只用于日志的路径串）也可覆盖，但那不是可执行意图的段——**不推荐**。

---

## 4. JLFS 校验（`data_crc`）的现状 —— **未能反出，需要 A/B 实测**

- 顶部目录每条记录 0x20 字节：`[u32 crc?][u32 off][u32 len][u32 ?][char name[16]]`
  例：`uboot.boot` → crc=0xB3ED631B, off=0xC0, len=0x3588
- 已尝试 **16 种 CRC-32/16 变体**（reflected/non-reflected、init 0/0xFFFFFFFF、xorout 0/0xFFFFFFFF）
  × 明文/密文，以及 adler32 → **全部不匹配**
- 固件里确实有两张 CRC 表：0x2199108（reflected，poly EDB88320）、0x2198D08（non-reflected，poly 04C11DB7），
  但该 u32 与它们算出的值都不一致 ⟹ 它更可能是 isd_download 写入的**随机 sign / 每文件 seed**，
  而非内容 CRC
- **实测判定方法（建议第一步就做）**：
  1. 只改一个无害字节（例如把某条日志串里的一个字符改掉），用 `H0_flash_patch.py` 生成镜像
  2. 什么都不改（不动 JLFS 条目）直接 CH341A 写回
  3. 若能正常开机 ⟹ **该字段启动时不被校验**（大概率，因为它是"升级时"的完整性校验）
  4. 若不开机 ⟹ 需要同时改该字段；此时把 4 字节清 0 或填 FF 各试一次（有些实现 `crc==0` 视为跳过）

> 另外：app 区明文 0x0000 处的 `app_area_head` 首 u32 = `0x0B7D636C`，也不是内容 CRC（已测），
> 同样视为 sign/seed。

---

## 5. 方案 C（最可控）：stub 直接写 BES 区

如果 §1.4 的方案 A/B 因内部逻辑细节（哪些键被读）不生效，就让 stub 自己拼 BES JSON 并**写 flash**：

```c
// 1) 拼 BES JSON（格式见 §6）
// 2) 定位 BES 区基址：flash 0x7BF000。JieLi 用 norflash 读接口写：
//    先按 4KB 扇区擦除，再写入；对应 API 在 SDK 里是 norflash_erase/norflash_write
//    （地址未在本轮反出 → 需你按 SDK 符号表 / 或用 msh 的 flash 命令确认）
// 3) 写入后重启生效（或直接调用 BES 重载函数）
```
⚠ 这一步依赖 norflash 写 API 的地址，本轮未定位；若你要走这条，我可以再花一轮把
`norflash_erase_sector/write` 与 BES 重载函数找出来。

---

## 6. BES 闹钟 JSON（可直接用 `F0_bes_patch.py` 生成）

```json
{"bank_time":0,"alarm_up":1,
 "alarm":"{\"hb\":[{\"na\":\"alarm\",\"th\":7,\"tm\":30,\"sd\":1793030400,\"wk\":127,\"wt\":0,\"s\":1}]}",
 "hp":"","rp":"","task_sinfo":"","hp_sinfo":"","rp_sinfo":""}
```
- `th` 时 / `tm` 分 / `sd` 本地零点 unix 秒 / `wk` 位掩码(127=每天) / `s` 1=启用
- 设备现有 BES 里就有一条 `s:0` 的闹钟（`na:"闹钟"`），所以屏幕看起来"0 条"

---

## 7. 落地步骤（建议顺序）

1. **先做 §4 的 A/B 验证**（改 1 字节 → 整片写 → 看能否开机）→ 确认 JLFS 校验是否必须同步
2. **写 stub**（clang pi32v2，链接到 0x20C3D00）+ hook `0x200843A`
3. `H0_flash_patch.py` 生成镜像 → CH341A 写 → 上电
4. 从 App 发 MQTT：`{"type":1,"message":{"arg":"@ALARM","url":"<json>","mediaId":1,"operate":1}}`
5. stub 命中 `@ALARM` → 解码 url → 走 §1.4 方案 A（或写 BES）
6. 若第 4 步 MQTT 消息根本没到 stub：换 hook 点到 MQTT 回调（需运行时定位，见下）

## 8. 仍未静态确定的三点（明确告知）
1. **MQTT 消息回调函数地址**：未定位。本轮找到了 *发送* 侧（`0x202437E` 拼
   `{"apiKey":…,"type":%d,"message":{"mediaId":"%s"}}`）与 `mediaId` 解析（0x20381EA），
   但收包回调没找到 —— 所以建议用 §1.2 的"hook JSON 解析器"绕开它。
2. **JLFS 条目 u32 的算法**（§4）——非标准 CRC，需 A/B 实测。
3. **norflash 写 API 地址**（§5 方案 C 需要）。
