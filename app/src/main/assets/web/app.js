'use strict';

// ---------- i18n ----------

const STRINGS = {
  en: {
    pairTitle: 'Connect to TV', pairHint: 'Enter the PIN shown on the TV screen', pairButton: 'Connect',
    pairAgain: 'Show the PIN again', wrongPin: 'Wrong PIN. Attempts left: {n}', newPin: 'Too many attempts — a new PIN is shown on the TV',
    expired: 'The PIN has expired — a new one is shown on the TV', offline: 'TV is not reachable',
    power: 'Power', keyboard: 'Keyboard', settings: 'Settings', up: 'Up', down: 'Down', left: 'Left', right: 'Right', ok: 'OK',
    back: 'Back', home: 'Home', menu: 'Options', volDown: 'Quieter', volUp: 'Louder', volume: 'Volume',
    keyboardTitle: 'Keyboard', keyboardPlaceholder: 'Type here — the text appears on the TV', clear: 'Clear', backspace: 'Backspace',
    enter: 'Enter', done: 'Done',
    appsTitle: 'Apps on the remote', favorites: 'On the remote', allApps: 'All apps', search: 'Search', noFavorites: 'Nothing yet — add apps below',
    edit: 'Edit', add: 'Add', remove: 'Remove', moveUp: 'Move up',
    adbRequired: 'This button needs ADB — see the TV app', failed: 'Command failed',
    limited: 'Limited mode: enable “Network debugging” on the TV for the D-pad and all buttons',
    waitingAuth: 'Confirm “Allow debugging” on the TV screen',
    nameLink: 'Permanent address {host} — works even if the IP changes. Open',
  },
  ru: {
    pairTitle: 'Подключение к ТВ', pairHint: 'Введите PIN-код с экрана телевизора', pairButton: 'Подключить',
    pairAgain: 'Показать PIN ещё раз', wrongPin: 'Неверный PIN. Осталось попыток: {n}', newPin: 'Слишком много попыток — на ТВ показан новый PIN',
    expired: 'PIN устарел — на ТВ показан новый', offline: 'ТВ недоступен',
    power: 'Выключить', keyboard: 'Клавиатура', settings: 'Настройки', up: 'Вверх', down: 'Вниз', left: 'Влево', right: 'Вправо', ok: 'OK',
    back: 'Назад', home: 'Домой', menu: 'Опции', volDown: 'Тише', volUp: 'Громче', volume: 'Громкость',
    keyboardTitle: 'Клавиатура', keyboardPlaceholder: 'Печатайте здесь — текст появится на ТВ', clear: 'Очистить', backspace: 'Стереть',
    enter: 'Ввод', done: 'Готово',
    appsTitle: 'Приложения на пульте', favorites: 'На пульте', allApps: 'Все приложения', search: 'Поиск', noFavorites: 'Пока пусто — добавьте приложения ниже',
    edit: 'Изменить', add: 'Добавить', remove: 'Убрать', moveUp: 'Выше',
    adbRequired: 'Для этой кнопки нужен ADB — см. приложение на ТВ', failed: 'Команда не выполнена',
    limited: 'Ограниченный режим: включите «Отладку по сети» на ТВ, чтобы работали крестовина и все кнопки',
    waitingAuth: 'Подтвердите «Разрешить отладку» на экране ТВ',
    nameLink: 'Постоянный адрес {host} — не сломается при смене IP. Открыть',
  },
};
const lang = (navigator.language || 'en').toLowerCase().startsWith('ru') ? 'ru' : 'en';
const t = (key, vars = {}) => (STRINGS[lang][key] || STRINGS.en[key] || key).replace(/\{(\w+)\}/g, (_, k) => vars[k]);

document.documentElement.lang = lang;
document.querySelectorAll('[data-i18n]').forEach((el) => { el.textContent = t(el.dataset.i18n); });
document.querySelectorAll('[data-i18n-label]').forEach((el) => { el.setAttribute('aria-label', t(el.dataset.i18nLabel)); el.title = t(el.dataset.i18nLabel); });
document.querySelectorAll('[data-i18n-placeholder]').forEach((el) => { el.placeholder = t(el.dataset.i18nPlaceholder); });

