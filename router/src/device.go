package main

// 多设备支持（v1.5.0）：把原本散在全局变量里的 per-device 状态收敛到 Device。
//
// 单设备向后兼容：旧 config.json（只有 api_key/device_id/openid）启动后自动生成
// 一台默认设备，所有既有接口返回结构不变（只新增字段）。

import (
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// DeviceConfig 是 config.json 里的设备条目（可持久化部分）。
type DeviceConfig struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	APIKey     string `json:"api_key"`
	OpenID     string `json:"openid"`
	Topic      string `json:"topic,omitempty"`
	AlarmVoice string `json:"alarm_voice,omitempty"`
	MusicFile  string `json:"music_file,omitempty"`
}

// Gate 每台设备独立的播放闸门状态。
type Gate struct {
	Win       time.Time
	Cnt       int
	StopAt    time.Time
	MediaCnt  int64
	LastMedia time.Time
	LastAudio time.Time
}

// Device 一台玩具的运行时状态。
type Device struct {
	ID         string
	Name       string
	APIKey     string
	OpenID     string
	Topic      string
	IP         string
	Online     bool
	AlarmVoice string
	MusicFile  string

	mu          sync.Mutex // 保护下面的时间/在线字段
	LastConnect time.Time

	AlarmMu sync.Mutex
	Alarms  []DevAlarm

	StMu sync.Mutex
	Stat Status

	ConnMu sync.Mutex
	Conns  map[*mqttConn]bool

	WSMu    sync.Mutex
	WSConns map[*wsConn]bool

	GateMu sync.Mutex
	Gate   Gate

	MediaSeq uint32
}

func (d *Device) topicName() string {
	if d.Topic != "" {
		return d.Topic
	}
	return "iot/" + d.APIKey + "/" + d.ID
}

func (d *Device) clientID() string {
	return "GID_device@@@" + d.APIKey + "@" + d.ID
}

func (d *Device) displayName() string {
	if d.Name != "" {
		return d.Name
	}
	return d.ID
}

// ---------------- 设备注册表 ----------------

var (
	devMu      sync.RWMutex
	devTable   = map[string]*Device{}
	devOrder   []string
	devDefault string
)

func newDevice(c DeviceConfig) *Device {
	return &Device{
		ID:         c.ID,
		Name:       c.Name,
		APIKey:     c.APIKey,
		OpenID:     c.OpenID,
		Topic:      c.Topic,
		AlarmVoice: c.AlarmVoice,
		MusicFile:  c.MusicFile,
		Conns:      map[*mqttConn]bool{},
		WSConns:    map[*wsConn]bool{},
		Stat:       Status{Vol: -1, Battery: -1},
	}
}

func devByKey(key string) *Device {
	key = strings.TrimSpace(key)
	if key == "" {
		return nil
	}
	devMu.RLock()
	defer devMu.RUnlock()
	if d, ok := devTable[key]; ok {
		return d
	}
	// 宽松匹配：忽略大小写 / 允许只给 APIKey
	lk := strings.ToLower(key)
	for id, d := range devTable {
		if strings.ToLower(id) == lk {
			return d
		}
	}
	for _, d := range devTable {
		if d.APIKey != "" && strings.EqualFold(d.APIKey, key) {
			return d
		}
	}
	return nil
}

// defaultDevice 返回默认设备；没有设备时返回 nil。
func defaultDevice() *Device {
	devMu.RLock()
	id := devDefault
	devMu.RUnlock()
	if d := devByKey(id); d != nil {
		return d
	}
	list := allDevices()
	if len(list) > 0 {
		return list[0]
	}
	return nil
}

func allDevices() []*Device {
	devMu.RLock()
	defer devMu.RUnlock()
	out := make([]*Device, 0, len(devOrder))
	for _, id := range devOrder {
		if d, ok := devTable[id]; ok {
			out = append(out, d)
		}
	}
	return out
}

// upsertDevice 注册/更新设备（MQTT CONNECT 时自动调用）。
func upsertDevice(id, apiKey, openid, name string) *Device {
	id = strings.TrimSpace(id)
	if id == "" {
		return nil
	}
	devMu.Lock()
	d, ok := devTable[id]
	if !ok {
		d = newDevice(DeviceConfig{ID: id})
		devTable[id] = d
		devOrder = append(devOrder, id)
	}
	if apiKey != "" {
		d.APIKey = apiKey
	}
	if openid != "" {
		d.OpenID = openid
	}
	if name != "" {
		d.Name = name
	}
	if devDefault == "" {
		devDefault = id
	}
	devMu.Unlock()
	return d
}

