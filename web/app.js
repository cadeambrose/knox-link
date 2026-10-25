/* Knox Link — second screen for Project Zomboid. The shell: connection, tabs, top bar, the map and
   its sheets, alerts, sheets and toasts. Plain JavaScript, no build step, nothing loaded from
   outside the game. */
'use strict';

const S = { core: null, lastCoreAt: 0 };
let tab = 'map';
let autoCarFrom = null, carOpened = false;
let watch = null;

// ---------------------------------------------------------------- connection

let ws = null, wsReady = false, authed = false, retry = 0, nextCmd = 1;
const pending = new Map();

function pinFromHash() {
  const m = location.hash.match(/pin=(\d{4})/);
  if (m) store('kl.pin', m[1]);
  const t = location.hash.match(/tab=(\w+)/);
  if (m || t) history.replaceState(null, '', location.pathname + location.search);
  return t ? t[1] : null;
}

function connect() {
  const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
  try { ws = new WebSocket(url); } catch (e) { scheduleReconnect(); return; }
  ws.onopen = () => {
    wsReady = true;
    retry = 0;
    send({ t: 'hello', pin: load('kl.pin') || '', client: navigator.userAgent.slice(0, 80) });
  };
  ws.onmessage = (ev) => {
    let m;
    try { m = JSON.parse(ev.data); } catch (e) { return; }
    onMessage(m);
  };
  ws.onclose = () => {
    wsReady = false;
    authed = false;
    setConn(false);
    scheduleReconnect();
  };
  ws.onerror = () => { try { ws.close(); } catch (e) { /* ignore */ } };
}

function scheduleReconnect() {
  const delay = Math.min(5000, 400 * Math.pow(1.6, retry++));
  setTimeout(connect, delay);
  setTimeout(() => {
    if (!authed) showOverlay('wait', 'Waiting for the game…', 'Start Project Zomboid with Knox Link enabled. This screen connects by itself.');
  }, 1200);
}

function send(obj) {
  if (ws && wsReady) ws.send(JSON.stringify(obj));
}

/**
 * Asks the game to do something; the label is toasted when it says yes (null: say nothing then).
 * quiet: for a stream of small commands (turning the character), where one toast per step would flood.
 * done(ok, msg): called with the game's answer (or when it didn't answer).
 */
function command(cmd, args = {}, label, quiet = false, done = null) {
  if (!authed) {
    if (!quiet) toast('Not connected to the game', true);
    if (done) done(false, 'Not connected to the game');
    return;
  }
  const id = nextCmd++;
  pending.set(id, { label: quiet ? null : (label || ''), done });
  send({ t: 'cmd', id, cmd, args });
  setTimeout(() => {
    if (!pending.has(id)) return;
    const p = pending.get(id);
    pending.delete(id);
    if (p.label !== null) toast('The game did not answer', true);
    if (p.done) p.done(false, 'The game did not answer');
  }, 6000);
}

let subscribedMp = false;

/** Each tab follows only what it shows; "core" always comes. */
function subscribe() {
  const c = [];
  if (tab === 'vitals') c.push('body', 'outfit');
  const avatar = tab === 'vitals' && Vitals.wantsAvatar();
  if (avatar) c.push('avatar');   // the game draws the live character only while someone watches
  if (tab === 'gear') {
    c.push('gear', 'outfit', 'loot');
    if (Gear.seg === 'craft') c.push('craft');
  }
  if (tab === 'journal') c.push('journal');
  if (tab === 'skills') c.push('skills');
  if (tab === 'map') c.push('marks', 'team');
  if (tab === 'comms') c.push('radio');
  if (tab === 'base') c.push('base');
  // multiplayer chat is followed everywhere, for its alerts
  const mp = !!(S.core && S.core.game && S.core.game.mp);
  if (mp || tab === 'comms') c.push('chat');
  c.push('menu');    // the game's own menus, sent once each when a screen asks for one
  c.push('radar');   // the danger radar shows on every tab
  subscribedMp = mp;
  send({ t: 'sub', c });
  if (avatar) avatarPoll();
}
function resubscribe() { if (authed) subscribe(); }

function onMessage(m) {
  switch (m.t) {
    case 'hello':
      if (m.ok) {
        authed = true;
        setConn(true);
        $('#verText').textContent = m.version || '';
        $('#connInfo').textContent = location.host + (m.local ? ' (this device)' : '');
        KnoxArt.retry();
        Radar.reset();   // maybe another game: nothing of the last one stays on the radar
        subscribe();
        loadSymbols(true);
        if (!S.core || Date.now() - S.lastCoreAt > 3000) showOverlay('wait', 'Waiting for a game…', 'Load a save or start a new game. This screen fills in by itself.');
        else hideOverlay();
      } else if (m.why === 'locked') {
        showOverlay('pin', null, 'Too many wrong PINs. Wait a minute, then try again.');
      } else {
        showOverlay('pin', null, m.why === 'wrong' ? 'That PIN did not match. Check the Knox Link panel in the game (F8).' : null);
        if (m.why === 'wrong') { const f = $('#pinForm'); f.classList.remove('shake'); void f.offsetWidth; f.classList.add('shake'); }
      }
      break;
    case 's':
      onState(m.c, m.d);
      break;
    case 'ack': {
      const p = pending.get(m.id);
      pending.delete(m.id);
      if (!p) break;
      if (p.done) p.done(!!m.ok, m.msg || '');
      const label = p.label;
      if (label === null) break;
      if (m.ok) { if (label) toast(label + (m.msg ? ` · ${m.msg}` : '')); }
      else toast(m.msg || 'Could not do that', true);
      break;
    }
    case 'e':
      if (m.d && m.d.toast) toast(m.d.toast, !!m.d.bad);
      break;
    default:
      break;
  }
}

