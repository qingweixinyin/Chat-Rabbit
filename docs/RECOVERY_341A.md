# 341A 恢复 + 补丁刷写指南

## 现状

- 设备当前**无法开机**（OTA 写入位置错误导致固件区损坏）
- 硬件无损：flash 可整片重写
- 完整原厂备份：`E:\Claude official\chat rabbit\chat rabbit-puya.bin`（8 MiB）
- 补丁镜像：`work\dump_patched_code.bin`（8 MiB，仅 221 字节差异）

## 一、恢复到原厂（必做第一步）

### 接线

1. 断电，拆开玩具，找到 **8 脚 SPI NOR flash**（SOP8 封装）
2. CH341A 装上 SOP8 测试夹，**注意 1 脚方向**（芯片上圆点/丝印缺口对准夹具 1 脚）
3. USB 插入电脑

### 软件（任选）

- **NeoProgrammer**（推荐，中文、支持 8MB）
- **CH341A Programmer 1.4**（老工具，8MB 需选对型号）

### 步骤

1. 点"检测/读取芯片 ID" → 记录型号（应类似 `W25Q64` / `GD25Q64` / `ZB25VQ64`）
2. **读取** 整片 → 保存为 `dump_readback.bin`（8 MiB）
3. 用 PowerShell 校验读回内容：
   ```powershell
   $a=[IO.File]::ReadAllBytes("dump_readback.bin")
   $b=[IO.File]::ReadAllBytes("E:\Claude official\chat rabbit\chat rabbit-puya.bin")
   # 前 2MB 应高度相似（坏的是 app 区，前 8KB 头/uboot 应一致）
   ```
4. **擦除** → 选择 `chat rabbit-puya.bin` → **写入** → **校验**
5. 断电，装回，开机 → 应恢复正常

> 如果写回原厂后仍开不了机，说明还有别的区被写坏（例如 VM/BES 之外的保留区），
> 此时用第 2 步的 `dump_readback.bin` 再比对定位差异。

## 二、写入补丁固件（恢复成功后）

1. 擦除 → 写入 `work\dump_patched_code.bin` → 校验
2. 断电，装回，开机
3. **能开机** → 立即测试 App 控制（见下）
4. **开不了** → 写回原厂（补丁代码有问题，我们再改）

## 三、测试 App 控制闹钟

设备联网后（路由器 mock 云自动接管），在电脑上执行：

```powershell
# 添加一条 07:30 的闹钟（每天）
Invoke-RestMethod -Method Post -Uri "http://192.168.6.1:8898/api/dev_alarm" `
  -ContentType "application/json" `
  -Body '{"action":"add","loop":1,"hour":7,"minute":30,"second":0,"year":2026,"month":10,"day":28,"event":"起床"}'

# 删除全部
Invoke-RestMethod -Method Post -Uri "http://192.168.6.1:8898/api/dev_alarm" `
  -ContentType "application/json" -Body '{"action":"del","alarmId":0}'
```

**判定依据**：设备屏幕上的闹钟记录数是否变化。
- 变化 → 补丁生效，App 控制打通
- 不变 → 说明 MQTT 消息没经过我们 hook 的 JSON 解析器，需要调整 hook 点

## 四、后续（OTA 修复）

OTA 容器格式理解有误（payload 写入基址错），后续需要：
1. 反汇编 `uboot.boot`（13.7 KB）找到 OTA 解析/写入代码
2. 或拿到一个**真实厂商 OTA 文件**做对照

在此之前**不要再用 OTA 刷机**。

## 备份清单（务必保留）

| 文件 | 用途 |
|---|---|
| `chat rabbit-puya.bin` | 原厂全片备份（恢复用） |
| `chat rabbit.bin` / `chat rabbit-3.bin` | 更早的 dump（参考） |
| `dump_patched_code.bin` | 补丁镜像 |
| `dump_readback.bin` | 明天读回的现场（用于定位损坏范围） |
