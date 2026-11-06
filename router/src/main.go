// chatrabbit —— 聊天兔本地云端（AC68U / koolshare armv7 静态二进制）
//
// 一个二进制提供：
//
//	:8081  假图灵 HTTP（bind / getMQTTConnectInfo / status / ota / audio / time_now / api/v2）+ /media/ 音频
//	:1883  假 MQTT broker（+ 40506）
//	:8890  控制 API + 自带控制页（GET /）
//	:8898  旧 API 端口（兼容监听；被 kongke 占用时自动跳过，不退出）
//
// 子命令：serve | ctl <play|stop|op|status|list> | version | dns-fix
package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const Version = "1.7.12"

var cfgPath = flag.String("c", "/koolshare/chatrabbit/chatrabbit.conf", "配置文件")
var debugOn = flag.Bool("d", false, "详细日志")

// ---------------- 配置 ----------------

type Config struct {
	LanIP      string `json:"lan_ip"`
	// TuringIP：给设备的"假云端地址"。dnsmasq 把 turingapi.com 解析到它，
	// iptables 再把发往它的流量重定向到本机。用独立地址（默认 198.18.0.1，
	// 保留的 benchmarking 网段）而不是 LANIP，是为了能"按目的地址"做转发，
	// 不需要再按设备 MAC 建规则 —— 任何玩具接入路由器都能直接找到本地云。
	TuringIP   string `json:"turing_ip"`
	HTTPPort   int    `json:"http_port"`
	MQTTPort   int    `json:"mqtt_port"`
	ExtraPort  int    `json:"extra_mqtt_port"`
	APIPort    int    `json:"api_port"`
	MediaDir   string `json:"media_dir"`
	LogDir     string `json:"log_dir"`
	GapSec     int    `json:"gap_seconds"`
	LogMaxMB   int    `json:"log_max_mb"` // 单个日志文件上限（MB），超过自动截断；<=0 关闭
	APIKey     string `json:"api_key"`
	DeviceID   string `json:"device_id"`
	OpenID     string `json:"openid"`
	KickOnCmd  bool   `json:"kick_on_command"`
	TimeMode   int    `json:"time_mode"`
	TimeOffset int64  `json:"time_offset_seconds"`
	TZHours    int    `json:"tz_hours"` // 设备本地时区（默认 8=东八区）；路由器系统时区可能是 UTC
	// OnceMode "仅一次"编码方式：
	//   "weekday" = sd=日期00:00 + wk=该日期星期位（默认）
	//   "epoch"   = sd=精确触发时刻 + wk=0（对照实验用）
	OnceMode   string `json:"once_mode"`
	ArgAsStr   bool   `json:"alarm_arg_as_string"`
	PublishQoS int    `json:"publish_qos"`
	WSAccept   bool   `json:"ws_accept"`   // 是否接受设备的 WebSocket 升级（默认 false：拒绝，让设备回退本地蜂鸣）
	AlarmVoice string `json:"alarm_voice"` // 闹铃时播报的音频文件（media 目录内）；空=用 wsAutoReply
	WSProxy    bool   `json:"ws_proxy"`    // 把设备的 WS 请求转发给真实图灵云并回传响应
	OTAVersion string `json:"ota_version"` // 返回给设备的"最新版本号"，空=不下发升级
	OTAURL     string `json:"ota_url"`     // 升级包下载地址
	OTAMD5     string `json:"ota_md5"`     // 升级包 MD5（设备可能校验）
	OTAForce   int    `json:"ota_force"`   // 是否强制升级
	// 设备当前版本：OTA 响应中的 payload.currentVersion 必须与它完全一致，
	// 否则设备（turing_iot.c: ota_update_version_info）不会升级。
	// GET /iot/v2/ota/version 没有 body，所以优先用设备 POST initVersion 时上报的值，其次用这个默认值。
	OTACurrentVersion string `json:"ota_current_version"`
	// /v2/iot/audio（设备向云端要"下一首"）返回的音频：media 目录内文件名
	MusicFile string `json:"music_file"`
	// 闹钟响铃时长（秒），0=不自动停止
	RingSeconds int `json:"ring_seconds"`
	// OTA 响应附加字段（用于试验不同字段名/结构，无需重新编译）
	OTAExtra map[string]any `json:"ota_extra"`
	// OTA 响应形态："map"(默认) / "string"(payload 为 JSON 字符串) / "top"(全部顶层)
	OTAFormat string `json:"ota_format"`
	// 老版 robot OTA 通道开关（flash_check.form 返回 upgrade 1/0）
	OTAEnable bool `json:"ota_enable"`
	// robot /api/ntp.form 响应模式：0=全字段(秒) 1=空body(对照组) 2=毫秒 3=字符串日期
	NTPFormMode int `json:"ntp_form_mode"`
	// 多设备（v1.5.0）：设备列表 + 默认设备。旧配置没有该字段时用顶层
	// api_key/device_id/openid 自动生成一台默认设备。
	Devices       []DeviceConfig `json:"devices,omitempty"`
	DefaultDevice string         `json:"default_device,omitempty"`
}

var cfg = Config{
	HTTPPort:  28081,
	MQTTPort:  1883,
	ExtraPort: 24056,
	APIPort:   8890,
	MediaDir:  "/koolshare/chatrabbit/media",
	LogDir:    "/koolshare/chatrabbit/logs",
	GapSec:    6,
	LogMaxMB:  1,
	APIKey:    "69043ca347f34348b1e043c5429fef16",
	DeviceID:  "AI91144031DD06A2",
	OpenID:    "o0LocalOpenid0000000000000000",
	TimeMode:  10,
	TZHours:   8,
	OnceMode:  "weekday",

	OTAVersion:        "1.0.0",
	OTACurrentVersion: "AC791N_NJ_1V2_V2.0",
	MusicFile:         "tone.mp3",
	RingSeconds:       20,
}

func (c *Config) topic() string    { return "iot/" + c.APIKey + "/" + c.DeviceID }
func (c *Config) clientID() string { return "GID_device@@@" + c.APIKey + "@" + c.DeviceID }

func loadConfig() {
	if b, err := os.ReadFile(*cfgPath); err == nil {
		_ = json.Unmarshal(b, &cfg)
	}
	if cfg.LanIP == "" {
		cfg.LanIP = detectLanIP()
	}
	if cfg.TuringIP == "" {
		cfg.TuringIP = "198.18.0.1"
	}
	_ = os.MkdirAll(cfg.MediaDir, 0755)
	_ = os.MkdirAll(cfg.LogDir, 0755)
}

// mediaHostURL 返回"设备可以去取媒体文件的地址"（不带端口 = 80）。
// 使用假云端地址 TuringIP：发往 198.18.0.1:80 的流量会被 iptables 按目的地址
// 重定向到本机 HTTP 端口，任何玩具通用，不依赖 MAC 白名单。
func mediaHostURL() string {
	ip := cfg.TuringIP
	if ip == "" {
		ip = "198.18.0.1"
	}
	return "http://" + ip
}

func saveConfig() {
	b, err := json.MarshalIndent(cfg, "", "  ")
	if err == nil {
		_ = os.WriteFile(*cfgPath, b, 0644)
	}
}

func detectLanIP() string {
	// 1) 控制脚本会用 nvram 取到真正的 LAN IP，通过环境变量传入（最可靠）
	if v := strings.TrimSpace(os.Getenv("CHATRABBIT_LANIP")); v != "" {
		return v
	}
	// 2) 按接口名优先挑 LAN 网桥，排除 WAN/隧道/容器网卡
	lanNames := map[string]bool{"br0": true, "br1": true, "br2": true, "lan": true, "lan0": true, "bridge0": true}
	badPrefixes := []string{"eth", "vlan", "ppp", "wan", "tun", "tap", "wg", "tailscale", "docker", "veth", "br-lan"}
	ifaces, err := net.Interfaces()
	if err == nil {
		pick := ""
		fallback := ""
		for _, ifc := range ifaces {
			if ifc.Flags&net.FlagLoopback != 0 || ifc.Flags&net.FlagUp == 0 {
				continue
			}
			name := strings.ToLower(ifc.Name)
			bad := false
			for _, p := range badPrefixes {
				if strings.HasPrefix(name, p) && name != "br-lan" {
					bad = true
					break
				}
			}
			addrs, aerr := ifc.Addrs()
			if aerr != nil {
				continue
			}
			for _, a := range addrs {
				ipn, ok := a.(*net.IPNet)
				if !ok || ipn.IP.To4() == nil || ipn.IP.IsLoopback() {
					continue
				}
				s := ipn.IP.String()
				if !(strings.HasPrefix(s, "192.168.") || strings.HasPrefix(s, "10.") || strings.HasPrefix(s, "172.")) {
					continue
				}
				if lanNames[name] {
					return s
				}
				if !bad && pick == "" {
					pick = s
				}
				if fallback == "" {
					fallback = s
				}
			}
		}
		if pick != "" {
			return pick
		}
		if fallback != "" {
			return fallback
		}
	}
	// 3) 最后兜底：遍历所有地址取第一个私网 IP
	if ips, err := net.InterfaceAddrs(); err == nil {
		for _, a := range ips {
			if ipn, ok := a.(*net.IPNet); ok && ipn.IP.To4() != nil && !ipn.IP.IsLoopback() {
				s := ipn.IP.String()
				if strings.HasPrefix(s, "192.168.") || strings.HasPrefix(s, "10.") {
					return s
				}
			}
		}
	}
	return "192.168.6.1"
}

// ---------------- 日志 ----------------

var (
	logMu     sync.Mutex
	logFile   *os.File
	logWrites int64
)

func logf(f string, a ...any) {
	line := time.Now().Format("01-02 15:04:05.000") + " " + fmt.Sprintf(f, a...)
	logMu.Lock()
	fmt.Println(line)
	if logFile != nil {
		logFile.WriteString(line + "\n")
	}
	logWrites++
	needTrim := logWrites%500 == 0
	logMu.Unlock()
	if needTrim {
		go autoTrimLogs() // 兜底：把日志"总容量"压回上限
	}
}

// ---------------- 日志管理（清空 / 自动限容） ----------------

func logDirFiles() []string {
	var out []string
	ents, err := os.ReadDir(cfg.LogDir)
	if err != nil {
		return out
	}
	for _, e := range ents {
		if e.IsDir() {
			continue
		}
		if strings.HasSuffix(e.Name(), ".log") {
			out = append(out, filepath.Join(cfg.LogDir, e.Name()))
		}
	}
	return out
}

func logStats() map[string]any {
	files := logDirFiles()
	var total int64
	detail := make([]map[string]any, 0, len(files))
	for _, p := range files {
		if st, err := os.Stat(p); err == nil {
			total += st.Size()
			detail = append(detail, map[string]any{"name": filepath.Base(p), "size": st.Size()})
		}
	}
	maxMB := cfg.LogMaxMB
	if maxMB <= 0 {
		maxMB = 4
	}
	return map[string]any{
		"ok": true, "total": total, "count": len(files),
		"max_mb": maxMB, "files": detail,
	}
}

// clearLogs 清空 serve.log（shell 重定向目标）、当前进程日志，并删除历史日志文件。
func clearLogs() int64 {
	var freed int64
	serve := filepath.Join(cfg.LogDir, "serve.log")
	if st, err := os.Stat(serve); err == nil {
		freed += st.Size()
		_ = os.Truncate(serve, 0)
	}
	cur := ""
	if logFile != nil {
		cur = logFile.Name()
		if st, err := os.Stat(cur); err == nil {
			freed += st.Size()
			_ = logFile.Truncate(0)
			_, _ = logFile.Seek(0, io.SeekStart)
		}
	}
	for _, p := range logDirFiles() {
		if p == serve || p == cur {
			continue
		}
		if st, err := os.Stat(p); err == nil {
			freed += st.Size()
			_ = os.Remove(p)
		}
	}
	return freed
}

func autoTrimLogs() {
	maxMB := cfg.LogMaxMB
	if maxMB <= 0 {
		return
	}
	// 需求：所有日志的"总容量"不超过 N MB（默认 1MB），不是每个文件各自 N MB
	limit := int64(maxMB) << 20
	type finfo struct {
		path string
		size int64
		mod  time.Time
	}
	var list []finfo
	var total int64
	for _, p := range logDirFiles() {
		if st, err := os.Stat(p); err == nil {
			total += st.Size()
			list = append(list, finfo{p, st.Size(), st.ModTime()})
		}
	}
	if total <= limit {
		return
	}
	// 先删最旧的历史日志，直到降到上限的 80%
	sort.Slice(list, func(i, j int) bool { return list[i].mod.Before(list[j].mod) })
	target := limit * 8 / 10
	for _, f := range list {
		if total <= target {
			break
		}
		if logFile != nil && f.path == logFile.Name() {
			continue // 当前写入中的日志最后处理
		}
		if err := os.Remove(f.path); err == nil {
			total -= f.size
			logf("[log] 删除旧日志 %s（%d 字节），保持总容量 <= %dMB", filepath.Base(f.path), f.size, maxMB)
		}
	}
	if total > limit && logFile != nil {
		if st, err := os.Stat(logFile.Name()); err == nil && st.Size() > 0 {
			_ = logFile.Truncate(0)
			_, _ = logFile.Seek(0, io.SeekStart)
			logf("[log] 日志总容量超过 %dMB，已截断当前日志", maxMB)
		}
	}
}

func startLogAutoTrim() {
	for {
		time.Sleep(20 * time.Second)
		autoTrimLogs()
	}
}

// deleteMediaFile 删除 media 目录下的单个文件（拒绝越权路径）。
// 清理所有设备上引用该文件的 alarm_voice / music_file。
func deleteMediaFile(r *http.Request) map[string]any {
	var req struct {
		Name string `json:"name"`
	}
	body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	_ = json.Unmarshal(body, &req)
	name := strings.TrimSpace(req.Name)
	if name == "" || name == "." || name == ".." || strings.ContainsAny(name, "/\\") {
		return map[string]any{"ok": false, "error": "invalid name"}
	}
	base := filepath.Base(name)
	full := filepath.Join(cfg.MediaDir, base)
	st, err := os.Stat(full)
	if err != nil || st.IsDir() {
		return map[string]any{"ok": false, "error": "not found", "name": base}
	}
	if err := os.Remove(full); err != nil {
		return map[string]any{"ok": false, "error": err.Error(), "name": base}
	}
	changed := false
	for _, d := range allDevices() {
		if d.AlarmVoice == base {
			d.AlarmVoice = ""
			changed = true
		}
		if d.MusicFile == base {
			d.MusicFile = ""
			changed = true
		}
	}
	if changed {
		persistDevices()
	}
	logf("[media] 删除文件: %s (%d 字节)", base, st.Size())
	return map[string]any{"ok": true, "deleted": base, "freed": st.Size()}
}

// diagInfo 返回运行态诊断信息，用于确认实际跑的二进制是否最新。
func diagInfo() map[string]any {
	def := defaultDevice()
	ac, mc, la, lm, ls := 0, int64(0), "", "", ""
	defID := ""
	if def != nil {
		ac, mc, la, lm, ls = def.gateStat()
		defID = def.ID
	}
	base := filepath.Dir(cfg.MediaDir)
	gz := filepath.Join(base, "bin", "chatrabbit.gz")
	run := "/tmp/chatrabbit.bin"
	info := func(p string) map[string]any {
		st, err := os.Stat(p)
		if err != nil {
			return map[string]any{"path": p, "exists": false}
		}
		return map[string]any{"path": p, "exists": true, "size": st.Size(), "mtime": st.ModTime().Format("2006-01-02 15:04:05")}
	}
	return map[string]any{
		"ok": true, "version": Version, "pid": os.Getpid(),
		"lan_ip": cfg.LanIP, "http_port": cfg.HTTPPort, "api_port": cfg.APIPort,
		"api_ports":     apiPortsSnapshot(),
		"kongke":        kongkeInstalled(), // 装了 kongke 时我们会让出 8898 兼容端口
		"compat_port":   CompatAPIPort,
		"sntp_ok":   sntpOK, "time_mode": cfg.TimeMode, "time_offset": cfg.TimeOffset, "tz_hours": cfg.TZHours, "once_mode": cfg.OnceMode,
		"audio_cnt": ac, "media_cnt": mc, "last_audio": la, "last_media": lm, "last_stop": ls,
		"default_device": defID, "device_count": len(allDevices()), "devices": deviceSummary(),
		"gz": info(gz), "run": info(run),
	}
}

// storageInfo 返回媒体目录所在文件系统的容量与媒体占用。
// 文件系统统计走平台实现（fsStat）：目标平台 linux，Windows 仅用于本机冒烟测试。
func storageInfo() map[string]any {
	total, free := fsStat(cfg.MediaDir)
	var mediaSize int64
	var count int
	if ents, err := os.ReadDir(cfg.MediaDir); err == nil {
		for _, e := range ents {
			if e.IsDir() {
				continue
			}
			if st, err := e.Info(); err == nil {
				mediaSize += st.Size()
				count++
			}
		}
	}
	return map[string]any{
		"ok":          true,
		"media_dir":   cfg.MediaDir,
		"media_size":  mediaSize,
		"media_count": count,
		"fs_total":    total,
		"fs_free":     free,
		"fs_used":     total - free,
	}
}

// ---------------- 状态 ----------------

type Status struct {
	Vol     int    `json:"vol"`
	Battery int    `json:"battery"`
	At      string `json:"at"`
}

// 全局共享：日志环、SNTP、NTP 轮换计数（per-device 状态见 device.go）
var (
	reqMu     sync.Mutex
	reqLog    = make([]string, 0, 64) // 最近的设备 HTTP 请求（排查点歌/拉流用）
	mediaMu   sync.Mutex
	mediaLog  = make([]string, 0, 32) // 真实媒体下载请求（避免被 /v2/iot/audio 刷屏淹没）
	mqttMu    sync.Mutex
	mqttLog   = make([]string, 0, 128) // 最近的 MQTT 事件（排查下发用）
	sntpOK    bool                     // SNTP(:123) 是否成功监听（设备校时依赖它）
	ntpRotMu  sync.Mutex
	ntpRotIdx int
)

// pushMediaLog 记录真实的媒体下载请求（用于确认设备是否来拉流）。
func pushMediaLog(line string) {
	mediaMu.Lock()
	defer mediaMu.Unlock()
	mediaLog = append(mediaLog, line)
	if len(mediaLog) > 32 {
		mediaLog = mediaLog[len(mediaLog)-32:]
	}
}

func snapshotMediaLog() []string {
	mediaMu.Lock()
	defer mediaMu.Unlock()
	out := make([]string, len(mediaLog))
	copy(out, mediaLog)
	return out
}