function onState(channel, d) {
  if (!d) return;
  S[channel] = d;
  switch (channel) {
    case 'core':
      S.lastCoreAt = Date.now();
      if (authed) hideOverlay();
      onCore(d);
      break;
    case 'body': Vitals.body(d); break;
    case 'gear': Gear.gear(d); break;
    case 'skills': Skills.skills(d); break;
    case 'marks': onMarks(d); break;
    case 'outfit': Vitals.outfit(d); Gear.outfit(d); break;
    case 'loot': Gear.loot(d); break;
    case 'radar': Radar.update(d); break;
    case 'menu': GameMenu.onMenu(d); break;   // the game's own menu for something a screen asked about
    case 'craft': Gear.craft(d); break;
    case 'journal': Journal.journal(d); break;
    case 'team': KnoxMap.setTeam(arr(d.list)); break;
    case 'radio': Comms.radio(d); break;
    case 'chat': onChat(d); break;
    case 'base': Base.base(d); break;
    default: break;
  }
}

// a game on its main menu stops sending; say so instead of showing frozen numbers
setInterval(() => {
  if (authed && S.lastCoreAt && Date.now() - S.lastCoreAt > 4000 && $('#overlay').classList.contains('hide')) {
    showOverlay('wait', 'Waiting for a game…', 'Knox Link is connected. Load a save or start a new game.');
  }
}, 1000);

function setConn(ok) {
  $('#conn').classList.toggle('ok', ok);
  $('#conn').classList.toggle('bad', !ok);
  $('#conn').title = ok ? 'Connected' : 'Not connected';
}

function showOverlay(mode, title, sub) {
  const ov = $('#overlay');
  ov.classList.remove('hide');
  $('#overlayWait').hidden = mode !== 'wait';
  $('#pinForm').hidden = mode !== 'pin';
  if (mode === 'wait') {
    if (title) $('#ovTitle').textContent = title;
    if (sub != null) $('#ovSub').textContent = sub;
  } else {
    $('#pinSub').textContent = sub || 'It is shown in the game: press F8 to open the Knox Link panel.';
    setTimeout(() => $('#pinInput').focus(), 50);
  }
}
function hideOverlay() { $('#overlay').classList.add('hide'); }

$('#pinForm').addEventListener('submit', (e) => {
  e.preventDefault();
  const v = $('#pinInput').value.trim();
  if (!/^\d{4}$/.test(v)) return;
  store('kl.pin', v);
  $('#pinInput').value = '';
  showOverlay('wait', 'Connecting…', '');
  send({ t: 'hello', pin: v });
});
$('#pinInput').addEventListener('input', (e) => {
  e.target.value = e.target.value.replace(/\D/g, '').slice(0, 4);
  if (e.target.value.length === 4) $('#pinForm').requestSubmit();
});
$('#ovSettings').addEventListener('click', () => { hideOverlay(); setTab('settings'); });

// ---------------------------------------------------------------- tabs

const TABS = [
  { k: 'map', label: 'Map', icon: 'map', art: 'Map' },
  { k: 'vitals', label: 'Vitals', icon: 'vitals', art: 'Heart' },
  { k: 'gear', label: 'Gear', icon: 'gear', art: 'Inventory' },
  { k: 'skills', label: 'Skills', icon: 'skills', more: true },
  { k: 'comms', label: 'Radio', icon: 'radio' },
  { k: 'base', label: 'Base', icon: 'home', more: true },
  { k: 'journal', label: 'Journal', icon: 'book', more: true },
  { k: 'car', label: 'Car', icon: 'car' },
];

function buildRail() {
  // the game's own sidebar icons; the plain icon stays until they arrive
  const side = (n, s) => KnoxArt.mediaUrl(`ui/Sidebar/128/${n}_${s}_128.png`);
  let html = TABS.map((t) => `<button data-tab="${t.k}" class="${t.more ? 'in-more' : ''}" ${t.k === 'car' ? 'hidden' : ''} aria-label="${t.label}">
      <span class="rb-ico${t.art ? ' wide' : ''}">${icon(t.icon)}${t.art ? `<img class="art off" alt="" src="${side(t.art, 'Off')}"><img class="art on" alt="" src="${side(t.art, 'On')}">` : ''}<i class="badge" hidden></i></span>
      <span class="rb-label">${t.label}</span></button>`).join('');
  html += `<div class="rail-spacer"></div>
    <button id="fsRail" class="rail-fs in-more" aria-label="Full screen" hidden><span class="rb-ico">${icon('fullscreen')}</span><span class="rb-label">Full screen</span></button>
    <button data-tab="settings" class="in-more rb-settings" aria-label="Settings"><span class="rb-ico">${icon('settings')}</span><span class="rb-label">Settings</span></button>
    <button id="moreBtn" class="rb-more" aria-label="More"><span class="rb-ico">${icon('more')}<i class="badge" hidden></i></span><span class="rb-label">More</span></button>`;
  $('#rail').innerHTML = html;
  $('#rail').addEventListener('click', (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    if (b.id === 'moreBtn') moreSheet();
    else if (b.id === 'fsRail') toggleFullscreen();
    else if (b.dataset.tab) setTab(b.dataset.tab);
  });
}

/** Phones in portrait: the tabs that don't fit the bottom bar. */
function moreSheet() {
  const items = [...TABS.filter((t) => t.more), { k: 'settings', label: 'Settings', icon: 'settings' }];
  const fs = !$('#fsRail').hidden;
  openSheet(sheetHead('More') + `<div class="more-grid">${items.map((t) =>
    `<button class="more-item${t.k === tab ? ' on' : ''}" data-act="tab" data-tab="${t.k}">${icon(t.icon)}<span>${t.label}</span></button>`).join('')}
    ${fs ? `<button class="more-item" data-act="fs">${icon('fullscreen')}<span>Full screen</span></button>` : ''}</div>`, {
    tab: (b) => { closeSheet(); setTab(b.dataset.tab); },
    fs: () => { closeSheet(); toggleFullscreen(); },
  });
}

