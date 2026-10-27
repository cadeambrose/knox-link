/* Knox Link — the danger radar: the zombies around the character as far as the character knows
   (the game's "radar" channel, KnoxLink_Radar.lua), drawn round the character with the view
   pointing up. Small in the top bar on every tab; a tap opens the larger one with its legend and
   settings. Marks glide between the game's updates at up to 30 frames a second and the radar stops
   drawing once nothing moves; heard marks breathe and lost ones fade at a slower pace. A zombie
   close behind lights the rim on its side and buzzes once (and beeps, if the player turned that on).
   The same drawing rules as the Android app's RadarView. */
'use strict';

const Radar = (() => {
  const RANGES = [10, 20, 30];
  const LOST_S = 6;               // how long the game keeps a lost zombie (LOST_MS)
  const STALE_MS = 8000;          // no word from the game: the radar dims and shows nothing
  const FAST_MS = 33, SLOW_MS = 90;
  const NEAR_STALE_MS = 4000;     // the game repeats itself every 3 s when nothing changes
  const QUIET_MS = 5000;          // a zombie behind you again after this long is news again

  // ------------------------------------------------------------------ what the radar knows

  const dots = new Map();         // id -> {fromX, fromY, toX, toY, s, b, floor, age, bornAt, goneAt, present}
  let updateAt = 0;               // time of the last update; 0 = none yet
  let interval = 200;             // ms between updates, smoothed: how long a mark takes to glide
  let yawFrom = 0, yawTo = 0;     // degrees; yawTo is yawFrom plus the shortest turn
  let cone = 72, gameRange = 0;
  let near = false, nearA = 180, nearD = 3, nearId = null, nearB = 'idle', nearAt = 0, nearSince = -100000;
  const nearSeen = new Map();
  let lastBuzzAt = -100000, askedAt = -100000;

  const views = new Set();        // the radars on screen: the top bar's, and the sheet's while it's open
  let summaryEl = null;

  const now = () => performance.now();
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  const rad = (d) => d * Math.PI / 180;
  const wrap = (a) => a - 360 * Math.floor((a + 180) / 360);

  /** The range the player picked here, or null: then the radar follows the game's (another screen may have picked it). */
  function chosenRange() {
    const r = Number(load('kl.radarRange'));
    return RANGES.includes(r) ? r : null;
  }
  function range() {
    return chosenRange() || (RANGES.includes(gameRange) ? gameRange : 20);
  }
  const vibrateOn = () => load('kl.radarVibrate') !== '0';
  const soundOn = () => load('kl.radarSound') === '1';
  // the radar is on show: buzz and beep for it
  const alertsOn = () => document.visibilityState === 'visible';

  function progress(t) {
    if (updateAt === 0) return 1;
    return clamp((t - updateAt) / interval, 0, 1);
  }

  /** A "radar" message from the game. */
  function update(o) {
    if (!o) return;
    const t = now();
    const fresh = updateAt === 0 || t - updateAt > STALE_MS;   // nothing to glide from
    const k = progress(t);
    // where everything is drawn right now is where its next glide starts
    for (const d of dots.values()) {
      d.fromX += (d.toX - d.fromX) * k;
      d.fromY += (d.toY - d.fromY) * k;
      d.toX = d.fromX;
      d.toY = d.fromY;
      d.present = false;
    }
    const shown = yawFrom + (yawTo - yawFrom) * k;
    const y = typeof o.yaw === 'number' ? o.yaw : NaN;
    if (Number.isNaN(y)) {
      yawFrom = yawTo = shown;
    } else if (fresh) {
      yawFrom = yawTo = y;
    } else {
      yawFrom = shown;
      yawTo = shown + wrap(y - shown);
    }
    if (Math.abs(yawFrom) > 720) {
      const turns = 360 * Math.round(yawFrom / 360);
      yawFrom -= turns;
      yawTo -= turns;
    }
    if (!fresh) interval = clamp(interval * 0.7 + Math.min(t - updateAt, 400) * 0.3, 120, 350);
    if (typeof o.c === 'number') cone = o.c;
    if (typeof o.r === 'number') gameRange = o.r;

    arr(o.z).forEach((e, i) => {
      if (!e || typeof e !== 'object') return;
      const id = e.i != null ? e.i : -(i + 1);
      const x = Number(e.x) || 0, yy = Number(e.y) || 0;
      let d = dots.get(id);
      if (!d) {
        d = { fromX: x, fromY: yy, toX: x, toY: yy, bornAt: t, goneAt: 0 };
        dots.set(id, d);
      } else if (fresh) {
        d.fromX = x;
        d.fromY = yy;
      }
      d.toX = x;
      d.toY = yy;
      d.present = true;
      d.goneAt = 0;
      d.s = e.s || 'seen';
      d.b = e.b || 'idle';
      d.floor = Number(e.f) || 0;
      d.age = Number(e.a) || 0;
    });
    for (const [id, d] of dots) {
      if (d.present) continue;
      if (d.goneAt === 0 && !fresh) d.goneAt = t;   // left the list: fades out
      else if (fresh || t - d.goneAt > 260) dots.delete(id);
    }
    updateAt = t;

    const n = o.near;
    near = !!(n && typeof n === 'object' && !Array.isArray(n));
    if (near) {
      nearId = n.i != null ? n.i : null;
      nearA = typeof n.a === 'number' ? n.a : 180;
      nearD = typeof n.d === 'number' ? n.d : 3;
      nearB = n.b || 'idle';
      nearAt = t;
      // a new close-behind event: this zombie wasn't behind you a moment ago
      const last = nearSeen.get(nearId);
      nearSeen.set(nearId, t);
      if (last == null || t - last > QUIET_MS) {
        nearSince = t;
        if (alertsOn() && t - lastBuzzAt > 1200) {
          lastBuzzAt = t;
          vibrate(nearB === 'attack');
        }
      }
      if (alertsOn() && soundOn()) startPulse();
    }
    for (const [id, at] of nearSeen) if (t - at > QUIET_MS * 2) nearSeen.delete(id);

    // the game looks as far as the player asked here (it forgets after a restart)
    const want = chosenRange();
    if (want && gameRange !== 0 && gameRange !== want && t - askedAt > 30000) {
      askedAt = t;
      command('radar', { r: want }, null, true);
    }
    kickAll();
  }

  /** Forgets everything until the game speaks again (a new connection, maybe another game). */
  function reset() {
    dots.clear();
    updateAt = 0;
    near = false;
    gameRange = 0;
    nearSeen.clear();
    quiet();
    kickAll();
  }

  /** The player picked a range: the game is told (it only looks that far), the radars redraw. */
  function setRange(r) {
    store('kl.radarRange', String(r));
    const t = now();
    if (updateAt !== 0 && t - updateAt < STALE_MS && gameRange !== r) {
      askedAt = t;
      command('radar', { r }, null);
    }
    kickAll();
  }

  /** "2 seen · 1 heard", for the sheet. */
  function summary() {
    const t = now();
    if (updateAt === 0) return 'Waiting for the game';
    if (t - updateAt > STALE_MS) return 'No word from the game';
    const r = range();
    let seen = 0, heard = 0;
    for (const d of dots.values()) {
      if (!d.present || d.toX * d.toX + d.toY * d.toY > r * r * 1.02) continue;
      if (d.s === 'seen') seen++;
      else if (d.s === 'heard') heard++;
    }
    let s = !seen && !heard ? 'Nothing seen or heard nearby'
      : seen && heard ? `${seen} seen · ${heard} heard` : seen ? `${seen} seen` : `${heard} heard`;
    if (near && t - nearAt < NEAR_STALE_MS) s += ' · one close behind you';
    return s;
  }

  // ------------------------------------------------------------------ buzz and beep

  function vibrate(strong) {
    // the app's own vibration setting, then the radar's
    if (!settings.vibrate || !vibrateOn() || !navigator.vibrate) return;
    // the browser only lets a page vibrate once it has been tapped
    if (navigator.userActivation && !navigator.userActivation.hasBeenActive) return;
    try { navigator.vibrate(strong ? [140, 90, 200] : [70, 60, 110]); } catch (e) { /* not allowed */ }
  }

  let audio = null, pulsing = false, pulseTimer = 0, sleepTimer = 0;

  /** The page may only make a sound after a tap: the first one wakes the sound up (when it's on). */
  function wakeAudio() {
    if (!soundOn()) return null;
    try {
      if (!audio) {
        const AC = window.AudioContext || window.webkitAudioContext;
        if (!AC) return null;
        audio = new AC();
      }
      if (audio.state === 'suspended') audio.resume().catch(() => { /* needs a tap first */ });
    } catch (e) { audio = null; }
    return audio;
  }

  /** One short beep, like a parking sensor's. */
  function beep() {
    const a = wakeAudio();
    if (!a) return false;
    try {
      const t0 = a.currentTime + 0.01;
      const gain = a.createGain();
      gain.gain.setValueAtTime(0.0001, t0);
      gain.gain.exponentialRampToValueAtTime(0.22, t0 + 0.006);
      gain.gain.setValueAtTime(0.22, t0 + 0.05);
      gain.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.062);
      gain.connect(a.destination);
      for (const f of [400, 1200]) {
        const o = a.createOscillator();
        o.frequency.value = f;
        o.connect(gain);
        o.start(t0);
        o.stop(t0 + 0.07);
      }
      return true;
    } catch (e) {
      return false;
    }
  }

  function startPulse() {
    if (pulsing) return;
    pulsing = true;
    clearTimeout(sleepTimer);
    pulseTimer = setTimeout(pulseNow, 0);
  }

  /** Beeps quicker the closer the zombie behind you is. */
  function pulseNow() {
    pulsing = false;
    const t = now();
    if (!near || !alertsOn() || t - nearAt > NEAR_STALE_MS || !soundOn()) {
      clearTimeout(sleepTimer);
      sleepTimer = setTimeout(() => { if (audio && !pulsing) audio.suspend().catch(() => {}); }, 5000);
      return;
    }
    if (!beep()) return;
    const f = clamp((nearD - 1) / 2, 0, 1);
    pulsing = true;
    pulseTimer = setTimeout(pulseNow, 260 + 640 * f);
  }

  /** Stops the beeps (the sound was turned off, or the game went away). */
  function quiet() {
    clearTimeout(pulseTimer);
    pulsing = false;
  }

  function soundChanged() {
    if (!soundOn()) { quiet(); return; }
    wakeAudio();   // inside the tap that turned it on, so the browser lets it play
    if (near && alertsOn() && now() - nearAt < NEAR_STALE_MS) startPulse();
  }

  document.addEventListener('pointerdown', () => { if (soundOn() && (!audio || audio.state === 'suspended')) wakeAudio(); }, true);

  // ------------------------------------------------------------------ drawing

  // the theme's colours, read from the page's tokens (again when the theme changes)
  let pal = null, palKey = null;
  function palette() {
    const root = document.documentElement;
    const key = root.dataset.theme || '';
    if (pal && palKey === key) return pal;
    const cs = getComputedStyle(root);
    const v = (n, fb) => hexRgb((cs.getPropertyValue(n) || '').trim() || fb);
    pal = {
      bg: v('--bg', '#0d0f12'), text: v('--text', '#eceef1'), text2: v('--text-2', '#a7aeb8'), text3: v('--text-3', '#6f7782'),
      line: v('--line', '#2a3039'), warn: v('--warn', '#f2a93b'), bad: v('--bad', '#ef5d5d'), light: key === 'light',
      font: (cs.getPropertyValue('--font') || '').trim() || 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    };
    palKey = key;
    return pal;
  }
  const rgba = (c, a) => `rgba(${c[0]},${c[1]},${c[2]},${clamp(a, 0, 1).toFixed(3)})`;
  const colorOf = (P, b) => (b === 'attack' ? P.bad : b === 'chase' ? P.warn : P.text2);

  /** Distance rings, in squares: the close-behind zone where it fits, then even steps. */
  function rings(r) {
    if (r <= 10) return [3, 5];
    if (r <= 20) return [3, 10];
    return [10, 20];
  }
  // a square is a metre
  const distance = (sq) => (settings.units === 'imperial' ? `${Math.round(sq * 1.0936)} yd` : `${Math.round(sq)} m`);

  function disc(g, x, y, r) {
    g.beginPath();
    g.arc(x, y, Math.max(0, r), 0, Math.PI * 2);
  }

  /** One mark: seen is a solid dot, heard a hollow ring that breathes, lost a dot that fades. */
  function mark(g, P, x, y, r, s, color, alpha, breath, big) {
    alpha = clamp(alpha, 0, 1);
    if (s === 'lost') {
      g.fillStyle = rgba(color, alpha);
      disc(g, x, y, r * 0.8);
      g.fill();
    } else if (s === 'heard') {
      const rr = r * (1.05 + 0.22 * breath);
      g.fillStyle = rgba(color, alpha * 0.16);
      disc(g, x, y, rr);
      g.fill();
      g.lineWidth = big ? 1.8 : 1.2;
      g.strokeStyle = rgba(color, alpha * (0.72 + 0.28 * (1 - breath)));
      disc(g, x, y, rr);
      g.stroke();
    } else {
      g.fillStyle = rgba(P.bg, alpha * 0.9);
      disc(g, x, y, r + (big ? 1.5 : 0.9));
      g.fill();
      g.fillStyle = rgba(color, alpha);
      disc(g, x, y, r);
      g.fill();
    }
  }

  /** The rim lit up at angle (degrees from up, clockwise), with an arrow pointing out at it. */
  function edge(g, cx, cy, rim, angle, color, glow, big) {
    const sw = big ? 4 : 2.4;
    const ar = rim - sw / 2;
    g.lineWidth = sw;
    g.lineCap = 'round';
    g.strokeStyle = rgba(color, 0.95 * glow);
    g.beginPath();
    g.arc(cx, cy, Math.max(0, ar), rad(angle - 90 - 24), rad(angle - 90 + 24));
    g.stroke();
    g.lineCap = 'butt';
    // a narrow arrow (twice as long as it's wide) so its point reads at a glance
    const tip = rim - sw - (big ? 2 : 0.8), back = tip - (big ? 11 : 5.6), half = big ? 5 : 2.8;
    g.save();
    g.translate(cx, cy);
    g.rotate(rad(angle));
    g.beginPath();
    g.moveTo(0, -tip);
    g.lineTo(half, -back);
    g.lineTo(-half, -back);
    g.closePath();
    g.fillStyle = rgba(color, glow);
    g.fill();
    g.restore();
  }

  /** Sizes the canvas to its box (sharp on any screen); returns its css size, or null when it isn't shown. */
  function prepare(canvas) {
    const r = canvas.getBoundingClientRect();
    if (!r.width || !r.height) return null;
    const dpr = Math.min(window.devicePixelRatio || 1, 3);
    const w = Math.round(r.width * dpr), h = Math.round(r.height * dpr);
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
    const g = canvas.getContext('2d');
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, r.width, r.height);
    return { g, w: r.width, h: r.height, dpr };
  }

  function draw(v) {
    if (!v.canvas.isConnected) { views.delete(v); clearTimeout(v.timer); return; }
    const box = prepare(v.canvas);
    if (!box) return;   // hidden: drawn again when it's shown (a resize kicks it)
    const { g, w, h, dpr } = box;
    const P = palette();
    const t = now();
    const cx = w / 2, cy = h / 2;
    const rim = Math.min(w, h) / 2 - (v.big ? 2 : 1);
    if (rim <= 0) return;
    const live = updateAt !== 0 && t - updateAt < STALE_MS;
    const r = range();
    const scale = rim / r;
    const k = progress(t);
    let fast = live && k < 1, slow = false;
    const ya = rad(yawFrom + (yawTo - yawFrom) * k);
    const cos = Math.cos(ya), sin = Math.sin(ya);

    // the disc, the view straight up, and the distance rings
    g.fillStyle = rgba(P.bg, 1);
    disc(g, cx, cy, rim);
    g.fill();
    const cn = clamp(cone, 10, 180);
    g.fillStyle = rgba(P.text, live ? (P.light ? 0.07 : 0.06) : 0.03);
    g.beginPath();
    g.moveTo(cx, cy);
    g.arc(cx, cy, rim, rad(-90 - cn), rad(-90 + cn));
    g.closePath();
    g.fill();
    g.lineWidth = Math.max(1 / dpr, v.big ? 1 : 0.8);
    g.font = '600 11px ' + P.font;
    g.textAlign = 'center';
    g.textBaseline = 'alphabetic';
    const ringLabel = (x, bottom, s) => { g.fillStyle = rgba(P.text3, 0.9); g.fillText(s, x, bottom - 4); };
    for (const ring of rings(r)) {
      const rr = ring * scale;
      const danger = ring <= 3;
      if (danger && rr < 6) continue;   // too tight round the character to read
      g.strokeStyle = danger ? rgba(P.bad, live ? 0.3 : 0.15) : rgba(P.text3, live ? 0.32 : 0.18);
      disc(g, cx, cy, rr);
      g.stroke();
      if (v.big && !danger) ringLabel(cx, cy + rr, distance(ring));
    }
    if (v.big) ringLabel(cx, cy + rim, distance(r));

    // the zombies: lost underneath, then heard, then seen
    if (live) {
      const base = v.big ? 5 : 2.5;
      const breath = 0.5 + 0.5 * Math.sin(t * (2 * Math.PI / 1400));
      const since = (t - updateAt) / 1000;
      for (let pass = 0; pass < 3; pass++) {
        for (const d of dots.values()) {
          const layer = d.s === 'lost' ? 0 : d.s === 'heard' ? 1 : 2;
          if (layer !== pass) continue;
          const x = d.fromX + (d.toX - d.fromX) * k, y = d.fromY + (d.toY - d.fromY) * k;
          if (x * x + y * y > r * r * 1.02) continue;
          let alpha = 1;
          const born = t - d.bornAt;
          if (born < 180) { alpha *= Math.max(0, born) / 180; fast = true; }
          if (d.goneAt) {
            const left = 1 - (t - d.goneAt) / 250;
            if (left <= 0) continue;
            alpha *= left;
            fast = true;
          }
          if (layer === 0) {
            const left = 1 - (d.age + since) / LOST_S;
            if (left <= 0) continue;
            alpha *= 0.75 * left;
            slow = true;
          } else if (layer === 1) {
            slow = true;
          }
          let rr = base;
          if (d.floor) { rr *= 0.75; alpha *= 0.7; }   // a floor up or down
          const fwd = x * cos + y * sin, right = -x * sin + y * cos;
          mark(g, P, cx + right * scale, cy - fwd * scale, rr, d.s, colorOf(P, d.b), alpha, breath, v.big);
        }
      }
    }

    // close behind: the rim lights up on that side, with an arrow pointing at it
    if (live && near && t - nearAt < NEAR_STALE_MS) {
      let ang = nearA;
      const nd = dots.get(nearId);
      if (nd) {
        const x = nd.fromX + (nd.toX - nd.fromX) * k, y = nd.fromY + (nd.toY - nd.fromY) * k;
        const fwd = x * cos + y * sin, right = -x * sin + y * cos;
        if (fwd !== 0 || right !== 0) ang = Math.atan2(right, fwd) * 180 / Math.PI;
      }
      const since = t - nearSince;
      let glow = 1;
      if (since < 900) {
        glow = 0.45 + 0.55 * Math.abs(Math.cos(since * (3 * Math.PI / 900)));
        fast = true;
      }
      edge(g, cx, cy, rim, ang, nearB === 'attack' ? P.bad : P.warn, glow, v.big);
    }

    // the character, facing up
    const ph = v.big ? 7 : 3.6, pw = ph * 0.78;
    g.beginPath();
    g.moveTo(cx, cy - ph);
    g.lineTo(cx + pw, cy + ph * 0.75);
    g.lineTo(cx, cy + ph * 0.3);
    g.lineTo(cx - pw, cy + ph * 0.75);
    g.closePath();
    g.fillStyle = rgba(P.text, live ? 1 : 0.45);
    g.fill();

    g.lineWidth = Math.max(1 / dpr, 1);
    g.strokeStyle = rgba(P.line, 1);
    disc(g, cx, cy, rim);
    g.stroke();

    // the next frame: smooth while marks glide, slower for breathing and fading, none when still
    if (!v.timer && !v.raf) {
      let delay = fast ? FAST_MS : slow ? SLOW_MS : -1;
      if (delay < 0 && live) delay = updateAt + STALE_MS - t + 50;   // to dim when the game goes quiet
      if (delay >= 0) v.timer = setTimeout(() => { v.timer = 0; draw(v); }, delay);
    }
  }

  /** New data or a new size: draw at the next frame; draw() decides the pace from there. */
  function kick(v) {
    clearTimeout(v.timer);
    v.timer = 0;
    if (!v.raf) v.raf = requestAnimationFrame(() => { v.raf = 0; draw(v); });
  }

  function kickAll() {
    for (const v of [...views]) kick(v);
    if (summaryEl) {
      if (!summaryEl.isConnected) summaryEl = null;
      else {
        const s = summary();
        if (summaryEl.textContent !== s) summaryEl.textContent = s;
      }
    }
  }

  function addView(canvas, big) {
    const v = { canvas, big, timer: 0, raf: 0 };
    views.add(v);
    kick(v);
    return v;
  }

  /** A legend sample: one kind of mark, or the close-behind rim (kind "near"). */
  function swatch(canvas) {
    const box = prepare(canvas);
    if (!box) return;
    const P = palette();
    const { g, w, h } = box;
    const cx = w / 2, cy = h / 2;
    const kind = canvas.dataset.kind;
    const color = P[canvas.dataset.color] || P.text2;
    if (kind === 'near') {
      const rim = Math.min(cx, cy) - 1;
      // the bottom of a small rim: behind you
      edge(g, cx, cy - rim * 0.55, rim * 1.35, 180, color, 1, false);
    } else {
      mark(g, P, cx, cy, 4.5, kind, color, kind === 'lost' ? 0.5 : 1, 0.35, true);
    }
  }

  // ------------------------------------------------------------------ the sheet

  const label = (sq) => (settings.units === 'imperial' ? `${Math.round(sq * 1.0936)} yd` : `${sq} m`);

  /** The larger radar, what its marks mean, and its settings. */
  function sheet() {
    const rows = [['seen', 'text2', 'Wandering'], ['seen', 'warn', 'Coming for you'], ['seen', 'bad', 'Attacking you'],
      ['heard', 'text2', 'Heard, not seen'], ['lost', 'text2', 'Lost track of it'], ['near', 'warn', 'Close behind you']];
    const cur = range();
    const canVibrate = 'vibrate' in navigator;
    const html = sheetHead('Danger radar', `<span id="radarSum">${esc(summary())}</span>`)
      + `<div class="radar-big"><canvas class="radar-disc" id="radarBig" role="img" aria-label="Danger radar"></canvas>
        <div class="radar-legend">${rows.map(([kind, color, text]) =>
          `<div class="rl-row"><canvas class="rl-sw" data-kind="${kind}" data-color="${color}" aria-hidden="true"></canvas><span>${text}</span></div>`).join('')}
          <p class="rl-note">Only what your character sees or hears: walls, the dark and traits like Eagle Eyed or Hard of Hearing all count.</p></div></div>
      <div class="set-group">
        <div class="set-row"><span>Range</span><div class="seg" id="radarRange">${RANGES.map((r) =>
          `<button data-act="range" data-r="${r}" class="${r === cur ? 'on' : ''}">${label(r)}</button>`).join('')}</div></div>
        ${canVibrate ? `<label class="set-row toggle"><span class="sr-txt"><b>Vibrate when one is close behind</b>${settings.vibrate ? '' : '<small>Vibration is off in Settings</small>'}</span>
          <input type="checkbox" id="radarVib"${vibrateOn() ? ' checked' : ''}><i></i></label>` : ''}
        <label class="set-row toggle"><span class="sr-txt"><b>Sound pulses</b><small>A beep that quickens as one closes in behind you</small></span>
          <input type="checkbox" id="radarSound"${soundOn() ? ' checked' : ''}><i></i></label>
      </div>`;
    openSheet(html, {
      range: (b) => {
        setRange(Number(b.dataset.r));
        $$('#radarRange button').forEach((x) => x.classList.toggle('on', x === b));
      },
    }, 'radar');
    const vib = $('#radarVib');
    if (vib) vib.addEventListener('change', () => store('kl.radarVibrate', vib.checked ? null : '0'));
    const snd = $('#radarSound');
    snd.addEventListener('change', () => { store('kl.radarSound', snd.checked ? '1' : null); soundChanged(); });
    summaryEl = $('#radarSum');
    addView($('#radarBig'), true);
    $$('#sheet .rl-sw').forEach(swatch);
  }

  // ------------------------------------------------------------------ the top bar's radar

  let small = null;

  function init(button, canvas) {
    small = addView(canvas, false);
    button.addEventListener('click', sheet);
    new ResizeObserver(() => kick(small)).observe(canvas);
    // back on screen (another tab of the browser, the phone woke up): draw what's known now
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') kickAll(); else quiet(); });
  }

  /** The theme or the units changed: everything is drawn again in the new look. */
  function redraw() {
    kickAll();
  }

  return { init, update, reset, sheet, redraw };
})();
