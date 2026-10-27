/* Knox Link — the game's own artwork, read by the mod from the player's own copy of the game:
   loose UI images (moodles, body outline, sidebar, watch, car overlays, paper map) and item icons
   cut out of the texture pack pages. Plus the pieces of the game's HUD rebuilt from that art:
   the digital watch, the car dashboard, the mechanics overlay and the health-panel figure.
   Each one shows a plain stand-in until its art has arrived. */
'use strict';

const KnoxArt = (() => {
  const mediaUrl = (p) => '/api/art/media?p=' + encodeURIComponent(p);
  const images = new Map();   // path -> Promise<Image|null>

  /** A loose PNG under the game's media folder, e.g. "ui/Moodles/128/Status_Bleeding.png". */
  function media(path) {
    let p = images.get(path);
    if (!p) {
      p = new Promise((resolve) => {
        const img = new Image();
        img.decoding = 'async';
        img.onload = () => resolve(img);
        img.onerror = () => resolve(null);
        img.src = mediaUrl(path);
      });
      images.set(path, p);
    }
    return p;
  }

  // ------------------------------------------------------------------ item icons from the packs

  let indexP = null;
  const pages = new Map();     // version:page -> Promise<Image|null>
  const icons = new Map();     // tex -> Promise<canvas|null>
  const urls = new Map();      // tex -> blob url (ready)
  const urlP = new Map();      // tex -> Promise<url|null>

  /** {v, tex}: the atlas of the game's texture packs. Always re-read, so a game update never meets old pages. */
  let lastV = null;
  function loadIndex() {
    if (!indexP) {
      indexP = fetch('/api/art/index', { cache: 'no-cache' }).then((r) => (r.ok ? r.json() : null))
        .then((j) => {
          if (!j || !j.tex) return null;
          const v = String(j.v || '');
          if (lastV !== null && v !== lastV) {
            // another copy of the game: icons cut from the old pages don't belong to it
            icons.clear(); urlP.clear(); urls.clear(); colors.clear();
          }
          lastV = v;
          return { v, tex: j.tex };
        })
        .catch(() => { indexP = null; return null; });
    }
    return indexP;
  }

  // pages are cached by the browser per art version ("v"): pages cut from one game version are
  // never used with another one's index
  function page(v, i) {
    const key = v + ':' + i;
    let p = pages.get(key);
    if (!p) {
      p = new Promise((resolve) => {
        const img = new Image();
        img.onload = () => resolve(img);
        img.onerror = () => { pages.delete(key); resolve(null); };
        img.src = '/api/art/page?i=' + i + '&v=' + encodeURIComponent(v);
      });
      pages.set(key, p);
    }
    return p;
  }

  function imageCanvas(img) {
    const c = document.createElement('canvas');
    c.width = img.naturalWidth; c.height = img.naturalHeight;
    c.getContext('2d').drawImage(img, 0, 0);
    return c;
  }

  /** An icon that isn't in the packs: a loose PNG from the game's media/textures (newer and modded items). */
  function looseIcon(name) {
    return new Promise((resolve) => {
      const img = new Image();
      img.onload = () => resolve(imageCanvas(img));
      img.onerror = () => resolve(null);
      img.src = '/api/art/icon?n=' + encodeURIComponent(name);
    });
  }

  async function oneIcon(name) {
    if (name.startsWith('media:')) {   // a loose picture under media/ui first (container buttons)
      const img = await media(name.slice(6));
      return img ? imageCanvas(img) : null;
    }
    const idx = await loadIndex();
    const r = idx && idx.tex[name];
    if (r) {
      const img = await page(idx.v, r[0]);
      if (!img) return undefined;   // the page didn't load (yet): try again next time
      const fw = Math.max(r[7], r[3]), fh = Math.max(r[8], r[4]);
      const c = document.createElement('canvas');
      c.width = fw; c.height = fh;
      c.getContext('2d').drawImage(img, r[1], r[2], r[3], r[4], r[5], r[6], r[3], r[4]);
      return c;
    }
    return looseIcon(name);
  }

  /**
   * An item icon by texture name ("Item_Bat"), padded to its full size like the game draws it.
   * "A|B" tries A, then B (recipe icons: "Item_" + name first, then the name itself).
   */
  function iconCanvas(tex) {
    if (!tex) return Promise.resolve(null);
    let p = icons.get(tex);
    if (!p) {
      p = (async () => {
        let retry = false;
        for (const name of String(tex).split('|')) {
          if (!name) continue;
          const c = await oneIcon(name);
          if (c) return c;
          if (c === undefined) retry = true;
        }
        if (retry) { icons.delete(tex); urlP.delete(tex); }
        return null;
      })();
      icons.set(tex, p);
    }
    return p;
  }

  function iconUrl(tex) {
    if (!tex) return Promise.resolve(null);
    if (urls.has(tex)) return Promise.resolve(urls.get(tex));
    let p = urlP.get(tex);
    if (!p) {
      p = iconCanvas(tex).then((c) => (c ? new Promise((res) => c.toBlob((b) => {
        const u = b ? URL.createObjectURL(b) : null;
        if (u) urls.set(tex, u);
        res(u);
      })) : null));
      urlP.set(tex, p);
    }
    return p;
  }

  /** Average of an icon's mid-tone pixels (outlines and highlights left out), as #rrggbb. */
  const colors = new Map();
  function iconColor(tex) {
    if (colors.has(tex)) return Promise.resolve(colors.get(tex));
    return iconCanvas(tex).then((c) => {
      let out = null;
      if (c) {
        const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
        const step = Math.max(1, Math.floor(Math.min(c.width, c.height) / 24));
        let r = 0, g = 0, b = 0, n = 0;
        for (let y = 0; y < c.height; y += step) {
          for (let x = 0; x < c.width; x += step) {
            const i = (y * c.width + x) * 4;
            if (d[i + 3] < 160) continue;
            const luma = (d[i] * 3 + d[i + 1] * 6 + d[i + 2]) / 10;
            if (luma < 28 || luma > 245) continue;
            r += d[i]; g += d[i + 1]; b += d[i + 2]; n++;
          }
        }
        if (n) out = '#' + [r, g, b].map((v) => Math.round(v / n).toString(16).padStart(2, '0')).join('');
      }
      colors.set(tex, out);
      return out;
    });
  }

  /** Fills in every <img data-tex> under root with its icon (or leaves the plain icon showing). */
  function fill(root) {
    const list = root.matches && root.matches('img[data-tex]') ? [root] : [...root.querySelectorAll('img[data-tex]:not([data-done])')];
    for (const img of list) {
      if (img.dataset.done) continue;
      img.dataset.done = '1';
      const tex = img.dataset.tex;
      const ready = urls.get(tex);
      if (ready) { img.src = ready; continue; }
      iconUrl(tex).then((u) => { if (u) img.src = u; });
    }
  }

  // the plain icon shows until the game's picture has loaded
  document.addEventListener('load', (e) => {
    const t = e.target;
    if (t && t.tagName === 'IMG' && t.classList.contains('art') && t.parentNode) t.parentNode.classList.add('ok');
  }, true);
  new MutationObserver((list) => {
    for (const m of list) for (const n of m.addedNodes) if (n.nodeType === 1) fill(n);
  }).observe(document.documentElement, { childList: true, subtree: true });

  // ------------------------------------------------------------------ helpers for drawing

  /** A copy of an image (or canvas) in one colour, keeping its shape (like PorterDuff SRC_IN). */
  function tint(src, color) {
    const c = document.createElement('canvas');
    c.width = src.naturalWidth || src.width;
    c.height = src.naturalHeight || src.height;
    const g = c.getContext('2d');
    g.drawImage(src, 0, 0);
    g.globalCompositeOperation = 'source-in';
    g.fillStyle = color;
    g.fillRect(0, 0, c.width, c.height);
    return c;
  }

  /** The alpha channel of a media image, for tapping on its visible pixels. */
  const masks = new Map();
  function alpha(path) {
    let p = masks.get(path);
    if (!p) {
      p = media(path).then((img) => {
        if (!img) return null;
        const c = document.createElement('canvas');
        c.width = img.naturalWidth; c.height = img.naturalHeight;
        const g = c.getContext('2d');
        g.drawImage(img, 0, 0);
        const d = g.getImageData(0, 0, c.width, c.height).data;
        const a = new Uint8Array(c.width * c.height);
        for (let i = 0; i < a.length; i++) a[i] = d[i * 4 + 3];
        return { w: c.width, h: c.height, a };
      });
      masks.set(path, p);
    }
    return p;
  }

  // ------------------------------------------------------------------ stickers (the tabs' icons)

  // the offsets within reach of a sticker's outline and rim, nearest first
  const REACH = 11;
  const OFFS = (() => {
    const l = [];
    for (let dy = -REACH; dy <= REACH; dy++) {
      for (let dx = -REACH; dx <= REACH; dx++) if (dx * dx + dy * dy <= REACH * REACH) l.push([dx, dy, dx * dx + dy * dy]);
    }
    l.sort((a, b) => a[2] - b[2]);
    return { x: Int8Array.from(l, (o) => o[0]), y: Int8Array.from(l, (o) => o[1]), d: Float32Array.from(l, (o) => Math.sqrt(o[2])) };
  })();

  /**
   * One of the game's pictures made into a sticker like the game's own sidebar icons: the picture (grown
   * without smoothing when it is small pixel art) on a square 20 px wider than it, a 7 px white outline, a dark
   * rim; in its own colours (on) or grey (off). The same as the Android app's ArtCache.sticker.
   */
  function stickerCanvas(src, on) {
    const w = src.width, h = src.height, longer = Math.max(w, h);
    const small = longer <= 48;
    const k = small ? Math.max(1, Math.floor(100 / longer)) : 100 / longer;
    const fw = small ? w * k : Math.max(1, Math.round(w * k)), fh = small ? h * k : Math.max(1, Math.round(h * k));
    const fit = document.createElement('canvas');
    fit.width = fw; fit.height = fh;
    const fg = fit.getContext('2d', { willReadFrequently: true });
    fg.imageSmoothingEnabled = !small;
    fg.imageSmoothingQuality = 'high';
    fg.drawImage(src, 0, 0, fw, fh);
    const inp = fg.getImageData(0, 0, fw, fh).data;
    const size = Math.max(fw, fh) + 20, ox = Math.floor((size - fw) / 2), oy = Math.floor((size - fh) / 2);
    const px = new Uint8ClampedArray(size * size * 4), shape = new Uint8Array(size * size);
    for (let y = 0; y < fh; y++) {
      for (let x = 0; x < fw; x++) {
        const s = (y * fw + x) * 4, i = (y + oy) * size + x + ox;
        px[i * 4] = inp[s]; px[i * 4 + 1] = inp[s + 1]; px[i * 4 + 2] = inp[s + 2];
        shape[i] = inp[s + 3] >= 128 ? 1 : 0;
      }
    }
    const c = document.createElement('canvas');
    c.width = c.height = size;
    const g = c.getContext('2d');
    const out = g.createImageData(size, size), o = out.data, n = OFFS.d.length;
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        const i = y * size + x, j = i * 4;
        if (shape[i]) {
          if (on) {
            o[j] = px[j]; o[j + 1] = px[j + 1]; o[j + 2] = px[j + 2];
          } else {
            const lum = (0.299 * px[j] + 0.587 * px[j + 1] + 0.114 * px[j + 2]) / 255;
            o[j] = o[j + 1] = o[j + 2] = Math.round((0.30 + 0.62 * lum) * 255);
          }
          o[j + 3] = 255;
          continue;
        }
        // the distance to the picture: the first offset that lands on it
        let d = -1;
        for (let q = 0; q < n; q++) {
          const xx = x + OFFS.x[q], yy = y + OFFS.y[q];
          if (xx >= 0 && yy >= 0 && xx < size && yy < size && shape[yy * size + xx]) { d = OFFS.d[q]; break; }
        }
        if (d < 0) continue;
        // white to 7 px, a soft step into the rim, the rim (black at 55 %) to 9.5 px, then fading out
        let a, white;
        if (d <= 7) { a = 1; white = 1; }
        else if (d <= 8) { const t = d - 7; a = 1 - 0.45 * t; white = (1 - t) / a; }
        else if (d <= 9.5) { a = 0.55; white = 0; }
        else { a = Math.max(0, 0.55 * (1 - (d - 9.5))); white = 0; }
        o[j] = o[j + 1] = o[j + 2] = Math.round(white * 255);
        o[j + 3] = Math.round(a * 255);
      }
    }
    g.putImageData(out, 0, 0);
    return c;
  }

  const stickers = new Map();   // "0:name" / "1:name" -> Promise<blob url|null>
  /** A sticker's picture (a blob url): name is a texture in the packs ("Item_Book") or a loose file ("ui/emotes/shrug.png"). */
  function sticker(name, on) {
    const key = (on ? '1:' : '0:') + name;
    let p = stickers.get(key);
    if (!p) {
      p = (async () => {
        let src = null;
        if (name.startsWith('ui/')) { const img = await media(name); if (img) src = imageCanvas(img); }
        else src = await iconCanvas(name);
        if (!src) { stickers.delete(key); return null; }   // not there (yet): the next connection tries again
        const c = stickerCanvas(src, on);
        return new Promise((res) => c.toBlob((b) => res(b ? URL.createObjectURL(b) : null)));
      })();
      stickers.set(key, p);
    }
    return p;
  }

  /** Forget the pictures that failed, so the next connection tries them again. */
  function retry() {
    for (const [k, p] of images) p.then((v) => { if (!v) images.delete(k); });
    for (const [k, p] of icons) p.then((v) => { if (!v) { icons.delete(k); urlP.delete(k); } });
    // a new connection may be a different game (or a game update): read the index again
    indexP = null;
  }

  return { media, mediaUrl, iconCanvas, iconUrl, iconNow: (tex) => urls.get(tex) || null, iconColor, fill, tint, alpha, sticker, retry };
})();

