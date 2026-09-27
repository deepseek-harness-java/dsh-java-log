const state = {
  logPath: localStorage.getItem('dsh.log.path') || '',
  conversations: [],
  activeConversationId: null,
  activeStream: null
};
const $ = selector => document.querySelector(selector);

function createId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return 'id-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

function toast(message, error = false) {
  const item = document.createElement('div');
  item.textContent = message;
  if (error) item.classList.add('error');
  $('#toast').appendChild(item);
  setTimeout(() => item.remove(), 4000);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[char]));
}

function renderMarkdown(text) {
  if (window.marked && window.DOMPurify) {
    return DOMPurify.sanitize(marked.parse(text));
  }
  return escapeHtml(text);
}

function setStreamStatus(text, visible = true) {
  $('#streamStatus').classList.toggle('hidden', !visible);
  $('#streamStatusText').innerHTML = `<span class="status-spinner"></span>${escapeHtml(text)}`;
}

function setBusy(busy) {
  $('#sendMessage').disabled = busy;
  $('#chatInput').disabled = busy;
  if (!busy) setStreamStatus('', false);
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  });
  const body = response.headers.get('content-type')?.includes('json') ? await response.json() : null;
  if (!response.ok) throw new Error(body?.message || `请求失败 (${response.status})`);
  return body;
}

// ---------- conversations ----------

function newConversation() {
  const conversation = { id: createId(), title: '新对话', messages: [], updatedAt: new Date().toISOString() };
  state.conversations.unshift(conversation);
  state.activeConversationId = conversation.id;
  saveConversations();
  renderConversations();
  renderChatMessages();
}

function saveConversations() {
  localStorage.setItem('dsh.log.conversations', JSON.stringify(state.conversations.slice(0, 50)));
}

function loadConversations() {
  try { state.conversations = JSON.parse(localStorage.getItem('dsh.log.conversations') || '[]'); }
  catch { state.conversations = []; }
}

function renderConversations() {
  const list = $('#conversationList');
  list.innerHTML = '';
  state.conversations.forEach(conversation => {
    const button = document.createElement('button');
    button.className = 'conv-item' + (conversation.id === state.activeConversationId ? ' active' : '');
    button.textContent = conversation.title;
    button.onclick = () => {
      state.activeConversationId = conversation.id;
      renderConversations();
      renderChatMessages();
    };
    list.appendChild(button);
  });
}

function renderChatMessages() {
  const container = $('#chatMessages');
  container.innerHTML = '';
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (!conversation) return;
  conversation.messages.forEach(message => {
    const div = document.createElement('div');
    if (message.role === 'user') {
      div.className = 'msg user';
      div.textContent = message.content;
    } else {
      div.className = 'msg assistant';
      div.innerHTML = assistantHtml(message);
    }
    container.appendChild(div);
  });
  container.scrollTop = container.scrollHeight;
}

function assistantHtml(message) {
  let html = '';
  if (message.reasoning) html += `<div class="reasoning">${escapeHtml(message.reasoning.slice(-600))}</div>`;
  if (message.steps?.length) {
    html += '<div>' + message.steps.map((step, index) => `
      <details class="step-item">
        <summary>
          <span class="step-name">${escapeHtml(step.toolName || '工具 ' + (index + 1))}</span>
          <span class="step-state ${step.status === 'result' ? 'ok' : 'run'}">${step.status === 'result' ? '✓ 完成' : '… 执行中'}</span>
        </summary>
        <div class="step-detail">
          ${step.argsJson ? `<pre>${escapeHtml(step.argsJson)}</pre>` : ''}
          ${step.resultJson ? `<pre>${escapeHtml(String(step.resultJson).slice(0, 1200))}</pre>` : ''}
        </div>
      </details>`).join('') + '</div>';
  }
  html += renderMarkdown(message.content || (message.streaming ? '…' : '（本轮无内容返回）'));
  return html;
}

// ---------- analysis panels ----------

async function loadStats() {
  if (!state.logPath) return;
  const data = await request('/api/log/stats', { method: 'POST', body: JSON.stringify({ path: state.logPath }) });
  const levels = data.levels || {};
  const total = data.totalLines || 1;
  $('#statsCard').innerHTML =
    `<div class="metric-grid">
      <div class="metric"><b>${data.totalLines}</b><span>总行数</span></div>
      <div class="metric"><b>${levels.ERROR || 0}</b><span>ERROR</span></div>
      <div class="metric"><b>${levels.WARN || 0}</b><span>WARN</span></div>
      <div class="metric"><b>${levels.INFO || 0}</b><span>INFO</span></div>
    </div>
    <div class="small muted">${escapeHtml((data.files || []).length)} 个文件 · ${escapeHtml(data.firstTimestamp || '?')} ~ ${escapeHtml(data.lastTimestamp || '?')}</div>
    ${Object.entries(levels).map(([k, v]) => `
      <div class="bar-row">
        <span class="label">${k}</span>
        <span class="bar" style="width:${Math.max(2, v / total * 140)}px;${k === 'ERROR' || k === 'FATAL' ? 'background:var(--danger)' : ''}"></span>
        <span class="num">${v}</span>
      </div>`).join('')}`;
}