function setTab(key) {
  if (!$('#view-' + key)) key = 'map';
  if (key !== 'car') autoCarFrom = null;
  tab = key;
  $$('.view').forEach((v) => v.classList.toggle('active', v.id === 'view-' + key));
  $$('#rail [data-tab]').forEach((b) => b.classList.toggle('active', b.dataset.tab === key));
  $('#moreBtn').classList.toggle('active', !!$(`#rail [data-tab="${key}"].in-more`));
  subscribe();
  if (key === 'map') { KnoxMap.redraw(); if (fogWanted) fogPoll(); }
  if (key === 'comms') { updateCommsBadge(); if (Comms.modeNow() === 'chat') Comms.scrollChat(); }
  store('kl.tab', key);
}

function setBadge(key, on) {
  const b = $(`#rail [data-tab="${key}"] .badge`);
  if (b) b.hidden = !on;
}

function setRailLabel(key, label) {
  const b = $(`#rail [data-tab="${key}"]`);
  const l = b && $('.rb-label', b);
  if (l && l.textContent !== label) { l.textContent = label; b.setAttribute('aria-label', label); }
}

// ---------------------------------------------------------------- top bar

const SPEED_ART = ['Pause', 'Play', 'FFwd1', 'FFwd2'];

function buildTopBar() {
  watch = Watch($('#watch'));
  // the game's own speed buttons (media/ui/speedControls), with plain icons until they arrive
  $$('#speed button').forEach((b, i) => {
    const a = (s) => KnoxArt.mediaUrl(`ui/speedControls/${SPEED_ART[i]}_${s}.png`);
    b.insertAdjacentHTML('beforeend', `<img class="art off" alt="" src="${a('Off')}"><img class="art on" alt="" src="${a('On')}">`);
  });
  $('#speed').addEventListener('click', (e) => {
    const b = e.target.closest('button[data-speed]');
    if (!b) return;
    const s = Number(b.dataset.speed);
    markSpeed(s);   // instant feedback
    command('speed', { s }, null);
  });
  $('#stopBtn').addEventListener('click', () => command('stop', {}, 'Stopped'));
  // the weather: tap for the forecast heard on the radio
  $('#info').addEventListener('click', () => Forecast.sheet(S.core));
}

function markSpeed(s) {
  $$('#speed button').forEach((b) => b.classList.toggle('on', Number(b.dataset.speed) === s));
}

function weatherOf(c) {
  const wx = c.wx;
  const h = c.time ? c.time.h : 12;
  const night = h < 6 || h >= 20;
  if (wx.storm) return ['storm', 'Thunderstorm'];
  if (wx.snow > 0.1) return ['snow', 'Snow'];
  if (wx.rain > 0.1) return ['rain', wx.rain > 0.6 ? 'Heavy rain' : 'Rain'];
  if (wx.fog > 0.35) return ['fog', 'Fog'];
  if (wx.cloud > 0.6) return ['cloud', 'Cloudy'];
  return night ? ['moon', 'Clear night'] : ['sun', 'Clear'];
}

function renderTop(c) {
  const time = c.time, wx = c.wx, pl = c.p || {};
  const h24 = settings.clock === '24', f = settings.units === 'imperial';
  // Immersion: no watch -> the watch stays dark; analog -> time only; digital -> time, date, temperature
  if (time) watch.set(time.h, time.mi, time.mo || 0, time.d || 0, wx && wx.t != null ? wx.t : null, h24, f);
  else watch.set(-1, 0, 0, 0, null, h24, f);
  $('#infoTop').textContent = time && time.ds != null ? 'Day ' + (Math.floor(time.ds) + 1) : !time ? 'No watch' : (pl.name || '');
  if (wx) {
    const [ic, word] = weatherOf(c);
    $('#wxIcon use').setAttribute('href', '#i-' + ic);
    const imm = !!(c.rules && c.rules.imm);
    let wind = '';
    if (wx.wind != null && imm) wind = ' · windy';
    else if (wx.wind >= 1) wind = ' · wind ' + (f ? Math.round(wx.wind * 0.621) + ' mph' : Math.round(wx.wind) + ' km/h');
    $('#infoSub').textContent = word + wind;
  }
  const g = c.game;
  if (g) {
    $('#speed').classList.toggle('disabled', !!g.mp);
    markSpeed(Math.min(3, g.speed == null ? 1 : g.speed));
  }
}

// ---------------------------------------------------------------- core

let forecastSig = '';

