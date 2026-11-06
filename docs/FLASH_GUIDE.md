# 补丁固件刷写指南（CH341A）

## 产物

| 文件 | 说明 |
|---|---|
| `work\dump_patched_code.bin` | **要刷入的 8 MiB 镜像**（在原 dump 上打补丁，仅 221 字节改动） |
| `E:\Claude official\chat rabbit\chat rabbit-puya.bin` | 原始备份（务必保留） |
| `work\dev_app_plain_patched.bin` | 解密后的补丁明文（如需重新打包用） |

## 补丁内容

1. **hook**：JSON 解析器入口 `0x200843A`（文件偏移 `0x833A`）6 字节
   `10 04 51 9c 00 04` → `80 ff a8 bc 0b 00`（跳转到 stub `0x20C40E8`）
2. **stub**：放在死代码洞 `0x20C40E8`（218 字节，文件偏移 `0xC3FE8`）
   - 若 JSON 文本含 `"@ALARM":` → 取其后的 hex 串，解码为内部命令信封 →
     调用固件命令分发器 `0x201B9F4` → 执行 `alarm_set` / `alarm_del`
   - 否则调用原解析体 `0x20083B6`，行为不变

## 刷写步骤

1. 断电，CH341A 夹住 SOP8 flash
2. **先读整片备份**（8 MiB），确认能读、保存为 `dump_readback.bin`
3. 校验备份与 `chat rabbit-puya.bin` 是否一致（可选但强烈建议）
4. 擦除 → 写入 `dump_patched_code.bin` → 校验
5. 断电，装回，开机

## 开机后验证

1. 设备能正常启动、屏幕正常、WiFi 自动连接（补丁不影响原有逻辑）
2. 网页/接口下发一条测试闹钟：
   ```
   POST http://192.168.6.1:8898/api/dev_alarm
   {"action":"add","loop":1,"hour":7,"minute":30,"second":0,"year":2026,"month":10,"day":27,"event":"test"}
   ```
3. 看设备屏幕闹钟记录是否 +1（这是补丁是否生效的判定依据）

## 失败处置

- 无法开机/异常：用备份 `dump_readback.bin` 原样写回即可恢复
- 能开机但闹钟不生效：说明 MQTT 消息没有经过 JSON 解析器（或 envelope 需要调整），
  需要下一轮调整 hook 点；设备本身可正常使用