func addDevice(id, name, apiKey, openid string) (*Device, error) {
	id = strings.TrimSpace(id)
	if id == "" {
		return nil, fmt.Errorf("id 不能为空")
	}
	devMu.Lock()
	if _, exists := devTable[id]; exists {
		devMu.Unlock()
		return nil, fmt.Errorf("设备已存在: %s", id)
	}
	d := newDevice(DeviceConfig{ID: id, Name: name, APIKey: apiKey, OpenID: openid})
	devTable[id] = d
	devOrder = append(devOrder, id)
	if devDefault == "" {
		devDefault = id
	}
	devMu.Unlock()
	_ = os.MkdirAll(devAlarmDir(), 0755)
	d.saveAlarms()
	persistDevices()
	logf("[dev] 新增设备 id=%s name=%q apiKey=%s", id, name, apiKey)
	return d, nil
}

func delDevice(id string) error {
	id = strings.TrimSpace(id)
	devMu.Lock()
	if _, ok := devTable[id]; !ok {
		devMu.Unlock()
		return fmt.Errorf("设备不存在: %s", id)
	}
	if len(devOrder) <= 1 {
		devMu.Unlock()
		return fmt.Errorf("至少保留一台设备")
	}
	delete(devTable, id)
	kept := make([]string, 0, len(devOrder)-1)
	for _, x := range devOrder {
		if x != id {
			kept = append(kept, x)
		}
	}
	devOrder = kept
	if devDefault == id {
		devDefault = kept[0]
	}
	devMu.Unlock()
	_ = os.Remove(devAlarmPath(id))
	persistDevices()
	logf("[dev] 删除设备 id=%s", id)
	return nil
}

func setDefaultDevice(id string) error {
	if devByKey(id) == nil {
		return fmt.Errorf("设备不存在: %s", id)
	}
	devMu.Lock()
	devDefault = id
	devMu.Unlock()
	persistDevices()
	logf("[dev] 默认设备 -> %s", id)
	return nil
}

func defaultDeviceID() string {
	devMu.RLock()
	defer devMu.RUnlock()
	return devDefault
}

// ---------------- 请求归属 ----------------

// deviceByIP 按已记录的设备 IP 反查（设备 HTTP 请求没有 dev 参数时用）。
func deviceByIP(ip string) *Device {
	if ip == "" {
		return nil
	}
	devMu.RLock()
	defer devMu.RUnlock()
	for _, d := range devTable {
		if d.IP != "" && d.IP == ip {
			return d
		}
	}
	return nil
}

func ipOf(remoteAddr string) string {
	if host, _, err := net.SplitHostPort(remoteAddr); err == nil {
		return host
	}
	return remoteAddr
}

// deviceFromRequest 用户侧（控制 API / 页面）归属：
// ?dev= → X-Device-Id → 源 IP → 默认设备。
func deviceFromRequest(r *http.Request) *Device {
	if r == nil {
		return defaultDevice()
	}
	if v := strings.TrimSpace(r.URL.Query().Get("dev")); v != "" {
		if d := devByKey(v); d != nil {
			return d
		}
	}
	if v := strings.TrimSpace(r.Header.Get("X-Device-Id")); v != "" {
		if d := devByKey(v); d != nil {
			return d
		}
	}
	if d := deviceByIP(ipOf(r.RemoteAddr)); d != nil {
		return d
	}
	return defaultDevice()
}

// deviceFromDeviceRequest 设备侧（假图灵云）归属：
// 源 IP 已登记 → 该设备；否则按源 IP 的 MAC **自动登记一台新设备**
// （多玩具关键：不能把新玩具的请求都算到默认设备头上，否则两台玩具
//   会拿到同一个 apiKey/deviceId，MQTT 上互相顶下线、后台只剩一台）。
func deviceFromDeviceRequest(r *http.Request) *Device {
	if r == nil {
		return defaultDevice()
	}
	ip := ipOf(r.RemoteAddr)
	if d := deviceByIP(ip); d != nil {
		return d
	}
	if v := strings.TrimSpace(r.URL.Query().Get("dev")); v != "" {
		if d := devByKey(v); d != nil {
			return d
		}
	}
	if d := ensureDeviceForIP(ip); d != nil {
		return d
	}
	return defaultDevice()
}