// pushMqttLog 记录最近的 MQTT 事件（最多 128 条）。
func pushMqttLog(line string) {
	mqttMu.Lock()
	defer mqttMu.Unlock()
	mqttLog = append(mqttLog, line)
	if len(mqttLog) > 128 {
		mqttLog = mqttLog[len(mqttLog)-128:]
	}
}

func snapshotMqttLog() []string {
	mqttMu.Lock()
	defer mqttMu.Unlock()
	out := make([]string, len(mqttLog))
	copy(out, mqttLog)
	return out
}

// pushReqLog 记录最近的 HTTP 请求（最多 64 条）。
func pushReqLog(line string) {
	reqMu.Lock()
	defer reqMu.Unlock()
	reqLog = append(reqLog, line)
	if len(reqLog) > 64 {
		reqLog = reqLog[len(reqLog)-64:]
	}
}

func snapshotReqLog() []string {
	reqMu.Lock()
	defer reqMu.Unlock()
	out := make([]string, len(reqLog))
	copy(out, reqLog)
	return out
}

// sanitizeAudioName 清洗上传文件名：去路径、去非法字符、补扩展名。
func sanitizeAudioName(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return "custom_ring.mp3"
	}
	s = filepath.Base(s)
	s = strings.Map(func(r rune) rune {
		if r == '/' || r == '\\' || r < 32 || r == 127 {
			return '_'
		}
		return r
	}, s)
	if s == "" || s == "." || s == ".." {
		return "custom_ring.mp3"
	}
	if filepath.Ext(s) == "" {
		s += ".mp3"
	}
	if len(s) > 120 {
		s = s[:120]
	}
	return s
}

// uniqueMediaPath 在 media 目录里生成不冲突的目标路径（同名自动追加 _1、_2 …）。
func uniqueMediaPath(name string) string {
	ext := filepath.Ext(name)
	base := strings.TrimSuffix(name, ext)
	p := filepath.Join(cfg.MediaDir, name)
	for i := 0; i < 100; i++ {
		if i > 0 {
			p = filepath.Join(cfg.MediaDir, fmt.Sprintf("%s_%d%s", base, i, ext))
		}
		if _, err := os.Stat(p); os.IsNotExist(err) {
			return p
		}
	}
	return filepath.Join(cfg.MediaDir, fmt.Sprintf("%s_%d%s", base, time.Now().Unix(), ext))
}

// ---------------- 播放闸门 ----------------
// 每台设备的闸门状态与判定见 device.go（Device.gateAllowAudio / gateMarkStop / gateStat）。

func setStatusFor(d *Device, vol, bat int) {
	if d == nil {
		d = defaultDevice()
	}
	if d == nil {
		return
	}
	d.setStatus(vol, bat)
}

func getStatusFor(d *Device) Status {
	if d == nil {
		d = defaultDevice()
	}
	if d == nil {
		return Status{Vol: -1, Battery: -1}
	}
	return d.getStatus()
}

// ---------------- 指令队列 ----------------

type Command struct {
	Payload  []byte
	Topic    string
	DevID    string
	Enqueued time.Time
}

var (
	cmdQueue   = make(chan Command, 128)
	pidCounter = 0
	httpCmd    []byte
	httpRaw    []byte // /iot/status/notify 的完全自定义响应体（原样返回）
	v2Resp     []byte

	// OTA: 设备最近一次上报的 currentVersion（initVersion 请求体）
	otaCurrentVersion string
	otaLastID         = 1001
)

// 下发相关：enqueue / enqueueRaw / publishNow / playCmd / stopCmd / opCmd
// 均为每设备方法，见 device.go。

// playAlias 为播放生成唯一 ASCII 文件名别名。
// 注意：不能用固定别名（设备会按 URL 缓存，导致再点别的歌仍播旧内容），
// 因此每次播放都生成新名字，并清理旧别名。
func playAlias(file string) string {
	base := filepath.Base(file)
	src := filepath.Join(cfg.MediaDir, base)
	if _, err := os.Stat(src); err != nil {
		return base
	}
	ext := filepath.Ext(base)
	if ext == "" {
		ext = ".mp3"
	}
	alias := "__play_" + strconv.FormatInt(time.Now().UnixNano(), 36) + ext
	dst := filepath.Join(cfg.MediaDir, alias)
	if err := os.Symlink(src, dst); err != nil {
		if b, e2 := os.ReadFile(src); e2 == nil {
			if e3 := os.WriteFile(dst, b, 0644); e3 != nil {
				return base
			}
		} else {
			return base
		}
	}
	// 清理过期别名（保留最近 5 个）
	if ents, err := os.ReadDir(cfg.MediaDir); err == nil {
		var names []string
		for _, e := range ents {
			n := e.Name()
			if strings.HasPrefix(n, "__play_") {
				names = append(names, n)
			}
		}
		if len(names) > 5 {
			sort.Strings(names)
			for _, n := range names[:len(names)-5] {
				_ = os.Remove(filepath.Join(cfg.MediaDir, n))
			}
		}
	}
	return alias
}

// ---------------- 闹钟 ----------------

type Alarm struct {
	ID      int    `json:"id"`
	Time    string `json:"time"`
	Repeat  []int  `json:"repeat"`
	Enabled bool   `json:"enabled"`
	// At > 0 时表示一次性闹钟（Unix 秒），到点触发后自动关闭
	At      int64 `json:"at,omitempty"`
	FiredAt int64 `json:"fired_at,omitempty"`
}

var (
	alarmMu sync.Mutex
	alarms  []Alarm
)

func alarmsPath() string { return filepath.Join(filepath.Dir(*cfgPath), "alarms.json") }

func loadAlarms() {
	if b, err := os.ReadFile(alarmsPath()); err == nil {
		_ = json.Unmarshal(b, &alarms)
	}
	if alarms == nil {
		alarms = []Alarm{}
	}
}

func saveAlarms() {
	b, _ := json.MarshalIndent(alarms, "", "  ")
	_ = os.WriteFile(alarmsPath(), b, 0644)
}

// ---------------- 设备本机闹钟（真实协议） ----------------
// 固件协议： MQTT -> {"fromUser":..,"optCode":1003,"optValue":{"hb":[{na,th,tm,sd,wk,wt,s}..]}}
// optCode=1003 为"整表替换"；1001/1002/1004/1005 是其它功能。

type DevAlarm struct {
	Na string `json:"na"`
	TH int    `json:"th"`
	TM int    `json:"tm"`
	SD int64  `json:"sd"`
	WK int    `json:"wk"`
	WT int    `json:"wt"`
	S  int    `json:"s"`
	// RV 每条闹钟独立铃声（media 目录文件名）。仅存路由器侧，不下发给设备。
	RV string `json:"rv,omitempty"`
	// ONCE 标记"仅一次"：设备对 wk=0 不触发，所以用"目标日期所在星期的单一位"编码，
	// 响过后由服务端自动删除，保证只响一次。仅存路由器侧。
	ONCE bool `json:"once,omitempty"`
	// Until "仅一次"的自动清理时限（unix 秒）= 目标时刻 + 30 分钟
	Until int64 `json:"until,omitempty"`
}

// onceWeekMask 把"某一天"编码成设备能识别的星期掩码。
// 设备语义：wk=0 表示"没有任何星期被选中"→ 永不触发；所以仅一次改用目标日期的星期位。
func onceWeekMask(d time.Time) int {
	w := int(d.Weekday()) // Go: 0=周日
	bit := (w + 6) % 7    // 设备: bit0=周一 … bit6=周日
	if bit < 0 || bit > 6 {
		return 63
	}
	return 1 << bit
}

// 每设备闹钟表 / 一次性清理 / 专属铃声选择 / 播放闸门 见 device.go。

// devNow 返回"设备本地时间"：路由器系统时区可能是 UTC，而闹钟 th/tm 是设备本地时间。
func devNow() time.Time {
	tz := cfg.TZHours
	if tz == 0 {
		tz = 8
	}
	return time.Now().UTC().Add(time.Duration(tz) * time.Hour)
}

func nextAlarmTS(hhmm string, repeat []int) int64 {
	now := time.Now()
	h, m := 7, 30
	if ps := strings.Split(hhmm, ":"); len(ps) >= 2 {
		h, _ = strconv.Atoi(ps[0])
		m, _ = strconv.Atoi(ps[1])
	}
	base := time.Date(now.Year(), now.Month(), now.Day(), h, m, 0, 0, now.Location())
	if len(repeat) == 0 {
		if !base.After(now) {
			base = base.Add(24 * time.Hour)
		}
		return base.UnixMilli()
	}
	for i := 0; i < 8; i++ {
		cand := base.AddDate(0, 0, i)
		if !cand.After(now) {
			continue
		}
		wd := int(cand.Weekday())
		tum := wd
		if wd == 0 {
			tum = 7
		}
		for _, r := range repeat {
			if r == tum {
				return cand.UnixMilli()
			}
		}
	}
	return base.UnixMilli()
}

func alarmArg(a Alarm) map[string]any {
	status := 0
	if a.Enabled {
		status = 1
	}
	parts := []string{}
	for _, r := range a.Repeat {
		parts = append(parts, strconv.Itoa(r))
	}
	devID := cfg.DeviceID
	if d := defaultDevice(); d != nil {
		devID = d.ID
	}
	return map[string]any{
		"id": a.ID, "deviceId": devID, "title": "闹钟",
		"time": nextAlarmTS(a.Time, a.Repeat), "repeat": strings.Join(parts, ","), "status": status,
	}
}

func pushAlarm(a Alarm) {
	d := defaultDevice()
	if d == nil {
		return
	}
	arg := alarmArg(a)
	var payloadArg any = arg
	if cfg.ArgAsStr {
		b, _ := json.Marshal(arg)
		payloadArg = string(b)
	}
	d.enqueue(map[string]any{"type": 2, "fromUser": d.OpenID,
		"message": map[string]any{"operate": 8, "arg": payloadArg}})
}

// startAlarmScheduler 云端闹钟调度器：到点通过 MQTT 推音频给设备播放。
// 注：这是旧版"云端闹钟"（/api/alarms*），多设备下统一作用于默认设备；
// 真正的设备本机闹钟走 /api/dev/* + Device.pushAlarms。
func startAlarmScheduler() {
	logf("[alarm] 调度器启动（每 5 秒检查一次）")
	ticker := time.NewTicker(5 * time.Second)
	for range ticker.C {
		now := time.Now()
		var fired []Alarm
		alarmMu.Lock()
		for i := range alarms {
			a := &alarms[i]
			if !a.Enabled {
				continue
			}
			if a.At > 0 {
				if now.Unix() >= a.At {
					a.Enabled = false
					a.FiredAt = now.Unix()
					fired = append(fired, *a)
				}
				continue
			}
			// 每日 HH:MM 闹钟
			if a.Time == "" {
				continue
			}
			if now.Format("15:04") != a.Time {
				continue
			}
			// 当天同一分钟只响一次
			key := now.Format("200601021504")
			if a.FiredAt > 0 && time.Unix(a.FiredAt, 0).Format("200601021504") == key {
				continue
			}
			wd := int(now.Weekday())
			if wd == 0 {
				wd = 7
			}
			ok := len(a.Repeat) == 0
			for _, r := range a.Repeat {
				if r == wd {
					ok = true
				}
			}
			if !ok {
				continue
			}
			a.FiredAt = now.Unix()
			fired = append(fired, *a)
		}
		alarmMu.Unlock()
		if len(fired) == 0 {
			continue
		}
		saveAlarms()
		for _, a := range fired {
			logf("[alarm] 触发闹钟 id=%d time=%q at=%d", a.ID, a.Time, a.At)
			d := defaultDevice()
			if d == nil {
				continue
			}
			d.enqueueRingAudio(d.ringtoneForHHMM(a.Time))
			if cfg.RingSeconds > 0 {
				sec := cfg.RingSeconds
				dd := d
				go func() {
					time.Sleep(time.Duration(sec) * time.Second)
					dd.stopCmd()
					logf("[alarm] 响铃 %d 秒结束，已发送停止", sec)
				}()
			}
		}
	}
}

// ---------------- MQTT broker ----------------

type mqttConn struct {
	c    net.Conn
	wmu  sync.Mutex
	cid  string
	subs []string
	dev  *Device // 该连接绑定的设备（CONNECT 时按 clientId 解析）
}

func (m *mqttConn) write(b []byte) error {
	m.wmu.Lock()
	defer m.wmu.Unlock()
	_, err := m.c.Write(b)
	return err
}

func readPacket(r *bufio.Reader) (byte, []byte, error) {
	h, err := r.ReadByte()
	if err != nil {
		return 0, nil, err
	}
	mult, val := 1, 0
	for i := 0; i < 4; i++ {
		b, err := r.ReadByte()
		if err != nil {
			return 0, nil, err
		}
		val += int(b&0x7f) * mult
		if b&0x80 == 0 {
			break
		}
		mult *= 128
	}
	body := make([]byte, val)
	if val > 0 {
		if _, err := io.ReadFull(r, body); err != nil {
			return 0, nil, err
		}
	}
	return h, body, nil
}

func encLen(n int) []byte {
	var out []byte
	for {
		b := byte(n % 128)
		n /= 128
		if n > 0 {
			b |= 0x80
		}
		out = append(out, b)
		if n == 0 {
			return out
		}
	}
}

func encStr(s string) []byte {
	b := []byte(s)
	out := make([]byte, 2+len(b))
	binary.BigEndian.PutUint16(out, uint16(len(b)))
	copy(out[2:], b)
	return out
}

func mkPacket(t byte, body []byte) []byte {
	out := []byte{t}
	out = append(out, encLen(len(body))...)
	return append(out, body...)
}

func readU16(b []byte, p int) (int, int) {
	if p+2 > len(b) {
		return 0, len(b)
	}
	return int(binary.BigEndian.Uint16(b[p:])), p + 2
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func handleMQTT(c net.Conn) {
	defer c.Close()
	m := &mqttConn{c: c}
	r := bufio.NewReader(c)
	for {
		h, body, err := readPacket(r)
		if err != nil {
			break
		}
		t, flags := h>>4, h&0x0f
		if *debugOn {
			logf("[MQTT] type=%d flags=%d len=%d", t, flags, len(body))
		}
		switch t {
		case 1: // CONNECT
			p := 0
			nl, p := readU16(body, p)
			proto := string(body[p : p+minInt(nl, len(body)-p)])
			p += nl
			level := byte(0)
			if p < len(body) {
				level = body[p]
			}
			p++
			cflags := byte(0)
			if p < len(body) {
				cflags = body[p]
			}
			p += 3
			cl, p := readU16(body, p)
			if p+cl <= len(body) {
				m.cid = string(body[p : p+cl])
			}
			p += cl
			var user, pass string
			if cflags&0x80 != 0 {
				ul, pp := readU16(body, p)
				if pp+ul <= len(body) {
					user = string(body[pp : pp+ul])
				}
				p = pp + ul
			}
			if cflags&0x40 != 0 {
				pl, pp := readU16(body, p)
				if pp+pl <= len(body) {
					pass = string(body[pp : pp+pl])
				}
				p = pp + pl
			}
			logf("[MQTT] CONNECT from %s proto=%s level=%d clientId=%q user=%q pass=%q",
				c.RemoteAddr(), proto, level, m.cid, user, pass)
			// 按 clientId 解析出设备并绑定会话：
			// clientId 形如 GID_device@@@<apiKey>@<deviceId>
			apiKey, deviceID := parseMQTTClientID(m.cid)
			d := upsertDevice(deviceID, apiKey, "", "")
			if d == nil {
				d = defaultDevice() // clientId 不合规时归到默认设备
			}
			m.dev = d
			if d != nil {
				host := ipOf(c.RemoteAddr().String())
				d.bindConn(m, host)
				healthNoteConnect(d.ID)
				pushMqttLog("[" + d.ID + "] CONNECT " + c.RemoteAddr().String() + " clientId=" + m.cid)
				logf("[MQTT] 绑定设备 %s (apiKey=%s ip=%s)", d.ID, apiKey, host)
			}
			_ = m.write(mkPacket(0x20, []byte{0, 0}))
		case 8: // SUBSCRIBE
			var granted []byte
			p := 2
			for p < len(body) {
				l, pp := readU16(body, p)
				if pp+l > len(body) {
					break
				}
				topic := string(body[pp : pp+l])
				p = pp + l
				q := byte(0)
				if p < len(body) {
					q = body[p]
				}
				p++
				m.subs = append(m.subs, topic)
				granted = append(granted, q&1)
				logf("[MQTT] SUBSCRIBE %q qos=%d", topic, q)
				pushMqttLog("SUBSCRIBE " + topic + " qos=" + strconv.Itoa(int(q)))
			}
			ack := append([]byte{}, body[:2]...)
			_ = m.write(mkPacket(0x90, append(ack, granted...)))
		case 3: // PUBLISH（设备上行）
			qos := (flags >> 1) & 3
			p := 0
			l, pp := readU16(body, p)
			topic := ""
			if pp+l <= len(body) {
				topic = string(body[pp : pp+l])
			}
			p = pp + l
			var pid []byte
			if qos > 0 && p+2 <= len(body) {
				pid = body[p : p+2]
				p += 2
			}
			payload := ""
			if p < len(body) {
				payload = string(body[p:])
			}
			logf("[MQTT] PUBLISH %q qos=%d %s", topic, qos, payload)
			pushMqttLog("UP " + truncate(payload, 200))
			// 设备上行里常带 play 状态：play=0 表示按键终止了闹铃
			if strings.Contains(payload, "\"play\":0") || strings.Contains(payload, "\"play\": 0") {
				logf("[stop] 设备 MQTT 上报 play=0（按键终止）→ 开闸门并补发停止指令")
				pushMqttLog("DEV STOP(play=0 via MQTT)")
				if m.dev != nil {
					m.dev.stopCmd()
				}
			} else if strings.Contains(payload, "\"play\":1") || strings.Contains(payload, "\"play\": 1") {
				if m.dev != nil {
					m.dev.gateResetAudio()
				}
			}
			if qos == 1 {
				_ = m.write(mkPacket(0x40, pid))
			}
		case 12: // PINGREQ
			_ = m.write(mkPacket(0xd0, nil))
		case 14: // DISCONNECT
			logf("[MQTT] DISCONNECT %q", m.cid)
			goto done
		case 4: // PUBACK
			if len(body) >= 2 {
				logf("[MQTT] PUBACK pid=%d", binary.BigEndian.Uint16(body))
			}
		}
	}
done:
	n := 0
	if m.dev != nil {
		n = m.dev.unbindConn(m)
	}
	logf("[MQTT] closed %q (remaining %d)", m.cid, n)
}

// sender 按命令里的设备 ID 定向投递（每台设备独立队列消费 + gap 节流）。
func sender() {
	for cmd := range cmdQueue {
		d := devByKey(cmd.DevID)
		if d == nil {
			d = defaultDevice()
		}
		if d == nil {
			continue
		}
		deadline := time.Now().Add(5 * time.Minute)
		for time.Now().Before(deadline) {
			if d.clientCount() > 0 {
				break
			}
			time.Sleep(2 * time.Second)
		}
		targets := d.connList()
		if len(targets) == 0 {
			logf("[send] [%s] 无连接，丢弃: %s", d.ID, string(cmd.Payload))
			continue
		}
		// 过期命令不再补发：设备掉线期间排队的播放/停止指令，重连后补发会表现为"自己开始放音乐"
		if age := time.Since(cmd.Enqueued); age > 120*time.Second {
			logf("[send] [%s] 命令已过期 %.0fs，丢弃: %s", d.ID, age.Seconds(), truncate(string(cmd.Payload), 120))
			pushMqttLog("[" + d.ID + "] DROP(expired) " + truncate(string(cmd.Payload), 100))
			continue
		}
		if cfg.KickOnCmd {
			for _, m := range targets {
				_ = m.write(mkPacket(0xe0, nil))
				_ = m.c.Close()
			}
			time.Sleep(3 * time.Second)
			targets = d.connList()
		}
		topic := cmd.Topic
		if topic == "" {
			topic = d.topicName()
		}
		body := encStr(topic)
		qos := byte(0)
		if cfg.PublishQoS == 1 {
			qos = 1
			pidCounter = pidCounter%65534 + 1
			body = append(body, byte(pidCounter>>8), byte(pidCounter))
		}
		body = append(body, cmd.Payload...)
		pkt := mkPacket(0x30|(qos<<1), body)
		for _, m := range targets {
			if err := m.write(pkt); err != nil {
				logf("[send] 写失败: %v", err)
			}
		}
		logf("[send] [%s] -> %d client(s): %s", d.ID, len(targets), string(cmd.Payload))
		time.Sleep(time.Duration(cfg.GapSec) * time.Second)
	}
}

func startMQTT() {
	start := func(port int) {
		ln, err := net.Listen("tcp", ":"+strconv.Itoa(port))
		if err != nil {
			logf("[MQTT] listen :%d 失败: %v", port, err)
			return
		}
		logf("[MQTT] listening :%d", port)
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go handleMQTT(c)
		}
	}
	go start(cfg.MQTTPort)
	if cfg.ExtraPort > 0 && cfg.ExtraPort != cfg.MQTTPort {
		go start(cfg.ExtraPort)
	}
	go sender()
}

