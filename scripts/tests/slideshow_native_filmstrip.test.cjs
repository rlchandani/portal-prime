/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const assets = path.resolve(__dirname, '../../app/src/main/assets/screensaver') + path.sep;
function fixture(options = {}) {
  let now = 0, nextTimer = 1, takes = 0;
  const timers = new Map(), prepares = [], commands = [], handoffs = [], publications = [], reports = [], errors = [], ordinary = [];
  class Element {
    constructor() { this.children = []; this.className = ''; this.style = { transition: '', opacity: '', setProperty(k,v) { this[k] = v; } }; }
    get classList() { return { contains: name => this.className.split(/\s+/).includes(name),
      add: (...names) => { this.className = [...new Set([...this.className.split(/\s+/), ...names])].join(' ').trim(); },
      remove: (...names) => { this.className = this.className.split(/\s+/).filter(n => !names.includes(n)).join(' '); },
      toggle: (name, on) => { if (on) this.classList.add(name); else this.classList.remove(name); } }; }
    appendChild(child) { this.children.push(child); child.parent = this; return child; }
    remove() { if (this.parent) this.parent.children = this.parent.children.filter(c => c !== this); this.parent = null; }
    get offsetWidth() { return 1200; }
    getAnimations() { return []; }
    set innerHTML(value) { assert.equal(value, ''); this.children = []; }
  }
  const stage = new Element();
  const photos = Array.from({ length: 16 }, (_, id) => ({ id, seed: String(id), url: `fixture-${id}`, w: 1200, h: 800 }));
  const schedule = (fn, ms) => { const id = nextTimer++; timers.set(id, { fn, at: now + Number(ms || 0) }); return id; };
  const choices = [...(options.choices || ['filmstrip', 'kenBurns', 'filmstrip'])];
  let context;
  const Android = {
    supportsNativeFilmstrip: () => options.supports !== false,
    prepareNativeFilmstrip(token, json) { prepares.push({ token, ids: JSON.parse(json) });
      if (options.syncPrepared) context.SS.nativeFilmstripPrepared?.(token, true);
      return options.acceptPrepare !== false; },
    nativeFilmstripCommand(token, action, requestId) { commands.push({ token, action, requestId });
      if (options.autoCommands !== false && !(action === 'restore' && options.holdRestore)) context.SS.nativeFilmstripCommandDone?.(token, requestId, true);
      return options.acceptCommand !== false; },
    promoteNativeFilmstrip(incoming, outgoing, id) {
      handoffs.push({ incoming, outgoing, id, opacity: stage.children.at(-1)?.style.opacity,
        transition: stage.children.at(-1)?.style.transition });
      if (options.syncHandoff) context.SS.nativeFilmstripHandoffDone?.(id, true);
      return options.acceptHandoff !== false;
    },
  };
  context = vm.createContext({ Android, PHOTOS: photos, AbortController, document: { createElement: () => new Element() },
    console: { warn: code => errors.push(code) }, innerWidth: 1200, innerHeight: 800, screen: { width: 1200, height: 800 },
    getComputedStyle: () => ({}), Date: class extends Date { static now() { return now; } },
    Math: Object.assign(Object.create(Math), { random: () => .5 }),
    setTimeout: schedule, clearTimeout: id => timers.delete(id),
    requestAnimationFrame: fn => schedule(fn, 16), cancelAnimationFrame: id => timers.delete(id),
    addEventListener() {}, removeEventListener() {}, shuffled: list => list.slice(), aspect: p => p.w / p.h,
    preloadDecoded: url => { publications.push(url); return options.failLegacy ? Promise.reject(Error('fixed')) : Promise.resolve(url); },
    photoUrl: p => p.url,
  });
  context.window = context;
  for (const file of ['modes.js', 'mode-impls.js', 'scheduler.js']) vm.runInContext(fs.readFileSync(assets + file, 'utf8'), context, { filename: file });
  const take = context.SS.take;
  context.SS.take = (...args) => { takes++; return take(...args); };
  context.SS.pickNextMode = () => choices.shift() || 'kenBurns';
  for (const id of context.SS.MODE_IDS) if (id !== 'filmstrip') context.SS.MODES[id] = { name: id, run(ctx, deck) {
    const selected = context.SS.take(deck, ctx.cursor, ['any']);
    const child = new Element(); ctx.root.appendChild(child);
    const entry = { id, selected: selected.map(p => p.id), pauses: 0, resumes: 0, disposed: false }; ordinary.push(entry);
    const cleanup = () => { entry.disposed = true; }; cleanup.ready = Promise.resolve(!options.failOrdinary);
    cleanup.pause = () => { entry.pauses++; }; cleanup.resume = () => { entry.resumes++; }; return cleanup;
  } };
  async function flush() { for (let i = 0; i < 60; i++) await Promise.resolve(); }
  async function tick(ms) { await flush(); const end = now + ms;
    for (;;) { const first = [...timers].filter(([, t]) => t.at <= end).sort((a,b) => a[1].at - b[1].at || a[0] - b[0])[0];
      if (!first) break; const [id,t] = first; timers.delete(id); now = t.at; t.fn(); await flush(); }
    now = end; await flush();
  }
  function run(token = 'direct-1', deck = photos) {
    const root = new Element(), cursor = { i: 0 };
    const cleanup = context.SS.MODES.filmstrip.run({ root, cursor, nativeToken: token, W:1200,H:800,
      url: p => p.url, microMs:30000, photoMs:30000 }, deck);
    return { cleanup, root, cursor };
  }
  const start = () => context.SS.startEngine({ stage, mode: 'shuffle', intervalSec: 30,
    onMode: (_, id) => reports.push(id) });
  return { context, photos, prepares, commands, handoffs, publications, reports, errors, ordinary, stage, timers,
    flush, tick, run, start, get takes() { return takes; } };
}
async function admit(f, token = f.prepares.at(-1).token) { f.context.SS.nativeFilmstripPrepared(token, true); await f.flush(); }
async function acknowledge(f, ok = true) { f.context.SS.nativeFilmstripHandoffDone(f.handoffs.at(-1).id, ok); await f.flush(); }

