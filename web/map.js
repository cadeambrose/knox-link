/* Knox Link map: draws the game's own vector world map (converted by the mod) in the style of the
   in-game paper map, with a tile cache so panning and zooming stay smooth on a phone or a
   handheld's second screen. On top: fog of war, the floor plan around the character when zoomed
   all the way in, what the character remembers (searched buildings, the car they left, the way
   they walked, "Where did I see...?" finds), the player's own map marks, other survivors, the base,
   the waypoint, the zombies the danger radar knows of and the player. */
'use strict';

const KnoxMap = (() => {
  const CELL = 300;
  const TILE = 256;                 // css px per cached tile
  const MAX_TILES = 110;
  // css px per world square; all the way in: the floor plan, a square a finger wide
  const MIN_SCALE = 1 / 48, MAX_SCALE = 44;
  const PLAN_ZOOM = 5;              // from here in, the floor plan shows (it fades in a little before)

  // feature kinds (must match MapData.java)
  const K = { WATER: 1, FOREST: 2, PRIMARY: 3, SECONDARY: 4, TERTIARY: 5, TRAIL: 6, RAIL: 7,
    RES: 10, RETAIL: 11, FOOD: 12, INDUSTRIAL: 13, COMMUNITY: 14, MEDICAL: 15, HOSPITALITY: 16, OTHER: 17 };
  const ORDER = [K.FOREST, K.WATER, K.TRAIL, K.TERTIARY, K.SECONDARY, K.PRIMARY, K.RAIL,
    K.RES, K.OTHER, K.INDUSTRIAL, K.RETAIL, K.FOOD, K.HOSPITALITY, K.COMMUNITY, K.MEDICAL];
  const KIND_NAME = {
    [K.RES]: 'Residential', [K.RETAIL]: 'Shops & offices', [K.FOOD]: 'Restaurants & bars',
    [K.INDUSTRIAL]: 'Industrial', [K.COMMUNITY]: 'Police, fire, schools', [K.MEDICAL]: 'Medical',
    [K.HOSPITALITY]: 'Hotels & motels', [K.OTHER]: 'Building',
  };
  const TEAM = '#2a9d8f', BASE = '#3e7c47';

  // "paper" is the game's own map style (ISMapDefinitions.lua); the dark ones suit night use
  const PALETTES = {
    paper: {
      paper: true, land: '#dbd7c0', fog: '#c8c5b0',
      [K.FOREST]: '#bdc5a3', [K.WATER]: '#3b8d95', [K.TRAIL]: '#b97a57', [K.TERTIARY]: '#ab9e8f',
      [K.SECONDARY]: '#867d71', [K.PRIMARY]: '#867d71', [K.RAIL]: '#c8bfe7',
      [K.RES]: '#d29e69', [K.OTHER]: '#d29e69', [K.COMMUNITY]: '#8b75eb', [K.HOSPITALITY]: '#7fcee1',
      [K.INDUSTRIAL]: '#383635', [K.MEDICAL]: '#e58097', [K.FOOD]: '#f5e13c', [K.RETAIL]: '#b8cd54',
      outline: 'rgba(70,60,50,.33)', label: '#2b2620', labelWater: '#1f4f55', halo: 'rgba(230,226,204,.8)',
      player: '#d0461e', wp: '#b0281e',
    },
    dark: {
      land: '#191c21', fog: '#0f1114',
      [K.FOREST]: '#1c2a21', [K.WATER]: '#1f3a53', [K.TRAIL]: '#41464e', [K.TERTIARY]: '#565b64',
      [K.SECONDARY]: '#6b717a', [K.PRIMARY]: '#a8844f', [K.RAIL]: '#6f7480',
      [K.RES]: '#3a4049', [K.RETAIL]: '#3e68a0', [K.FOOD]: '#ad6a34', [K.INDUSTRIAL]: '#62558a', [K.COMMUNITY]: '#31837a',
      [K.MEDICAL]: '#c2504a', [K.HOSPITALITY]: '#a3863a', [K.OTHER]: '#464c55',
      outline: 'rgba(0,0,0,.45)', label: '#d9dde3', labelWater: '#7fb2dc', halo: 'rgba(13,15,18,.85)',
      player: '#f2a93b', wp: '#f2a93b',
    },
    oled: {
      land: '#0c0d10', fog: '#060708',
      [K.FOREST]: '#122018', [K.WATER]: '#13293d', [K.TRAIL]: '#33373e', [K.TERTIARY]: '#464a52',
      [K.SECONDARY]: '#5a5f67', [K.PRIMARY]: '#9a7845', [K.RAIL]: '#5f646e',
      [K.RES]: '#2c3138', [K.RETAIL]: '#33598c', [K.FOOD]: '#985c2c', [K.INDUSTRIAL]: '#544979', [K.COMMUNITY]: '#29726a',
      [K.MEDICAL]: '#ad453f', [K.HOSPITALITY]: '#8f7631', [K.OTHER]: '#383d45',
      outline: 'rgba(0,0,0,.6)', label: '#cfd3d9', labelWater: '#6e9fca', halo: 'rgba(0,0,0,.9)',
      player: '#f2a93b', wp: '#f2a93b',
    },
  };
  const HAND = '"Segoe Print", "Bradley Hand", "Comic Sans MS", casual, "Coming Soon", cursive';

  let canvas, ctx, dpr = 1, W = 0, H = 0;
  let pal = PALETTES.paper, styleName = 'paper';
  let showLabels = true;
  let data = null;                  // decoded map
  let view = { cx: 10650, cy: 9400, s: 0.7 };
  let follow = true;
  let player = null, shown = null;  // target and drawn (smoothed) player position
  let waypoint = null;
  let marks = [], team = [], base = null;
  const symbolArt = new Map();      // symbol id -> image
  const tintCache = new Map();      // id|colour -> tinted canvas
  let paperImg = null, paperPattern = null;
  let fog = null;                   // { unit, w, h, bits, canvas }
  let dark = false;
  let plan = null, planWanted = false;   // the floor plan: { x, y, w, h, c = 3 bytes a square }
  let zeds = [];                    // the radar's zombies on the map: { x, y, kind (0 seen, 1 heard, 2 lost), hot }
  let stamps = [];                  // buildings the character went into: [x, y, w, h, searched 0..100]
  let car = null, trail = [], showTrail = false;
  let pins = [], focusPin = -1;     // "Where did I see...?" finds, and the one shown big
  let dirty = true;
  let callbacks = {};
  const tiles = new Map();          // key -> {canvas, done, job}
  const queue = [];                 // tile keys waiting to be drawn

  // ------------------------------------------------------------------ decoding

  function decode(buf) {
    const dv = new DataView(buf);
    const magic = String.fromCharCode(dv.getUint8(0), dv.getUint8(1), dv.getUint8(2), dv.getUint8(3));
    if (magic !== 'KLM1') throw new Error('not a Knox Link map');
    const count = dv.getUint32(4, true);
    // pass 1: sizes
    let p = 8, rings = 0, points = 0;
    for (let f = 0; f < count; f++) {
      const rc = dv.getUint16(p + 2, true); p += 4;
      for (let r = 0; r < rc; r++) {
        const n = dv.getUint32(p, true);
        p += 12 + (n - 1) * 4; rings++; points += n;
      }
    }
    const kind = new Uint8Array(count), geom = new Uint8Array(count);
    const fRing = new Uint32Array(count + 1), rPt = new Uint32Array(rings + 1);
    const pts = new Float32Array(points * 2), bbox = new Float32Array(count * 4);
    p = 8; let ri = 0, pi = 0;
    for (let f = 0; f < count; f++) {
      kind[f] = dv.getUint8(p); geom[f] = dv.getUint8(p + 1);
      const rc = dv.getUint16(p + 2, true); p += 4;
      fRing[f] = ri;
      let minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
      for (let r = 0; r < rc; r++) {
        const n = dv.getUint32(p, true);
        let x = dv.getInt32(p + 4, true), y = dv.getInt32(p + 8, true);
        p += 12;
        rPt[ri++] = pi;
        pts[pi * 2] = x; pts[pi * 2 + 1] = y; pi++;
        if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y;
        for (let k = 1; k < n; k++) {
          x += dv.getInt16(p, true); y += dv.getInt16(p + 2, true); p += 4;
          pts[pi * 2] = x; pts[pi * 2 + 1] = y; pi++;
          if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y;
        }
      }
      bbox[f * 4] = minX; bbox[f * 4 + 1] = minY; bbox[f * 4 + 2] = maxX; bbox[f * 4 + 3] = maxY;
    }
    fRing[count] = ri; rPt[ri] = pi;
    let labels = [];
    if (p + 4 <= buf.byteLength) {
      const len = dv.getUint32(p, true);
      try { labels = JSON.parse(new TextDecoder().decode(new Uint8Array(buf, p + 4, Math.min(len, buf.byteLength - p - 4)))); } catch (e) { labels = []; }
    }
    // cell index, features grouped by draw order inside each cell
    const cells = new Map();
    for (let f = 0; f < count; f++) {
      const cx = Math.floor((bbox[f * 4] + bbox[f * 4 + 2]) / 2 / CELL);
      const cy = Math.floor((bbox[f * 4 + 1] + bbox[f * 4 + 3]) / 2 / CELL);
      const key = cx * 4096 + cy;
      let list = cells.get(key);
      if (!list) { list = []; cells.set(key, list); }
      list.push(f);
    }
    const rank = new Uint8Array(32);
    ORDER.forEach((k, i) => { rank[k] = i; });
    for (const list of cells.values()) list.sort((a, b) => rank[kind[a]] - rank[kind[b]]);
    return { count, kind, geom, fRing, rPt, pts, bbox, cells, labels, rank };
  }

  // ------------------------------------------------------------------ tiles

  const levelOf = (s) => Math.max(-6, Math.min(4, Math.ceil(Math.log2(s) - 0.15)));
  const tileKey = (L, tx, ty) => L + ':' + tx + ':' + ty;

  function featuresIn(x0, y0, x1, y1) {
    const out = [];
    const c0 = Math.floor(x0 / CELL) - 1, c1 = Math.floor(x1 / CELL) + 1;
    const r0 = Math.floor(y0 / CELL) - 1, r1 = Math.floor(y1 / CELL) + 1;
    const { bbox, cells } = data;
    for (let cx = c0; cx <= c1; cx++) {
      for (let cy = r0; cy <= r1; cy++) {
        const list = cells.get(cx * 4096 + cy);
        if (!list) continue;
        for (const f of list) {
          if (bbox[f * 4 + 2] < x0 || bbox[f * 4] > x1 || bbox[f * 4 + 3] < y0 || bbox[f * 4 + 1] > y1) continue;
          out.push(f);
        }
      }
    }
    const rank = data.rank, kind = data.kind;
    out.sort((a, b) => rank[kind[a]] - rank[kind[b]]);
    return out;
  }

  function getTile(L, tx, ty) {
    const key = tileKey(L, tx, ty);
    let t = tiles.get(key);
    if (t) { tiles.delete(key); tiles.set(key, t); return t; }   // LRU touch
    const c = document.createElement('canvas');
    c.width = c.height = Math.round(TILE * dpr);
    t = { key, L, tx, ty, canvas: c, done: false, job: null };
    tiles.set(key, t);
    queue.push(t);
    while (tiles.size > MAX_TILES) {
      const oldest = tiles.keys().next().value;
      const o = tiles.get(oldest);
      tiles.delete(oldest);
      const qi = queue.indexOf(o);
      if (qi >= 0) queue.splice(qi, 1);
    }
    return t;
  }

  /** Draws part of a tile; returns true when finished. Spread over frames so the UI never stalls. */
  function workTile(t, deadline) {
    const sL = Math.pow(2, t.L);
    const span = TILE / sL;
    const x0 = t.tx * span, y0 = t.ty * span;
    const g = t.canvas.getContext('2d');
    if (!t.job) {
      t.job = { list: featuresIn(x0, y0, x0 + span, y0 + span), i: 0 };
      g.setTransform(1, 0, 0, 1, 0, 0);
      g.clearRect(0, 0, t.canvas.width, t.canvas.height);
    }
    const k = sL * dpr;
    g.setTransform(k, 0, 0, k, -x0 * k, -y0 * k);
    const { kind, geom, fRing, rPt, pts, bbox } = data;
    const job = t.job;
    const minPx = 0.7 / k;          // smaller than this on screen: skip
    const decim = 0.8 / k;          // drop points closer than this to the last kept one
    const outline = t.L >= 1;
    let lastKind = -1;
    while (job.i < job.list.length) {
      const f = job.list[job.i++];
      const kd = kind[f];
      const w = bbox[f * 4 + 2] - bbox[f * 4], h = bbox[f * 4 + 3] - bbox[f * 4 + 1];
      if (w < minPx && h < minPx) continue;
      if (kd !== lastKind) {
        g.fillStyle = pal[kd] || pal[K.OTHER];
        g.strokeStyle = pal[kd] || pal[K.OTHER];
        lastKind = kd;
      }
      g.beginPath();
      for (let r = fRing[f]; r < fRing[f + 1]; r++) {
        const a = rPt[r], b = rPt[r + 1];
        let lx = pts[a * 2], ly = pts[a * 2 + 1];
        g.moveTo(lx, ly);
        for (let q = a + 1; q < b; q++) {
          const x = pts[q * 2], y = pts[q * 2 + 1];
          if (q < b - 1 && Math.abs(x - lx) < decim && Math.abs(y - ly) < decim) continue;
          g.lineTo(x, y);
          lx = x; ly = y;
        }
        if (geom[f] === 1) g.closePath();
      }
      if (geom[f] === 2) {
        g.lineWidth = (kd === K.RAIL ? 1.6 : 2.2) / k;
        if (kd === K.RAIL) g.setLineDash([5 / k, 4 / k]);
        g.stroke();
        g.setLineDash([]);
      } else {
        g.fill('evenodd');
        if (outline && kd >= K.RES) {
          g.lineWidth = 1 / k;
          g.strokeStyle = pal.outline;
          g.stroke();
          g.strokeStyle = pal[kd];
        }
      }
      if ((job.i & 63) === 0 && performance.now() > deadline) return false;
    }
    t.done = true;
    t.job = null;
    return true;
  }

  function pump() {
    const deadline = performance.now() + 9;
    let progressed = false;
    // newest requests first: they are what is on screen now
    while (queue.length && performance.now() < deadline) {
      const t = queue[queue.length - 1];
      if (workTile(t, deadline)) queue.pop();
      progressed = true;
    }
    if (progressed) dirty = true;
  }

  // ------------------------------------------------------------------ colours

  function hex(c) {
    const m = /^#?([0-9a-f]{6})$/i.exec(c || '');
    const v = m ? parseInt(m[1], 16) : 0x212121;
    return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
  }
  function mix(a, b, t) {
    const x = hex(a), y = hex(b);
    return '#' + x.map((v, i) => Math.round(v + (y[i] - v) * t).toString(16).padStart(2, '0')).join('');
  }

  // ------------------------------------------------------------------ drawing

  const sx = (wx) => (wx - view.cx) * view.s + W / 2;
  const sy = (wy) => (wy - view.cy) * view.s + H / 2;
  function screenToWorld(px, py) {
    return [(px - W / 2) / view.s + view.cx, (py - H / 2) / view.s + view.cy];
  }

  function drawTiles() {
    const L = levelOf(view.s);
    const sL = Math.pow(2, L);
    const span = TILE / sL;
    const [wx0, wy0] = screenToWorld(0, 0);
    const [wx1, wy1] = screenToWorld(W, H);
    const tx0 = Math.floor(wx0 / span), tx1 = Math.floor(wx1 / span);
    const ty0 = Math.floor(wy0 / span), ty1 = Math.floor(wy1 / span);
    const size = span * view.s;
    ctx.imageSmoothingEnabled = true;
    const wanted = [];
    for (let tx = tx0; tx <= tx1; tx++) {
      for (let ty = ty0; ty <= ty1; ty++) {
        const x = sx(tx * span), y = sy(ty * span);
        const t = getTile(L, tx, ty);
        if (t.done) {
          ctx.drawImage(t.canvas, x, y, size + 0.5, size + 0.5);
        } else {
          // draw the best coarser tile we already have, stretched, until this one is ready
          for (let up = 1; up <= 4; up++) {
            const L2 = L - up;
            if (L2 < -6) break;
            const span2 = TILE / Math.pow(2, L2);
            const px = Math.floor((tx * span) / span2), py = Math.floor((ty * span) / span2);
            const t2 = tiles.get(tileKey(L2, px, py));
            if (t2 && t2.done) {
              const frac = span / span2, n = t2.canvas.width;
              const ox = ((tx * span) - px * span2) / span2 * n, oy = ((ty * span) - py * span2) / span2 * n;
              ctx.drawImage(t2.canvas, ox, oy, n * frac, n * frac, x, y, size + 0.5, size + 0.5);
              break;
            }
          }
          wanted.push(t);
        }
      }
    }
    // keep the on-screen tiles at the end of the queue so they are drawn first
    for (const t of wanted) {
      const i = queue.indexOf(t);
      if (i >= 0 && i !== queue.length - 1) { queue.splice(i, 1); queue.push(t); }
    }
  }

  function drawFog() {
    if (!fog || !fog.canvas) return;
    // unexplored ground, coloured like the game's own "unvisited" map areas
    ctx.imageSmoothingEnabled = true;
    ctx.drawImage(fog.canvas, sx(0), sy(0), fog.w * fog.unit * view.s, fog.h * fog.unit * view.s);
  }

  function drawPaper() {
    if (!pal.paper || !paperPattern) return;
    // the game's paper texture, multiplied over the map; it moves with the map like real paper
    ctx.save();
    ctx.globalCompositeOperation = 'multiply';
    ctx.globalAlpha = 150 / 255;
    const ox = ((sx(0) % 256) + 256) % 256, oy = ((sy(0) % 256) + 256) % 256;
    if (paperPattern.setTransform) paperPattern.setTransform(new DOMMatrix([1, 0, 0, 1, ox, oy]));
    ctx.fillStyle = paperPattern;
    ctx.fillRect(0, 0, W, H);
    ctx.restore();
  }

  function fogged(wx, wy) {
    if (!fog || !fog.bits || fog.unit <= 0) return false;
    const ux = Math.floor(wx / fog.unit), uy = Math.floor(wy / fog.unit);
    if (ux < 0 || uy < 0 || ux >= fog.w || uy >= fog.h) return true;
    const i = uy * fog.w + ux;
    return (fog.bits[i >> 3] & (1 << (i & 7))) === 0;
  }

  function drawLabels() {
    if (!showLabels || !data || !data.labels.length) return;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.lineJoin = 'round';
    const s = view.s;
    for (const l of data.labels) {
      // town and region names while zoomed out, smaller place names once zoomed in
      const big = l.s >= 1.5;
      if (big ? s > 1.1 : s < 0.3) continue;
      const zoomFit = Math.min(1, Math.max(0.6, s * (big ? 2.5 : 1.4)));
      const size = (big ? Math.min(30, 11 + l.s * 5) : Math.min(16, 9 + l.s * 5)) * zoomFit;
      const x = sx(l.x), y = sy(l.y);
      if (x < -300 || y < -60 || x > W + 300 || y > H + 60) continue;
      if (fogged(l.x, l.y)) continue;   // no names for places the character hasn't seen
      ctx.save();
      ctx.translate(x, y);
      if (l.r) ctx.rotate(l.r * Math.PI / 180);
      const water = l.k && l.k.indexOf('water') >= 0;
      ctx.font = (water ? 'italic 700 ' : '700 ') + Math.round(size) + 'px Georgia, "Times New Roman", serif';
      ctx.lineWidth = 4;
      ctx.strokeStyle = pal.halo;
      ctx.strokeText(l.t, 0, 0);
      ctx.fillStyle = water ? pal.labelWater : pal.label;
      ctx.fillText(l.t, 0, 0);
      ctx.restore();
    }
  }

  const markSize = () => 22 * Math.max(0.75, Math.min(1.45, Math.sqrt(view.s)));

  /** A symbol picture in one ink colour (the art is drawn in the mark's colour, like the game's map). */
  function tinted(id, color) {
    const key = id + '|' + color;
    let c = tintCache.get(key);
    if (c) return c;
    const img = symbolArt.get(id);
    if (!img) return null;
    c = document.createElement('canvas');
    c.width = img.naturalWidth || img.width;
    c.height = img.naturalHeight || img.height;
    const g = c.getContext('2d');
    g.drawImage(img, 0, 0);
    g.globalCompositeOperation = 'source-in';
    g.fillStyle = color;
    g.fillRect(0, 0, c.width, c.height);
    if (tintCache.size > 400) tintCache.clear();
    tintCache.set(key, c);
    return c;
  }

  function inkOf(m) {
    const c = '#' + (/^[0-9a-f]{6}$/i.test(m.c || '') ? m.c : '212121');
    // on the dark map, ink would vanish: draw it light
    return pal.paper ? c : mix(c, '#f2eee4', 0.75);
  }

  function drawMarks() {
    if (!marks.length) return;
    const size = markSize();
    const zoomFit = Math.max(0.75, Math.min(1.3, Math.sqrt(view.s)));
    for (const m of marks) {
      const x = sx(m.x), y = sy(m.y);
      if (x < -60 || y < -60 || x > W + 60 || y > H + 60) continue;
      const color = inkOf(m);
      ctx.save();
      ctx.translate(x, y);
      if (m.rot) ctx.rotate(m.rot * Math.PI / 180);
      if (m.sym != null) {
        const art = tinted(m.sym, color);
        if (art) {
          ctx.imageSmoothingEnabled = true;
          ctx.drawImage(art, -size / 2, -size / 2, size, size);
        } else {
          ctx.strokeStyle = color;
          ctx.lineWidth = 2.5;
          ctx.beginPath(); ctx.arc(0, 0, size * 0.35, 0, Math.PI * 2); ctx.stroke();
        }
      } else if (m.text) {
        // a handwritten note, like the game's own map notes
        const fs = 14 * zoomFit;
        ctx.font = `700 ${fs.toFixed(1)}px ${HAND}`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.lineJoin = 'round';
        ctx.lineWidth = 3;
        ctx.strokeStyle = pal.halo;
        ctx.strokeText(m.text, 0, 0);
        ctx.fillStyle = color;
        ctx.fillText(m.text, 0, 0);
      }
      ctx.restore();
    }
  }

  /** A small name under a marker, with a halo so it reads on any ground. */
  function drawTag(s, x, y, color) {
    if (!s) return;
    ctx.font = '700 11.5px system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'alphabetic';
    ctx.lineJoin = 'round';
    ctx.lineWidth = 3.5;
    ctx.strokeStyle = pal.halo;
    ctx.strokeText(s, x, y);
    ctx.fillStyle = pal.paper ? mix(color, '#000000', 0.25) : mix(color, '#ffffff', 0.35);
    ctx.fillText(s, x, y);
  }

  function drawBase() {
    if (!base || base.x == null) return;
    const x = sx(base.x + 0.5), y = sy(base.y + 0.5);
    if (x < -80 || y < -60 || x > W + 80 || y > H + 60) return;
    const r = 10;
    ctx.beginPath();
    ctx.moveTo(x, y - r * 1.15);
    ctx.lineTo(x + r, y - r * 0.2);
    ctx.lineTo(x + r * 0.72, y - r * 0.2);
    ctx.lineTo(x + r * 0.72, y + r * 0.85);
    ctx.lineTo(x - r * 0.72, y + r * 0.85);
    ctx.lineTo(x - r * 0.72, y - r * 0.2);
    ctx.lineTo(x - r, y - r * 0.2);
    ctx.closePath();
    ctx.lineJoin = 'round';
    ctx.lineWidth = 4;
    ctx.strokeStyle = pal.paper ? '#ffffff' : '#0d0f12';
    ctx.stroke();
    ctx.fillStyle = BASE;
    ctx.fill();
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(x - r * 0.22, y + r * 0.2, r * 0.44, r * 0.65);
    if (view.s > 0.45) drawTag(base.n || (base.safehouse ? 'Safehouse' : 'Base'), x, y + r * 2.1, BASE);
  }

  function drawTeam() {
    const r = 7;
    for (const m of team) {
      const x = sx(m.x), y = sy(m.y);
      if (x < -80 || y < -40 || x > W + 80 || y > H + 40) continue;
      ctx.fillStyle = '#ffffff';
      ctx.beginPath(); ctx.arc(x, y, r + 2.5, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = TEAM;
      ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill();
      const n = String(m.n || '');
      const sp = n.indexOf(' ');
      drawTag(sp > 0 ? n.slice(0, sp) : n, x, y + r + 13, TEAM);
    }
  }

  function drawWaypoint() {
    if (!waypoint) return;
    const x = sx(waypoint.x + 0.5), y = sy(waypoint.y + 0.5);
    if (shown) {
      ctx.save();
      ctx.setLineDash([7, 7]);
      ctx.lineWidth = 2.5;
      ctx.strokeStyle = pal.wp;
      ctx.globalAlpha = 190 / 255;
      ctx.beginPath(); ctx.moveTo(sx(shown.x), sy(shown.y)); ctx.lineTo(x, y); ctx.stroke();
      ctx.restore();
    }
    const r = 12;
    ctx.beginPath();
    ctx.moveTo(x, y);
    ctx.bezierCurveTo(x - r * 0.33, y - r * 0.66, x - r, y - r * 1.15, x - r, y - r * 1.85);
    ctx.arc(x, y - r * 1.85, r, Math.PI, 0);
    ctx.bezierCurveTo(x + r, y - r * 1.15, x + r * 0.33, y - r * 0.66, x, y);
    ctx.closePath();
    ctx.lineWidth = 3;
    ctx.strokeStyle = pal.halo;
    ctx.stroke();
    ctx.fillStyle = pal.wp;
    ctx.fill();
    ctx.fillStyle = pal.halo;
    ctx.beginPath(); ctx.arc(x, y - r * 1.85, r * 0.38, 0, Math.PI * 2); ctx.fill();
  }

  function drawPlayer() {
    if (!shown) return;
    const x = sx(shown.x), y = sy(shown.y);
    const r = 11;
    ctx.save();
    ctx.translate(x, y);
    ctx.globalAlpha = 56 / 255;
    ctx.fillStyle = pal.player;
    ctx.beginPath(); ctx.arc(0, 0, r * 2.4, 0, Math.PI * 2); ctx.fill();
    ctx.globalAlpha = 1;
    ctx.rotate(((shown.dir || 0) * Math.PI) / 180);
    ctx.beginPath();
    ctx.moveTo(r * 1.5, 0);
    ctx.lineTo(-r * 0.9, r * 0.95);
    ctx.lineTo(-r * 0.35, 0);
    ctx.lineTo(-r * 0.9, -r * 0.95);
    ctx.closePath();
    ctx.lineJoin = 'round';
    ctx.lineWidth = 4;
    ctx.strokeStyle = '#fff';
    ctx.stroke();
    ctx.fillStyle = pal.player;
    ctx.fill();
    ctx.restore();
  }

  function disc(x, y, r) {
    ctx.beginPath();
    ctx.arc(x, y, Math.max(0, r), 0, Math.PI * 2);
    ctx.fill();
  }

  function roundRect(x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  // ------------------------------------------------------------------ the floor plan

  // edges, as Plan.java on the server sends them
  const E_WALL = 1, E_DOOR = 2, E_DOOR_OPEN = 3, E_WINDOW = 4, E_WINDOW_OPEN = 5, E_WINDOW_BROKEN = 6,
    E_FENCE = 7, E_DOORWAY = 8, E_FRAME = 9;

  /** Zoomed in far enough for the floor plan (or out of it again): the screen asks the game for it, or stops. */
  function checkPlanWanted() {
    const want = view.s >= PLAN_ZOOM * 0.7;
    if (want === planWanted) return;
    planWanted = want;
    callbacks.onPlanWanted && callbacks.onPlanWanted(want);
  }

  function line(color, width, x0, y0, x1, y1) {
    ctx.strokeStyle = color;
    ctx.lineWidth = width;
    ctx.beginPath();
    ctx.moveTo(x0, y0);
    ctx.lineTo(x1, y1);
    ctx.stroke();
  }

  /** What the character has seen of their floor around them: the ground, furniture, stairs, then the walls. */
  function drawPlan() {
    const pl = plan, u = view.s;   // css px per square
    if (!pl || u < PLAN_ZOOM * 0.7) return;
    const [ax, ay] = screenToWorld(0, 0), [bx, by] = screenToWorld(W, H);
    const i0 = Math.max(0, Math.floor(ax) - pl.x), i1 = Math.min(pl.w - 1, Math.floor(bx) - pl.x);
    const j0 = Math.max(0, Math.floor(ay) - pl.y), j1 = Math.min(pl.h - 1, Math.floor(by) - pl.y);
    if (i0 > i1 || j0 > j1) return;
    const paper = !!pal.paper, c = pl.c, n = pl.w;
    const inside = paper ? '#f1ebd6' : '#2b3036', outside = paper ? '#d9d3ba' : '#20242a';
    const memory = paper ? '#e3dcc4' : '#24282e';   // seen before, not in view now
    const furniture = paper ? '#b9a684' : '#4c5560', tree = paper ? '#7e9b5e' : '#3c5a3a';
    const ink = { ink: paper ? '#2b2620' : '#d9dde3', door: paper ? '#8a5a2b' : '#c9925a', glass: paper ? '#4f7ea3' : '#7fb2dc', wood: paper ? '#6b4a2b' : '#b08a5e' };
    const fade = Math.min(1, (u - PLAN_ZOOM * 0.7) / (PLAN_ZOOM * 0.3));
    ctx.save();
    ctx.globalAlpha = fade;
    ctx.lineCap = 'butt';
    // the ground the character has seen: brighter where it's in view now
    for (let j = j0; j <= j1; j++) {
      for (let i = i0; i <= i1; i++) {
        const b0 = c[(j * n + i) * 3];
        if (!(b0 & 1)) continue;
        const x = sx(pl.x + i), y = sy(pl.y + j);
        ctx.fillStyle = !(b0 & 2) ? memory : (b0 & 8) ? inside : outside;
        ctx.fillRect(x, y, u + 0.6, u + 0.6);
        if (b0 & 128) {
          ctx.fillStyle = tree;
          disc(x + u / 2, y + u / 2, u * 0.38);
        } else if (b0 & (32 | 64)) {
          ctx.fillStyle = furniture;
          const k = u * 0.12;
          roundRect(x + k, y + k, u - 2 * k, u - 2 * k, u * 0.1);
          ctx.fill();
          if ((b0 & 64) && u > 14) {   // storage: a drawer line
            ctx.globalAlpha = fade / 2;
            line(ink.ink, Math.max(1, u * 0.05), x + u * 0.3, y + u * 0.5, x + u * 0.7, y + u * 0.5);
            ctx.globalAlpha = fade;
          }
        }
        if (b0 & 16) {   // stairs: treads
          ctx.globalAlpha = fade * 2 / 3;
          for (let s = 1; s < 4; s++) line(ink.ink, Math.max(1, u * 0.05), x + u * 0.15, y + u * s / 4, x + u * 0.85, y + u * s / 4);
          ctx.globalAlpha = fade;
        }
      }
    }
    // walls, doors and windows on the squares' north and west edges
    const wall = Math.max(2, u * 0.16);
    for (let j = j0; j <= Math.min(pl.h - 1, j1 + 1); j++) {
      for (let i = i0; i <= Math.min(n - 1, i1 + 1); i++) {
        const k = (j * n + i) * 3;
        const north = c[k + 1], west = c[k + 2];
        if (!north && !west) continue;
        const x = sx(pl.x + i), y = sy(pl.y + j);
        if (north) edge(north, x, y, x + u, y, true, u, wall, ink);
        if (west) edge(west, x, y, x, y + u, false, u, wall, ink);
      }
    }
    ctx.restore();
  }

  function edge(code, x0, y0, x1, y1, north, u, wall, ink) {
    const kind = code & 15;
    const jam = u * 0.14;   // the wall stub either side of an opening
    const gx0 = north ? x0 + jam : x0, gy0 = north ? y0 : y0 + jam, gx1 = north ? x1 - jam : x1, gy1 = north ? y1 : y1 - jam;
    switch (kind) {
      case E_WALL:
        line(ink.ink, wall, x0, y0, x1, y1);
        break;
      case E_DOOR:
      case E_DOOR_OPEN:
      case E_DOORWAY:
        // the frame's ends, then the door: closed across the gap, open swung into the square
        line(ink.ink, wall, x0, y0, gx0, gy0);
        line(ink.ink, wall, gx1, gy1, x1, y1);
        if (kind === E_DOOR) line(ink.door, wall * 0.6, gx0, gy0, gx1, gy1);
        else if (kind === E_DOOR_OPEN) {
          const len = u - 2 * jam;
          line(ink.door, wall * 0.45, gx0, gy0, north ? gx0 : gx0 + len, north ? gy0 + len : gy0);
        }
        break;
      case E_WINDOW:
      case E_WINDOW_OPEN:
      case E_WINDOW_BROKEN:
      case E_FRAME:
        line(ink.ink, wall, x0, y0, gx0, gy0);
        line(ink.ink, wall, gx1, gy1, x1, y1);
        if (kind === E_WINDOW_BROKEN || kind === E_FRAME) {
          ctx.setLineDash([u * 0.08, u * 0.08]);
          line(kind === E_FRAME ? ink.ink : ink.glass, wall * 0.3, gx0, gy0, gx1, gy1);
          ctx.setLineDash([]);
        } else {
          // a pane: two thin lines, apart when the window is open
          const off = wall * (kind === E_WINDOW_OPEN ? 0.45 : 0.22);
          const ox = north ? 0 : off, oy = north ? off : 0, pw = Math.max(1, wall * 0.22);
          line(ink.glass, pw, gx0 - ox, gy0 - oy, gx1 - ox, gy1 - oy);
          line(ink.glass, pw, gx0 + ox, gy0 + oy, gx1 + ox, gy1 + oy);
        }
        break;
      case E_FENCE:
        ctx.setLineDash([u * 0.12, u * 0.08]);
        line(ink.ink, Math.max(1.5, wall * 0.35), x0, y0, x1, y1);
        ctx.setLineDash([]);
        break;
      default:
        break;
    }
    if (code & 16) {   // barricaded: planks across the opening
      const px = north ? u * 0.18 : 0, py = north ? 0 : u * 0.18;
      for (let s = 1; s <= 3; s++) {
        const mx = x0 + (x1 - x0) * s / 4, my = y0 + (y1 - y0) * s / 4;
        line(ink.wood, wall * 0.5, mx - py * 0.6 - px, my - px * 0.6 - py, mx + py * 0.6 + px, my + px * 0.6 + py);
      }
    }
  }

  /** The zombies the danger radar knows of, where it puts them around the character: seen solid, heard or lost a ring. */
  function drawZombies() {
    if (!zeds.length || view.s < 2) return;
    const r = Math.max(4.5, Math.min(view.s * 0.32, 11));
    for (const z of zeds) {
      const x = sx(z.x), y = sy(z.y);
      if (x < -20 || y < -20 || x > W + 20 || y > H + 20) continue;
      const color = z.hot ? '#e0352b' : z.kind === 0 ? '#c0392b' : z.kind === 1 ? '#e08a2b' : '#8a8f96';
      ctx.fillStyle = pal.paper ? '#ffffff' : '#0d0f12';
      disc(x, y, r + 2);
      if (z.kind === 0) {
        ctx.fillStyle = color;
        disc(x, y, r);
      } else {
        ctx.lineWidth = 2.2;
        ctx.strokeStyle = color;
        ctx.beginPath(); ctx.arc(x, y, r - 1, 0, Math.PI * 2); ctx.stroke();
      }
    }
  }

  // ------------------------------------------------------------------ what the character remembers

  const SEARCHED = '#3e7c47', CAR = '#2f5d8a', PIN = '#8b3fb0';

  /** How searched the building at a spot is (0..100), or -1 when the character hasn't been in it. */
  function searchedAt(wx, wy) {
    for (const [x, y, w, h, s] of stamps) {
      if (wx >= x && wy >= y && wx < x + w && wy < y + h) return s;
    }
    return -1;
  }

  /** A green tick on each building searched, a slice for one searched in part. */
  function drawStamps() {
    const z = view.s;
    if (!stamps.length || z < 0.55 || z >= PLAN_ZOOM) return;
    const r = 8 * Math.max(0.85, Math.min(1.3, Math.sqrt(z)));
    for (const [bx, by, bw, bh, s] of stamps) {
      if (s <= 0) continue;
      const x = sx(bx + bw / 2), y = sy(by + bh / 2);
      if (x < -20 || y < -20 || x > W + 20 || y > H + 20) continue;
      ctx.fillStyle = pal.paper ? '#ffffff' : '#0d0f12';
      disc(x, y, r + 2);
      if (s >= 80) {
        ctx.fillStyle = SEARCHED;
        disc(x, y, r);
        ctx.beginPath();
        ctx.moveTo(x - r * 0.45, y + r * 0.02);
        ctx.lineTo(x - r * 0.1, y + r * 0.38);
        ctx.lineTo(x + r * 0.5, y - r * 0.32);
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        ctx.lineWidth = 2.2;
        ctx.strokeStyle = '#ffffff';
        ctx.stroke();
        ctx.lineCap = 'butt';
      } else {
        // partly searched: how much, as a slice
        ctx.lineWidth = 2;
        ctx.strokeStyle = SEARCHED;
        ctx.beginPath(); ctx.arc(x, y, r - 1, 0, Math.PI * 2); ctx.stroke();
        ctx.fillStyle = SEARCHED;
        ctx.beginPath();
        ctx.moveTo(x, y);
        ctx.arc(x, y, r - 2.5, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * s / 100);
        ctx.closePath();
        ctx.fill();
      }
    }
  }

  /** The car the character left: a little car, from the side, in a blue disc. */
  function drawCar() {
    if (!car || view.s < 0.3) return;
    const x = sx(Number(car.x) + 0.5), y = sy(Number(car.y) + 0.5);
    if (x < -60 || y < -60 || x > W + 60 || y > H + 60) return;
    const r = 11;
    ctx.fillStyle = pal.paper ? '#ffffff' : '#0d0f12';
    disc(x, y, r + 2.5);
    ctx.fillStyle = CAR;
    disc(x, y, r);
    ctx.fillStyle = '#ffffff';
    roundRect(x - r * 0.62, y - r * 0.08, r * 1.24, r * 0.4, r * 0.12);
    ctx.fill();
    ctx.beginPath();
    ctx.moveTo(x - r * 0.36, y - r * 0.08);
    ctx.lineTo(x - r * 0.2, y - r * 0.38);
    ctx.lineTo(x + r * 0.22, y - r * 0.38);
    ctx.lineTo(x + r * 0.4, y - r * 0.08);
    ctx.closePath();
    ctx.fill();
    ctx.fillStyle = CAR;
    disc(x - r * 0.34, y + r * 0.34, r * 0.15);
    disc(x + r * 0.34, y + r * 0.34, r * 0.15);
    if (view.s > 0.45) drawTag(String(car.n || 'Your car'), x, y + r * 2.1, CAR);
  }

  /** The way the character walked, dotted in the player's colour (off unless the map key turns it on). */
  function drawTrail() {
    if (!showTrail || trail.length < 4 || view.s < 0.2) return;
    ctx.save();
    ctx.globalAlpha = 140 / 255;
    ctx.strokeStyle = pal.player;
    ctx.lineWidth = 2.2;
    ctx.setLineDash([2, 4]);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.moveTo(sx(trail[0] + 0.5), sy(trail[1] + 0.5));
    for (let i = 2; i + 1 < trail.length; i += 2) ctx.lineTo(sx(trail[i] + 0.5), sy(trail[i + 1] + 0.5));
    ctx.stroke();
    ctx.restore();
  }

  function drawPins() {
    pins.forEach((o, i) => { if (i !== focusPin) drawPin(o, false); });
    if (focusPin >= 0 && focusPin < pins.length) drawPin(pins[focusPin], true);
  }

  function drawPin(o, big) {
    if (!o) return;
    const x = sx(Number(o.x) + 0.5), y = sy(Number(o.y) + 0.5);
    if (x < -60 || y < -60 || x > W + 60 || y > H + 60) return;
    const r = big ? 11 : 7;
    ctx.fillStyle = pal.paper ? '#ffffff' : '#0d0f12';
    disc(x, y, r + 2.5);
    ctx.fillStyle = PIN;
    disc(x, y, r);
    ctx.fillStyle = '#ffffff';
    disc(x, y, r * 0.38);
    if (big) {
      const c = Number(o.c) || 1;
      drawTag(c > 1 ? `${o.n} ×${c}` : String(o.n || ''), x, y + r * 2.2, PIN);
    }
  }

  function frame() {
    requestAnimationFrame(frame);
    if (!canvas || !W) return;
    if (data && queue.length) pump();
    // ease the drawn player toward the latest position (updates arrive 4x a second)
    if (player) {
      if (!shown) shown = { ...player };
      const dx = player.x - shown.x, dy = player.y - shown.y;
      if (Math.abs(dx) > 0.01 || Math.abs(dy) > 0.01 || Math.abs(player.dir - shown.dir) > 0.5) {
        const jump = dx * dx + dy * dy > 400;   // teleport or vehicle at speed: snap
        shown.x = jump ? player.x : shown.x + dx * 0.25;
        shown.y = jump ? player.y : shown.y + dy * 0.25;
        const dd = ((player.dir - shown.dir + 540) % 360) - 180;
        shown.dir = (shown.dir + dd * 0.3 + 360) % 360;
        dirty = true;
      }
      if (follow && (Math.abs(view.cx - shown.x) > 0.01 || Math.abs(view.cy - shown.y) > 0.01)) {
        view.cx += (shown.x - view.cx) * 0.3;
        view.cy += (shown.y - view.cy) * 0.3;
        dirty = true;
      }
    }
    if (!dirty) return;
    dirty = false;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.globalCompositeOperation = 'source-over';
    ctx.globalAlpha = 1;
    ctx.fillStyle = pal.land;
    ctx.fillRect(0, 0, W, H);
    if (data) drawTiles();
    drawFog();
    drawPlan();
    drawPaper();
    if (data && view.s < PLAN_ZOOM) drawLabels();
    drawTrail();
    drawStamps();
    drawMarks();
    drawBase();
    drawCar();
    drawWaypoint();
    drawTeam();
    drawPins();
    drawZombies();
    drawPlayer();
    if (dark) { ctx.fillStyle = 'rgba(5,6,7,.9)'; ctx.fillRect(0, 0, W, H); }
  }

  function resize() {
    const r = canvas.getBoundingClientRect();
    if (!r.width || !r.height) return;   // hidden tab: keep the last size
    const nd = Math.min(window.devicePixelRatio || 1, 2);
    if (nd !== dpr) { dpr = nd; tiles.clear(); queue.length = 0; }
    W = r.width; H = r.height;
    canvas.width = Math.round(W * dpr);
    canvas.height = Math.round(H * dpr);
    dirty = true;
  }

  // ------------------------------------------------------------------ input

  function zoomAt(f, px, py) {
    const ns = Math.max(MIN_SCALE, Math.min(MAX_SCALE, view.s * f));
    if (px === undefined) { px = W / 2; py = H / 2; }
    const [wx, wy] = screenToWorld(px, py);
    view.s = ns;
    if (!follow) {
      view.cx = wx - (px - W / 2) / ns;
      view.cy = wy - (py - H / 2) / ns;
    }
    checkPlanWanted();
    dirty = true;
  }

  function hitBuilding(wx, wy) {
    if (!data) return null;
    const list = featuresIn(wx - 1, wy - 1, wx + 1, wy + 1);
    const { kind, fRing, rPt, pts } = data;
    for (let i = list.length - 1; i >= 0; i--) {
      const f = list[i];
      if (kind[f] < K.RES) continue;
      let inside = false;
      for (let r = fRing[f]; r < fRing[f + 1]; r++) {
        const a = rPt[r], b = rPt[r + 1];
        for (let p = a, q = b - 1; p < b; q = p++) {
          const xi = pts[p * 2], yi = pts[p * 2 + 1], xj = pts[q * 2], yj = pts[q * 2 + 1];
          if ((yi > wy) !== (yj > wy) && wx < ((xj - xi) * (wy - yi)) / (yj - yi) + xi) inside = !inside;
        }
      }
      if (inside) return KIND_NAME[kind[f]] || 'Building';
    }
    return null;
  }

  /** The mark, survivor, base, car left behind or "Where did I see...?" find under a tap, or null. */
  function thingAt(px, py) {
    let best = 22, hit = null;
    for (const m of team) {
      const d = Math.hypot(sx(m.x) - px, sy(m.y) - py);
      if (d < best) { best = d; hit = { kind: 'team', thing: m }; }
    }
    if (base && base.x != null) {
      const d = Math.hypot(sx(base.x + 0.5) - px, sy(base.y + 0.5) - py);
      if (d < best) { best = d; hit = { kind: 'base', thing: base }; }
    }
    if (car && view.s >= 0.3) {
      const d = Math.hypot(sx(Number(car.x) + 0.5) - px, sy(Number(car.y) + 0.5) - py);
      if (d < best) { best = d; hit = { kind: 'car', thing: car }; }
    }
    for (const o of pins) {
      const d = Math.hypot(sx(Number(o.x) + 0.5) - px, sy(Number(o.y) + 0.5) - py);
      if (d < best) { best = d; hit = { kind: 'seen', thing: o }; }
    }
    const reach = Math.max(best, markSize() * 0.7);
    for (const m of marks) {
      if (m.text) continue;
      const d = Math.hypot(sx(m.x) - px, sy(m.y) - py);
      if (d < reach && d < best) { best = d; hit = { kind: 'mark', thing: m }; }
    }
    // a small marker tapped squarely wins over the writing around it
    if (hit && best < 12) return hit;
    // notes are wide: a tap on (or just beside) the writing counts
    const fs = 14 * Math.max(0.75, Math.min(1.3, Math.sqrt(view.s)));
    ctx.font = `700 ${fs.toFixed(1)}px ${HAND}`;
    for (const m of marks) {
      if (!m.text) continue;
      const hw = ctx.measureText(String(m.text)).width / 2 + 6, hh = fs / 2 + 6;
      const dx = Math.max(0, Math.abs(sx(m.x) - px) - hw), dy = Math.max(0, Math.abs(sy(m.y) - py) - hh);
      const d = Math.hypot(dx, dy);
      if (d < 8 && (!hit || d < best)) { best = d; hit = { kind: 'mark', thing: m }; }
    }
    return hit;
  }

  function attachInput() {
    const pointers = new Map();
    let pinch = null, press = null, lastTap = 0;

    canvas.addEventListener('pointerdown', (e) => {
      if (e.pointerType === 'mouse' && e.button !== 0) return;   // right-click: see contextmenu
      canvas.setPointerCapture(e.pointerId);
      pointers.set(e.pointerId, { x: e.offsetX, y: e.offsetY });
      if (pointers.size === 1) {
        press = { x: e.offsetX, y: e.offsetY, moved: false,
          timer: setTimeout(() => {
            if (press && !press.moved) {
              const [wx, wy] = screenToWorld(press.x, press.y);
              press.long = true;
              if (navigator.vibrate) { try { navigator.vibrate(18); } catch (err) { /* not allowed */ } }
              callbacks.onLongPress && callbacks.onLongPress(Math.floor(wx), Math.floor(wy));
            }
          }, 480) };
      } else if (pointers.size === 2) {
        if (press) { clearTimeout(press.timer); press = null; }
        const [a, b] = [...pointers.values()];
        pinch = { d: Math.hypot(a.x - b.x, a.y - b.y), s: view.s };
      }
    });
    canvas.addEventListener('pointermove', (e) => {
      const prev = pointers.get(e.pointerId);
      if (!prev) return;
      const cur = { x: e.offsetX, y: e.offsetY };
      pointers.set(e.pointerId, cur);
      if (pointers.size === 2 && pinch) {
        const [a, b] = [...pointers.values()];
        const d = Math.hypot(a.x - b.x, a.y - b.y);
        const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
        zoomAt((pinch.s * d / pinch.d) / view.s, mx, my);
        return;
      }
      if (pointers.size === 1) {
        if (press && Math.hypot(cur.x - press.x, cur.y - press.y) > 8) {
          press.moved = true;
          clearTimeout(press.timer);
        }
        if (press && press.moved) {
          if (follow) setFollow(false);
          view.cx -= (cur.x - prev.x) / view.s;
          view.cy -= (cur.y - prev.y) / view.s;
          dirty = true;
        }
      }
    });
    const end = (e) => {
      pointers.delete(e.pointerId);
      if (pointers.size < 2) pinch = null;
      if (press && pointers.size === 0) {
        clearTimeout(press.timer);
        if (!press.moved && !press.long && e.type === 'pointerup') {
          const now = performance.now();
          if (now - lastTap < 300) {
            zoomAt(2, press.x, press.y);
            lastTap = 0;
          } else {
            lastTap = now;
            const hit = dark ? null : thingAt(press.x, press.y);
            if (hit) {
              callbacks.onThing && callbacks.onThing(hit.kind, hit.thing);
            } else {
              const [wx, wy] = screenToWorld(press.x, press.y);
              let b = fogged(wx, wy) || dark ? null : hitBuilding(wx, wy);
              if (b) {
                // what the character remembers of it
                const s = searchedAt(wx, wy);
                if (s >= 80) b += ' · searched';
                else if (s > 0) b += ` · ${s}% searched`;
                else if (s === 0) b += ' · been inside';
              }
              callbacks.onBuilding && callbacks.onBuilding(b);
            }
          }
        }
        press = null;
      }
    };
    canvas.addEventListener('pointerup', end);
    canvas.addEventListener('pointercancel', end);
    // a right-click on a PC does what press-and-hold does on a touch screen
    canvas.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      if (press && press.long) return;   // a long touch already opened it
      const [wx, wy] = screenToWorld(e.offsetX, e.offsetY);
      callbacks.onLongPress && callbacks.onLongPress(Math.floor(wx), Math.floor(wy));
    });
    canvas.addEventListener('wheel', (e) => {
      e.preventDefault();
      zoomAt(Math.exp(-e.deltaY * 0.0015), e.offsetX, e.offsetY);
    }, { passive: false });
  }

  function setFollow(on) {
    follow = on;
    callbacks.onFollow && callbacks.onFollow(on);
    dirty = true;
  }

  function buildFog() {
    if (!fog) return;
    const c = document.createElement('canvas');
    c.width = fog.w; c.height = fog.h;
    const g = c.getContext('2d');
    const img = g.createImageData(fog.w, fog.h);
    const [r, gg, b] = hex(pal.fog);
    for (let i = 0, n = fog.w * fog.h; i < n; i++) {
      if (fog.bits[i >> 3] & (1 << (i & 7))) continue;   // seen: clear
      img.data[i * 4] = r; img.data[i * 4 + 1] = gg; img.data[i * 4 + 2] = b; img.data[i * 4 + 3] = 255;
    }
    g.putImageData(img, 0, 0);
    fog.canvas = c;
    dirty = true;
  }

  // ------------------------------------------------------------------ public

  return {
    KIND_NAME,
    K,
    colorOf: (kind) => pal[kind],
    init(el, cb) {
      canvas = el;
      ctx = canvas.getContext('2d', { alpha: false });
      callbacks = cb || {};
      resize();
      new ResizeObserver(resize).observe(canvas);
      attachInput();
      requestAnimationFrame(frame);
    },
    async load(url, onStatus) {
      for (;;) {
        let res;
        try {
          res = await fetch(url, { cache: 'no-store' });
        } catch (e) {
          onStatus && onStatus("Waiting for the game's map…");
          await new Promise((r) => setTimeout(r, 2500));
          continue;
        }
        if (res.status === 503) {
          onStatus && onStatus('Preparing the map — first time only…');
          await new Promise((r) => setTimeout(r, 1500));
          continue;
        }
        if (!res.ok) throw new Error('map ' + res.status);
        const buf = await res.arrayBuffer();
        data = decode(buf);
        tiles.clear(); queue.length = 0;
        dirty = true;
        return data;
      }
    },
    loaded: () => data !== null,
    setPlayer(p) {
      player = p ? { x: p.x, y: p.y, dir: p.dir || 0 } : null;
      if (player && !shown) { view.cx = player.x; view.cy = player.y; }
    },
    setWaypoint(wp) {
      const next = wp && wp.x != null ? { x: wp.x, y: wp.y } : null;
      if ((next && waypoint && next.x === waypoint.x && next.y === waypoint.y) || (!next && !waypoint)) return;
      waypoint = next; dirty = true;
    },
    setMarks(list) { marks = list || []; dirty = true; },
    setSymbolArt(id, img) { if (!img) return; symbolArt.set(id, img); for (const k of [...tintCache.keys()]) if (k.startsWith(id + '|')) tintCache.delete(k); dirty = true; },
    hasSymbolArt: (id) => symbolArt.has(id),
    setTeam(list) { team = list || []; dirty = true; },
    setBase(b) {
      const next = b && b.x != null ? b : null;
      if (JSON.stringify(next) === JSON.stringify(base)) return;
      base = next; dirty = true;
    },
    setPaper(img) {
      paperImg = img;
      paperPattern = img && ctx ? ctx.createPattern(img, 'repeat') : null;
      dirty = true;
    },
    /** The explored-area mask from /api/map/fog ("KLV1"), or null to show everything. */
    setFog(buf) {
      if (!buf) { if (fog) { fog = null; dirty = true; } return; }
      const dv = new DataView(buf);
      if (buf.byteLength < 16 || String.fromCharCode(dv.getUint8(0), dv.getUint8(1), dv.getUint8(2), dv.getUint8(3)) !== 'KLV1') return;
      const unit = dv.getInt32(4, true), w = dv.getInt32(8, true), h = dv.getInt32(12, true);
      const n = (w * h + 7) >> 3;
      if (unit <= 0 || w <= 0 || h <= 0 || buf.byteLength < 16 + n) return;
      fog = { unit, w, h, bits: new Uint8Array(buf, 16, n), canvas: null };
      buildFog();
    },
    hasFog: () => !!fog,
    /** Too dark to read the map: everything but the darkness is hidden, taps do nothing. */
    setDark(on) { if (dark !== !!on) { dark = !!on; dirty = true; } },
    setStyle(name) {
      if (!PALETTES[name] || name === styleName) return;
      styleName = name; pal = PALETTES[name];
      for (const t of tiles.values()) { t.done = false; t.job = null; if (!queue.includes(t)) queue.push(t); }
      buildFog();
      tintCache.clear();
      dirty = true;
    },
    isPaper: () => !!pal.paper,
    /** The floor plan around the character ("plan": x, y, z, w, h, c = base64, 3 bytes a square: see Plan.java). */
    setPlan(d) {
      plan = null;
      if (d && d.c) {
        try {
          const raw = atob(String(d.c));
          const c = new Uint8Array(raw.length);
          for (let i = 0; i < raw.length; i++) c[i] = raw.charCodeAt(i);
          const w = Number(d.w) || 0, h = Number(d.h) || 0;
          if (w > 0 && h > 0 && c.length >= w * h * 3) plan = { x: Number(d.x) || 0, y: Number(d.y) || 0, w, h, c };
        } catch (e) { plan = null; }
      }
      if (view.s >= PLAN_ZOOM * 0.7) dirty = true;
    },
    /** Zoomed in far enough that the screen should follow the floor plan. */
    planWanted: () => planWanted,
    /** The zombies the danger radar knows of ("radar"), put on the map around the character (only their floor). */
    setZombies(radar) {
      zeds = [];
      const list = radar && Array.isArray(radar.z) ? radar.z : [];
      if (player) {
        for (const o of list) {
          if (!o || typeof o !== 'object' || Number(o.f)) continue;   // another floor
          zeds.push({ x: player.x + (Number(o.x) || 0), y: player.y + (Number(o.y) || 0),
            kind: o.s === 'seen' ? 0 : o.s === 'heard' ? 1 : 2, hot: o.b === 'attack' || o.b === 'chase' });
        }
      }
      if (view.s >= 2) dirty = true;
    },
    /** The map's memory layer ("memory"): searched buildings, the car left behind, the way walked. */
    setMemory(m) {
      const b = m && Array.isArray(m.b) ? m.b : [];
      stamps = b.filter(Array.isArray).map((r) => [Number(r[0]) || 0, Number(r[1]) || 0, Number(r[2]) || 0, Number(r[3]) || 0,
        Math.round((Number(r[4]) || 0) * 100)]);
      car = m && m.car && typeof m.car === 'object' && m.car.x != null ? m.car : null;
      trail = m && Array.isArray(m.tr) ? m.tr.map(Number) : [];
      dirty = true;
    },
    setTrailShown(on) { showTrail = !!on; dirty = true; },
    /** "Where did I see...?" finds to pin on the map; focus: the one to make big (-1: none). */
    setPins(list, focus) { pins = Array.isArray(list) ? list : []; focusPin = focus; dirty = true; },
    /** Moves the view to a place (and stops following the character), zoomed in at least this far. */
    focusOn(x, y, zoom) {
      setFollow(false);
      view.cx = x;
      view.cy = y;
      view.s = Math.max(view.s, Math.min(MAX_SCALE, zoom));
      checkPlanWanted();
      dirty = true;
    },
    searchedAt,
    /** Where a world square is on the canvas, in css px. */
    toScreen: (wx, wy) => [sx(wx), sy(wy)],
    setLabels(on) { showLabels = on; dirty = true; },
    zoom(f) { zoomAt(f); },
    setFollow,
    isFollowing: () => follow,
    redraw() { resize(); dirty = true; },
  };
})();