// ---------------- WebSocket（ws-api.turingapi.com 的猜想通道） ----------------

type wsConn struct {
	c   net.Conn
	wmu sync.Mutex
}

var (
	wsMu        sync.Mutex
	wsAutoReply []byte // 收到设备 WS 帧后自动回复的明文 JSON
	wsAutoBin   string // 收到设备 WS 帧后自动推送的音频文件（二进制帧）
)

// 添加 Device.connList（sender 用到；WS 相关列表在各设备的 WSConns 里）。
func (d *Device) connList() []*mqttConn {
	d.ConnMu.Lock()
	defer d.ConnMu.Unlock()
	out := make([]*mqttConn, 0, len(d.Conns))
	for m := range d.Conns {
		out = append(out, m)
	}
	return out
}

// aesEncB64 按图灵 getMQTTConnectInfo 的方式加密：AES-CBC，key=iv=secretKey，PKCS5，base64
func aesEncB64(plain string) string {
	key := []byte("ix6hy5975d228rrv")
	block, err := aes.NewCipher(key)
	if err != nil {
		return ""
	}
	data := []byte(plain)
	pad := 16 - len(data)%16
	data = append(data, bytes.Repeat([]byte{byte(pad)}, pad)...)
	out := make([]byte, len(data))
	cipher.NewCBCEncrypter(block, key).CryptBlocks(out, data)
	return base64.StdEncoding.EncodeToString(out)
}

func wsAcceptKey(key string) string {
	h := sha1.New()
	h.Write([]byte(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
	return base64.StdEncoding.EncodeToString(h.Sum(nil))
}

func (w *wsConn) writeFrame(opcode byte, payload []byte) error {
	w.wmu.Lock()
	defer w.wmu.Unlock()
	out := []byte{0x80 | opcode}
	n := len(payload)
	switch {
	case n < 126:
		out = append(out, byte(n))
	case n < 65536:
		out = append(out, 126, byte(n>>8), byte(n))
	default:
		out = append(out, 127, 0, 0, 0, 0, byte(n>>24), byte(n>>16), byte(n>>8), byte(n))
	}
	out = append(out, payload...)
	_, err := w.c.Write(out)
	return err
}

func readWSFrame(r *bufio.Reader) (byte, []byte, error) {
	h0, err := r.ReadByte()
	if err != nil {
		return 0, nil, err
	}
	opcode := h0 & 0x0f
	h1, err := r.ReadByte()
	if err != nil {
		return 0, nil, err
	}
	masked := h1&0x80 != 0
	n := int(h1 & 0x7f)
	if n == 126 {
		b := make([]byte, 2)
		if _, err := io.ReadFull(r, b); err != nil {
			return 0, nil, err
		}
		n = int(binary.BigEndian.Uint16(b))
	} else if n == 127 {
		b := make([]byte, 8)
		if _, err := io.ReadFull(r, b); err != nil {
			return 0, nil, err
		}
		n = int(binary.BigEndian.Uint32(b[4:]))
	}
	var mask [4]byte
	if masked {
		if _, err := io.ReadFull(r, mask[:]); err != nil {
			return 0, nil, err
		}
	}
	payload := make([]byte, n)
	if n > 0 {
		if _, err := io.ReadFull(r, payload); err != nil {
			return 0, nil, err
		}
	}
	if masked {
		for i := range payload {
			payload[i] ^= mask[i%4]
		}
	}
	return opcode, payload, nil
}

func handleWS(w http.ResponseWriter, r *http.Request) {
	hj, ok := w.(http.Hijacker)
	if !ok {
		return
	}
	conn, bufrw, err := hj.Hijack()
	if err != nil {
		return
	}
	key := r.Header.Get("Sec-WebSocket-Key")
	resp := "HTTP/1.1 101 Switching Protocols\r\n" +
		"Upgrade: websocket\r\n" +
		"Connection: Upgrade\r\n" +
		"Sec-WebSocket-Accept: " + wsAcceptKey(key) + "\r\n\r\n"
	if _, err := conn.Write([]byte(resp)); err != nil {
		conn.Close()
		return
	}
	wc := &wsConn{c: conn}
	dev := deviceFromDeviceRequest(r)
	if dev == nil {
		dev = defaultDevice()
	}
	if dev != nil {
		dev.WSMu.Lock()
		if dev.WSConns == nil {
			dev.WSConns = map[*wsConn]bool{}
		}
		dev.WSConns[wc] = true
		dev.WSMu.Unlock()
	}
	logf("[WS] 握手完成 host=%q path=%q from %s dev=%v", r.Host, r.URL.RequestURI(), r.RemoteAddr, dev != nil)
	defer func() {
		if dev != nil {
			dev.WSMu.Lock()
			delete(dev.WSConns, wc)
			dev.WSMu.Unlock()
		}
		conn.Close()
		logf("[WS] 断开 %s", r.RemoteAddr)
	}()
	for {
		opcode, payload, err := readWSFrame(bufrw.Reader)
		if err != nil {
			return
		}
		switch opcode {
		case 8:
			return
		case 9:
			_ = wc.writeFrame(0xA, payload)
		default:
			logf("[WS] frame op=%d len=%d payload=%s", opcode, len(payload), truncate(string(payload), 1200))
			// 闹铃触发时按"当前时间命中的那台闹钟"选铃声；无命中则回落该设备全局铃声
			ringFile := ""
			if dev != nil {
				ringFile = dev.alarmRingIfDue()
				if ringFile == "" {
					ringFile = dev.AlarmVoice
				}
				if ringFile != "" && !dev.gateAllowAudio() {
					logf("[WS] [%s] 闸门拦截（停止后或超 3 次/60s），不回播放指令", dev.ID)
					ringFile = ""
				}
			}
			if ringFile != "" {
				// 设备侧音乐/语音统一走"假云端地址"（TuringIP，80 端口）：
				// v1.7.0 之后 iptables 只按目的地址 198.18.0.1 重定向到本机 :28081，
				// 不再为每台玩具写 MAC 规则。用 LANIP 的话新玩具（新 MAC）没规则，
				// 会打到路由器 80 端口的 ASUS 界面 → 音乐/铃声一直"没反应"。
				u := mediaHostURL() + "/media/" + url.PathEscape(ringFile)
				reply := map[string]any{"code": 200, "done": true, "message": "success",
					"nlpResponse": map[string]any{
						"intent": map[string]any{"code": 50101},
						"results": []any{map[string]any{
							"groupType": 0, "resultType": "text",
							"values": map[string]any{"ttsUrl": []string{u}, "text": "闹钟时间到了"},
						}},
					}}
				b, _ := json.Marshal(reply)
				_ = wc.writeFrame(0x1, b)
				logf("[WS] 闹铃语音回复 ttsUrl=%s", u)
			} else if len(wsAutoReply) > 0 {
				if bytes.HasPrefix(wsAutoReply, []byte("PLAIN ")) {
					// 直接回明文 JSON（图灵 WS 的响应格式就是明文）
					raw := wsAutoReply[len("PLAIN "):]
					_ = wc.writeFrame(0x1, raw)
					logf("[WS] 明文回复: %s", truncate(string(raw), 400))
				} else {
					enc := aesEncB64(string(wsAutoReply))
					reply, _ := json.Marshal(map[string]any{"data": enc})
					_ = wc.writeFrame(0x1, reply)
					logf("[WS] 自动回复(加密): %s", truncate(string(reply), 300))
				}
			}
			if wsAutoBin != "" {
				full := filepath.Join(cfg.MediaDir, filepath.Base(wsAutoBin))
				if b, err := os.ReadFile(full); err == nil {
					_ = wc.writeFrame(0x2, b)
					logf("[WS] 自动推送音频 %q (%d 字节, 二进制帧)", wsAutoBin, len(b))
				} else {
					logf("[WS] 音频读取失败 %q: %v", wsAutoBin, err)
				}
			}
		}
	}
}

// wsBroadcast 向所有设备的 WS 连接广播文本帧。
func wsBroadcast(text string) int {
	list := make([]*wsConn, 0)
	for _, d := range allDevices() {
		d.WSMu.Lock()
		for w := range d.WSConns {
			list = append(list, w)
		}
		d.WSMu.Unlock()
	}
	for _, w := range list {
		if err := w.writeFrame(0x1, []byte(text)); err != nil {
			logf("[WS] 发送失败: %v", err)
		}
	}
	logf("[WS] broadcast 到 %d 个连接: %s", len(list), truncate(text, 500))
	return len(list)
}

// wsConnList 某台设备的 WS 连接列表。
func (d *Device) wsConnList() []*wsConn {
	d.WSMu.Lock()
	defer d.WSMu.Unlock()
	out := make([]*wsConn, 0, len(d.WSConns))
	for w := range d.WSConns {
		out = append(out, w)
	}
	return out
}

// wsConnCount 全部设备的 WS 连接总数（/api/ws_count 用）。
func wsConnCount() int {
	n := 0
	for _, d := range allDevices() {
		d.WSMu.Lock()
		n += len(d.WSConns)
		d.WSMu.Unlock()
	}
	return n
}

// resolvePublic 用公共 DNS（223.5.5.5）解析，绕开路由器自身的劫持
func resolvePublic(host string) (string, error) {
	r := &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
		d := net.Dialer{Timeout: 5 * time.Second}
		return d.Dial("udp", "223.5.5.5:53")
	}}
	ips, err := r.LookupHost(context.Background(), host)
	if err != nil || len(ips) == 0 {
		return "", err
	}
	return ips[0], nil
}

// handleWSProxy：设备 WS ↔ 真实图灵云 WS 的双向帧转发
func handleWSProxy(w http.ResponseWriter, r *http.Request) {
	hj, ok := w.(http.Hijacker)
	if !ok {
		return
	}
	dev, devBuf, err := hj.Hijack()
	if err != nil {
		return
	}
	key := r.Header.Get("Sec-WebSocket-Key")
	_, _ = dev.Write([]byte("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
		"Sec-WebSocket-Accept: " + wsAcceptKey(key) + "\r\n\r\n"))

	// 连真实服务器
	ip, err := resolvePublic("ws-api.turingapi.com")
	if err != nil {
		logf("[WSP] DNS 失败: %v", err)
		dev.Close()
		return
	}
	up, err := net.DialTimeout("tcp", ip+":80", 8*time.Second)
	if err != nil {
		logf("[WSP] 连接 %s 失败: %v", ip, err)
		dev.Close()
		return
	}
	upKey := base64.StdEncoding.EncodeToString([]byte("chatrabbit-proxy!"))
	_, _ = up.Write([]byte("GET " + r.URL.RequestURI() + " HTTP/1.1\r\nHost: ws-api.turingapi.com\r\n" +
		"Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + upKey + "\r\n" +
		"Sec-WebSocket-Version: 13\r\n\r\n"))
	upBuf := bufio.NewReader(up)
	// 读上游响应头
	var head []byte
	for {
		line, err := upBuf.ReadString('\n')
		if err != nil {
			up.Close()
			dev.Close()
			return
		}
		head = append(head, []byte(line)...)
		if line == "\r\n" {
			break
		}
	}
	if !strings.Contains(string(head), "101") {
		logf("[WSP] 上游握手失败: %s", truncate(string(head), 200))
		up.Close()
		dev.Close()
		return
	}
	logf("[WSP] 代理已建立: %s <-> %s (%s)", r.RemoteAddr, ip, r.URL.RequestURI())

	done := make(chan struct{}, 2)
	// 设备 -> 上游（需 mask）
	go func() {
		defer func() { done <- struct{}{} }()
		br := devBuf.Reader
		for {
			op, payload, err := readWSFrame(br)
			if err != nil {
				return
			}
			logf("[WSP] -> 上游 op=%d len=%d", op, len(payload))
			if err := writeMaskedFrame(up, op, payload); err != nil {
				return
			}
			if op == 8 {
				return
			}
		}
	}()
	// 上游 -> 设备（不 mask）
	go func() {
		defer func() { done <- struct{}{} }()
		for {
			op, payload, err := readWSFrame(upBuf)
			if err != nil {
				return
			}
			logf("[WSP] <- 上游 op=%d len=%d %s", op, len(payload), truncate(string(payload), 400))
			if err := writeFrameTo(dev, op, payload); err != nil {
				return
			}
			if op == 8 {
				return
			}
		}
	}()
	<-done
	up.Close()
	dev.Close()
}

func writeMaskedFrame(w io.Writer, opcode byte, payload []byte) error {
	mask := [4]byte{0x12, 0x34, 0x56, 0x78}
	n := len(payload)
	out := []byte{0x80 | opcode}
	switch {
	case n < 126:
		out = append(out, 0x80|byte(n))
	case n < 65536:
		out = append(out, 0x80|126, byte(n>>8), byte(n))
	default:
		out = append(out, 0x80|127, 0, 0, 0, 0, byte(n>>24), byte(n>>16), byte(n>>8), byte(n))
	}
	out = append(out, mask[:]...)
	for i, b := range payload {
		out = append(out, b^mask[i%4])
	}
	_, err := w.Write(out)
	return err
}

func writeFrameTo(w io.Writer, opcode byte, payload []byte) error {
	n := len(payload)
	out := []byte{0x80 | opcode}
	switch {
	case n < 126:
		out = append(out, byte(n))
	case n < 65536:
		out = append(out, 126, byte(n>>8), byte(n))
	default:
		out = append(out, 127, 0, 0, 0, 0, byte(n>>24), byte(n>>16), byte(n>>8), byte(n))
	}
	out = append(out, payload...)
	_, err := w.Write(out)
	return err
}

// ---------------- 假图灵 HTTP ----------------

var (
	mqttHijackMu   sync.Mutex
	mqttHijackDone = map[string]bool{}
)

