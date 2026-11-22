// ── 身份：JWT 存在 localStorage，请求时放 Authorization 头 ──
const TOKEN_KEY = 'skqa_token';
const USER_KEY  = 'skqa_user';
const TENANT_KEY = 'skqa_tenant';

function getToken(){ return localStorage.getItem(TOKEN_KEY) || ''; }
function getUser(){ try { return JSON.parse(localStorage.getItem(USER_KEY) || 'null'); } catch { return null; } }
function setSession(token, user){ localStorage.setItem(TOKEN_KEY, token); localStorage.setItem(USER_KEY, JSON.stringify(user)); renderAuth(); }
function clearSession(){ localStorage.removeItem(TOKEN_KEY); localStorage.removeItem(USER_KEY); renderAuth(); }
function getTenant(){ return localStorage.getItem(TENANT_KEY) || String((getUser() && getUser().tenantId) || 1001); }
function setTenant(t){ localStorage.setItem(TENANT_KEY, t); }

// ── API 客户端：自动带 token；401 时清会话并弹登录框 ──
async function api(path, {method='GET', body=null, form=null, timeoutMs=180000, auth=true} = {}){
  const headers = {};
  if (body) headers['Content-Type'] = 'application/json';
  if (auth && getToken()) headers['Authorization'] = 'Bearer ' + getToken();

  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch('/api' + path, {
      method, headers, signal: ctrl.signal,
      body: form ? form : (body ? JSON.stringify(body) : undefined)
    });
    const text = await res.text();
    let data; try { data = text ? JSON.parse(text) : null; } catch { data = { raw: text }; }
    if (res.status === 401) { clearSession(); openLogin(); }
    if (!res.ok) {
      const msg = (data && (data.error || data.message || data.detail || data.raw)) || ('HTTP ' + res.status);
      throw new Error(typeof msg === 'string' ? msg : JSON.stringify(msg));
    }
    return data;
  } finally { clearTimeout(timer); }
}

// 评测服务是旁路，不进网关
async function metric(path, {method='POST', body=null, timeoutMs=300000} = {}){
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch('/metric' + path, {
      method, signal: ctrl.signal,
      headers: body ? { 'Content-Type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined
    });
    const text = await res.text();
    let data; try { data = text ? JSON.parse(text) : null; } catch { data = { raw: text }; }
    if (!res.ok) throw new Error((data && (data.detail || data.error)) || ('HTTP ' + res.status));
    return data;
  } finally { clearTimeout(timer); }
}

async function login(username, password){
  const r = await api('/auth/login', { method:'POST', body:{ username, password }, auth:false });
  setSession(r.token, { username: r.username, role: r.role, tenantId: r.tenantId });
  return r;
}
function logout(){ clearSession(); }

// ── 登录弹窗（两个页面共用，由 JS 注入）──
const SEED_ACCOUNTS = [
  { u: 'admin',  p: 'admin123',  d: '管理员' },
  { u: 'editor', p: 'editor123', d: '编辑' },
  { u: 'guest',  p: 'guest123',  d: '访客（只读）' }
];

function ensureLoginModal(){
  if (el('loginOverlay')) return;
  const div = document.createElement('div');
  div.id = 'loginOverlay';
  div.className = 'overlay';
  div.innerHTML = `
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="loginTitle">
      <h3 id="loginTitle">登录</h3>
      <div class="sub">登录后才能上传文档、回滚版本等写操作</div>

      <div class="field">
        <label for="lu">用户名</label>
        <input type="text" id="lu" autocomplete="username" placeholder="admin">
      </div>

      <div class="field">
        <label for="lp">密码</label>
        <div class="pwd">
          <input type="password" id="lp" autocomplete="current-password" placeholder="••••••••">
          <button class="eye" id="eye" type="button" title="显示/隐藏密码">👁</button>
        </div>
      </div>

      <div class="accts">
        ${SEED_ACCOUNTS.map(a => `<span class="acct" data-u="${a.u}" data-p="${a.p}">${a.u} · ${a.d}</span>`).join('')}
      </div>

      <div class="err" id="loginErr"></div>

      <div class="actions">
        <button class="ghost" id="closeLogin">取消</button>
        <button class="primary" id="doLogin">登录</button>
      </div>
    </div>`;
  document.body.appendChild(div);

  // 点遮罩关闭（点内容区不关）
  div.addEventListener('mousedown', e => { if (e.target === div) closeLogin(); });

  // 快捷填入种子账号
  div.querySelectorAll('.acct').forEach(c => c.onclick = () => {
    el('lu').value = c.dataset.u;
    el('lp').value = c.dataset.p;
    el('loginErr').textContent = '';
    el('lp').focus();
  });

  // 显示/隐藏密码
  el('eye').onclick = () => {
    const input = el('lp');
    const show = input.type === 'password';
    input.type = show ? 'text' : 'password';
    el('eye').textContent = show ? '🙈' : '👁';
  };

  el('closeLogin').onclick = closeLogin;
  el('doLogin').onclick = submitLogin;

  // Enter 提交；Esc 关闭
  ['lu', 'lp'].forEach(id => el(id).addEventListener('keydown', e => {
    if (e.key === 'Enter') { e.preventDefault(); submitLogin(); }
  }));
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && div.classList.contains('show')) closeLogin();
  });
}