function onCore(c) {
  renderTop(c);
  // the world's rules for the map: none at all, too dark to read, or only what was explored
  const mr = c.rules && c.rules.map;
  const allowed = !mr || mr.allowed !== false;
  const mdark = !!(mr && mr.dark);
  const wantFog = allowed && !!mr && mr.allKnown === false;
  KnoxMap.setDark(mdark);
  $('#mapNotice').hidden = allowed && !mdark;
  if (!allowed || mdark) $('#mapNoticeText').textContent = !allowed ? "The world map is turned off in this world's settings." : 'Too dark to read the map.';
  $('#mapCanvas').style.visibility = allowed ? '' : 'hidden';
  const wasFog = fogWanted;
  fogWanted = wantFog;
  if (wantFog && !wasFog) fogPoll();
  if (!wantFog && wasFog) KnoxMap.setFog(null);

  const p = c.p;
  if (p) KnoxMap.setPlayer(p);
  KnoxMap.setWaypoint(c.wp);
  renderWaypoint(c);
  Vitals.core(c);
  Gear.core(c);
  Car.core(c);
  Base.core(c);
  Game.perf(c);
  KnoxMap.setBase(c.base || null);
  const mp = !!(c.game && c.game.mp);
  Comms.setMp(mp);
  setRailLabel('comms', mp ? 'Comms' : 'Radio');
  if (mp !== subscribedMp) resubscribe();
  updateCommsBadge();
  const fs = JSON.stringify(c.fc || null) + (c.di || 0) + settings.units + settings.clock;
  if (fs !== forecastSig) {
    forecastSig = fs;
    Comms.setForecast(Forecast.card(c));
  }
  checkAlerts(c);
  const bleeding = arr(c.m).some((m) => m.id === 'BLEEDING');
  $('#rail [data-tab="vitals"]').classList.toggle('alarm', bleeding);

  const veh = c.veh;
  $('#rail [data-tab="car"]').hidden = !veh;
  // the dashboard opens once when you start driving; leaving it for another tab is respected
  const driving = !!(veh && veh.drv);
  if (driving && !carOpened && settings.autoCar && tab !== 'car') {
    const from = tab;
    setTab('car');
    autoCarFrom = from;
  }
  carOpened = driving;
  if (!veh && tab === 'car') {
    const back = autoCarFrom || 'map';
    autoCarFrom = null;
    setTab(back);
  }
}

// ---------------------------------------------------------------- map

let fogWanted = false, fogTimer = 0;

/** Refreshes the explored-area mask while the map is on screen. */
async function fogPoll() {
  clearTimeout(fogTimer);
  if (fogWanted && authed && tab === 'map') {
    try {
      const r = await fetch('/api/map/fog', { cache: 'no-store' });
      if (r.ok && fogWanted) KnoxMap.setFog(await r.arrayBuffer());
    } catch (e) { /* try again later */ }
  }
  if (fogWanted) fogTimer = setTimeout(fogPoll, 8000);
}

function renderWaypoint(c) {
  const wp = c.wp, p = c.p;
  const chip = $('#wpChip');
  if (!wp || !p) { chip.hidden = true; return; }
  const dx = wp.x - p.x, dy = wp.y - p.y;
  const d = Math.hypot(dx, dy);
  chip.hidden = false;
  $('#wpText').textContent = d < 6 ? 'You are there' : `${wp.label || 'Waypoint'} · ${Fmt.dist(d)} ${Fmt.compass(dx, dy)}`;
}

let placeTimer = 0;

function initMap() {
  KnoxMap.init($('#mapCanvas'), {
    onLongPress(x, y) { hideMapHint(); spotSheet(x, y); },
    onThing(kind, thing) {
      if (kind === 'mark') markSheet(thing);
      else if (kind === 'team') teamSheet(thing);
      else baseSheet();
    },
    onBuilding(name) {
      const chip = $('#placeChip');
      clearTimeout(placeTimer);
      if (!name) { chip.hidden = true; return; }
      chip.textContent = name;
      chip.hidden = false;
      placeTimer = setTimeout(() => { chip.hidden = true; }, 3500);
    },
    onFollow(on) { $('#follow').classList.toggle('on', on); },
  });
  KnoxMap.load('/api/map', (msg) => { $('#mapStatus').textContent = msg; $('#mapStatus').hidden = false; })
    .then(() => { $('#mapStatus').hidden = true; })
    .catch(() => { $('#mapStatus').textContent = 'The map could not be read.'; });
  KnoxArt.media('textures/worldMap/Paper.png').then((img) => KnoxMap.setPaper(img));
  $('#zoomIn').addEventListener('click', () => KnoxMap.zoom(1.6));
  $('#zoomOut').addEventListener('click', () => KnoxMap.zoom(1 / 1.6));
  $('#follow').addEventListener('click', () => KnoxMap.setFollow(!KnoxMap.isFollowing()));
  $('#legendBtn').addEventListener('click', legendSheet);
  $('#wpClear').addEventListener('click', () => command('waypointClear', {}, 'Waypoint cleared'));
  if (load('kl.mapHint')) hideMapHint();
  else setTimeout(hideMapHint, 9000);
}

function hideMapHint() {
  $('#mapHint').classList.add('gone');
  store('kl.mapHint', '1');
}

function legendSheet() {
  const K = KnoxMap.K;
  const kinds = [[K.RES, 'Residential'], [K.RETAIL, 'Shops & offices'], [K.FOOD, 'Restaurants & bars'], [K.MEDICAL, 'Medical'],
    [K.COMMUNITY, 'Police, fire, schools'], [K.INDUSTRIAL, 'Industrial'], [K.HOSPITALITY, 'Hotels & motels'], [K.PRIMARY, 'Road'],
    [K.WATER, 'Water'], [K.FOREST, 'Forest']];
  openSheet(sheetHead('Map key') + `<div class="legend">${kinds.map(([k, n]) => `<span><i style="background:${KnoxMap.colorOf(k)}"></i>${n}</span>`).join('')}</div>
    <p class="sh-note">Press and hold on the map to set a waypoint, draw on your map or set your base. Tap a building to see what it is.</p>`);
}

function distanceFrom(x, y) {
  const p = S.core && S.core.p;
  if (!p) return null;
  const dx = x - p.x, dy = y - p.y;
  const d = Math.hypot(dx, dy);
  return d < 6 ? 'Right where you are' : `${Fmt.dist(d)} ${Fmt.compass(dx, dy)} of you`;
}