test('native selection consumes SS.take once and admits only after preparation acknowledgement', async () => {
  const f = fixture(), scene = f.run(); await f.flush();
  assert.equal(f.prepares.length, 1); assert.equal(f.takes, 1); assert.equal(scene.cursor.i, 12);
  assert.deepEqual(f.prepares[0].ids, Array.from({length:12},(_,i)=>i)); assert.equal(scene.cleanup.nativeToken, undefined);
  await admit(f); assert.equal(await scene.cleanup.ready, true); assert.equal(scene.cleanup.nativeToken, 'direct-1');
  assert.equal(f.publications.length, 0); scene.cleanup(); assert.equal(f.timers.size, 0);
});
for (const failure of ['immediate', 'callback']) test(`${failure} native failure falls back to identical selection without another cursor advance`, async () => {
  const f = fixture({ acceptPrepare: failure !== 'immediate' }), scene = f.run('fallback', fakesReverse());
  function fakesReverse() { return f.photos.slice().reverse(); }
  await f.flush(); if (failure === 'callback') f.context.SS.nativeFilmstripPrepared('fallback', false);
  await f.flush(); assert.equal(await scene.cleanup.ready, true); assert.equal(f.takes, 1); assert.equal(scene.cursor.i, 12);
  assert.equal(scene.cleanup.nativeToken, undefined);
  assert.ok(f.publications.includes('fixture-15')); assert.ok(!f.publications.includes('fixture-0'));
  scene.cleanup(); assert.equal(f.timers.size, 0);
});
test('preparation deadline pauses with playback and late success while paused waits for resume', async () => {
  const f = fixture(), scene = f.run('paused'); await f.flush(); await f.tick(3000); scene.cleanup.pause();
  await f.tick(20000); assert.equal(f.publications.length, 0); assert.equal(scene.cleanup.nativeToken, undefined);
  await admit(f, 'paused'); await f.flush(); assert.equal(scene.cleanup.nativeToken, undefined);
  scene.cleanup.resume(); await f.flush(); assert.equal(await scene.cleanup.ready, true); scene.cleanup();
});
test('preparation timeout uses only remaining active time and ignores stale success', async () => {
  const f = fixture(), scene = f.run('timeout'); await f.flush(); await f.tick(3000); scene.cleanup.pause();
  await f.tick(20000); scene.cleanup.resume(); await f.tick(4999); assert.equal(f.publications.length, 0);
  await f.tick(1); assert.equal(await scene.cleanup.ready, true); assert.equal(scene.cleanup.nativeToken, undefined);
  f.context.SS.nativeFilmstripPrepared('timeout', true); await f.flush(); assert.equal(scene.cleanup.nativeToken, undefined);
  scene.cleanup(); assert.equal(f.timers.size, 0);
});
test('dispose during paused preparation settles readiness and drops stale callbacks', async () => {
  const f = fixture(), scene = f.run('cancelled'); await f.flush(); scene.cleanup.pause(); scene.cleanup();
  assert.equal(await scene.cleanup.ready, false); f.context.SS.nativeFilmstripPrepared('cancelled', true); await f.flush();
  assert.equal(f.publications.length, 0); assert.equal(scene.cleanup.nativeToken, undefined); assert.equal(f.timers.size, 0);
});
test('command waiters settle on disposal and stale acknowledgements have no effect', async () => {
  const f = fixture({autoCommands:false}), scene = f.run('commands'); await f.flush(); await admit(f); await scene.cleanup.ready;
  const next = scene.cleanup.next(); await f.flush(); const command = f.commands.at(-1); scene.cleanup.pause(); scene.cleanup();
  assert.equal(await next, false); f.context.SS.nativeFilmstripCommandDone(command.token, command.requestId, true);
  assert.equal(f.timers.size, 0);
});
test('native next/previous and micro-timer share acknowledged navigation without cursor consumption', async () => {
  const f = fixture(), scene = f.run('motion'); await f.flush(); await admit(f); await scene.cleanup.ready;
  assert.equal(await scene.cleanup.next(), true); assert.equal(await scene.cleanup.previous(), true);
  await f.tick(29999); assert.equal(f.commands.filter(c=>c.action==='next').length, 1);
  await f.tick(1); assert.equal(f.commands.filter(c=>c.action==='next').length, 2);
  assert.equal(scene.cursor.i,12); scene.cleanup();
});
test('a manual direction waits behind the admitted native move instead of being dropped', async () => {
  const f = fixture({ autoCommands: false }), scene = f.run('queued-move');
  try {
    await f.flush(); await admit(f); await scene.cleanup.ready;
    const first = scene.cleanup.next(); await f.flush();
    const next = f.commands.at(-1);
    let settled = false;
    const second = scene.cleanup.previous().then(ok => { settled = true; return ok; });
    await f.flush();
    assert.equal(settled, false, 'The latest gesture must remain pending during the first acknowledgement');
    assert.equal(f.commands.filter(c => c.action === 'previous').length, 0);
    f.context.SS.nativeFilmstripCommandDone(next.token, next.requestId, true); await f.flush();
    const previous = f.commands.at(-1);
    assert.equal(previous.action, 'previous');
    f.context.SS.nativeFilmstripCommandDone(previous.token, previous.requestId, true);
    assert.equal(await first, true); assert.equal(await second, true);
    assert.equal(scene.cursor.i, 12);
  } finally { scene.cleanup(); }
});
test('rapid gestures retain only the latest pending native direction', async () => {
  const f = fixture({ autoCommands: false }), scene = f.run('latest-move');
  try {
    await f.flush(); await admit(f); await scene.cleanup.ready;
    const first = scene.cleanup.next(); await f.flush(); const next = f.commands.at(-1);
    const superseded = scene.cleanup.next();
    let latestSettled = false;
    const latest = scene.cleanup.previous().then(ok => { latestSettled = true; return ok; });
    await f.flush();
    assert.equal(await superseded, false);
    assert.equal(latestSettled, false);
    f.context.SS.nativeFilmstripCommandDone(next.token, next.requestId, true); await f.flush();
    const previous = f.commands.at(-1); assert.equal(previous.action, 'previous');
    f.context.SS.nativeFilmstripCommandDone(previous.token, previous.requestId, true);
    assert.equal(await first, true); assert.equal(await latest, true);
    assert.deepEqual(f.commands.map(c => c.action), ['next', 'previous']);
  } finally { scene.cleanup(); }
});
test('queued native gestures settle on disposal and cannot issue late commands', async () => {
  const f = fixture({ autoCommands: false }), scene = f.run('retired-move');
  await f.flush(); await admit(f); await scene.cleanup.ready;
  const first = scene.cleanup.next(); await f.flush(); const next = f.commands.at(-1);
  let queuedSettled = false;
  const queued = scene.cleanup.previous().then(ok => { queuedSettled = true; return ok; });
  await f.flush(); assert.equal(queuedSettled, false);
  scene.cleanup();
  assert.equal(await first, false); assert.equal(await queued, false);
  f.context.SS.nativeFilmstripCommandDone(next.token, next.requestId, true); await f.flush();
  assert.equal(f.commands.some(c => c.action === 'previous'), false);
  assert.equal(f.timers.size, 0);
});
test('later preparation failure cannot release admitted native scene', async () => {
  const f = fixture(), scene = f.run('resize'); await f.flush(); await admit(f); await scene.cleanup.ready;
  f.context.SS.nativeFilmstripPrepared('resize', false); await f.flush();
  assert.equal(scene.cleanup.nativeToken,'resize'); assert.equal(f.publications.length,0);
  assert.equal(f.commands.filter(c=>c.action==='release').length,0); scene.cleanup();
});
test('disabled bridge follows legacy path without native requests', async () => {
  const f = fixture({supports:false}), scene=f.run(); await f.flush(); assert.equal(await scene.cleanup.ready,true);
  assert.equal(f.prepares.length,0); assert.equal(f.takes,1); assert.equal(scene.cursor.i,12); scene.cleanup();
});
test('WV to native emits no style marker until handoff ack and keeps old layer opaque', async () => {
  const f=fixture({choices:['kenBurns','filmstrip','cinema']}), engine=f.start(); await f.tick(16);
  assert.deepEqual(f.reports,['kenBurns']); engine.next(); await f.flush(); await admit(f); await f.tick(16);
  assert.equal(f.handoffs.length,1); assert.equal(f.handoffs[0].outgoing,''); assert.match(f.handoffs[0].incoming,/^[A-Za-z0-9_-]{1,64}$/);
  assert.deepEqual(f.reports,['kenBurns']); assert.equal(f.stage.children[0].classList.contains('on'),true);
  await acknowledge(f); assert.deepEqual(f.reports,['kenBurns','filmstrip']); await f.tick(1700);
  assert.equal(f.ordinary[0].disposed,true); engine.stop(); assert.equal(f.timers.size,0);
});
test('native to WV makes successor opaque before bridge and retires native after 1700 active ms', async () => {
  const f=fixture({choices:['filmstrip','cinema']}), engine=f.start(); await f.flush(); await admit(f); await f.tick(16); await acknowledge(f);
  await f.tick(30000); await f.tick(16); const handoff=f.handoffs.at(-1);
  assert.equal(handoff.incoming,''); assert.ok(handoff.outgoing); assert.equal(handoff.opacity,'1'); assert.equal(handoff.transition,'none');
  assert.deepEqual(f.reports,['filmstrip']); await acknowledge(f); assert.deepEqual(f.reports,['filmstrip','cinema']);
  engine.pause(); await f.tick(10000); assert.equal(f.commands.filter(c=>c.action==='release').length,0);
  engine.resume(); await f.tick(1699); assert.equal(f.commands.filter(c=>c.action==='release').length,0);
  await f.tick(1); assert.equal(f.commands.filter(c=>c.action==='release').length,1); engine.stop(); assert.equal(f.timers.size,0);
});
test('handoff deadline pauses and ack while paused commits only after resume', async () => {
  const f=fixture(),engine=f.start(); await f.flush(); await admit(f); await f.tick(16); engine.pause(); await f.tick(20000);
  assert.equal(f.reports.length,0); await acknowledge(f); assert.equal(f.reports.length,0); engine.resume(); await f.tick(16);
  assert.deepEqual(f.reports,['filmstrip']); engine.stop(); assert.equal(f.timers.size,0);
});
test('failed handoff keeps current scene/history and releases only incoming native', async () => {
  const f=fixture({choices:['kenBurns','filmstrip','cinema']}),engine=f.start(); await f.tick(16);
  engine.next(); await f.flush(); await admit(f); await f.tick(16); await acknowledge(f,false);
  assert.deepEqual(f.reports,['kenBurns']); assert.equal(f.stage.children.length,1); assert.equal(f.ordinary[0].disposed,false);
  assert.ok(f.ordinary[0].resumes>0); engine.previous(); await f.tick(16);
  assert.deepEqual(f.reports,['kenBurns','kenBurns']); engine.stop(); assert.equal(f.timers.size,0);
});
test('latest navigation queues during handoff and previous replays committed history', async () => {
  const f=fixture({choices:['kenBurns','filmstrip','cinema']}),engine=f.start(); await f.tick(16); engine.next(); await f.flush();
  await admit(f); await f.tick(16); const token=f.prepares[0].token; engine.next(); engine.previous();
  assert.equal(f.prepares.length,1); assert.equal(f.handoffs.length,1); await acknowledge(f); await f.tick(1700); await f.tick(16);
  assert.equal(f.handoffs.at(-1).incoming,''); assert.equal(f.handoffs.at(-1).outgoing,token); await acknowledge(f);
  assert.deepEqual(f.reports,['kenBurns','filmstrip','kenBurns']); assert.deepEqual(Array.from(f.ordinary.at(-1).selected),[0]); engine.stop();
});
test('stop cancels handoff and ignores late acknowledgement', async () => {
  const f=fixture(),engine=f.start(); await f.flush(); await admit(f); await f.tick(16); const id=f.handoffs[0].id;
  engine.pause(); engine.stop(); f.context.SS.nativeFilmstripHandoffDone(id,true); await f.flush();
  assert.equal(f.reports.length,0); assert.equal(f.stage.children.length,0); assert.equal(f.timers.size,0);
});