function openLogin(){
  ensureLoginModal();
  const div = el('loginOverlay');
  div.classList.add('show');
  const saved = localStorage.getItem('skqa_last_user') || 'admin';
  if (!el('lu').value) el('lu').value = saved;
  el('loginErr').textContent = '';
  setTimeout(() => el('lu').value ? el('lp').focus() : el('lu').focus(), 30);
}

function closeLogin(){
  const div = el('loginOverlay');
  if (div) div.classList.remove('show');
  const btn = el('doLogin');
  if (btn) { btn.disabled = false; btn.textContent = '登录'; }
}

async function submitLogin(){
  const username = el('lu').value.trim();
  const password = el('lp').value;
  if (!username || !password) { el('loginErr').textContent = '请输入用户名和密码'; return; }

  const btn = el('doLogin');
  btn.disabled = true;
  btn.innerHTML = '<span class="spin dark"></span>登录中…';
  el('loginErr').textContent = '';
  try {
    await login(username, password);
    localStorage.setItem('skqa_last_user', username);
    closeLogin();
    if (window.__onLogin) window.__onLogin();
  } catch (e) {
    el('loginErr').textContent = e.message || '登录失败';
    btn.disabled = false;
    btn.textContent = '登录';
  }
}
// 顶栏身份区
function renderAuth(){
  const box = el('authBox');
  if (!box) return;
  const u = getUser();
  if (u) {
    box.innerHTML = `<span class="badge ok">${esc(u.username)} · ${esc(u.role)}</span>
      <button class="ghost" id="logoutBtn" style="margin-left:8px;padding:5px 12px">退出</button>`;
    const btn = el('logoutBtn');
    if (btn) btn.onclick = () => { logout(); if (window.__onLogout) window.__onLogout(); };
  } else {
    box.innerHTML = `<button class="ghost" id="loginBtn" style="padding:5px 12px">登录</button>`;
    const btn = el('loginBtn');
    if (btn) btn.onclick = openLogin;
  }
}

function el(id){ return document.getElementById(id); }
function fmt(ms){ return (ms/1000).toFixed(1) + ' s'; }
function esc(s){ return String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }

function initNav(defaultSection){
  const items = document.querySelectorAll('.nav-item[data-section]');
  const go = (key) => {
    items.forEach(i => i.classList.toggle('active', i.dataset.section === key));
    document.querySelectorAll('.section').forEach(s => s.classList.toggle('active', s.id === 'sec-' + key));
    const t = document.querySelector(`.nav-item[data-section="${key}"]`);
    if (t) el('page-title').textContent = t.dataset.title || t.textContent.trim();
    localStorage.setItem('skqa_section', key);
    if (window.__onSection) window.__onSection(key);
  };
  items.forEach(i => i.onclick = () => go(i.dataset.section));
  go(localStorage.getItem('skqa_section') || defaultSection);
  return go;
}

document.addEventListener('DOMContentLoaded', renderAuth);
// 极简 markdown：只处理模型答案里最常见的三种（粗体、行内代码、列表），
// 先转义再替换，避免 XSS；不做完整解析，够用即可。
function md(s){
  let h = esc(s);
  h = h.replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>');
  h = h.replace(/`([^`]+)`/g, '<code class="inline">$1</code>');
  h = h.replace(/^\s*[-*]\s+/gm, '• ');
  return h;
}

/* ── 主题：浅色 / 深色，跟随系统偏好，用户可手动切换 ── */
const THEME_KEY = 'skqa_theme';

function systemTheme(){
  return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}
function currentTheme(){
  return localStorage.getItem(THEME_KEY) || systemTheme();
}
function applyTheme(theme){
  document.documentElement.setAttribute('data-theme', theme);
  localStorage.setItem(THEME_KEY, theme);
  renderTheme();
}
function toggleTheme(){
  applyTheme(currentTheme() === 'dark' ? 'light' : 'dark');
}
function renderTheme(){
  const box = el('themeBox');
  if (!box) return;
  const dark = currentTheme() === 'dark';
  box.innerHTML = `<button class="icon-btn" id="themeBtn" title="${dark ? '切换到浅色' : '切换到深色'}">${dark ? '☀' : '☾'}</button>`;
  el('themeBtn').onclick = toggleTheme;
}
function initTheme(){
  // 支持 ?theme=dark|light 强制指定（演示、截图、分享链接用）
  const forced = new URLSearchParams(location.search).get('theme');
  if (forced === 'dark' || forced === 'light') {
    localStorage.setItem(THEME_KEY, forced);
  }
  // 先应用到 <html>，避免刷新时闪一下白屏
  document.documentElement.setAttribute('data-theme', currentTheme());
  renderTheme();
}
initTheme();
document.addEventListener('DOMContentLoaded', renderTheme);
// ?login=1 打开页面即弹登录框（演示、截图用）
document.addEventListener('DOMContentLoaded', () => {
  if (new URLSearchParams(location.search).get('login') === '1') openLogin();
});