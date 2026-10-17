const state = { tasks: [], taskId: null, result: null, modelUsage: [], reportText: '', source: null, renderTimer: null, selectedPost: null };
const $ = id => document.getElementById(id);

async function api(path, options = {}) {
  const res = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...options });
  const text = await res.text();
  let body = {};
  try { body = text ? JSON.parse(text) : {}; } catch (_) { body = { message: text }; }
  if (!res.ok) throw new Error(body.message || body.code || res.statusText);
  return body;
}

function esc(s) { return String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
function markdown(src) {
  const lines = String(src || '').split(/\r?\n/);
  let html = '', list = false;
  const inline = s => esc(s)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\[(P\d{3})\]/g, '<a class="citation" data-ref="$1">$1</a>');
  for (let line of lines) {
    const t = line.trim();
    const h = /^(#{1,6})\s+(.*)$/.exec(t);
    if (h) { if (list) { html += '</ul>'; list = false; } html += `<h${h[1].length}>${inline(h[2])}</h${h[1].length}>`; continue; }
    if (/^[-*]\s+/.test(t)) { if (!list) { html += '<ul>'; list = true; } html += `<li>${inline(t.replace(/^[-*]\s+/, ''))}</li>`; continue; }
    if (!t) { if (list) { html += '</ul>'; list = false; } continue; }
    if (list) { html += '</ul>'; list = false; }
    html += `<p>${inline(t)}</p>`;
  }
  if (list) html += '</ul>';
  return html;
}

async function loadBrowserStatus() {
  try {
    const status = await api('/api/browser/status');
    const el = $('browser-status');
    el.textContent = status.message;
    el.className = `browser-status ${status.connected ? 'connected' : 'disconnected'}`;
  } catch (e) { console.error(e); }
}

async function loadModelUsage() {
  if (!state.taskId) return;
  try { state.modelUsage = await api(`/api/tasks/${state.taskId}/model-usage`); }
  catch (e) { state.modelUsage = []; console.error(e); }
}

function pollUntilTerminal(url, options = {}) {
  const intervalMs = options.intervalMs || 800;
  const timeoutMs = options.timeoutMs || 120000;
  const isTerminal = options.isTerminal || (x => ['COMPLETED', 'FAILED'].includes(x?.status));
  const started = Date.now();
  return new Promise((resolve, reject) => {
    const tick = async () => {
      try {
        if (options.shouldStop?.()) return reject(new Error('POLL_STOPPED'));
        if (Date.now() - started > timeoutMs) throw new Error('POLL_TIMEOUT');
        const current = await api(url);
        options.onStatus?.(current);
        if (isTerminal(current)) return resolve(current);
        setTimeout(tick, intervalMs);
      } catch (e) { reject(e); }
    };
    setTimeout(tick, intervalMs);
  });
}

function safePostUrl(url) {
  const parsed = new URL(String(url || ''));
  if (parsed.protocol !== 'https:') throw new Error('Invalid post URL protocol');
  if (parsed.hostname !== 'www.xiaohongshu.com') throw new Error('Invalid post URL host');
  return parsed.toString();
}

async function loadTasks() {
  try {
    state.tasks = (await api('/api/tasks')).sort((a, b) =>
      new Date(b.createdAt || 0) - new Date(a.createdAt || 0));
    renderTasks();
    if (!state.taskId && state.tasks.length) selectTask(state.tasks[0].taskId);
  } catch (e) { console.error(e); }
}

function renderTasks() {
  $('task-list').innerHTML = state.tasks.length ? state.tasks.map(t => `
    <div class="task-item ${t.taskId === state.taskId ? 'active' : ''}" data-id="${esc(t.taskId)}">
      <strong>${esc(t.keyword)}</strong>
      <span>${esc(t.status)} · ${esc((t.createdAt || '').replace('T', ' ').slice(0, 19))}</span>
    </div>`).join('') : '<div class="muted">暂无任务</div>';
  document.querySelectorAll('.task-item').forEach(el => el.onclick = () => selectTask(el.dataset.id));
}

async function startTask() {
  const keyword = $('keyword').value.trim();
  $('form-message').textContent = '';
  if (!keyword) { $('form-message').textContent = '请输入关键词'; return; }
  try {
    const out = await api('/api/tasks', { method: 'POST', body: JSON.stringify({ keyword, runMode: 'NEW' }) });
    await loadTasks();
    selectTask(out.taskId);
  } catch (e) { $('form-message').textContent = e.message; }
}

async function selectTask(taskId) {
  state.taskId = taskId; state.result = null; state.reportText = ''; state.selectedPost = null;
  if (state.source) state.source.close();
  renderTasks(); $('empty-state').classList.add('hidden'); $('task-view').classList.remove('hidden');
  await refreshTask();
  subscribe(taskId);
  await refreshResult(true);
  await loadModelUsage();
  renderResult();
  loadQuestions().catch(console.error);
}

async function refreshTask() {
  if (!state.taskId) return;
  const task = await api(`/api/tasks/${state.taskId}`);
  const i = state.tasks.findIndex(x => x.taskId === task.taskId); if (i >= 0) state.tasks[i] = task; else state.tasks.push(task);
  renderTasks(); renderTask(task);
}

function renderTask(task) {
  $('task-keyword').textContent = task.keyword || '';
  $('task-status').textContent = task.status || '';
  $('task-status').className = `status-pill ${task.status || ''}`;
  $('task-id').textContent = task.taskId || '';
  $('task-created').textContent = (task.createdAt || '').replace('T', ' ').slice(0, 19);
  $('task-duration').textContent = task.orchestration?.currentStage || '';
  const c = task.collection || {};
  $('metric-candidates').textContent = c.distinctPostCount || 0;
  $('metric-relevant').textContent = c.relevantCount || 0;
  $('metric-selected').textContent = c.selectedCount || 0;
  $('metric-analyzed').textContent = c.successfulDetailCount || 0;
  const allUsage = state.modelUsage || [];
  const usageTotal = allUsage.reduce((acc, x) => ({
    calls: acc.calls + Number(x.calls || 0),
    inputTokens: acc.inputTokens + Number(x.inputTokens || 0),
    outputTokens: acc.outputTokens + Number(x.outputTokens || 0)
  }), {calls: 0, inputTokens: 0, outputTokens: 0});
  const cumulativeCandidate = state.result?.metrics?.cumulativeModel;
  const m = allUsage.length ? usageTotal : cumulativeCandidate && Object.keys(cumulativeCandidate).length
    ? cumulativeCandidate : state.result?.metrics?.model || {};
  $('metric-calls').textContent = m.calls || 0;
  $('metric-tokens').textContent = Number(m.inputTokens || 0) + Number(m.outputTokens || 0);
  const map = { QUEUED: 2, WAITING_BROWSER: 8, SEARCHING: 20, CLASSIFYING: 32, RANKING: 38, COLLECTING_DETAILS: 62, EXTRACTING_IMAGES: 72, GENERATING_REPORT: 88, COMPLETED: 100, PARTIALLY_COMPLETED: 100 };
  $('progress-bar').style.width = `${map[task.status] || Math.max(5, Object.values(map).length ? 5 : 5)}%`;
  $('rerun-button').disabled = !task.status || ['CREATED','QUEUED','WAITING_BROWSER','SEARCHING','CLASSIFYING','RANKING','COLLECTING_DETAILS','EXTRACTING_IMAGES','GENERATING_REPORT'].includes(task.status);
}

function subscribe(taskId) {
  state.source = new EventSource(`/api/tasks/${taskId}/stream`);
  ['status','log','agent-status','final-answer-gate','waiting-user','done','error','report-token'].forEach(type => state.source.addEventListener(type, ev => {
    let data = {}; try { data = JSON.parse(ev.data || '{}'); } catch (_) {}
    if (type === 'status') { refreshTask().catch(console.error); addEvent(data.stage || data.status || '状态更新', data); }
    else if (type === 'log') { refreshTask().catch(console.error); addEvent(data.message || data.message, data); }
    else if (type === 'agent-status') { addEvent(`Agent R${data.round || 1} · ${data.tool || ''} · ${data.status || ''}`, data); }
    else if (type === 'final-answer-gate') { $('gate-pill').textContent = data.status || ''; $('gate-pill').className = `gate-pill ${data.status || ''}`; addEvent('Final Answer Gate', data); }
    else if (type === 'waiting-user') { addEvent('等待人工处理：登录或验证码', data); }
    else if (type === 'report-token') { state.reportText += data.text || data.message || ''; scheduleReport(); }
    else if (type === 'done') { addEvent(`任务结束：${data.status || ''}`, data); refreshResult().catch(console.error); refreshTask().catch(console.error); }
    else if (type === 'error') { addEvent(data.message || data.message || '任务错误', data, true); }
  }));
  state.source.onerror = () => { addEvent('SSE 连接中断，将使用状态轮询恢复', {}, true); if (state.source) { state.source.close(); state.source = null; } };
}

function addEvent(message, data = {}, error = false) {
  const div = document.createElement('div'); div.className = `event${error ? ' error' : ''}`;
  div.innerHTML = `<strong>${esc(message || '-')}</strong><small>${new Date().toLocaleTimeString()}</small>`;
  $('event-log').prepend(div);
}

async function refreshResult(quiet = false) {
  if (!state.taskId) return;
  try { state.result = await api(`/api/tasks/${state.taskId}/result`); renderResult(); }
  catch (e) { if (!quiet) addEvent(e.message, {}, true); }
}

function renderResult() {
  const r = state.result || {};
  const report = r.report || {};
  if (report.reportMarkdown) state.reportText = report.reportMarkdown;
  renderMarkdown();
  $('gate-pill').textContent = report.finalAnswerGate || '等待生成';
  $('gate-pill').className = `gate-pill ${report.finalAnswerGate || ''}`;
  const posts = r.posts || [];
  $('post-count').textContent = posts.length;
  $('post-list').innerHTML = posts.map(p => {
    let originalUrl = '#';
    try { originalUrl = safePostUrl(p.url); } catch (_) {}
    return `
    <article class="post-card" data-ref="${esc(p.postRef)}" data-task-id="${esc(state.taskId)}">
      <div class="post-title">[${esc(p.postRef)}] ${esc(p.title || '无标题')}</div>
      <div class="post-meta">
        <span class="tag">${esc(p.author || '作者缺失')}</span><span class="tag">${esc(p.publishedAt || '时间缺失')}</span>
        <span class="tag">点赞 ${esc(p.likes?.raw || '缺失')}</span><span class="tag">${esc(p.collectionStatus || '')}</span>
        ${originalUrl === '#' ? '' : `<a class="original-link" href="${esc(originalUrl)}" target="_blank" rel="noopener noreferrer nofollow">打开原帖</a>`}
      </div>
      <div class="five-status"></div>
      <div class="post-detail">${esc(p.body || '')}${p.imageText?.length ? '\n[图片文字]\n' + p.imageText.map(x => esc(x.text || '')).join('\n') : ''}</div>
    </article>`;
  }).join('');
  document.querySelectorAll('.post-card .post-title').forEach(el => {
    el.onclick = () => requestFiveW1H(el.closest('.post-card'));
  });
  document.querySelectorAll('.post-card .original-link').forEach(el => {
    el.onclick = event => event.stopPropagation();
  });
  const diff = r.diff;
  if (diff) {
    const formatDetail = x => `${x.postRef} · ${x.postId} · ${x.title || '无标题'}`;
    const newPosts = diff.newPostDetails?.length ? diff.newPostDetails.map(formatDetail) : (diff.newPosts || []);
    const missingPosts = diff.missingPostDetails?.length ? diff.missingPostDetails.map(formatDetail) : (diff.missingPosts || []);
    $('diff-summary').textContent = `新增 ${newPosts.length} · 消失 ${missingPosts.length} · 复用 ${diff.reused?.posts || 0}`;
    $('diff-content').innerHTML = `<strong>新增帖子</strong><ul>${newPosts.map(x => `<li>${esc(x)}</li>`).join('') || '<li>无</li>'}</ul>
      <strong>本次搜索范围内消失帖子</strong><ul>${missingPosts.map(x => `<li>${esc(x)}</li>`).join('') || '<li>无</li>'}</ul>
      <strong>热度变化</strong><pre>${esc(JSON.stringify(diff.metricChanges || [], null, 2))}</pre>
      <strong>新观点</strong><ul>${(diff.newOpinions || []).map(x => `<li>${esc(x)}</li>`).join('')}</ul>
      <strong>复用统计</strong><pre>${esc(JSON.stringify(diff.reused || {}, null, 2))}</pre>`;
  }
  const metrics = r.metrics;
  if (metrics) {
    const allUsage = state.modelUsage || [];
    const usageTotal = allUsage.reduce((acc, x) => ({
      calls: acc.calls + Number(x.calls || 0),
      inputTokens: acc.inputTokens + Number(x.inputTokens || 0),
      outputTokens: acc.outputTokens + Number(x.outputTokens || 0)
    }), {calls: 0, inputTokens: 0, outputTokens: 0});
    const cumulative = metrics.cumulativeModel && Object.keys(metrics.cumulativeModel).length
      ? metrics.cumulativeModel : metrics.model;
    const rows = [['总耗时', `${metrics.totalDurationMs || 0} ms`], ['队列等待', `${metrics.queue?.waitMs || 0} ms`],
      ['搜索耗时', `${metrics.collector?.searchDurationMs || 0} ms`], ['详情耗时', `${metrics.collector?.detailDurationMs || 0} ms`],
      ['缓存命中', metrics.collector?.cacheHits || 0], ['重试', metrics.collector?.retries || 0],
      ['Agent 轮次（本轮）', metrics.model?.agentLoopRounds || 0], ['Tool 调用（本轮）', metrics.model?.toolCalls || 0],
      ['模型调用（本轮）', metrics.model?.calls || 0], ['输入 Token（本轮）', metrics.model?.inputTokens || 0],
      ['输出 Token（本轮）', metrics.model?.outputTokens || 0],
      ['模型调用（累计）', cumulative?.calls || 0],
      ['输入 Token（累计）', cumulative?.inputTokens || 0],
      ['输出 Token（累计）', cumulative?.outputTokens || 0],
      ['全量调用记录', allUsage.length],
      ['全量输入 Token', usageTotal.inputTokens],
      ['全量输出 Token', usageTotal.outputTokens]];
    $('metrics-content').innerHTML = `<table><tbody>${rows.map(x => `<tr><th>${x[0]}</th><td>${x[1]}</td></tr>`).join('')}</tbody></table>`;
  }
}

function scheduleReport() { clearTimeout(state.renderTimer); state.renderTimer = setTimeout(renderMarkdown, 80); }
function renderMarkdown() { if (state.reportText) $('report').innerHTML = markdown(state.reportText); }

document.addEventListener('click', e => {
  const citation = e.target.closest('.citation');
  if (!citation) return;
  const card = document.querySelector(`.post-card[data-ref="${citation.dataset.ref}"]`);
  if (card) { card.scrollIntoView({ behavior: 'smooth', block: 'center' }); card.querySelector('.post-title').click(); }
});

async function requestFiveW1H(card) {
  if (!card || !state.taskId || card.dataset.fiveRequesting === '1' || card.dataset.fiveLoaded === '1') return;
  const ref = card.dataset.ref;
  const statusEl = card.querySelector('.five-status');
  card.dataset.fiveRequesting = '1';
  document.querySelectorAll('.post-card').forEach(x => x.classList.remove('selected'));
  card.classList.add('selected');
  statusEl.innerHTML = '<span class="tag">5W1H 生成中…</span>';
  try {
    await api(`/api/tasks/${state.taskId}/posts/${ref}/5w1h`, { method: 'POST' });
    const final = await pollUntilTerminal(`/api/tasks/${state.taskId}/posts/${ref}/5w1h/status`, {
      shouldStop: () => !card.isConnected || state.taskId !== card.dataset.taskId,
      onStatus: current => {
        statusEl.innerHTML = `<span class="tag">5W1H：${esc(current.status || 'RUNNING')}</span>`;
      }
    });
    if (final.status === 'FAILED') throw new Error(final.failure || '5W1H generation failed');
    const five = await api(`/api/tasks/${state.taskId}/posts/${ref}/5w1h`);
    statusEl.innerHTML = '<span class="tag">5W1H：已完成</span>';
    const detail = card.querySelector('.post-detail');
    detail.insertAdjacentHTML('beforeend', `<div><strong>5W1H</strong><br>${Object.entries(five).map(([k, v]) => `${esc(k.toUpperCase())}: ${esc(v?.text || '未提及')}`).join('<br>')}</div>`);
    card.dataset.fiveLoaded = '1';
  } catch (e) {
    statusEl.innerHTML = `<span class="tag">5W1H：失败，点击标题重试</span>`;
    addEvent(`5W1H 加载失败：${e.message}`, {}, true);
  } finally {
    card.dataset.fiveRequesting = '';
  }
}

async function ask() {
  const q = $('question-input').value.trim(); if (!q || !state.taskId) return;
  try {
    const created = await api(`/api/tasks/${state.taskId}/questions`, {
      method: 'POST',
      body: JSON.stringify({ question: q })
    });
    $('question-input').value = '';
    renderQuestions([created]);
    addEvent('追问已提交', {});
    const final = await pollUntilTerminal(`/api/tasks/${state.taskId}/questions/${created.questionId}`, {
      onStatus: current => renderQuestions([current])
    });
    if (final.status === 'FAILED') throw new Error(final.failure || 'Question generation failed');
    await loadQuestions();
  } catch (e) { addEvent(`追问失败：${e.message}`, {}, true); }
}

async function loadQuestions() {
  if (!state.taskId) return;
  const qs = await api(`/api/tasks/${state.taskId}/questions`);
  renderQuestions(qs);
}

function renderQuestions(qs) {
  $('question-list').innerHTML = (qs || []).map(x => `
    <div class="question">
      <div class="q">${esc(x.question)}</div>
      <div class="a">${esc(x.answer || x.status || '')}</div>
      ${x.citations?.length ? `<div class="question-citations">${x.citations.map(c => `<span class="tag">${esc(c)}</span>`).join('')}</div>` : ''}
    </div>`).join('');
}

$('start-button').onclick = startTask;
$('keyword').addEventListener('keydown', e => { if (e.key === 'Enter') startTask(); });
$('ask-button').onclick = ask;
$('cancel-button').onclick = async () => { try { await api(`/api/tasks/${state.taskId}/cancel`, { method: 'POST' }); await refreshTask(); } catch (e) { addEvent(e.message, {}, true); } };
$('retry-button').onclick = async () => { try { await api(`/api/tasks/${state.taskId}/retry`, { method: 'POST', body: '{}' }); selectTask(state.taskId); } catch (e) { addEvent(e.message, {}, true); } };
$('rerun-button').onclick = async () => { try { const out = await api(`/api/tasks/${state.taskId}/rerun`, { method: 'POST' }); await loadTasks(); selectTask(out.taskId); } catch (e) { addEvent(e.message, {}, true); } };
loadBrowserStatus();
loadTasks();
setInterval(() => loadBrowserStatus(), 10000);
setInterval(() => { if (!state.source && state.taskId) refreshTask().catch(console.error); }, 4000);