/** Press and hold on the map. */
function spotSheet(x, y) {
  const b = S.core && S.core.base;
  const hasBase = !!b, safehouse = !!(b && b.safehouse);
  openSheet(sheetHead('This spot', distanceFrom(x, y)) + `<div class="acts">
      ${act('pin', 'Set a waypoint here', 'wp')}
      ${act('flag', 'Draw a symbol on your map…', 'sym')}
      ${act('pen', 'Write a note on your map…', 'note')}
      ${safehouse ? '' : act('home', hasBase ? 'Move my base here' : 'Make this my base', 'base')}</div>`, {
    wp: () => { command('waypoint', { x, y }, 'Waypoint set'); closeSheet(); },
    sym: () => symbolSheet(x, y),
    note: () => noteSheet(x, y),
    base: () => { command('baseSet', { x, y }, hasBase ? 'Base moved' : 'Base set'); closeSheet(); },
  });
}

// the pens and pencils the character carries (ISWorldMapSymbols: one colour per writing tool)
const INKS = [['Pen', 'Pen', '212121'], ['Pencil', 'Pencil', '333333'], ['RedPen', 'Red pen', 'a60e0e'],
  ['BluePen', 'Blue pen', '28307d'], ['GreenPen', 'Green pen', '0f632b']];
let ink = 'Pen';
const markTools = () => (S.marks && S.marks.tools) || null;
const inkColor = () => '#' + (INKS.find((k) => k[0] === ink) || INKS[0])[2];

/** The pens and pencils the character carries, as chips to pick from, or null if none. */
function inkRow(tools) {
  const have = arr(tools && tools.inks).map(String);
  if (!have.length) return null;
  if (!have.includes(ink)) ink = have[0];
  return `<div class="inks">${INKS.filter((k) => have.includes(k[0])).map(([id, label, c]) =>
    `<button class="ink${id === ink ? ' on' : ''}" data-act="ink" data-ink="${id}"><i style="background:#${c}"></i>${label}</button>`).join('')}</div>`;
}

function pickInk(b) {
  ink = b.dataset.ink;
  $$('#sheet .ink').forEach((x) => x.classList.toggle('on', x === b));
  const g = $('#sheet .symbols');
  if (g) g.style.setProperty('--ink', inkColor());
}

const NO_PEN = '<p class="none">You need a pen or a pencil to draw on your map, like in the game.</p>';

let symbolDefs = [], symbolsLoading = false, symbolsTried = false;

/** The game's own map symbols (id, picture, group), once per connection. */
function loadSymbols(fresh) {
  if (fresh) symbolsTried = false;
  if (symbolsLoading || (symbolsTried && symbolDefs.length)) return;
  symbolsLoading = true;
  fetch('/api/map/symbols').then((r) => (r.ok ? r.json() : [])).then((list) => {
    symbolDefs = arr(list).filter((d) => d && d.id);
    symbolsTried = true;
    if (S.marks) onMarks(S.marks);
    if (sheetKind === 'symbols' && sheetArgs) symbolSheet(sheetArgs[0], sheetArgs[1]);
  }).catch(() => { /* next time */ }).finally(() => { symbolsLoading = false; });
}

function symbolSheet(x, y) {
  const inks = inkRow(markTools());
  if (!inks) { openSheet(sheetHead('Draw on your map', 'It shows on the map in the game too.') + NO_PEN); return; }
  if (!symbolDefs.length) loadSymbols();
  const groups = new Map();
  for (const d of symbolDefs) {
    const t = d.tab || '';
    if (!groups.has(t)) groups.set(t, []);
    groups.get(t).push(d);
  }
  // symbols are drawn on paper-coloured tiles in the chosen ink, as on the game's map
  let grid = '';
  for (const [t, list] of groups) {
    grid += (t ? `<p class="sh-label">${esc(t)}</p>` : '') + `<div class="sym-grid">${list.map((d) => {
      const m = `url(&quot;${KnoxArt.mediaUrl(d.tex)}&quot;)`;
      return `<button class="sym" data-act="sym" data-id="${esc(d.id)}" title="${esc(symbolName(d.id))}" aria-label="${esc(symbolName(d.id))}"><i style="-webkit-mask-image:${m};mask-image:${m}"></i></button>`;
    }).join('')}</div>`;
  }
  openSheet(sheetHead('Draw on your map', 'It shows on the map in the game too.') + inks
    + `<div class="symbols" style="--ink:${inkColor()}">${grid || "<p class=\"none\">Loading the game's map symbols…</p>"}</div>`, {
    ink: pickInk,
    sym: (b) => { command('markAdd', { sym: b.dataset.id, x: x + 0.5, y: y + 0.5, ink }, 'Drawn on your map'); closeSheet(); },
  }, 'symbols', [x, y]);
}

function noteSheet(x, y) {
  const tools = markTools();
  const inks = inkRow(tools);
  const head = sheetHead('Write on your map', "A short note, in your character's handwriting.");
  if (!inks) { openSheet(head + NO_PEN); return; }
  if (tools && tools.notes === false) { openSheet(head + "<p class=\"none\">Your character can't read or write.</p>"); return; }
  const add = () => {
    const s = $('#noteInput').value.trim();
    if (!s) return;
    command('markAdd', { text: s, x: x + 0.5, y: y + 0.5, ink }, 'Written on your map');
    closeSheet();
  };
  openSheet(head + inks + `<form id="noteForm" class="field-form"><input id="noteInput" class="field hand" maxlength="40" placeholder="Loot here, Danger, …" autocomplete="off" autofocus></form>
    <div class="acts">${act('pen', 'Write it', 'write')}</div>`, { ink: pickInk, write: add });
  $('#noteForm').addEventListener('submit', (e) => { e.preventDefault(); add(); });
}

/** "MedCross" -> "Med cross" */
function symbolName(id) {
  const s = String(id || '').replace(/([a-z])([A-Z])/g, '$1 $2');
  return s ? s[0] + s.slice(1).toLowerCase() : 'Mark';
}

