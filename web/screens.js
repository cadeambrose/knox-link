/* Knox Link — the tab screens. Each one builds its pieces once and updates them from the game's
   messages; the shell (app.js) routes the messages and owns the sheet, toasts and alerts. */
'use strict';

// =================================================================== shared: items and weapons

function itemGroup(it) {
  const c = String(it.c || '').toLowerCase();
  if (it.food || it.fluid || /food|drink|cook/.test(c)) return 'food';
  if (/firstaid|medic|health/.test(c)) return 'medical';
  if (/weapon|ammo|firearm|explosive/.test(c) && !/part/.test(c)) return 'weapon';
  if (/tool|electronic|light|camping|fishing|garden|carpentry|mechanic|farming|trap/.test(c)) return 'tool';
  if (/cloth|accessor|bag|container|protect|wear/.test(c)) return 'clothing';
  return 'other';
}

function fallbackIcon(it) {
  switch (itemGroup(it)) {
    case 'food': return it.fluid && !it.food ? 'drink' : 'food';
    case 'medical': return 'medical';
    case 'weapon': return 'weapon';
    case 'tool': return 'tool';
    case 'clothing': return 'shirt';
    default: return 'box';
  }
}

function itemTags(it) {
  const out = [];
  if (it.eq) out.push(['In hand', 'acc']);
  if (it.worn) out.push([it.loc || 'Worn', '']);   // where it's worn, in the game's words
  const f = it.food;
  if (f) {
    if (f.rotten) out.push(['Rotten', 'bad']); else if (f.stale) out.push(['Stale', 'warn']);
    if (f.burnt) out.push(['Burnt', 'bad']); else if (f.cooked) out.push(['Cooked', 'good']);
    if (f.frozen) out.push(['Frozen', '']);
    if (f.hunger) out.push([`Hunger ${f.hunger > 0 ? '+' : ''}${f.hunger}`, '']);
    if (f.thirst) out.push([`Thirst ${f.thirst > 0 ? '+' : ''}${f.thirst}`, '']);
  }
  const fl = it.fluid;
  if (fl) out.push([`${fl.n ? fl.n + ' ' : ''}${num(fl.amt)}/${num(fl.cap)} L`, fl.amt <= 0 ? 'warn' : '']);
  if (it.use != null) out.push([`${pct(it.use)}% left`, it.use < 0.2 ? 'warn' : '']);
  if (it.cond != null) out.push([`Condition ${pct(it.cond)}%`, it.cond < 0.25 ? 'bad' : it.cond < 0.5 ? 'warn' : '']);
  if (it.ammo != null) out.push([`${it.ammo}/${it.ammoMax}`, it.ammo === 0 ? 'warn' : '']);
  return out;
}

/** One item as a list row; picked: ticked while several are being picked (the tick stands in for its icon). */
function itemRow(it, attrs = '', picked = false) {
  const t = itemTags(it);
  return `<button class="item" ${attrs}>${picked ? `<span class="ii pick">${icon('check')}</span>` : itemIcon(it.ic, fallbackIcon(it))}
    <span class="it-main"><span class="it-name">${esc(it.n)}${it.cnt > 1 ? `<span class="cnt">×${it.cnt}</span>` : ''}</span>${t.length ? `<span class="it-tags">${tags(t)}</span>` : ''}</span>
    <span class="it-w">${num(it.w)}</span></button>`;
}

/**
 * The game's own picture for a place (its inventory button, a bag's or key ring's icon, the loot window's
 * floor, container or body picture): a loose file under media/ui first, else a texture-pack picture.
 */
function placePic(ic, fallback, cls = 'flat') {
  const s = String(ic || '');
  const name = s.replace(/^.*[/\\]/, '').replace(/\.png$/i, '');
  return itemIcon(s ? (/\.png$/i.test(s) && s.startsWith('ui/') ? 'media:' + s + '|' : '') + name : '', fallback, cls);
}

/** Every item id of a row: a stack lists them all, so all of it moves. */
function idsOf(it) {
  const ids = arr(it && it.ids);
  return ids.length ? ids : [it.id];
}

/** "Move to" buttons for a sheet (targets: Map of key -> label); one tap moves the item, or every one of a stack. */
function moveToHtml(targets) {
  if (!targets.size) return '';
  return `<p class="sh-label">Move to</p><div class="move-to">${[...targets].map(([to, label]) =>
    `<button class="mv" data-act="move" data-to="${esc(to)}">${esc(label)}</button>`).join('')}</div>`;
}

// =================================================================== the game's own menus

/**
 * The game's own right-click menu for an item or a thing in the world, as a sheet: its options in
 * its words, greyed out with its reason when they can't be done now; a submenu opens in place.
 * The chosen option runs through the game's own code (the "menuDo" command).
 */
const GameMenu = {
  wait: null,     // the menu this screen asked for: {req, title, target}
  stack: [],      // the menu levels shown: [{title, opts, path, steps}], a submenu on top
  target: null,

  /** target: what the menu is for, as the game add-on wants it ({kind: "item", ids} or {kind: "obj", x, y, z, i, n}). */
  open(title, target) {
    // the "menu" channel goes to every screen: a random number tells this screen's answer apart
    const req = 1 + Math.floor(Math.random() * 2e9);
    this.wait = { req, title, target };
    openSheet(sheetHead(esc(title)) + '<p class="none" id="menuWait">Asking the game…</p>', null, 'menu', { req });
    command('menu', { ...target, req }, null, true, (ok, msg) => {
      if (ok || !this.showing(req)) return;
      this.wait = null;
      const w = $('#menuWait');
      if (w) w.textContent = msg || "The game can't show that menu right now.";
    });
  },

  /** The sheet still waits for this answer (it wasn't closed or replaced meanwhile). */
  showing(req) {
    return !!this.wait && this.wait.req === req && sheetKind === 'menu' && !!sheetArgs && sheetArgs.req === req;
  },

  /** The "menu" message: the game's menu for what a screen asked about. */
  onMenu(d) {
    const w = this.wait;
    if (!w || Number(d.req) !== w.req) return;
    const open = this.showing(w.req);
    this.wait = null;
    if (!open) return;
    this.target = w.target;
    this.stack = [{ title: d.title || w.title, opts: arr(d.opts), path: [] }];
    this.show();
  },

  show() {
    const lvl = this.stack[this.stack.length - 1];
    // the n-th option of the same name is told apart as "name#n", the way the game add-on finds it again
    const seen = {};
    lvl.steps = [];
    let rows = '';
    lvl.opts.forEach((e, i) => {
      if (!e || typeof e !== 'object') return;
      const text = String(e.t == null ? '' : e.t);
      const n = (seen[text] || 0) + 1;
      seen[text] = n;
      lvl.steps[i] = n > 1 ? `${text}#${n}` : text;
      const off = e.d != null && e.d !== false;
      const why = typeof e.d === 'string' ? e.d : '';
      rows += `<button class="row-act gm-opt${off ? ' off' : ''}" data-act="opt" data-i="${i}">${e.ic ? itemIcon(e.ic, 'box', 'sm') : ''}
        <span class="ra-main"><span class="ra-name">${esc(text)}</span>${why ? `<span class="ra-why">${esc(why)}</span>` : ''}</span>
        ${e.s != null ? icon('chevron', 'gm-more') : ''}</button>`;
    });
    const head = this.stack.length > 1
      ? `<button class="gm-back" data-act="back">${icon('chevron')}<span>${esc(lvl.title)}</span></button>`
      : `<h3 class="gm-title">${esc(lvl.title)}</h3>`;
    openSheet(head + (rows ? `<div class="acts gm-list">${rows}</div>` : '<p class="none">Nothing to do with it right now.</p>'), (name, b) => {
      if (name === 'back') { this.stack.pop(); this.show(); return; }
      const i = Number(b.dataset.i);
      const e = lvl.opts[i];
      if (!e) return;
      const here = [...lvl.path, lvl.steps[i]];
      if (e.s != null) {
        this.stack.push({ title: String(e.t), opts: arr(e.s), path: here });
        this.show();
      } else if (e.d != null && e.d !== false) {
        toast(typeof e.d === 'string' && e.d ? e.d : "That can't be done now", true);
      } else {
        command('menuDo', { ...this.target, path: here }, String(e.t));
        closeSheet();
      }
    }, 'menu', { req: 0 });
  },
};

/** Swap weapons like the game's hotbar: everything you carry, in-hand and belt/back first. */
function weaponSheet(outfit) {
  const list = arr(outfit && outfit.weapons);
  const current = list.find((w) => w.eq === 'p');
  let rows = '';
  for (const w of list) {
    if (w.eq === 'p') continue;
    const t = [];
    if (w.slot) t.push([w.slot, 'acc']); else if (w.bag) t.push(['In ' + w.bag, '']);
    if (w.eq === 's') t.push(['Off hand', 'acc']);
    if (w.two) t.push(['Two-handed', '']);
    if (w.ranged) t.push([`${w.ammo || 0}/${w.ammoMax || 0}`, !w.ammo ? 'warn' : '']);
    if (w.broken) t.push(['Broken', 'bad']);
    else if (w.cond != null) t.push([pct(w.cond) + '%', w.cond < 0.25 ? 'bad' : w.cond < 0.5 ? 'warn' : 'good']);
    rows += `<button class="row-act" data-act="swap" data-id="${w.id}">${itemIcon(w.ic, 'weapon', 'big')}
      <span class="ra-main"><span class="ra-name${w.broken ? ' dim' : ''}">${esc(w.n)}</span><span class="it-tags">${tags(t)}</span></span></button>`;
  }
  const html = sheetHead('Weapons', current ? 'In your hands: ' + esc(current.n) : 'Your hands are free.')
    + `<div class="act-pair">${act('weapon', 'Best one', 'best')}${current ? act('hand', 'Put it away', 'away', `data-id="${current.id}"`) : ''}</div>`
    + (rows ? `<div class="acts">${rows}</div>` : `<p class="none">${current ? 'No other weapons on you.' : 'You carry no weapons.'}</p>`);
  openSheet(html, {
    best: () => { command('quick', { a: 'weapon' }, 'Equipping'); closeSheet(); },
    away: (b) => { command('unequip', { id: Number(b.dataset.id) }, 'Putting away'); closeSheet(); },
    swap: (b) => { command('swap', { id: Number(b.dataset.id) }, 'Equipping'); closeSheet(); },
  });
}

// =================================================================== Vitals