// macOfIP 从 /proc/net/arp 读取某 IP 的 MAC（Linux 路由器上一定有）。
func macOfIP(ip string) string {
	if ip == "" {
		return ""
	}
	b, err := os.ReadFile("/proc/net/arp")
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(b), "\n") {
		f := strings.Fields(line)
		if len(f) >= 4 && f[0] == ip {
			m := strings.ToUpper(f[3])
			if m != "" && m != "00:00:00:00:00:00" {
				return m
			}
		}
	}
	return ""
}

// ensureDeviceForIP 保证"这个 IP 对应的玩具"在设备表里有独立身份：
// deviceID = AI91 + MAC(去冒号)，apiKey = md5(deviceID)。
// 这样每台玩具的 MQTT clientId/topic 都不同，后台/页面/App 才能列出多台。
func ensureDeviceForIP(ip string) *Device {
	if ip == "" {
		return nil
	}
	mac := macOfIP(ip)
	if mac == "" {
		return nil
	}

	id := "AI91" + strings.ReplaceAll(mac, ":", "")
	devMu.Lock()
	existing := devTable[id]
	devMu.Unlock()
	if existing != nil {
		existing.IP = ip
		return existing
	}
	sum := md5.Sum([]byte(id))
	apiKey := hex.EncodeToString(sum[:])
	name := "玩具" + id[len(id)-4:]
	d, err := addDevice(id, name, apiKey, "")
	if err != nil {
		return devByKey(id)
	}
	d.IP = ip
	logf("[dev] 自动登记设备 id=%s ip=%s mac=%s（按 MAC 独立身份，无需手工添加）", id, ip, mac)
	return d
}

// parseMQTTClientID 解析 "GID_device@@@<apiKey>@<deviceId>"。
func parseMQTTClientID(cid string) (apiKey, deviceID string) {
	i := strings.Index(cid, "@@@")
	if i < 0 {
		return "", ""
	}
	rest := cid[i+3:]
	j := strings.Index(rest, "@")
	if j < 0 {
		return rest, ""
	}
	return rest[:j], rest[j+1:]
}

// ---------------- 持久化 ----------------

func devAlarmDir() string { return filepath.Join(filepath.Dir(*cfgPath), "dev_alarms") }

func devAlarmPath(id string) string {
	safe := strings.Map(func(r rune) rune {
		if r == '/' || r == '\\' || r == ':' || r < 32 {
			return '_'
		}
		return r
	}, id)
	if safe == "" {
		safe = "default"
	}
	return filepath.Join(devAlarmDir(), safe+".json")
}

// legacyDevAlarmsPath 旧版单设备文件位置。
func legacyDevAlarmsPath() string { return filepath.Join(filepath.Dir(*cfgPath), "dev_alarms.json") }

// persistDevices 把注册表写回 config.json 的 devices / default_device。
func persistDevices() {
	list := allDevices()
	out := make([]DeviceConfig, 0, len(list))
	for _, d := range list {
		out = append(out, DeviceConfig{
			ID: d.ID, Name: d.Name, APIKey: d.APIKey, OpenID: d.OpenID,
			Topic: d.Topic, AlarmVoice: d.AlarmVoice, MusicFile: d.MusicFile,
		})
	}
	cfg.Devices = out
	cfg.DefaultDevice = defaultDeviceID()
	// 兼容字段：老客户端/脚本仍读顶层的 api_key/device_id/openid/alarm_voice/music_file
	if d := defaultDevice(); d != nil {
		cfg.APIKey = d.APIKey
		cfg.DeviceID = d.ID
		cfg.OpenID = d.OpenID
		cfg.AlarmVoice = d.AlarmVoice
		cfg.MusicFile = d.MusicFile
	}
	saveConfig()
}