// ---------- helpers ----------

const $ = (sel) => document.querySelector(sel);
const vibrate = (ms = 12) => { if (navigator.vibrate) navigator.vibrate(ms); };

let toastTimer;
function toast(msg) {
  const el = $('#toast');
  el.textContent = msg;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 2600);
}

async function api(path, body) {
  const opts = body === undefined
    ? { cache: 'no-store' }
    : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
  const res = await fetch(path, opts);
  let data = null;
  try { data = await res.json(); } catch (_) { /* not JSON */ }
  if (res.status === 401) { showPairing(); throw new Error('not_paired'); }
  if (!res.ok) {
    const err = new Error((data && data.error) || res.statusText);
    err.data = data;
    throw err;
  }
  return data;
}

function reportError(e) {
  if (e.message === 'not_paired') return;
  if (e.message === 'adb_required') toast(t('adbRequired'));
  else if (e instanceof TypeError) toast(t('offline'));
  else toast(t('failed'));
}

// Commands are sent strictly in order so fast taps never arrive shuffled.
let queue = Promise.resolve();
function send(path, body) {
  queue = queue.then(() => api(path, body)).catch(reportError);
  return queue;
}
const key = (name, mode = 'click') => {
  const done = send('/api/key', { key: name, mode });
  if (name.startsWith('volume') || name === 'mute') done.then(scheduleVolumeRefresh);
  return done;
};

// ---------- pairing ----------

let status = null;

function showPairing() {
  $('#remote').hidden = true;
  $('#pair').hidden = false;
  $('#pair-device').textContent = status ? status.device : '';
  api('/api/pair/request', {}).catch(() => {});
  setTimeout(() => $('#pin').focus(), 50);
}

async function confirmPin() {
  const pin = $('#pin').value.trim();
  if (pin.length !== 4) return;
  $('#pair-error').textContent = '';
  $('#pair-go').disabled = true;
  try {
    await api('/api/pair/confirm', { pin, name: deviceLabel() });
    await start();
  } catch (e) {
    const d = e.data || {};
    if (e.message === 'wrong_pin') {
      $('#pair-error').textContent = d.attemptsLeft > 0 ? t('wrongPin', { n: d.attemptsLeft }) : t('newPin');
    } else if (e.message === 'expired') {
      $('#pair-error').textContent = t('expired');
      api('/api/pair/request', {}).catch(() => {});
    } else {
      $('#pair-error').textContent = t('offline');
    }
    $('#pin').value = '';
    $('#pin').focus();
  } finally {
    $('#pair-go').disabled = false;
  }
}

function deviceLabel() {
  const ua = navigator.userAgent;
  const m = ua.match(/\(([^)]+)\)/);
  return m ? m[1].split(';').slice(0, 2).join(';') : ua.slice(0, 60);
}

$('#pin').addEventListener('input', (e) => {
  e.target.value = e.target.value.replace(/\D/g, '').slice(0, 4);
  if (e.target.value.length === 4) confirmPin();
});
$('#pair-go').addEventListener('click', confirmPin);
$('#pair-again').addEventListener('click', () => api('/api/pair/request', {}).catch(() => toast(t('offline'))));

// ---------- remote buttons ----------

const REPEAT_DELAY = 380;
const REPEAT_EVERY = 110;
const LONG_PRESS = 550;