const Vitals = {
  METERS: [
    ['hp', 'Health', true], ['endu', 'Endurance', true], ['hunger', 'Hunger', false], ['thirst', 'Thirst', false],
    ['fatigue', 'Tired', false], ['stress', 'Stress', false], ['panic', 'Panic', false], ['pain', 'Pain', false],
    ['boredom', 'Bored', false], ['unhappy', 'Unhappy', false], ['sick', 'Sick', false], ['wet', 'Wet', false]],
  /** action, label, icon, toast while it runs */
  QUICK: [['bandage', 'Bandage', 'bandage', 'Bandaging'], ['eat', 'Eat', 'food', 'Eating'], ['drink', 'Drink', 'drink', 'Drinking'],
    ['pills', 'Painkiller', 'pill', 'Taking a painkiller'], ['light', 'Light', 'light', 'Light'], ['weapon', 'Weapon', 'weapon', 'Equipping'],
    ['sit', 'Rest', 'rest', 'Rest']],

  init() {
    this.fig = Figure($('#figure'), (k) => this.tapBody(k));
    this.avatar = $('#avatar');
    this.angle = 90;
    this.avatarOff = false;
    this.bedNear = false;
    this.selected = null;
    this.treatPart = -1;     // the body part whose treatments the open sheet shows (its index), or -1
    this.treatSig = '';
    this.treatList = [];
    this.moodleSig = '';
    $('#quick').innerHTML = this.QUICK.map(([a, label, ic]) =>
      `<button class="qt" data-q="${a}">${icon(ic)}<span>${label}</span></button>`).join('')
      // Project Viewpoint's camera, only while that mod runs
      + `<button class="qt" data-q="view" hidden>${icon('eye')}<span>3D view</span></button>`;
    this.viewNext = '';
    $('#quick').addEventListener('click', (e) => {
      const b = e.target.closest('[data-q]');
      if (!b) return;
      if (b.dataset.q === 'view') { command('view', {}, null); return; }
      const q = this.QUICK.find((x) => x[0] === b.dataset.q);
      // weapons get a picker: the game's hotbar swap, or the best one in one more tap
      if (q[0] === 'weapon' && S.outfit) weaponSheet(S.outfit);
      else if (q[0] === 'sit' && this.bedNear) this.confirmSleep();
      else command('quick', { a: q[0] }, q[3]);
    });
    $('#meters').innerHTML = this.METERS.map(([k, label], i) =>
      `<div class="meter${i < 2 ? ' big' : ''}" data-k="${k}"><div class="row"><b>${label}</b><span>–</span></div><div class="bar"><i></i></div></div>`).join('');
    $('#flipBtn').addEventListener('click', () => this.flip());
    $('#lookInfo').addEventListener('click', (e) => {
      const c = e.target.closest('[data-worn]');
      const w = c && arr(S.outfit && S.outfit.worn).find((x) => String(x.id) === c.dataset.worn);
      if (w) this.openWorn(w);
    });
    this.attachAvatar();
    this.setLook(load('kl.bodyLook') === '1', true);
  },

  // ------------------------------------------------------------ quick actions

  /** Near a bed, "Rest" becomes "Sleep". */
  setBedNear(near) {
    if (near === this.bedNear) return;
    this.bedNear = near;
    const b = $('#quick [data-q="sit"]');
    b.innerHTML = `${icon(near ? 'moon' : 'rest')}<span>${near ? 'Sleep' : 'Rest'}</span>`;
  },

  /** The 3D view tile names the view a tap switches to, like the game's Back wheel. */
  setView(view) {
    const b = $('#quick [data-q="view"]');
    b.hidden = !view;
    const next = view ? view.n : '';
    if (!view || next === this.viewNext) return;
    this.viewNext = next;
    b.querySelector('span').textContent = next === 'first' ? '1st person' : next === 'third' ? '3rd person' : 'Top-down';
  },

  confirmSleep() {
    openSheet(sheetHead('Go to bed?', 'Your character walks to the nearest bed and sleeps until rested, unless something wakes them. Not while zombies are around.')
      + `<div class="acts">${act('moon', 'Sleep', 'sleep')}${act('rest', 'Just rest here', 'rest')}</div>`, {
      sleep: () => { command('quick', { a: 'sleep' }, 'Sleep'); closeSheet(); },
      rest: () => { command('quick', { a: 'sit' }, 'Rest'); closeSheet(); },
    });
  },

  // ------------------------------------------------------------ look mode

  /** Flips the figure between injuries and how the character looks, like turning a card. */
  flip() {
    if (this.flipping) return;
    this.flipping = true;
    const card = $('#bodyCard');
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) {
      this.setLook(!this.fig.isLook());
      this.flipping = false;
      return;
    }
    card.style.transition = 'transform .13s ease-in';
    card.style.transform = 'perspective(1600px) rotateY(90deg)';
    setTimeout(() => {
      this.setLook(!this.fig.isLook());
      card.style.transition = 'none';
      card.style.transform = 'perspective(1600px) rotateY(-90deg)';
      void card.offsetWidth;
      card.style.transition = 'transform .13s ease-out';
      card.style.transform = '';
      setTimeout(() => { card.style.transition = ''; this.flipping = false; }, 150);
    }, 130);
  },

  setLook(on, first) {
    this.fig.setLook(on);
    if (!on) this.showAvatar(false);
    store('kl.bodyLook', on ? '1' : null);
    $('#bodyTitle').textContent = on ? 'Outfit' : 'Body';
    $('#flipBtn').innerHTML = icon(on ? 'vitals' : 'shirt');
    $('#flipBtn').setAttribute('aria-label', on ? 'Show injuries' : 'Show outfit');
    $('#bodyLegend').hidden = on;
    $('#lookInfo').hidden = !on;
    $('#bodyHint').textContent = on ? "Drag to turn · tap a part to see what's on it" : 'Tap the body to see your character';
    if (on) this.refreshLookInfo();
    else $('#bodyHp').textContent = S.body && S.body.hp != null ? `${S.body.hp}% health` : '';
    if (!first) { resubscribe(); if (on) avatarPoll(); }
  },

  /** True while the live picture should be fetched (look mode on this tab). */
  wantsAvatar() { return this.fig.isLook() && !this.avatarOff; },

  showAvatar(on) {
    this.avatar.hidden = !on;
    $('#figure').style.visibility = on ? 'hidden' : '';
  },

  /** A new frame of the character (an object URL). */
  avatarFrame(url) {
    if (!this.fig.isLook()) { URL.revokeObjectURL(url); return; }
    const old = this.avatarUrl;
    this.avatarUrl = url;
    this.avatar.onload = () => { if (old) URL.revokeObjectURL(old); this.showAvatar(true); };
    this.avatar.src = url;
  },

  /** This game can't draw the character for us: the painted silhouette stands in. */
  avatarUnavailable() {
    this.avatarOff = true;
    this.showAvatar(false);
    resubscribe();   // and the game can stop trying to draw it
  },

  /** Drag sideways to turn the character; tap a part of them to see what they wear there. */
  attachAvatar() {
    const av = this.avatar;
    let down = null, dragging = false, lastSent = 0;
    av.addEventListener('pointerdown', (e) => {
      down = { x: e.clientX, y: e.clientY };
      dragging = false;
      av.setPointerCapture(e.pointerId);
      e.preventDefault();
    });
    av.addEventListener('pointermove', (e) => {
      if (!down) return;
      const dx = e.clientX - down.x;
      if (!dragging && Math.abs(dx) > 8) dragging = true;
      if (!dragging) return;
      this.angle = ((this.angle - dx * 0.8) % 360 + 360) % 360;
      down.x = e.clientX;
      const now = Date.now();
      if (now - lastSent > 90) { lastSent = now; command('avatarTurn', { deg: Math.round(this.angle) }, null, true); }
    });
    const up = (e) => {
      if (!down) return;
      if (dragging) command('avatarTurn', { deg: Math.round(this.angle) }, null, true);
      else if (e.type === 'pointerup') {
        const part = this.avatarPart(e.clientX, e.clientY);
        if (part) this.openOutfitPart(part); else this.flip();
      }
      down = null;
    };
    av.addEventListener('pointerup', up);
    av.addEventListener('pointercancel', up);
    av.addEventListener('dragstart', (e) => e.preventDefault());
  },

  /** Which body part a tap on the picture is on (the model stands centred, head at the top). */
  avatarPart(cx, cy) {
    const r = this.avatar.getBoundingClientRect();
    // the picture keeps its 1:2 shape inside the box
    const s = Math.min(r.width / 240, r.height / 480);
    const iw = 240 * s, ih = 480 * s;
    const fx = (cx - r.left - (r.width - iw) / 2) / iw, fy = (cy - r.top - (r.height - ih) / 2) / ih;
    if (fx < 0.1 || fx > 0.9 || fy < 0.03 || fy > 0.99) return null;
    const facing = this.angle > 0 && this.angle < 180;
    // facing you, their right hand is on your left
    const side = ((fx < 0.5) === facing) ? '_R' : '_L';
    const off = Math.abs(fx - 0.5);
    if (fy < 0.16) return off < 0.12 ? 'Head' : null;
    if (fy < 0.21) return off < 0.1 ? 'Neck' : null;
    if (fy < 0.62 && off > 0.17) {
      if (fy > 0.48) return 'Hand' + side;
      return fy > 0.36 ? 'ForeArm' + side : 'UpperArm' + side;
    }
    if (fy < 0.36) return 'Torso_Upper';
    if (fy < 0.47) return 'Torso_Lower';
    if (fy < 0.53) return 'Groin';
    if (fy < 0.71) return 'UpperLeg' + side;
    if (fy < 0.9) return 'LowerLeg' + side;
    return 'Foot' + side;
  },

  tapBody(key) {
    if (key == null) { this.flip(); return; }
    if (this.fig.isLook()) { this.openOutfitPart(key); return; }
    if (this.treatable(key)) this.openPart(key);
    else this.flip();
  },

  partData(key) { return S.body ? arr(S.body.parts).find((p) => p.k === key) : null; },

  partName(key) {
    const p = this.partData(key);
    if (p && p.n) return p.n;
    return key.replace('_L', ' (left)').replace('_R', ' (right)').replace('_', ' ');
  },

  /** Something to see or do on this part in the health view. */
  treatable(key) {
    const p = this.partData(key);
    if (!p) return false;
    return !!(p.bleed || p.scratch || p.cut || p.deep || p.bite || p.burn || p.fracture || p.glass || p.bullet || p.band || p.infected
      || (p.hp != null && p.hp < 100));
  },

  /** What the character wears and carries (the "outfit" message). */
  outfit(d) {
    this.recolor();
  },

  recolor() {
    const o = S.outfit;
    if (!o) return;
    const colors = {};
    let headCovered = false;
    for (const e of arr(o.worn)) {   // inner layers first: the outer one wins
      const parts = arr(e.parts);
      if (!parts.length) continue;
      const c = this.itemColor(e);
      for (const p of parts) { colors[p] = c; if (p === 'Head') headCovered = true; }
    }
    this.fig.setLookColors(colors, hexColor(o.skin, '#d5a27c'), hexColor(o.hair, '#4a3424'), headCovered);
    if (this.fig.isLook()) this.refreshLookInfo();
  },

  /** The colour the game tints it in, or the main colour of its icon. */
  itemColor(e) {
    if (e.c) return hexColor(e.c, '#8a8f98');
    if (!e.ic) return '#8a8f98';
    this.iconColors = this.iconColors || {};
    if (e.ic in this.iconColors) return this.iconColors[e.ic] || '#8a8f98';
    this.iconColors[e.ic] = null;
    KnoxArt.iconColor(e.ic).then((c) => { this.iconColors[e.ic] = c; if (c) this.recolor(); });
    return '#8a8f98';
  },

  refreshLookInfo() {
    const o = S.outfit;
    const el = $('#lookInfo');
    if (!o) { el.innerHTML = '<p class="muted small">Looking at your clothes…</p>'; return; }
    let bite = 0, scratch = 0, n = 0;
    for (const p of Object.values(o.prot || {})) { if (!p) continue; bite += p.b || 0; scratch += p.s || 0; n++; }
    $('#bodyHp').textContent = '';
    let html = '';
    if (n) html += `<div class="tagrow">${tag('Bite protection ' + Math.round(bite / n) + '%', bite / n >= 30 ? 'good' : '')}${tag('Scratch protection ' + Math.round(scratch / n) + '%', scratch / n >= 50 ? 'good' : '')}</div>`;
    // a bag, a belt, a watch: worn, but not on a body part
    const extra = arr(o.worn).filter((e) => !arr(e.parts).length);
    if (extra.length) html += `<div class="tagrow">${extra.map((e) => `<button class="tag chip" data-worn="${e.id}">${esc(e.n)}</button>`).join('')}</div>`;
    if (el.innerHTML !== html) el.innerHTML = html;
  },

  openOutfitPart(key) {
    this.selected = key;
    this.fig.setSelected(key);
    const o = S.outfit;
    const pp = o && o.prot && o.prot[key];
    let html = sheetHead(esc(this.partName(key)), pp ? `Protection: bite ${pp.b || 0}% · scratch ${pp.s || 0}%` : '');
    const worn = arr(o && o.worn).slice().reverse().filter((e) => arr(e.parts).includes(key));   // outermost first
    html += worn.length ? `<div class="acts">${worn.map((e) => this.wornRow(e)).join('')}</div>` : '<p class="none">Nothing on it.</p>';
    if (key.startsWith('Hand')) {
      const hands = S.core && S.core.hands;
      let held = hands ? hands[key.endsWith('_R') ? 'p' : 's'] : null;
      if (hands && key.endsWith('_L') && hands.both) held = hands.p;
      html += `<p class="sh-label">In this hand</p><p class="sh-big">${held ? esc(held.n) : 'Nothing'}</p>`;
      if (o) html += `<div class="acts">${act('weapon', 'Switch weapon…', 'weapons')}</div>`;
    }
    openSheet(html, {
      worn: (b) => { const e = arr(S.outfit && S.outfit.worn).find((x) => String(x.id) === b.dataset.id); if (e) this.openWorn(e); },
      weapons: () => weaponSheet(S.outfit),
    });
  },

  wornTags(e) {
    const out = [];
    if (e.cond != null) out.push([`Condition ${pct(e.cond)}%`, e.cond < 0.25 ? 'bad' : e.cond < 0.5 ? 'warn' : '']);
    if (e.holes > 0) out.push([e.holes + (e.holes === 1 ? ' hole' : ' holes'), 'warn']);
    if (e.bite > 0) out.push([`Bite protection ${e.bite}%`, '']);
    if (e.scratch > 0) out.push([`Scratch protection ${e.scratch}%`, '']);
    if (e.wet != null) out.push(['Wet', 'warn']);
    if (e.blood) out.push(['Bloody', 'bad']);
    return out;
  },

  wornRow(e) {
    const t = this.wornTags(e);
    return `<button class="row-act" data-act="worn" data-id="${e.id}">${itemIcon(e.ic, 'shirt', 'big')}
      <span class="ra-main"><span class="ra-name">${esc(e.n)}</span>${t.length ? `<span class="it-tags">${tags(t)}</span>` : ''}</span></button>`;
  },

  openWorn(e) {
    const t = this.wornTags(e);
    openSheet(`<div class="sh-item">${itemIcon(e.ic, 'shirt', 'huge')}<h3>${esc(e.n)}</h3></div>`
      + (t.length ? `<div class="sh-facts">${tags(t)}</div>` : '')
      + `<div class="acts">${act('shirt', 'Take it off', 'off')}</div>`, {
      off: () => { command('takeoff', { id: e.id }, 'Taking off'); closeSheet(); },
    });
  },

  core(c) {
    const p = c.p || {};
    this.setBedNear(!!p.bed);
    this.setView(c.view || null);
    this.fig.setSex(!!p.female);
    const b = c.bars;
    // Immersion sends no numbers for needs: then the moodles say it, like in the game
    const numbers = !!(b && b.hunger != null);
    $('#condCard').hidden = !numbers;
    if (numbers) {
      for (const [k, , high] of this.METERS) {
        const el = $(`.meter[data-k="${k}"]`);
        const v = Math.max(0, Math.min(1, b[k] || 0));
        const col = meterColor(v, high);
        const span = $('span', el);
        span.textContent = pct(v) + '%';
        span.style.color = col === 'var(--text-3)' || col === 'var(--good)' ? '' : col;
        const i = $('i', el);
        i.style.width = pct(v) + '%';
        i.style.background = col;
      }
      $('#tempLabel').textContent = b.temp > 0 ? 'Body ' + Fmt.bodyTemp(b.temp) : '';
    }
    const list = arr(c.m);
    const sig = list.map((m) => m.id + m.l).join(',') + (document.documentElement.classList.contains('art-moodle') ? '+' : '-');
    if (sig === this.moodleSig) return;
    this.moodleSig = sig;
    $('#moodleCount').textContent = list.length ? String(list.length) : '';
    $('#moodles').innerHTML = list.length ? list.map((m) => {
      const color = moodleColor(m.g, m.l);
      const pips = [1, 2, 3, 4].map((i) => `<i style="background:${i <= m.l ? color : ''}"></i>`).join('');
      return `<div class="moodle">${moodleTile(moodleIcon(m.id, m.l), color)}
        <div class="m-main"><div class="m-name">${esc(m.n)}</div>${m.d ? `<div class="m-desc">${esc(m.d)}</div>` : ''}</div>
        <div class="pips">${pips}</div></div>`;
    }).join('') : '<p class="none">Feeling fine.</p>';
  },

  body(d) {
    this.fig.update(arr(d.parts));
    if (!this.fig.isLook()) $('#bodyHp').textContent = d.hp != null ? `${d.hp}% health` : '';
    // the open body part: the facts stay steady while the sheet is open; the treatments follow what you have
    if (this.treatPart >= 0 && this.selected) {
      const p = this.partData(this.selected);
      if (p) this.showTreatments(p);
    }
  },

  /** A hurt body part: what's wrong with it in the game's words, and every treatment its health panel offers there. */
  openPart(key) {
    const p = this.partData(key);
    if (!p) return;
    this.selected = key;
    this.fig.setSelected(key);
    this.treatPart = p.i;
    this.treatSig = '';
    this.treatList = [];
    openSheet(sheetHead(esc(p.n), `${p.hp}% healthy${p.pain ? ` · pain ${p.pain}` : ''}`)
      + `<div class="sh-facts">${this.woundFacts(p)}</div><div id="treatBox"></div>`, {
      treat: (b) => {
        const e = this.treatList[Number(b.dataset.k)];
        if (!e) return;
        command('treat', { part: this.treatPart, a: e.a, i: e.i || '' }, e.a);
        closeSheet();
      },
    }, 'part', key);
    this.showTreatments(p);
    // the game looks at this part's treatments while the sheet is open
    command('healthPart', { part: p.i }, null);
  },

  /** What's wrong with a body part, in the game's own words and colours (its health panel's lines). */
  woundFacts(p) {
    const lines = arr(p.lines);
    if (lines.length) return lines.map((l) => tag(l.t, ['bad', 'warn', 'good'].includes(l.k) ? l.k : '')).join('');
    const w = this.wounds(p);   // an older game add-on: the plain facts
    return w.length ? tags(w) : tag('No wounds', 'good');
  },

  /** The treatments the game's health panel offers on the open part, in treating order (body.sel). */
  showTreatments(p) {
    const box = $('#treatBox');
    if (!box || sheetKind !== 'part') return;
    const sel = S.body && S.body.sel;
    const known = !!sel && Number(sel.i) === this.treatPart;
    const list = known ? arr(sel.t) : [];
    const sig = known ? JSON.stringify(list) : '?';
    if (sig === this.treatSig) return;
    this.treatSig = sig;
    this.treatList = list;
    if (!known) { box.innerHTML = '<p class="none">Looking at what you have…</p>'; return; }
    if (!list.length) {
      box.innerHTML = `<p class="none">${this.treatable(p.k) ? 'You have nothing to treat this with.' : 'Nothing to treat here.'}</p>`;
      return;
    }
    box.innerHTML = '<p class="sh-label">Treat</p><div class="acts">' + list.map((e, k) => `<button class="row-act" data-act="treat" data-k="${k}">
      ${itemIcon(e.ic, 'bandage')}<span class="ra-main"><span class="ra-name">${esc(e.a)}</span>${e.i ? `<span class="ra-sub">${esc(e.i)}</span>` : ''}</span></button>`).join('') + '</div>';
  },

  sheetClosed() {
    if (this.treatPart >= 0) command('healthPart', { part: -1 }, null, true);
    this.treatPart = -1;
    this.treatList = [];
    this.selected = null;
    this.fig.setSelected(null);
  },

  wounds(p) {
    const w = [];
    if (p.bleed) w.push(['Bleeding', 'bad']);
    if (p.bite) w.push(['Bite', 'bad']);
    if (p.deep) w.push(['Deep wound', 'bad']);
    if (p.glass) w.push(['Glass shard', 'bad']);
    if (p.bullet) w.push(['Bullet', 'bad']);
    if (p.infected) w.push(['Infected', 'bad']);
    if (p.fracture) w.push([p.splint ? 'Fracture (splinted)' : 'Fracture', 'warn']);
    if (p.burn) w.push(['Burn', 'warn']);
    if (p.cut) w.push(['Laceration', 'warn']);
    if (p.scratch) w.push(['Scratch', 'warn']);
    if (p.stitch) w.push(['Stitched', 'good']);
    if (p.band) w.push([p.dirty ? 'Dirty bandage' : 'Bandaged', p.dirty ? 'warn' : 'good']);
    return w;
  },
};

// =================================================================== Gear

