# 聊天兔子（杰理 AC791N）逆向 —— 交接文档

> 最后更新：2026-10-25 13:47
> 下次继续先读本文件，再读 `REPORT.md`（约 855 行）

## 一、当前状态一句话

**固件加密 100% 破解、完整明文已拿到、官方工具链已装好、可重新打包**；
云端闹钟协议测试失败（设备不响应明文 MQTT 控制消息）；
**下一步：先恢复环境 → OTA 远程刷机验证 → 然后做 App（配对/音乐可做，闹钟需补丁，对讲不支持）**。

## 二、⚠️ 环境已变更（2026-10-25 检查，必须先恢复）

| 项 | 原状态 | 现状态 |
|---|---|---|
| 路由器 | ASUS AC68U（asus/Abcd1234，armv7） | **RT-AX89X**（SSH 密码不对，需确认 `uname -m`） |
| mock 云 :8898 | 运行中 | **未运行** |
| 玩具 192.168.6.106 | 在线 | **离线** |
| 本机 IP | 192.168.6.x | 192.168.6.157 |

### 恢复清单

1. 登录 RT-AX89X（确认 SSH 端口/账号/密码；`uname -m` 看 aarch64 还是 armv7）
2. 重新交叉编译 mock 云（aarch64 → `GOOS=linux GOARCH=arm64`；源码 `router\src\main.go`）
3. 部署 `/koolshare/chatrabbit/bin/chatrabbit` + ctl 脚本启动
4. 重建 iptables 重定向（→8081/1883）+ DNS 劫持 `turingapi.com`
5. 玩具上电联网（连不上就 BLE 重新配网）
6. 验证 `curl http://<路由IP>:8898/api/status` → device_online=true

## 三、关键结论（已验证）

### 1. ENC 加密算法（100% 验证 243344/243344）

```c
// 每 32 字节块：
key = (0xFFFF ^ (((load_addr + block_offset) >> 2) & 0xFFFF)) & 0xFFFF;
for (i = 0; i < 32; i++) {
    data[i] ^= key & 0xFF;
    key = (key << 1) ^ ((key & 0x8000) ? 0x1021 : 0);
}
```

- 设备 app 区：flash `0x4120` 起，load `0x2000120`，解密相位 p=0x40
- 映射：`mem = flash + 0x1FF9000`
- 明文产物：`work/dev_app_plain.bin`（2,020,387 字节）

### 2. 闹钟相关

- `operate=8`(CONCROL_SET_CLOCK) 在 SDK v1.2.0 中未实现（default: no support）
- 图灵云闹钟 = 一次性定时器（不持久、不写 VM）
- 厂商固件另有 `alarm_set`/`alarm_del`/`startDate`/`cycleType` 字段（flash 0x183B98 附近）
- VM 闹钟列表：flash `0x7BF000`，NUL 结尾 JSON，**无 CRC/长度头**；默认 `{"hb":[]}`/`{"rp":[]}`
- MQTT 明文 8 组格式矩阵测试 → 设备无反应

### 3. OTA 触发条件（源码确认，下一步主攻）

`/iot/v2/ota/initVersion` 响应必须为：

```json
{"payload":{
  "updateStatus":0,
  "agree":1,
  "id":12345,
  "nextVersion":"1.0.1",
  "currentVersion":"<设备当前版本>",
  "firmwareUrl":"http://192.168.6.1:8081/update.ufw"
}}
```

### 4. App 功能可行性（2026-10-25 结论）

| 功能 | 可行性 | 说明 |
|---|---|---|
| 配对（配网+绑定） | ✅ | BLE 服务 0xFFFF：FF01 写/FF02 通知；传 SSID/pwd/openid（SDK `le_net_cfg_turing.c`） |
| 音乐播放 | ✅ | MQTT type=1(audio): `{arg,url,mediaId,operate:1}`；type=0(chat): `{url}` |
| 闹铃 | ⚠️ 需 patch 固件 | 需 OTA 刷入补丁（把 operate=8 实现为写 VM） |
| 对讲 | ❌ | 无 intercom/rtp/webrtc；只能半双工语音留言 |

## 四、环境与文件