function markSheet(m) {
  const title = m.text != null ? '“' + esc(m.text) + '”' : esc(symbolName(m.sym));
  const x = Math.floor(m.x), y = Math.floor(m.y);
  const tools = markTools();
  openSheet(sheetHead(title, distanceFrom(m.x, m.y)) + `<div class="acts">${act('pin', 'Set a waypoint here', 'wp')}
      ${tools && tools.erase ? act('trash', 'Rub it out', 'erase') : '<p class="none">You need an eraser to rub it out.</p>'}</div>`, {
    wp: () => { command('waypoint', { x, y }, 'Waypoint set'); closeSheet(); },
    erase: () => { command('markRemove', { i: m.i, x: m.x, y: m.y }, 'Rubbed out'); closeSheet(); },
  });
}

function teamSheet(m) {
  const x = Math.floor(m.x), y = Math.floor(m.y);
  openSheet(sheetHead(esc(m.n), distanceFrom(m.x, m.y)) + `<div class="acts">${act('pin', 'Set a waypoint to where they are', 'wp')}</div>`, {
    wp: () => { command('waypoint', { x, y, label: m.n }, 'Waypoint set'); closeSheet(); },
  });
}

function baseSheet() {
  const b = S.core && S.core.base;
  if (!b) return;
  openSheet(sheetHead(esc(b.n || (b.safehouse ? 'Safehouse' : 'Base')), distanceFrom(b.x, b.y))
    + `<div class="acts">${act('pin', 'Go there', 'go')}${act('home', 'Open the Base tab', 'tab')}</div>`, {
    go: () => { command('waypoint', { x: Math.floor(b.x), y: Math.floor(b.y), label: 'Base' }, 'Waypoint set'); closeSheet(); },
    tab: () => { closeSheet(); setTab('base'); },
  });
}

function onMarks(d) {
  const list = arr(d.list);
  KnoxMap.setMarks(list);
  for (const m of list) {
    if (m.sym == null || KnoxMap.hasSymbolArt(m.sym)) continue;
    const def = symbolDefs.find((x) => x.id === m.sym);
    if (def) KnoxArt.media(def.tex).then((img) => KnoxMap.setSymbolArt(m.sym, img));
  }
}

// ---------------------------------------------------------------- alerts

/** level at which a bad moodle is worth an alert, and the quick action it offers */
const ALERTS = {
  BLEEDING: [1, 'Bandage', 'bandage'], ZOMBIE: [1], INJURED: [2, 'Bandage', 'bandage'], PAIN: [2, 'Painkiller', 'pills'],
  SICK: [2], HAS_A_COLD: [2], HUNGRY: [3, 'Eat', 'eat'], THIRST: [3, 'Drink', 'drink'], TIRED: [3],
  ENDURANCE: [3, 'Rest', 'sit'], PANIC: [3], HYPOTHERMIA: [2], HYPERTHERMIA: [2], WINDCHILL: [3],
  HEAVY_LOAD: [3, 'Open bag', 'tab:gear'], NOXIOUS_SMELL: [3], WET: [4], DRUNK: [4], UNHAPPY: [4], STRESS: [4], BORED: [4], ANGRY: [4],
};
let moodleLevels = {}, moodlesPrimed = false, arrivedShown = false, lowFuelShown = false, wpKey = '', lastChatN = -1;
let alertTimer = 0;

/**
 * Shows an alert across the top of every tab. art: a media/ui/Moodles/128 icon (or null for a
 * plain icon); action: a quick action to offer ("eat", "tab:comms", ...) or null.
 */
function showAlert(art, plainIcon, color, title, text, actionLabel, action, strong) {
  if (!settings.alerts) return;
  const el = $('#alert');
  el.innerHTML = `${moodleTile(art, color, art ? null : plainIcon)}<div class="al-main"><b>${esc(title)}</b>${text ? `<p>${esc(text)}</p>` : ''}</div>
    ${actionLabel ? `<button class="al-act">${esc(actionLabel)}</button>` : ''}<button class="al-close" aria-label="Dismiss">${icon('x')}</button>`;
  $('.al-close', el).onclick = hideAlert;
  const a = $('.al-act', el);
  if (a) {
    a.onclick = () => {
      hideAlert();
      if (action.startsWith('tab:')) setTab(action.slice(4));
      else command('quick', { a: action }, actionLabel);
    };
  }
  el.hidden = false;
  el.classList.remove('out');
  void el.offsetWidth;
  el.classList.add('in');
  clearTimeout(alertTimer);
  alertTimer = setTimeout(hideAlert, strong ? 12000 : 7000);
  buzz(strong);
}

function hideAlert() {
  const el = $('#alert');
  if (el.hidden) return;
  el.classList.remove('in');
  el.classList.add('out');
  setTimeout(() => { if (el.classList.contains('out')) el.hidden = true; }, 200);
}

function buzz(strong) {
  if (!settings.vibrate || !navigator.vibrate) return;
  try { navigator.vibrate(strong ? [140, 90, 200] : [80]); } catch (e) { /* not allowed */ }
}