const Gear = {
  init() {
    this.filter = 'all';
    this.seg = load('kl.gearSeg') || 'on';
    this.weaponsSig = '';
    this.items = new Map();
    this.inContainer = new Map();   // item id -> the container of yours it's in (0 = your inventory, else the bag's id)
    this.selecting = false;         // "Move items": picking several items to move at once
    this.picked = new Set();
    this.targetsSig = '';
    $('#gearSeg').addEventListener('click', (e) => { const b = e.target.closest('[data-seg]'); if (b) this.setSegment(b.dataset.seg); });
    $('#gearFilter').addEventListener('click', (e) => {
      if (e.target.closest('[data-select]')) { this.setSelecting(!this.selecting); return; }
      const b = e.target.closest('[data-f]');
      if (!b) return;
      this.filter = b.dataset.f;
      $$('#gearFilter [data-f]').forEach((x) => x.classList.toggle('on', x === b));
      if (S.gear) this.gear(S.gear);
    });
    $('#containers').addEventListener('click', (e) => {
      const all = e.target.closest('[data-pickall]');
      if (all) { this.pickAll(all.dataset.pickall.split(',').map(Number)); return; }
      const r = e.target.closest('[data-item]');
      if (!r) return;
      const id = Number(r.dataset.item);
      if (!this.selecting) { this.openItem(id); return; }
      // picking several: a tap ticks it, the icon shows the tick (what you wear comes off first)
      const it = this.items.get(id);
      if (it && it.worn) { toast('Take it off first to move it', true); return; }
      this.togglePick(id);
    });
    $('#pickTargets').addEventListener('click', (e) => {
      const b = e.target.closest('[data-to]');
      if (b) this.movePickedTo(b.dataset.to, b.dataset.where);
    });
    $('#hands').addEventListener('click', (e) => {
      const h = e.target.closest('[data-hand]');
      if (h && this.items.has(Number(h.dataset.hand))) this.openItem(Number(h.dataset.hand));
    });
    $('#hotbar').addEventListener('click', (e) => {
      const t = e.target.closest('[data-slot],[data-all]');
      if (!t) return;
      if (t.dataset.all) { weaponSheet(S.outfit); return; }
      command('hotbar', { i: Number(t.dataset.slot) }, t.dataset.held ? 'Putting away' : 'Drawing');
    });
    this.initAround();
    $('#craftSearch').addEventListener('input', () => this.renderCraft());
    $('#craftList').addEventListener('click', (e) => {
      const r = e.target.closest('[data-recipe]');
      if (r) command('craft', { id: r.dataset.recipe }, 'Making');
    });
    this.setSegment(this.seg, true);
  },

  /** Around you: a tap takes (or opens a stack's sheet), pressing and holding (or a right-click) opens the item's sheet. */
  initAround() {
    const el = $('#gearAround');
    let timer = 0, sx = 0, sy = 0;
    const stop = () => { clearTimeout(timer); timer = 0; };
    el.addEventListener('pointerdown', (e) => {
      this.held = false;
      stop();
      const row = e.target.closest('[data-take]');
      if (!row || e.button > 0) return;
      sx = e.clientX;
      sy = e.clientY;
      timer = setTimeout(() => { timer = 0; this.held = true; this.holdAround(row); }, 500);
    });
    el.addEventListener('pointermove', (e) => { if (timer && Math.hypot(e.clientX - sx, e.clientY - sy) > 10) stop(); });
    for (const ev of ['pointerup', 'pointercancel', 'pointerleave']) el.addEventListener(ev, stop);
    el.addEventListener('contextmenu', (e) => {
      const row = e.target.closest('[data-take]');
      if (!row) return;
      e.preventDefault();
      stop();
      if (!this.held) { this.held = true; this.holdAround(row); }
    });
    el.addEventListener('click', (e) => {
      // the tap that ends a press-and-hold doesn't also take the item
      if (this.held) { this.held = false; return; }
      this.tapAround(e);
    });
  },

  setSegment(s, first) {
    // moving items works on you and around you (straight from a shelf into a bag)
    if (s === 'craft' && this.selecting) this.setSelecting(false);
    this.seg = s;
    store('kl.gearSeg', s);
    $('#gearOn').hidden = s !== 'on';
    $('#gearAround').hidden = s !== 'around';
    $('#gearCraft').hidden = s !== 'craft';
    $$('#gearSeg button').forEach((b) => b.classList.toggle('on', b.dataset.seg === s));
    $('#view-gear').scrollTop = 0;
    if (!first) resubscribe();
  },

  core(c) {
    const h = c.hands || {};
    const sig = JSON.stringify(h);
    if (sig !== this.handsSig) {
      this.handsSig = sig;
      $('#hands').innerHTML = this.hand('Main hand', h.p, h.both) + this.hand('Off hand', h.both ? null : h.s, false);
    }
    const b = c.bars || {};
    if (b.weightMax > 0) {
      const r = b.weight / b.weightMax;
      $('#weightText').textContent = `${num(b.weight)} / ${num(b.weightMax)}`;
      const i = $('#weightBar');
      i.style.width = Math.min(100, r * 100) + '%';
      i.style.background = r > 1 ? 'var(--bad)' : r > 0.8 ? 'var(--warn)' : 'var(--good)';
    }
  },

  hand(label, it, both) {
    if (!it) {
      return `<div class="card hand empty"><span class="ii">${icon('hand')}</span><span class="h-main"><span class="h-label">${label}</span><span class="h-name">Empty</span></span></div>`;
    }
    const sub = [];
    if (both) sub.push('Both hands');
    if (it.cond != null) sub.push(`Condition ${pct(it.cond)}%`);
    if (it.ammo != null) sub.push(`Ammo ${it.ammo}/${it.ammoMax}${it.chambered ? ' +1' : ''}`);
    if (it.use != null) sub.push(`${pct(it.use)}% left`);
    return `<button class="card hand" data-hand="${it.id}">${itemIcon(it.ic, 'hand')}<span class="h-main"><span class="h-label">${label}</span>
      <span class="h-name">${esc(it.n)}</span>${sub.length ? `<span class="h-sub">${esc(sub.join(' · '))}</span>` : ''}</span></button>`;
  },

  /** The hotbar strip (and the way to every other weapon), from the "outfit" message. */
  outfit(d) {
    const slots = arr(d.hotbar), weapons = arr(d.weapons);
    const sig = JSON.stringify(slots) + weapons.length;
    if (sig === this.weaponsSig) return;
    this.weaponsSig = sig;
    $('#hotbarCard').hidden = !slots.length && !weapons.length;
    let html = '';
    for (const s of slots) {
      const has = s.id != null, held = !!s.eq;
      const cd = s.cond;
      const barHtml = has && cd != null ? bar(cd, cd < 0.25 ? 'var(--bad)' : cd < 0.5 ? 'var(--warn)' : 'var(--good)', 'thin') : '<div class="bar thin hidden"></div>';
      html += `<button class="slot${held ? ' held' : ''}${has ? '' : ' free'}" ${has ? `data-slot="${s.i}"` : 'disabled'} ${held ? 'data-held="1"' : ''}
        title="${esc(s.item || s.n || '')}">${itemIcon(has ? s.ic : '', 'weapon', 'big')}<span class="slot-name">${esc(s.n || '')}</span>${barHtml}</button>`;
    }
    // every weapon you carry, bags included: the game's hotbar swap
    if (weapons.length) html += `<button class="slot more" data-all="1">${icon('weapon')}<span class="slot-name">All weapons</span></button>`;
    $('#hotbar').innerHTML = html;
  },

  gear(d) {
    this.items.clear();
    this.inContainer.clear();
    const conts = arr(d.containers);
    for (const c of conts) for (const it of arr(c.items)) { this.items.set(it.id, it); this.inContainer.set(it.id, c.id); }
    // picked things that went somewhere else aren't picked any more
    for (const id of this.picked) if (!this.items.has(id) && !this.aroundById(id)) this.picked.delete(id);
    this.updatePickBar();
    // the game resends the same list now and then: repainting it would swallow a tap in progress
    const sig = JSON.stringify(d) + this.filter + (this.selecting ? '|' + [...this.picked].join(',') : '');
    if (sig === this.gearSig) return;
    this.gearSig = sig;
    let html = '';
    for (const c of conts) {
      const hands = [], worn = [], rows = [];
      for (const it of arr(c.items)) {
        if (this.filter !== 'all' && itemGroup(it) !== this.filter) continue;
        // your inventory splits into what's in your hands, what you wear and what you carry
        if (c.id === 0 && it.eq) hands.push(it);
        else if (c.id === 0 && it.worn) worn.push(it);
        else rows.push(it);
      }
      const weight = `${num(c.w)}${c.cap > 0 ? ' / ' + num(c.cap) : ''}`;
      if (c.id === 0) {
        if (hands.length) html += this.section('In your hands', 'hand', null, hands);
        if (worn.length) html += this.section('Wearing', 'shirt', null, worn);
        if (rows.length || this.filter === 'all') html += this.section('Carrying', 'gear', weight, rows, c.ic);
      } else if (rows.length || this.filter === 'all') {
        html += this.section(c.n, c.ring ? 'key' : 'bag', weight, rows, c.ic);
      }
    }
    $('#containers').innerHTML = html || '<p class="none">Nothing here.</p>';
  },

  /** One section of what you have on you: a heading and its items in a card. */
  section(title, ic, right, rows, tex) {
    let all = '';
    if (this.selecting) {
      // the whole section in one tap (what you wear stays: it comes off first)
      const ids = rows.filter((it) => !it.worn).map((it) => it.id);
      if (ids.length) all = this.pickAllBtn(ids);
    }
    return `<div class="cont"><div class="cont-head">${tex ? placePic(tex, ic, 'flat head') : icon(ic)}<b>${esc(title)}</b>${all}${right != null ? `<span>${right}</span>` : ''}</div>
      <div class="items">${rows.map((it) => itemRow(it, `data-item="${it.id}"${this.selecting && it.worn ? ' data-worn="1"' : ''}`,
        this.selecting && this.picked.has(it.id))).join('') || '<p class="none pad">Empty</p>'}</div></div>`;
  },

  // ------------------------------------------------------------ moving things

  /**
   * Where an item could go, for "Move to": your inventory, each bag you carry, the containers
   * around you and the floor. Map of key -> label; skips the place it is in (and a bag into itself).
   */
  moveTargets(itemId, from, fromNear) {
    const out = new Map();
    if (from == null || from !== 0) out.set('inv', 'Inventory');
    for (const c of arr(S.gear && S.gear.containers)) {
      if (c.id === 0 || c.id === itemId || (from != null && from === c.id)) continue;
      out.set('bag:' + c.id, c.n);
    }
    if (S.loot && fromNear == null) {
      const named = {};   // three "Drawer"s become Drawer, Drawer 2, Drawer 3
      for (const c of arr(S.loot.list)) {
        if (c.corpse) continue;
        if (c.floor) { out.set('floor', 'Floor'); continue; }
        const k = (named[c.n] || 0) + 1;
        named[c.n] = k;
        out.set('near:' + c.k, k > 1 ? `${c.n} ${k}` : c.n);
      }
    }
    return out;
  },

  // "Move items": tap the items (or "All" for a whole bag or container), then tap where they go in the
  // bar at the bottom. The bar stays for the next batch until "Done". Works around you too.
  setSelecting(on) {
    this.selecting = on;
    this.picked.clear();
    const b = $('#gearFilter [data-select]');
    b.textContent = on ? 'Done' : 'Move items';
    b.classList.toggle('on', on);
    this.targetsSig = '';
    this.pickChanged();
  },

  togglePick(id) {
    if (!this.picked.delete(id)) this.picked.add(id);
    this.pickChanged();
  },

  /** "All" on a section or container: picks every item in it, or none if they all were. */
  pickAll(ids) {
    if (ids.every((id) => this.picked.has(id))) ids.forEach((id) => this.picked.delete(id));
    else ids.forEach((id) => this.picked.add(id));
    this.pickChanged();
  },

  pickAllBtn(ids) {
    return `<button class="mini-btn" data-pickall="${ids.join(',')}">${ids.every((id) => this.picked.has(id)) ? 'None' : 'All'}</button>`;
  },

  pickChanged() {
    if (S.gear) this.gear(S.gear);
    this.lootSig = '';
    if (S.loot) this.loot(S.loot);
    this.updatePickBar();
  },

  /** An item around you by its id (a stack's first one), or null. */
  aroundById(id) {
    for (const c of arr(S.loot && S.loot.list)) {
      const it = arr(c.items).find((x) => x.id === id);
      if (it) return it;
    }
    return null;
  },

  /** The bar: how many are picked, and every place they can go (your bags, around you, the floor). */
  updatePickBar() {
    $('#pickBar').hidden = !this.selecting;
    if (!this.selecting) return;
    const n = this.picked.size;
    $('#pickCount').textContent = n ? `${n === 1 ? '1 picked' : n + ' picked'} · tap where they go` : 'Tap the items to move, then where they go';
    const targets = this.moveTargets(-1, null, null);
    const sig = JSON.stringify([...targets]) + (n > 0);
    if (sig === this.targetsSig) return;
    this.targetsSig = sig;
    const el = $('#pickTargets');
    el.classList.toggle('idle', !n);
    el.innerHTML = [...targets].map(([to, label]) => {
      const [tex, fb] = this.targetPicture(to);
      return `<button data-to="${esc(to)}" data-where="${esc(label)}">${placePic(tex, fb, 'flat chip')}${esc(label)}</button>`;
    }).join('');
  },

  /** The game's own picture for a place to move to (the inventory button, a bag's icon, the loot window's), and the line icon until then. */
  targetPicture(to) {
    if (to === 'inv') return ['Icon_InventoryBasic', 'gear'];
    if (to.startsWith('bag:')) {
      const c = arr(S.gear && S.gear.containers).find((x) => 'bag:' + x.id === to);
      return [c && c.ic, c && c.ring ? 'key' : 'bag'];
    }
    const list = arr(S.loot && S.loot.list);
    if (to === 'floor') {
      const c = list.find((x) => x.floor);
      return [(c && c.ic) || 'Container_Floor', 'layers'];
    }
    const c = list.find((x) => 'near:' + x.k === to);
    return [c && c.ic, 'box'];
  },

  movePickedTo(to, where) {
    const ids = [];
    let worn = 0;
    for (const id of this.picked) {
      const it = this.items.get(id) || this.aroundById(id);
      if (!it) continue;
      if (it.worn) { worn++; continue; }   // worn things come off first
      ids.push(...idsOf(it));
    }
    if (!ids.length) { toast(worn ? 'Take worn things off first' : 'Tap the items to move first', true); return; }
    command('move', { ids, to }, 'Moving to ' + where);
    this.picked.clear();
    this.pickChanged();
  },

  openItem(id) {
    const it = this.items.get(id);
    if (!it) return;
    const t = itemTags(it);
    let acts = '', n = 0;
    const defs = {
      equip1: ['hand', 'Equip in main hand', 'Equipping'], equip2: ['hand', 'Equip in off hand', 'Equipping'],
      unequip: ['x', 'Put away', 'Putting away'], read: ['book', 'Read', 'Reading'], pill: ['pill', 'Take one', 'Taking'],
      wear: ['shirt', 'Wear', 'Putting on'], takeoff: ['shirt', 'Take off', 'Taking off'],
    };
    if (it.lg) {
      // a diary someone left behind: read here, page by page
      acts += act('book', 'Read the diary', 'diary');
      n++;
    }
    for (const a of arr(it.a)) {
      if (a === 'read' && it.lg) continue;
      if (a === 'eat' || a === 'drink') {
        acts += `<p class="sh-label">${a === 'eat' ? 'Eat' : 'Drink'}</p><div class="act-row">`
          + [['1', 'All'], ['0.5', 'Half'], ['0.25', 'Quarter']].map(([p, l]) => act(null, l, a, `data-portion="${p}"`)).join('') + '</div>';
        n++;
      } else if (defs[a]) {
        acts += act(defs[a][0], defs[a][1], a);
        n++;
      }
    }
    const ids = idsOf(it);
    openSheet(`<div class="sh-item">${itemIcon(it.ic, fallbackIcon(it), 'huge')}<div><h3>${esc(it.n)}${it.cnt > 1 ? ` <span class="muted">×${it.cnt}</span>` : ''}</h3>
        <p class="sh-sub">${esc(it.c || '')} · weight ${num(it.w)}</p></div></div>`
      + (t.length ? `<div class="sh-facts">${tags(t)}</div>` : '')
      + (n ? `<div class="acts">${acts}</div>` : '')
      // anywhere else you can put it: your bags, the containers around you, the floor (not while worn)
      + (it.worn ? '' : moveToHtml(this.moveTargets(id, this.inContainer.get(id), null)))
      // every action the game's own right-click menu has for it
      + `<div class="acts${n ? ' after' : ''}">${act('more', 'All actions…', 'menu')}</div>`, (name, b) => {
      if (name === 'move') {
        command('move', { ids, to: b.dataset.to }, 'Moving');
      } else if (name === 'menu') {
        GameMenu.open(it.n, { kind: 'item', ids });
        return;
      } else if (name === 'diary') {
        Diaries.read(String(it.lg));   // it opens when the game has sent it
      } else if (name === 'eat' || name === 'drink') {
        command(name, { id, portion: Number(b.dataset.portion) }, name === 'eat' ? 'Eating' : 'Drinking');
      } else if (defs[name]) {
        const args = { id };
        if (name === 'equip1') args.hand = 'primary';
        if (name === 'equip2') args.hand = 'secondary';
        command(name.startsWith('equip') ? 'equip' : name, args, defs[name][2]);
      } else return;
      closeSheet();
    });
  },

  // ------------------------------------------------------------ around you

  /**
   * What's around you (the "loot" message): the containers the game's loot window lists, the
   * bodies a few steps away, and the things around you with a menu of their own.
   */
  loot(d) {
    const list = arr(d.list), objs = arr(d.objs);
    const withItems = list.filter((c) => !c.floor && arr(c.items).length).length;
    $('#gearSeg [data-seg="around"]').textContent = withItems ? 'Around you · ' + withItems : 'Around you';
    this.updatePickBar();   // the containers around you are places to move things to
    const sig = JSON.stringify(list) + JSON.stringify(objs) + settings.units + (this.selecting ? '|' + [...this.picked].join(',') : '');
    if (sig === this.lootSig) return;
    this.lootSig = sig;
    // the one the game has open first, then the ones with something in them, bodies nearest first; empty ones last
    const rank = (c) => (c.sel ? 0 : c.corpse ? 2 : arr(c.items).length ? (c.floor ? 3 : 1) : 4);
    const order = list.slice().sort((a, b) => rank(a) - rank(b));
    let html = order.map((c) => {
      const items = arr(c.items);
      // the loot window's own button picture: a loose file under media/ui, or one from the packs
      const pic = placePic(c.ic, c.floor ? 'layers' : 'box');
      let sub = num(c.w) + (c.cap > 0 ? ' / ' + num(c.cap) : '');
      if (c.corpse) {
        const away = Number(c.d) || 0;
        sub = `${away < 1.2 ? 'Next to you' : Fmt.near(away) + ' away'} · ${num(c.w)}`;
      }
      return `<div class="card cont-card"><div class="cc-head">${pic}<span class="cc-main"><b>${esc(c.n)}</b>
          <span>${items.length ? sub : 'Empty'}</span></span>
          ${!items.length ? '' : this.selecting ? this.pickAllBtn(items.map((it) => it.id)) : `<button class="mini-btn" data-takeall="${c.k}">Take all</button>`}</div>
        ${items.length ? `<div class="items flush">${items.map((it) => itemRow(it, `data-take="${it.id}" data-k="${c.k}"`, this.selecting && this.picked.has(it.id))).join('')}</div>` : ''}</div>`;
    }).join('');
    // the things around you that have a menu of their own; one tap opens the game's menu for that spot
    if (objs.length) {
      html += `<div class="card cont-card"><div class="cc-head"><span class="ii flat">${icon('hand')}</span><span class="cc-main"><b>Things around you</b></span></div>
        <div class="items flush">${objs.map((o, i) => `<button class="item thing" data-obj="${i}"><span class="it-main"><span class="it-name">${esc(o.n)}</span></span>
          <span class="it-w">${Fmt.near(Number(o.d) || 0)}</span></button>`).join('')}</div></div>`;
    }
    $('#gearAround').innerHTML = html || emptyState('box', 'Nothing around you', "Walk up to shelves, cupboards, cars or bodies. Whatever the game's loot window shows appears here, ready to take.");
  },

  /** The item a row of Around you stands for, and its container. */
  aroundItem(row) {
    const cont = arr(S.loot && S.loot.list).find((c) => String(c.k) === row.dataset.k);
    const it = cont && arr(cont.items).find((x) => String(x.id) === row.dataset.take);
    return it ? { it, cont } : null;
  },

  tapAround(e) {
    const pick = e.target.closest('[data-pickall]');
    if (pick) { this.pickAll(pick.dataset.pickall.split(',').map(Number)); return; }
    const all = e.target.closest('[data-takeall]');
    if (all) { command('takeAll', { k: Number(all.dataset.takeall) }, 'Taking'); return; }
    const obj = e.target.closest('[data-obj]');
    if (obj) {
      const o = arr(S.loot && S.loot.objs)[Number(obj.dataset.obj)];
      if (o) GameMenu.open(o.n, { kind: 'obj', x: o.x, y: o.y, z: o.z, i: o.i, n: o.n });
      return;
    }
    const row = e.target.closest('[data-take]');
    const found = row && this.aroundItem(row);
    if (!found) return;
    if (this.selecting) { this.togglePick(found.it.id); return; }
    // one tap takes one; a stack asks how many
    if (!(found.it.cnt > 1)) command('take', { id: found.it.id }, 'Taking');
    else this.aroundSheet(found.it, found.cont);
  },

  holdAround(row) {
    const found = this.aroundItem(row);
    if (!found) return;
    if (this.selecting) this.togglePick(found.it.id);
    else this.aroundSheet(found.it, found.cont);
  },

  /** Everything for an item around you: take it, take the whole stack, straight into a bag, or the game's own menu. */
  aroundSheet(it, cont) {
    const cnt = it.cnt || 1;
    const ids = idsOf(it);
    // straight into a bag instead of your pockets
    const targets = this.moveTargets(it.id, null, cont.k);
    targets.delete('inv');
    openSheet(`<div class="sh-item">${itemIcon(it.ic, fallbackIcon(it), 'huge')}<div><h3>${esc(it.n)}${cnt > 1 ? ` <span class="muted">×${cnt}</span>` : ''}</h3>
        <p class="sh-sub">In ${esc(cont.n)}</p></div></div>`
      // a diary someone left behind: it can be read where it lies
      + `<div class="acts">${it.lg ? act('book', 'Read the diary', 'diary') : ''}${act('hand', cnt > 1 ? 'Take one' : 'Take', 'one')}${cnt > 1 ? act('bag', 'Take all ' + cnt, 'all') : ''}</div>`
      + moveToHtml(targets)
      + `<div class="acts after">${act('more', 'All actions…', 'menu')}</div>`, {
      diary: () => { closeSheet(); Diaries.read(String(it.lg)); },
      one: () => { command('take', { id: it.id }, 'Taking'); closeSheet(); },
      all: () => { command('take', { ids }, 'Taking'); closeSheet(); },
      move: (b) => { command('move', { ids, to: b.dataset.to }, 'Moving'); closeSheet(); },
      menu: () => GameMenu.open(it.n, { kind: 'item', ids }),
    });
  },

  // ------------------------------------------------------------ craft

  craft(d) {
    const sig = JSON.stringify(d);
    if (sig === this.craftSig) return;
    this.craftSig = sig;
    this.renderCraft();
  },

  /** "CookingPrep" -> "Cooking prep" */
  prettify(s) {
    const out = String(s).replace(/([a-z])([A-Z])/g, '$1 $2').replace(/_/g, ' ');
    return out.charAt(0).toUpperCase() + out.slice(1).toLowerCase();
  },

  renderCraft() {
    const d = S.craft;
    const el = $('#craftList');
    if (!d) return;
    if (d.off) { el.innerHTML = "<p class=\"none\">This version of the game doesn't have the new crafting.</p>"; return; }
    const q = $('#craftSearch').value.trim().toLowerCase();
    const groups = [];
    for (const r of arr(d.list)) {
      if (q && !String(r.n || '').toLowerCase().includes(q)) continue;
      const cat = r.cat || '';
      if (!groups.length || groups[groups.length - 1].cat !== cat) groups.push({ cat, list: [] });
      groups[groups.length - 1].list.push(r);
    }
    el.innerHTML = groups.length ? groups.map((g) => `<div class="card craft-card"><h2>${esc(g.cat ? this.prettify(g.cat) : 'Other')}</h2>
      ${g.list.map((r) => {
        // recipe icons are named without the "Item_" the packs use: try that first, then the name itself
        let ic = String(r.ic || '');
        if (ic && !ic.startsWith('Item_')) ic = 'Item_' + ic + '|' + ic;
        return `<button class="item" data-recipe="${esc(r.id)}">${itemIcon(ic, 'tool')}<span class="it-main"><span class="it-name">${esc(r.n)}</span></span><span class="mini-btn">Make</span></button>`;
      }).join('')}</div>`).join('')
      : emptyState('tool', q ? 'No match' : 'Nothing to make right now', 'Carry the tools and materials a recipe needs, or stand next to a table for the ones made on a surface.');
  },
};

// =================================================================== Skills

const Skills = {
  skills(d) {
    const sig = JSON.stringify(d);
    if (sig === this.sig) return;   // skills change slowly: skip identical repaints
    this.sig = sig;
    $('#skills').innerHTML = arr(d.groups).map((g) => `<div class="card">${cardHead(esc(g.g))}${arr(g.s).map((s) => {
      const lvl = s.lvl || 0, p = s.p || 0;
      const zero = lvl === 0 && !p;
      let sq = '';
      // the game's own skill squares: filled up to the level, the one being earned partly filled for progress
      for (let i = 0; i < 10; i++) {
        if (i < lvl) sq += '<i class="on"></i>';
        else if (i === lvl && lvl < 10 && p > 0) sq += `<i class="now part" style="--p:${(p * 100).toFixed(1)}%"></i>`;
        else if (i === lvl && lvl < 10) sq += '<i class="now"></i>';
        else sq += '<i></i>';
      }
      return `<div class="skill${zero ? ' zero' : ''}"><div class="s-row"><span class="s-name">${esc(s.n)}</span>
        ${s.mult ? tag('×' + num(s.mult), 'acc') : ''}${s.boost ? tag('+' + s.boost) : ''}<b class="s-lvl">${lvl}</b></div>
        <div class="squares" aria-label="Level ${lvl}">${sq}</div></div>`;
    }).join('')}</div>`).join('') || '<p class="none">No skills yet.</p>';
  },
};

// =================================================================== Car

