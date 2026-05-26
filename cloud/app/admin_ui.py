"""Minimal browser admin console for the Cloud Bridge."""

from fastapi import APIRouter, Depends, Request
from fastapi.responses import HTMLResponse

from app.auth import AuthService, require_admin
from app.config import get_settings

router = APIRouter()


TASK_KIND_EXAMPLES = [
    {
        "kind": "agent.run_task",
        "label": "Agent task",
        "params": {"task": "how much battery left"},
        "description": "Run a natural-language instruction on the selected phone.",
    },
    {
        "kind": "android.open_url",
        "label": "Open URL",
        "params": {"url": "https://example.com"},
        "description": "Open a URL on the selected phone.",
    },
    {
        "kind": "ths.sync_holdings",
        "label": "Sync THS holdings",
        "params": {"account_alias": "main"},
        "description": "Run the 同花顺 holdings playbook and persist structured results.",
    },
]


@router.get("/admin", response_class=HTMLResponse)
async def admin_console() -> HTMLResponse:
    """Serve the self-contained admin page.

    The page itself is public, but every data/action request still requires
    ADMIN_TOKEN via Authorization: Bearer.
    """
    return HTMLResponse(ADMIN_HTML)


@router.get(
    "/api/admin/info",
    dependencies=[Depends(require_admin)],
)
async def admin_info(request: Request):
    """Return safe operational metadata for the admin console."""
    settings = get_settings()
    base_url = str(request.base_url).rstrip("/")
    auth = AuthService()
    return {
        "service": "PokeClaw Cloud Bridge",
        "base_url": base_url,
        "admin_token_env": "ADMIN_TOKEN",
        "admin_token_masked": auth.mask_token(settings.admin_token),
        "device_tokens_env": "DEVICE_TOKENS",
        "device_tokens_count": len(settings.device_token_set),
        "device_tokens_masked": [
            auth.mask_token(token) for token in sorted(settings.device_token_set)
        ],
        "websocket_url": base_url.replace("http://", "ws://").replace("https://", "wss://") + "/ws/device",
        "task_kinds": TASK_KIND_EXAMPLES,
        "endpoints": [
            {
                "method": "GET",
                "path": "/api/devices",
                "description": "List connected devices.",
            },
            {
                "method": "POST",
                "path": "/api/tasks",
                "description": "Dispatch a task to a connected device.",
            },
            {
                "method": "GET",
                "path": "/api/tasks/{request_id}",
                "description": "Fetch task status/result.",
            },
            {
                "method": "GET",
                "path": "/api/tasks?limit=20",
                "description": "List recent persisted task logs.",
            },
            {
                "method": "GET",
                "path": "/api/holdings",
                "description": "Query persisted holdings snapshots.",
            },
        ],
    }


