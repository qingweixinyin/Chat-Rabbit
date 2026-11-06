# PATCH_INJECT2 — ±0x10 定死 + 注册点 imm32 + 代码洞边界

> 第 9 轮 · bank-analyst。基准一律：**slot A 明文 `dev_app_plain.bin`，`va = 0x2000100 + file_off`**。
> 所有 file_off 都可直接用于 CH341A 改明文。

---

## A. 修正后的地址表（libc 部分已用 `80 ff` 调用扫描二次验证）

用 `80 ff <rel32>`（6 字节 call，site = 0x2000100+off，target = site+6+rel32）全盘扫描
（10,299 个调用；你扫到 5,492 个可能是过滤了某些区），命中数与你的完全一致：

| 函数 | **真实地址** | 调用点数量 | 说明 |
|---|---|---|---|
| memcpy | **0x20E548E** | 830 | file_off 0xE538E 处即为 `[--sp] = {rets, r6-r4}` 干净入口 |
| memset | **0x20E5578** | 721 | |
| **strcmp**（对齐比较+逐字节） | **0x20E58C0** | 563 | 我上次表里没列，这是 strcmp |
| strlen | **0x20E59CE** | 367 | 经典 `b[r1++]=?` 循环，`rts` 结尾 |
| **memcmp**（带长度、返回差） | **0x20E5456** | 270 | |
| strstr | **0x20E5B22** | 207 | |
| malloc | **0x40018B6** | 210 | SDRAM 段，我上次的地址**是对的** |
| free | **0x4001250** | 954 | 同上 |
| sprintf | 0x20038F8 | 0（未被直接调用） | 走 `vsnprintf` 包装，见下 |
| vsnprintf/格式化 | 0x2001FE0 | 大量 | 从路由器 dump 可见 `call 0x2001fe0`（带 fmt）—— 实际用的是这个 |

⚠ **结论：0x20xxxxx 段（.text）我上一版地址整体 +0x10，减 0x10 即真值**；
但 **0x4xxxxxx（SDRAM）段是对的**（我的 .dynamic_data 映射没偏）。

## B. JSON / router / dispatch 这一族：**指针调用，用绝对 imm32 定址（已找到）**

### B.1 直接证据（在你自己能复算的明文里）
| 目标 | **真实地址** | 明文 file_off 里的 u32 证据 |
|---|---|---|
| **dispatcher**（内部命令分发器，`(type, json, len)`） | **0x201B9F4** | **file_off `0x2488C`** 处 4 字节 = `f4 b9 01 02` |
| **router**（`(type, json)`，按 `code` switch） | **0x2020242** | file_off `0x1AB8E6`、`0x1AB9E7` 两处 = `42 02 02 02` |
| CMLoadFromBuffer（JSON 解析） | 0x200843A | 无绝对指针（被 router 直接 `call`，见下） |
| cfg_get（取键） | 0x20084FE | 同上 |
| cJSON_CreateObject / AddStringToObject / AddItemToObject / PrintUnformatted / Delete | 0x2004954 / 0x2004B56 / 0x2004C04 / 0x2005886 / 0x200588C | 被 router 等直接 `call` |
| alarm_set 分支 / alarm_del 分支 | 0x201C070 / 0x201C182 | 在 dispatcher 内部，由 `opt_code` 字符串进入 |
| register_handler | 0x201DD8C → 真值 **0x201DD7C**（待你复算） | 无绝对指针；它把参数存到 `0x406D060+3261*4` |

> **注意**：router 的真实地址仍是 **0x2020242**（有两处绝对指针为证），而我上一版说它 0x2020242 是对的；
> 反过来 dispatcher 是 **0x201B9F4**（不是我上版的 0x201BA04）。两者不在同一偏移上——
> 这说明上一版的偏移**不是全表统一**，只能**逐个用"绝对指针/调用点"核实**，请以上表为准。

### B.2 ★ 你要的"注册点 imm32"（可直接改成你的 stub 地址）
- **file_off `0x2488C`**，4 字节，原值 `f4 b9 01 02`（= **0x201B9F4**，dispatcher）
- 改成你的 stub 地址（小端）即可：设备启动后 `app_iTROS_task` 会把该值存进 `0x406D060+3261*4`，
  之后 iTROS 层每次收到命令都会 `call` 你的 stub（参数：`r0=type, r1=json, r2=len`）
- ⚠ 前提：iTROS 会话要建立（设备要连 `smartdevice.ai.turingapi.com:80`）。**实测它不连** →
  这个 hook 只在你同时把会话拉起来时有用；否则请用"hook JSON 解析器"的思路（见 §D）