const Car = {
  init() {
    this.dash = Dashboard($('#dashCanvas'), () => { $('#speedoCard').hidden = true; });
    this.mech = CarOverlay($('#mech'));
    $('#carControls').addEventListener('click', (e) => {
      const b = e.target.closest('[data-control]');
      if (!b || b.disabled) return;
      const c = b.dataset.control;
      command('car', { a: c }, c === 'horn' ? null : cap1(c));
    });
  },

  core(c) {
    const v = c.veh;
    if (!v) return;
    this.dash.set(v);
    $('#speedoCard').hidden = this.dash.ready();
    // plain speedometer, shown only until the dashboard artwork has arrived
    const max = Math.max(60, v.max || 160);
    const frac = Math.max(0, Math.min(1, (v.spd || 0) / max));
    const R = 120, C = 2 * Math.PI * R;
    $('#speedoArc').setAttribute('stroke-dasharray', `${C * 0.75 * frac} ${C}`);
    $('#speedoNum').textContent = Fmt.speed(v.spd || 0);
    $('#speedoUnit').textContent = Fmt.speedUnit();
    const gear = String(v.gear || '');
    $('#gearbox').innerHTML = ['R', 'N', '1', '2', '3', '4', '5'].map((g) => `<i class="${g === gear ? 'on' : ''}">${g}</i>`).join('');
    $('#vehName').textContent = String(v.name || 'Vehicle');
    $('#vehRole').textContent = v.drv ? 'Driving' : 'Passenger';
    const gauge = (id, value, text, color) => {
      $(`#${id} span`).textContent = text;
      const i = $(`#${id} .bar i`);
      i.style.width = (Math.max(0, Math.min(1, value)) * 100).toFixed(1) + '%';
      i.style.background = color;
    };
    const f = v.fuel || 0;
    gauge('gFuel', f, pct(f) + '%', f < 0.15 ? 'var(--bad)' : f < 0.3 ? 'var(--warn)' : 'var(--good)');
    const e = v.eng == null ? -1 : v.eng;
    gauge('gEngine', Math.max(0, e) / 100, e >= 0 ? Math.round(e) + '%' : '—', e < 30 ? 'var(--bad)' : e < 60 ? 'var(--warn)' : 'var(--good)');
    $('#gBattery').hidden = v.batt == null;
    if (v.batt != null) gauge('gBattery', v.batt, pct(v.batt) + '%', v.batt < 0.2 ? 'var(--bad)' : 'var(--good)');
    // the controls double as the status: tap one to switch it, like the dashboard buttons
    const drv = !!v.drv, run = !!v.run;
    const chip = (control, ic, text, on, enabled, show = true) => show
      ? `<button class="pill${on ? ' on' : ''}" data-control="${control}" ${enabled ? '' : 'disabled'}>${icon(ic)}${text}</button>` : '';
    const html = chip('engine', 'engine', run ? 'Engine on' : (v.keys || v.hotwired ? 'Start engine' : 'Engine off'), run, drv)
      + chip('lights', 'light', v.lights ? 'Lights on' : 'Lights off', !!v.lights, drv, !!v.hasLights)
      + chip('heater', 'sun', v.heater ? 'Heater on' : 'Heater off', !!v.heater, true, !!v.hasHeater)
      + chip('horn', 'horn', 'Horn', false, drv);
    if (html !== this.controlsHtml) { this.controlsHtml = html; $('#carControls').innerHTML = html; }
    $('#partsCard').hidden = !v.mech;
    this.mech.set(v.mech || null);
  },
};

// =================================================================== Forecast

/**
 * The weather forecast the character last heard on the Automated Emergency Broadcast: the same
 * numbers the radio read out for today and tomorrow, nothing they didn't hear.
 */
const Forecast = {
  /** [label, day] for the forecast days that are still ahead, from core.fc and core.di. */
  days(core) {
    const fc = core && core.fc;
    if (!fc) return [];
    const age = (core.di || 0) - (fc.di || 0);
    const out = [];
    arr(fc.days).forEach((d, k) => {
      const rel = k - age;
      if (rel >= 0 && rel <= 1 && d) out.push([rel === 0 ? 'Today' : 'Tomorrow', d]);
    });
    return out;
  },
  sky(d) {
    const wx = d.wx != null;
    if (d.storm) return 'storm';
    if (d.blizzard || (wx && d.snow)) return 'snow';
    if (wx || d.heavy) return 'rain';
    if (d.fog) return 'fog';
    if ((d.cloud || 0) > 0.4) return 'cloud';
    return 'sun';
  },
  segment(h) {
    if (h <= 4 || h >= 23) return 'night';
    if (h < 8) return 'early morning';
    if (h < 12) return 'morning';
    if (h < 18) return 'afternoon';
    return 'evening';
  },
  /** "Rain from the afternoon", "Overcast", "Clear skies"… worded like the broadcast. */
  headline(d) {
    let what;
    if (d.storm) what = 'Storms';
    else if (d.blizzard) what = 'Blizzard';
    else if (d.heavy) what = 'Heavy rain';
    else if (d.wx != null) what = d.snow ? 'Snow' : 'Rain';
    else if (d.fog) return 'Fog';
    else if ((d.cloud || 0) > 0.7) return 'Overcast';
    else if ((d.cloud || 0) > 0.4) return 'Partly cloudy';
    else return 'Clear skies';
    if (d.wx === 'from') return what + ' from the ' + this.segment(d.at || 0);
    if (d.wx === 'until') return (d.at || 0) >= 22 ? what + ' all day' : what + ' until the ' + this.segment(d.at || 0);
    return what;
  },
  temps: (d) => (d.tmin == null ? '' : Fmt.temp(d.tmin) + ' to ' + Fmt.temp(d.tmax)),
  wind(d) {
    const w = d.wind || 0;
    if (w > 0.75) return 'Gale-force winds';
    if (w > 0.5) return 'Strong winds';
    if (w > 0.25) return 'Windy';
    return 'Light winds';
  },
  /** "Heard on the Emergency Broadcast today at 7:00 AM" (the time only if they could read it). */
  heard(core) {
    const fc = core.fc;
    if (!fc) return '';
    const age = (core.di || 0) - (fc.di || 0);
    let when = age <= 0 ? 'today' : age === 1 ? 'yesterday' : age + ' days ago';
    if (fc.t && fc.t.w) when += ' at ' + Fmt.clock(fc.t.h, fc.t.mi);
    return 'Heard on the Emergency Broadcast ' + when;
  },
  dayRow(label, d) {
    return `<div class="fc-day">${icon(this.sky(d))}<span class="fc-main"><span class="fc-label">${label}</span><b>${this.headline(d)}</b>
      <span class="fc-wind">${this.wind(d)}</span></span><span class="fc-temp">${this.temps(d)}</span></div>`;
  },
  /** Tapping the weather in the top bar. */
  sheet(core) {
    const days = this.days(core);
    let html;
    if (!days.length) {
      const old = core && core.fc;
      html = sheetHead('Forecast', old ? 'The last forecast you heard is out of date.' : "You haven't heard a forecast yet.")
        + `<div class="tip">${icon('radio')}<p>Tune a radio to the Automated Emergency Broadcast. It reads out the weather for today and tomorrow every hour.</p></div>`;
    } else {
      html = sheetHead('Forecast', esc(this.heard(core))) + `<div class="fc-days">${days.map(([l, d]) => this.dayRow(l, d)).join('')}</div>`;
    }
    openSheet(html);
  },
  /** A small card for the top of the radio log, when there's a forecast to show. */
  card(core) {
    const days = this.days(core);
    if (!days.length) return '';
    return `<div class="card">${cardHead('Forecast', 'Emergency Broadcast')}<div class="fc-days">${days.map(([l, d]) => this.dayRow(l, d)).join('')}</div></div>`;
  },
};

// =================================================================== Radio & chat

const Comms = {
  PHRASES: ['On my way', 'Help!', 'Zombies here!', 'Wait for me', 'Follow me', 'Yes', 'No', 'Thanks'],
  CHANNELS: [['say', 'Say'], ['shout', 'Shout'], ['general', 'General'], ['faction', 'Faction'], ['safehouse', 'Safehouse']],

  init() {
    this.mode = 'radio';
    this.channel = 'say';
    this.mp = false;
    this.radioEmpty();
    this.chatEmpty();
    $('#phrases').innerHTML = this.PHRASES.map((p) => `<button data-phrase="${esc(p)}">${esc(p)}</button>`).join('');
    $('#phrases').addEventListener('click', (e) => { const b = e.target.closest('[data-phrase]'); if (b) this.send(b.dataset.phrase); });
    $('#commsSeg').addEventListener('click', (e) => { const b = e.target.closest('[data-mode]'); if (b) this.setMode(b.dataset.mode); });
    $('#chatForm').addEventListener('submit', (e) => { e.preventDefault(); this.send($('#chatInput').value); });
    $('#channelBtn').addEventListener('click', () => this.pickChannel());
    $('#radioList').addEventListener('click', (e) => { if (e.target.closest('[data-clear]')) command('radioClear', {}, 'Log cleared'); });
  },

  radioEmpty() {
    $('#radioList').innerHTML = emptyState('radio', 'Nothing heard yet', 'Turn on a radio or a TV near your character. What they hear shows up here, and stays after a reload.');
  },
  chatEmpty() {
    $('#chatList').innerHTML = emptyState('chat', 'No messages yet', 'Chat from other players shows up here. Tap a phrase or type to answer.');
  },

  modeNow() { return this.mp ? this.mode : 'radio'; },

  setMode(m) {
    this.mode = m;
    const chat = this.mp && m === 'chat';
    $('#radioPane').hidden = chat;
    $('#chatPane').hidden = !chat;
    $('#view-comms').classList.toggle('chatting', chat);
    $$('#commsSeg button').forEach((b) => b.classList.toggle('on', (b.dataset.mode === 'chat') === chat));
    if (chat) this.scrollChat();
  },

  /** Multiplayer adds the chat. */
  setMp(on) {
    if (this.mp === on) return;
    this.mp = on;
    $('#commsSeg').hidden = !on;
    this.setMode(on ? 'chat' : 'radio');
  },

  setForecast(html) { $('#forecastHolder').innerHTML = html; },

  radio(d) {
    const sig = JSON.stringify(d.list);
    if (sig === this.radioSig) return;
    this.radioSig = sig;
    const list = arr(d.list);
    if (!list.length) { this.radioEmpty(); return; }
    $('#radioList').innerHTML = list.map((e) => {
      const kind = e.k || 'radio';
      const ic = kind === 'tv' ? 'tv' : kind === 'walkie' ? 'walkie' : 'radio';
      const lines = arr(e.l);
      return `<div class="card bc"><div class="bc-head">${icon(ic)}<b>${esc(this.station(e))}</b>${e.t && e.t.w ? `<span>${Fmt.clock(e.t.h, e.t.mi)}</span>` : ''}</div>
        ${lines.map((l, i) => `<p class="${i === lines.length - 1 ? 'last' : ''}">${esc(l)}</p>`).join('')}</div>`;
    }).join('') + '<button class="link-btn" data-clear="1">Clear the log</button>';
  },

  station(e) {
    const kind = e.k || 'radio';
    const name = e.s || '';
    const f = e.f || 0;
    const freq = f > 1000 ? (f / 1000).toFixed(1) + ' MHz' : '';
    switch (kind) {
      case 'tv': return name || 'Television';
      case 'media': return name || 'Recording';
      case 'walkie': return 'Walkie-talkie' + (freq ? ' · ' + freq : '');
      default:
        if (!name) return freq || 'Radio';
        return freq ? name + ' · ' + freq : name;
    }
  },

  channelColor(k) {
    switch (k) {
      case 'shout': return 'var(--warn)';
      case 'faction': return 'var(--good)';
      case 'safehouse': return '#2a9d8f';
      case 'whisper': return '#b083e8';
      case 'radio': return '#5aa9e6';
      case 'admin': case 'server': return 'var(--bad)';
      case 'general': return 'var(--accent)';
      default: return 'var(--text-2)';
    }
  },

  chat(d) {
    const can = d.can || {};
    this.canFaction = !!can.faction;
    this.canSafehouse = !!can.safehouse;
    const sig = JSON.stringify(d.list);
    if (sig === this.chatSig) return;
    this.chatSig = sig;
    const list = arr(d.list);
    if (!list.length) { this.chatEmpty(); return; }
    // oldest at the top, newest just above the box, like a chat
    $('#chatList').innerHTML = list.slice().reverse().map((m) => {
      const k = m.k || 'say';
      const who = m.me ? 'You' : (m.a || '');
      let label = (who ? who + ' · ' : '') + cap1(k);
      if (m.t && m.t.w) label += ' · ' + Fmt.clock(m.t.h, m.t.mi);
      return `<div class="msg${m.me ? ' me' : ''}"><span class="msg-label" style="color:${this.channelColor(k)}">${esc(label)}</span><p>${esc(m.s)}</p></div>`;
    }).join('');
    this.scrollChat();
  },

  scrollChat() {
    const l = $('#chatList');
    requestAnimationFrame(() => { l.scrollTop = l.scrollHeight; });
  },

  pickChannel() {
    const rows = this.CHANNELS.filter(([k]) => !(k === 'faction' && !this.canFaction) && !(k === 'safehouse' && !this.canSafehouse))
      .map(([k, label]) => `<button class="act" data-act="ch" data-ch="${k}" style="color:${k === 'say' ? 'var(--text)' : this.channelColor(k)}">${icon('chat')}<span>${label}${k === 'shout' ? '<span class="act-note">zombies hear it</span>' : ''}</span></button>`).join('');
    openSheet(sheetHead('Send to') + `<div class="acts">${rows}</div>`, {
      ch: (b) => {
        this.channel = b.dataset.ch;
        $('#channelBtn').textContent = this.CHANNELS.find((c) => c[0] === this.channel)[1];
        closeSheet();
      },
    });
  },

  send(text) {
    const s = String(text || '').trim();
    if (!s) return;
    command('chat', { s, ch: this.channel }, null);
    $('#chatInput').value = '';
  },
};

// =================================================================== Base

const Base = {
  init() {
    this.clock = true;
    $('#baseCol').addEventListener('click', (e) => {
      const b = e.target.closest('[data-base]');
      if (!b) return;
      const d = S.base || {};
      const a = b.dataset.base;
      if (a === 'set') command('baseSet', {}, 'Base set');
      else if (a === 'go') command('waypoint', { x: Math.floor(d.x), y: Math.floor(d.y), label: 'Base' }, 'Waypoint set');
      else if (a === 'move') this.confirmMove(d);
      else if (a === 'remove') this.confirmRemove();
    });
  },

  /** Player position and the world's rules, for the distance line and the "seen" wording. */
  core(c) {
    this.pos = c.p;
    this.clock = !c.rules || c.rules.clock !== false;
    this.updateDistance();
  },

  updateDistance() {
    const el = $('#baseDist');
    const d = S.base;
    if (!el || !d || !this.pos || !d.set) return;
    const dx = d.x - this.pos.x, dy = d.y - this.pos.y;
    const dist = Math.hypot(dx, dy);
    el.textContent = dist < 25 ? "You're here" : Fmt.dist(dist) + ' ' + Fmt.compass(dx, dy);
  },

  base(d) {
    const sig = JSON.stringify(d) + this.clock;
    if (sig === this.sig) { this.updateDistance(); return; }
    this.sig = sig;
    const col = $('#baseCol');
    if (!d.set) {
      col.innerHTML = `<div class="card base-empty">${icon('home')}<b>No base yet</b>
        <p>Mark the place you live in. Knox Link keeps an eye on it: generators, rain collectors and crops, as you saw them the last time you were there.</p>
        <button class="act" data-base="set">${icon('home')}<span>Make this spot my base</span></button>
        <p class="muted small">Or press and hold anywhere on the map.</p></div>`;
      return;
    }
    const name = d.n || (d.safehouse ? 'Safehouse' : 'Base');
    const seen = d.seen;
    let html = `<div class="card"><div class="base-head"><span class="base-ico">${icon('home')}</span><span class="bh-main"><b>${esc(name)}</b><span id="baseDist"></span></span></div>
      <div class="base-btns"><button class="act" data-base="go">${icon('pin')}<span>Go there</span></button>
      ${d.safehouse ? '' : `<button class="act" data-base="move">${icon('home')}<span>Move here</span></button><button class="act sq" data-base="remove" aria-label="Forget this base">${icon('trash')}</button>`}</div></div>
      <p class="seen${seen && seen.live ? ' live' : ''}">${esc(this.seenText(d, seen))}</p>`;
    if (seen) {
      const on = !!seen.power;
      html += `<div class="card row-card">${icon('bolt', on ? 'acc' : 'dim')}<b>Electricity</b>${tag(on ? 'On' : 'Off', on ? 'good' : '')}</div>`;
      const gens = arr(seen.gens);
      if (gens.length) {
        html += `<div class="card">${cardHead(gens.length > 1 ? 'Generators' : 'Generator')}${gens.map((g) => {
          const f = (g.fuel || 0) / 100;
          return `<div class="gen"><div class="gen-row"><span class="grow">Fuel</span>${tag(g.on ? 'Running' : 'Off', g.on ? 'good' : '')}<b>${g.fuel || 0}%</b></div>
            ${bar(f, f < 0.15 ? 'var(--bad)' : f < 0.35 ? 'var(--warn)' : 'var(--good)')}
            <p class="${(g.cond || 0) < 30 ? 'bad' : 'muted'} small">Condition ${g.cond || 0}%</p></div>`;
        }).join('')}</div>`;
      }
      const b = seen.barrels;
      if (b) {
        html += `<div class="card">${cardHead('Rain collectors', String(b.n || 0))}<div class="gen-row">${icon('water', 'water')}<span class="grow">Water</span><b>${pct(b.pct)}% full</b></div>
          ${bar(b.pct || 0, '#5aa9e6')}</div>`;
      }
      const crops = arr(seen.crops);
      if (crops.length) {
        html += `<div class="card">${cardHead('Crops')}${crops.map((g) => {
          const t = [];
          if (g.ready > 0) t.push([g.ready + ' ready to harvest', 'good']);
          if (g.dry > 0) t.push([g.dry + ' need water', 'warn']);
          if (g.sick > 0) t.push([g.sick + ' sick', 'bad']);
          if (g.dead > 0) t.push([g.dead + ' dead', 'bad']);
          const words = (g.waterWord || '') + (g.healthWord ? ' · ' + g.healthWord : '');
          if (words && !words.startsWith(' ·')) t.push([words, '']);
          return `<div class="crop"><div class="gen-row">${icon('plant', 'good')}<b>${esc(g.n)} <span class="muted">×${g.cnt}</span></b></div><div class="tagrow ind">${tags(t)}</div></div>`;
        }).join('')}</div>`;
      }
      if (!gens.length && !b && !crops.length) html += '<p class="none">No generators, rain collectors or crops around it yet.</p>';
    }
    col.innerHTML = html;
    this.updateDistance();
  },

  /** "Live", or how long ago the character last saw it (rough without a watch). */
  seenText(d, seen) {
    if (!seen) return 'Go back there once and Knox Link will remember what you see.';
    if (seen.live) return "You're here: this is live.";
    const h = d.ago || 0;
    if (!this.clock) {
      if (h < 12) return 'Seen earlier today';
      if (h < 36) return 'Seen about a day ago';
      return 'Seen ' + Math.round(h / 24) + ' days ago';
    }
    if (h < 1) return 'Seen less than an hour ago';
    if (h < 48) return 'Seen ' + Math.round(h) + ' hours ago';
    return 'Seen ' + Math.round(h / 24) + ' days ago';
  },

  confirmMove(d) {
    openSheet(sheetHead('Move your base here?', "Knox Link will look after the place you're standing in instead, and forget what it saw at the old one.")
      + `<div class="acts">${act('home', 'Move it here', 'yes')}${act('x', 'Keep it where it is', 'no')}</div>`, {
      yes: () => { command('baseSet', d.n ? { n: d.n } : {}, 'Base moved'); closeSheet(); },
      no: () => closeSheet(),
    });
  },

  confirmRemove() {
    openSheet(sheetHead('Forget this base?', 'Only Knox Link forgets it. Nothing changes in your game.')
      + `<div class="acts">${act('trash', 'Forget it', 'yes', '', 'danger')}${act('x', 'Keep it', 'no')}</div>`, {
      yes: () => { command('baseClear', {}, 'Base forgotten'); closeSheet(); },
      no: () => closeSheet(),
    });
  },
};

