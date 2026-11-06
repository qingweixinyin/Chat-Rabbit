# PATCH_INJECT3 — 双编码重扫后的真实地址表 + hook 字节 + 代码洞结论

> 第 10 轮 · bank-analyst。基准：`dev_app_plain.bin`，`va = 0x2000100 + file_off`。

---

## 0. 统一扫描器（两种 call 编码都覆盖）已实现并自检

| 编码 | 字节 | 目标计算 | 数量 |
|---|---|---|---|
| 长调用 | `80 ff <rel32>` | `site + 6 + rel32` | 10,299 |
| 短调用 | `bf ea <rel16>` | `site + 4 + rel16*2` | 14,443 |
| 合计 | | | **24,742** |

自检：`site 0x20085F4 --bf ea--> 0x200843A` ✓ 与你给的例子一致。
脚本：`work\bank\J0_scan2.py`（可复算）。

---

## 1. ★ JSON 解析器真实地址（hook 目标）

### 1.1 证据链（不依赖任何符号名）
分发器 **0x201B9F4** 的入口：
```
201b9f4: [--sp] = {rets, r15-r4}
201b9f6: sp += -384
201b9f8: r0 = r1                     ; r1 = 第二个参数 = JSON 文本
201b9fa: call 0x200843A              ; ★ 解析调用
201b9fe: r12 = r0                    ; 树
201ba00: r11 = 0x2186678             ; "…pMsg cJSON_Parse fail"（0x2186678 = 串起点）
201ba0c: if (r0 == 0) goto 0x201bc58 ; 解析失败 → 打上面那条日志
201ba18: r1 = <pool 0x217E530> + 3923 ; "payload"
201ba1c: call 0x20084FE              ; cfg_get
201ba2e: r1 = <pool> + 4944           ; "opt_code"
201ba34: call 0x20084FE
```

⟹ **0x200843A 就是把字符串解析成树的入口**，解析失败立刻打 `pMsg cJSON_Parse fail`。
它的实现体在 **0x20083B6**（先 `strlen` → `malloc` → 逐字符解析，典型 cJSON_Parse）。

> ⚠ **重要更正（对上一条的自我纠正）**：objdump 显示的 `<_syscfg_bin_read+0x..>` 这类名字
> 是**从 sdk.elf 按"最近符号"猜出来的，不是本固件符号**，不能当函数身份依据。
> 所以"0x200843A 是 syscfg 包装"这个判断是被假符号误导了；它实际就是 JSON 解析入口。

### 1.2 入口原始字节（你要的 hook 覆盖用）

| 地址 | 前 12 字节 | 结构 |
|---|---|---|
| **0x200843A**（薄包装，尾调用体） | `10 04 51 9c 00 04` | `[--sp] = rets`(2B) + `call 0x20083B6`(4B) + `pc = [sp++]`(2B) = 共 6 字节可整段覆盖 |
| **0x20083B6**（真正的解析实现） | `78 04 e2 99 04 16 88 80 45 20 00 86` | `[--sp] = {rets, r8-r4}` + `sp += -28` + … |

**推荐 hook 0x200843A**：它正好是 6 字节尾调用包装，用 `80 ff <rel32>`（也是 6 字节）
**等长整段替换**，不破坏任何相邻指令：
- 把 `0x200843A..0x200843F` 的 `10 04 51 9c 00 04` 换成 `80 ff <rel32>` → 跳你的 stub
- stub 参数：`r0` = JSON 字符串指针（就是原来传给包装函数的那个）
- stub 里若要放行：直接 `tail-call` 到 0x20083B6（或 call 后原样返回）即可
- `file_off = 0x833A`（CH341A 直接改这 6 字节）

若你更想 hook 真正的解析体 0x20083B6：覆盖其前 6 字节 `78 04 e2 99 04 16`（需在 stub 里
重放被覆盖的 1~2 条指令后跳回 `0x20083BC`）—— 能做但你得多写几行汇编。

---

## 2. 修正后的关键地址表（已用双编码扫描 + 绝对指针双重核对）

| 用途 | **真实地址** | 佐证 |
|---|---|---|
| **JSON 解析入口（推荐 hook）** | **0x200843A** | 分发器 0x201B9F4 直接 call；10 个短调用点（0x20085F4/0x20091FE/0x200A5FA/0x200CCFA/0x200CE6E/0x200CFE2/0x200D162/0x200D2A2/0x200D3F2/0x200D51E） |
| JSON 解析实现体 | **0x20083B6** | 0x200843A 的尾调用目标 |
| cfg_get（取键） | **0x20084FE** | 264 个调用点（全库最高之一） |
| **dispatcher**（`opt_code` 分发，`(type,json,len)`） | **0x201B9F4** | 明文 foff `0x2488C` 处有绝对 imm32 `f4 b9 01 02`；分发器内部结构见 §1.1 |
| **router**（按 `code` switch，`(type,json)`） | **0x2020242** | 明文 foff `0x1AB8E6`、`0x1AB9E7` 两处绝对 imm32 `42 02 02 02` |
| memcpy / memset / strcmp / strlen / memcmp / strstr | 0x20E548E / 0x20E5578 / 0x20E58C0 / 0x20E59CE / 0x20E5456 / 0x20E5B22 | 调用数 830/721/563/367/271/207 |
| malloc / free | 0x40018B6 / 0x4001250 | 210 / 954（SDRAM 段，无偏移） |
| 格式化输出 | 0x2001FE0 | 253 个调用点（`sprintf` 实际走这里） |
| cJSON 结构偏移 | `type`+0、`valuestring`+16、`valueint/value`+20 | dispatcher 内 `[r0+16]`/`[r0+20]` 普遍用法 |