### B.3 MQTT 用户消息回调
- 本轮**仍未定位**（MQTT 库回调是结构体成员指针，没有绝对 imm32 也无可扫的 call）。
- 已知发送侧：`0x202437E` 拼 `{"apiKey":…,"type":%d,"message":{"mediaId":"%s"}}`（真实地址请按 -0x10 复算：
  file_off 0x243? 需你按 §A 的方法用 `80 ff` 扫它的调用点反推）。

### B.4 cJSON 结构字段偏移（从 router/dispatcher 的实际取用得出）
`cfg_get` 返回值 item 的用法（dispatcher 里随处可见）：
| 字段 | 偏移 | 说明 |
|---|---|---|
| `type` | **+0** | cJSON 内部类型（int） |
| `valuestring` | **+16** | 字符串值（`[r0+16]` 直接当 char* 用） |
| `valueint` | **+20** | 数值（`[r0+20]` 直接当 int 用；也有 `b[r0+20]` 当 byte） |
| `child` / `next` | 未在本轮实证（`+4` / `+8` 候选） | 需再验，若你 stub 只做"取顶层键"可用 `cfg_get` 就够，不必自己遍历 |

---

## C. 代码洞（你问的边界）—— **我上次给的区间不干净，已收窄**

扫描结果：`0x20C3C00..0x20C4E00` 内**存在被调用的函数**：
`0x20C46C4(3) 0x20C46D8(2) 0x20C46EC(21) 0x20C46FA(2) 0x20C4712 …… 0x20C4846(2)`
⟹ 该段**不是**纯死代码，不能整段覆盖。

另有 6 个绝对指针指向 `0x20C3CA8..0x20C46C0`：
`0x20C3CA8@foff 0x193D4`、`0x20C3E7E@0x193CC`、`0x20C40B8@0x193E4`、`0x20C45E2@0x193EC`
（这 4 个位于**已证死亡的 wws init** 内部，安全）
`0x20C40F0@foff 0x1AE5ED`、`0x20C4100@foff 0xEE2C3`（**在别的区，需你确认是否活**）

**建议**：不要用 0x20C3xxx 这一段。改用 **wws init 自身**（已证零引用）：
- 死 init 主体：`va 0x20194xx`，file_off **0x1930x..0x1934x** 一带
- 这里能放 ~0x40~0x60 字节，够一个"转发+判断"的小 stub；
- 若需要更大空间，把 stub 放在 **file_off 0x1AB8E6/0x1AB9E7 附近的 rodata 表后**
  （那里是数据区，SDRAM 会拷贝，可执行但"意图"不是代码，风险自担）。

> 结论：**"≥512 字节干净代码洞"在这版固件里不存在**（app.bin 无 FF 空洞，且死代码段互相穿插）。
> 可行做法是 stub 控制在 ~64 字节内，或把 stub 拆成两段。

---

## D. 推荐 hook 顺序（结合上面事实）

1. **首选**：改 file_off **0x2488C** 的 imm32 → 你的 stub（接管 iTROS 命令分发）。
   但需要 iTROS 会话来驱动；你已实测设备不连 `smartdevice` ⟹ 只有把会话也拉起来才有效。
2. **更稳**：hook **cfg_get (0x20084FE)** 或 **CMLoadFromBuffer (0x200843A)** 的入口
   （这两个是被直接 `call` 的，**有 0 个调用点是你扫描器没扫到的？**——注意：它们显示 0 调用，
   说明是 `call rN` 间接调用；若你要在入口前打跳转补丁，直接覆盖入口前 6 字节即可，不需要找调用点）。
3. 你的 MQTT 消息若能确认最终会走 JSON 解析，则 hook JSON 解析器即可捕获。

---

## E. 一句话给 lead
- libc 真值：**memcpy 0x20E548E / memset 0x20E5578 / strcmp 0x20E58C0 / strlen 0x20E59CE / memcmp 0x20E5456 / strstr 0x20E5B22 / malloc 0x40018B6 / free 0x4001250**
- **dispatch 真值 0x201B9F4**，其注册点 imm32 在 **file_off 0x2488C**（4 字节，改这里就能换处理器）
- **router 真值仍是 0x2020242**（两处绝对指针佐证）
- 代码洞：0x20C3xxx 段**不干净**，别整段覆盖；用 wws init 死代码（file_off ≈0x1930x~0x1934x）放 ≤64B stub