// initDevices 从配置装载设备表；旧配置自动生成一台默认设备。
func initDevices() {
	_ = os.MkdirAll(devAlarmDir(), 0755)
	list := cfg.Devices
	if len(list) == 0 {
		// 旧 config：用顶层字段造一台默认设备
		id := strings.TrimSpace(cfg.DeviceID)
		if id == "" {
			id = "default"
		}
		list = []DeviceConfig{{
			ID: id, Name: "默认设备", APIKey: cfg.APIKey, OpenID: cfg.OpenID,
			AlarmVoice: cfg.AlarmVoice, MusicFile: cfg.MusicFile,
		}}
		logf("[dev] 旧配置迁移：自动生成默认设备 id=%s", id)
	}
	devMu.Lock()
	devTable = map[string]*Device{}
	devOrder = nil
	for _, c := range list {
		if strings.TrimSpace(c.ID) == "" {
			continue
		}
		if _, dup := devTable[c.ID]; dup {
			continue
		}
		devTable[c.ID] = newDevice(c)
		devOrder = append(devOrder, c.ID)
	}
	if len(devOrder) == 0 {
		d := newDevice(DeviceConfig{ID: "default", Name: "默认设备"})
		devTable["default"] = d
		devOrder = []string{"default"}
	}
	devDefault = cfg.DefaultDevice
	if _, ok := devTable[devDefault]; !ok {
		devDefault = devOrder[0]
	}
	devMu.Unlock()

	// 装载闹钟表 + 旧数据迁移
	for _, d := range allDevices() {
		d.loadAlarms()
	}
	migrateLegacyDevAlarms()
	persistDevices()
}

// migrateLegacyDevAlarms 把旧的 dev_alarms.json 迁移到默认设备，并保留备份。
func migrateLegacyDevAlarms() {
	old := legacyDevAlarmsPath()
	b, err := os.ReadFile(old)
	if err != nil {
		return
	}
	def := defaultDevice()
	if def == nil {
		return
	}
	target := devAlarmPath(def.ID)
	if _, err := os.Stat(target); err == nil {
		return // 已有新文件，不动旧文件
	}
	var list []DevAlarm
	if err := json.Unmarshal(b, &list); err != nil {
		logf("[dev] 旧 dev_alarms.json 解析失败，跳过迁移: %v", err)
		return
	}
	def.AlarmMu.Lock()
	def.Alarms = list
	def.saveAlarmsLocked()
	def.AlarmMu.Unlock()
	_ = os.WriteFile(old+".bak", b, 0644)
	logf("[dev] 旧 dev_alarms.json 已迁移到设备 %s（%d 条），备份 dev_alarms.json.bak", def.ID, len(list))
}

// ---------------- 每设备：闹钟表 ----------------

func (d *Device) loadAlarms() {
	var list []DevAlarm
	if b, err := os.ReadFile(devAlarmPath(d.ID)); err == nil {
		_ = json.Unmarshal(b, &list)
	}
	if list == nil {
		list = []DevAlarm{}
	}
	d.AlarmMu.Lock()
	d.Alarms = list
	d.AlarmMu.Unlock()
}

func (d *Device) saveAlarmsLocked() {
	b, _ := json.MarshalIndent(d.Alarms, "", "  ")
	_ = os.MkdirAll(devAlarmDir(), 0755)
	_ = os.WriteFile(devAlarmPath(d.ID), b, 0644)
}

func (d *Device) saveAlarms() {
	d.AlarmMu.Lock()
	d.saveAlarmsLocked()
	d.AlarmMu.Unlock()
}

func (d *Device) alarmCount() int {
	d.AlarmMu.Lock()
	defer d.AlarmMu.Unlock()
	return len(d.Alarms)
}

// pushAlarms 发送整张闹钟表到设备。
func (d *Device) pushAlarms() {
	d.AlarmMu.Lock()
	hb := make([]map[string]any, 0, len(d.Alarms))
	for _, a := range d.Alarms {
		hb = append(hb, map[string]any{
			"na": a.Na, "th": a.TH, "tm": a.TM,
			"sd": a.SD, "wk": a.WK, "wt": a.WT, "s": a.S,
		})
	}
	n := len(d.Alarms)
	d.AlarmMu.Unlock()
	openid := d.OpenID
	if openid == "" {
		openid = cfg.OpenID
	}
	msg := map[string]any{
		"fromUser": openid,
		"optCode":  1003,
		"optValue": map[string]any{"hb": hb},
		"repeat":   1,
		"reset":    0,
	}
	raw, _ := json.Marshal(msg)
	d.enqueueRaw(raw)
	logf("[dev_alarm] [%s] push %d 条 -> %s", d.ID, n, truncate(string(raw), 400))
}