test('synchronous callback cannot override immediate bridge rejection', async () => {
  const f=fixture({syncPrepared:true,acceptPrepare:false}),scene=f.run('sync'); await f.flush();
  assert.equal(await scene.cleanup.ready,true); assert.equal(scene.cleanup.nativeToken,undefined); assert.equal(f.takes,1); scene.cleanup();
  const g=fixture({syncHandoff:true,acceptHandoff:false}),engine=g.start(); await g.flush(); await admit(g); await g.tick(16);
  assert.equal(g.reports.length,0); assert.equal(g.stage.children.length,0); engine.stop(); assert.equal(g.timers.size,0);
});
test('unacknowledged handoff times out after eight active seconds and ignores a late ack', async () => {
  const f=fixture(),engine=f.start(); await f.flush(); await admit(f); await f.tick(16); const id=f.handoffs[0].id;
  await f.tick(3000); engine.pause(); await f.tick(20000); assert.equal(f.stage.children.length,1);
  engine.resume(); await f.tick(4999); assert.equal(f.stage.children.length,1); await f.tick(1);
  assert.equal(f.stage.children.length,0); f.context.SS.nativeFilmstripHandoffDone(id,true); await f.flush();
  assert.equal(f.reports.length,0); assert.deepEqual(f.errors,['native_filmstrip_handoff_failed']); engine.stop(); assert.equal(f.timers.size,0);
});
test('pending command deadline is not double-armed when readiness starts', async () => {
  const f=fixture({autoCommands:false}),scene=f.run('deadline'); await f.flush(); scene.cleanup.pause(); await admit(f);
  scene.cleanup.resume(); await f.flush(); assert.equal(await scene.cleanup.ready,true);
  for (const cmd of f.commands) if(cmd.action!=='release') f.context.SS.nativeFilmstripCommandDone(cmd.token,cmd.requestId,true);
  scene.cleanup(); assert.equal(f.timers.size,0);
});
test('initial handoff queues navigation and settles it after acknowledgement', async () => {
  const f=fixture(),engine=f.start(); await f.flush(); await admit(f); await f.tick(16); engine.previous(); await acknowledge(f);
  assert.equal(f.commands.filter(c=>c.action==='previous').length,1); engine.stop(); assert.equal(f.timers.size,0);
});
test('native to native retains both token owners until the outgoing fade completes', async () => {
  const f=fixture({choices:['filmstrip','filmstrip']}),engine=f.start(); await f.flush(); await admit(f); await f.tick(16); await acknowledge(f);
  const first=f.prepares[0].token; await f.tick(30000); await f.flush(); await admit(f); await f.tick(16);
  const second=f.prepares[1].token; assert.notEqual(first,second); assert.deepEqual(f.handoffs.at(-1).incoming,second);
  assert.equal(f.handoffs.at(-1).outgoing,first); await acknowledge(f); await f.tick(1699);
  assert.equal(f.commands.filter(c=>c.action==='release').length,0); await f.tick(1);
  assert.equal(f.commands.find(c=>c.action==='release').token,first); engine.stop(); assert.equal(f.timers.size,0);
});
test('registry stays bounded and invalid source IDs use original legacy selection', async () => {
  const f=fixture(), scenes=[]; for(let i=0;i<4;i++) { scenes.push(f.run('bound-'+i)); await f.flush(); }
  assert.equal(f.prepares.length,3); assert.equal(await scenes[3].cleanup.ready,true);
  for(const scene of scenes) scene.cleanup(); assert.equal(f.timers.size,0);
  const g=fixture();g.photos[0].id=1000;const scene=g.run('invalid');await g.flush();
  assert.equal(g.prepares.length,0);assert.equal(await scene.cleanup.ready,true);assert.equal(g.takes,1);scene.cleanup();
});
test('legacy fallback failure keeps previous scene and retries without a native style marker', async () => {
  const f=fixture({choices:['kenBurns','filmstrip','cinema'],failLegacy:true,acceptPrepare:false}),engine=f.start();
  await f.tick(16); engine.next();await f.flush();assert.deepEqual(f.reports,['kenBurns']);assert.equal(f.ordinary[0].disposed,false);
  await f.tick(5000);await f.tick(16);assert.deepEqual(f.reports,['kenBurns','cinema']);engine.stop();assert.equal(f.timers.size,0);
});
test('command deadline pauses and a late acknowledgement cannot restart a disposed scene', async () => {
  const f=fixture({autoCommands:false}),scene=f.run('command-timeout');await f.flush();await admit(f);await scene.cleanup.ready;
  const next=scene.cleanup.next();await f.flush();const request=f.commands.at(-1);await f.tick(2000);scene.cleanup.pause();
  await f.tick(20000);scene.cleanup.resume();await f.tick(5999);let settled=false;next.then(()=>settled=true);await f.flush();assert.equal(settled,false);
  await f.tick(1);assert.equal(await next,false);scene.cleanup();f.context.SS.nativeFilmstripCommandDone(request.token,request.requestId,true);
  assert.equal(f.timers.size,0);
});