// =================================================================== the digital watch

/**
 * The in-game digital watch (media/ui/ClockAssets): time, AM/PM, temperature and date, LCD style.
 * Laid out with the offsets of the game's own large clock (zombie.ui.Clock.assignLargeOffsets):
 * every segment is drawn unlit first ("ghost"), then the lit digits on top.
 */
function Watch(canvas) {
  const files = { bg: 'ClockLargeBackground', colon: 'ClockDivideLarge', am: 'ClockAmLarge', pm: 'ClockPmLarge',
    dot: 'ClockDigitsMediumDot', E: 'ClockDigitsMediumE', C: 'ClockDigitsMediumC', F: 'ClockDigitsMediumF',
    minus: 'ClockDigitsMediumMinus', slash: 'DateDivideMedium' };
  for (let i = 0; i < 10; i++) { files['L' + i] = 'ClockDigitsLarge' + i; files['M' + i] = 'ClockDigitsMedium' + i; }
  const LIT = 'rgb(100,200,210)', GHOST = 'rgba(40,40,40,0.502)';   // the game's displayColour and ghostColour
  const img = {}, glyphs = new Map();
  let st = { h: -1 }, pending = Object.keys(files).length;
  for (const [k, f] of Object.entries(files)) {
    KnoxArt.media('ui/ClockAssets/' + f + '.png').then((im) => { if (im) img[k] = im; pending--; draw(); });
  }

  function put(g, k, x, y, color) {
    const im = img[k];
    if (!im) return 0;
    const key = k + color;
    let t = glyphs.get(key);
    if (!t) { t = KnoxArt.tint(im, color); glyphs.set(key, t); }
    g.drawImage(t, x, y);
    return im.width;
  }

  /** One pass over every segment: ghost = all of them unlit, otherwise only the lit digits. */
  function display(g, ghost, color) {
    const L = (d, x, y) => { if (ghost) put(g, 'L8', x, y, color); else if (d >= 0) put(g, 'L' + d, x, y, color); };
    const M = (d, x, y) => { if (ghost) put(g, 'M8', x, y, color); else if (d >= 0) put(g, 'M' + d, x, y, color); };
    const has = st.h >= 0;
    let x = 3, y = 3;
    // the hour: 12-hour clocks leave the leading zero off
    const h = !has ? -1 : (st.h24 ? st.h : ((st.h + 11) % 12) + 1);
    const t = has ? [h >= 10 || st.h24 ? Math.floor(h / 10) : -1, h % 10, Math.floor(st.mi / 10), st.mi % 10] : [-1, -1, -1, -1];
    for (let i = 0; i < 4; i++) {
      L(t[i], x, y);
      x += 18;
      if (i === 1) {
        x += 3;
        if (ghost || has) put(g, 'colon', x, y, color);
        x += 6 + 3;
      } else if (i < 3) {
        x += 2;
      }
    }
    x += 3;
    if (ghost) {
      put(g, 'am', x, y + 15, color);
      put(g, 'pm', x, y + 25, color);
    } else if (has && !st.h24) {
      put(g, st.h < 12 ? 'am' : 'pm', x, y + (st.h < 12 ? 15 : 25), color);
    }
    // second row: temperature, then the date
    x = 3;
    y += 34 + 5;
    const temp = st.t;
    if (ghost || temp != null) {
      let v = temp == null ? 0 : (st.f ? temp * 1.8 + 32 : temp);
      const neg = v < 0;
      if (neg) v = -v;
      const td = [Math.floor(Math.floor(v) / 100), Math.floor(Math.floor(v % 100) / 10), Math.floor(v) % 10, Math.floor(v * 10) % 10];
      if (ghost || neg) put(g, 'minus', x, y, color);
      x += -2;
      if (ghost || td[0] === 1) put(g, 'M1', x, y, color);
      x += 9 + 1;
      for (let i = 1; i < 4; i++) {
        M(td[i], x, y);
        x += 9;
        if (i === 2) {
          x += 2;
          put(g, 'dot', x, y + 15, color);
          x += 2 + 2;
        } else if (i < 3) {
          x += 1;
        }
      }
      x += 2;
      put(g, 'dot', x, y, color);   // the degree sign
      x += 2 + 2;
      put(g, ghost ? 'E' : (st.f ? 'F' : 'C'), x, y, color);
      x += 9 + 8;
    } else {
      x += 65;
    }
    if (ghost || st.mo > 0) {
      // month/day where the 12-hour clock is used, day/month elsewhere
      const a = st.h24 ? st.d : st.mo, b = st.h24 ? st.mo : st.d;
      const dd = [Math.floor(a / 10), a % 10, Math.floor(b / 10), b % 10];
      for (let i = 0; i < 4; i++) {
        M(dd[i], x, y);
        x += 9;
        if (i === 1) {
          x += 2;
          put(g, 'slash', x, y, color);
          x += 5 + 2;
        } else if (i < 3) {
          x += 1;
        }
      }
    }
  }

  function draw() {
    const r = canvas.getBoundingClientRect();
    if (!r.height) return;
    const dpr = Math.min(window.devicePixelRatio || 1, 3);
    const s = r.height / 62;
    const w = Math.round(156 * s * dpr), hgt = Math.round(62 * s * dpr);
    if (canvas.width !== w || canvas.height !== hgt) { canvas.width = w; canvas.height = hgt; }
    const g = canvas.getContext('2d');
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.clearRect(0, 0, w, hgt);
    g.setTransform(s * dpr, 0, 0, s * dpr, 0, 0);
    g.imageSmoothingEnabled = true;
    if (!img.bg || !img.L8 || pending > 0) {
      // plain text until the watch artwork arrives
      g.fillStyle = getComputedStyle(canvas).color || '#eceef1';
      g.font = '700 26px system-ui, sans-serif';
      g.textBaseline = 'middle';
      g.fillText(st.h < 0 ? '--:--' : fmtClock(st.h, st.mi), 4, 31);
      return;
    }
    g.globalAlpha = 0.75;
    g.drawImage(img.bg, 0, 0);
    g.globalAlpha = 1;
    display(g, true, GHOST);
    display(g, false, LIT);
  }

  function fmtClock(h, m) {
    const mm = String(m).padStart(2, '0');
    return st.h24 ? `${String(h).padStart(2, '0')}:${mm}` : `${((h + 11) % 12) + 1}:${mm}`;
  }

  new ResizeObserver(draw).observe(canvas);
  return {
    /** h < 0: no watch (the display stays dark). mo/d 0 and t null: an analog watch (time only). */
    set(h, mi, mo, d, t, h24, f) {
      const next = { h, mi, mo, d, t: t == null ? null : Math.round(t * 10) / 10, h24, f };
      if (JSON.stringify(next) === JSON.stringify(st)) return;
      st = next;
      draw();
    },
    redraw: draw,
  };
}