async function loadErrors() {
  if (!state.logPath) return;
  const data = await request('/api/log/errors', { method: 'POST', body: JSON.stringify({ path: state.logPath }) });
  const max = data.topPatterns?.[0]?.count || 1;
  $('#errorList').innerHTML =
    `<div class="small muted">${data.errorLines} 条 ERROR/FATAL · ${data.scannedFiles} 个文件</div>` +
    (data.topPatterns || []).map(item => `
      <div class="bar-row">
        <span class="label" title="${escapeHtml(item.pattern)}">${escapeHtml(item.pattern)}</span>
        <span class="bar" style="width:${Math.max(4, item.count / max * 110)}px;background:var(--danger)"></span>
        <span class="num">${item.count}</span>
      </div>`).join('') +
    '<h4 style="margin:10px 0 4px;font-size:12.5px">样本</h4>' +
    (data.samples || []).slice(0, 8).map(s => `<div class="story-item"><b>${escapeHtml(s.level)}</b> ${escapeHtml(s.message.slice(0, 120))}</div>`).join('');
}

async function loadStacks() {
  if (!state.logPath) return;
  const data = await request('/api/log/stacks', { method: 'POST', body: JSON.stringify({ path: state.logPath }) });
  const maxE = data.exceptionTypes?.[0]?.count || 1;
  $('#stackList').innerHTML =
    '<h4 style="margin:4px 0;font-size:12.5px">异常类型 Top 10</h4>' +
    (data.exceptionTypes || []).map(item => `
      <div class="bar-row">
        <span class="label" title="${escapeHtml(item.exception)}">${escapeHtml(item.exception.split('.').pop())}</span>
        <span class="bar" style="width:${Math.max(4, item.count / maxE * 110)}px"></span>
        <span class="num">${item.count}</span>
      </div>`).join('') || '<div class="muted small">无堆栈</div>' +
    '<h4 style="margin:10px 0 4px;font-size:12.5px">业务热帧 Top 10</h4>' +
    (data.hotFrames || []).map(item => `<div class="story-item"><b>${escapeHtml(item.frame)}</b> × ${item.count}</div>`).join('');
}

async function loadTimeline() {
  if (!state.logPath) return;
  const data = await request('/api/log/timeline', { method: 'POST', body: JSON.stringify({ path: state.logPath }) });
  $('#timelineCard').innerHTML = data.file
    ? `<div class="small muted">${escapeHtml(data.file)} : 第 ${data.firstErrorLine} 行</div>` +
      (data.context || []).map(row => `
        <div class="story-item" ${row.lineNo === data.firstErrorLine ? 'style="border-left-color:var(--danger)"' : ''}>
          <b>L${row.lineNo}</b> ${escapeHtml(row.text.slice(0, 160))}
        </div>`).join('')
    : `<div class="muted">${escapeHtml(data.message || '未找到 ERROR')}</div>`;
}

async function loadTail() {
  if (!state.logPath) return;
  const data = await request('/api/log/tail', { method: 'POST', body: JSON.stringify({ path: state.logPath }) });
  $('#tailCard').innerHTML =
    `<div class="small muted">${escapeHtml(data.file)}</div>
     <pre style="font-size:11px;max-height:400px;overflow:auto;background:var(--bg);padding:8px;border-radius:8px">${escapeHtml((data.lines || []).join('\n').slice(0, 6000))}</pre>`;
}

async function refreshAll() {
  await Promise.all([loadStats(), loadErrors(), loadStacks(), loadTimeline(), loadTail()]
    .map(p => p.catch(e => toast(e.message, true))));
}

// ---------- chat ----------