// =================================================================== Settings > Game

/**
 * The game's own settings that are handy from here: one-tap modes (Cool, Balanced, Smooth: the
 * frame-rate cap and how far Project Viewpoint's 3D furniture reaches), the heat control, the
 * frame rate, the survival guide; and how warm the device running the game is.
 */
const Game = {
  init() {
    this.sig = '';
    this.holdUntil = {};   // a control just tapped keeps its new look until the game has caught up
    $('#perfMode').addEventListener('click', (e) => {
      const b = e.target.closest('[data-mode]');
      if (!b) return;
      this.hold('mode');
      this.mark('#perfMode', b);
      command('perfMode', { mode: b.dataset.mode }, b.textContent);
    });
    $('#fpsSeg').addEventListener('click', (e) => {
      const b = e.target.closest('[data-cap]');
      if (!b) return;
      this.hold('cap');
      this.mark('#fpsSeg', b);
      command('fps', { cap: Number(b.dataset.cap) }, null);
    });
    $('#perfAuto').addEventListener('change', (e) => { this.hold('auto'); command('perfMode', { auto: e.target.checked }, null); });
    $('#perfRest').addEventListener('change', (e) => { this.hold('rest'); command('perfMode', { rest: e.target.checked }, null); });
    $('#perfFg').addEventListener('change', (e) => {
      this.hold('fg');
      command('perfMode', { fg: e.target.checked }, e.target.checked ? 'Frame generation on' : 'Frame generation off');
    });
    $('#perfBodies').addEventListener('change', (e) => {
      this.hold('bodies');
      command('perfMode', { bodies: e.target.checked }, e.target.checked ? 'Bodies in 3D' : 'Flat bodies');
    });
    $('#guideShow').addEventListener('change', (e) => { this.hold('guide'); command('guide', { show: e.target.checked }, null); });
  },

  hold(k) { this.holdUntil[k] = Date.now() + 1500; },
  held(k) { return (this.holdUntil[k] || 0) > Date.now(); },
  mark(seg, on) { $$(seg + ' button').forEach((x) => x.classList.toggle('on', x === on)); },

  /** The game's frame-rate cap, mode and heat control, and the device's temperatures (core.perf). */
  perf(core) {
    const p = core && core.perf;
    if (!p) return;
    // (a hold running out shows the game's own value again)
    const sig = JSON.stringify(p) + settings.units + ['mode', 'auto', 'cap', 'guide', 'bodies', 'rest', 'fg'].filter((k) => this.held(k)).join();
    if (sig === this.sig) return;
    this.sig = sig;
    $('#gameCard').hidden = false;
    const hasMode = p.mode != null;
    $('#modeRow').hidden = !hasMode;
    $('#autoRow').hidden = !hasMode;
    if (!this.held('mode')) $$('#perfMode button').forEach((b) => b.classList.toggle('on', b.dataset.mode === p.mode));
    if (!this.held('auto')) $('#perfAuto').checked = !!p.auto;
    $('#bodiesRow').hidden = p.bodies == null;   // only with Project Viewpoint
    if (p.bodies != null && !this.held('bodies')) $('#perfBodies').checked = !!p.bodies;
    $('#restRow').hidden = p.rest == null;
    if (p.rest != null && !this.held('rest')) $('#perfRest').checked = !!p.rest;
    $('#fgRow').hidden = p.fg == null;   // only where Project Viewpoint's frame generation runs
    if (p.fg != null && !this.held('fg')) $('#perfFg').checked = !!p.fg;
    const cap = p.unc ? 0 : Number(p.cap) || 0;
    if (!this.held('cap')) $$('#fpsSeg button').forEach((b) => b.classList.toggle('on', Number(b.dataset.cap) === cap));
    $('#guideRow').hidden = p.guide == null;
    if (p.guide != null && !this.held('guide')) $('#guideShow').checked = !!p.guide;
    const parts = [];
    if (p.held != null) parts.push(`Held at ${p.held} fps to cool down`);
    if (p.cpu != null) parts.push('Chip ' + Fmt.temp(p.cpu));
    if (p.gpu != null) parts.push('graphics ' + Fmt.temp(p.gpu));
    if (p.bat != null) parts.push('battery ' + Fmt.temp(p.bat));
    const note = $('#perfNote');
    note.textContent = parts.length ? parts.join(' · ') : 'Higher frame rates look smoother but make the device warmer.';
    // a phone chip's hottest core runs at 80-90 under load; it slows itself down near 100
    const cpu = Number(p.cpu) || 0;
    note.classList.toggle('bad', cpu >= 97);
    note.classList.toggle('warn', cpu >= 92 && cpu < 97);
  },
};

// =================================================================== Journal

/**
 * The survivor's journal: a page a day, written as a diary in the character's own voice (by the
 * game's offline writer, or the AI writer of the Android app when it's on), with what happened
 * folded underneath (kills, skills, wounds, places, sleep), the day's photos and your own notes,
 * on paper like the game's notebooks. Above them, the diaries of the dead the character found.
 */
const Journal = {
  init() {
    this.unfolded = new Set();   // pages whose "what happened" is open
    // the scrapbook: a photo of the game for today's page (a camera in the game, in immersion)
    $('#photoBtn').addEventListener('click', () => command('photo', {}, 'Photo taken'));
    $('#diariesFound').addEventListener('click', (e) => {
      const b = e.target.closest('[data-diary]');
      if (b) Diaries.read(b.dataset.diary);
    });
    $('#journalPages').addEventListener('click', (e) => {
      const ph = e.target.closest('[data-photo]');
      if (ph) {
        const p = this.photo(Number(ph.dataset.day), ph.dataset.photo);
        if (p) Photos.open(p, true);
        return;
      }
      const f = e.target.closest('[data-fold]');
      if (!f) return;
      const facts = f.nextElementSibling;
      if (!facts) return;
      const show = facts.hidden;
      facts.hidden = !show;
      f.classList.toggle('open', show);
      f.setAttribute('aria-expanded', String(show));
      const d = Number(f.dataset.fold);
      if (show) this.unfolded.add(d); else this.unfolded.delete(d);
    });
    $('#journalForm').addEventListener('submit', (e) => {
      e.preventDefault();
      const s = $('#journalInput').value.trim();
      if (!s) return;
      command('note', { s }, null);
      $('#journalInput').value = '';
    });
    $('#journalInput').addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); $('#journalForm').requestSubmit(); }
    });
    // press and hold (or right-click) a note to tear it out
    const pages = $('#journalPages');
    let timer = 0;
    const tear = (n) => this.confirmDelete(Number(n.dataset.day), Number(n.dataset.note));
    pages.addEventListener('pointerdown', (e) => {
      const n = e.target.closest('[data-note]');
      if (!n) return;
      clearTimeout(timer);
      timer = setTimeout(() => tear(n), 550);
    });
    for (const ev of ['pointerup', 'pointercancel', 'pointerleave', 'scroll']) pages.addEventListener(ev, () => clearTimeout(timer), true);
    pages.addEventListener('contextmenu', (e) => {
      const n = e.target.closest('[data-note]');
      if (!n) return;
      e.preventDefault();
      clearTimeout(timer);
      tear(n);
    });
  },

  journal(d) {
    const sig = JSON.stringify(d);
    if (sig === this.sig) return;
    this.sig = sig;
    const write = d.write !== false;
    $('#journalForm').hidden = !write;
    let html = write ? '' : "<p class=\"muted small pad-s\">Your character can't read or write, but the days still go by.</p>";
    html += arr(d.days).map((p) => this.page(p, d.today, write)).join('');
    $('#journalPages').innerHTML = html;
  },

  /** A photo on one of the pages, by its day and id. */
  photo(day, id) {
    const p = arr(S.journal && S.journal.days).find((x) => Number(x.d) === day);
    return p ? arr(p.ph).find((x) => String(x.id) === id) || null : null;
  },

  /** The left-behind diaries the character has with them or within reach ("diaries"). */
  diaries(d) {
    const html = Diaries.card(d);
    if (html === this.diariesSig) return;
    this.diariesSig = html;
    const box = $('#diariesFound');
    box.innerHTML = html;
    box.hidden = !html;
  },

  page(p, today, write) {
    const d = p.d;
    // the diary entry, in the character's hand: the AI writer's when one has been written, else the game's own
    const ai = write && p.ai ? String(p.ai) : '';
    const entry = ai || (write && p.entry ? String(p.entry) : '');
    let html = '';
    if (entry) html += `<p class="entry">${esc(entry)}</p>`;
    // the day's photos, like prints tucked in the page
    const photos = arr(p.ph).filter((x) => x && x.id);
    if (photos.length) html += Photos.strip(photos, `data-day="${d}"`);
    if (entry && ai) html += '<p class="entry-by">written by AI</p>';
    // what happened: folded under the entry, or the page itself when there's no entry
    let facts = '', n = 0;
    const kills = p.kills || 0;
    if (kills > 0) { facts += this.line(kills === 1 ? 'Killed a zombie.' : `Killed ${kills} zombies.`, null, 'kill'); n++; }
    for (const l of arr(p.lines)) { facts += this.line(l.s, l, l.k); n++; }
    if (n && entry) {
      const open = this.unfolded.has(d);
      html += `<button class="fold${open ? ' open' : ''}" data-fold="${d}" aria-expanded="${open}">What happened (${n})${icon('chevron')}</button>
        <div class="facts small"${open ? '' : ' hidden'}>${facts}</div>`;
    } else if (n) {
      html += `<div class="facts">${facts}</div>`;
    }
    arr(p.notes).forEach((note, i) => {
      html += `<div class="note" data-day="${d}" data-note="${i + 1}" title="Press and hold to tear it out">${esc(note.s)}${note.w ? `<span class="when">${Fmt.clock(note.h, note.mi)}</span>` : ''}</div>`;
      n++;
    });
    if (!n && !entry && !photos.length) html = `<p class="quiet">${d === today ? 'Nothing written yet today.' : 'A quiet day.'}</p>`;
    return `<article class="page"><header><h3>Day ${d}</h3>${d === today ? '<span>Today</span>' : ''}</header>${html}</article>`;
  },

  /** One thing that happened (small and soft when it sits under the diary entry). */
  line(text, l, kind) {
    const bite = kind === 'bite';
    return `<div class="jl${bite ? ' bite' : ''}"><i>•</i><span>${esc(text)}</span>${l && l.w ? `<span class="when">${Fmt.clock(l.h, l.mi)}</span>` : ''}</div>`;
  },

  confirmDelete(day, index) {
    openSheet(sheetHead('Tear out this note?') + `<div class="acts">${act('trash', 'Tear it out', 'yes', '', 'danger')}${act('x', 'Keep it', 'no')}</div>`, {
      yes: () => { command('noteDelete', { d: day, i: index }, null); closeSheet(); },
      no: () => closeSheet(),
    });
  },
};

// =================================================================== the scrapbook's photos

/**
 * The scrapbook's photos on the second screen: a strip of them on a journal page, a viewer with
 * the caption, and saving one to this device. They come from the game's server (/api/photo, with
 * the session's key); the game keeps them in the save.
 */
const Photos = {
  url: (id) => apiUrl('/api/photo?id=' + encodeURIComponent(id)),

  /** A page's photos side by side, like prints tucked in the page; a tap opens one (data-photo is its id). */
  strip(list, attrs = '') {
    return `<div class="prints">${list.map((p) => `<button class="print" data-photo="${esc(p.id)}" ${attrs}>`
      + `<img alt="" loading="lazy" src="${esc(this.url(p.id))}">${p.c ? `<span>${esc(p.c)}</span>` : ''}</button>`).join('')}</div>`;
  },

  /** The viewer: the photo, its caption, and what can be done with it. editable: the character's own (caption, throw away). */
  open(p, editable) {
    const id = String(p.id);
    let caption = String(p.c || '');
    const when = p.w ? `<p class="ph-when">${Fmt.clock(Number(p.h) || 0, Number(p.mi) || 0)}</p>` : '';
    openSheet(`<div class="ph-view"><img alt="" src="${esc(this.url(id))}"></div>${when}`
      + (editable ? `<form class="ph-cap" id="capForm"><input class="field" id="capInput" maxlength="120" value="${esc(caption)}" placeholder="Add a caption…"
          autocomplete="off" enterkeyhint="done" aria-label="Caption"></form>`
        : caption ? `<p class="ph-caption">${esc(caption)}</p>` : '')
      + `<div class="acts">${act('camera', 'Save to this device', 'save')}${editable ? act('trash', 'Throw this photo away', 'del', '', 'danger') : ''}${act('x', 'Close', 'close')}</div>`, {
      save: () => download(this.url(id), 'KnoxLink-' + id + '.jpg'),
      del: () => { command('photoDelete', { id }, 'Photo thrown away'); closeSheet(); },
      close: () => closeSheet(),
    }, 'photo', { id });
    const input = $('#capInput');
    if (!input) return;
    // saved once it's done: Enter (or Done), or leaving the field
    input.addEventListener('change', () => {
      const c = input.value.trim();
      if (c === caption) return;
      caption = c;
      command('photoCaption', { id, c }, 'Caption saved');
    });
    $('#capForm').addEventListener('submit', (e) => { e.preventDefault(); input.blur(); });
  },
};

// =================================================================== "Where did I see…?"

/**
 * "Where did I see...?": a search through everything the character has looked in (the game's
 * loot window, as they went: Memory.java keeps it in the save). By name or by kind, nearest
 * first, with the container, the room and the town; a tap shows it on the map.
 */
const Seen = {
  KINDS: [['Food', 'cat:food'], ['Water', 'cat:water'], ['Medicine', 'cat:firstaid,bandage'], ['Weapons', 'cat:weapon'],
    ['Tools', 'cat:tool'], ['Ammo', 'cat:ammo'], ['Bags', 'cat:bag'], ['Books', 'cat:skillbook,literature'],
    ['Seeds', 'cat:gardening'], ['Materials', 'cat:material']],
  req: 0,
  timer: 0,
  rows: [],

  open() {
    clearTimeout(this.timer);
    this.rows = [];
    openSheet(sheetHead('Where did I see…?', 'Everything your character has looked in, as they went.')
      + `<input class="field" id="seenQ" type="search" placeholder="A hammer, beans, a shotgun…" autocomplete="off" spellcheck="false"
          enterkeyhint="search" aria-label="What to look for">
        <div class="chips seen-kinds">${this.KINDS.map(([label, q]) => `<button data-act="kind" data-q="${q}">${label}</button>`).join('')}</div>
        <div id="seenResults"></div>
        <div class="acts after">${act('x', 'Close', 'close')}</div>`, {
      kind: (b) => {
        clearTimeout(this.timer);
        $('#seenQ').value = '';
        this.mark(b);
        this.search(b.dataset.q);
      },
      row: (b) => this.show(Number(b.dataset.i)),
      close: () => closeSheet(),
    }, 'seen');
    const q = $('#seenQ');
    q.addEventListener('input', () => {
      this.mark(null);
      clearTimeout(this.timer);
      this.timer = setTimeout(() => this.search(q.value), 350);
    });
    q.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter') return;
      e.preventDefault();
      clearTimeout(this.timer);
      this.search(q.value);
    });
  },

  /** The kind asked for last stays lit. */
  mark(b) { $$('#sheet .seen-kinds button').forEach((x) => x.classList.toggle('on', x === b)); },

  /** Asks the game; the answer comes back once on "seen" (onSeen). */
  search(q) {
    const box = $('#seenResults');
    if (!box || sheetKind !== 'seen') return;
    if (!String(q).trim()) { box.innerHTML = ''; return; }
    this.req++;
    command('seenFind', { q: String(q), req: this.req }, null);
  },

  /** The game answered a search ("seen"): to the open sheet, if it's the latest question. */
  onSeen(d) {
    const box = $('#seenResults');
    if (!box || sheetKind !== 'seen' || Number(d.req) !== this.req) return;
    const r = arr(d.r).filter((o) => o && typeof o === 'object');
    this.rows = r;
    if (!r.length) {
      box.innerHTML = "<p class=\"none\">Nothing like that in anything you've looked through.</p>";
      KnoxMap.setPins(null, -1);
      return;
    }
    const total = Number(d.total) || r.length;
    box.innerHTML = (total > r.length ? `<p class="seen-note">The nearest ${r.length} of ${total}</p>` : '')
      + `<div class="acts">${r.map((o, i) => this.row(o, i)).join('')}</div>`;
    KnoxMap.setPins(r, -1);
  },

  row(o, i) {
    const n = Number(o.c) || 1, d = Number(o.d) || 0;
    // a few steps away to the step, further in tens of metres
    const far = d < 3 ? 'Here' : (d < 30 ? Fmt.near(d) : Fmt.dist(d)) + (o.dir ? ' ' + o.dir : '');
    return `<button class="row-act seen-row" data-act="row" data-i="${i}">${itemIcon(o.ic, 'box', 'sm')}
      <span class="ra-main"><span class="ra-name">${esc(o.n)}${n > 1 ? `<span class="cnt">×${n}</span>` : ''}</span><span class="ra-sub">${esc(this.where(o, true))}</span></span>
      <span class="seen-far"><b>${esc(far)}</b><span>${esc(this.ago(o.h))}</span></span></button>`;
  },

  /** A find tapped: every find stays pinned, this one big, and the map goes there. */
  show(i) {
    const o = this.rows[i];
    if (!o) return;
    KnoxMap.setPins(this.rows, i);
    showOnMap(Number(o.x) + 0.5, Number(o.y) + 0.5, 3);
    closeSheet();
  },

  /** "Counter · Kitchen · Muldraugh" (floor: and "upstairs" when it was on another floor). */
  where(o, floor) {
    let s = String(o.b || '');
    if (o.rm) s += ' · ' + o.rm;
    if (o.town) s += ' · ' + o.town;
    const z = Number(o.z) || 0;
    if (floor && z) s += ' · ' + (z > 0 ? 'upstairs' : 'downstairs');
    return s;
  },

  ago(hours) {
    const h = Number(hours) || 0;
    if (h < 1) return 'just now';
    if (h < 24) {
      const n = Math.round(h);
      return n === 1 ? 'an hour ago' : n + ' hours ago';
    }
    const d = Math.round(h / 24);
    return d === 1 ? 'yesterday' : d + ' days ago';
  },
};

// =================================================================== diaries left behind

/**
 * Diaries left behind: when a character dies, their journal stays on their body as a diary
 * (KnoxLink_Legacy.lua). The journal lists the ones the character has with them or within reach,
 * and opens one like a book: the dead character's days in their own words, with their photos.
 */