async function lostStart(f, successor) {
  const engine=f.start();await f.flush();await admit(f);await f.tick(16);await acknowledge(f);
  const retained=f.prepares[0].token;await f.tick(30000);await f.flush();if(successor==='filmstrip')await admit(f);await f.tick(16);
  const attempted=f.handoffs.at(-1);await f.tick(8000);
  return { engine, retained, attempted, restore:f.commands.find(c=>c.action==='restore') };
}
for (const successor of ['cinema','filmstrip']) test(`lost native-to-${successor} ack retains successor until opaque restore ack`, async () => {
  const f=fixture({choices:['filmstrip',successor],holdRestore:true});const {engine,retained,attempted,restore}=await lostStart(f,successor);
  assert.ok(restore);assert.equal(restore.token,retained);assert.equal(f.handoffs.length,2);
  assert.deepEqual(f.reports,['filmstrip']);assert.equal(f.stage.children.length,2);
  assert.equal(f.commands.some(c=>c.action==='release'),false);engine.next();engine.previous();
  f.context.SS.nativeFilmstripHandoffDone(attempted.id,true);await f.flush();assert.equal(f.stage.children.length,2);
  f.context.SS.nativeFilmstripCommandDone(restore.token,restore.requestId,true);await f.flush();assert.equal(f.stage.children.length,1);
  assert.equal(f.commands.some(c=>c.action==='release'&&c.token===retained),false);
  if(successor==='filmstrip')assert.equal(f.commands.find(c=>c.action==='release').token,attempted.incoming);
  else assert.equal(f.ordinary[0].disposed,true);
  assert.equal(f.commands.at(-1).action==='release' ? f.commands.at(-2).action : f.commands.at(-1).action,'resume');
  engine.stop();assert.equal(f.timers.size,0);
});
test('stop before restore ack releases ownership and stale completion cannot alter replacement engine', async () => {
  const f=fixture({choices:['filmstrip','cinema','kenBurns'],holdRestore:true});const {engine,restore}=await lostStart(f,'cinema');
  assert.ok(restore);engine.pause();engine.stop();assert.equal(f.timers.size,0);const replacement=f.start();await f.tick(16);
  const children=f.stage.children.slice(),reports=f.reports.slice();f.context.SS.nativeFilmstripCommandDone(restore.token,restore.requestId,true);
  await f.flush();assert.deepEqual(f.stage.children,children);assert.deepEqual(f.reports,reports);replacement.stop();assert.equal(f.timers.size,0);
});
test('restore acknowledgement while paused defers successor disposal and old-scene resume', async () => {
  const f=fixture({choices:['filmstrip','cinema'],holdRestore:true});const {engine,restore}=await lostStart(f,'cinema');assert.ok(restore);
  engine.pause();f.context.SS.nativeFilmstripCommandDone(restore.token,restore.requestId,true);await f.flush();
  assert.equal(f.stage.children.length,2);assert.equal(f.ordinary[0].disposed,false);const resumes=f.commands.filter(c=>c.action==='resume').length;
  await f.tick(20000);assert.equal(f.stage.children.length,2);engine.resume();await f.tick(16);
  assert.equal(f.stage.children.length,1);assert.equal(f.ordinary[0].disposed,true);assert.equal(f.commands.filter(c=>c.action==='resume').length,resumes+1);
  engine.stop();assert.equal(f.timers.size,0);
});
test('restore deadline uses active playback time and failure retains both owners', async () => {
  const f=fixture({choices:['filmstrip','cinema'],holdRestore:true});const {engine,restore}=await lostStart(f,'cinema');assert.ok(restore);
  await f.tick(3000);engine.pause();await f.tick(20000);assert.equal(f.errors.length,0);engine.resume();await f.tick(4999);
  assert.equal(f.errors.length,0);await f.tick(1);assert.deepEqual(f.errors,['native_filmstrip_restore_failed']);assert.equal(f.stage.children.length,2);
  assert.equal(f.ordinary[0].disposed,false);assert.equal(f.commands.some(c=>c.action==='release'),false);
  f.context.SS.nativeFilmstripCommandDone(restore.token,restore.requestId,true);await f.flush();engine.next();engine.previous();await f.tick(60000);
  assert.equal(f.stage.children.length,2);assert.equal(f.handoffs.length,2);assert.deepEqual(f.reports,['filmstrip']);
  engine.stop();assert.equal(f.timers.size,0);
});
test('false restore ack retains successor and ignores later success', async () => {
  const f=fixture({choices:['filmstrip','filmstrip'],holdRestore:true});const {engine,restore}=await lostStart(f,'filmstrip');assert.ok(restore);
  f.context.SS.nativeFilmstripCommandDone(restore.token,restore.requestId,false);await f.flush();assert.equal(f.stage.children.length,2);
  assert.equal(f.commands.some(c=>c.action==='release'),false);assert.deepEqual(f.errors,['native_filmstrip_restore_failed']);
  f.context.SS.nativeFilmstripCommandDone(restore.token,restore.requestId,true);await f.flush();assert.equal(f.stage.children.length,2);
  engine.stop();assert.equal(f.timers.size,0);
});