function bindKey(btn) {
  const name = btn.dataset.key;
  const repeat = btn.hasAttribute('data-repeat');
  const long = btn.hasAttribute('data-long');
  let timer = null;
  let downAt = 0;
  let active = false;

  const stop = () => {
    clearTimeout(timer);
    clearInterval(timer);
    timer = null;
    btn.classList.remove('pressed');
  };

  btn.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return;
    btn.setPointerCapture(e.pointerId);
    active = true;
    downAt = Date.now();
    btn.classList.add('pressed');
    vibrate();
    if (long) return; // decided on release: short click or long press
    key(name);
    if (repeat) {
      timer = setTimeout(() => { timer = setInterval(() => key(name), REPEAT_EVERY); }, REPEAT_DELAY);
    }
  });
  btn.addEventListener('pointerup', () => {
    if (!active) return;
    active = false;
    stop();
    if (long) {
      const isLong = Date.now() - downAt >= LONG_PRESS;
      if (isLong) vibrate(30);
      key(name, isLong ? 'long' : 'click');
    }
  });
  btn.addEventListener('pointercancel', () => { active = false; stop(); });
  // Keyboard / accessibility activation (no pointer events).
  btn.addEventListener('click', (e) => { if (e.detail === 0) key(name); });
  btn.addEventListener('contextmenu', (e) => e.preventDefault());
}

document.querySelectorAll('#remote [data-key]').forEach(bindKey);

// Physical keyboard on a computer works as a remote too.
const KEYMAP = {
  ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Enter: 'ok',
  Escape: 'back', Backspace: 'back', Home: 'home', ContextMenu: 'menu', '-': 'volume_down', '+': 'volume_up', '=': 'volume_up',
};
document.addEventListener('keydown', (e) => {
  if ($('#remote').hidden || openSheet || e.target.tagName === 'INPUT' || e.metaKey || e.ctrlKey || e.altKey) return;
  const name = KEYMAP[e.key];
  if (!name) return;
  e.preventDefault();
  key(name);
});

// ---------- sheets ----------

let openSheet = null;

function showSheet(id) {
  closeSheet();
  openSheet = $(id);
  $('#backdrop').hidden = false;
  openSheet.hidden = false;
}

function closeSheet() {
  if (!openSheet) return;
  openSheet.hidden = true;
  $('#backdrop').hidden = true;
  if (document.activeElement) document.activeElement.blur();
  openSheet = null;
}

$('#backdrop').addEventListener('click', closeSheet);
document.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', closeSheet));
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && openSheet) closeSheet(); });

// ---------- keyboard ----------

const kb = $('#kb-input');
let lastText = '';

$('#btn-keyboard').addEventListener('click', () => {
  vibrate();
  kb.value = '';
  lastText = '';
  showSheet('#sheet-keyboard');
  kb.focus(); // must happen inside the tap handler for iOS to open the keyboard
});

// The phone field mirrors the TV field: each change is sent as the full text plus an
// equivalent "delete N chars, then type X" edit for backends that can only press keys.
let textTimer;
kb.addEventListener('input', () => {
  clearTimeout(textTimer);
  textTimer = setTimeout(syncText, 120);
});

function syncText() {
  const cur = kb.value;
  if (cur === lastText) return;
  const a = Array.from(lastText);
  const b = Array.from(cur);
  let p = 0;
  while (p < a.length && p < b.length && a[p] === b[p]) p++;
  send('/api/text', { full: cur, deleteCount: a.length - p, append: b.slice(p).join('') });
  lastText = cur;
}

kb.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    e.preventDefault();
    syncText();
    key('enter');
  } else if (e.key === 'Backspace' && kb.value === '') {
    key('del');
  }
});

$('#kb-enter').addEventListener('click', () => { vibrate(); syncText(); key('enter'); kb.focus(); });
$('#kb-clear').addEventListener('click', () => { vibrate(); kb.value = ''; syncText(); kb.focus(); });

// Backspace button: edits the local field when it has text, otherwise deletes on the TV.
(function bindDel() {
  const btn = $('#kb-del');
  let timer = null;
  const press = () => {
    if (kb.value) {
      kb.value = Array.from(kb.value).slice(0, -1).join('');
      syncText();
    } else {
      key('del');
    }
  };
  const stop = () => { clearTimeout(timer); clearInterval(timer); timer = null; };
  btn.addEventListener('pointerdown', (e) => {
    e.preventDefault(); // keep focus (and the on-screen keyboard) on the input
    vibrate();
    press();
    timer = setTimeout(() => { timer = setInterval(press, REPEAT_EVERY); }, REPEAT_DELAY);
  });
  ['pointerup', 'pointercancel', 'pointerleave'].forEach((ev) => btn.addEventListener(ev, stop));
}());