---

## 3. 代码洞结论（0x20C3CA8..0x20C40F0）

### 3.1 绝对指针审计（全库 u32 扫描）
**只有 3 个**指针指向该窗口，且**全部存放在死 wws init 里**：

| 指针值 | 存放位置 | 是否死代码 |
|---|---|---|
| 0x20C3CA8 | va 0x20194D4（foff 0x193D4） | ✅ 死 init 内 |
| 0x20C3E7E | va 0x20194CC（foff 0x193CC） | ✅ 死 init 内 |
| 0x20C40B8 | va 0x20194E4（foff 0x193E4） | ✅ 死 init 内 |

窗口外另有 2 个（`0x20C40F0`@data foff 0x1AE5ED、`0x20C4100`@data foff 0xEE2C3）→ **不属本窗口，但你若扩到 0x20C4100 就要当心**。

### 3.2 但要小心：窗口内**有 4 个被 call 的入口**
| 目标 | 被调次数 | 调用者位置 | 判读 |
|---|---|---|---|
| 0x20C3EC6 | 5 | 0x20C4246/0x20C4276/0x20C429E/0x20C4306/0x20C4356 | 调用者都在 0x20C42xx（同一死模块） |
| 0x20C3EE4 | 3 | 0x20C4420/0x20C44CC/0x20C44FE | 同上 |
| 0x20C40A0 | 1 | 0x20C45D0 | 同上 |
| 0x20C40E4 | 1 | **0x20BDBF2** | ⚠ 调用者在窗口外，需你确认该函数是否活 |

### 3.3 结论（可直接执行）
- **能整段覆盖**的前提：0x20BDBF2 所在函数也是死代码。要稳妥，**避开这 4 个入口**：
  - **洞 A：0x20C3CA8 .. 0x20C3EC6 = 542 字节**（窗口内无任何 call 入口）
  - **洞 B：0x20C3EE4 .. 0x20C40A0 = 444 字节**
  - **洞 C：0x20C40E8 .. 0x20C46C0 = 1496 字节**（首个已知入口在 0x20C46C4，之前为空）
- 你的 stub 是 `.text 210B + .rodata 522B = 732B`：
  - 建议 **代码放洞 A（542B 够 210B 代码 + 预留）**，**rodata 放洞 B（444B）或洞 C**
  - 或整体放 **洞 C（1496B）**，把 stub 链接基址设为 **0x20C40E8**（你原计划 0x20C3D00 落在洞 A 中间，也可行）

---

## 4. 给 lead 的落地清单（一步一步）
1. `6 字节覆盖`：`file_off 0x833A` ← `80 ff <(stub - (0x200843A+6)) 小端 rel32>`
   （即 `target = stub`，`rel32 = stub - 0x2008440`）
2. stub 逻辑：`if (strstr(r0, "\"@ALARM\"") || strstr(r0,"@ALARM")) { /* 处理 */ }`
   → 放行时 `return ((void*(*)(const char*))0x20083B6)(r0);`
3. 处理里调分发器（绕开未知 `code`）：
   `((void(*)(int,char*,int))0x201B9F4)(2, buf, strlen(buf));`
   其中 buf = `{"opt_code":"alarm_set","payload":{"loop":1,"hour":7,"mintue":30,"second":0,"year":2026,"month":10,"day":27,"event":"alarm"}}`
4. `python H0_flash_patch.py <dump> <你的 dev_app_plain> <out>` → CH341A 写
5. 若 MQTT 消息根本不走 JSON 解析（发一条普通消息测），则改用 §2 的 dispatcher 注册点
   `file_off 0x2488C` 覆盖（前提是 iTROS 会话起来了）

---

## 5. 仍需确认（诚实标注）
- **MQTT 收包函数**本身仍未定位（`MQTTSubscribe failed` 串正好位于池基址 0x2186540，163 处引用都是"池基址加载"，无法唯一确定）。
  但 §1 的 hook 覆盖"所有走 JSON 解析的入口"，对 MQTT 消息只要它也解析 JSON 就会被抓到。
- 0x20BDBF2 所在函数是否活（决定洞 A/B/C 之外的 0x20C40A0/0x20C40E4 是否安全）。