function checkAlerts(c) {
  const now = {};
  let worst = null, worstRank = -1;
  for (const m of arr(c.m)) {
    now[m.id] = m.l;
    const rule = ALERTS[m.id];
    if (!moodlesPrimed || !rule || m.g !== 2) continue;
    const before = moodleLevels[m.id] || 0;
    if (m.l > before && m.l >= rule[0]) {
      const rank = m.l * 10 + (m.id === 'BLEEDING' || m.id === 'ZOMBIE' ? 50 : 0);
      if (rank > worstRank) { worstRank = rank; worst = m; }
    }
  }
  moodleLevels = now;
  moodlesPrimed = true;
  if (worst) {
    const rule = ALERTS[worst.id];
    const strong = worst.id === 'BLEEDING' || worst.id === 'ZOMBIE' || worst.l >= 4;
    let label = rule[1] || null, action = rule[2] || null;
    if (worst.id === 'TIRED' && c.p && c.p.bed) { label = 'Sleep'; action = 'sleep'; }
    // only offer an item action the character has something for (the game says what it has)
    const can = c.can;
    if (action && can && typeof can === 'object' && Object.prototype.hasOwnProperty.call(can, action) && !can[action]) {
      label = null;
      action = null;
    }
    showAlert(moodleIcon(worst.id, worst.l), 'vitals', moodleColor(2, worst.l), worst.n, worst.d, label, action, strong);
    return;
  }

  // arriving at the waypoint
  const wp = c.wp, p = c.p;
  const key = wp ? `${wp.x},${wp.y}` : '';
  if (key !== wpKey) { wpKey = key; arrivedShown = false; }
  if (wp && p && Math.hypot(wp.x - p.x, wp.y - p.y) < 6 && !arrivedShown) {
    arrivedShown = true;
    showAlert(null, 'pin', 'var(--accent)', "You've arrived", wp.label || '', null, null, false);
    return;
  }

  // low fuel while driving
  const veh = c.veh;
  if (!veh || (veh.fuel == null ? 1 : veh.fuel) > 0.2) lowFuelShown = false;
  else if (veh.drv && veh.fuel < 0.1 && !lowFuelShown) {
    lowFuelShown = true;
    showAlert(null, 'fuel', moodleColor(2, 3), 'Low on fuel', `About ${pct(veh.fuel)}% left in the tank.`, null, null, false);
  }
}

/** New chat lines: a badge, and an alert when the chat isn't on screen. */
function onChat(d) {
  Comms.chat(d);
  const n = d.n || 0;
  const list = arr(d.list);
  if (lastChatN >= 0 && n > lastChatN && list.length) {
    const m = list[0];
    const onScreen = tab === 'comms' && Comms.modeNow() === 'chat';
    if (m && !m.me && !onScreen) {
      showAlert(null, 'chat', Comms.channelColor(m.k), m.a || 'New message', m.s, 'Reply', 'tab:comms', false);
    }
  }
  lastChatN = n;
}

function updateCommsBadge() {
  const cnt = S.core && S.core.cnt;
  if (!cnt) return;
  const radio = cnt.radio || 0, chat = cnt.chat || 0;
  if (tab === 'comms') {
    store('kl.seenRadio', radio);
    store('kl.seenChat', chat);
    setBadge('comms', false);
    return;
  }
  let seenRadio = Number(load('kl.seenRadio') || 0), seenChat = Number(load('kl.seenChat') || 0);
  // a new session counts from zero again
  if (seenRadio > radio || seenChat > chat) {
    seenRadio = Math.min(seenRadio, radio);
    seenChat = Math.min(seenChat, chat);
    store('kl.seenRadio', seenRadio);
    store('kl.seenChat', seenChat);
  }
  setBadge('comms', radio > seenRadio || chat > seenChat);
}

// ---------------------------------------------------------------- the live character

let avatarBusy = false, avatarTimer = 0;

/** Fetches the live character picture a few times a second while it's on screen. */
async function avatarPoll() {
  clearTimeout(avatarTimer);
  if (avatarBusy || !authed || tab !== 'vitals' || !Vitals.wantsAvatar()) return;
  avatarBusy = true;
  let status = 0, blob = null;
  try {
    const r = await fetch('/api/avatar.png', { cache: 'no-store' });
    status = r.status;
    if (r.ok) blob = await r.blob();
  } catch (e) { /* not now */ }
  avatarBusy = false;
  if (status === 410 || status === 404) { Vitals.avatarUnavailable(); return; }   // the game can't draw it
  if (blob) Vitals.avatarFrame(URL.createObjectURL(blob));
  avatarTimer = setTimeout(avatarPoll, blob ? 140 : 700);
}

// ---------------------------------------------------------------- sheet & toasts

let sheetHandlers = null, sheetKind = '', sheetArgs = null, sheetPushed = false, skipPop = false;

/**
 * Opens the bottom sheet (or swaps what it shows). handlers: {action: fn(button)} for its
 * [data-act] buttons, or one fn(action, button).
 */
function openSheet(html, handlers, kind, args) {
  const layer = $('#sheetLayer'), sh = $('#sheet');
  const wasOpen = !layer.hidden;
  sh.innerHTML = `<div class="grip"></div>${html}`;
  sheetHandlers = handlers || null;
  sheetKind = kind || '';
  sheetArgs = args || null;
  if (!wasOpen) {
    layer.hidden = false;
    // Back (a phone's back gesture) closes the sheet first, like the Android app
    try { history.pushState({ klSheet: 1 }, ''); sheetPushed = true; } catch (e) { sheetPushed = false; }
  }
  sh.scrollTop = 0;
  const f = $('[autofocus]', sh);
  if (f) setTimeout(() => f.focus(), 80);
}

function closeSheet(fromHistory) {
  const layer = $('#sheetLayer');
  if (layer.hidden) return;
  layer.hidden = true;
  $('#sheet').innerHTML = '';
  sheetHandlers = null;
  sheetKind = '';
  sheetArgs = null;
  Vitals.sheetClosed();
  if (sheetPushed && fromHistory !== true) { skipPop = true; history.back(); }
  sheetPushed = false;
}