// =================================================================== the car dashboard

/** The in-game car dashboard (ISVehicleDashboard.lua): gauges with needles and the warning lights. */
function Dashboard(canvas, onReady) {
  const names = ['dashboard', 'vehicle_spedometer', 'rj-vehicle_engineguage', 'rj-vehicle_fuelguage-full',
    'rj-vehicle_fuelguage-low', 'rj-vehicle_fuelguage-empty', 'icon_enginetrouble_light', 'icon_lowbattery_light',
    'icon_doorslocked_light', 'icon_headlights_light', 'icon_heating_light', 'ignition', 'ignition_key_on',
    'ignition_key_off', 'ignition_hotwired', 'speedregulator_light'];
  const img = {};
  let v = null, raf = 0;
  let speedShown = -1, rpmShown = -1, fuelShown = -1;
  for (const n of names) {
    KnoxArt.iconCanvas(n).then((c) => {
      if (c) img[n] = c;
      if (n === 'dashboard' && c && onReady) onReady();
      schedule();
    });
  }
  const ease = (shown, target) => (shown < 0 ? target : shown + (target - shown) * 0.2);

  function schedule() { if (!raf) raf = requestAnimationFrame(draw); }

  function draw() {
    raf = 0;
    const bg = img.dashboard;
    const r = canvas.getBoundingClientRect();
    if (!bg || !v || !r.width) return;
    const dpr = Math.min(window.devicePixelRatio || 1, 3);
    const w = Math.round(r.width * dpr), h = Math.round(r.height * dpr);
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
    const g = canvas.getContext('2d');
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.clearRect(0, 0, w, h);
    const scale = Math.min(r.width / 552, r.height / 121);
    const ox = (r.width - 552 * scale) / 2, oy = (r.height - 121 * scale) / 2;
    g.setTransform(scale * dpr, 0, 0, scale * dpr, ox * dpr, oy * dpr);
    g.imageSmoothingEnabled = true;
    const put = (k, x, y) => { if (img[k]) g.drawImage(img[k], x, y); };
    // a warning light: coloured square behind the icon (grey = off, green = fine, red/amber = trouble)
    const light = (k, x, y, color) => {
      g.fillStyle = color;
      g.beginPath();
      if (g.roundRect) g.roundRect(x, y, 20, 20, 3); else g.rect(x, y, 20, 20);
      g.fill();
      put(k, x, y);
    };
    put('dashboard', 0, 0);
    const grey = 'rgba(128,128,128,.6)', good = 'rgba(70,194,136,.7)', bad = 'rgba(239,93,93,.7)', amber = 'rgba(255,128,25,.7)';
    const running = !!v.run;
    const eng = v.eng == null ? 100 : v.eng;
    light('icon_enginetrouble_light', 105, 26, !running ? grey : eng < 25 ? bad : eng < 60 ? amber : good);
    const batt = v.batt == null ? -1 : v.batt;
    light('icon_lowbattery_light', 137, 26, batt < 0 ? grey : batt < 0.1 ? bad : running ? good : grey);
    light('icon_doorslocked_light', 169, 26, v.locked ? good : grey);
    light('icon_headlights_light', 352, 26, v.lights ? good : grey);
    if (v.hasHeater) light('icon_heating_light', 411, 26, v.heater ? good : grey);
    light('speedregulator_light', 14, 93, v.regulator ? good : grey);
    put(v.hotwired ? 'ignition_hotwired' : v.keys ? (running ? 'ignition_key_on' : 'ignition_key_off') : 'ignition', 475, 60);

    const speed = Math.min(1, Math.abs(v.spd || 0) / 120);
    const rpm = running ? Math.max(0, Math.min(1, ((v.rpm || 0) - 1000) / 6000)) : 0;
    const fuel = Math.max(0, Math.min(1, v.fuel || 0));
    speedShown = ease(speedShown, speed);
    rpmShown = ease(rpmShown, rpm);
    fuelShown = ease(fuelShown, fuel);
    const needle = (x, y, px, py, len, value, width) => {
      const a = (180 - 180 * Math.max(0, Math.min(1, value))) * Math.PI / 180;
      g.strokeStyle = '#e0442e';
      g.lineWidth = width;
      g.lineCap = 'round';
      g.beginPath();
      g.moveTo(x + px, y + py);
      g.lineTo(x + px + Math.cos(a) * len, y + py - Math.sin(a) * len);
      g.stroke();
    };
    put('rj-vehicle_engineguage', 85, 55);
    needle(85, 55, 64, 60, 45, rpmShown, 2.2);
    put('vehicle_spedometer', 341, 55);
    needle(341, 55, 64, 60, 45, speedShown, 2.2);
    put(fuel < 0.05 ? 'rj-vehicle_fuelguage-empty' : fuel < 0.25 ? 'rj-vehicle_fuelguage-low' : 'rj-vehicle_fuelguage-full', 236, 21);
    needle(236, 21, 40, 37, 25, fuelShown, 2);
    // the gear, where the game puts its "S" label
    g.fillStyle = '#fff';
    g.font = '700 15px "Roboto Condensed", "Arial Narrow", system-ui, sans-serif';
    g.textAlign = 'center';
    g.textBaseline = 'alphabetic';
    g.fillText(String(v.gear || ''), 66, 68);
    if (Math.abs(speedShown - speed) > 0.002 || Math.abs(rpmShown - rpm) > 0.002 || Math.abs(fuelShown - fuel) > 0.002) schedule();
  }

  new ResizeObserver(schedule).observe(canvas);
  return {
    set(vehicle) { v = vehicle; schedule(); },
    ready: () => !!img.dashboard,
  };
}