const Diaries = {
  MONTHS: ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'],
  req: 0,
  book: null,   // the diary open in the sheet

  /** The journal's "Diaries you found", or '' when there are none here. */
  card(d) {
    const list = arr(d && d.list).filter((e) => e && e.id);
    if (!list.length) return '';
    return `<h2>${list.length === 1 ? 'A diary you found' : 'Diaries you found'}</h2>` + list.map((e) => {
      const days = Number(e.days) || 0;
      const where = e.on ? 'With you' : e.near ? 'Within reach' : 'From this world';
      return `<button class="paper diary-row" data-diary="${esc(e.id)}">${icon('book')}<span class="dr-main"><b>${esc(e.name)}'s diary</b>
        <span>${where} · ${days === 1 ? '1 day' : days + ' days'}</span></span><span class="dr-read">Read</span></button>`;
    }).join('');
  },

  /** Asks the game for a diary; it comes back on "diary" (onDiary). */
  read(id) {
    this.req++;
    command('diaryRead', { id, req: this.req }, null);
  },

  /** The game sent a diary: open it if it's the one asked for last. */
  onDiary(d) {
    if (!d || Number(d.req) !== this.req) return;
    this.req++;   // shown once
    this.show(d);
  },

  /** The calendar date of a diary's day n, from its day 1 ({y, mo, d}). */
  dateOf(start, n) {
    if (!start || start.y == null) return null;
    const t = new Date(Date.UTC(Number(start.y), (Number(start.mo) || 1) - 1, (Number(start.d) || 1) + n - 1));
    return `${this.MONTHS[t.getUTCMonth()]} ${t.getUTCDate()}, ${t.getUTCFullYear()}`;
  },

  show(d) {
    this.book = d;
    // the cover: who they were
    const who = [d.prof, ...arr(d.traits).slice(0, 4)].filter((s) => s).map(String).join(' · ');
    const days = arr(d.days).filter((p) => p && typeof p === 'object');
    const last = days.length ? Number(days[days.length - 1].d) || 1 : 1;
    let pages = '';
    for (const p of days) {
      const text = String(p.s || ''), notes = arr(p.notes), ph = arr(p.ph).filter((x) => x && x.id);
      if (!text && !notes.length && !ph.length) continue;
      const n = Number(p.d) || 1, date = this.dateOf(d.start, n);
      pages += `<section class="book-day"><header><h4>Day ${n}</h4>${date ? `<span>${date}</span>` : ''}</header>
        ${text ? `<p class="entry">${esc(text)}</p>` : ''}${notes.map((s) => `<p class="book-note">${esc(s)}</p>`).join('')}
        ${ph.length ? Photos.strip(ph, 'data-act="photo"') : ''}</section>`;
    }
    openSheet(`<div class="paper book"><h3 class="book-title">${esc(d.name)}'s diary</h3>${who ? `<p class="book-who">${esc(who)}</p>` : ''}
        <p class="book-span">${last === 1 ? 'One day.' : `Day 1 to day ${last}.`}</p>
        ${pages || '<p class="book-blank">The pages are blank.</p>'}</div>
      <div class="acts after">${act('x', 'Close the diary', 'close')}</div>`, {
      photo: (b) => { const p = this.photo(b.dataset.photo); if (p) Photos.open(p, false); },
      close: () => closeSheet(),
    }, 'diary');
  },

  photo(id) {
    for (const p of arr(this.book && this.book.days)) {
      const ph = arr(p && p.ph).find((x) => x && String(x.id) === id);
      if (ph) return ph;
    }
    return null;
  },
};

// =================================================================== a character's recap

/**
 * When a character dies, their run on one card (KnoxLink_Legacy.lua sends it on "recap"): how long
 * they lasted, how it ended, what they did, the way they walked and their last photo. It can be
 * saved as a picture, to keep or to share.
 */
const Recap = {
  PAPER: '#e9e3cd', INK: '#2b2620', INK_SOFT: '#6b6050', RED: '#a60e0e', GREEN: '#3e7c47',
  SERIF: 'Georgia, "Times New Roman", "Noto Serif", serif',
  SANS: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
  r: null,
  trail: null,   // the way walked ([x, y, ...]), or null until it's here (or when it couldn't be read)

  /** Shows a character's recap once (a screen that connects later gets the same one again: not shown twice). */
  maybeShow(r) {
    const id = String((r && r.id) || '');
    if (!id) return;
    let seen = [];
    try { seen = JSON.parse(load('kl.recapsSeen') || '[]'); } catch (e) { seen = []; }
    if (!Array.isArray(seen)) seen = [];
    if (seen.includes(id)) return;
    seen.push(id);
    store('kl.recapsSeen', JSON.stringify(seen.slice(-50)));
    this.show(r);
  },

  lasted(hours) {
    const days = Math.floor(hours / 24), h = Math.floor(hours % 24);
    const d = days === 1 ? '1 day' : days + ' days';
    if (days === 0) return h === 1 ? '1 hour' : h + ' hours';
    return h === 0 ? d : d + ', ' + (h === 1 ? '1 hour' : h + ' hours');
  },

  ending(cause) {
    if (!cause) return null;
    switch (cause) {
      case 'zombies': return 'Killed by the dead';
      case 'the infection': return 'Taken by the infection';
      case 'starvation': return 'Starved';
      case 'thirst': return 'Died of thirst';
      default: return 'Died of ' + cause;
    }
  },

  date(d) {
    if (!d || d.y == null) return null;
    return `${Diaries.MONTHS[Math.max(0, Math.min(11, (Number(d.mo) || 1) - 1))]} ${Number(d.d) || 0}, ${Number(d.y)}`;
  },

  /** The way walked, in squares (about metres). */
  walked(p) {
    let s = 0;
    for (let i = 2; p && i + 1 < p.length; i += 2) s += Math.hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]);
    return s;
  },

  /** The words and numbers of the card, for the sheet and the picture. */
  facts(r) {
    const hours = Number(r.hours) || 0, kills = Number(r.kills) || 0;
    const from = this.date(r.start), to = this.date(r.died);
    return {
      name: String(r.name || ''),
      sub: (r.prof ? r.prof + ' · ' : '') + 'survived ' + this.lasted(hours),
      dates: from && to ? from + ' – ' + to : '',
      end: this.ending(r.cause),
      nums: [[String(Math.max(1, Math.floor(hours / 24) + 1)), 'days'], [String(kills), kills === 1 ? 'kill' : 'kills'],
        [!this.trail || this.trail.length < 4 ? '–' : Fmt.dist(this.walked(this.trail)), 'walked']],
      best: arr(r.best).filter((b) => b && b.n).map((b) => `${b.n} ${Number(b.l) || 0}`).join(' · '),
      moments: arr(r.moments).filter((m) => m && m.s).map((m) => `Day ${Number(m.d) || 1}  ·  ${m.s}`),
    };
  },

  show(r) {
    this.r = r;
    this.trail = null;
    const f = this.facts(r);
    openSheet(`<div class="paper recap"><h3 class="rc-name">${esc(f.name)}</h3><p class="rc-sub">${esc(f.sub)}</p>
        ${f.dates ? `<p class="rc-sub">${esc(f.dates)}</p>` : ''}${f.end ? `<p class="rc-end">${esc(f.end)}.</p>` : ''}
        <div class="rc-stats">${f.nums.map(([v, l], i) => `<div><b${i === 2 ? ' id="rcWalked"' : ''}>${i === 2 ? '…' : esc(v)}</b><span>${l}</span></div>`).join('')}</div>
        <canvas class="rc-trail" id="rcTrail" role="img" aria-label="The way they walked"></canvas>
        ${f.best ? `<p class="rc-best">Best at: ${esc(f.best)}</p>` : ''}${f.moments.map((s) => `<p class="rc-moment">${esc(s)}</p>`).join('')}
        ${r.ph ? `<img class="rc-photo" id="rcPhoto" alt="" src="${esc(Photos.url(String(r.ph)))}">` : ''}</div>
      <div class="acts after">${act('camera', 'Save this card as a picture', 'save')}${act('x', 'Close', 'close')}</div>`, {
      save: () => this.save(),
      close: () => closeSheet(),
    }, 'recap');
    this.drawSmall();
    fetch(apiUrl('/api/trail?id=' + encodeURIComponent(String(r.id))), { cache: 'no-store' })
      .then((res) => (res.ok ? res.json() : null)).catch(() => null)
      .then((j) => {
        if (this.r !== r) return;
        const p = j && Array.isArray(j.p) ? j.p.map(Number) : null;
        this.trail = p ? p.slice(0, p.length - (p.length % 2)) : null;
        const w = $('#rcWalked');
        if (!w || sheetKind !== 'recap') return;
        w.textContent = this.facts(r).nums[2][0];
        this.drawSmall();
      });
  },

  /** The trail on the open card, sharp on any screen. */
  drawSmall() {
    const c = $('#rcTrail');
    if (!c) return;
    const box = c.getBoundingClientRect();
    if (!box.width || !box.height) return;
    const dpr = Math.min(window.devicePixelRatio || 1, 3);
    c.width = Math.round(box.width * dpr);
    c.height = Math.round(box.height * dpr);
    const g = c.getContext('2d');
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.drawTrail(g, 0, 0, box.width, box.height, this.trail, 1);
  },

  rrect(g, x, y, w, h, r) {
    g.beginPath();
    g.moveTo(x + r, y);
    g.arcTo(x + w, y, x + w, y + h, r);
    g.arcTo(x + w, y + h, x, y + h, r);
    g.arcTo(x, y + h, x, y, r);
    g.arcTo(x, y, x + w, y, r);
    g.closePath();
  },

  /** The way walked, drawn small: start in green, the end in red. dp: the size of a css pixel here. */
  drawTrail(g, x, y, w, h, pts, dp) {
    g.save();
    g.fillStyle = '#ddd5bc';
    this.rrect(g, x, y, w, h, 8 * dp);
    g.fill();
    g.clip();
    if (!pts || pts.length < 4) {
      g.fillStyle = this.INK_SOFT;
      g.font = `${13 * dp}px ${this.SANS}`;
      g.textAlign = 'center';
      g.fillText(pts ? "They didn't go far." : '…', x + w / 2, y + h / 2);
      g.restore();
      return;
    }
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (let i = 0; i + 1 < pts.length; i += 2) {
      x0 = Math.min(x0, pts[i]); x1 = Math.max(x1, pts[i]);
      y0 = Math.min(y0, pts[i + 1]); y1 = Math.max(y1, pts[i + 1]);
    }
    const pad = 14 * dp, bw = Math.max(20, x1 - x0), bh = Math.max(20, y1 - y0);
    const s = Math.min((w - 2 * pad) / bw, (h - 2 * pad) / bh);
    const ox = x + (w - bw * s) / 2 - x0 * s, oy = y + (h - bh * s) / 2 - y0 * s;
    // a faint grid, a square every 100 squares
    const step = 100 * s;
    if (step > 12 * dp) {
      g.strokeStyle = 'rgba(0,0,0,.13)';
      g.lineWidth = dp;
      g.beginPath();
      for (let gx = x + ((ox - x) % step + step) % step; gx < x + w; gx += step) { g.moveTo(gx, y); g.lineTo(gx, y + h); }
      for (let gy = y + ((oy - y) % step + step) % step; gy < y + h; gy += step) { g.moveTo(x, gy); g.lineTo(x + w, gy); }
      g.stroke();
    }
    g.beginPath();
    g.moveTo(ox + pts[0] * s, oy + pts[1] * s);
    for (let i = 2; i + 1 < pts.length; i += 2) g.lineTo(ox + pts[i] * s, oy + pts[i + 1] * s);
    g.lineJoin = 'round';
    g.lineCap = 'round';
    g.lineWidth = 2.5 * dp;
    g.strokeStyle = 'rgba(138,43,30,.8)';
    g.stroke();
    g.fillStyle = this.GREEN;
    g.beginPath();
    g.arc(ox + pts[0] * s, oy + pts[1] * s, 5 * dp, 0, Math.PI * 2);
    g.fill();
    const ex = ox + pts[pts.length - 2] * s, ey = oy + pts[pts.length - 1] * s, r = 5 * dp;
    g.strokeStyle = this.RED;
    g.lineWidth = 3 * dp;
    g.beginPath();
    g.moveTo(ex - r, ey - r); g.lineTo(ex + r, ey + r);
    g.moveTo(ex - r, ey + r); g.lineTo(ex + r, ey - r);
    g.stroke();
    g.restore();
  },

  /** A line cut to a width, with "…" at the end. */
  fit(g, s, max) {
    if (g.measureText(s).width <= max) return s;
    while (s.length > 1 && g.measureText(s + '…').width > max) s = s.slice(0, -1);
    return s + '…';
  },

  /** The card as a picture to keep: 1080 wide, on the journal's paper. */
  render(r, photo) {
    const f = this.facts(r);
    const w = 1080, u = 2.6;   // "css px" in the picture
    const photoH = photo ? Math.round(photo.naturalHeight * (w - 120) / photo.naturalWidth) : 0;
    const h = 1000 + photoH + (photo ? 40 : 0) + f.moments.length * 46 + (f.best ? 50 : 0);
    const c = document.createElement('canvas');
    c.width = w;
    c.height = h;
    const g = c.getContext('2d');
    g.fillStyle = this.PAPER;
    g.fillRect(0, 0, w, h);
    let y = 70;
    g.fillStyle = this.INK;
    g.font = `bold 64px ${this.SERIF}`;
    g.fillText(this.fit(g, f.name, w - 120), 60, y + 40);
    y += 80;
    g.font = `32px ${this.SANS}`;
    g.fillStyle = this.INK_SOFT;
    g.fillText(f.sub, 60, y + 20);
    y += 46;
    if (f.dates) {
      g.fillText(f.dates, 60, y + 20);
      y += 46;
    }
    if (f.end) {
      g.fillStyle = this.RED;
      g.font = `italic bold 38px ${this.SERIF}`;
      g.fillText(f.end + '.', 60, y + 30);
      y += 60;
    }
    // the numbers
    g.textAlign = 'center';
    f.nums.forEach(([v, l], i) => {
      const cx = 60 + (w - 120) * (i + 0.5) / 3;
      g.fillStyle = this.INK;
      g.font = `bold 58px ${this.SERIF}`;
      g.fillText(v, cx, y + 70);
      g.font = `28px ${this.SANS}`;
      g.fillStyle = this.INK_SOFT;
      g.fillText(l, cx, y + 110);
    });
    g.textAlign = 'left';
    y += 140;
    this.drawTrail(g, 60, y, w - 120, 420, this.trail, u);
    y += 450;
    g.textBaseline = 'top';
    if (f.best) {
      g.fillStyle = this.INK;
      g.font = `30px ${this.SANS}`;
      g.fillText(this.fit(g, 'Best at: ' + f.best, w - 120), 60, y);
      y += 50;
    }
    g.fillStyle = this.INK_SOFT;
    g.font = `30px ${this.SERIF}`;
    for (const s of f.moments) {
      g.fillText(this.fit(g, s, w - 120), 60, y);
      y += 46;
    }
    g.textBaseline = 'alphabetic';
    if (photo) {
      y += 20;
      g.drawImage(photo, 60, y, w - 120, photoH);
      y += photoH;
    }
    g.fillStyle = 'rgba(0,0,0,.5)';
    g.font = `24px ${this.SANS}`;
    g.textAlign = 'right';
    g.fillText('Project Zomboid · Knox Link', w - 60, h - 30);
    return c;
  },

  save() {
    const r = this.r;
    if (!r) return;
    const img = $('#rcPhoto');
    const c = this.render(r, img && img.complete && img.naturalWidth ? img : null);
    c.toBlob((b) => {
      if (!b) { toast("Couldn't save it", true); return; }
      const url = URL.createObjectURL(b);
      download(url, `KnoxLink-${String(r.name || '').replace(/[^A-Za-z0-9]+/g, '-')}-${r.id}.png`);
      setTimeout(() => URL.revokeObjectURL(url), 5000);
    }, 'image/png');
  },
};

// =================================================================== the AI service (for Ask)

/**
 * Talks to the AI service the player picked: Claude (Anthropic), OpenAI, Google Gemini, OpenRouter,
 * or any OpenAI-compatible server (a model on a PC with Ollama or LM Studio). The same services,
 * models and words as the Android app's AiClient. Only the prompt it is given goes out, with the
 * player's own key, straight from this browser to the service; the key is kept in this browser only.
 */