window.addEventListener('popstate', () => {
  if (skipPop) { skipPop = false; return; }
  if (!$('#sheetLayer').hidden) { sheetPushed = false; closeSheet(true); }
});
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeSheet(); });
$('#scrim').addEventListener('click', () => closeSheet());
$('#sheet').addEventListener('click', (e) => {
  const b = e.target.closest('[data-act]');
  if (!b || !sheetHandlers) return;
  if (typeof sheetHandlers === 'function') sheetHandlers(b.dataset.act, b, e);
  else if (sheetHandlers[b.dataset.act]) sheetHandlers[b.dataset.act](b, e);
});

function toast(text, bad = false) {
  const el = document.createElement('div');
  el.className = 'toast' + (bad ? ' bad' : '');
  el.textContent = text;
  const box = $('#toasts');
  box.appendChild(el);
  while (box.children.length > 3) box.firstChild.remove();
  setTimeout(() => { el.classList.add('gone'); setTimeout(() => el.remove(), 320); }, 2200);
}

// ---------------------------------------------------------------- settings

function mapStyle() {
  if (settings.map !== 'dark') return 'paper';
  return settings.theme === 'oled' ? 'oled' : 'dark';
}

function applySettings() {
  const root = document.documentElement;
  root.dataset.theme = settings.theme;
  root.dataset.scale = settings.scale;
  $('meta[name="theme-color"]').content = settings.theme === 'light' ? '#ffffff' : settings.theme === 'oled' ? '#0b0c0e' : '#15181d';
  $$('.seg[data-set]').forEach((seg) => {
    $$('button', seg).forEach((b) => b.classList.toggle('on', b.dataset.v === String(settings[seg.dataset.set])));
  });
  $$('input[data-set]').forEach((inp) => { inp.checked = !!settings[inp.dataset.set]; });
  KnoxMap.setStyle(mapStyle());
  KnoxMap.setLabels(settings.labels);
  updateWakeLock();
  // units and the clock show up everywhere: draw it all again
  Comms.radioSig = Comms.chatSig = Journal.sig = Base.sig = forecastSig = '';
  if (S.radio) Comms.radio(S.radio);
  if (S.chat) Comms.chat(S.chat);
  if (S.journal) Journal.journal(S.journal);
  if (S.base) Base.base(S.base);
  if (S.loot) Gear.loot(S.loot);   // (distances follow the units)
  if (S.core) onCore(S.core);
  if (watch) watch.redraw();
  Radar.redraw();
}

$('#view-settings').addEventListener('click', (e) => {
  const b = e.target.closest('.seg[data-set] button');
  if (!b) return;
  settings[b.closest('.seg').dataset.set] = b.dataset.v;
  saveSettings();
  applySettings();
});
$('#view-settings').addEventListener('change', (e) => {
  const inp = e.target.closest('input[data-set]');
  if (!inp) return;
  settings[inp.dataset.set] = inp.checked;
  saveSettings();
  applySettings();
});

function toggleFullscreen() {
  const el = document.documentElement;
  if (document.fullscreenElement) document.exitFullscreen();
  else if (el.requestFullscreen) el.requestFullscreen().catch(() => toast('Full screen is not available here', true));
}
$('#fsBtn').addEventListener('click', toggleFullscreen);
// In a normal browser tab, offer full screen right on the rail (an installed app is full screen already).
function updateFsButton() {
  const app = matchMedia('(display-mode: fullscreen), (display-mode: standalone)').matches;
  const hide = app || !!document.fullscreenElement || !document.documentElement.requestFullscreen;
  $('#fsRail').hidden = hide;
  $('#fsBtn').closest('.set-row').hidden = app || !document.documentElement.requestFullscreen;
  $('#fsBtn').lastChild.textContent = document.fullscreenElement ? 'Leave full screen' : 'Enter full screen';
  setTimeout(() => KnoxMap.redraw(), 50);
}
document.addEventListener('fullscreenchange', updateFsButton);
$('#forgetPin').addEventListener('click', () => {
  store('kl.pin', null);
  showOverlay('pin', null, 'Enter the PIN shown in the game (F8).');
});

let wakeLock = null;
async function updateWakeLock() {
  try {
    if (settings.wake && document.visibilityState === 'visible' && 'wakeLock' in navigator) {
      if (!wakeLock) {
        wakeLock = await navigator.wakeLock.request('screen');
        wakeLock.addEventListener('release', () => { wakeLock = null; });
      }
    } else if (wakeLock) {
      await wakeLock.release();
      wakeLock = null;
    }
  } catch (e) { wakeLock = null; }
}
document.addEventListener('visibilitychange', updateWakeLock);

// ---------------------------------------------------------------- start

function start() {
  const askedTab = pinFromHash();
  buildRail();
  buildTopBar();
  // the game's art for moodles and skill squares: switch from the plain look once it's here
  const root = document.documentElement;
  KnoxArt.media('ui/Moodles/128/_Moodles_BGsolid.png').then((i) => { if (i) { root.classList.add('art-moodle'); if (S.core) Vitals.core(S.core); } });
  Promise.all([KnoxArt.media('ui/SkillPanel/SkillUnit_fill.png'), KnoxArt.media('ui/SkillPanel/SkillUnit_border.png')])
    .then(([a, b]) => { if (a && b) root.classList.add('art-skill'); });
  initMap();
  Radar.init($('#radarBtn'), $('#radarCanvas'));
  Vitals.init();
  Gear.init();
  Car.init();
  Comms.init();
  Base.init();
  Game.init();
  Journal.init();
  if (!('vibrate' in navigator)) $('input[data-set="vibrate"]').closest('.set-row').hidden = true;
  applySettings();
  updateFsButton();
  const saved = askedTab || load('kl.tab');
  setTab(saved && saved !== 'car' ? saved : 'map');
  showOverlay('wait', 'Connecting to the game…', 'Keep Project Zomboid running with Knox Link enabled.');
  connect();
}

start();
