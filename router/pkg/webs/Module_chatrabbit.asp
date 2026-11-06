<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>聊天兔 · 本地云控制</title>
<style>
  body{font-family:-apple-system,"Segoe UI",Roboto,sans-serif;margin:16px;background:#f5f6f8;color:#222}
  h2{margin:0 0 12px}
  .card{background:#fff;border:1px solid #e3e6ea;border-radius:8px;padding:12px;margin-bottom:12px}
  button{background:#2563eb;color:#fff;border:0;border-radius:6px;padding:7px 13px;margin:3px 5px 3px 0;cursor:pointer;font-size:13px}
  button.gray{background:#6b7280}
  button.green{background:#059669}
  button.red{background:#dc2626}
  button.sm{padding:3px 8px;font-size:12px}
  code{background:#f0f2f5;padding:1px 5px;border-radius:4px;font-size:12px}
  #status{font-size:14px;line-height:1.7}
  #log{background:#111;color:#9fe870;font-family:Consolas,monospace;font-size:12px;height:140px;overflow:auto;padding:8px;border-radius:6px}
  input[type=text],input[type=time]{padding:6px;border:1px solid #cbd5e1;border-radius:6px}
  select{padding:6px;border-radius:6px;border:1px solid #cbd5e1}
  table{border-collapse:collapse;width:100%;font-size:13px;margin-top:8px}
  th,td{border-bottom:1px solid #eef1f4;padding:5px 6px;text-align:left}
  textarea{width:100%;height:90px;font-family:Consolas,monospace;font-size:12px;border:1px solid #cbd5e1;border-radius:6px;padding:6px}
  .muted{color:#6b7280;font-size:12px}
  .tablewrap{overflow-x:auto;-webkit-overflow-scrolling:touch}
  /* 手机竖屏：表格改成卡片式，否则 7 列会被挤出屏幕（wt/状态/操作看不见） */
  @media (max-width:780px){
    .tablewrap{overflow-x:visible}
    .tablewrap thead{display:none}
    #alarms tr{display:block;border:1px solid #e3e6ea;border-radius:8px;padding:8px 10px;margin:8px 0;background:#fbfcfd}
    #alarms td{display:flex;justify-content:space-between;align-items:center;gap:10px;border:0;padding:3px 0;text-align:right}
    #alarms td::before{content:attr(data-label);color:#6b7280;font-size:12px;flex:0 0 auto;text-align:left}
    #alarms td select,#alarms td input[type=number]{max-width:64%;min-width:0}
    #alarms td.act{justify-content:flex-end;gap:6px}
    #alarms td.act::before{content:attr(data-label);margin-right:auto}
  }
</style>
</head>
<body>
<h2>聊天兔 · 本地云控制</h2>

<div class="card" id="status">读取中…</div>

<div class="card">
  <b>设备</b>（多设备：切换后下面的状态/闹钟/铃声/播放都作用于所选设备）
  <div style="margin:6px 0">
    <select id="devSel" onchange="switchDev(this.value)"><option value="">加载中…</option></select>
    <button class="gray sm" onclick="loadDevices()">刷新列表</button>
    <button class="gray sm" onclick="addDev()">新增设备</button>
    <button class="red sm" onclick="delDev()">删除当前设备</button>
    <span class="muted" id="devInfo"></span>
  </div>
</div>

<div style="margin:6px 0">
  <button class="gray sm" onclick="showDiag()">DIAG</button>
  <button class="gray sm" onclick="showHealth()">HEALTH</button>
  <button class="gray sm" onclick="showMediaLog()">MEDIA LOG</button>
  <button class="gray sm" onclick="showDevState()">设备状态</button>
  <button class="gray sm" onclick="showTime()">时间</button>
  <button class="gray sm" onclick="setTimeMode(5)">时间格式=裸秒</button>
  <button class="gray sm" onclick="setTimeMode(1)">时间格式=JSON</button>
  <button class="gray sm" onclick="setTimeMode(8)" style="background:#2a7">时间格式=自动轮换</button>
  <button class="gray sm" onclick="setTimeMode(9)">时间格式=只给秒</button>
  <button class="gray sm" onclick="setTimeMode(10)">时间格式=纯文本</button>
  <button class="gray sm" onclick="showReqLog()">REQ LOG</button>
  <button class="gray sm" onclick="showMqttLog()">MQTT LOG</button>
  <button class="gray sm" onclick="showInstallLog()">INSTALL LOG</button>
  <button class="gray sm" onclick="cleanBad()">CLEAN BAD</button>
  <button class="gray sm" onclick="selfTest()">SELF TEST</button>
</div>

<div class="card">
  <b>播放</b><br>
  <select id="files"><option>加载中…</option></select>
  <button onclick="playSel()">播放选中</button>
  <button class="gray" onclick="stopPlay()">停止</button>
  <button class="gray" onclick="loadFiles()">刷新</button>
  <span style="margin-left:12px">上传：<input type="file" id="uf"> <button class="green" onclick="upload()">上传到路由器</button></span>
</div>

<div class="card">
  <b>控制</b><br>
  <button onclick="op(3)">呼吸灯 开</button>
  <button onclick="op(2)">呼吸灯 关</button>
  <button onclick="op(4)">低电提醒 开</button>
  <button onclick="op(5)">低电提醒 关</button>
  <button onclick="op(1)">音量 +</button>
  <button onclick="op(0)">音量 -</button>
  <button onclick="volSet(4)" style="background:#2a7">音量设为4(最大)</button>
  <button onclick="volSet(2)" style="background:#2a7">音量设为2</button>
  <button class="gray" onclick="loadStatus()">刷新状态</button>
</div>

<div class="card">
  <b>本机闹钟</b>（直接写入设备，改完立刻同步）
  <div class="muted" style="margin:6px 0">
    手机端管理页：<a href="/dev-alarms" target="_blank">/dev-alarms</a>
  </div>
  <div style="margin:8px 0">
    时间 <input type="time" id="a_time" value="07:30">
    名称 <input type="text" id="a_name" value="闹钟" size="10">
    铃声 <select id="a_ring"><option value="">默认（全局铃声）</option></select>
    wt <input type="number" id="a_wt" value="0" min="0" max="9" style="width:60px" title="固件闹钟记录的 wt 字段（语义待证，用于对照实验）">
  </div>
  <div style="margin:8px 0">
    重复（可多选）：
    <label style="margin-right:6px"><input type="checkbox" class="wd" value="1">一</label>
    <label style="margin-right:6px"><input type="checkbox" class="wd" value="2">二</label>
    <label style="margin-right:6px"><input type="checkbox" class="wd" value="3">三</label>
    <label style="margin-right:6px"><input type="checkbox" class="wd" value="4">四</label>
    <label style="margin-right:6px"><input type="checkbox" class="wd" value="5">五</label>
    <label style="margin-right:6px"><input type="checkbox" class="wd" value="6">六</label>
    <label style="margin-right:6px"><input type="checkbox" class="wd" value="7">日</label>
    <button class="sm" onclick="wdPreset('all')">每天</button>
    <button class="sm" onclick="wdPreset('work')">工作日</button>
    <button class="sm" onclick="wdPreset('odd')">一三五</button>
    <button class="sm" onclick="wdPreset('none')">仅一次</button>
  </div>
  <div style="margin:8px 0">
    <input type="date" id="a_date" style="display:none">
    <span id="wds" style="display:none"></span>
    <button class="green" onclick="addAlarm()">＋ 添加并下发</button>
    <button class="red" onclick="clearAlarms()">清空设备闹钟</button>
    <span style="margin-left:12px" class="muted">仅一次编码：</span>
    <button class="sm" onclick="setOnceMode('weekday')">星期位（默认）</button>
    <button class="sm" onclick="setOnceMode('epoch')">时间戳（对照）</button>
  </div>
  <div class="tablewrap">
  <table>
    <thead><tr><th>时间</th><th>名称</th><th>重复</th><th>铃声</th><th>wt</th><th>状态</th><th>操作</th></tr></thead>
    <tbody id="alarms"><tr><td colspan="7" class="muted">读取中…</td></tr></tbody>
  </table>
  </div>
</div>

<div class="card">
  <b>闹铃模式</b>（决定闹铃响的时候谁说话）
  <div class="muted" style="margin:6px 0">
    当前模式：<b id="cur_mode">读取中…</b>
  </div>
  <div style="margin:6px 0">
    <button class="green" onclick="setProxy(true)">原厂模式（图灵官方语音）</button>
    <button class="blue" onclick="setProxy(false)">本地模式（播放下面选的音频）</button>
  </div>
  <div class="muted">原厂模式：转发到真实图灵云，闹铃说"闹钟时间已到"（官方 TTS）。<br>
    本地模式：用本机配置的音频，可放音乐/自录语音。</div>
</div>

<div class="card">
  <b>闹铃语音（已打通）</b><br>
  <div class="muted">闹铃响时设备会连 WebSocket 问"播什么"，我们回一个 ttsUrl → 设备下载播放。设成任意音频即可。</div>
  <div style="margin:8px 0">
    当前：<code id="cur_voice">（未设置，闹铃用默认提示音）</code><br>
    <select id="voice_files"><option>加载中…</option></select>
    <button class="green" onclick="setVoice()">设为闹铃语音</button>
    <button class="gray" onclick="clearVoice()">清除（恢复默认提示音）</button>
  </div>
</div>

<div class="card">
  <b>原始 JSON 发送器</b>（每行一条，按 gap 间隔逐条下发，用于试各种删除/设置格式）
  <div style="margin:6px 0">
    <button class="sm" onclick="preset('emptyArr')">预设：arg=[]</button>
    <button class="sm" onclick="preset('emptyStr')">预设：arg="[]"</button>
    <button class="sm" onclick="preset('minus1')">预设：id=-1,status=0</button>
    <button class="sm" onclick="preset('ids1_26')">预设：id 1..26 逐条 status=0</button>
    <button class="sm" onclick="preset('status0only')">预设：只发 status=0</button>
  </div>
  <textarea id="rawbox"></textarea>
  <div><button onclick="sendRaw()">发送</button> <span class="muted" id="rawmsg"></span></div>
</div>

<div class="card">
  <b>日志</b> <span class="muted" id="logsize"></span>
  <button class="red sm" onclick="clearLogs()">清空日志</button>
  <div class="muted" id="storage" style="margin:6px 0"></div>
  <button class="gray sm" onclick="cleanMedia()">清理非音频文件</button>
  <div id="log"></div>
</div>

<script>
/* ---- API 端口：主用 8890，失败自动回退 8898（kongke 占用 8898，所以不能只依赖它） ---- */
const API_CANDIDATES = [
  location.protocol + "//" + location.hostname + ":8890",
  location.protocol + "//" + location.hostname + ":8898"
];
let API_INDEX = 0;
let API = API_CANDIDATES[0];
let API_PORT = "8890";
function apiPortOf(base){ const m = String(base).match(/:(\d+)$/); return m ? m[1] : ""; }
function apiReady(){
  API = API_CANDIDATES[API_INDEX];
  API_PORT = apiPortOf(API);
  const el = document.getElementById("api_port");
  if(el) el.textContent = ":" + API_PORT;
}
const WDS = [["日",0],["一",1],["二",2],["三",3],["四",4],["五",5],["六",6]];
/* ---- 多设备：所有 API 自动带 ?dev=<当前设备> ---- */
let DEV = "";
function withDev(url){
  if(!DEV) return url;
  if(url.indexOf("dev=") >= 0) return url;
  return url + (url.indexOf("?") >= 0 ? "&" : "?") + "dev=" + encodeURIComponent(DEV);
}
function log(s){const el=document.getElementById("log");el.textContent += s+"\n";el.scrollTop=el.scrollHeight;}
/* 依次尝试候选端口；首次成功后缓存，之后直接用缓存端口 */
async function apiFetch(url, opt){
  let lastErr = null;
  for(let k=0;k<API_CANDIDATES.length;k++){
    const idx = (API_INDEX + k) % API_CANDIDATES.length;
    const base = API_CANDIDATES[idx];
    try{
      const r = await fetch(base + withDev(url), opt||{});
      if(idx !== API_INDEX){
        API_INDEX = idx; apiReady();
        log("[API] 主端口不可用，已回退到 :" + API_PORT);
      }
      return r;
    }catch(e){ lastErr = e; }
  }
  throw lastErr || new Error("api unreachable");
}
async function j(url, opt){ const r = await apiFetch(url, opt||{}); return r.json(); }
async function post(url, body){ return j(url,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body||{})}); }

async function loadDevices(){
  try{
    const d = await j("/api/devices");
    const sel = document.getElementById("devSel");
    const cur = DEV || d.default || "";
    sel.innerHTML = "";
    (d.devices||[]).forEach(function(x){
      const o = document.createElement("option");
      o.value = x.id;
      o.textContent = (x.name||x.id) + (x.online ? " ✅在线" : " ⭕离线") + " · " + x.alarm_count + "条";
      if(x.id === cur) o.selected = true;
      sel.appendChild(o);
    });
    if(!DEV) DEV = cur;
    window.DEVICES = d.devices||[];
    const info = document.getElementById("devInfo");
    if(info){
      const cur2 = (d.devices||[]).filter(function(x){ return x.id===DEV; })[0];
      info.textContent = cur2 ? ("当前 " + cur2.id + (cur2.ip? " · IP "+cur2.ip : "") + " · 默认设备 " + d.default) : "";
    }
  }catch(e){ log("loadDevices failed: "+e); }
}
async function switchDev(id){
  DEV = id || "";
  log("[设备] 切换到 " + DEV);
  await reloadAll();
}
async function addDev(){
  const id = prompt("新设备 deviceId（固件里的设备号，如 AI91144031DD06A2）:");
  if(!id) return;
  const name = prompt("设备名称（可空）:") || id;
  const apiKey = prompt("apiKey（可空，留空则按 deviceId 自动生成时需填）:") || "";
  const r = await post("/api/devices/add", {id:id, name:name, apiKey:apiKey});
  log("新增设备 -> " + JSON.stringify(r));
  if(r.ok){ DEV = id; }
  await loadDevices(); await reloadAll();
}
async function delDev(){
  const id = document.getElementById("devSel").value;
  if(!id) return;
  if(!confirm("删除设备 " + id + " 及其闹钟数据？")) return;
  const r = await post("/api/devices/del", {id:id});
  log("删除设备 -> " + JSON.stringify(r));
  if(r.ok){ DEV = r.default || ""; }
  await loadDevices(); await reloadAll();
}
async function reloadAll(){
  await loadDevices();
  loadStatus(); loadFiles(); loadAlarms(); loadVoiceFiles(); loadLogSize(); loadStorage();
}

async function loadStatus(){
  try{
    const d = await j("/api/status");
    const s = d.status||{};
    const lvl = {"-1":"未上报","0":"振动/无声","1":"静音","2":"音量1","3":"音量2","4":"音量3"}[String(s.vol)] || s.vol;
    document.getElementById("status").innerHTML =
      `<b>设备：</b>${d.device_online? "✅ 在线" : "❌ 离线"}（连接数 ${d.clients}，上次 ${d.last_connect}）<br>`+
      `<b>电量：</b>${s.battery}%　<b>音量：</b>${lvl}（vol=${s.vol}）`+
      ((s.vol === 0 || s.vol === -1) ? `<span style="color:#c00"> ← 设备当前无声/未上报，点歌会听不到；请按玩具上的音量键调大</span>` : "")+`<br>`+
      `<b>本机：</b>${d.ip}　<b>玩具IP：</b>${d.device_ip || "（未连接）"}　<b>主题：</b><code>${d.topic}</code>　<b>间隔：</b>${d.gap}s　<b>版本：</b>${d.version}　<b>API：</b><span id="api_port">:${API_PORT}</span>`;
    const cv=document.getElementById("cur_voice");
    if(cv) cv.textContent = d.alarm_voice ? d.alarm_voice : "（未设置，闹铃用默认提示音）";
    const cm=document.getElementById("cur_mode");
    if(cm) cm.textContent = !d.ws_accept ? "默认提示音（WS 已关闭）"
        : (d.ws_proxy ? "原厂模式：图灵官方语音" : "本地模式：播放选定音频");
  }catch(e){ document.getElementById("status").textContent = "读取失败："+e; }
}
async function loadFiles(){
  const d = await j("/api/list");
  window.FILES = d.files || [];
  const sel = document.getElementById("files");
  sel.innerHTML = "";
  (d.files||[]).forEach(f=>{ const o=document.createElement("option"); o.value=f; o.textContent=f; sel.appendChild(o); });
  if(!(d.files||[]).length) sel.innerHTML = "<option>（media 为空）</option>";
  // 添加表单的"铃声"下拉
  const ar = document.getElementById("a_ring");
  if(ar){
    const keep = ar.value;
    ar.innerHTML = '<option value="">默认（全局铃声）</option>';
    window.FILES.forEach(function(f){ const o=document.createElement("option"); o.value=f; o.textContent=f; ar.appendChild(o); });
    if(keep) ar.value = keep;
  }
}
async function playSel(){ const f=document.getElementById("files").value; if(!f||f.startsWith("（")) return; log("play "+f); log(JSON.stringify(await post("/api/play",{file:f}))); }
async function stopPlay(){ log(JSON.stringify(await post("/api/stop"))); }
async function op(n){ log("operate="+n); log(JSON.stringify(await post("/api/cmd",{operate:n}))); }
async function volSet(v){  const r = await post("/api/volume",{vol:v});
  log("volume -> " + JSON.stringify(r));
  setTimeout(loadStatus, 1200);
}
async function upload(){
  const f = document.getElementById("uf").files[0]; if(!f) return;
  log("上传 "+f.name+" ...");
  const r = await apiFetch("/api/upload?name="+encodeURIComponent(f.name), {method:"POST", body:f});
  log(JSON.stringify(await r.json())); loadFiles();
}
/* ---- 星期多选 ---- */
function wdBoxes(){ return Array.prototype.slice.call(document.querySelectorAll(".wd")); }
function wdMask(){
  let m = 0;
  wdBoxes().forEach(function(c){ if(c.checked) m |= (1 << (Number(c.value)-1)); });
  return m;
}
function wdSet(mask){
  wdBoxes().forEach(function(c){ c.checked = !!(mask & (1 << (Number(c.value)-1))); });
  document.getElementById("a_date").style.display = "none";
}
function wdPreset(kind){
  if(kind === "all"){ wdSet(63); }
  else if(kind === "work"){ wdSet(0b0011111); }        // 周一~周五 = 31
  else if(kind === "odd"){ wdSet(0b0010101); }         // 周一三五 = 21
  else if(kind === "none"){                            // 仅一次
    wdBoxes().forEach(function(c){ c.checked = false; });
    document.getElementById("a_date").style.display = "";
  }
}
function wkText(wk){
  if(wk===0) return "仅一次";
  if(wk>=63) return "每天";
  const n=["周一","周二","周三","周四","周五","周六","周日"];
  let o=[]; for(let i=0;i<7;i++) if(wk&(1<<i)) o.push(n[i]);
  return o.join(" ")||"仅一次";
}
async function addAlarm(){
  const tv = document.getElementById("a_time").value || "07:30";
  const [h,m] = tv.split(":").map(Number);
  const name = document.getElementById("a_name").value || "闹钟";
  const mask = wdMask();
  const body = {hour:h, minute:m, name:name, enabled:true};
  if(mask === 0){
    const dstr = document.getElementById("a_date").value;
    if(!dstr){ alert("请勾选星期，或点击“仅一次”并选择日期"); return; }
    body.onceDate = dstr;
  }else if(mask >= 127){
    body.wk = 63;               // 七天全选 = 每天
  }else{
    body.wk = mask;             // 位掩码：周一=bit0 … 周日=bit6
  }
  const ringSel = document.getElementById("a_ring");
  if(ringSel && ringSel.value) body.ring = ringSel.value;
  const wtEl = document.getElementById("a_wt");
  body.wt = Number((wtEl && wtEl.value) || 0);   // 固件 wt 字段（语义待证，默认 0）
  log("添加闹钟 " + tv + " " + name + " wk=" + (body.wk || ("once:" + body.onceDate)) + " ring=" + (body.ring||"默认") + " wt=" + body.wt);
  log(JSON.stringify(await post("/api/dev/add", body)));
  loadAlarms();
}
async function setOnceMode(m){
  log("仅一次编码 -> " + m);
  log(JSON.stringify(await post("/api/time/set", {once_mode: m})));
}

async function clearAlarms(){
  if(!confirm("清空设备上所有本机闹钟？")) return;
  log(JSON.stringify(await post("/api/dev/clear"))); loadAlarms();
}
async function loadAlarms(){
  const d = await j("/api/dev/list");
  const tb = document.getElementById("alarms");
  const L = d.list || [];
  // 确保铃声候选列表已就绪（否则每行下拉里只剩“默认”）
  if(!window.FILES || !window.FILES.length){
    try{ const f = await j("/api/list"); window.FILES = f.files || []; }catch(e){ window.FILES = window.FILES || []; }
  }
  if(!L.length){ tb.innerHTML = '<tr><td colspan="7" class="muted">设备暂无闹钟</td></tr>'; return; }
  tb.innerHTML = "";
  L.forEach((a, idx)=>{
    const hh = String(a.th).padStart(2,"0")+":"+String(a.tm).padStart(2,"0");
    const tr=document.createElement("tr");
    tr.innerHTML = `<td data-label="时间">${hh}</td><td data-label="名称">${a.na||"闹钟"}</td><td data-label="重复">${wkText(a.wk)}</td>`;
    // 每条闹钟独立铃声
    const tdRing = document.createElement("td");
    tdRing.setAttribute("data-label", "铃声");
    const sel = document.createElement("select");
    sel.className = "sm";
    const opt0 = document.createElement("option");
    opt0.value = ""; opt0.textContent = "默认";
    sel.appendChild(opt0);
    (window.FILES||[]).forEach(function(f){
      const o = document.createElement("option");
      o.value = f; o.textContent = f;
      if(a.rv === f) o.selected = true;
      sel.appendChild(o);
    });
    if(a.rv){
      const o = document.createElement("option");
      o.value = a.rv; o.textContent = a.rv + (window.FILES && window.FILES.indexOf(a.rv)<0 ? "（缺失）" : "");
      if(window.FILES && window.FILES.indexOf(a.rv)<0){ o.selected = true; sel.appendChild(o); }
    }
    sel.onchange = async function(){
      log("设置 index="+idx+" 铃声 -> "+(sel.value||"(默认)"));
      log(JSON.stringify(await post("/api/dev/ring",{index:idx,file:sel.value})));
      loadAlarms();
    };
    tdRing.appendChild(sel);
    tr.appendChild(tdRing);
    // wt 列：0-9 数字输入，改动即调 /api/dev/wt（固件字段，语义待证）
    const tdWt = document.createElement("td");
    tdWt.setAttribute("data-label", "wt");
    const wtIn = document.createElement("input");
    wtIn.type = "number"; wtIn.min = "0"; wtIn.max = "9"; wtIn.value = String(a.wt || 0);
    wtIn.style.width = "54px";
    wtIn.onchange = async function(){
      log("设置 index="+idx+" wt -> "+wtIn.value);
      log(JSON.stringify(await post("/api/dev/wt",{index:idx,wt:Number(wtIn.value||0)})));
      loadAlarms();
    };
    tdWt.appendChild(wtIn);
    tr.appendChild(tdWt);
    const tdSt = document.createElement("td");
    tdSt.setAttribute("data-label", "状态");
    tdSt.textContent = a.s ? "✅ 开" : "⏸ 关";
    tr.appendChild(tdSt);
    const td=document.createElement("td");
    td.className = "act";
    td.setAttribute("data-label", "操作");
    const b1=document.createElement("button"); b1.className="sm gray"; b1.textContent = a.s?"关闭":"开启";
    b1.onclick = async()=>{ log(JSON.stringify(await post("/api/dev/toggle",{index:idx}))); loadAlarms(); };
    const b2=document.createElement("button"); b2.className="sm red"; b2.textContent="删除";
    b2.onclick = async()=>{
      if(!confirm(`删除 ${hh} ${a.na||""}？`)) return;
      log(JSON.stringify(await post("/api/dev/del",{index:idx}))); loadAlarms();
    };
    td.appendChild(b1); td.appendChild(b2); tr.appendChild(td); tb.appendChild(tr);
  });
}
function preset(kind){
  const base = {"type":2,"fromUser":"","message":{"operate":8,"arg":null}};
  let lines = [];
  if(kind==="emptyArr"){ base.message.arg = []; lines=[JSON.stringify(base)]; }
  if(kind==="emptyStr"){ base.message.arg = "[]"; lines=[JSON.stringify(base)]; }
  if(kind==="minus1"){ base.message.arg = {id:-1,status:0}; lines=[JSON.stringify(base)]; }
  if(kind==="status0only"){ base.message.arg = {status:0}; lines=[JSON.stringify(base)]; }
  if(kind==="ids1_26"){
    for(let i=1;i<=26;i++){ lines.push(JSON.stringify({"type":2,"fromUser":"","message":{"operate":8,"arg":{"id":i,"status":0}}})); }
  }
  document.getElementById("rawbox").value = lines.join("\n");
}
async function sendRaw(){
  const txt = document.getElementById("rawbox").value.trim();
  if(!txt) return;
  const r = await j("/api/raw",{method:"POST",body:txt});
  document.getElementById("rawmsg").textContent = JSON.stringify(r);
  log("raw -> "+JSON.stringify(r));
}
async function loadVoiceFiles(){
  const d = await j("/api/list");
  const sel = document.getElementById("voice_files");
  sel.innerHTML="";
  (d.files||[]).forEach(f=>{const o=document.createElement("option");o.value=f;o.textContent=f;sel.appendChild(o);});
  if(!(d.files||[]).length) sel.innerHTML="<option>（media 为空）</option>";
}
async function setVoice(){
  const f=document.getElementById("voice_files").value;
  if(!f||f.startsWith("（")) return;
  const r=await post("/api/alarmvoice",{file:f});
  document.getElementById("cur_voice").textContent=r.file||"";
  log("闹铃语音 -> "+JSON.stringify(r));
  loadStatus();
}
async function clearVoice(){
  const r=await post("/api/alarmvoice",{file:""});
  document.getElementById("cur_voice").textContent="（未设置，闹铃用默认提示音）";
  log("已清除闹铃语音");
}
async function setProxy(on){
  const r=await post("/api/wsproxy",{enable:on});
  log("闹铃模式 -> "+(on?"原厂（图灵官方语音）":"本地（自定义音频）"));
  loadStatus();
}
/* 启动顺序：设备列表 → media 文件（闹钟行的铃声下拉才有候选）→ 各卡片 */
(async function initPage(){
  try{ await loadDevices(); }catch(e){ log("loadDevices failed: "+e); }
  try{ await loadFiles(); }catch(e){ log("loadFiles failed: "+e); }
  loadStatus(); loadAlarms(); loadVoiceFiles(); loadLogSize(); loadStorage();
})();
setInterval(loadStatus, 5000);
setInterval(loadLogSize, 30000);
setInterval(loadStorage, 30000);

/* ---- media file list with delete ---- */
(function(){
  var sel = document.getElementById("voice_files");
  if(!sel) return;
  var box = document.getElementById("filelist");
  if(!box){
    box = document.createElement("div");
    box.id = "filelist";
    box.style.margin = "8px 0";
    sel.parentNode.appendChild(box);
  }
  window.renderFileList = async function(){
    try{
      var d = await j("/api/list");
      var L = d.files || [];
      box.innerHTML = "";
      if(!L.length){ box.innerHTML = '<span class="muted">(media empty)</span>'; return; }
      var head = document.createElement("div");
      head.className = "muted";
      head.textContent = "[media files] click delete to remove:";
      box.appendChild(head);
      L.forEach(function(f){
        var row = document.createElement("div");
        row.style.margin = "2px 0";
        var span = document.createElement("span");
        span.textContent = f;
        span.style.marginRight = "8px";
        var btn = document.createElement("button");
        btn.className = "sm red";
        btn.textContent = "DELETE";
        btn.onclick = async function(){
          if(!confirm("Delete " + f + " ?")) return;
          try{
            var r = await post("/api/file/delete", {name: f});
            log("delete " + f + " -> " + JSON.stringify(r));
            if(window.loadVoiceFiles) await window.loadVoiceFiles();
            if(window.renderFileList) await window.renderFileList();
            if(window.loadStorage) window.loadStorage();
          }catch(e){ log("delete failed: " + e); }
        };
        row.appendChild(span);
        row.appendChild(btn);
        box.appendChild(row);
      });
    }catch(e){}
  };
  var _lvf = window.loadVoiceFiles;
  if(typeof _lvf === "function"){
    window.loadVoiceFiles = async function(){ await _lvf.apply(null, arguments); window.renderFileList(); };
  }
  window.renderFileList();
})();

async function loadLogSize(){
  try{
    const d = await j("/api/log/stat");
    const el = document.getElementById("logsize");
    if(!el) return;
    const kb = (d.total/1024).toFixed(1);
    el.textContent = "（共 " + kb + " KB，" + d.count + " 个文件，单文件上限 " + d.max_mb + " MB，超限自动截断）";
  }catch(e){}
}
async function clearLogs(){
  if(!confirm("清空所有日志文件？")) return;
  try{
    const r = await post("/api/log/clear", {});
    log("已清空日志，释放 " + r.freed + " 字节");
    loadLogSize();
  }catch(e){ log("清空失败：" + e); }
}
async function cleanMedia(){
  if(!confirm("删除媒体目录下所有非 mp3 文件？")) return;
  try{
    const r = await post("/api/media/clean", {});
    log("已清理 " + r.count + " 个文件，释放 " + r.freed + " 字节");
    loadStorage(); loadFiles();
  }catch(e){ log("清理失败：" + e); }
}
async function loadStorage(){  try{
    const d = await j("/api/storage");
    const el = document.getElementById("storage");
    if(!el) return;
    const mb = x => (x/1048576).toFixed(1);
    el.textContent = "媒体目录：" + d.media_dir + "（占用 " + mb(d.media_size) + " MB / " + d.media_count +
      " 个文件）｜该分区剩余 " + mb(d.fs_free) + " MB，共 " + mb(d.fs_total) + " MB";
  }catch(e){}
}

/* ---- diagnostics buttons (static, defined globally) ---- */
async function showDiag(){
  try{ var d = await j("/api/diag"); log("==== diag ===="); log(JSON.stringify(d)); }
  catch(e){ log("diag failed: " + e); }
}
async function showHealth(){
  try{
    var d = await j("/api/health?n=20");
    var now = d.now || {};
    log("==== health ====");
    log("DNS: " + (now.dns_ok ? "OK" : "FAIL") + " -> " + (now.dns_addr||"-") + " (" + (now.dns_ms||0) + "ms)"
        + "  DNS失败累计=" + (d.dns_fail_total||0) + "  运行=" + (d.uptime_s||0) + "s  API=" + JSON.stringify(now.api_ports||[]));
    (now.devices||[]).forEach(function(v){
      log("  " + v.id + "  " + (v.online?"在线":"离线") + "  MQTT连接=" + v.conns
          + "  累计CONNECT=" + v.connects + "  在线翻转=" + v.flips + "  闹钟=" + v.alarms + "  IP=" + (v.ip||"-"));
    });
    var h = d.history || [];
    log("---- 最近 " + h.length + " 次采样（每分钟一次）----");
    h.forEach(function(s){
      var ds = (s.devices||[]).map(function(x){ return x.id.slice(-6) + ":" + (x.online?"on":"off") + "/f" + x.flips; }).join(" ");
      log("  " + s.at + "  dns=" + (s.dns_ok?"ok":"FAIL") + "(" + s.dns_ms + "ms)  " + ds);
    });
    log("（判读：dns 连续 FAIL 或采样中断 → 路由器 DNS/网络面问题；采样在但设备 off → 玩具侧或我们的服务问题）");
  }catch(e){ log("health failed: " + e); }
}
async function showReqLog(){  try{ var d = await j("/api/reqlog"); var it = d.items || [];
    log("==== device requests (" + it.length + ") ====");
    it.slice(-40).forEach(function(x){ log(x); });
  }catch(e){ log("reqlog failed: " + e); }
}
async function showDevState(){
  try{
    const r = await post("/api/dev/state", {});
    log("dev/state -> " + JSON.stringify(r));
    log("(等待 2 秒后查看 MQTT LOG 里设备的 UP 上报)");
  }catch(e){ log("devstate failed: " + e); }
}
async function showTime(){
  try{
    const d = await j("/api/time");
    log("==== time ====");
    log("服务器时间: " + d.server_time + "  unix=" + d.unix);
    log("设备最近上报: " + (d.device_status_at || "（无）"));
    log("time_mode=" + d.mode + "  offset=" + d.offset);
  }catch(e){ log("time failed: " + e); }
}
async function setTimeMode(m){
  const r = await post("/api/time/set", {mode:m});
  log("time mode -> " + JSON.stringify(r));
}
async function showMediaLog(){  try{ var d = await j("/api/medialog"); var it = d.items || [];
    log("==== media downloads (" + it.length + ") ====");
    if(!it.length) log("(no media request yet - device never fetched audio)");
    it.slice(-20).forEach(function(x){ log(x); });
  }catch(e){ log("medialog failed: " + e); }
}
async function showMqttLog(){  try{ var d = await j("/api/mqttlog"); var it = d.items || [];
    log("==== mqtt events (" + it.length + ") ====");
    it.slice(-40).forEach(function(x){ log(x); });
  }catch(e){ log("mqttlog failed: " + e); }
}
async function showInstallLog(){
  try{ var d = await j("/api/installlog"); log("==== install log ====");
    (d.lines||[]).slice(-40).forEach(function(x){ log(x); });
  }catch(e){ log("installlog failed: " + e); }
}
async function cleanBad(){
  if(!confirm("Delete bad-named audio files?")) return;
  try{
    var d = await post("/api/media/cleanup_bad", {});
    log("cleanup_bad -> " + JSON.stringify(d));
    if(window.loadVoiceFiles) window.loadVoiceFiles();
    if(window.renderFileList) window.renderFileList();
    if(window.loadStorage) window.loadStorage();
  }catch(e){ log("cleanup failed: " + e); }
}

/* ---- self test: check media URL reachability from this client ---- */
async function selfTest(){
  var host = location.hostname;
  var lp = (await j("/api/diag")).http_port;
  var urls = [
    "http://" + host + ":" + lp + "/media/tone.mp3",
    "http://" + host + "/media/tone.mp3"
  ];
  for (var i = 0; i < urls.length; i++){
    var u = urls[i];
    try{
      var r = await fetch(u, {method:"GET", headers:{"Range":"bytes=0-1"}});
      var b = await r.arrayBuffer();
      log("SELFTEST " + u + " -> HTTP " + r.status + " bytes=" + b.byteLength + " ctype=" + r.headers.get("Content-Type"));
    }catch(e){
      log("SELFTEST " + u + " -> FAIL " + e);
    }
  }
  log("SELFTEST done (若第一个失败说明 " + lp + " 端口不通；第二个失败说明 80 重定向没生效)");
}
</script>
</body>
</html>