const Ai = {
  PROVIDERS: ['claude', 'openai', 'gemini', 'openrouter', 'custom'],

  label: (p) => ({ openai: 'OpenAI', gemini: 'Gemini', openrouter: 'OpenRouter', custom: 'Custom' }[p] || 'Claude'),

  /** A few good models for the service, the cheapest first (the first one is the default). */
  models(p) {
    switch (p) {
      case 'openai': return ['gpt-5-mini', 'gpt-5', 'gpt-4.1-mini'];
      case 'gemini': return ['gemini-2.5-flash', 'gemini-2.5-flash-lite', 'gemini-2.5-pro'];
      case 'openrouter': return ['anthropic/claude-haiku-4.5', 'openai/gpt-5-mini', 'google/gemini-2.5-flash'];
      case 'custom': return [];
      default: return ['claude-haiku-5-5', 'claude-sonnet-5-5', 'claude-opus-5-5'];
    }
  },

  modelHint(p) {
    switch (p) {
      case 'openai': return 'The mini models cost the least and answer well enough.';
      case 'gemini': return 'Flash is cheap and quick.';
      case 'openrouter': return 'Any model on OpenRouter, written as maker/model.';
      case 'custom': return 'The model\'s name on your server, as it lists it.';
      default: return 'Haiku costs the least. Sonnet and Opus write better and cost more.';
    }
  },

  /** The service, model, key (one per service, so trying another doesn't lose the first) and a custom server's address. */
  config() {
    let p = load('kl.aiProvider') || 'claude';
    if (!this.PROVIDERS.includes(p)) p = 'claude';
    const model = load('kl.aiModel_' + p);
    return { provider: p, model: (model == null ? this.models(p)[0] || '' : model).trim(), key: (load('kl.aiKey_' + p) || '').trim(),
      base: (load('kl.aiBase') || '').trim() };
  },

  /** What's still missing before it can be used, or null. */
  missing(c) {
    if (c.provider === 'custom' && !c.base) return "Type your server's address";
    if (!c.model) return "Type the model's name";
    if (c.provider !== 'custom' && !c.key) return 'Add your API key';
    return null;
  },

  describe(c) { return this.label(c.provider) + ' · ' + c.model; },

  shorten: (s, max) => (s.length <= max ? s : s.slice(0, max - 1) + '…'),

  /**
   * Sends one prompt and returns the model's text (or throws an Error in a few plain words for a toast).
   * maxTokens is what the answer itself needs; models that think before they answer get room for that on top.
   */
  async complete(c, system, user, maxTokens) {
    let text;
    if (c.provider === 'claude') text = await this.claude(c, system, user, maxTokens);
    else if (c.provider === 'gemini') text = await this.gemini(c, system, user, Math.max(maxTokens * 4, 1200));
    else text = await this.chat(c, system, user, Math.max(maxTokens * 4, 1200));
    text = this.clean(text);
    if (!text) throw new Error('The model sent back no text');
    return text;
  },

  async claude(c, system, user, maxTokens) {
    // the current models think before they answer, and the thinking counts toward max_tokens:
    // room for it, and the low effort level
    const body = { model: c.model, max_tokens: Math.max(maxTokens * 4, 1200), system, messages: [{ role: 'user', content: user }],
      output_config: { effort: 'low' } };
    const headers = { 'x-api-key': c.key, 'anthropic-version': '2023-06-01', 'anthropic-dangerous-direct-browser-access': 'true' };
    let r;
    try {
      r = await this.post('https://api.anthropic.com/v1/messages', body, 45000, headers);
    } catch (e) {
      // older models don't know the effort setting: once more without it
      const why = String(e.message);
      if (!why.includes('effort') && !why.includes('output_config')) throw e;
      delete body.output_config;
      r = await this.post('https://api.anthropic.com/v1/messages', body, 45000, headers);
    }
    const text = (Array.isArray(r.content) ? r.content : []).filter((b) => b && b.type === 'text').map((b) => String(b.text || '')).join('');
    if (!text && r.stop_reason === 'refusal') throw new Error('The model declined to write this page');
    if (!text && r.stop_reason === 'max_tokens') throw new Error('The model ran out of room before it wrote anything');
    return text;
  },

  async gemini(c, system, user, maxTokens) {
    const model = c.model.startsWith('models/') ? c.model.slice(7) : c.model;
    const body = { systemInstruction: { parts: [{ text: system }] }, contents: [{ role: 'user', parts: [{ text: user }] }],
      generationConfig: { maxOutputTokens: maxTokens } };
    const r = await this.post(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`,
      body, 45000, { 'x-goog-api-key': c.key });
    const first = Array.isArray(r.candidates) ? r.candidates[0] : null;
    if (!first) {
      const why = r.promptFeedback && r.promptFeedback.blockReason;
      throw new Error(why ? `The service blocked the request (${why})` : 'The model sent back no text');
    }
    const parts = first.content && Array.isArray(first.content.parts) ? first.content.parts : [];
    const text = parts.filter((p) => p && !p.thought && p.text != null).map((p) => String(p.text)).join('');
    if (!text && first.finishReason === 'MAX_TOKENS') throw new Error('The model ran out of room before it wrote anything');
    return text;
  },

  /** OpenAI's chat completions, which OpenRouter, Ollama, LM Studio and most others speak too. */
  async chat(c, system, user, maxTokens) {
    const openai = c.provider === 'openai';
    const base = openai ? 'https://api.openai.com/v1' : c.provider === 'openrouter' ? 'https://openrouter.ai/api/v1' : this.serverUrl(c.base);
    const body = { model: c.model, messages: [{ role: 'system', content: system }, { role: 'user', content: user }] };
    // OpenAI's newer models only take max_completion_tokens; the others still read max_tokens
    body[openai ? 'max_completion_tokens' : 'max_tokens'] = maxTokens;
    const r = await this.post(base + '/chat/completions', body, c.provider === 'custom' ? 120000 : 45000,
      c.key ? { Authorization: 'Bearer ' + c.key } : {});
    const first = Array.isArray(r.choices) ? r.choices[0] : null;
    const msg = first && first.message;
    if (!msg) throw new Error('The model sent back no text');
    if (msg.refusal) throw new Error('The model declined to write this page');
    const text = msg.content == null ? '' : String(msg.content);
    if (!text.trim() && first.finish_reason === 'length') throw new Error('The model ran out of room before it wrote anything');
    return text;
  },

  /** A server address as typed ("192.168.1.20:11434") -> its OpenAI-style API root ("http://192.168.1.20:11434/v1"). */
  serverUrl(typed) {
    let s = String(typed || '').trim();
    while (s.endsWith('/')) s = s.slice(0, -1);
    if (s.endsWith('/chat/completions')) s = s.slice(0, -'/chat/completions'.length);
    if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(s)) s = 'http://' + s;
    let path = '';
    try { path = new URL(s).pathname; } catch (e) { path = ''; }
    if (!path || path === '/') s += '/v1';
    return s;
  },

  async post(url, body, ms, headers) {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), ms);
    let res, text;
    try {
      // nothing about the player or the device: no cookies, no referrer
      res = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...headers },
        body: JSON.stringify(body), signal: ctl.signal, cache: 'no-store', credentials: 'omit', referrerPolicy: 'no-referrer' });
      text = await res.text();
    } catch (e) {
      if (e && e.name === 'AbortError') throw new Error('No answer in time');
      if (navigator.onLine === false) throw new Error('No internet, or the address is wrong');
      let host = url;
      try { host = new URL(url).host; } catch (x) { /* as typed */ }
      // a browser can't tell a server that's off from one that doesn't let this page in
      throw new Error(`Couldn't reach ${host}`);
    } finally {
      clearTimeout(timer);
    }
    if (res.status < 200 || res.status >= 300) throw new Error(this.httpError(res.status, text));
    try { return JSON.parse(text); } catch (e) { throw new Error("The service's answer didn't make sense"); }
  },

  /** "The key wasn't accepted: <the service's own words>". */
  httpError(code, body) {
    let msg = null;
    try {
      let o = JSON.parse(body);
      if (Array.isArray(o)) o = o[0];
      if (o && typeof o === 'object') {
        const e = o.error;
        if (e && typeof e === 'object') msg = e.message || null;
        else if (typeof e === 'string') msg = e;
        if (!msg && typeof o.message === 'string') msg = o.message;
      }
    } catch (e) { /* not JSON */ }
    let what;
    if (code === 401 || code === 403) what = "The key wasn't accepted";
    else if (code === 404) what = 'Model not found';
    else if (code === 429) what = 'Too many requests, or no credit left';
    else if (code === 400) what = 'The service refused the request';
    else what = code >= 500 ? 'The service is having trouble' : 'Error ' + code;
    return msg && String(msg).trim() ? what + ': ' + this.shorten(String(msg).trim(), 140) : what;
  },

  /** The text alone: no thinking, markdown, heading or quotes around it. */
  clean(s) {
    let t = String(s || '').replace(/<think>[\s\S]*?<\/think>/g, '').replace(/\r/g, '');
    t = t.split('**').join('').split('__').join('');
    t = t.replace(/^#+\s*/gm, '').trim();
    // a "Day 12" or "Day 12 - July 20" heading line
    t = t.replace(/^(day\s+\d+[^\n]{0,40})\n+/i, '').trim();
    if (t.length > 1 && ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('“') && t.endsWith('”')))) t = t.slice(1, -1).trim();
    t = t.replace(/\n{3,}/g, '\n\n');
    if (t.length > 1500) {
      const cut = Math.max(t.lastIndexOf('. ', 1500), t.lastIndexOf('! ', 1500), t.lastIndexOf('? ', 1500));
      t = cut > 750 ? t.slice(0, cut + 1) : t.slice(0, 1499) + '…';
    }
    return t;
  },

  /** The AI's sheet: the service, the model, the key (or the address of a server of your own) and a test. Saved as it's typed. */
  sheet() {
    const c = this.config(), p = c.provider, custom = p === 'custom', models = this.models(p);
    let html = sheetHead('AI', 'Who answers in Ask: an AI service you pick, with your own key.')
      + `<p class="ai-hint">It gets only your question and your character's situation: what they carry, their wounds, their skills, where they are.</p>
        <p class="sh-label ai-h">Service</p><div class="ai-chips">${this.PROVIDERS.map((x) =>
          `<button class="ai-chip${x === p ? ' on' : ''}" data-act="prov" data-p="${x}">${this.label(x)}</button>`).join('')}</div>`;
    if (custom) {
      html += `<p class="sh-label ai-h">Server address</p><input class="field" id="aiBase" value="${esc(c.base)}" placeholder="http://192.168.1.20:11434/v1"
          autocomplete="off" spellcheck="false" autocapitalize="off" inputmode="url">
        <p class="ai-hint">Any server that speaks OpenAI's API. For a model on your PC, Ollama is at http://PC-ADDRESS:11434/v1 and LM Studio at
          http://PC-ADDRESS:1234/v1. Let it accept connections from your network (Ollama: OLLAMA_HOST=0.0.0.0) and from this page
          (Ollama: OLLAMA_ORIGINS=*; LM Studio: Enable CORS).</p>`;
    }
    html += `<p class="sh-label ai-h">Model</p><input class="field" id="aiModel" value="${esc(c.model)}" placeholder="${custom ? 'llama3.2' : esc(models[0] || '')}"
        autocomplete="off" spellcheck="false" autocapitalize="off">`
      + (models.length ? `<div class="ai-chips sm">${models.map((m) =>
        `<button class="ai-chip sm${m === c.model ? ' on' : ''}" data-act="model" data-m="${esc(m)}">${esc(m)}</button>`).join('')}</div>` : '')
      + `<p class="ai-hint">${esc(this.modelHint(p))}</p>
        <p class="sh-label ai-h">API key</p><div class="ai-key"><input class="field" id="aiKey" type="password" value="${esc(c.key)}"
          placeholder="${custom ? 'Not needed for most servers' : 'Paste your key here'}" autocomplete="off" spellcheck="false" autocapitalize="off">
          <button class="btn" data-act="show">Show</button></div>
        <p class="ai-hint">${custom ? "Leave it empty if your server doesn't ask for one. " : ''}Kept in this browser only.</p>
        <div class="ai-test"><button class="btn primary" data-act="test">Test</button><span id="aiResult"></span></div>`;
    openSheet(html, {
      prov: (b) => {
        if (b.dataset.p === p) return;
        store('kl.aiProvider', b.dataset.p);
        this.changed();
        this.sheet();
      },
      model: (b) => {
        store('kl.aiModel_' + p, b.dataset.m);
        this.changed();
        this.sheet();
      },
      show: (b) => {
        const k = $('#aiKey');
        const shown = k.type === 'text';
        k.type = shown ? 'password' : 'text';
        b.textContent = shown ? 'Show' : 'Hide';
      },
      test: () => this.test(),
    }, 'ai');
    const watch = (sel, key) => {
      const el = $(sel);
      if (el) el.addEventListener('input', () => { store(key, el.value.trim()); this.changed(); });
    };
    watch('#aiBase', 'kl.aiBase');
    watch('#aiModel', 'kl.aiModel_' + p);
    watch('#aiKey', 'kl.aiKey_' + p);
  },

  /** The settings changed: Ask says who answers now. */
  changed() { Ask.status(); },

  async test() {
    const out = $('#aiResult');
    const say = (cls, text) => { if (out.isConnected) { out.className = cls; out.textContent = text; } };
    const cfg = this.config(), miss = this.missing(cfg);
    if (miss) { say('bad', miss); return; }
    say('', 'Testing…');
    try {
      const text = await this.complete(cfg, 'You check that a connection works.', 'Reply with just the word OK.', 20);
      say('good', `It works. The model said “${this.shorten(text.replace(/\n/g, ' '), 40)}”`);
    } catch (e) {
      say('bad', e.message);
    }
  },
};

// =================================================================== Ask

/**
 * "Ask": questions about the game, answered by the AI the player set up (any of its services), with
 * the character's situation (KnoxLink_Ask.lua, on "ask") so the answer fits what they have and
 * where they are. Nothing goes out until a question is asked; one request each.
 */
const Ask = {
  STARTERS: ['What should I do next?', 'How do I treat my wounds?', 'What can I make with what I have?',
    'Where should I look for food and water?', 'How do I get a generator running?'],

  SYSTEM: 'You help someone playing Project Zomboid, Build 42, on a second screen next to the game. '
    + 'Answer their question about the game: its mechanics, crafting, building, farming, cooking, medicine, fighting, '
    + 'vehicles and how to survive. Use the character\'s situation given below: say which of the things they have, or '
    + 'that are within reach, they can use, and what they still need. Keep it short and practical: a few sentences or a '
    + 'short list, about 150 words at most unless the question needs steps. Use the game\'s own names for items, skills '
    + 'and menus. Build 42 changed a lot from Build 41 (crafting, farming, animals, building); when you aren\'t sure '
    + 'something works the same way in Build 42, say so rather than guess, and never make up items or recipes. '
    + 'Don\'t mention the situation summary itself; just use it. Plain text, no markdown headings.',

  init() {
    this.history = [];   // {k: "q" (asked), "a" (answered) or "e" (what went wrong), s}
    this.text = '';      // the character's situation
    this.name = '';
    this.busy = false;
    $('#askForm').addEventListener('submit', (e) => { e.preventDefault(); this.send(); });
    $('#askNew').addEventListener('click', () => { this.history = []; this.render(); });
    $('#askAi').addEventListener('click', () => Ai.sheet());
    $('#askList').addEventListener('click', (e) => {
      const b = e.target.closest('[data-starter]');
      if (b) this.ask(this.STARTERS[Number(b.dataset.starter)]);
    });
    this.render();
  },

  /** The character's situation, as the game tells it while this tab is open. */
  situation(d) {
    this.text = String(d.text || '');
    const name = String(d.name || '');
    if (name !== this.name) { this.name = name; this.status(); }
  },

  /** The tab came into view: the AI's settings may have changed meanwhile. */
  shown() { this.render(); },

  send() {
    const input = $('#askInput');
    const q = input.value.trim();
    if (!q) return;
    input.value = '';
    this.ask(q);
  },

  async ask(q) {
    if (this.busy || !q) return;
    const cfg = Ai.config(), missing = Ai.missing(cfg);
    if (missing) {
      toast(missing + ' (AI settings)', true);
      Ai.sheet();
      return;
    }
    this.busy = true;
    this.history.push({ k: 'q', s: q });
    this.render();
    let user = "The character's situation:\n" + (this.text || '(not known yet)') + '\n\n';
    // the last few turns are enough to follow up on
    const earlier = this.history.slice(Math.max(0, this.history.length - 9), -1).filter((h) => h.k !== 'e');
    if (earlier.length) user += 'Earlier in this conversation:\n' + earlier.map((h) => (h.k === 'q' ? 'Player: ' : 'You: ') + h.s + '\n').join('') + '\n';
    user += 'The player asks: ' + q;
    let k = 'a', s;
    try {
      s = await Ai.complete(cfg, this.SYSTEM, user, 700);
    } catch (e) {
      k = 'e';
      s = String((e && e.message) || 'Network error');
    }
    this.busy = false;
    this.history.push({ k, s });
    this.render();
  },

  status() {
    const el = $('#askStatus');
    const cfg = Ai.config(), missing = Ai.missing(cfg);
    el.textContent = missing ? 'Set up an AI first: tap AI' : `Answered by ${Ai.describe(cfg)}${this.name ? ` · knows how ${this.name} is doing` : ''}`;
    el.classList.toggle('warn', !!missing);
  },

  render() {
    this.status();
    const list = $('#askList');
    if (!this.history.length) {
      list.innerHTML = `<p class="ask-hello">Questions about the game, answered with your character's situation in mind: what you carry, your wounds,
        your skills, where you are. It uses the AI you set up under AI, with your own key; each question is one request.</p>`
        + this.STARTERS.map((s, i) => `<button class="ask-starter" data-starter="${i}">${esc(s)}</button>`).join('');
      return;
    }
    list.innerHTML = this.history.map((h) => `<p class="${h.k === 'q' ? 'ask-q' : h.k === 'e' ? 'ask-a err' : 'ask-a'}">${esc(h.s)}</p>`).join('')
      + (this.busy ? '<p class="ask-wait">Thinking…</p>' : '');
    requestAnimationFrame(() => { list.scrollTop = list.scrollHeight; });
  },
};

// =================================================================== Weapons

const clamp01 = (v) => Math.max(0, Math.min(1, Number(v) || 0));

/**
 * The armoury (KnoxLink_Weapons.lua, on "weapons"): every weapon the character carries, like a shooter's
 * class screen, and a gunsmith for the guns: the gun big on a workbench with its places for parts around
 * it, the parts that fit (carried, seen somewhere, or not found yet) and what each one would change.
 * Melee weapons and throwables open on the same workbench; weapons seen elsewhere go to the map.
 */