// startOnceJanitorFor 每台设备一次性闹钟的清理协程。
func startOnceJanitorFor(d *Device) {
	for {
		time.Sleep(60 * time.Second)
		now := time.Now().Unix()
		var removed []string
		d.AlarmMu.Lock()
		kept := make([]DevAlarm, 0, len(d.Alarms))
		for _, a := range d.Alarms {
			if a.ONCE && a.Until > 0 && now > a.Until {
				removed = append(removed, fmt.Sprintf("%02d:%02d %q", a.TH, a.TM, a.Na))
				continue
			}
			kept = append(kept, a)
		}
		if len(removed) > 0 {
			d.Alarms = kept
			d.saveAlarmsLocked()
		}
		d.AlarmMu.Unlock()
		if len(removed) > 0 {
			logf("[once] [%s] 自动删除已过期的一次性闹钟 %d 条：%s", d.ID, len(removed), strings.Join(removed, "; "))
			d.pushAlarms()
		}
	}
}

// ---------------- 每设备：铃声选择 ----------------

// pickDueRingLocked 在已加锁的闹钟表上挑选"当前应响的闹钟"：
// 取时间最接近当前的那条（5 分钟内，越近越优先）。
func (d *Device) pickDueRingLocked(hh, mm int) (string, string, int) {
	bestRV, bestNa, bestDist := "", "", 999
	for i := range d.Alarms {
		a := &d.Alarms[i]
		if a.S == 0 {
			continue
		}
		alarmMin := a.TH*60 + a.TM
		nowMin := hh*60 + mm
		diff := nowMin - alarmMin
		if diff < -30 {
			diff += 24 * 60
		}
		if diff < 0 || diff > 5 {
			continue
		}
		if diff < bestDist {
			bestDist = diff
			bestRV = a.RV
			bestNa = a.Na
		}
	}
	return bestRV, bestNa, bestDist
}

// ringtoneForNow 依据"当前时间最接近哪条闹钟"选择铃声；没有专属铃声则回落全局。
func (d *Device) ringtoneForNow() string {
	now := devNow()
	hh, mm := now.Hour(), now.Minute()
	d.AlarmMu.Lock()
	rv, na, diff := d.pickDueRingLocked(hh, mm)
	d.AlarmMu.Unlock()
	if rv != "" {
		logf("[ring] [%s] 命中闹钟 %q（距今 %d 分钟）-> 铃声 %q", d.ID, na, diff, rv)
		return rv
	}
	return d.AlarmVoice
}

// alarmRingIfDue 仅在"当前有到点闹钟且带专属铃声"时返回铃声，否则空串。
func (d *Device) alarmRingIfDue() string {
	now := devNow()
	hh, mm := now.Hour(), now.Minute()
	d.AlarmMu.Lock()
	rv, na, diff := d.pickDueRingLocked(hh, mm)
	d.AlarmMu.Unlock()
	if rv != "" {
		logf("[ring] [%s] 闹钟到点 %q（距今 %d 分钟）-> 专属铃声 %q", d.ID, na, diff, rv)
		return rv
	}
	return ""
}

// ringtoneForHHMM 按 "07:30" 形式的闹钟时间查找该设备的专属铃声。
func (d *Device) ringtoneForHHMM(hhmm string) string {
	ps := strings.Split(hhmm, ":")
	if len(ps) < 2 {
		return d.AlarmVoice
	}
	h, _ := strconv.Atoi(ps[0])
	m, _ := strconv.Atoi(ps[1])
	d.AlarmMu.Lock()
	defer d.AlarmMu.Unlock()
	for i := range d.Alarms {
		a := &d.Alarms[i]
		if a.TH == h && a.TM == m && a.RV != "" {
			logf("[ring] [%s] 按时间命中 %02d:%02d %q -> 铃声 %q", d.ID, a.TH, a.TM, a.Na, a.RV)
			return a.RV
		}
	}
	return d.AlarmVoice
}

// ---------------- 每设备：播放闸门 ----------------

// gateAllowAudio 是否还允许再回一次"播放"给设备：
//   - 停止后 10 秒内一律不再回（用户已按停止）
//   - 60 秒窗口内最多回 3 次（打断设备的"下一首"无限循环）
func (d *Device) gateAllowAudio() bool {
	now := time.Now()
	d.GateMu.Lock()
	defer d.GateMu.Unlock()
	g := &d.Gate
	if !g.StopAt.IsZero() && now.Sub(g.StopAt) < 10*time.Second {
		return false
	}
	if now.Sub(g.Win) > 60*time.Second {
		g.Win = now
		g.Cnt = 0
	}
	g.Cnt++
	g.LastAudio = now
	return g.Cnt <= 3
}