ADMIN_HTML = r"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>PokeClaw Cloud Admin</title>
  <style>
    :root {
      color-scheme: light dark;
      --bg: #f5f7fb;
      --panel: #ffffff;
      --panel-soft: #f0f4f8;
      --text: #172033;
      --muted: #65738a;
      --border: #d9e1ec;
      --brand: #2563eb;
      --brand-strong: #1d4ed8;
      --ok: #0f8a5f;
      --bad: #c2413a;
      --warn: #a05a00;
      --code: #0b1220;
      --code-text: #d7e1f4;
    }
    @media (prefers-color-scheme: dark) {
      :root {
        --bg: #101624;
        --panel: #172033;
        --panel-soft: #202b42;
        --text: #edf2fb;
        --muted: #9aacc6;
        --border: #31415d;
        --brand: #60a5fa;
        --brand-strong: #93c5fd;
        --code: #070b13;
        --code-text: #d7e1f4;
      }
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      background: var(--bg);
      color: var(--text);
      font: 14px/1.45 system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding: 18px 24px;
      border-bottom: 1px solid var(--border);
      background: var(--panel);
      position: sticky;
      top: 0;
      z-index: 10;
    }
    h1 { margin: 0; font-size: 20px; }
    h2 { margin: 0 0 12px; font-size: 16px; }
    h3 { margin: 16px 0 8px; font-size: 14px; }
    main {
      display: grid;
      grid-template-columns: minmax(320px, 420px) minmax(0, 1fr);
      gap: 16px;
      padding: 16px;
      max-width: 1360px;
      margin: 0 auto;
    }
    section {
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 16px;
      min-width: 0;
    }
    .stack { display: grid; gap: 16px; align-content: start; }
    .row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
    .split { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    label { display: grid; gap: 6px; color: var(--muted); font-size: 12px; }
    input, select, textarea {
      width: 100%;
      border: 1px solid var(--border);
      border-radius: 6px;
      padding: 9px 10px;
      background: var(--panel);
      color: var(--text);
      font: inherit;
    }
    textarea { min-height: 104px; resize: vertical; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
    button {
      border: 0;
      border-radius: 6px;
      padding: 9px 12px;
      background: var(--brand);
      color: white;
      font-weight: 650;
      cursor: pointer;
    }
    button.secondary {
      color: var(--text);
      background: var(--panel-soft);
      border: 1px solid var(--border);
    }
    button:disabled { opacity: .55; cursor: not-allowed; }
    .muted { color: var(--muted); }
    .pill {
      display: inline-flex;
      align-items: center;
      border: 1px solid var(--border);
      border-radius: 999px;
      padding: 4px 8px;
      background: var(--panel-soft);
      color: var(--muted);
      font-size: 12px;
      white-space: nowrap;
    }
    .device {
      display: grid;
      gap: 6px;
      padding: 10px;
      border: 1px solid var(--border);
      border-radius: 8px;
      background: var(--panel-soft);
      margin-top: 8px;
    }
    .device strong { font-size: 13px; }
    pre {
      overflow: auto;
      white-space: pre-wrap;
      word-break: break-word;
      background: var(--code);
      color: var(--code-text);
      border-radius: 8px;
      padding: 12px;
      margin: 0;
      min-height: 84px;
    }
    .status-ok { color: var(--ok); }
    .status-bad { color: var(--bad); }
    .status-warn { color: var(--warn); }
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 8px; border-bottom: 1px solid var(--border); text-align: left; vertical-align: top; }
    th { color: var(--muted); font-size: 12px; font-weight: 650; }
    @media (max-width: 900px) {
      header { position: static; align-items: flex-start; flex-direction: column; }
      main { grid-template-columns: 1fr; padding: 12px; }
      .split { grid-template-columns: 1fr; }
    }
  </style>
</head>
<body>
  <header>
    <div>
      <h1>PokeClaw Cloud Admin</h1>
      <div class="muted" id="serviceMeta">输入 ADMIN_TOKEN 后加载设备和接口信息</div>
    </div>
    <div class="row" style="min-width:min(520px,100%);">
      <label style="flex:1; min-width:240px;">
        ADMIN_TOKEN
        <input id="tokenInput" type="password" autocomplete="current-password" placeholder="Bearer token">
      </label>
      <button id="saveTokenBtn">保存</button>
      <button class="secondary" id="clearTokenBtn">清除</button>
    </div>
  </header>

  <main>
    <div class="stack">
      <section>
        <h2>设备</h2>
        <div class="row">
          <button id="refreshDevicesBtn">刷新设备</button>
          <span class="pill" id="deviceCount">0 connected</span>
        </div>
        <label style="margin-top:12px;">
          选择设备
          <select id="deviceSelect"></select>
        </label>
        <div id="deviceCards"></div>
      </section>

      <section>
        <h2>发送指令</h2>
        <label>
          任务类型
          <select id="kindSelect"></select>
        </label>
        <label style="margin-top:10px;">
          指令 / 主要参数
          <input id="instructionInput" placeholder="how much battery left">
        </label>
        <label style="margin-top:10px;">
          Params JSON
          <textarea id="paramsInput" spellcheck="false"></textarea>
        </label>
        <div class="row" style="margin-top:10px;">
          <label style="width:120px;">
            timeout_sec
            <input id="timeoutInput" type="number" min="10" max="600" value="120">
          </label>
          <button id="submitTaskBtn">发送任务</button>
          <button class="secondary" id="loadRecentBtn">最近任务</button>
        </div>
      </section>
    </div>

    <div class="stack">
      <section>
        <h2>当前结果</h2>
        <div class="row">
          <span class="pill" id="taskState">idle</span>
          <span class="muted" id="requestIdText"></span>
        </div>
        <pre id="resultOutput">等待任务提交。</pre>
      </section>

      <section>
        <h2>接口调用方法</h2>
        <div class="split">
          <div>
            <h3>列出设备</h3>
            <pre id="curlDevices"></pre>
            <h3>提交任务</h3>
            <pre id="curlSubmit"></pre>
          </div>
          <div>
            <h3>查询结果</h3>
            <pre id="curlStatus"></pre>
            <h3>手机 WebSocket</h3>
            <pre id="curlWs"></pre>
          </div>
        </div>
      </section>

      <section>
        <h2>云端配置</h2>
        <pre id="adminInfo">尚未加载。</pre>
      </section>

      <section>
        <h2>最近任务</h2>
        <div style="overflow:auto;">
          <table>
            <thead>
              <tr>
                <th>request_id</th>
                <th>device</th>
                <th>kind</th>
                <th>status</th>
                <th>completed</th>
              </tr>
            </thead>
            <tbody id="recentTasksBody"></tbody>
          </table>
        </div>
      </section>
    </div>
  </main>

<script>
const els = {
  token: document.getElementById('tokenInput'),
  serviceMeta: document.getElementById('serviceMeta'),
  saveToken: document.getElementById('saveTokenBtn'),
  clearToken: document.getElementById('clearTokenBtn'),
  refreshDevices: document.getElementById('refreshDevicesBtn'),
  deviceSelect: document.getElementById('deviceSelect'),
  deviceCards: document.getElementById('deviceCards'),
  deviceCount: document.getElementById('deviceCount'),
  kindSelect: document.getElementById('kindSelect'),
  instruction: document.getElementById('instructionInput'),
  params: document.getElementById('paramsInput'),
  timeout: document.getElementById('timeoutInput'),
  submit: document.getElementById('submitTaskBtn'),
  recent: document.getElementById('loadRecentBtn'),
  result: document.getElementById('resultOutput'),
  taskState: document.getElementById('taskState'),
  requestId: document.getElementById('requestIdText'),
  adminInfo: document.getElementById('adminInfo'),
  curlDevices: document.getElementById('curlDevices'),
  curlSubmit: document.getElementById('curlSubmit'),
  curlStatus: document.getElementById('curlStatus'),
  curlWs: document.getElementById('curlWs'),
  recentBody: document.getElementById('recentTasksBody'),
};

let adminInfo = null;
let devices = [];
let pollTimer = null;
let activeRequestId = '';

function token() { return els.token.value.trim(); }
function baseUrl() { return location.origin; }
function authHeaders() {
  return {
    'Authorization': `Bearer ${token()}`,
    'Content-Type': 'application/json',
  };
}
async function api(path, options = {}) {
  const headers = options.body ? authHeaders() : {'Authorization': `Bearer ${token()}`};
  const resp = await fetch(path, {...options, headers});
  const text = await resp.text();
  let data;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!resp.ok) {
    const detail = data && data.detail ? data.detail : text;
    throw new Error(`${resp.status} ${detail}`);
  }
  return data;
}
function pretty(value) {
  return JSON.stringify(value, null, 2);
}
function setStatus(text, cls) {
  els.taskState.textContent = text;
  els.taskState.className = `pill ${cls || ''}`;
}
function currentKindMeta() {
  if (!adminInfo) return null;
  return adminInfo.task_kinds.find(k => k.kind === els.kindSelect.value);
}
function selectedDeviceId() {
  return els.deviceSelect.value || (devices[0] && devices[0].device_id) || '';
}
function paramsForKind() {
  const kind = els.kindSelect.value;
  const value = els.instruction.value.trim();
  if (kind === 'agent.run_task') return {task: value || 'how much battery left'};
  if (kind === 'android.open_url') return {url: value || 'https://example.com'};
  if (kind === 'ths.sync_holdings') return {account_alias: value || 'main'};
  return currentKindMeta() ? currentKindMeta().params : {};
}
function syncParamsFromInstruction() {
  els.params.value = pretty(paramsForKind());
  updateSnippets();
}
function requestBody() {
  return {
    device_id: selectedDeviceId(),
    kind: els.kindSelect.value,
    params: JSON.parse(els.params.value || '{}'),
    timeout_sec: Number(els.timeout.value || 120),
  };
}
function updateSnippets() {
  const t = token() || '<ADMIN_TOKEN>';
  const host = baseUrl();
  const reqId = activeRequestId || '<request_id>';
  let body;
  try { body = requestBody(); } catch { body = {device_id: selectedDeviceId() || '<device_id>'}; }
  els.curlDevices.textContent = `curl -H 'Authorization: Bearer ${t}' \\\n  '${host}/api/devices'`;
  els.curlSubmit.textContent = `curl -X POST '${host}/api/tasks' \\\n  -H 'Authorization: Bearer ${t}' \\\n  -H 'Content-Type: application/json' \\\n  -d '${JSON.stringify(body)}'`;
  els.curlStatus.textContent = `curl -H 'Authorization: Bearer ${t}' \\\n  '${host}/api/tasks/${reqId}'`;
  const wsUrl = adminInfo ? adminInfo.websocket_url : host.replace('http', 'ws') + '/ws/device';
  els.curlWs.textContent = `${wsUrl}?device_id=<device_id>&app_version=<app_version>&token=<DEVICE_TOKEN>`;
}
function renderKinds() {
  els.kindSelect.innerHTML = '';
  const kinds = adminInfo ? adminInfo.task_kinds : [{kind:'agent.run_task', label:'Agent task'}];
  for (const item of kinds) {
    const opt = document.createElement('option');
    opt.value = item.kind;
    opt.textContent = `${item.label} · ${item.kind}`;
    els.kindSelect.appendChild(opt);
  }
  if (!els.instruction.value) els.instruction.value = 'how much battery left';
  syncParamsFromInstruction();
}
function renderDevices() {
  els.deviceSelect.innerHTML = '';
  els.deviceCards.innerHTML = '';
  els.deviceCount.textContent = `${devices.length} connected`;
  if (!devices.length) {
    const opt = document.createElement('option');
    opt.value = '';
    opt.textContent = '没有在线设备';
    els.deviceSelect.appendChild(opt);
    els.deviceCards.innerHTML = '<p class="muted">手机需要先连接 Cloud Bridge WebSocket。</p>';
    updateSnippets();
    return;
  }
  for (const d of devices) {
    const opt = document.createElement('option');
    opt.value = d.device_id;
    opt.textContent = `${d.device_id}${d.busy ? ' · busy' : ''}`;
    els.deviceSelect.appendChild(opt);

    const div = document.createElement('div');
    div.className = 'device';
    div.innerHTML = `
      <strong>${d.device_id}</strong>
      <span class="muted">version ${d.app_version} · ${d.busy ? 'busy' : 'idle'}</span>
      <span class="muted">last_seen ${d.last_seen}</span>
      <span>${d.capabilities.map(c => `<span class="pill">${c}</span>`).join(' ')}</span>
    `;
    els.deviceCards.appendChild(div);
  }
  updateSnippets();
}
async function loadInfo() {
  adminInfo = await api('/api/admin/info');
  els.serviceMeta.textContent = `${adminInfo.service} · ${adminInfo.base_url}`;
  els.adminInfo.textContent = pretty({
    admin_token_env: adminInfo.admin_token_env,
    admin_token_masked: adminInfo.admin_token_masked,
    device_tokens_env: adminInfo.device_tokens_env,
    device_tokens_count: adminInfo.device_tokens_count,
    device_tokens_masked: adminInfo.device_tokens_masked,
    websocket_url: adminInfo.websocket_url,
    endpoints: adminInfo.endpoints,
  });
  renderKinds();
}
async function loadDevices() {
  devices = await api('/api/devices');
  renderDevices();
}
async function loadRecentTasks() {
  const rows = await api('/api/tasks?limit=20');
  els.recentBody.innerHTML = rows.map(r => `
    <tr>
      <td><button class="secondary" data-request-id="${r.request_id}">${r.request_id}</button></td>
      <td>${r.device_id}</td>
      <td>${r.kind}</td>
      <td>${r.status}</td>
      <td>${r.completed_at || ''}</td>
    </tr>
  `).join('') || '<tr><td colspan="5" class="muted">暂无任务。</td></tr>';
  els.recentBody.querySelectorAll('button[data-request-id]').forEach(btn => {
    btn.addEventListener('click', () => watchTask(btn.dataset.requestId));
  });
}
async function submitTask() {
  if (!selectedDeviceId()) throw new Error('没有可选择的在线设备');
  const body = requestBody();
  setStatus('dispatching', 'status-warn');
  els.result.textContent = pretty(body);
  const resp = await api('/api/tasks', {method: 'POST', body: JSON.stringify(body)});
  await watchTask(resp.request_id);
  await loadRecentTasks().catch(() => {});
}
async function watchTask(requestId) {
  activeRequestId = requestId;
  els.requestId.textContent = requestId;
  updateSnippets();
  if (pollTimer) clearInterval(pollTimer);
  const poll = async () => {
    const data = await api(`/api/tasks/${requestId}`);
    const terminal = ['completed', 'failed', 'timed_out', 'persist_failed'].includes(data.status);
    setStatus(data.status, data.status === 'completed' ? 'status-ok' : terminal ? 'status-bad' : 'status-warn');
    els.result.textContent = pretty(data);
    if (terminal && pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
      await loadRecentTasks().catch(() => {});
    }
  };
  await poll();
  pollTimer = setInterval(() => poll().catch(err => {
    setStatus('poll error', 'status-bad');
    els.result.textContent = err.message;
  }), 2000);
}
async function boot() {
  els.token.value = localStorage.getItem('pokeclaw_admin_token') || '';
  renderKinds();
  updateSnippets();
  if (!token()) return;
  try {
    await loadInfo();
    await loadDevices();
    await loadRecentTasks();
  } catch (err) {
    els.adminInfo.textContent = err.message;
    setStatus('auth required', 'status-bad');
  }
}

els.saveToken.addEventListener('click', async () => {
  localStorage.setItem('pokeclaw_admin_token', token());
  await boot();
});
els.clearToken.addEventListener('click', () => {
  localStorage.removeItem('pokeclaw_admin_token');
  els.token.value = '';
  location.reload();
});
els.refreshDevices.addEventListener('click', () => loadDevices().catch(err => alert(err.message)));
els.recent.addEventListener('click', () => loadRecentTasks().catch(err => alert(err.message)));
els.submit.addEventListener('click', () => submitTask().catch(err => {
  setStatus('submit failed', 'status-bad');
  els.result.textContent = err.message;
}));
els.kindSelect.addEventListener('change', syncParamsFromInstruction);
els.instruction.addEventListener('input', syncParamsFromInstruction);
els.params.addEventListener('input', updateSnippets);
els.deviceSelect.addEventListener('change', updateSnippets);
els.timeout.addEventListener('input', updateSnippets);
els.token.addEventListener('input', updateSnippets);

boot();
</script>
</body>
</html>
"""