async function sendMessage() {
  const input = $('#chatInput');
  const text = input.value.trim();
  if (!text) return;
  if (!state.logPath) return toast('请先填写日志路径并点击分析', true);
  if (!state.activeConversationId) newConversation();
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (conversation.title === '新对话') conversation.title = text.slice(0, 30);
  conversation.messages.push({ role: 'user', content: text });
  const pending = { id: createId(), role: 'assistant', content: '', reasoning: '', steps: [], streaming: true };
  conversation.messages.push(pending);
  input.value = '';
  saveConversations();
  renderConversations();
  renderChatMessages();
  setBusy(true);
  setStreamStatus('AI 正在思考');
  try {
    await streamAssistant(state.logPath, text, pending);
    setStreamStatus('已完成');
    setTimeout(() => setBusy(false), 450);
  } catch (error) {
    pending.content = error.name === 'AbortError' ? '**已停止生成。**' : error.message;
    setStreamStatus('执行失败');
    setTimeout(() => setBusy(false), 900);
  }
  saveConversations();
  renderChatMessages();
}

async function streamAssistant(logPath, message, pending) {
  const controller = new AbortController();
  state.activeStream = controller;
  const response = await fetch('/api/assistant/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ agentId: state.activeConversationId, message, logPath }),
    signal: controller.signal
  });
  if (!response.ok || !response.body) throw new Error(`请求失败 (${response.status})`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let event = 'message';
  let data = '';
  let finished = false;
  let renderQueued = false;
  const render = () => {
    if (renderQueued) return;
    renderQueued = true;
    setTimeout(() => { renderQueued = false; renderChatMessages(); }, 60);
  };
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop();
    lines.forEach(line => {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data += `${line.slice(5).trim()}\n`;
      else if (!line && data) {
        try {
          const payload = JSON.parse(data);
          if (event === 'chunk') pending.content += payload.content || '';
          if (event === 'chunk' && pending.content) setStreamStatus('正在回复');
          if (event === 'reasoning') pending.reasoning += payload.content || '';
          if (event === 'step_break') {
            const exists = payload.callId && pending.steps.some(item => item.callId === payload.callId);
            if (!exists) pending.steps.push({ ...payload, status: 'running' });
            setStreamStatus(`正在执行 ${payload.toolName || '工具'}`);
          }
          if (event === 'tool_result') {
            const step = [...pending.steps].reverse().find(item => item.callId === payload.callId)
              || [...pending.steps].reverse().find(item => item.status === 'running');
            if (step) Object.assign(step, payload, { status: 'result' });
            else pending.steps.push({ ...payload, status: 'result' });
            setStreamStatus('正在整理结果');
          }
          if (event === 'finish' || event === 'done') {
            pending.steps.forEach(item => { if (item.status === 'running') item.status = 'result'; });
            pending.streaming = false;
            finished = true;
          }
          if (event === 'error') pending.content += `\n\n**错误：** ${payload.message || '未知错误'}`;
          render();
        } catch { }
        event = 'message';
        data = '';
      }
    });
    if (finished) break;
  }
  if (!finished) pending.streaming = false;
  state.activeStream = null;
  renderChatMessages();
}

// ---------- events ----------

$('#sendMessage').onclick = sendMessage;
$('#chatInput').addEventListener('keydown', event => {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault();
    sendMessage();
  }
});
$('#stopStream').onclick = () => state.activeStream?.abort();
$('#newConversation').onclick = () => { newConversation(); };
$('#loadLogs').onclick = async () => {
  state.logPath = $('#logPath').value.trim();
  if (!state.logPath) return toast('请填写日志路径', true);
  localStorage.setItem('dsh.log.path', state.logPath);
  await refreshAll();
  toast('日志已分析');
};
$('#refreshResource').onclick = async () => {
  if (!state.logPath) return toast('请先填写日志路径', true);
  await refreshAll();
  toast('已刷新');
};
document.querySelectorAll('[data-resource-tab]').forEach(button => {
  button.onclick = () => {
    document.querySelectorAll('[data-resource-tab]').forEach(item => item.classList.toggle('active', item === button));
    document.querySelectorAll('[data-tab]').forEach(panel => panel.classList.toggle('hidden', panel.dataset.tab !== button.dataset.resourceTab));
  };
});
$('#toggleTheme').onclick = () => {
  const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  localStorage.setItem('dsh.log.theme', next);
  $('#toggleTheme').textContent = next === 'dark' ? '☀️' : '🌙';
};
document.documentElement.dataset.theme = localStorage.getItem('dsh.log.theme') || 'light';
$('#toggleTheme').textContent = document.documentElement.dataset.theme === 'dark' ? '☀️' : '🌙';

// ---------- init ----------

(async function init() {
  loadConversations();
  if (!state.conversations.length) newConversation();
  else { state.activeConversationId = state.conversations[0].id; }
  renderConversations();
  renderChatMessages();
  if (state.logPath) {
    $('#logPath').value = state.logPath;
    await refreshAll();
  }
})();