// ---------- apps ----------

let favorites = [];
let allApps = null;
const iconUrl = (pkg) => `/api/apps/icon?pkg=${encodeURIComponent(pkg)}`;

function renderStrip() {
  const strip = $('#apps');
  strip.textContent = '';
  favorites.forEach((app) => {
    const b = document.createElement('button');
    b.className = 'app';
    b.innerHTML = '<div class="tile"><img alt="" loading="lazy"></div><span></span>';
    b.querySelector('img').src = iconUrl(app.pkg);
    b.querySelector('span').textContent = app.label;
    b.addEventListener('click', () => { vibrate(); send('/api/launch', { pkg: app.pkg }); });
    strip.appendChild(b);
  });
  const edit = document.createElement('button');
  edit.className = 'app edit';
  edit.innerHTML = '<div class="tile"><svg><use href="#i-edit"/></svg></div><span></span>';
  edit.querySelector('span').textContent = t('edit');
  edit.addEventListener('click', openAppsEditor);
  strip.appendChild(edit);
}

async function loadFavorites() {
  favorites = await api('/api/favorites');
  renderStrip();
}

async function saveFavorites() {
  renderStrip();
  renderEditor();
  try {
    favorites = await api('/api/favorites', { pkgs: favorites.map((a) => a.pkg) });
  } catch (e) { reportError(e); }
}

function listItem(app, buttons) {
  const li = document.createElement('li');
  li.innerHTML = '<img alt="" loading="lazy"><span class="name"></span>';
  li.querySelector('img').src = iconUrl(app.pkg);
  li.querySelector('.name').textContent = app.label;
  buttons.forEach(({ icon, label, onClick, disabled }) => {
    const b = document.createElement('button');
    b.innerHTML = `<svg><use href="#${icon}"/></svg>`;
    b.setAttribute('aria-label', label);
    b.disabled = !!disabled;
    b.addEventListener('click', onClick);
    li.appendChild(b);
  });
  return li;
}

function renderEditor() {
  const fav = $('#fav-list');
  fav.textContent = '';
  if (!favorites.length) {
    const li = document.createElement('li');
    li.className = 'empty';
    li.textContent = t('noFavorites');
    fav.appendChild(li);
  }
  favorites.forEach((app, i) => {
    fav.appendChild(listItem(app, [
      { icon: 'i-up', label: t('moveUp'), disabled: i === 0, onClick: () => { favorites.splice(i - 1, 0, favorites.splice(i, 1)[0]); saveFavorites(); } },
      { icon: 'i-x', label: t('remove'), onClick: () => { favorites.splice(i, 1); saveFavorites(); } },
    ]));
  });

  const all = $('#all-list');
  all.textContent = '';
  const q = $('#app-search').value.trim().toLowerCase();
  const chosen = new Set(favorites.map((a) => a.pkg));
  (allApps || [])
    .filter((a) => !chosen.has(a.pkg) && (!q || a.label.toLowerCase().includes(q) || a.pkg.includes(q)))
    .forEach((app) => {
      all.appendChild(listItem(app, [
        { icon: 'i-plus', label: t('add'), onClick: () => { favorites.push(app); saveFavorites(); } },
      ]));
    });
}

async function openAppsEditor() {
  vibrate();
  $('#app-search').value = '';
  showSheet('#sheet-apps');
  renderEditor();
  try {
    allApps = await api('/api/apps');
    renderEditor();
  } catch (e) { reportError(e); }
}

$('#app-search').addEventListener('input', renderEditor);

// ---------- volume slider ----------

const slider = $('#volume');
let volumeTouchedAt = 0;
let volumeBusy = false;
let volumePending = null;
let volumeTimer;