// =================================================================== the mechanics overlay

/** The game's mechanics overlay for a car: base image plus each part tinted by its condition. */
function CarOverlay(el) {
  let prefix = null;
  const partEls = new Map();
  const box = document.createElement('div');
  box.className = 'mech-box';
  el.appendChild(box);
  const mask = (path) => `url("${KnoxArt.mediaUrl(path)}")`;
  // the game's colours: green when fine, through yellow, to red; missing parts dark
  const color = (c) => (c < 0 ? '#3a3f47' : c >= 70 ? '#46c288' : c >= 40 ? '#e8c24a' : c >= 15 ? '#e0823c' : '#ef5d5d');
  return {
    set(mech) {
      if (!mech) { el.hidden = true; return; }
      el.hidden = false;
      const pre = String(mech.prefix || '');
      if (pre !== prefix) {
        prefix = pre;
        partEls.clear();
        box.innerHTML = '';
        const base = document.createElement('img');
        base.className = 'mech-base';
        base.alt = '';
        base.onload = () => { box.style.aspectRatio = base.naturalWidth + ' / ' + base.naturalHeight; box.classList.add('ok'); };
        base.src = KnoxArt.mediaUrl('ui/vehicles/mechanic overlay/' + pre + 'base.png');
        box.appendChild(base);
      }
      const seen = new Set();
      for (const p of arr(mech.parts)) {
        const img = String(p.img || '');
        if (!img) continue;
        seen.add(img);
        let i = partEls.get(img);
        if (!i) {
          i = document.createElement('i');
          const m = mask('ui/vehicles/mechanic overlay/' + pre + img + '.png');
          i.style.webkitMaskImage = m;
          i.style.maskImage = m;
          box.appendChild(i);
          partEls.set(img, i);
        }
        i.style.background = color(p.c == null ? 100 : p.c);
      }
      for (const [k, i] of partEls) if (!seen.has(k)) { i.remove(); partEls.delete(k); }
    },
  };
}

