/* Knox Link — shared helpers: DOM, settings, formatting and the small building blocks every screen uses. */
'use strict';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const icon = (id, cls = '') => `<svg class="ico ${cls}" aria-hidden="true"><use href="#i-${id}"/></svg>`;
const pct = (v) => Math.round((v || 0) * 100);
// An empty Lua table arrives as {} instead of [], so every list goes through this.
const arr = (v) => (Array.isArray(v) ? v : v && typeof v === 'object' ? Object.keys(v).sort((a, b) => a - b).map((k) => v[k]) : []);
const num = (v) => {
  v = Number(v) || 0;
  return Number.isInteger(v) ? String(v) : String(Math.round(v * 100) / 100);
};
const cap1 = (s) => (s ? s[0].toUpperCase() + s.slice(1) : '');

// ---------------------------------------------------------------- settings

const DEFAULTS = {
  theme: 'dark', map: 'paper', scale: 'm',
  units: /^en-(US|LR|MM)/.test(navigator.language) ? 'imperial' : 'metric',
  clock: /^en-(US|CA|AU|PH|IN)/.test(navigator.language) ? '12' : '24',
  alerts: true, vibrate: true, autoCar: true, labels: true, wake: true,
};
const settings = (() => {
  let saved = {};
  try { saved = JSON.parse(localStorage.getItem('kl.settings') || '{}'); } catch (e) { saved = {}; }
  return { ...DEFAULTS, ...saved };
})();
function saveSettings() {
  try { localStorage.setItem('kl.settings', JSON.stringify(settings)); } catch (e) { /* private mode */ }
}
function store(key, value) {
  try { if (value == null) localStorage.removeItem(key); else localStorage.setItem(key, value); } catch (e) { /* ignore */ }
}
function load(key) {
  try { return localStorage.getItem(key); } catch (e) { return null; }
}

// ---------------------------------------------------------------- formatting (units and clock follow the settings)

const Fmt = {
  temp: (c) => settings.units === 'imperial' ? `${Math.round(c * 9 / 5 + 32)}°F` : `${Math.round(c)}°C`,
  bodyTemp: (c) => settings.units === 'imperial' ? `${(c * 9 / 5 + 32).toFixed(1)}°F` : `${c.toFixed(1)}°C`,
  speed: (kmh) => Math.round(settings.units === 'imperial' ? kmh * 0.621371 : kmh),
  speedUnit: () => settings.units === 'imperial' ? 'MPH' : 'KM/H',
  dist(squares) {
    if (settings.units === 'imperial') {
      const yd = squares * 1.0936;
      return yd < 1000 ? `${Math.round(yd / 10) * 10} yd` : `${(yd / 1760).toFixed(1)} mi`;
    }
    return squares < 1000 ? `${Math.round(squares / 10) * 10} m` : `${(squares / 1000).toFixed(1)} km`;
  },
  /** A distance of a few steps, to the step: "Here", "2 yd", "3 m". */
  near(squares) {
    if (squares < 1.2) return 'Here';
    return settings.units === 'imperial' ? `${Math.round(squares * 1.0936)} yd` : `${Math.round(squares)} m`;
  },
  clock(h, m) {
    const mm = String(m).padStart(2, '0');
    if (settings.clock === '24') return `${String(h).padStart(2, '0')}:${mm}`;
    return `${((h + 11) % 12) + 1}:${mm} ${h < 12 ? 'AM' : 'PM'}`;
  },
  /** 0 = east, 90 = south: world y grows downwards, like the game's map. */
  compass(dx, dy) {
    const a = (Math.atan2(dy, dx) * 180 / Math.PI + 360) % 360;
    return ['E', 'SE', 'S', 'SW', 'W', 'NW', 'N', 'NE'][Math.round(a / 45) % 8];
  },
};

// ---------------------------------------------------------------- colours

function hexRgb(c) {
  const m = /^#?([0-9a-f]{6})$/i.exec(c || '');
  const v = m ? parseInt(m[1], 16) : 0x8a8f98;
  return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
}
/** "#rrggbb" from a hex string the game sent ("d5a27c"), or the fallback. */
const hexColor = (h, fallback) => (/^[0-9a-f]{6}$/i.test(h || '') ? '#' + h.toLowerCase() : fallback);