function renderVolume(v) {
  // Hidden when the box has fixed output volume (the TV/receiver controls it); buttons still work.
  $('.volume').classList.toggle('has-slider', !v.fixed && v.max > 0);
  slider.max = v.max;
  // Don't fight the finger: ignore server values while the user is dragging.
  if (Date.now() - volumeTouchedAt > 800) slider.value = v.level;
  paintSlider();
}

function paintSlider() {
  const max = Number(slider.max) || 1;
  slider.style.setProperty('--fill', `${(Number(slider.value) / max) * 100}%`);
}

async function loadVolume() {
  try { renderVolume(await api('/api/volume')); } catch (_) { /* keep the last state */ }
}

function scheduleVolumeRefresh() {
  clearTimeout(volumeTimer);
  volumeTimer = setTimeout(loadVolume, 350);
}

// Latest value wins: while a request is in flight only the newest position is kept.
async function pumpVolume() {
  volumeBusy = true;
  while (volumePending !== null) {
    const level = volumePending;
    volumePending = null;
    try { await api('/api/volume', { level }); } catch (e) { reportError(e); }
  }
  volumeBusy = false;
}

slider.addEventListener('input', () => {
  volumeTouchedAt = Date.now();
  paintSlider();
  volumePending = Number(slider.value);
  if (!volumeBusy) pumpVolume();
});
slider.addEventListener('change', () => { volumeTouchedAt = Date.now(); vibrate(8); });

// ---------- permanent address ----------

// Opened by IP? If this device can resolve the TV's mDNS name (tv-remote.local), offer it:
// a bookmark or home-screen icon with that address survives IP changes. Not every phone
// resolves .local, so it is offered only after a successful probe.
let nameLinkChecked = false;
async function offerNameLink() {
  if (nameLinkChecked || !status.nameUrl) return;
  const target = new URL(status.nameUrl);
  if (location.hostname === target.hostname) return;
  nameLinkChecked = true;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 3000);
  try {
    await fetch(`${status.nameUrl}/icon.svg`, { mode: 'no-cors', cache: 'no-store', signal: ctrl.signal });
  } catch (_) {
    return; // name does not resolve here: keep using the IP
  } finally {
    clearTimeout(timer);
  }
  const link = $('#name-link');
  link.href = status.nameUrl;
  link.textContent = t('nameLink', { host: target.host });
  link.hidden = false;
}

// ---------- status ----------

function renderStatus() {
  $('#device').textContent = status.device;
  const dot = $('#dot');
  const full = status.adb === 'connected';
  dot.className = 'dot ' + (full ? 'good' : status.adb === 'unavailable' ? 'bad' : 'warn');
  const limited = $('#limited');
  if (full) {
    limited.hidden = true;
  } else {
    limited.hidden = false;
    limited.textContent = status.adb === 'waiting_auth' ? t('waitingAuth') : t('limited');
  }
}

async function refreshStatus() {
  try {
    status = await api('/api/status');
    if (!status.paired) { showPairing(); return; }
    renderStatus();
    loadVolume();
  } catch (e) {
    if (e instanceof TypeError) $('#dot').className = 'dot bad';
  }
}

async function start() {
  try {
    status = await api('/api/status');
  } catch (e) {
    toast(t('offline'));
    setTimeout(start, 3000);
    return;
  }
  if (!status.paired) { showPairing(); return; }
  $('#pair').hidden = true;
  $('#remote').hidden = false;
  renderStatus();
  loadVolume();
  loadFavorites().catch(reportError);
  offerNameLink();
}

setInterval(() => { if (!document.hidden && !$('#remote').hidden) refreshStatus(); }, 10000);
document.addEventListener('visibilitychange', () => { if (!document.hidden && !$('#remote').hidden) refreshStatus(); });

// A service worker is required for "Install app" in Chrome; browsers only allow it on secure
// origins (https or a flag, see README). iOS "Add to Home Screen" works without it.
if ('serviceWorker' in navigator && window.isSecureContext) {
  navigator.serviceWorker.register('/sw.js').catch(() => {});
}

start();