// =================================================================== the body figure

/**
 * The body, drawn with the game's own health-panel silhouette (media/ui/defense): the base figure
 * plus one overlay per body part, tinted by the part's state. Simple shapes stand in until the
 * art has arrived. In "look" mode the same figure is painted the way the character looks: skin,
 * hair, and the colour of whatever they wear on each part.
 */
function Figure(root, onPart) {
  // BodyPartType name -> image suffix, and a stand-in shape (200x425 box). In the male art the
  // "lower_body" picture is the chest and "upper_body" the belly, so they're crossed here; the
  // female art is named the right way round (see fileOf).
  const PARTS = [
    ['Head', 'head', 'e', 100, 36, 25, 28], ['Neck', 'neck', 'r', 88, 64, 24, 13],
    ['Torso_Upper', 'lower_body', 'r', 62, 79, 76, 73], ['Torso_Lower', 'upper_body', 'r', 66, 154, 68, 50],
    ['Groin', 'groin', 'r', 70, 206, 60, 30],
    ['UpperArm_R', 'upper_arms_right', 'r', 35, 83, 24, 66], ['UpperArm_L', 'upper_arms_left', 'r', 141, 83, 24, 66],
    ['ForeArm_R', 'lower_arms_right', 'r', 29, 151, 24, 64], ['ForeArm_L', 'lower_arms_left', 'r', 147, 151, 24, 64],
    ['Hand_R', 'hand_right', 'r', 25, 217, 27, 31], ['Hand_L', 'hand_left', 'r', 148, 217, 27, 31],
    ['UpperLeg_R', 'upper_legs_right', 'r', 70, 238, 28, 79], ['UpperLeg_L', 'upper_legs_left', 'r', 102, 238, 28, 79],
    ['LowerLeg_R', 'lower_legs_right', 'r', 72, 319, 25, 74], ['LowerLeg_L', 'lower_legs_left', 'r', 103, 319, 25, 74],
    ['Foot_R', 'feet_right', 'r', 64, 395, 33, 22], ['Foot_L', 'feet_left', 'r', 103, 395, 33, 22],
  ];
  const plain = PARTS.map(([k, , t, a, b, c, d]) => t === 'e'
    ? `<ellipse data-k="${k}" cx="${a}" cy="${b}" rx="${c}" ry="${d}"/>`
    : `<rect data-k="${k}" x="${a}" y="${b}" width="${c}" height="${d}" rx="11"/>`).join('');
  root.innerHTML = `<svg class="fig-plain" viewBox="0 0 200 425" aria-hidden="true">${plain}</svg>
    <div class="fig-art"><i class="fig-base"></i>${PARTS.map(([k]) => `<i class="fp" data-k="${k}"></i>`).join('')}<i class="fig-hair"></i></div>`;
  const art = root.querySelector('.fig-art');
  const els = {};
  for (const el of root.querySelectorAll('[data-k]')) (els[el.dataset.k] = els[el.dataset.k] || []).push(el);
  let sex = '', loaded = 0, masks = {}, look = false, tints = {}, bleeding = new Set(), selected = null;
  let lookColors = {}, skin = '#d5a27c', hair = '#4a3424', headCovered = false;

  function setSex(female) {
    const s = female ? 'female' : 'male';
    if (s === sex) return;
    sex = s;
    loaded = 0;
    masks = {};
    root.classList.remove('art-ok');
    const fileOf = (k, f) => (s === 'female' && k === 'Torso_Upper' ? 'upper_body' : s === 'female' && k === 'Torso_Lower' ? 'lower_body' : f);
    const all = [['base', 'base'], ...PARTS.map(([k, f]) => [k, fileOf(k, f)])];
    for (const [k, f] of all) {
      const path = `ui/defense/${s}_${f}.png`;
      const url = `url("${KnoxArt.mediaUrl(path)}")`;
      const el = k === 'base' ? art.querySelector('.fig-base') : art.querySelector(`.fp[data-k="${k}"]`);
      el.style.webkitMaskImage = url;
      el.style.maskImage = url;
      if (k === 'Head') { const hr = art.querySelector('.fig-hair'); hr.style.webkitMaskImage = url; hr.style.maskImage = url; }
      KnoxArt.media(path).then((img) => {
        if (s !== sex || !img) return;
        if (k === 'base') art.style.aspectRatio = img.naturalWidth + ' / ' + img.naturalHeight;
        if (++loaded >= PARTS.length - 1) root.classList.add('art-ok');
      });
      if (k !== 'base') KnoxArt.alpha(path).then((m) => { if (s === sex && m) masks[k] = m; });
    }
  }

  function paint() {
    root.classList.toggle('look', look);
    const acc = 'var(--accent)';
    for (const [k] of PARTS) {
      let c;
      if (look) {
        c = lookColors[k] || skin;
        if (k === selected) c = `color-mix(in srgb, ${c}, ${acc} 50%)`;
      } else {
        c = tints[k] || 'var(--fig-ok)';
        if (k === selected) c = acc;
      }
      const pulse = !look && bleeding.has(k) && k !== selected;
      for (const el of els[k]) {
        if (el.tagName === 'I') { el.style.backgroundColor = c; el.classList.toggle('bleed', pulse); }
        else { el.style.fill = !look && !tints[k] && k !== selected ? 'var(--surface-3)' : c; el.classList.toggle('bleed', pulse); }
      }
    }
    art.querySelector('.fig-base').style.backgroundColor = look ? `color-mix(in srgb, ${skin}, #000 30%)` : 'var(--surface-3)';
    const hr = art.querySelector('.fig-hair');
    hr.hidden = !look || headCovered;
    hr.style.backgroundColor = hair;
  }

  /** The part whose own picture has a visible pixel under the finger (a little tolerance around it). */
  function hit(bx, by) {
    if (root.classList.contains('art-ok') && Object.keys(masks).length) {
      for (let tol = 0; tol <= 6; tol += 3) {
        for (const [k] of PARTS) {
          const m = masks[k];
          if (!m) continue;
          const px = Math.floor(bx * m.w), py = Math.floor(by * m.h);
          const stepT = Math.max(1, tol);
          for (let dy = -tol; dy <= tol; dy += stepT) {
            for (let dx = -tol; dx <= tol; dx += stepT) {
              const qx = px + dx, qy = py + dy;
              if (qx < 0 || qy < 0 || qx >= m.w || qy >= m.h) continue;
              if (m.a[qy * m.w + qx] > 40) return k;
            }
          }
        }
      }
      return null;
    }
    const x = bx * 200, y = by * 425;
    for (const [k, , t, a, b, c, d] of PARTS) {
      if (t === 'e' ? ((x - a) / c) ** 2 + ((y - b) / d) ** 2 <= 1 : x >= a && x <= a + c && y >= b && y <= b + d) return k;
    }
    return null;
  }

  root.addEventListener('click', (e) => {
    const box = (root.classList.contains('art-ok') ? art : root.querySelector('.fig-plain')).getBoundingClientRect();
    if (e.clientX < box.left || e.clientX > box.right || e.clientY < box.top || e.clientY > box.bottom) { onPart(null); return; }
    onPart(hit((e.clientX - box.left) / box.width, (e.clientY - box.top) / box.height));
  });

  return {
    PARTS,
    setSex,
    /** Colour per part from the body message. */
    update(parts) {
      tints = {};
      bleeding = new Set();
      for (const p of parts) {
        let c = null;
        const hp = p.hp == null ? 100 : p.hp;
        const wound = p.scratch || p.cut || p.deep || p.bite || p.burn || p.fracture || p.glass || p.bullet;
        if (p.bleed) { c = '#ef5d5d'; bleeding.add(p.k); }
        else if (p.band) c = p.dirty ? '#b9a888' : '#e6e9ee';
        else if (hp < 40) c = '#c0503a';
        else if (hp < 75) c = '#c98a2e';
        else if (hp < 100 || wound) c = '#c9b04a';
        if (c) tints[p.k] = c;
      }
      paint();
    },
    setLook(on) { look = !!on; paint(); },
    isLook: () => look,
    setLookColors(colors, skinColor, hairColor, covered) {
      lookColors = colors; skin = skinColor; hair = hairColor; headCovered = covered;
      paint();
    },
    setSelected(k) { selected = k; paint(); },
  };
}