func jsonResp(w http.ResponseWriter, v any) {
	b, _ := json.Marshal(v)
	w.Header().Set("Content-Type", "application/json;charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(b)))
	_, _ = w.Write(b)
}

func turingMock(d *Device, p string) any {
	if d == nil {
		d = defaultDevice()
	}
	ep := "tcp://" + cfg.LanIP + ":" + strconv.Itoa(cfg.MQTTPort)
	switch {
	case strings.HasSuffix(p, "/public/mqttManager/getMQTTConnectInfo"):
		topic, clientID := "", ""
		if d != nil {
			topic, clientID = d.topicName(), d.clientID()
		}
		return map[string]any{"desc": "success", "code": 0, "payload": map[string]any{
			"account": "Signature|local|fake", "password": "local-fake",
			"topic": topic, "clientId": clientID, "endpoint": ep,
		}}

	case strings.HasSuffix(p, "/iot/status/notify"):
		if len(httpRaw) > 0 {
			logf("[httpraw] 状态响应原文: %s", truncate(string(httpRaw), 400))
			return json.RawMessage(httpRaw)
		}
		if len(httpCmd) > 0 {
			var v any
			if json.Unmarshal(httpCmd, &v) == nil {
				logf("[httpcmd] 状态响应回带指令: %s", string(httpCmd))
				return map[string]any{"code": 0, "desc": "success", "payload": v, "cmd": v, "data": v}
			}
		}
		return map[string]any{"code": 0, "desc": "success"}
	case strings.HasSuffix(p, "/iot/v2/ota/initVersion"), strings.HasSuffix(p, "/iot/v2/ota/version"):
		// 设备侧（turing_iot.c: ota_update_version_info）要求：
		// payload.updateStatus==0 / agree==1 / id!=0 /
		// nextVersion 非空且 != currentVersion / currentVersion 必须与设备版本完全一致 /
		// firmwareUrl 非空，才会调用 get_update_data(url) 下载升级
		cur := otaCurrentVersion
		if cur == "" {
			cur = cfg.OTACurrentVersion
		}
		logf("[OTA] 触发检查: current=%q next=%q url=%q md5=%q force=%d",
			cur, cfg.OTAVersion, cfg.OTAURL, cfg.OTAMD5, cfg.OTAForce)
		// 同时给出两套字段名：图灵标准版（nextVersion/firmwareUrl）与厂商/旧版（version/url）
		pay := map[string]any{
			"updateStatus":   0,
			"agree":          1,
			"id":             otaLastID,
			"nextVersion":    cfg.OTAVersion,
			"currentVersion": cur,
			"firmwareUrl":    cfg.OTAURL,
			"firmwareMd5":    cfg.OTAMD5,
			"md5":            cfg.OTAMD5,
			"force":          cfg.OTAForce,
			// 兼容字段
			"version":      cfg.OTAVersion,
			"url":          cfg.OTAURL,
			"softVer":      cfg.OTAVersion,
			"downUrl":      cfg.OTAURL,
			"updateUrl":    cfg.OTAURL,
			"firmwareUrl2": cfg.OTAURL,
		}
		for k, v := range cfg.OTAExtra {
			pay[k] = v
		}
		top := map[string]any{"code": 0, "desc": "success", "msg": "success"}
		for k, v := range cfg.OTAExtra {
			top[k] = v
		}
		// ota_format: ""/"map"=payload 为对象；"string"=payload 为 JSON 字符串；"top"=只留顶层字段
		switch cfg.OTAFormat {
		case "string":
			b, _ := json.Marshal(pay)
			top["payload"] = string(b)
			top["data"] = string(b)
			top["result"] = string(b)
		case "top":
			// 不输出 payload/data/result，全部字段放顶层
			for k, v := range pay {
				if _, exists := top[k]; !exists {
					top[k] = v
				}
			}
		default:
			top["payload"] = pay
			top["data"] = pay
			top["result"] = pay
		}
		return top
	case strings.HasSuffix(p, "/v2/iot/audio"):
		// 设备"下一首"请求：必须返回 payload.{url,name,id}，否则设备播错误提示音。
		// 加闸门：用户按停止后 10 秒内、或 60 秒内已回 3 次 → 回空，避免"停了还在放"。
		if d != nil && !d.gateAllowAudio() {
			logf("[audio] [%s] 闸门拦截（停止后或 60s 内超 3 次），返回空 payload", d.ID)
			return map[string]any{"code": 0, "desc": "success", "payload": map[string]any{}}
		}
		f := ""
		if d != nil {
			f = d.MusicFile
			// ★ 闹钟到点优先用该条闹钟的专属铃声（否则会一直用"最后上传的那首"）
			if rv := d.alarmRingIfDue(); rv != "" {
				f = rv
			}
		}
		if f == "" {
			f = cfg.MusicFile
		}
		if f == "" {
			f = "tone.mp3"
		}
		alias := playAlias(f)
		songURL := mediaHostURL() + "/media/" + url.PathEscape(alias)
		logf("[audio] 设备要下一首，返回 %q(alias=%q) -> %s", f, alias, songURL)
		return map[string]any{"code": 0, "desc": "success",
			"payload": map[string]any{"url": songURL, "name": f, "id": 1001, "type": 0}}
	case strings.HasSuffix(p, "/api/v2"):
		logf("[api/v2] 设备来问，返回 %d 字节", len(v2Resp))
		if len(v2Resp) > 0 {
			var v any
			if json.Unmarshal(v2Resp, &v) == nil {
				return v
			}
		}
		return map[string]any{"code": 0, "desc": "success"}
	case strings.HasSuffix(p, "/time_now"):
		now := time.Now().Add(time.Duration(cfg.TimeOffset) * time.Second)
		sec := now.Unix()
		ds := now.Format("2006-01-02 15:04:05")
		logf("[time_now] 返回 mode=%d %s (sec=%d)", cfg.TimeMode, ds, sec)
		// 多字段别名 + 多层级同时给，覆盖所有可能的解析方式
		// 注意：设备是 32 位时间，绝不返回毫秒（会溢出成 2037 年）
		inner := map[string]any{
			"time": sec, "timestamp": sec, "now": sec, "serverTime": sec, "server_time": sec,
			"time1": sec, "time2": sec, "t": sec, "ts": sec, "sec": sec,
			"date": ds, "datetime": ds, "dateTime": ds, "strTime": ds,
			"timezone": 8, "tz": 8,
		}
		switch cfg.TimeMode {
		case 2:
			// 原「毫秒」模式：设备 32 位会溢出成 2037 年，改为仍然下发秒
			inner["time"] = sec
			inner["timestamp"] = sec
		case 3:
			return map[string]any{"code": 0, "desc": "success", "data": sec, "payload": sec, "timestamp": sec, "time": sec}
		case 4:
			inner["time"] = ds
			inner["timestamp"] = ds
		}
		return map[string]any{
			"code": 0, "desc": "success", "message": "success",
			"data": inner, "payload": inner, "result": inner,
			"timestamp": sec, "time": sec,
		}
	}
	return map[string]any{"code": 0, "desc": "success", "payload": map[string]any{}}
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}

func httpHandler(w http.ResponseWriter, r *http.Request) {
	// 设备侧请求：先按源 IP 归属到设备（MQTT CONNECT 时已记录），再走默认设备兜底
	dev := deviceFromDeviceRequest(r)
	if strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
		logf("[WS] 升级请求 host=%q path=%q ua=%q key=%q accept=%v", r.Host, r.URL.RequestURI(), r.UserAgent(), r.Header.Get("Sec-WebSocket-Key"), cfg.WSAccept)
		if !cfg.WSAccept {
			// 拒绝升级：设备会回退到本地提示音（避免闹铃静音）
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte("ws disabled"))
			return
		}
		if cfg.WSProxy {
			handleWSProxy(w, r)
			return
		}
		handleWS(w, r)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/media/") {
		serveMedia(w, r, strings.TrimPrefix(r.URL.Path, "/media/"))
		return
	}
	if r.URL.Path == "/api/reqlog" {
		jsonResp(w, map[string]any{"ok": true, "items": snapshotReqLog()})
		return
	}
	body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	logf("[HTTP] %s %s host=%q from %s ua=%q body=%s", r.Method, r.URL.RequestURI(), r.Host, r.RemoteAddr, r.UserAgent(), truncate(string(body), 800))
	pushReqLog(time.Now().Format("15:04:05") + " " + r.Method + " " + r.URL.RequestURI() + " host=" + r.Host + " from=" + r.RemoteAddr)
	// 记录设备上报的当前版本，OTA 响应必须回显它（否则设备判定版本不匹配而不升级）
	if strings.HasSuffix(r.URL.Path, "/iot/v2/ota/initVersion") || strings.HasSuffix(r.URL.Path, "/iot/v2/ota/version") {
		var m struct {
			CurrentVersion string `json:"currentVersion"`
		}
		if json.Unmarshal(body, &m) == nil && m.CurrentVersion != "" {
			otaCurrentVersion = m.CurrentVersion
		}
	}
	// /api/v2 特殊响应：REDIRECT <url> 走 302；RAW <text> 走纯文本
	if strings.HasSuffix(r.URL.Path, "/api/v2") && len(v2Resp) > 0 {
		s := string(v2Resp)
		if strings.HasPrefix(s, "REDIRECT ") {
			target := strings.TrimSpace(s[len("REDIRECT "):])
			logf("[api/v2] 302 跳转 -> %s", target)
			http.Redirect(w, r, target, http.StatusFound)
			return
		}
		if strings.HasPrefix(s, "RAW ") {
			txt := s[len("RAW "):]
			logf("[api/v2] 纯文本返回 %d 字节", len(txt))
			w.Header().Set("Content-Type", "text/plain")
			_, _ = w.Write([]byte(txt))
			return
		}
		if strings.HasPrefix(s, "FILE ") {
			name := strings.TrimSpace(s[len("FILE "):])
			full := filepath.Join(cfg.MediaDir, filepath.Base(name))
			if _, err := os.Stat(full); err == nil {
				ct := "audio/mpeg"
				switch strings.ToLower(filepath.Ext(full)) {
				case ".wav":
					ct = "audio/wav"
				case ".mp3":
					ct = "audio/mpeg"
				case ".amr":
					ct = "audio/amr"
				}
				w.Header().Set("Content-Type", ct)
				logf("[api/v2] 直接返回音频 %q (%s)", name, ct)
				http.ServeFile(w, r, full)
				return
			}
			logf("[api/v2] FILE 模式找不到 %q", name)
		}
	}
	if strings.HasSuffix(r.URL.Path, "/iot/status/notify") {
		var m struct {
			Type   int `json:"type"`
			Status struct {
				Vol     int `json:"vol"`
				Battery int `json:"battery"`
				Play    int `json:"play"`  // 1=正在播放 0=已停止（闹铃被按键终止时为 0）
				Alarm   int `json:"alarm"` // 部分固件会带闹钟状态
			} `json:"status"`
		}
		if json.Unmarshal(body, &m) == nil {
			setStatusFor(dev, m.Status.Vol, m.Status.Battery)
			// 设备上报 play=0：说明用户在设备上按了停止/唤醒键 → 立即开启播放闸门
			if m.Status.Play == 0 {
				logf("[stop] 设备上报 play=0（按键终止）→ 开闸门并补发停止指令")
				pushMqttLog("DEV STOP(play=0) 按键终止闹铃")
				if dev != nil {
					dev.stopCmd()
				}
			} else if m.Status.Play == 1 {
				if dev != nil {
					dev.gateResetAudio()
				}
				logf("[play] 设备上报 play=1（开始播放）→ 重置播放闸门")
			}
		}
	}
	// 校时专用：/api/ntp.form（设备硬编码 IP 请求）与 /time_now
	// mode=8 自动轮换多种格式，让设备自己试中（每次请求换一种）
	if strings.Contains(r.URL.Path, "ntp.form") || strings.HasSuffix(r.URL.Path, "/time_now") {
		now := time.Now().Add(time.Duration(cfg.TimeOffset) * time.Second)
		// mode=9：最小化机器人云格式（只给数字秒，不带任何日期字符串/毫秒）
		if cfg.TimeMode == 9 {
			body := `{"code":200,"body":{"time":` + strconv.FormatInt(now.Unix(), 10) + `}}`
			logf("[ntp] mode9 -> %s", body)
			pushMqttLog("NTP(mode9) " + body)
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(body))
			return
		}
		// mode=10：纯文本秒
		if cfg.TimeMode == 10 {
			txt := strconv.FormatInt(now.Unix(), 10)
			logf("[ntp] mode10 -> %s", txt)
			pushMqttLog("NTP(mode10) " + txt)
			w.Header().Set("Content-Type", "text/plain")
			_, _ = w.Write([]byte(txt))
			return
		}
		if cfg.TimeMode == 8 {
			ntpRotMu.Lock()
			idx := ntpRotIdx
			ntpRotIdx++
			ntpRotMu.Unlock()
			// ★ 只能下发 10 位秒级时间戳：设备是 32 位时间，毫秒会溢出成 2037 年
			kinds := []string{"robot_body_sec", "robot_body_str", "json_sec", "data_sec", "sec"}
			kind := kinds[idx%len(kinds)]
			sec := now.Unix()
			ds := now.Format("2006-01-02 15:04:05")
			ntpRotMuShown := ""
			switch kind {
			case "sec":
				ntpRotMuShown = strconv.FormatInt(sec, 10)
			case "robot_body_sec":
				ntpRotMuShown = `{"code":200,"body":{"time":` + strconv.FormatInt(sec, 10) +
					`,"timestamp":` + strconv.FormatInt(sec, 10) +
					`,"date":"` + ds + `","datetime":"` + ds + `"}}`
			case "json_sec":
				ntpRotMuShown = `{"code":0,"desc":"success","timestamp":` + strconv.FormatInt(sec, 10) +
					`,"time":` + strconv.FormatInt(sec, 10) + `}`
			case "data_sec":
				ntpRotMuShown = `{"code":0,"data":{"time":` + strconv.FormatInt(sec, 10) +
					`,"timestamp":` + strconv.FormatInt(sec, 10) + `}}`
			case "robot_body_str":
				ntpRotMuShown = `{"code":200,"body":{"date":"` + ds + `","datetime":"` + ds + `","time":"` + ds + `"}}`
			}
			logf("[ntp] 轮换 %s -> %s", kind, truncate(ntpRotMuShown, 80))
			pushMqttLog("NTP(" + kind + ") " + truncate(ntpRotMuShown, 60))
			if strings.HasPrefix(ntpRotMuShown, "{") {
				w.Header().Set("Content-Type", "application/json")
			} else {
				w.Header().Set("Content-Type", "text/plain")
			}
			_, _ = w.Write([]byte(ntpRotMuShown))
			return
		}
	}
	// /time_now 裸文本模式（mode>=5）：直接回纯文本秒级时间，供只会 atoi/atol 的固件解析
	if strings.HasSuffix(r.URL.Path, "/time_now") && cfg.TimeMode >= 5 {
		now := time.Now().Add(time.Duration(cfg.TimeOffset) * time.Second)
		// 一律秒级：设备 32 位时间存不下毫秒，会溢出成 2037 年
		txt := strconv.FormatInt(now.Unix(), 10)
		logf("[time_now] 裸文本 mode=%d -> %s", cfg.TimeMode, txt)
		w.Header().Set("Content-Type", "text/plain")
		_, _ = w.Write([]byte(txt))
		return
	}
	jsonResp(w, turingMock(dev, r.URL.Path))
}

func serveMedia(w http.ResponseWriter, r *http.Request, name string) {
	name = path.Clean("/" + name)[1:]
	full := filepath.Join(cfg.MediaDir, name)
	st, err := os.Stat(full)
	if err != nil || st.IsDir() {
		http.NotFound(w, r)
		logf("[HTTP] media 404 %q", name)
		return
	}
	dev := deviceFromDeviceRequest(r)
	devID := "-"
	if dev != nil {
		devID = dev.ID
	}
	logf("[HTTP] media [%s] %q range=%q ua=%q", devID, name, r.Header.Get("Range"), r.UserAgent())
	pushMediaLog("[" + devID + "] " + time.Now().Format("15:04:05") + " GET /media/" + name + " range=" + r.Header.Get("Range") + " size=" + strconv.FormatInt(st.Size(), 10))
	if dev != nil {
		dev.noteMedia()
	}
	http.ServeFile(w, r, full)
}

// ---------------- 控制 API ----------------

func apiHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	p := r.URL.Path
	// 当前设备：?dev= → X-Device-Id → 源 IP → 默认设备（旧客户端不带 dev 时行为不变）
	dev := deviceFromRequest(r)
	if p == "/" || p == "/index.html" {
		ui := filepath.Join(filepath.Dir(cfg.MediaDir), "webs", "Module_chatrabbit.asp")
		if _, err := os.Stat(ui); err == nil {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Header().Set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
			w.Header().Set("Pragma", "no-cache")
			http.ServeFile(w, r, ui)
			return
		}
	}
	if p == "/alarms" {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(alarmPageHTML))
		return
	}
	if p == "/dev-alarms" {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(devAlarmPageHTML))
		return
	}
	// ---------------- 老版图灵 "robot" 接口（设备硬编码 120.76.63.67:7890） ----------------
	// 通过 iptables DNAT 把设备到 120.76.63.67:7890 的流量转到本服务。
	// 响应格式固定：{"code":200,"body":{...}}，code/upgrade 必须是 JSON 数字。
	if strings.HasSuffix(p, "/sdk/auth.form") {
		logf("[robot] auth.form %s", r.URL.RawQuery)
		jsonResp(w, map[string]any{"code": 200, "body": map[string]any{
			"token": "0123456789abcdef0123456789abcdef",
		}})
		return
	}
	if strings.HasSuffix(p, "/robot/flash_check.form") {
		logf("[robot] flash_check.form %s (enable=%v)", r.URL.RawQuery, cfg.OTAEnable)
		if !cfg.OTAEnable {
			jsonResp(w, map[string]any{"code": 200, "body": map[string]any{"upgrade": 0}})
			return
		}
		jsonResp(w, map[string]any{"code": 200, "body": map[string]any{
			"upgrade":      1,
			"flashVersion": cfg.OTAVersion,
			"flashUrl":     cfg.OTAURL,
		}})
		return
	}
	if strings.HasSuffix(p, "/robot/flash_download.form") {
		logf("[robot] flash_download.form %s (enable=%v)", r.URL.RawQuery, cfg.OTAEnable)
		if !cfg.OTAEnable {
			jsonResp(w, map[string]any{"code": 200, "body": map[string]any{"upgrade": 0}})
			return
		}
		u := cfg.OTAURL
		if u == "" {
			u = "http://" + cfg.LanIP + ":" + strconv.Itoa(cfg.HTTPPort) + "/media/ota_probe.ufw"
		}
		jsonResp(w, map[string]any{"code": 200, "body": map[string]any{
			"upgrade":     1,
			"firmwareUrl": u,
		}})
		return
	}
	if strings.HasSuffix(p, "/api/ntp.form") {
		// 标准做法：返回 UTC epoch；设备自己按配置换算本地时间
		now := time.Now().UTC()
		sec := now.Unix()
		ds := now.Format("2006-01-02 15:04:05")
		logf("[robot] ntp.form mode=%d -> %d (%s)", cfg.NTPFormMode, sec, ds)
		switch cfg.NTPFormMode {
		case 1: // 对照组：不返回时间，看设备时间是否还跳
			jsonResp(w, map[string]any{"code": 200, "body": map[string]any{}})
			return
		case 2: // 原「毫秒」模式：设备 32 位会溢出成 2037，改为秒
			jsonResp(w, map[string]any{"code": 200, "body": map[string]any{
				"time": sec, "timestamp": sec, "time1": sec, "time2": sec,
			}})
			return
		case 3: // 字符串日期
			jsonResp(w, map[string]any{"code": 200, "body": map[string]any{
				"time": ds, "timestamp": ds, "date": ds, "datetime": ds,
			}})
			return
		}
		// 尽可能多的字段别名（全部秒级，绝不含毫秒）
		body := map[string]any{
			"time": sec, "timestamp": sec, "now": sec, "serverTime": sec, "server_time": sec,
			"time1": sec, "time2": sec, "t": sec, "ts": sec, "sec": sec,
			"date": ds, "datetime": ds, "dateTime": ds, "strTime": ds, "str_time": ds,
			"timezone": 8, "tz": 8,
		}
		jsonResp(w, map[string]any{"code": 200, "body": body, "data": body, "payload": body})
		return
	}
	switch {
	case p == "/api/ws_push":
		var req struct {
			Text string `json:"text"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.Text == "" {
			jsonResp(w, map[string]any{"ok": false, "err": "text required"})
			return
		}
		sent := wsBroadcast(req.Text)
		logf("[ws_push] 已推送到 %d 个 WS 连接: %s", sent, truncate(req.Text, 600))
		jsonResp(w, map[string]any{"ok": true, "sent": sent})
	case p == "/api/ws_count":
		jsonResp(w, map[string]any{"ok": true, "count": wsConnCount()})
	case p == "/api/ntpform":
		var req struct {
			Mode *int `json:"mode"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.Mode != nil {
			cfg.NTPFormMode = *req.Mode
			saveConfig()
			logf("[api] ntp_form_mode=%d", cfg.NTPFormMode)
		}
		jsonResp(w, map[string]any{"ok": true, "mode": cfg.NTPFormMode})
	case p == "/api/kick":
		var targets []*mqttConn
		for _, dd := range allDevices() {
			targets = append(targets, dd.connList()...)
		}
		for _, m := range targets {
			_ = m.c.Close()
		}
		logf("[api] kick: 已断开 %d 个 MQTT 客户端", len(targets))
		jsonResp(w, map[string]any{"ok": true, "kicked": len(targets)})
	case p == "/api/diag":
		jsonResp(w, diagInfo())
	case p == "/api/installlog":
		b, _ := os.ReadFile("/tmp/upload/soft_log.txt")
		s := string(b)
		lines := strings.Split(strings.TrimRight(s, "\n"), "\n")
		if len(lines) > 80 {
			lines = lines[len(lines)-80:]
		}
		jsonResp(w, map[string]any{"ok": true, "lines": lines})
	case p == "/api/media/cleanup_bad":
		removed := []string{}
		var freed int64
		if ents, err := os.ReadDir(cfg.MediaDir); err == nil {
			for _, e := range ents {
				if e.IsDir() {
					continue
				}
				name := e.Name()
				bad := !utf8.ValidString(name) || strings.Contains(name, "%E") || strings.Contains(name, "%D") || strings.ContainsAny(name, "\x00\r\n")
				if !bad {
					continue
				}
				full := filepath.Join(cfg.MediaDir, name)
				var sz int64
				if st, err := e.Info(); err == nil {
					sz = st.Size()
				}
				if err := os.Remove(full); err == nil {
					removed = append(removed, name)
					freed += sz
				}
			}
		}
		logf("[media] 清理乱码文件名 %d 个，释放 %d 字节", len(removed), freed)
		jsonResp(w, map[string]any{"ok": true, "removed": removed, "count": len(removed), "freed": freed})
	case p == "/api/dev/state":
		// 让设备立即上报一次状态（type=3 NOTIFY operate=0 → mqtt_init_state_noitfy）
		raw, _ := json.Marshal(map[string]any{
			"type": 3, "fromUser": "",
			"message": map[string]any{"operate": 0},
		})
		sent := 0
		if dev != nil {
			sent = dev.publishNow(raw)
		}
		logf("[devstate] 请求设备上报状态 dev=%v sent=%d", dev != nil, sent)
		jsonResp(w, map[string]any{"ok": sent > 0, "sent": sent})
	case p == "/api/time":
		now := devNow()
		dsa := ""
		if dev != nil {
			dsa = dev.getStatus().At
		}
		jsonResp(w, map[string]any{
			"ok": true, "server_time": now.Format("2006-01-02 15:04:05"), "tz_hours": cfg.TZHours, "once_mode": cfg.OnceMode,
			"unix": now.Unix(), "offset": cfg.TimeOffset, "mode": cfg.TimeMode,
			"device_status_at": dsa, "lan_ip": cfg.LanIP,
		})
	case p == "/api/time/set":
		var req struct {
			Offset   *int64  `json:"offset"`
			Mode     *int    `json:"mode"`
			TZ       *int    `json:"tz"`
			OnceMode *string `json:"once_mode"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.Offset != nil {
			cfg.TimeOffset = *req.Offset
		}
		if req.Mode != nil {
			cfg.TimeMode = *req.Mode
		}
		if req.TZ != nil {
			cfg.TZHours = *req.TZ
		}
		if req.OnceMode != nil {
			cfg.OnceMode = *req.OnceMode
		}
		saveConfig()
		logf("[time] offset=%d mode=%d tz=%d once_mode=%s", cfg.TimeOffset, cfg.TimeMode, cfg.TZHours, cfg.OnceMode)
		jsonResp(w, map[string]any{"ok": true, "offset": cfg.TimeOffset, "mode": cfg.TimeMode,
			"tz_hours": cfg.TZHours, "once_mode": cfg.OnceMode})
	case p == "/api/medialog":
		jsonResp(w, map[string]any{"ok": true, "items": snapshotMediaLog()})
	case p == "/api/reqlog":
		jsonResp(w, map[string]any{"ok": true, "items": snapshotReqLog()})
	case p == "/api/mqttlog":
		jsonResp(w, map[string]any{"ok": true, "items": snapshotMqttLog()})
	case p == "/api/health":
		// 夜间排障用：DNS 自检 + 每台设备在线/重连统计 + 最近样本
		n := 60
		if v := r.URL.Query().Get("n"); v != "" {
			if i, err := strconv.Atoi(v); err == nil && i > 0 && i <= 240 {
				n = i
			}
		}
		jsonResp(w, map[string]any{
			"ok": true, "now": healthSampleOnce(), "history": healthSnapshot(n),
			"dns_fail_total": healthDNSFailTotal(), "connect_total": healthConnectTotals(),
			"uptime_s": int64(time.Since(healthStart).Seconds()),
		})
	case p == "/api/status":
		// 兼容单设备客户端：不传 ?dev= 时返回默认设备；新增 dev_id/dev_name/devices 字段
		n, devIP, lcStr := 0, "", ""
		stat := Status{Vol: -1, Battery: -1}
		topic, alarmVoice, musicFile := "", "", ""
		online := false
		if dev != nil {
			n = dev.clientCount()
			devIP = dev.deviceIP()
			if t := dev.lastConnectTime(); !t.IsZero() {
				lcStr = t.Format("15:04:05")
			}
			stat = dev.getStatus()
			topic = dev.topicName()
			alarmVoice = dev.AlarmVoice
			musicFile = dev.MusicFile
			online = n > 0
		}
		devID, devName := "", ""
		if dev != nil {
			devID, devName = dev.ID, dev.displayName()
		}
		jsonResp(w, map[string]any{
			"device_online": online, "clients": n, "last_connect": lcStr,
			"device_ip": devIP, "status": stat, "topic": topic, "ip": cfg.LanIP,
			"gap": cfg.GapSec, "version": Version,
			"alarm_voice": alarmVoice, "ws_accept": cfg.WSAccept, "ws_proxy": cfg.WSProxy,
			"ring_seconds": cfg.RingSeconds, "music_file": musicFile,
			"ota_version": cfg.OTAVersion, "ota_url": cfg.OTAURL,
			"dev_id": devID, "dev_name": devName, "device_count": len(allDevices()),
		})
	case p == "/api/file/delete":
		jsonResp(w, deleteMediaFile(r))
	case p == "/api/media/clean":
		removed := []string{}
		var freed int64
		if ents, err := os.ReadDir(cfg.MediaDir); err == nil {
			for _, e := range ents {
				if e.IsDir() {
					continue
				}
				name := e.Name()
				lname := strings.ToLower(name)
				audio := strings.HasSuffix(lname, ".mp3") || strings.HasSuffix(lname, ".aac") ||
					strings.HasSuffix(lname, ".wav") || strings.HasSuffix(lname, ".m4a") ||
					strings.HasSuffix(lname, ".flac") || strings.HasSuffix(lname, ".ogg")
				if audio {
					continue
				}
				full := filepath.Join(cfg.MediaDir, name)
				var sz int64
				if st, err := e.Info(); err == nil {
					sz = st.Size()
				}
				if err := os.Remove(full); err == nil {
					removed = append(removed, name)
					freed += sz
				}
			}
		}
		logf("[media] 清理非音频文件 %d 个，释放 %d 字节", len(removed), freed)
		jsonResp(w, map[string]any{"ok": true, "removed": removed, "count": len(removed), "freed": freed})
	case p == "/api/storage":
		jsonResp(w, storageInfo())
	case p == "/api/log/stat":
		jsonResp(w, logStats())
	case p == "/api/log/clear":
		freed := clearLogs()
		logf("[log] 手动清空日志，释放 %d 字节", freed)
		jsonResp(w, map[string]any{"ok": true, "freed": freed})
	case p == "/api/list":
		ents, _ := os.ReadDir(cfg.MediaDir)
		files := []string{}
		for _, e := range ents {
			if e.IsDir() {
				continue
			}
			// 隐藏播放别名（__play_* 是内部临时文件）
			if strings.HasPrefix(e.Name(), "__play") {
				continue
			}
			files = append(files, e.Name())
		}
		jsonResp(w, map[string]any{"files": files})
	case p == "/api/play":
		var req struct{ File string }
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.File == "" {
			jsonResp(w, map[string]any{"ok": false, "err": "file required"})
			return
		}
		if _, err := os.Stat(filepath.Join(cfg.MediaDir, req.File)); err != nil {
			jsonResp(w, map[string]any{"ok": false, "err": "no such file"})
			return
		}
		if dev == nil {
			jsonResp(w, map[string]any{"ok": false, "err": "no device"})
			return
		}
		alias := playAlias(req.File)
		dev.gateResetAudio() // 用户主动播放：重置闸门
		dev.playCmd(alias)
		dev.MusicFile = filepath.Base(req.File)
		persistDevices()
		logf("[api] [%s] play %q (alias=%q, music_file 同步更新)", dev.ID, req.File, alias)
		jsonResp(w, map[string]any{"ok": true, "file": dev.MusicFile, "alias": alias, "dev_id": dev.ID})
	case p == "/api/ring":
		var req struct {
			Seconds *int    `json:"seconds"`
			File    *string `json:"file"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.Seconds != nil {
			cfg.RingSeconds = *req.Seconds
		}
		if req.File != nil && *req.File != "" && dev != nil {
			dev.AlarmVoice = filepath.Base(*req.File)
			persistDevices()
		}
		saveConfig()
		file := ""
		if dev != nil {
			file = dev.AlarmVoice
		}
		logf("[api] ring seconds=%d file=%q", cfg.RingSeconds, file)
		jsonResp(w, map[string]any{"ok": true, "seconds": cfg.RingSeconds, "file": file})
	case p == "/api/music":
		var req struct {
			File *string `json:"file"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.File != nil && dev != nil {
			dev.MusicFile = filepath.Base(*req.File)
			persistDevices()
			logf("[api] [%s] music_file=%q", dev.ID, dev.MusicFile)
		}
		file := ""
		if dev != nil {
			file = dev.MusicFile
		}
		jsonResp(w, map[string]any{"ok": true, "file": file})
	case p == "/api/files":
		var out []string
		if ents, err := os.ReadDir(cfg.MediaDir); err == nil {
			for _, e := range ents {
				if e.IsDir() {
					continue
				}
				if strings.HasPrefix(e.Name(), "__play") {
					continue
				}
				if strings.HasSuffix(strings.ToLower(e.Name()), ".mp3") {
					out = append(out, e.Name())
				}
			}
		}
		jsonResp(w, map[string]any{"files": out})
	case p == "/api/stop":
		if dev != nil {
			dev.stopCmd()
		}
		jsonResp(w, map[string]any{"ok": true})
	case p == "/api/cmd":
		var req struct{ Operate int }
		_ = json.NewDecoder(r.Body).Decode(&req)
		if dev != nil {
			dev.opCmd(req.Operate)
		}
		jsonResp(w, map[string]any{"ok": true, "operate": req.Operate})
	case p == "/api/playchat":
		// 备用播放通道：type=0 (chat) 的 url 播放（app_music 的 AI_SERVER_EVENT_RECV_CHAT 分支）
		var req struct {
			File string `json:"file"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		f := req.File
		if f == "" && dev != nil {
			f = dev.MusicFile
		}
		if f == "" {
			f = "tone.mp3"
		}
		alias := playAlias(f)
		u := mediaHostURL() + "/media/" + url.PathEscape(alias)
		openid := cfg.OpenID
		if dev != nil && dev.OpenID != "" {
			openid = dev.OpenID
		}
		raw, _ := json.Marshal(map[string]any{
			"type": 0, "fromUser": openid,
			"message": map[string]any{"url": u},
		})
		sent := 0
		if dev != nil {
			sent = dev.publishNow(raw)
		}
		logf("[playchat] file=%q alias=%q url=%s sent=%d", f, alias, u, sent)
		jsonResp(w, map[string]any{"ok": sent > 0, "alias": alias, "url": u, "sent": sent})
	case p == "/api/volume":
		var req struct {
			Vol *int `json:"vol"`
			Op  *int `json:"op"` // 可选：0=音量-，1=音量+（内部换算成目标音量）
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		target := 5
		if req.Vol != nil {
			target = *req.Vol
		} else if req.Op != nil {
			cur := 0
			if dev != nil {
				cur = dev.getStatus().Vol
			}
			if cur < 0 {
				cur = 0
			}
			if *req.Op == 1 {
				target = cur + 1
			} else {
				target = cur - 1
			}
		}
		if target < 0 {
			target = 0
		}
		// 设备音量合法范围 0~4（0=振动 1=静音 2/3/4=音量1~3）；越界会让设备异常重启
		if target > 4 {
			target = 4
		}
		// 固件 turing_mqtt_cmd.c 的 CONTROL 分支：必须带 arg，arg 就是目标音量
		raw, _ := json.Marshal(map[string]any{
			"type": 2, "fromUser": "",
			"message": map[string]any{"operate": 1, "arg": target},
		})
		sent := 0
		if dev != nil {
			sent = dev.publishNow(raw)
		}
		logf("[volume] [%v] 目标音量=%d sent=%d", dev != nil, target, sent)
		jsonResp(w, map[string]any{"ok": sent > 0, "vol": target, "sent": sent})
	case p == "/api/dev_alarm":
		// 通过 MQTT 下发设备本机闹钟命令（需要补丁固件）
		var req struct {
			Action  string `json:"action"` // add / del
			Loop    int    `json:"loop"`
			Hour    int    `json:"hour"`
			Minute  int    `json:"minute"`
			Second  int    `json:"second"`
			Year    int    `json:"year"`
			Month   int    `json:"month"`
			Day     int    `json:"day"`
			Event   string `json:"event"`
			AlarmID int    `json:"alarmId"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		var env map[string]any
		if req.Action == "del" {
			env = map[string]any{"opt_code": "alarm_del", "payload": map[string]any{"alarmId": req.AlarmID}}
		} else {
			env = map[string]any{"opt_code": "alarm_set", "payload": map[string]any{
				"loop": req.Loop, "hour": req.Hour, "mintue": req.Minute, "second": req.Second,
				"year": req.Year, "month": req.Month, "day": req.Day, "event": req.Event,
			}}
		}
		raw, _ := json.Marshal(env)
		hexStr := make([]byte, len(raw)*2)
		const hexd = "0123456789abcdef"
		for i, b := range raw {
			hexStr[i*2] = hexd[b>>4]
			hexStr[i*2+1] = hexd[b&0x0f]
		}
		msg := map[string]any{
			"type": 1,
			"message": map[string]any{
				"arg":     "@ALARM",
				"url":     string(hexStr),
				"mediaId": "1",
				"operate": 1,
			},
		}
		mraw, _ := json.Marshal(msg)
		if dev != nil {
			dev.enqueueRaw(mraw)
		}
		logf("[dev_alarm] %s -> %s", req.Action, string(raw))
		jsonResp(w, map[string]any{"ok": true, "queued": 1, "envelope": string(raw), "mqtt": string(mraw)})
	case p == "/api/rawhex":
		// 发送任意二进制 MQTT payload（hex 字符串）
		raw, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		txt := strings.TrimSpace(string(raw))
		txt = strings.TrimPrefix(txt, "0x")
		txt = strings.ReplaceAll(txt, " ", "")
		b, err := hex.DecodeString(txt)
		if err != nil {
			jsonResp(w, map[string]any{"ok": false, "err": err.Error()})
			return
		}
		if dev == nil {
			jsonResp(w, map[string]any{"ok": false, "err": "no device"})
			return
		}
		dev.enqueueRaw(b)
		logf("[rawhex] [%s] %d 字节: %s", dev.ID, len(b), truncate(string(b), 200))
		jsonResp(w, map[string]any{"ok": true, "queued": 1, "len": len(b), "dev_id": dev.ID})
	case p == "/api/raw":
		raw, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		n := 0
		var bad string
		if dev == nil {
			jsonResp(w, map[string]any{"ok": false, "err": "no device"})
			return
		}
		for _, ln := range bytes.Split(raw, []byte("\n")) {
			ln = bytes.TrimSpace(ln)
			if len(ln) == 0 {
				continue
			}
			var v any
			if err := json.Unmarshal(ln, &v); err != nil {
				bad = err.Error()
				break
			}
			dev.enqueueRaw(ln)
			n++
		}
		if bad != "" {
			jsonResp(w, map[string]any{"ok": false, "err": "bad json: " + bad, "queued": n})
			return
		}
		jsonResp(w, map[string]any{"ok": true, "queued": n})
	case p == "/api/alarmvoice":
		var req struct{ File string }
		body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		if err := json.Unmarshal(body, &req); err != nil {
			req.File = strings.TrimSpace(string(body))
		}
		if dev == nil {
			jsonResp(w, map[string]any{"ok": false, "err": "no device"})
			return
		}
		if req.File == "" || req.File == "null" {
			dev.AlarmVoice = ""
			logf("[alarmvoice] [%s] cleared", dev.ID)
		} else {
			dev.AlarmVoice = filepath.Base(req.File)
			logf("[alarmvoice] [%s] set: %s", dev.ID, dev.AlarmVoice)
		}
		persistDevices()
		jsonResp(w, map[string]any{"ok": true, "file": dev.AlarmVoice, "dev_id": dev.ID})
	case p == "/api/wsbin":
		raw, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		name := strings.TrimSpace(string(raw))
		if name == "" || name == "null" {
			wsAutoBin = ""
			logf("[wsbin] cleared")
		} else {
			wsAutoBin = filepath.Base(name)
			logf("[wsbin] set: %s", wsAutoBin)
		}
		jsonResp(w, map[string]any{"ok": true, "file": wsAutoBin})
	case p == "/api/wsproxy":
		var req struct{ Enable *bool }
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.Enable != nil {
			cfg.WSProxy = *req.Enable
			if cfg.WSProxy {
				cfg.WSAccept = true
			}
			saveConfig()
			logf("[api] ws_proxy=%v ws_accept=%v (已保存)", cfg.WSProxy, cfg.WSAccept)
		}
		jsonResp(w, map[string]any{"ok": true, "proxy": cfg.WSProxy, "accept": cfg.WSAccept})
	case p == "/api/wsaccept":
		var req struct{ Accept *bool }
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.Accept != nil {
			cfg.WSAccept = *req.Accept
			saveConfig()
			logf("[api] ws_accept=%v (已保存)", cfg.WSAccept)
		}
		jsonResp(w, map[string]any{"ok": true, "accept": cfg.WSAccept})
	case p == "/api/ota":
		var req struct {
			Version *string
			URL     *string
			MD5     *string
			Force   *int
			Extra   map[string]any
			Format  *string
			Enable  *bool
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.Version != nil {
			cfg.OTAVersion = *req.Version
		}
		if req.URL != nil {
			cfg.OTAURL = *req.URL
		}
		if req.MD5 != nil {
			cfg.OTAMD5 = *req.MD5
		}
		if req.Force != nil {
			cfg.OTAForce = *req.Force
		}
		if req.Extra != nil {
			cfg.OTAExtra = req.Extra
		}
		if req.Format != nil {
			cfg.OTAFormat = *req.Format
		}
		if req.Enable != nil {
			cfg.OTAEnable = *req.Enable
		}
		saveConfig()
		logf("[api] ota version=%q url=%q md5=%q force=%d (已保存)",
			cfg.OTAVersion, cfg.OTAURL, cfg.OTAMD5, cfg.OTAForce)
		jsonResp(w, map[string]any{"ok": true, "version": cfg.OTAVersion,
			"url": cfg.OTAURL, "md5": cfg.OTAMD5, "force": cfg.OTAForce})
	case p == "/api/wsauto":
		raw, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		raw = bytes.TrimSpace(raw)
		if len(raw) == 0 || string(raw) == "null" {
			wsAutoReply = nil
			logf("[wsauto] cleared")
		} else {
			wsAutoReply = raw
			logf("[wsauto] set: %s", string(raw))
		}
		jsonResp(w, map[string]any{"ok": true, "len": len(wsAutoReply), "enc_preview": aesEncB64("{\"code\":0}")})
	case p == "/api/ws":
		raw, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		n := wsBroadcast(string(raw))
		jsonResp(w, map[string]any{"ok": true, "sent": n})
	case p == "/api/time":
		var req struct {
			Offset *int64 `json:"offset"`
			Mode   *int   `json:"mode"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.Offset != nil {
			cfg.TimeOffset = *req.Offset
			logf("[api] time offset=%ds", cfg.TimeOffset)
		}
		if req.Mode != nil {
			cfg.TimeMode = *req.Mode
			logf("[api] time mode=%d", cfg.TimeMode)
		}
		saveConfig()
		jsonResp(w, map[string]any{"ok": true, "offset": cfg.TimeOffset, "mode": cfg.TimeMode,
			"served": time.Now().Unix() + cfg.TimeOffset})
	case p == "/api/httpraw":
		raw, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		raw = bytes.TrimSpace(raw)
		if len(raw) == 0 || string(raw) == "null" {
			httpRaw = nil
			logf("[httpraw] cleared")
		} else {
			if !json.Valid(raw) {
				jsonResp(w, map[string]any{"ok": false, "err": "invalid json"})
				return
			}
			httpRaw = raw
			logf("[httpraw] set: %s", truncate(string(raw), 400))
		}
		jsonResp(w, map[string]any{"ok": true, "len": len(httpRaw)})
	case p == "/api/httpcmd":
		raw, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		raw = bytes.TrimSpace(raw)
		if len(raw) == 0 || string(raw) == "null" {
			httpCmd = nil
			logf("[httpcmd] cleared")
		} else {
			var v any
			if err := json.Unmarshal(raw, &v); err != nil {
				jsonResp(w, map[string]any{"ok": false, "err": err.Error()})
				return
			}
			httpCmd = raw
			logf("[httpcmd] set: %s", string(raw))
		}
		jsonResp(w, map[string]any{"ok": true, "len": len(httpCmd)})
	case p == "/api/v2resp":
		raw, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		raw = bytes.TrimSpace(raw)
		if len(raw) == 0 || string(raw) == "null" {
			v2Resp = nil
			logf("[v2resp] cleared")
		} else {
			v2Resp = raw
			var v any
			if err := json.Unmarshal(raw, &v); err != nil {
				logf("[v2resp] set(text): %s", string(raw))
			} else {
				logf("[v2resp] set(json): %s", string(raw))
			}
		}
		jsonResp(w, map[string]any{"ok": true, "len": len(v2Resp)})
	case p == "/api/upload":
		name := r.URL.Query().Get("name")
		if name == "" {
			name = "upload.bin"
		}
		name = filepath.Base(name)
		dst := filepath.Join(cfg.MediaDir, name)
		f, err := os.Create(dst)
		if err != nil {
			jsonResp(w, map[string]any{"ok": false, "err": err.Error()})
			return
		}
		n, err := io.Copy(f, io.LimitReader(r.Body, 200<<20))
		f.Close()
		logf("[API] upload %q %d bytes", name, n)
		jsonResp(w, map[string]any{"ok": err == nil, "file": name, "bytes": n})
	case p == "/api/alarms":
		alarmMu.Lock()
		list := append([]Alarm{}, alarms...)
		alarmMu.Unlock()
		jsonResp(w, map[string]any{"alarms": list})
	case p == "/api/alarms/add":
		var req struct {
			Time   string `json:"time"`
			Repeat []int  `json:"repeat"`
			At     int64  `json:"at"` // 一次性闹钟（Unix 秒）
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		alarmMu.Lock()
		maxID := 0
		for _, a := range alarms {
			if a.ID > maxID {
				maxID = a.ID
			}
		}
		al := Alarm{ID: maxID + 1, Time: req.Time, Repeat: req.Repeat, Enabled: true, At: req.At}
		alarms = append(alarms, al)
		saveAlarms()
		alarmMu.Unlock()
		jsonResp(w, map[string]any{"ok": true, "alarm": al})
	case p == "/api/alarms/toggle":
		var req struct {
			ID      int  `json:"id"`
			Enabled bool `json:"enabled"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		alarmMu.Lock()
		var target *Alarm
		for i := range alarms {
			if alarms[i].ID == req.ID {
				alarms[i].Enabled = req.Enabled
				target = &alarms[i]
			}
		}
		saveAlarms()
		alarmMu.Unlock()
		jsonResp(w, map[string]any{"ok": target != nil})
	case p == "/api/alarms/update":
		var req struct {
			ID      int     `json:"id"`
			Time    *string `json:"time"`
			Repeat  []int   `json:"repeat"`
			Enabled *bool   `json:"enabled"`
			At      *int64  `json:"at"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		alarmMu.Lock()
		var target *Alarm
		for i := range alarms {
			if alarms[i].ID == req.ID {
				if req.Time != nil {
					alarms[i].Time = *req.Time
				}
				if req.Repeat != nil {
					alarms[i].Repeat = req.Repeat
				}
				if req.Enabled != nil {
					alarms[i].Enabled = *req.Enabled
				}
				if req.At != nil {
					alarms[i].At = *req.At
				}
				alarms[i].FiredAt = 0
				target = &alarms[i]
			}
		}
		saveAlarms()
		alarmMu.Unlock()
		jsonResp(w, map[string]any{"ok": target != nil, "alarm": target})
	case p == "/api/alarms/remove":
		var req struct{ ID int }
		_ = json.NewDecoder(r.Body).Decode(&req)
		alarmMu.Lock()
		var out []Alarm
		for _, a := range alarms {
			if a.ID != req.ID {
				out = append(out, a)
			}
		}
		alarms = out
		saveAlarms()
		alarmMu.Unlock()
		jsonResp(w, map[string]any{"ok": true})
	case p == "/api/dev/list":
		if dev == nil {
			jsonResp(w, map[string]any{"ok": false, "err": "no device", "list": []DevAlarm{}})
			return
		}
		dev.AlarmMu.Lock()
		out := append([]DevAlarm{}, dev.Alarms...)
		dev.AlarmMu.Unlock()
		jsonResp(w, map[string]any{"ok": true, "list": out, "dev_id": dev.ID})

	case p == "/api/dev/add":
		rawAdd, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		pushReqLog("BODY /api/dev/add " + truncate(string(rawAdd), 500))
		var req struct {
			Hour      int    `json:"hour"`
			Minute    int    `json:"minute"`
			Name      string `json:"name"`
			Enabled   bool   `json:"enabled"`
			Weekday   int    `json:"weekday"`  // 0=每天 1..7=周一..周日
			Wk        int    `json:"wk"`       // 直接给位掩码（bit0=周一 ... bit6=周日），优先于 weekday
			Days      []int  `json:"days"`     // 数组形式 1..7（周一..周日），与 wk 等价
			OnceDate  string `json:"onceDate"` // 可选 yyyy-MM-dd，仅一次
			OnceDate2 string `json:"once_date"`
			Date      string `json:"date"`
			SD        int64  `json:"sd"`
			Wt        *int   `json:"wt"`   // 设备记录里的 wt（响铃方式/铃声类型）
			Ring      string `json:"ring"` // 该闹钟的专属铃声（media 文件名，空=用全局 alarm_voice）
		}
		_ = json.Unmarshal(rawAdd, &req)
		a := DevAlarm{Na: req.Name, TH: req.Hour, TM: req.Minute, SD: time.Now().Unix(), WK: 63, WT: 0, S: 0}
		if req.Wt != nil {
			a.WT = *req.Wt
		}
		if req.Ring != "" {
			a.RV = filepath.Base(req.Ring)
		}
		if req.Enabled {
			a.S = 1
		}
		od := req.OnceDate
		if od == "" {
			od = req.OnceDate2
		}
		if od == "" {
			od = req.Date
		}
		if od != "" || req.SD > 0 {
			// 仅一次编码（两种实验模式，见 cfg.OnceMode）：
			//   weekday: sd=日期00:00 + wk=该日期星期位（设备只按星期位判定）
			//   epoch  : sd=精确触发时刻 + wk=0（假设固件把 sd 当绝对时间）
			var target time.Time
			if req.SD > 0 {
				a.SD = req.SD
				target = time.Unix(req.SD, 0).UTC().Add(time.Duration(cfg.TZHours) * time.Hour)
			} else if t, err := time.ParseInLocation("2006-01-02", od, time.Local); err == nil {
				a.SD = t.Unix()
				target = time.Unix(a.SD, 0).UTC().Add(time.Duration(cfg.TZHours) * time.Hour)
			}
			a.ONCE = true
			fire := time.Date(target.Year(), target.Month(), target.Day(), req.Hour, req.Minute, 0, 0, time.UTC).
				Add(-time.Duration(cfg.TZHours) * time.Hour)
			a.Until = fire.Add(30 * time.Minute).Unix()
			if cfg.OnceMode == "epoch" {
				a.SD = fire.Unix() // 精确触发时刻
				a.WK = 0
			} else {
				a.WK = onceWeekMask(target) // 该日期星期位
			}
			logf("[dev] add 仅一次 mode=%s date=%q sd=%d wk=%d 触发≈%s", cfg.OnceMode, od, a.SD, a.WK,
				fire.Add(time.Duration(cfg.TZHours)*time.Hour).Format("01-02 15:04"))
		} else if req.Wk > 0 {
			a.WK = req.Wk // 位掩码：一次写入多条星期，避免 App 拆成多条记录
		} else if len(req.Days) > 0 {
			mask := 0
			for _, d := range req.Days {
				if d >= 1 && d <= 7 {
					mask |= 1 << (d - 1)
				}
			}
			if mask == 0 {
				mask = 63
			}
			a.WK = mask
		} else if req.Weekday == 0 {
			a.WK = 63 // 每天
		} else if req.Weekday > 0 && req.Weekday <= 7 {
			a.WK = 1 << (req.Weekday - 1)
		}
		if dev == nil {
			jsonResp(w, map[string]any{"ok": false, "err": "no device"})
			return
		}
		dev.AlarmMu.Lock()
		dev.Alarms = append(dev.Alarms, a)
		dev.saveAlarmsLocked()
		dev.AlarmMu.Unlock()
		dev.pushAlarms()
		jsonResp(w, map[string]any{"ok": true, "dev_id": dev.ID})

	case p == "/api/dev/upsert":
		// 原子"新增或修改"：按 match 条件定位旧记录原地替换；找不到就追加。
		// 避免 App 侧 del+add 造成设备端短暂缺条/看起来像清空。
		rawUp, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		pushReqLog("BODY /api/dev/upsert " + truncate(string(rawUp), 500))
		var req struct {
			Hour      int    `json:"hour"`
			Minute    int    `json:"minute"`
			Name      string `json:"name"`
			Enabled   bool   `json:"enabled"`
			Wk        int    `json:"wk"`
			OnceDate  string `json:"onceDate"`
			OnceDate2 string `json:"once_date"`
			Date      string `json:"date"`
			SD        int64  `json:"sd"`
			Wt        *int   `json:"wt"`
			Ring      string `json:"ring"` // 该闹钟的专属铃声（空=保留原值/用全局）
			Match     *struct {
				Name   string `json:"name"`
				Hour   int    `json:"hour"`
				Minute int    `json:"minute"`
				Wk     int    `json:"wk"`
			} `json:"match"`
		}
		_ = json.Unmarshal(rawUp, &req)
		a := DevAlarm{Na: req.Name, TH: req.Hour, TM: req.Minute, SD: time.Now().Unix(), WK: 63, WT: 0, S: 0}
		if req.Wt != nil {
			a.WT = *req.Wt
		}
		if req.Enabled {
			a.S = 1
		}
		od := req.OnceDate
		if od == "" {
			od = req.OnceDate2
		}
		if od == "" {
			od = req.Date
		}
		if od != "" || req.SD > 0 {
			// 仅一次：用"目标日期所在星期的单一位"编码（设备对 wk=0 不触发）
			var target time.Time
			if req.SD > 0 {
				a.SD = req.SD
				target = time.Unix(req.SD, 0).UTC().Add(time.Duration(cfg.TZHours) * time.Hour)
			} else if t, err := time.ParseInLocation("2006-01-02", od, time.Local); err == nil {
				a.SD = t.Unix()
				target = time.Unix(a.SD, 0).UTC().Add(time.Duration(cfg.TZHours) * time.Hour)
			}
			a.ONCE = true
			fire := time.Date(target.Year(), target.Month(), target.Day(), req.Hour, req.Minute, 0, 0, time.UTC).
				Add(-time.Duration(cfg.TZHours) * time.Hour)
			a.Until = fire.Add(30 * time.Minute).Unix()
			if cfg.OnceMode == "epoch" {
				a.SD = fire.Unix()
				a.WK = 0
			} else {
				a.WK = onceWeekMask(target)
			}
			logf("[dev] upsert 仅一次 mode=%s date=%q sd=%d wk=%d 触发≈%s", cfg.OnceMode, od, a.SD, a.WK,
				fire.Add(time.Duration(cfg.TZHours)*time.Hour).Format("01-02 15:04"))
		} else if req.Wk > 0 {
			a.WK = req.Wk
		}
		if dev == nil {
			jsonResp(w, map[string]any{"ok": false, "err": "no device"})
			return
		}
		dev.AlarmMu.Lock()
		replaced := -1
		if req.Match != nil {
			for i := range dev.Alarms {
				d := dev.Alarms[i]
				if d.Na == req.Match.Name && d.TH == req.Match.Hour && d.TM == req.Match.Minute &&
					(req.Match.Wk == 0 || d.WK == req.Match.Wk) {
					if req.Ring == "" {
						a.RV = d.RV // 未显式指定铃声时沿用旧值
					} else {
						a.RV = filepath.Base(req.Ring)
					}
					dev.Alarms[i] = a
					replaced = i
					break
				}
			}
		}
		if replaced < 0 {
			if req.Ring != "" {
				a.RV = filepath.Base(req.Ring)
			}
			dev.Alarms = append(dev.Alarms, a)
		}
		dev.saveAlarmsLocked()
		total := len(dev.Alarms)
		dev.AlarmMu.Unlock()
		dev.pushAlarms()
		logf("[dev] [%s] upsert %q %02d:%02d wk=%d -> %s (total %d)", dev.ID, a.Na, a.TH, a.TM, a.WK,
			map[bool]string{true: "replaced", false: "appended"}[replaced >= 0], total)
		jsonResp(w, map[string]any{"ok": true, "replaced": replaced >= 0, "index": replaced, "total": total, "dev_id": dev.ID})

	case p == "/api/dev/ring":
		// 给某条已有闹钟单独设置铃声（不改时间/星期，不下发设备）
		var req struct {
			Index int    `json:"index"`
			File  string `json:"file"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if dev == nil {
			jsonResp(w, map[string]any{"ok": false, "err": "no device"})
			return
		}
		dev.AlarmMu.Lock()
		ok := req.Index >= 0 && req.Index < len(dev.Alarms)
		if ok {
			dev.Alarms[req.Index].RV = filepath.Base(req.File)
			dev.saveAlarmsLocked()
		}
		var cur DevAlarm
		if ok {
			cur = dev.Alarms[req.Index]
		}
		dev.AlarmMu.Unlock()
		if !ok {
			jsonResp(w, map[string]any{"ok": false, "error": "index out of range"})
			return
		}
		logf("[dev] [%s] 闹钟 index=%d 铃声设为 %q", dev.ID, req.Index, cur.RV)
		jsonResp(w, map[string]any{"ok": true, "index": req.Index, "ring": cur.RV})

	case p == "/api/dev/wt":
		// 只改某条闹钟的 wt 字段（固件字段，语义待证；用于对照实验），并整表下发
		var req struct {
			Index int  `json:"index"`
			Wt    *int `json:"wt"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if dev == nil {
			jsonResp(w, map[string]any{"ok": false, "err": "no device"})
			return
		}
		if req.Wt == nil {
			jsonResp(w, map[string]any{"ok": false, "err": "wt required"})
			return
		}
		val := *req.Wt
		if val < 0 {
			val = 0
		}
		if val > 9 {
			val = 9
		}
		dev.AlarmMu.Lock()
		ok := req.Index >= 0 && req.Index < len(dev.Alarms)
		if ok {
			dev.Alarms[req.Index].WT = val
			dev.saveAlarmsLocked()
		}
		dev.AlarmMu.Unlock()
		if !ok {
			jsonResp(w, map[string]any{"ok": false, "error": "index out of range"})
			return
		}
		dev.pushAlarms()
		logf("[dev] [%s] 闹钟 index=%d wt 设为 %d", dev.ID, req.Index, val)
		jsonResp(w, map[string]any{"ok": true, "index": req.Index, "wt": val})

	case p == "/api/dev/del":
		var req struct {
			Index int `json:"index"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if dev == nil {
			jsonResp(w, map[string]any{"ok": false, "err": "no device"})
			return
		}
		dev.AlarmMu.Lock()
		if req.Index >= 0 && req.Index < len(dev.Alarms) {
			dev.Alarms = append(dev.Alarms[:req.Index], dev.Alarms[req.Index+1:]...)
		}
		dev.saveAlarmsLocked()
		dev.AlarmMu.Unlock()
		dev.pushAlarms()
		jsonResp(w, map[string]any{"ok": true})

	case p == "/api/dev/toggle":
		var req struct {
			Index int `json:"index"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if dev == nil {
			jsonResp(w, map[string]any{"ok": false, "err": "no device"})
			return
		}
		dev.AlarmMu.Lock()
		if req.Index >= 0 && req.Index < len(dev.Alarms) {
			if dev.Alarms[req.Index].S == 1 {
				dev.Alarms[req.Index].S = 0
			} else {
				dev.Alarms[req.Index].S = 1
			}
		}
		dev.saveAlarmsLocked()
		dev.AlarmMu.Unlock()
		dev.pushAlarms()
		jsonResp(w, map[string]any{"ok": true})

	case p == "/api/dev/clear":
		if dev == nil {
			jsonResp(w, map[string]any{"ok": false, "err": "no device"})
			return
		}
		dev.AlarmMu.Lock()
		dev.Alarms = []DevAlarm{}
		dev.saveAlarmsLocked()
		dev.AlarmMu.Unlock()
		dev.pushAlarms()
		jsonResp(w, map[string]any{"ok": true})

	case p == "/api/dev/push":
		if dev == nil {
			jsonResp(w, map[string]any{"ok": false, "err": "no device"})
			return
		}
		dev.pushAlarms()
		jsonResp(w, map[string]any{"ok": true})

	case p == "/api/ctl":
		// 设备控制：0=音量减 1=音量加 2=关灯 3=开灯 7=查询状态
		var req struct {
			Operate int `json:"operate"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if dev != nil {
			dev.opCmd(req.Operate)
		}
		logf("[ctl] operate=%d", req.Operate)
		jsonResp(w, map[string]any{"ok": true, "operate": req.Operate})

	case p == "/api/ringtest":
		// 让设备立刻播放当前闹铃
		f := ""
		if dev != nil {
			f = dev.AlarmVoice
			if f == "" {
				f = dev.MusicFile
			}
		}
		if f == "" {
			jsonResp(w, map[string]any{"ok": false, "err": "no ringtone configured"})
			return
		}
		dev.playCmd(playAlias(f))
		logf("[ringtest] [%s] play %q (alias)", dev.ID, f)
		jsonResp(w, map[string]any{"ok": true, "file": f})

	case p == "/api/ringtone/upload":
		// 上传自定义闹铃音频（原始字节）。保留客户端原始文件名，存到同一 media 目录，
		// 这样网页和 App 上传的音乐都出现在同一个列表里（同名自动加序号，不覆盖）。
		body, err := io.ReadAll(io.LimitReader(r.Body, 32<<20))
		if err != nil || len(body) == 0 {
			jsonResp(w, map[string]any{"ok": false, "err": "empty body"})
			return
		}
		name := sanitizeAudioName(r.URL.Query().Get("name"))
		dst := uniqueMediaPath(name)
		if err := os.WriteFile(dst, body, 0644); err != nil {
			jsonResp(w, map[string]any{"ok": false, "err": err.Error()})
			return
		}
		saved := filepath.Base(dst)
		if dev != nil {
			dev.AlarmVoice = saved
			persistDevices()
		}
		logf("[ringtone] [%v] 上传 %d 字节 -> %s", dev != nil, len(body), saved)
		jsonResp(w, map[string]any{"ok": true, "size": len(body), "file": saved})

	// ---------------- 多设备管理（v1.5.0） ----------------
	case p == "/api/devices":
		jsonResp(w, map[string]any{
			"ok": true, "default": defaultDeviceID(),
			"devices": deviceSummary(),
		})
	case p == "/api/devices/add":
		var req struct {
			ID     string `json:"id"`
			Name   string `json:"name"`
			APIKey string `json:"apiKey"`
			OpenID string `json:"openid"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		d, err := addDevice(req.ID, req.Name, req.APIKey, req.OpenID)
		if err != nil {
			jsonResp(w, map[string]any{"ok": false, "err": err.Error()})
			return
		}
		go startOnceJanitorFor(d)
		jsonResp(w, map[string]any{"ok": true, "device": d.info(), "default": defaultDeviceID()})
	case p == "/api/devices/del":
		var req struct {
			ID string `json:"id"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if err := delDevice(req.ID); err != nil {
			jsonResp(w, map[string]any{"ok": false, "err": err.Error()})
			return
		}
		jsonResp(w, map[string]any{"ok": true, "default": defaultDeviceID(), "devices": deviceSummary()})
	case p == "/api/devices/select":
		var req struct {
			ID string `json:"id"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if err := setDefaultDevice(req.ID); err != nil {
			jsonResp(w, map[string]any{"ok": false, "err": err.Error()})
			return
		}
		jsonResp(w, map[string]any{"ok": true, "default": defaultDeviceID()})

	case p == "/api/alarms/clear":
		alarmMu.Lock()
		alarms = []Alarm{}
		saveAlarms()
		alarmMu.Unlock()
		jsonResp(w, map[string]any{"ok": true})
	default:
		http.NotFound(w, r)
	}
}

// ---------------- 云端闹钟网页控制台 ----------------

const devAlarmPageHTML = `<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>聊天兔 · 本机闹钟</title>
<style>
:root{--bg:#0f1220;--card:#1a1f35;--fg:#e8ecff;--sub:#8b93b5;--pri:#5b8cff;--ok:#37d67a;--err:#ff5c5c}
*{box-sizing:border-box}
body{margin:0;padding:16px;background:var(--bg);color:var(--fg);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Microsoft YaHei",sans-serif}
h1{font-size:20px;margin:0 0 4px}
.sub{color:var(--sub);font-size:13px;margin-bottom:16px}
.card{background:var(--card);border-radius:14px;padding:14px;margin-bottom:14px}
.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
input[type=time]{background:#0d1020;color:var(--fg);border:1px solid #2a3150;border-radius:10px;padding:10px 12px;font-size:22px;font-weight:600}
input[type=text],select{background:#0d1020;color:var(--fg);border:1px solid #2a3150;border-radius:10px;padding:10px 12px;font-size:15px}
button{cursor:pointer;border:0;border-radius:10px;padding:10px 16px;font-size:15px;font-weight:600}
.primary{background:var(--pri);color:#fff}
.ghost{background:#252b45;color:var(--fg)}
.danger{background:#3a1f27;color:#ff8a8a}
.item{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:12px 0;border-bottom:1px solid #232a44}
.item:last-child{border-bottom:0}
.time{font-size:26px;font-weight:700}
.meta{color:var(--sub);font-size:12px;margin-top:2px}
.acts{display:flex;gap:8px}
.mini{padding:8px 12px;font-size:13px}
.empty{color:var(--sub);text-align:center;padding:22px 0}
#toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:#2a3150;color:#fff;padding:10px 18px;border-radius:999px;font-size:14px;opacity:0;transition:.25s;pointer-events:none}
#toast.show{opacity:1}
</style></head><body>
<h1>聊天兔 · 本机闹钟</h1>
<div class="sub">直接写入玩具本机闹钟（走 MQTT 真实协议，无需刷机）</div>

<div class="card">
  <div class="row">
    <input type="time" id="t" value="07:30">
    <input type="text" id="n" placeholder="名称(可空)" value="闹钟">
    <select id="w">
      <option value="0">每天</option>
      <option value="1">周一</option>
      <option value="2">周二</option>
      <option value="3">周三</option>
      <option value="4">周四</option>
      <option value="5">周五</option>
      <option value="6">周六</option>
      <option value="7">周日</option>
    </select>
    <button class="primary" id="add">添加</button>
  </div>
</div>

<div class="card" id="list"><div class="empty">加载中…</div></div>

<div class="card">
  <div class="row" style="margin-bottom:10px">
    <button class="primary" id="ring">立刻响铃</button>
    <span class="sub" style="margin:0">响铃时长</span>
    <select id="secs">
      <option value="10">10 秒</option>
      <option value="20" selected>20 秒</option>
      <option value="30">30 秒</option>
      <option value="60">60 秒</option>
    </select>
  </div>
  <div class="row">
    <span class="sub" style="margin:0">铃声文件</span>
    <select id="files"></select>
  </div>
  <div class="row" style="margin-top:10px">
    <input type="file" id="up" accept="audio/*" style="color:var(--sub);font-size:13px">
    <button class="ghost" id="upload">上传为新铃声</button>
  </div>
</div>

<div class="card">
  <div class="row">
    <button class="ghost" id="push">重新同步到设备</button>
    <button class="danger" id="clear">清空全部</button>
  </div>
</div>

<div id="toast"></div>
<script>
const $=s=>document.querySelector(s);
function toast(m){const t=$('#toast');t.textContent=m;t.classList.add('show');setTimeout(()=>t.classList.remove('show'),1600);}
async function api(p,b){const r=await fetch(p,{method:b?'POST':'GET',headers:{'Content-Type':'application/json'},body:b?JSON.stringify(b):undefined});return r.json();}
function wkName(wk){
  if(wk===0)return '不重复';
  if(wk>=63)return '每天';
  const n=['周一','周二','周三','周四','周五','周六','周日'];
  let o=[];for(let i=0;i<7;i++)if(wk&(1<<i))o.push(n[i]);
  return o.join(' ')||'不重复';
}
async function loadList(){
  const r=await api('/api/dev/list');
  const box=$('#list');
  const L=r.list||[];
  if(!L.length){box.innerHTML='<div class="empty">暂无闹钟</div>';return;}
  box.innerHTML='';
  L.forEach((a,i)=>{
    const d=document.createElement('div');d.className='item';
    const p2=String(a.th).padStart(2,'0')+':'+String(a.tm).padStart(2,'0');
    d.innerHTML='<div><div class="time">'+p2+'</div><div class="meta">'+(a.na||'闹钟')+' · '+wkName(a.wk)+' · '+(a.s?'开启':'关闭')+'</div></div>';
    const acts=document.createElement('div');acts.className='acts';
    const b1=document.createElement('button');b1.className='ghost mini';b1.textContent=a.s?'关闭':'开启';
    b1.onclick=async()=>{await api('/api/dev/toggle',{index:i});toast('已切换');loadList();};
    const b2=document.createElement('button');b2.className='danger mini';b2.textContent='删除';
    b2.onclick=async()=>{if(confirm('删除 '+p2+'？')){await api('/api/dev/del',{index:i});toast('已删除');loadList();}};
    acts.appendChild(b1);acts.appendChild(b2);
    d.appendChild(acts);box.appendChild(d);
  });
}
$('#add').onclick=async()=>{
  const t=$('#t').value||'07:30';
  const [h,m]=t.split(':').map(Number);
  await api('/api/dev/add',{hour:h,minute:m,name:$('#n').value||'闹钟',enabled:true,weekday:Number($('#w').value)});
  toast('已添加并同步到设备');loadList();
};
$('#push').onclick=async()=>{await api('/api/dev/push',{});toast('已同步');};
$('#clear').onclick=async()=>{if(confirm('清空设备上所有本机闹钟？')){await api('/api/dev/clear',{});toast('已清空');loadList();}};
$('#ring').onclick=async()=>{await api('/api/ringtest',{});toast('设备响铃中');};
$('#secs').onchange=async()=>{await api('/api/ring',{seconds:Number($('#secs').value)});toast('响铃时长已设置');};
$('#files').onchange=async()=>{await api('/api/ring',{file:$('#files').value});toast('铃声已切换');};
$('#upload').onclick=async()=>{
  const f=$('#up').files[0];
  if(!f){toast('先选择音频文件');return;}
  const buf=await f.arrayBuffer();
  const r=await fetch('/api/ringtone/upload?name='+encodeURIComponent(f.name),{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:buf});
  const j=await r.json();
  toast(j.ok?'上传成功：'+j.file:'上传失败');
  loadFiles();
};
async function loadFiles(){
  const r=await api('/api/files');const sel=$('#files');sel.innerHTML='';
  (r.files||[]).forEach(n=>{const o=document.createElement('option');o.value=n;o.textContent=n;sel.appendChild(o);});
}
async function loadStatus(){
  const s=await api('/api/status');
  if(s.alarm_voice){$('#files').value=s.alarm_voice;}
  if(s.ring_seconds){$('#secs').value=String(s.ring_seconds);}
}
loadFiles().then(loadStatus);
loadList();
</script>
</body></html>`

const alarmPageHTML = `<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>聊天兔 · 闹钟管理</title>
<style>
:root{--bg:#0f1220;--card:#1a1f35;--fg:#e8ecff;--sub:#8b93b5;--pri:#5b8cff;--ok:#37d67a;--warn:#ffb020;--err:#ff5c5c}
*{box-sizing:border-box}
body{margin:0;padding:16px;background:var(--bg);color:var(--fg);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Microsoft YaHei",sans-serif}
h1{font-size:20px;margin:0 0 4px}
.sub{color:var(--sub);font-size:13px;margin-bottom:16px}
.card{background:var(--card);border-radius:14px;padding:14px;margin-bottom:14px;box-shadow:0 2px 10px rgba(0,0,0,.25)}
.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
input[type=time]{background:#0d1020;color:var(--fg);border:1px solid #2a3150;border-radius:10px;padding:10px 12px;font-size:22px;font-weight:600;letter-spacing:1px}
button{cursor:pointer;border:0;border-radius:10px;padding:10px 16px;font-size:15px;font-weight:600}
.primary{background:var(--pri);color:#fff}
.ghost{background:#252b45;color:var(--fg)}
.danger{background:#3a1f27;color:#ff8a8a}
.week{display:flex;gap:6px;flex-wrap:wrap;margin-top:10px}
.wd{width:38px;height:38px;border-radius:10px;background:#232a44;color:var(--sub);display:flex;align-items:center;justify-content:center;font-size:14px;user-select:none;cursor:pointer}
.wd.on{background:var(--pri);color:#fff}
.item{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:12px 0;border-bottom:1px solid #232a44}
.item:last-child{border-bottom:0}
.time{font-size:26px;font-weight:700;letter-spacing:1px}
.meta{color:var(--sub);font-size:12px;margin-top:2px}
.badge{display:inline-block;padding:2px 8px;border-radius:999px;font-size:11px;margin-left:6px}
.b-on{background:rgba(55,214,122,.15);color:var(--ok)}
.b-off{background:rgba(139,147,181,.15);color:var(--sub)}
.acts{display:flex;gap:8px}
.mini{padding:8px 12px;font-size:13px}
.empty{color:var(--sub);text-align:center;padding:22px 0;font-size:14px}
#toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:#2a3150;color:#fff;padding:10px 18px;border-radius:999px;font-size:14px;opacity:0;transition:.25s;pointer-events:none}
#toast.show{opacity:1}
.st{display:flex;gap:14px;font-size:13px;color:var(--sub)}
.st b{color:var(--fg)}
</style></head><body>
<h1>聊天兔 · 云端闹钟</h1>
<div class="sub">通过自有云端控制玩具闹钟（到点由路由器推送铃声）</div>

<div class="card">
  <div class="st"><span>设备：<b id="online">-</b></span><span>电量：<b id="bat">-</b></span><span>音量：<b id="vol">-</b></span></div>
</div>

<div class="card">
  <div class="row">
    <input type="time" id="time" value="07:00">
    <button class="primary" onclick="addAlarm()">添加闹钟</button>
    <button class="ghost" onclick="testRing()">试听铃声</button>
    <button class="ghost" onclick="stopRing()">停止</button>
  </div>
  <div class="week" id="week"></div>
</div>

<div class="card">
  <div class="row" style="justify-content:space-between"><b>铃声设置</b>
    <button class="ghost mini" onclick="applyRing()">保存</button>
  </div>
  <div class="row" style="margin-top:10px">
    <select id="ringFile" style="background:#0d1020;color:var(--fg);border:1px solid #2a3150;border-radius:10px;padding:10px;font-size:15px;flex:1"></select>
    <span style="color:var(--sub);font-size:13px">响铃</span>
    <input id="ringSec" type="number" min="0" max="300" value="20" style="width:74px;background:#0d1020;color:var(--fg);border:1px solid #2a3150;border-radius:10px;padding:10px;font-size:15px">
    <span style="color:var(--sub);font-size:13px">秒</span>
  </div>
</div>

<div class="card">
  <div class="row" style="justify-content:space-between">
    <b>闹钟列表</b>
    <button class="danger mini" onclick="clearAll()">清空全部</button>
  </div>
  <div id="list"><div class="empty">加载中…</div></div>
</div>

<div id="toast"></div>

<script>
const WD=['一','二','三','四','五','六','日'];
let selected=new Set([1,2,3,4,5,6,7]);
function renderWeek(){
  const box=document.getElementById('week'); box.innerHTML='';
  WD.forEach((name,i)=>{
    const d=document.createElement('div');
    d.className='wd'+(selected.has(i+1)?' on':'');
    d.textContent=name;
    d.onclick=()=>{ selected.has(i+1)?selected.delete(i+1):selected.add(i+1); renderWeek(); };
    box.appendChild(d);
  });
}
function toast(msg){
  const t=document.getElementById('toast'); t.textContent=msg; t.classList.add('show');
  clearTimeout(t._h); t._h=setTimeout(()=>t.classList.remove('show'),1800);
}
async function api(path,body){
  const opt=body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{};
  const r=await fetch(path,opt); return r.json();
}
async function loadStatus(){
  try{
    const s=await api('/api/status');
    document.getElementById('online').textContent=s.device_online?'在线':'离线';
    document.getElementById('online').style.color=s.device_online?'var(--ok)':'var(--err)';
    if(s.status){ document.getElementById('bat').textContent=s.status.battery+'%'; document.getElementById('vol').textContent=s.status.vol; }
  }catch(e){}
}
async function loadList(){
  try{
    const d=await api('/api/alarms');
    const box=document.getElementById('list');
    if(!d.alarms||d.alarms.length===0){ box.innerHTML='<div class="empty">还没有闹钟，先添加一个吧</div>'; return; }
    box.innerHTML='';
    d.alarms.sort((a,b)=>(a.time||'').localeCompare(b.time||''));
    d.alarms.forEach(a=>{
      const el=document.createElement('div'); el.className='item';
      const rep=(a.repeat&&a.repeat.length)?('周'+a.repeat.map(r=>WD[(r+6)%7]).join('、')):'一次性';
      el.innerHTML='<div><div class="time">'+a.time+'</div>'+
        '<div class="meta">'+rep+'<span class="badge '+(a.enabled?'b-on':'b-off')+'">'+(a.enabled?'开':'关')+'</span></div></div>';
      const acts=document.createElement('div'); acts.className='acts';
      const b1=document.createElement('button'); b1.className='ghost mini'; b1.textContent=a.enabled?'关闭':'开启';
      b1.onclick=async()=>{ await api('/api/alarms/toggle',{id:a.id,enabled:!a.enabled}); toast(a.enabled?'已关闭':'已开启'); loadList(); };
      const b2=document.createElement('button'); b2.className='ghost mini'; b2.textContent='改时间';
      b2.onclick=async()=>{ const t=prompt('新时间 HH:MM',a.time); if(t){ await api('/api/alarms/update',{id:a.id,time:t}); toast('已修改'); loadList(); } };
      const b3=document.createElement('button'); b3.className='danger mini'; b3.textContent='删除';
      b3.onclick=async()=>{ if(confirm('删除 '+a.time+' 闹钟？')){ await api('/api/alarms/remove',{id:a.id}); toast('已删除'); loadList(); } };
      acts.append(b1,b2,b3); el.appendChild(acts); box.appendChild(el);
    });
  }catch(e){ document.getElementById('list').innerHTML='<div class="empty">加载失败</div>'; }
}
async function addAlarm(){
  const t=document.getElementById('time').value;
  if(!t){ toast('请选择时间'); return; }
  const rep=[...selected].sort();
  await api('/api/alarms/add',{time:t,repeat:rep});
  toast('已添加 '+t); loadList();
}
async function clearAll(){
  if(!confirm('清空全部闹钟？')) return;
  await api('/api/alarms/clear',{}); toast('已清空'); loadList();
}
async function testRing(){
  await api('/api/play',{file:'tone.mp3'}); toast('已推送试听铃声');
}
async function stopRing(){
  await api('/api/stop',{}); toast('已发送停止');
}
async function loadFiles(){
  try{
    const d=await api('/api/files');
    const sel=document.getElementById('ringFile'); sel.innerHTML='';
    (d.files||[]).forEach(f=>{ const o=document.createElement('option'); o.value=f; o.textContent=f; sel.appendChild(o); });
    const st=await api('/api/status');
    if(st.alarm_voice){ sel.value=st.alarm_voice; }
  }catch(e){}
}
async function applyRing(){
  const file=document.getElementById('ringFile').value;
  const sec=parseInt(document.getElementById('ringSec').value||'20',10);
  await api('/api/ring',{file:file,seconds:sec});
  toast('铃声已保存');
}
renderWeek(); loadStatus(); loadList(); loadFiles();
setInterval(loadStatus,5000); setInterval(loadList,10000);
</script>
</body></html>`

// ---------------- ctl ----------------

func apiBase() string { return "http://127.0.0.1:" + strconv.Itoa(cfg.APIPort) }

// apiURLs 返回 ctl 子命令要尝试的 API 地址：主端口 → 兼容端口。
func apiURLs() []string {
	base := []string{apiBase()}
	if cfg.APIPort != CompatAPIPort {
		base = append(base, "http://127.0.0.1:"+strconv.Itoa(CompatAPIPort))
	}
	return base
}

func apiPost(p string, payload any) {
	b, _ := json.Marshal(payload)
	var lastErr error
	for _, base := range apiURLs() {
		resp, err := http.Post(base+p, "application/json", strings.NewReader(string(b)))
		if err != nil {
			lastErr = err
			continue
		}
		defer resp.Body.Close()
		out, _ := io.ReadAll(resp.Body)
		fmt.Println(string(out))
		return
	}
	fmt.Println("失败:", lastErr)
	os.Exit(1)
}

func apiGet(p string) {
	var lastErr error
	for _, base := range apiURLs() {
		resp, err := http.Get(base + p)
		if err != nil {
			lastErr = err
			continue
		}
		defer resp.Body.Close()
		out, _ := io.ReadAll(resp.Body)
		fmt.Println(string(out))
		return
	}
	fmt.Println("失败:", lastErr)
	os.Exit(1)
}

// ---------------- main ----------------

// startSNTP 提供最小 SNTP 服务（UDP :123），让丢失 RTC 的设备能立刻完成时间同步。
func startSNTP() {
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 123})
	if err != nil {
		logf("[NTP] listen :123 失败: %v", err)
		return
	}
	sntpOK = true
	logf("[NTP] listening :123")
	buf := make([]byte, 512)
	for {
		n, raddr, err := conn.ReadFromUDP(buf)
		if err != nil || n < 48 {
			continue
		}
		// 标准 NTPv4 服务端应答：originate 必须回显客户端的 transmit 时间戳
		resp := make([]byte, 48)
		copy(resp[24:32], buf[40:48])                 // originate = client transmit
		resp[0] = 0x24                                // LI=0, VN=4, Mode=4（server）
		resp[1] = 1                                   // stratum
		resp[2] = 4                                   // poll
		resp[3] = 0xEC                                // precision = -20
		binary.BigEndian.PutUint32(resp[4:8], 0)      // root delay
		binary.BigEndian.PutUint32(resp[8:12], 0x100) // root dispersion
		copy(resp[12:16], []byte("LOCL"))
		// 标准 NTP：UTC 时间
		now := time.Now().UTC()
		sec := uint32(now.Unix() + 2208988800)
		frac := uint32(uint64(now.Nanosecond()) * (1 << 32) / 1000000000)
		binary.BigEndian.PutUint32(resp[16:20], sec) // reference
		binary.BigEndian.PutUint32(resp[20:24], frac)
		binary.BigEndian.PutUint32(resp[32:36], sec) // receive
		binary.BigEndian.PutUint32(resp[36:40], frac)
		binary.BigEndian.PutUint32(resp[40:44], sec) // transmit
		binary.BigEndian.PutUint32(resp[44:48], frac)
		_, _ = conn.WriteToUDP(resp, raddr)
		logf("[NTP] 应答 %s（客户端时间字段 %x）", raddr.String(), buf[40:48])
	}
}

// ---------------- 控制 API 监听 ----------------

// CompatAPIPort 旧版 API 端口：为旧客户端/旧 App 保留兼容监听。
// kongke 插件的"劫持面板"用 8898，所以它可能被占用 —— 占用时只记日志、不退出。
const CompatAPIPort = 8898

var (
	apiPortsMu     sync.Mutex
	apiListenPorts []int
)

func apiPortsSnapshot() []int {
	apiPortsMu.Lock()
	defer apiPortsMu.Unlock()
	out := make([]int, len(apiListenPorts))
	copy(out, apiListenPorts)
	return out
}

// startAPIListeners 监听主端口（必须）与旧端口（尽力而为），共用同一个 mux。
// kongkeInstalled 检测控客插件是否装在本机。
// 老版 kongke（2.x 早期）的"域名劫持面板"就监听 8898，和我们的兼容端口正面冲突：
// 谁先抢到 8898，另一个就永远起不来。既然 8898 只是给我们旧 App 兜底的，
// 装了 kongke 就完全让给它，别再抢。
func kongkeInstalled() bool {
	cands := []string{
		"/koolshare/kongke",
		"/koolshare/kongke/scripts/kongke_ctl.sh",
		"/koolshare/scripts/kongke_ctl.sh",
		"/koolshare/scripts/kkctl.sh",
	}
	for _, p := range cands {
		if _, err := os.Stat(p); err == nil {
			return true
		}
	}
	return false
}

func startAPIListeners(mux *http.ServeMux) {
	ports := []int{cfg.APIPort}
	compat := CompatAPIPort
	if compat == cfg.APIPort {
		compat = 0
	}
	if compat > 0 && kongkeInstalled() {
		logf("[api] 检测到 kongke 插件：跳过 %d 兼容监听（让给它的劫持面板，避免抢端口）", compat)
		compat = 0
	}
	if compat > 0 {
		ports = append(ports, compat)
	}
	for _, port := range ports {
		ln, err := net.Listen("tcp", ":"+strconv.Itoa(port))
		if err != nil {
			if port == cfg.APIPort {
				logf("[API] %d 监听失败: %v（其它服务继续运行）", port, err)
			} else {
				logf("[api] %d 被占用（kongke 劫持面板），跳过兼容监听", port)
			}
			continue
		}
		apiPortsMu.Lock()
		apiListenPorts = append(apiListenPorts, port)
		apiPortsMu.Unlock()
		logf("[API] listening :%d", port)
		go func(l net.Listener, p int) {
			if err := http.Serve(l, mux); err != nil {
				logf("[API] :%d 服务结束: %v", p, err)
			}
		}(ln, port)
	}
}

func main() {
	log.SetFlags(0)
	flag.Parse()
	loadConfig()
	args := flag.Args()
	cmd := "serve"
	if len(args) > 0 {
		cmd = args[0]
	}
	switch cmd {
	case "version":
		fmt.Println("chatrabbit", Version)
	case "serve":
		f, err := os.OpenFile(filepath.Join(cfg.LogDir, time.Now().Format("20060102-150405")+".log"),
			os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
		if err == nil {
			logFile = f
		}
		loadAlarms()
		initDevices() // 装载/迁移多设备表（旧配置自动生成默认设备）
		logf("chatrabbit %s start: ip=%s http=:%d mqtt=:%d/%d api=:%d media=%s gap=%ds debug=%v devices=%d default=%s",
			Version, cfg.LanIP, cfg.HTTPPort, cfg.MQTTPort, cfg.ExtraPort, cfg.APIPort, cfg.MediaDir, cfg.GapSec, *debugOn,
			len(allDevices()), defaultDeviceID())
		startMQTT()
		go startSNTP()
		go startAlarmScheduler()
		go startLogAutoTrim()
		go startHealthWatch()
		for _, d := range allDevices() {
			go startOnceJanitorFor(d)
		}
		// 控制 API：主端口 + 旧端口兼容监听（共用同一个 mux）
		apiMux := http.NewServeMux()
		apiMux.HandleFunc("/", apiHandler)
		startAPIListeners(apiMux)
		mux := http.NewServeMux()
		mux.HandleFunc("/", httpHandler)
		logf("[HTTP] listening :%d", cfg.HTTPPort)
		if err := http.ListenAndServe(":"+strconv.Itoa(cfg.HTTPPort), mux); err != nil {
			logf("[HTTP] 失败: %v", err)
			os.Exit(1)
		}
	case "ctl":
		if len(args) < 2 {
			fmt.Println("用法: chatrabbit ctl <play|stop|op|status|list> [参数]")
			os.Exit(1)
		}
		switch args[1] {
		case "play":
			if len(args) < 3 {
				fmt.Println("用法: ctl play <文件名>")
				os.Exit(1)
			}
			apiPost("/api/play", map[string]string{"file": args[2]})
		case "stop":
			apiPost("/api/stop", map[string]string{})
		case "op":
			n, _ := strconv.Atoi(args[2])
			apiPost("/api/cmd", map[string]int{"operate": n})
		case "status":
			apiGet("/api/status")
		case "list":
			apiGet("/api/list")
		default:
			fmt.Println("未知子命令:", args[1])
			os.Exit(1)
		}
	case "dns-fix":
		fixDNS()
	default:
		fmt.Println("用法: chatrabbit [serve|ctl|version|dns-fix]")
		os.Exit(1)
	}
}

func fixDNS() {
	conf := "/jffs/configs/dnsmasq.conf.add"
	// 解析到"假云端地址"（TuringIP），而不是路由器 LAN IP。
	// 这样 iptables 只需按目的地址 198.18.0.1 做重定向，任何设备都通用，
	// 不再需要为每台玩具单独写 MAC 规则（多玩具免配置）。
	target := cfg.TuringIP
	if target == "" {
		target = "198.18.0.1"
	}
	line := "address=/turingapi.com/" + target
	b, _ := os.ReadFile(conf)
	old := string(b)
	// 把**所有** turingapi.com 相关的 address 行（含历史遗留的
	// iot-ai./ws-api./mqtt.ai. 子域，它们以前指向 LANIP）合并成一条
	// address=/turingapi.com/<TuringIP>；否则子域仍解析到路由器 IP，
	// 按目的地址的重定向就覆盖不到，设备会连到路由器的 80/8883 失败。
	var out []string
	wrote := false
	for _, l := range strings.Split(old, "\n") {
		t := strings.TrimSpace(l)
		if strings.HasPrefix(t, "address=/") && strings.Contains(t, "turingapi.com") {
			if !wrote {
				out = append(out, line)
				wrote = true
			}
			continue
		}
		out = append(out, l)
	}
	if !wrote {
		out = append(out, line)
	}
	s := strings.Join(out, "\n")
	changed := s != old
	if !changed {
		// 幂等：配置没变就绝不重启 dnsmasq。
		// dnsmasq 重启会造成秒级 DNS 中断，IoT 设备（玩具/插座）会掉线重连——
		// 2026-10-28 上午的整段 WiFi 反复断连就是这个原因（当天频繁部署触发）。
		fmt.Println("dnsmasq 配置已是最新，跳过重启:", line)
		return
	}
	if err := os.WriteFile(conf, []byte(s), 0644); err != nil {
		fmt.Println("写 dnsmasq 配置失败:", err)
		os.Exit(1)
	}
	fmt.Println("已写入", conf, "->", line)
	_ = exec.Command("sh", "-c", "service restart_dnsmasq").Run()
	fmt.Println("已重启 dnsmasq")
}

// ---------------- 健康看门狗（夜间排障用） ----------------
// 每分钟采一次：DNS 自检（turingapi.com 是否解析到本机）+ 每台设备在线/MQTT 连接/重连翻转次数。
// 写一行 [health] 到 serve.log，并在状态变化时额外记一条 [health!]。
// 目的：IoT 反复掉线时，事后能直接判断是"DNS/网络面挂了"还是"我们的服务挂了"。

type devHealthSample struct {
	ID       string `json:"id"`
	Online   bool   `json:"online"`
	Conns    int    `json:"conns"`
	IP       string `json:"ip"`
	Alarms   int    `json:"alarms"`
	Connects int    `json:"connects"`
	Flips    int    `json:"flips"`
}

type healthSample struct {
	At       string            `json:"at"`
	DNSOK    bool              `json:"dns_ok"`
	DNSMs    int64             `json:"dns_ms"`
	DNSAddr  string            `json:"dns_addr"`
	Devices  []devHealthSample `json:"devices"`
	APIPorts []int             `json:"api_ports"`
}

var (
	healthStart      = time.Now()
	healthMu         sync.Mutex
	healthHist       []healthSample
	healthPrevOnline = map[string]bool{}
	healthFlips      = map[string]int{}
	healthConnects   = map[string]int{}
	healthDNSFail    int
	healthLastDNSOK  = true
	healthHaveSample bool
)

func healthNoteConnect(id string) {
	healthMu.Lock()
	healthConnects[id]++
	healthMu.Unlock()
}

func healthDNSFailTotal() int {
	healthMu.Lock()
	defer healthMu.Unlock()
	return healthDNSFail
}

func healthConnectTotals() map[string]int {
	healthMu.Lock()
	defer healthMu.Unlock()
	out := make(map[string]int, len(healthConnects))
	for k, v := range healthConnects {
		out[k] = v
	}
	return out
}

func healthSnapshot(n int) []healthSample {
	healthMu.Lock()
	defer healthMu.Unlock()
	if n > len(healthHist) {
		n = len(healthHist)
	}
	out := make([]healthSample, n)
	copy(out, healthHist[len(healthHist)-n:])
	return out
}

func dnsSelfCheck() (bool, int64, string) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	t0 := time.Now()
	// 必须查本机 dnsmasq（127.0.0.1:53），这才是 IoT 设备实际拿到的解析结果；
	// 用 net.DefaultResolver 会走 /etc/resolv.conf 里的 WAN DNS，测不到劫持是否生效。
	r := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			d := net.Dialer{Timeout: 1500 * time.Millisecond}
			return d.DialContext(ctx, "udp", "127.0.0.1:53")
		},
	}
	addrs, err := r.LookupHost(ctx, "turingapi.com")
	ms := time.Since(t0).Milliseconds()
	if err != nil || len(addrs) == 0 {
		return false, ms, ""
	}
	joined := strings.Join(addrs, ",")
	ok := false
	for _, a := range addrs {
		if a == cfg.LanIP {
			ok = true
		}
	}
	return ok, ms, joined
}

func healthSampleOnce() healthSample {
	dnsOK, dnsMs, dnsAddr := dnsSelfCheck()
	s := healthSample{
		At: time.Now().Format("2006-01-02 15:04:05"), DNSOK: dnsOK, DNSMs: dnsMs, DNSAddr: dnsAddr,
		APIPorts: apiPortsSnapshot(),
	}
	healthMu.Lock()
	if !dnsOK {
		healthDNSFail++
	}
	for _, d := range allDevices() {
		online := d.clientCount() > 0
		n := d.clientCount()
		prev, seen := healthPrevOnline[d.ID]
		if seen && prev != online {
			healthFlips[d.ID]++
			healthPrevOnline[d.ID] = online
			healthMu.Unlock()
			logf("[health!] 设备 %s 在线状态变化: %v -> %v (累计翻转 %d)", d.ID, prev, online, healthFlips[d.ID])
			healthMu.Lock()
		}
		healthPrevOnline[d.ID] = online
		s.Devices = append(s.Devices, devHealthSample{
			ID: d.ID, Online: online, Conns: n, IP: d.deviceIP(), Alarms: d.alarmCount(),
			Connects: healthConnects[d.ID], Flips: healthFlips[d.ID],
		})
	}
	healthHist = append(healthHist, s)
	if len(healthHist) > 240 { // 保留 4 小时
		healthHist = healthHist[len(healthHist)-240:]
	}
	healthMu.Unlock()
	return s
}

func startHealthWatch() {
	// 启动 15 秒后先采一次，之后每 60 秒一次
	time.Sleep(15 * time.Second)
	for {
		s := healthSampleOnce()
		parts := make([]string, 0, len(s.Devices))
		for _, dv := range s.Devices {
			state := "off"
			if dv.Online {
				state = "on"
			}
			parts = append(parts, fmt.Sprintf("%s:%s(%d,flips=%d,conn=%d)", dv.ID, state, dv.Conns, dv.Flips, dv.Connects))
		}
		dnsFlag := "ok"
		if !s.DNSOK {
			dnsFlag = "FAIL"
		}
		healthMu.Lock()
		changed := healthHaveSample && s.DNSOK != healthLastDNSOK
		healthLastDNSOK = s.DNSOK
		healthHaveSample = true
		healthMu.Unlock()
		logf("[health] dns=%s(%s,%dms) api=%v up=%s devs=[%s]",
			dnsFlag, s.DNSAddr, s.DNSMs, s.APIPorts, time.Since(healthStart).Truncate(time.Second), strings.Join(parts, " "))
		if changed {
			logf("[health!] DNS 状态变化 -> %s", dnsFlag)
		}
		time.Sleep(60 * time.Second)
	}
}