/** The game's moodle colours: greens for good moodles, yellow through red for bad ones. */
function moodleColor(goodBad, level) {
  const l = Math.max(1, Math.min(4, level || 1)) - 1;
  if (goodBad === 2) return ['#e3d36e', '#e8a84c', '#da6941', '#b93434'][l];
  if (goodBad === 1) return ['#b8dc6e', '#92cf62', '#66b956', '#409c46'][l];
  return '#b0b4ba';
}

/** good="high": full is fine (health); good="low": empty is fine (hunger). */
function meterColor(v, highIsGood) {
  const bad = highIsGood ? 1 - v : v;
  if (bad > 0.75) return 'var(--bad)';
  if (bad > 0.45) return 'var(--warn)';
  return highIsGood ? 'var(--good)' : 'var(--text-3)';
}

// ---------------------------------------------------------------- building blocks

/** MoodleType -> icon in media/ui/Moodles/128 */
const MOODLE_ICON = {
  ENDURANCE: 'Mood_Exhausted', TIRED: 'Mood_Sleepy', HUNGRY: 'Status_Hunger', PANIC: 'Mood_Panicked', SICK: 'Mood_Nauseous',
  BORED: 'Mood_Bored', UNHAPPY: 'Mood_Sad', BLEEDING: 'Status_Bleeding', WET: 'Status_Wet', HAS_A_COLD: 'Mood_Ill',
  ANGRY: 'Mood_Angry', STRESS: 'Mood_Stressed', THIRST: 'Status_Thirst', INJURED: 'Status_InjuredMinor', PAIN: 'Mood_Pained',
  HEAVY_LOAD: 'Status_HeavyLoad', DRUNK: 'Mood_Drunk', DEAD: 'Mood_Dead', ZOMBIE: 'Mood_Zombified',
  HYPERTHERMIA: 'Status_TemperatureHot', HYPOTHERMIA: 'Status_TemperatureLow', WINDCHILL: 'Status_Windchill',
  CANT_SPRINT: 'Status_MovementRestricted', UNCOMFORTABLE: 'Mood_Discomfort', NOXIOUS_SMELL: 'Mood_NoxiousSmell',
  FOOD_EATEN: 'Status_Hunger',
};
function moodleIcon(id, level) {
  return id === 'INJURED' && level >= 3 ? 'Status_InjuredMajor' : MOODLE_ICON[id];
}

/** A moodle drawn like the game draws it: the coloured round background, then the icon. */
function moodleTile(art, color, plainIcon) {
  const pic = art ? `<img class="art" alt="" src="${KnoxArt.mediaUrl('ui/Moodles/128/' + art + '.png')}">` : '';
  return `<span class="mtile" style="--c:${color}">${plainIcon ? icon(plainIcon) : ''}${pic}</span>`;
}

/** An item's own icon from the game, in a rounded box (a plain icon until it arrives). */
function itemIcon(tex, fallback, cls = '') {
  const ready = tex && KnoxArt.iconNow(tex);
  if (ready) return `<span class="ii ok ${cls}"><img class="art" alt="" src="${ready}"></span>`;
  return `<span class="ii ${cls}">${icon(fallback || 'box')}${tex ? `<img class="art" alt="" data-tex="${esc(tex)}">` : ''}</span>`;
}

/** A small coloured tag ("Rotten", "In hand"). kind: "", "bad", "warn", "good", "acc". */
const tag = (text, kind = '') => `<span class="tag ${kind}">${esc(text)}</span>`;
const tags = (list) => list.map(([t, k]) => tag(t, k)).join('');

/** A big tappable action row for sheets. */
function act(iconName, label, name, attrs = '', cls = '') {
  return `<button class="act ${cls}" data-act="${name}" ${attrs}>${iconName ? icon(iconName) : ''}<span>${label}</span></button>`;
}

function sheetHead(title, sub) {
  return `<h3>${title}</h3>${sub ? `<p class="sh-sub">${sub}</p>` : '<div class="sh-gap"></div>'}`;
}

/** A card with the small uppercase title row, like the game's panels. */
const cardHead = (title, sub = '', extra = '') => `<div class="card-head"><h2>${title}</h2><span class="sub">${sub}</span>${extra}</div>`;

function bar(v, color, cls = '') {
  return `<div class="bar ${cls}"><i style="width:${Math.max(0, Math.min(100, v * 100)).toFixed(1)}%;background:${color}"></i></div>`;
}

function emptyState(iconName, title, text) {
  return `<div class="empty-state">${icon(iconName)}<b>${title}</b><p>${text}</p></div>`;
}