| 项目 | 位置 |
|---|---|
| 固件 dump（当前） | `chat rabbit-puya.bin` |
| 固件 dump（最初 23 闹钟） | `chat rabbit.bin` |
| 解密明文 | `work\dev_app_plain.bin` |
| 反汇编 | `work\dev_wrap.elf` + `work\dev_app_disasm.txt`（41MB） |
| 完整记录 | `work\REPORT.md` |
| 官方工具链 | `C:\JL\pi32\bin` |
| Code::Blocks | `C:\Program Files\CodeBlocks` |
| SDK v1.2.0 | `work\sdkv12\fw-AC79_AIoT_SDK-release-AC79NN_SDK_V1.2.0` |
| 自建固件样例 | `work\sdkbuild\jl_isd.bin` / `.fw` |
| Mock 云源码 | `router\src\main.go` |
| 图灵 AES 密钥 | key=IV=`ix6hy5975d228rrv`（AES-128-CBC/PKCS7/base64） |
| 设备 topic | `iot/69043ca347f34348b1e043c5429fef16/AI91144031DD06A2` |

## 五、复跑命令

```powershell
curl.exe -s http://<路由IP>:8898/api/status          # mock 云状态
curl.exe -s -X POST http://<路由IP>:8898/api/raw --data-binary '{"type":1,...}'
python work\ssh_log2.py                              # 路由器日志（改 IP/密码）
python work\decrypt_device_plain.py                  # 重新解密固件
python work\wrap_elf2.py                             # 反汇编
```

## 六、下一步优先级

1. **恢复环境**（见第二节）
2. **OTA 远程刷机验证**（成功后一切通）
3. App 一期（状态/音乐/语音留言）← 不依赖固件修改
4. 若 OTA 成功 → 固件 patch → App 二期（闹钟增删）
5. 兜底：CH341A 编程器直接写 VM

## 七、注意事项

- 路由器 ctl restart 会丢自定义 NAT 规则
- 设备用硬编码 IP 绕过 DNS，需 iptables PREROUTING 重定向
- 非法 operate=8 消息 1-2 分钟后可能触发"闹钟已全部删除"语音
- 设备 BLE 只在配网模式可见（0x1800 + 0xFFFF，FF01/FF02）

---

# 交接：2026-10-26 晚（设备待 341A 恢复）

## 当前状态
- **玩具无法开机**（OT​A 写坏固件区），等 CH341A（用户明天到货）
- 硬件无损，flash 可整片重写，原厂备份完整

## 明天第一件事（10 分钟内完成）
1. 341A 夹 SOP8 flash（注意 1 脚），读整片保存 `dump_readback.bin`
2. 校验读回：与 `chat rabbit-puya.bin` 对比
3. 写回 `chat rabbit-puya.bin` → 开机验证恢复正常
4. 写 `work\dump_patched_code.bin`（补丁固件）→ 开机
5. 能开机则测试：
   ```
   POST http://192.168.6.1:8898/api/dev_alarm
   {"action":"add","loop":1,"hour":7,"minute":30,"year":2026,"month":10,"day":28,"event":"test"}
   ```
   看设备屏幕闹钟数是否 +1
6. 失败 → 写回原厂，改补丁方案

## 补丁固件构成（`dump_patched_code.bin`）
- hook: `0x200843A`（foff `0x833A`）6 字节 `10 04 51 9c 00 04` → `80 ff a8 bc 0b 00`
- stub: `0x20C40E8`（foff `0xC3FE8`，218 B）
  - 含 `"@ALARM":` 的 JSON → hex 解码 → `dispatch(0x201B9F4)(2, buf, len)`
  - 其它 → `parse_body(0x20083B6)(js)`

## 已知问题（补丁可能崩溃的原因）
1. **代码洞不干净**：`0x20C40E8..0x20C41C2` 内有 2 个绝对指针引用
   （`0x20EE3C3 → 0x20C4100`、`0x21AE6ED → 0x20C40F0`）
   → 需换真死区；候选（无 call 目标/无绝对指针，需再验局部跳转）：
   `0x20C8C6E..0x20C93EA`(1916B)、`0x20C5ADA..0x20C6100`(1574B)、`0x20C84EE..0x20C8AA2`(1460B)
2. **hook 点太"热"**：JSON 解析器启动时大量调用；更安全的做法是 hook MQTT 音频播放路径
3. 工具：`work\check_hole.py`（验洞）、`work\find_hole.py`（找洞）、`work\callmap.py`（双编码调用图）、
   `work\patch\inject_callsites.py`（改调用点）、`work\patch\stub.c`（补丁源码）

## OTA 状态
- 通道可用但**容器格式判断错误**（负载写入基址错）→ 已封禁，勿再使用
- 修复需要：反汇编 uboot（加密形态）或拿到真实厂商 OTA 文件