func (d *Device) gateResetAudio() {
	d.GateMu.Lock()
	d.Gate.Win = time.Now()
	d.Gate.Cnt = 0
	d.Gate.StopAt = time.Time{}
	d.GateMu.Unlock()
}

func (d *Device) gateMarkStop() {
	d.GateMu.Lock()
	d.Gate.StopAt = time.Now()
	d.GateMu.Unlock()
}

func (d *Device) gateStat() (int, int64, string, string, string) {
	d.GateMu.Lock()
	defer d.GateMu.Unlock()
	g := d.Gate
	la, lm, ls := "", "", ""
	if !g.LastAudio.IsZero() {
		la = g.LastAudio.Format("15:04:05")
	}
	if !g.LastMedia.IsZero() {
		lm = g.LastMedia.Format("15:04:05")
	}
	if !g.StopAt.IsZero() {
		ls = g.StopAt.Format("15:04:05")
	}
	return g.Cnt, g.MediaCnt, la, lm, ls
}

func (d *Device) noteMedia() {
	d.GateMu.Lock()
	d.Gate.MediaCnt++
	d.Gate.LastMedia = time.Now()
	d.GateMu.Unlock()
}

// ---------------- 每设备：状态 ----------------

func (d *Device) setStatus(vol, bat int) {
	d.StMu.Lock()
	if vol >= 0 {
		d.Stat.Vol = vol
	}
	if bat >= 0 {
		d.Stat.Battery = bat
	}
	d.Stat.At = time.Now().Format("15:04:05")
	s := d.Stat
	d.StMu.Unlock()
	b, _ := json.MarshalIndent(s, "", "  ")
	_ = os.WriteFile(filepath.Join(cfg.LogDir, "status-"+safeFileName(d.ID)+".json"), b, 0644)
}

func (d *Device) getStatus() Status {
	d.StMu.Lock()
	defer d.StMu.Unlock()
	return d.Stat
}

func (d *Device) clientCount() int {
	d.ConnMu.Lock()
	defer d.ConnMu.Unlock()
	return len(d.Conns)
}

func (d *Device) lastConnectTime() time.Time {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.LastConnect
}

func (d *Device) deviceIP() string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.IP
}

func (d *Device) bindConn(m *mqttConn, remoteIP string) {
	d.ConnMu.Lock()
	d.Conns[m] = true
	d.ConnMu.Unlock()
	d.mu.Lock()
	d.LastConnect = time.Now()
	if remoteIP != "" {
		d.IP = remoteIP
	}
	d.Online = true
	d.mu.Unlock()
}

func (d *Device) unbindConn(m *mqttConn) int {
	d.ConnMu.Lock()
	delete(d.Conns, m)
	n := len(d.Conns)
	d.ConnMu.Unlock()
	if n == 0 {
		d.mu.Lock()
		d.Online = false
		d.mu.Unlock()
	}
	return n
}

func safeFileName(s string) string {
	out := strings.Map(func(r rune) rune {
		if r == '/' || r == '\\' || r == ':' || r < 32 {
			return '_'
		}
		return r
	}, s)
	if out == "" {
		return "default"
	}
	return out
}

// ---------------- 每设备：MQTT 下发 ----------------

func (d *Device) enqueue(payload any) {
	b, err := json.Marshal(payload)
	if err != nil {
		logf("marshal: %v", err)
		return
	}
	d.enqueueRaw(b)
}

func (d *Device) enqueueRaw(raw []byte) {
	cmdQueue <- Command{Payload: raw, Topic: d.topicName(), DevID: d.ID, Enqueued: time.Now()}
	logf("[queue] [%s] %s", d.ID, string(raw))
}