const Weapons = {
  // the game's places for gun parts, in plain words, and where each sits around the gun on the workbench
  PLACES: { Scope: ['Optic', 'top'], Canon: ['Barrel', 'right'], RecoilPad: ['Recoil pad', 'left'], Sling: ['Sling', 'bottom'] },
  // the stats where less is better
  LESS: ['aim', 'recoil', 'reload', 'weight', 'noise'],

  init() {
    this.view = null;   // the panel over the list: {kind: "gun" | "melee" | "throw", id, k = the place picked, pick = the part type picked, '' = none}
    this.sig = '';
    this.listTop = 0;
    const col = $('#weaponsCol');
    col.addEventListener('click', (e) => this.tap(e));
    // on a PC, the mouse wheel moves the row of parts sideways
    col.addEventListener('wheel', (e) => {
      const row = e.target.closest('.ws-parts');
      if (!row || Math.abs(e.deltaY) <= Math.abs(e.deltaX) || row.scrollWidth <= row.clientWidth) return;
      row.scrollLeft += e.deltaY;
      e.preventDefault();
    }, { passive: false });
  },

  /** The "weapons" message. */
  weapons() { this.render(); },

  /** A weapon of the message, by kind and id, or null. */
  find(kind, id) {
    const d = S.weapons || {};
    return arr(kind === 'gun' ? d.guns : kind === 'melee' ? d.melee : d.throw).find((w) => w && String(w.id) === String(id)) || null;
  },

  slotsOf: (g) => arr(g && g.slots).filter((s) => s && s.k),
  /** The type of the part on a place, or '' when it's empty. */
  onType: (s) => (s && s.part && s.part.t) || '',
  /** The parts to pick from on a place: everything that fits, and the one on the gun even when it isn't in that list. */
  tiles(s) {
    const fits = arr(s.fits).filter((f) => f && f.t);
    return s.part && s.part.t && !fits.some((f) => f.t === s.part.t) ? [s.part, ...fits] : fits;
  },

  render() {
    const d = S.weapons;
    if (!d) return;
    // the open panel's weapon is gone (dropped, thrown, broken): back to the list
    if (this.view && !this.find(this.view.kind, this.view.id)) this.view = null;
    if (this.view && this.view.kind === 'gun') this.settle();
    const sig = JSON.stringify(d) + JSON.stringify(this.view) + settings.units;
    if (sig === this.sig) return;
    this.sig = sig;
    const v = this.view, col = $('#weaponsCol');
    const row = $('.ws-parts', col), left = row ? row.scrollLeft : 0;
    col.innerHTML = !v ? this.list(d) : v.kind === 'gun' ? this.gunsmith(d) : v.kind === 'melee' ? this.meleeView(d) : this.throwView();
    const row2 = $('.ws-parts', col);
    if (row2) row2.scrollLeft = left;   // a new message doesn't lose the place in the row of parts
  },

  /** The gunsmith's place and part still exist (a part went on or off meanwhile); else what's on the first place. */
  settle() {
    const v = this.view, slots = this.slotsOf(this.find('gun', v.id));
    let s = slots.find((x) => x.k === v.k);
    if (!s) {
      s = slots[0] || null;
      v.k = s ? s.k : null;
      v.pick = this.onType(s);
    } else if (v.pick && !this.tiles(s).some((f) => f.t === v.pick)) {
      v.pick = this.onType(s);
    }
  },

  /** How far, to the step when it's close ("26 m S"). */
  far(o) {
    const d = Number(o.d) || 0;
    return d < 3 ? 'Here' : (d < 100 ? Fmt.near(d) : Fmt.dist(d)) + (o.dir ? ' ' + o.dir : '');
  },

  /** The game's picture of a weapon or part, large, its pixels kept sharp. */
  big: (ic, fallback = 'weapon') => itemIcon(ic, fallback, 'px big'),

  condColor: (v) => (v < 0.25 ? 'var(--bad)' : v < 0.5 ? 'var(--warn)' : 'var(--good)'),

  // ------------------------------------------------------------ the list: a class screen

  list(d) {
    const guns = arr(d.guns), melee = arr(d.melee), thr = arr(d.throw).filter((t) => t && t.id != null), seen = arr(d.seen);
    const screw = !!d.screwdriver;
    let html = `<div class="wp-head"><b>Weapons</b><button class="tag chip ${screw ? 'good' : 'warn'} wp-screw" data-w="screw">${screw ? icon('check') : ''}${screw ? 'Screwdriver' : 'No screwdriver'}</button></div>`;
    html += '<h2 class="wp-sec">Guns</h2>' + (guns.length ? `<div class="wp-cards">${guns.map((g) => this.card(g, 'gun', d.max)).join('')}</div>` : '<p class="none">No guns on you.</p>');
    html += '<h2 class="wp-sec">Melee</h2>' + (melee.length ? `<div class="wp-cards">${melee.map((w) => this.card(w, 'melee', d.max)).join('')}</div>` : '<p class="none">No melee weapons on you.</p>');
    html += '<h2 class="wp-sec">Throwables</h2>';
    if (!thr.length) html += '<p class="none">Nothing to throw.</p>';
    else {
      html += `<div class="card wp-throws">${[['lethal', 'Lethal'], ['tactical', 'Tactical']].map(([k, title]) => {
        const list = thr.filter((t) => (t.kind === 'lethal') === (k === 'lethal'));
        return list.length ? `<p class="wp-group">${title}</p>${list.map((t) => this.throwRow(t)).join('')}` : '';
      }).join('')}</div>`;
    }
    if (seen.length) html += `<h2 class="wp-sec">Seen elsewhere</h2><div class="acts">${seen.map((o, i) => this.seenRow(o, i)).join('')}</div>`;
    return html;
  },

  /** One gun or melee weapon: its picture on a dark panel, where it is, its state, and three bars. */
  card(w, kind, max) {
    const s = w.stats || {}, m = (max && max[kind]) || {};
    const where = w.eq ? tag('In your hands', 'acc') : w.slot ? tag(w.slot) : '';
    let rows = this.condRow('Condition', w.broken ? 0 : w.cond, w.broken ? 'Broken' : null);
    if (w.sharp != null && w.sharpMax > 0) rows += this.condRow('Sharpness', w.sharp / w.sharpMax);
    if (w.head != null && w.headMax > 0) rows += this.condRow('Head', w.head / w.headMax, `${w.head} / ${w.headMax}`);
    let extra = '';
    if (kind === 'gun') {
      extra = `<span class="wp-ammo">${esc(this.ammoShort(w.ammo))}</span><span class="wp-slots">${this.slotsOf(w).map((sl) => {
        const p = sl.part, name = (this.PLACES[sl.k] || [sl.k])[0];
        return `<span class="wp-slot${p ? '' : ' empty'}" title="${esc(name + (p ? ': ' + p.n : ''))}">${p ? itemIcon(p.ic, 'tool', 'px') : icon('plus')}</span>`;
      }).join('')}</span>`;
    }
    const minis = kind === 'gun'
      ? [['Damage', s.dmg / (m.dmg || 1)], ['Range', s.range / (m.range || 1)], ['Accuracy', (s.hit || 0) / 100]]
      : [['Damage', (s.dmg || 0) / 3], ['Range', (s.range || 0) / (m.range || 1.5)], ['Speed', ((s.speed || 0) - 0.6) / 0.8]];
    return `<button class="card wp-card" data-w="${kind}" data-id="${esc(w.id)}"><span class="wp-pic">${this.big(w.ic)}</span>
      <span class="wp-main"><span class="wp-name"><b>${esc(w.n)}</b>${where}</span>${rows}${extra}
        <span class="wp-minis">${minis.map(([l, v]) => `<span class="wp-mini"><span>${l}</span>${bar(clamp01(v), 'var(--text-2)', 'thin')}</span>`).join('')}</span></span></button>`;
  },

  condRow(label, v, text) {
    v = clamp01(v);
    return `<span class="wp-cond"><span>${label}</span>${bar(v, this.condColor(v), 'thin')}<span>${text || pct(v) + '%'}</span></span>`;
  },

  /** "12 / 15 · 2 magazines · 48 rounds" */
  ammoShort(a) {
    if (!a) return '';
    const out = [`${a.n || 0} / ${a.max || 0}`];
    if (a.mag) out.push(a.mags === 1 ? '1 magazine' : `${a.mags || 0} magazines`);
    out.push(a.spare === 1 ? '1 round' : `${a.spare || 0} rounds`);
    return out.join(' · ');
  },

  /** "Fire · 4 m", "Blast · 7 m", ... the ones it has. */
  effects(t) {
    return [['Fire', t.fire], ['Blast', t.blast], ['Smoke', t.smoke], ['Noise', t.noise]].filter(([, v]) => Number(v) > 0).map(([n, v]) => `${n} · ${Fmt.near(Number(v))}`);
  },

  throwRow(t) {
    return `<div class="wp-throw"><button class="wp-tmain" data-w="throw" data-id="${esc(t.id)}">${itemIcon(t.ic, 'weapon', 'px')}
        <span class="ra-main"><span class="ra-name">${esc(t.n)}${t.cnt > 1 ? `<span class="cnt">×${t.cnt}</span>` : ''}</span>
        <span class="it-tags">${this.effects(t).map((e) => tag(e)).join('')}</span></span></button>
      <button class="mini-btn" data-w="equip" data-id="${esc(t.id)}">Equip</button></div>`;
  },

  /** A weapon seen elsewhere, like a "Where did I see...?" find; a tap shows it on the map. */
  seenRow(o, i) {
    const n = Number(o.c) || 1;
    return `<button class="row-act seen-row" data-w="seen" data-i="${i}">${itemIcon(o.ic, 'weapon', 'sm')}
      <span class="ra-main"><span class="ra-name">${esc(o.n)}${n > 1 ? `<span class="cnt">×${n}</span>` : ''}</span><span class="ra-sub">${esc(Seen.where(o, true))}</span></span>
      <span class="seen-far"><b>${esc(this.far(o))}</b><span>${esc(Seen.ago(o.h))}</span></span></button>`;
  },

  // ------------------------------------------------------------ the gunsmith and the other weapons' panels

  /** Back to the list, the weapon's name and condition. */
  top(w) {
    const c = clamp01(w.cond);
    const cond = w.broken ? tag('Broken', 'bad') : w.cond != null ? tag(`Condition ${pct(c)}%`, c < 0.25 ? 'bad' : c < 0.5 ? 'warn' : '') : '';
    return `<div class="ws-top"><button class="ws-back" data-w="back">${icon('chevron')}<span>Weapons</span></button>
      <div class="ws-title"><b>${esc(w.n)}</b>${cond}</div></div>`;
  },

  /** The workbench: the weapon big in the middle; for a gun, each place for a part around it with a line to the gun. */
  stage(w, slots) {
    let nodes = '';
    for (const s of slots || []) {
      const place = this.PLACES[s.k];
      if (!place) continue;
      const p = s.part, on = !!this.view && s.k === this.view.k;
      nodes += `<button class="ws-node ${place[1]}${on ? ' on' : ''}" data-w="slot" data-k="${esc(s.k)}">
        <span class="ws-k">${place[0]}</span><span class="ws-tile">${p ? itemIcon(p.ic, 'tool', 'px') : icon('plus')}</span>
        <span class="ws-pn${p ? '' : ' none'}">${p ? esc(p.n) : 'None'}</span></button>`;
    }
    return `<div class="ws-stage${slots ? '' : ' plain'}"><div class="ws-gun">${this.big(w.ic)}</div>${nodes}</div>`;
  },

  gunsmith(d) {
    const v = this.view, g = this.find('gun', v.id);
    const slots = this.slotsOf(g);
    const s = slots.find((x) => x.k === v.k) || null;
    const tiles = s ? this.tiles(s) : [];
    const fit = s && v.pick ? tiles.find((f) => f.t === v.pick) || null : null;
    // what's on the gun is picked: nothing to compare; anything else: the stats it would give
    const same = !s || v.pick === this.onType(s);
    const stats = g.stats || {};
    const next = same ? null : this.preview(stats, s.part, fit);
    return this.top(g) + `<div class="ws-grid"><div class="ws-left">${this.stage(g, slots)}
        ${s ? this.partsRow(s, tiles) + `<div class="ws-act">${this.mainButton(s, fit, same)}</div>` : '<p class="none">No parts fit this gun.</p>'}</div>
      <div class="ws-right"><div class="card ws-stats">${cardHead('Stats')}${this.gunBars(stats, next, (d.max && d.max.gun) || {})}</div>
        <div class="card ws-ammo">${cardHead('Ammo')}${this.ammoLines(g.ammo)}</div>
        ${this.buttons(g, 'All actions…')}</div></div>`;
  },

  /** "None" first, then each part that fits: picked, it's previewed. */
  partsRow(s, tiles) {
    const pick = this.view.pick, onT = this.onType(s);
    const tile = (t, pic, name, badge, dim) => `<button class="ws-part${pick === t ? ' on' : ''}${dim ? ' dim' : ''}" data-w="pick" data-t="${esc(t)}">
      <span class="ws-pic">${pic}</span><span class="ws-pname">${name}</span><span class="ws-badge">${badge}</span></button>`;
    let html = tile('', icon('x'), 'None', '', false);
    for (const f of tiles) {
      const mine = f.t === onT, have = Number(f.have) > 0;
      const badge = mine ? tag('On the gun', 'acc') : have ? tag('You have it', 'good') : f.at ? tag('Seen · ' + this.far(f.at)) : tag('Not found yet');
      html += tile(f.t, itemIcon(f.ic, 'tool', 'px'), esc(f.n), badge, !mine && !have && !f.at);
    }
    return `<div class="ws-parts">${html}</div>`;
  },

  /** What the picked part can do here: attach, swap, take off, or where it was seen. */
  mainButton(s, fit, same) {
    if (same) return '<p class="ws-hint">Pick a part to see what it changes.</p>';
    const screw = !!(S.weapons && S.weapons.screwdriver);
    const needs = '<button class="btn" disabled>Needs a screwdriver</button>';
    if (!fit) return screw ? `<button class="btn primary" data-w="off">${icon('x')}Take off</button>` : needs;
    if (Number(fit.have) > 0) return screw ? `<button class="btn primary" data-w="attach">${icon('tool')}${s.part ? 'Swap' : 'Attach'}</button>` : needs;
    if (fit.at) return `<button class="btn primary" data-w="where">${icon('pin')}Show where it is</button>`;
    return '<button class="btn" disabled>Not found yet</button>';
  },

  /** The gun's stats with the picked part (null: none) in place of the one on it, the way the game adds parts up. */
  preview(stats, on, fit) {
    const s = { ...stats };
    const off = (on && on.mods) || {}, add = (fit && fit.mods) || {};
    for (const k of ['dmg', 'aim', 'reload', 'recoil', 'hit', 'range', 'weight']) s[k] = (Number(s[k]) || 0) - (Number(off[k]) || 0) + (Number(add[k]) || 0);
    // the sight range follows the optic: the new one's, or the gun's own when the optic comes off
    if (add.optic) s.sight = Number(add.sight) || 0;
    else if (off.optic && stats.sight0 != null) s.sight = Number(stats.sight0);
    return s;
  },

  gunBars(now, next, m) {
    const n2 = (x) => num(Math.round((Number(x) || 0) * 100) / 100);
    return [
      ['dmg', 'Damage', (x) => x.dmg / (m.dmg || 1), '', n2],
      ['range', 'Range', (x) => x.range / (m.range || 1), '', n2],
      ['hit', 'Accuracy', (x) => x.hit / 100, '', (v) => `${Math.round(Number(v) || 0)}%`],
      ['aim', 'Aim speed', (x) => (90 - x.aim) / 80, 'aim time ', n2],
      ['recoil', 'Recoil control', (x) => (40 - x.recoil) / 35, 'recoil ', n2],
      ['reload', 'Reload speed', (x) => (40 - x.reload) / 25, 'reload time ', n2],
      ['sight', 'Sight range', (x) => x.sight / 25, '', n2],
      ['noise', 'Noise', (x) => x.noise / (m.noise || 1), '', n2],
      ['weight', 'Handling', (x) => 1 - x.weight / 6, 'weight ', n2],
    ].map(([k, label, barOf, prefix, fmt]) => this.statRow(k, label, now, next, barOf, prefix, fmt)).join('');
  },

  /**
   * One stat: its bar and the game's number. With a part picked that changes it: "30 → 20", the new
   * number and the change on the bar green when it's better, red when it's worse.
   */
  statRow(k, label, now, next, barOf, prefix, fmt) {
    const a = Number(now[k]) || 0, b = next ? Number(next[k]) || 0 : a;
    const b0 = clamp01(barOf(now));
    let fill = `<i class="st-now" style="width:${(b0 * 100).toFixed(1)}%"></i>`, value = prefix + fmt(a);
    if (fmt(b) !== fmt(a)) {
      const better = this.LESS.includes(k) ? b < a : b > a, b1 = clamp01(barOf(next));
      const lo = Math.min(b0, b1), hi = Math.max(b0, b1);
      fill = `<i class="st-now" style="width:${(lo * 100).toFixed(1)}%"></i>`
        + (hi > lo ? `<i class="${better ? 'st-up' : 'st-down'}" style="left:${(lo * 100).toFixed(1)}%;width:${((hi - lo) * 100).toFixed(1)}%"></i>` : '');
      value = `${prefix}${fmt(a)}<span class="st-to">→</span><b class="${better ? 'up' : 'down'}">${fmt(b)}</b>`;
    }
    return `<div class="st"><div class="st-top"><span>${label}</span><span class="st-v">${value}</span></div><div class="st-bar">${fill}</div></div>`;
  },

  /** A bar with no preview (a melee weapon's stats, its condition). */
  plainRow(label, v, text, color) {
    v = clamp01(v);
    return `<div class="st"><div class="st-top"><span>${label}</span><span class="st-v">${text}</span></div>
      <div class="st-bar"><i class="st-now" style="width:${(v * 100).toFixed(1)}%${color ? ';background:' + color : ''}"></i></div></div>`;
  },

  /** In the gun, the magazine, and what's carried for it. */
  ammoLines(a) {
    if (!a) return '<p class="none">No ammo for it.</p>';
    const lines = [`In the gun: ${a.n || 0} / ${a.max || 0}${a.chambered ? ' (one in the chamber)' : ''}`];
    if (a.mag) lines.push(`Magazine: ${a.mag} (${a.magIn ? 'in the gun' : 'not in the gun'})`);
    const carried = [];
    if (a.mag) carried.push(a.mags === 1 ? '1 spare magazine' : `${a.mags || 0} spare magazines`);
    const n = a.spare || 0;
    carried.push(`${n} ${n === 1 ? 'round' : 'rounds'}${a.round ? ' of ' + a.round : ''}`);
    lines.push('Carried: ' + carried.join(' · '));
    return lines.map((l) => `<p>${esc(l)}</p>`).join('');
  },

  buttons(w, menu) {
    return `<div class="act-pair ws-btns"><button class="act" data-w="equip" data-id="${esc(w.id)}"${w.eq ? ' disabled' : ''}>${icon('hand')}<span>${w.eq ? 'In your hands' : 'Equip'}</span></button>
      ${menu ? `<button class="act" data-w="menu">${icon('more')}<span>${menu}</span></button>` : ''}</div>`;
  },

  meleeView(d) {
    const w = this.find('melee', this.view.id), s = w.stats || {}, m = (d.max && d.max.melee) || {};
    const c = w.broken ? 0 : clamp01(w.cond);
    let state = this.plainRow('Condition', c, w.broken ? 'Broken' : `${pct(c)}%`, this.condColor(c));
    if (w.head != null && w.headMax > 0) state += this.plainRow('Head', w.head / w.headMax, `${w.head} / ${w.headMax}`, this.condColor(w.head / w.headMax));
    if (w.sharp != null && w.sharpMax > 0) state += this.plainRow('Sharpness', w.sharp / w.sharpMax, `${pct(clamp01(w.sharp / w.sharpMax))}%`, this.condColor(w.sharp / w.sharpMax));
    const n2 = (x) => num(Math.round((Number(x) || 0) * 100) / 100);
    const endure = Number(s.endure) || 0, crowd = Number(s.crowd) || 1;
    const bars = [
      ['Damage', (s.dmg || 0) / 3, n2(s.dmg)],
      ['Range', (s.range || 0) / (m.range || 1.5), n2(s.range)],
      ['Speed', ((s.speed || 0) - 0.6) / 0.8, n2(s.speed)],
      ['Critical', (s.crit || 0) / 100, `${Math.round(s.crit || 0)}%`],
      ['Knockback', (s.knock || 0) / (m.knock || 1), n2(s.knock)],
      ['Crowd', crowd / 4, `hits up to ${crowd}`],
      ['Durability', Math.log(Math.max(1, endure)) / Math.log(60), `wears 1 hit in ${endure}`],
    ].map(([l, v, t]) => this.plainRow(l, v, t)).join('');
    return this.top(w) + `<div class="ws-grid"><div class="ws-left">${this.stage(w, null)}<div class="card ws-stats">${cardHead('Condition')}${state}</div></div>
      <div class="ws-right"><div class="card ws-stats">${cardHead('Stats', s.two ? 'Two-handed' : '')}${bars}</div>
        ${this.buttons(w, 'Repair or sharpen…')}</div></div>`;
  },

  throwView() {
    const t = this.find('throw', this.view.id);
    const facts = this.effects(t);
    if (t.power > 0) facts.push(`Power · ${num(t.power)}`);
    if (t.timer > 0) facts.push(`Timer · ${num(t.timer)}`);
    const cnt = Number(t.cnt) || 1;
    return this.top(t) + `<div class="ws-grid"><div class="ws-left">${this.stage(t, null)}</div>
      <div class="ws-right"><div class="card">${cardHead(t.kind === 'lethal' ? 'Lethal' : 'Tactical', cnt === 1 ? 'You have 1' : `You have ${cnt}`)}
        <div class="tagrow">${facts.map((f) => tag(f)).join('') || '<p class="none">Nothing more to it.</p>'}</div></div>
        ${this.buttons(t)}</div></div>`;
  },

  tap(e) {
    const b = e.target.closest('[data-w]');
    if (!b || b.disabled) return;
    const d = S.weapons || {}, w = b.dataset.w, v = this.view, pane = $('#view-weapons');
    switch (w) {
      case 'screw':
        toast('You need a screwdriver to change attachments, like in the game.', !d.screwdriver);
        return;
      case 'gun': case 'melee': case 'throw': {
        this.listTop = pane.scrollTop;
        this.view = { kind: w, id: b.dataset.id };
        if (w === 'gun') {
          // the first place, with what's on it (or "None") picked
          const s = this.slotsOf(this.find('gun', b.dataset.id))[0] || null;
          this.view.k = s ? s.k : null;
          this.view.pick = this.onType(s);
        }
        this.render();
        pane.scrollTop = 0;
        return;
      }
      case 'back':
        this.view = null;
        this.render();
        pane.scrollTop = this.listTop;
        return;
      case 'slot': {
        const s = this.slotsOf(this.find('gun', v.id)).find((x) => x.k === b.dataset.k);
        if (!s) return;
        v.k = s.k;
        v.pick = this.onType(s);
        this.render();
        return;
      }
      case 'pick':
        v.pick = b.dataset.t;
        this.render();
        return;
      case 'equip':
        command('swap', { id: Number(b.dataset.id) }, 'Equipping');
        return;
      case 'menu': {
        const x = this.find(v.kind, v.id);
        if (x) GameMenu.open(x.n, { kind: 'item', ids: [x.id] });
        return;
      }
      case 'seen': {
        const list = arr(d.seen), i = Number(b.dataset.i), o = list[i];
        if (!o) return;
        KnoxMap.setPins(list, i);
        showOnMap(Number(o.x) + 0.5, Number(o.y) + 0.5, 3);
        return;
      }
      default:
        break;
    }
    // the gunsmith's button: the picked part on, the part off, or where it was seen
    const g = v && this.find('gun', v.id), s = g && this.slotsOf(g).find((x) => x.k === v.k);
    if (!s) return;
    const fit = this.tiles(s).find((f) => f.t === v.pick) || null;
    if (w === 'attach' && fit && fit.id != null) command('weaponAttach', { w: g.id, p: fit.id }, s.part ? 'Swapping' : 'Attaching');
    else if (w === 'off') command('weaponDetach', { w: g.id, k: s.k }, 'Taking off');
    else if (w === 'where' && fit && fit.at) {
      KnoxMap.setPins([fit.at], 0);
      showOnMap(Number(fit.at.x) + 0.5, Number(fit.at.y) + 0.5, 3);
    }
  },
};