// publishNow 跳过 gap 队列，立刻把消息推给该设备的在线连接。
func (d *Device) publishNow(payload []byte) int {
	d.ConnMu.Lock()
	targets := make([]*mqttConn, 0, len(d.Conns))
	for m := range d.Conns {
		targets = append(targets, m)
	}
	d.ConnMu.Unlock()
	if len(targets) == 0 {
		logf("[send-now] [%s] 无连接，丢弃: %s", d.ID, string(payload))
		pushMqttLog("[" + d.ID + "] DROP(no client) " + truncate(string(payload), 160))
		return 0
	}
	body := encStr(d.topicName())
	qos := byte(0)
	if cfg.PublishQoS == 1 {
		qos = 1
		pidCounter = pidCounter%65534 + 1
		body = append(body, byte(pidCounter>>8), byte(pidCounter))
	}
	body = append(body, payload...)
	pkt := mkPacket(0x30|(qos<<1), body)
	sent := 0
	for _, m := range targets {
		if err := m.write(pkt); err != nil {
			logf("[send-now] [%s] 写失败: %v", d.ID, err)
			pushMqttLog("[" + d.ID + "] WRITE-ERR " + err.Error())
		} else {
			sent++
		}
	}
	logf("[send-now] [%s] -> %d client(s): %s", d.ID, sent, string(payload))
	pushMqttLog("[" + d.ID + "] PUB(" + strconv.Itoa(sent) + ") " + truncate(string(payload), 160))
	return sent
}

func (d *Device) playCmd(file string) {
	u := "http://" + cfg.LanIP + "/media/" + url.PathEscape(file)
	mid := int32(atomic.AddUint32(&d.MediaSeq, 1) % 2000000000)
	if mid <= 0 {
		mid += 1000
	}
	openid := d.OpenID
	if openid == "" {
		openid = cfg.OpenID
	}
	raw, _ := json.Marshal(map[string]any{
		"type": 1, "fromUser": openid,
		"message": map[string]any{
			"category": 0, "operate": 1, "arg": file,
			"mediaId": mid, "url": u, "tip": "",
		},
	})
	logf("[play] [%s] file=%q mediaId=%d url=%s", d.ID, file, mid, u)
	d.publishNow(raw)
}

func (d *Device) opCmd(op int) {
	d.enqueue(map[string]any{"type": 2, "fromUser": "", "message": map[string]any{"operate": op}})
}

func (d *Device) stopCmd() {
	openid := d.OpenID
	if openid == "" {
		openid = cfg.OpenID
	}
	raw, _ := json.Marshal(map[string]any{
		"type": 1, "fromUser": openid,
		"message": map[string]any{"category": 0, "operate": 2, "arg": "", "mediaId": 1, "url": "", "tip": ""},
	})
	d.gateMarkStop()
	logf("[stop] [%s] 下发停止（并开启 10 秒播放闸门）", d.ID)
	d.publishNow(raw)
}

// enqueueRingAudio 让设备播放指定音频（云端闹钟的铃声）。
func (d *Device) enqueueRingAudio(file string) {
	if file == "" {
		file = d.ringtoneForNow()
	}
	if file == "" {
		file = d.MusicFile
	}
	if file == "" {
		file = cfg.MusicFile
	}
	if file == "" {
		file = "tone.mp3"
	}
	u := "http://" + cfg.LanIP + ":" + strconv.Itoa(cfg.HTTPPort) + "/media/" + url.PathEscape(file)
	openid := d.OpenID
	if openid == "" {
		openid = cfg.OpenID
	}
	d.enqueue(map[string]any{"type": 1, "fromUser": openid,
		"message": map[string]any{
			"arg": file, "category": 0, "mediaId": 9001,
			"operate": 1, "tip": "", "url": u,
		}})
	logf("[alarm] [%s] 云端闹钟触发，推送铃声 %q -> %s", d.ID, file, u)
}

// deviceInfo 供 /api/devices 与页面使用。
func (d *Device) info() map[string]any {
	d.mu.Lock()
	online := d.Online || d.clientCount() > 0
	ip := d.IP
	lc := d.LastConnect
	d.mu.Unlock()
	last := ""
	if !lc.IsZero() {
		last = lc.Format("15:04:05")
	}
	return map[string]any{
		"id": d.ID, "name": d.displayName(),
		"online": online, "ip": ip, "last_connect": last,
		"alarm_count": d.alarmCount(),
		"alarm_voice": d.AlarmVoice, "music_file": d.MusicFile,
		"topic": d.topicName(), "api_key": d.APIKey,
	}
}

// deviceSummary 按默认顺序返回设备列表。
func deviceSummary() []map[string]any {
	list := allDevices()
	out := make([]map[string]any, 0, len(list))
	for _, d := range list {
		out = append(out, d.info())
	}
	return out
}

// sortedDeviceIDs 仅用于日志/调试。
func sortedDeviceIDs() []string {
	ids := make([]string, 0)
	for _, d := range allDevices() {
		ids = append(ids, d.ID)
	}
	sort.Strings(ids)
	return ids
}
