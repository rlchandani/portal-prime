/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
'use strict';

/**
 * NATIVE acceptance only. Authoring this file does not run any device commands.
 *
 * Prerequisites:
 * - Parent has installed the current debug APK on PortalMusic20261030/emulator-5556,
 *   API 34, and enabled CDP in debug startEngine. No installation/build here.
 * - Parent owns this AVD exclusively during the test; no concurrent settings writes.
 * - Parent supplies a mode-0600 connection JSON OUTSIDE the repository:
 *   {"baseUrl":"http://127.0.0.1:<parent-forward-port>","token":"<private>",
 *    "serial":"emulator-5556","avd":"PortalMusic20261030","apiLevel":34,
 *    "packageName":"com.immortal.launcher.debug","apkSha256":"<64 hex>"}
 *   Its existing ADB forward must target this serial, tcp:8723. Never remove it.
 * - Node >=22 with its built-in WebSocket, adb on PATH.
 * - --private-dir is a new absolute directory outside any checkout, for recovery.
 * - --fixtures-dir contains six public synthetic JPEGs prepared with Pillow,
 *   any simple *.jpg/*.jpeg filenames, each 6400x4800 or 4800x6400.
 *   Both orientations are required. Optional small.jpg: 720x540 JPEG, within
 *   1.25 * 600 tier and above the engine's 512px junk cutoff.
 *   High-res JPEGs should be <12 MiB each. No giant in-Node image generation.
 *   An 18-byte invalid .jpg is written only in the private recovery directory
 *   and pushed alongside the fixtures for the required native 404 check.
 *
 * Invocation (do not put tokens on the command line):
 * NODE_PATH="$NODE_PATH" node bounded-local-photo-native-checks.cjs \
 *   --connection-file /private/owned-emulator-connection.json \
 *   --expected-apk-sha256 <parent-current-debug-apk-sha256> \
 *   --fixtures-dir /private/synthetic-jpegs \
 *   --private-dir /private/native-photo-check-recovery-unique
 *
 * Hard limits: test INCLUDING preflight <=120s; finally cleanup <=40s.
 * Results contain only allowlisted scalars and dimensions, never URLs, source
 * credentials, tokens, raw errors, screenshots, logcat or connection/backup paths.
 * Exit: 0 pass, 1 failed, 2 CDP unsupported (never substitute a browser mock).
 * Runtime proof covers bridge output, actual intercepted images and first scene.
 * Thread-affinity/shared decoder internals still require parent's source review.
 *
 * Exact preference recovery is needed because /screensaver cannot clear a blank
 * folderPath. Normal changes/restoration use APIs; while the owned process is
 * stopped, its original shared_prefs archive restores absent fields and incidental
 * writes byte-for-byte. Archive stays private on cleanup failure.
 */
const fs = require('node:fs');
const fsp = fs.promises;
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const { EventEmitter } = require('node:events');

const SERIAL = 'emulator-5556';
const AVD = 'PortalMusic20261030';
const PKG = 'com.immortal.launcher.debug';
const COMPONENT = `${PKG}/com.immortal.launcher.PhotoFramePreviewActivity`;
const TEST_MS = 120_000, CLEANUP_MS = 40_000;
class CheckError extends Error {
  constructor(code) { super(code); this.code = code; }
}
const check = (condition, code) => { if (!condition) throw new CheckError(code); };
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const hash = data => crypto.createHash('sha256').update(data).digest('hex');
const same = (a, b) => canonical(a) === canonical(b);
// Tar metadata can change on extraction; exact preference-file bytes must not.
function preferencePayloads(bytes) {
  const entries = {};
  for (let offset = 0; offset + 512 <= bytes.length;) {
    const header = bytes.subarray(offset, offset + 512);
    if (header.every(byte => byte === 0)) break;
    const name = header.subarray(0, 100).toString('utf8').split('\0')[0];
    const sizeText = header.subarray(124, 136).toString('ascii').replace(/\0/g, '').trim();
    check(/^[0-7]+$/.test(sizeText), 'preference_archive_size');
    const size = parseInt(sizeText, 8), type = header[156];
    check(offset + 512 + size <= bytes.length, 'preference_archive_bounds');
    if (type === 0 || type === 48) {
      check(/^shared_prefs\/[A-Za-z0-9_.-]+\.xml$/.test(name) && !(name in entries),
        'preference_archive_path');
      entries[name] = hash(bytes.subarray(offset + 512, offset + 512 + size));
    } else check(type === 53 && /^shared_prefs\/?$/.test(name), 'preference_archive_type');
    offset += 512 + Math.ceil(size / 512) * 512;
  }
  check(Object.keys(entries).length > 0, 'preference_archive_empty');
  return entries;
}
function canonical(value) {
  if (Array.isArray(value)) return JSON.stringify(value.map(v => JSON.parse(canonical(v))));
  if (value && typeof value === 'object')
    return JSON.stringify(Object.fromEntries(Object.keys(value).sort().map(k => [k, JSON.parse(canonical(value[k]))])));
  return JSON.stringify(value);
}
function options() {
  const out = {};
  const allowed = new Set(['connection-file', 'fixtures-dir', 'private-dir', 'expected-apk-sha256']);
  for (let i = 2; i < process.argv.length; i += 2) {
    const key = process.argv[i].replace(/^--/, '');
    check(process.argv[i].startsWith('--') && allowed.has(key) && process.argv[i + 1] && !out[key], 'usage');
    out[key] = process.argv[i + 1];
  }
  check([...allowed].every(k => out[k]), 'usage');
  check(/^[a-fA-F0-9]{64}$/.test(out['expected-apk-sha256']), 'expected_hash_required');
  return out;
}
// Header validation only, no pixel decode in Node.
function jpegSize(bytes) {
  check(bytes[0] === 0xff && bytes[1] === 0xd8, 'fixture_jpeg_header');
  let offset = 2;
  while (offset < bytes.length) {
    check(bytes[offset++] === 0xff, 'fixture_jpeg_marker');
    while (bytes[offset] === 0xff) offset++;
    const marker = bytes[offset++];
    if (marker === 0xd9 || marker === 0xda) break;
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
    check(offset + 2 <= bytes.length, 'fixture_jpeg_segment');
    const length = bytes.readUInt16BE(offset);
    check(length >= 2 && offset + length <= bytes.length, 'fixture_jpeg_segment');
    if ([0xc0, 0xc1, 0xc2].includes(marker)) {
      check(length >= 8, 'fixture_jpeg_sof');
      return [bytes.readUInt16BE(offset + 5), bytes.readUInt16BE(offset + 3)];
    }
    offset += length;
  }
  throw new CheckError('fixture_jpeg_dimensions');
}

// Embedded WebViews expose a page socket, not desktop Chrome's browser endpoint.
async function connectNativePage(url, bounded) {
  const socket = new WebSocket(url), events = new EventEmitter(), pending = new Map();
  let nextId = 0;
  socket.addEventListener('message', event => {
    let message;
    try { message = JSON.parse(event.data); } catch (_) { return; }
    if (message.id) {
      const request = pending.get(message.id);
      if (request) {
        pending.delete(message.id);
        message.error ? request.reject(new CheckError('cdp_command_rejected')) : request.resolve(message.result);
      }
    } else if (message.method) events.emit(message.method, message.params);
  });
  const disconnected = () => {
    for (const request of pending.values()) request.reject(new CheckError('cdp_disconnected'));
    pending.clear();
  };
  socket.addEventListener('close', disconnected);
  socket.addEventListener('error', disconnected);
  try {
    await bounded(new Promise((resolve, reject) => {
      socket.addEventListener('open', resolve, { once: true });
      socket.addEventListener('error', () => reject(new CheckError('cdp_socket_failed')), { once: true });
    }), 5000, 'cdp_socket_timeout');
  } catch (error) { socket.close(); throw error; }
  const session = {
    send(method, params = {}) {
      return new Promise((resolve, reject) => {
        const id = ++nextId;
        pending.set(id, { resolve, reject });
        socket.send(JSON.stringify({ id, method, params }));
      });
    },
    on: (name, callback) => events.on(name, callback),
    off: (name, callback) => events.off(name, callback),
    detach: async () => { socket.close(); disconnected(); },
  };
  const page = {
    async evaluate(fn, argument) {
      const result = await session.send('Runtime.evaluate', {
        expression: `(${fn.toString()})(${JSON.stringify(argument) ?? 'undefined'})`,
        awaitPromise: true, returnByValue: true,
      });
      check(!result.exceptionDetails, 'native_evaluation_exception');
      return result.result?.value;
    },
    async waitForFunction(fn, argument, { timeout }) {
      const until = Date.now() + timeout;
      do {
        if (await this.evaluate(fn, argument)) return;
        await delay(100);
      } while (Date.now() < until);
      throw new CheckError('native_condition_timeout');
    },
  };
  return { session, page };
}

async function main() {
  const started = Date.now(), testAbort = new AbortController();
  let phase = 'test', deadline = started + TEST_MS;
  const children = new Set();
  let opts, connection, privateDir, originals, prefArchive, forward, remoteRoot;
  let mutated = false, launched = false, pushed = false, session;
  const fixtureBytes = new Map(), ownedCacheKeys = new Set();
  let fixtures = [], coldName, smallName;
  const report = { status: 'failed', native: true, serial: SERIAL, avd: AVD, apiLevel: 34,
    firstScene: false, coldGuarded: false, coldDecoded: false, smallDecoded: false,
    invalid404: false, restored: false, ownedForwardRemoved: true };

  function remaining(requestPhase = phase, cap = 8_000) {
    check(requestPhase === phase && Date.now() < deadline, `${requestPhase}_deadline`);
    return Math.max(1, Math.min(cap, deadline - Date.now()));
  }
  function stopChildren() { for (const child of children) child.kill('SIGKILL'); }
  async function command(args, { input, binary = false, cap = 8_000, requestPhase = phase } = {}) {
    const timeout = remaining(requestPhase, cap);
    return new Promise((resolve, reject) => {
      // Every command is explicitly serial-scoped. No shell on the host.
      const child = spawn('adb', ['-s', SERIAL, ...args], { stdio: ['pipe', 'pipe', 'pipe'] });
      children.add(child);
      let chunks = [], count = 0, settled = false;
      const finish = (error, value) => {
        if (settled) return; settled = true; clearTimeout(timer); children.delete(child);
        error ? reject(error) : resolve(value);
      };
      const timer = setTimeout(() => { child.kill('SIGKILL'); finish(new CheckError('adb_timeout')); }, timeout);
      child.stdout.on('data', chunk => {
        count += chunk.length;
        if (count > 32 * 1024 * 1024) { child.kill('SIGKILL'); finish(new CheckError('adb_output_limit')); }
        else chunks.push(chunk);
      });
      // Native stderr may contain device/private paths; never relay it.
      child.stderr.resume();
      child.stdin.on('error', () => {});
      child.on('error', () => finish(new CheckError('adb_unavailable')));
      child.on('close', code => {
        if (code !== 0) return finish(new CheckError('adb_failed'));
        const value = Buffer.concat(chunks);
        finish(null, binary ? value : value.toString('utf8').trim());
      });
      child.stdin.end(input);
    });
  }
  const adb = (args, settings) => command(args, { ...settings, requestPhase: 'test' });
  async function evaluate(page, expression, argument, cap = 20_000) {
    const timeout = remaining('test', cap);
    const value = await bounded(page.evaluate(expression, argument), timeout, 'evaluation_timeout');
    remaining('test');
    return value;
  }
  async function api(route, method = 'GET', body, requestPhase = phase) {
    const timeout = remaining(requestPhase, 5_000);
    let response;
    try {
      response = await fetch(new URL(route, connection.baseUrl), {
        method, redirect: 'error', signal: requestPhase === 'test'
          ? AbortSignal.any([testAbort.signal, AbortSignal.timeout(timeout)]) : AbortSignal.timeout(timeout),
        headers: { Authorization: `Bearer ${connection.token}`, 'Content-Type': 'application/json' },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      check(response.ok, 'api_not_ready');
      const text = await response.text();
      check(text.length < 2 * 1024 * 1024, 'api_response_limit');
      const value = JSON.parse(text);
      check(value.ok === true, 'api_rejected');
      return value;
    } catch (error) {
      throw error instanceof CheckError ? error : new CheckError('api_connection_failed');
    }
  }
  async function identity(requestPhase = phase) {
    const avd = await command(['emu', 'avd', 'name'], { requestPhase });
    check(avd.split(/\r?\n/)[0] === AVD, 'avd_guard');
    const sdk = await command(['shell', 'getprop', 'ro.build.version.sdk'], { requestPhase });
    check(sdk === '34', 'api_level_guard');
  }
  async function privateWrite(name, value) {
    await fsp.writeFile(path.join(privateDir, name), value, { mode: 0o600, flag: 'wx' });
  }
  async function bounded(promise, ms, code) {
    let timer;
    try { return await Promise.race([promise, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new CheckError(code)), ms);
    })]); } finally { clearTimeout(timer); }
  }

  async function test() {
    opts = options();
    const connectionPath = await fsp.realpath(opts['connection-file']);
    const stat = await fsp.stat(connectionPath);
    check(stat.isFile() && stat.size <= 8192 && (stat.mode & 0o077) === 0 &&
      (typeof process.getuid !== 'function' || stat.uid === process.getuid()), 'private_connection_permissions');
    connection = JSON.parse(await fsp.readFile(connectionPath, 'utf8'));
    const base = new URL(connection.baseUrl);
    check(base.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(base.hostname) &&
      base.port && base.pathname === '/' && !base.search && !base.hash && !base.username && !base.password,
      'loopback_api_required');
    check(connection.serial === SERIAL && connection.avd === AVD && connection.apiLevel === 34 &&
      connection.packageName === PKG && typeof connection.token === 'string' && connection.token.length > 0,
      'connection_identity_guard');
    const expectedHash = opts['expected-apk-sha256'].toLowerCase();
    check(connection.apkSha256?.toLowerCase() === expectedHash, 'connection_apk_hash_guard');

    // API post-install readiness is read BEFORE ANY ADB command or mutation.
    originals = {
      profile: await api('/remote/settings/profile'),
      screensaver: await api('/screensaver'),
      sources: await api('/remote/sources'),
      info: await api('/info'),
      diag: await api('/diag'),
    };
    check(originals.profile.profileComplete === true &&
      Array.isArray(originals.profile.settings?.domains) && originals.info.apiLevel === 34,
      'post_install_readiness');
    check(originals.sources.sources?.source === 'default' &&
      originals.screensaver.screensaver?.source === 'default', 'default_source_group_required');
    await identity('test');
    const bindings = await adb(['forward', '--list']);
    check(bindings.split(/\r?\n/).some(line =>
      line.trim() === `${SERIAL} tcp:${base.port} tcp:8723`), 'owned_api_forward_guard');
    const packagePaths = (await adb(['shell', 'pm', 'path', PKG])).split(/\r?\n/);
    check(packagePaths.length === 1 && /^package:\/data\/app\/[A-Za-z0-9_+=/~.-]+\/base\.apk$/.test(packagePaths[0]),
      'single_apk_guard');
    const installedPath = packagePaths[0].slice('package:'.length);
    check(originals.diag.hardware?.appApkPath === installedPath, 'api_owned_apk_guard');
    const installedHash = await adb(['shell', 'sha256sum', installedPath]);
    check(installedHash.split(/\s/)[0] === expectedHash, 'installed_apk_hash_guard');
    check(/\bDEBUGGABLE\b/.test(await adb(['shell', 'dumpsys', 'package', PKG])), 'debug_apk_required');
    // run-as also proves the package is debuggable before backing up any private data.
    check(/^\d+$/.test(await adb(['shell', 'run-as', PKG, 'id', '-u'])), 'run_as_required');
    report.apkSha256 = expectedHash;

    const repo = await fsp.realpath(path.resolve(__dirname, '../../..'));
    const checkout = await fsp.realpath('~/portal-prime');
    const privateParent = await fsp.realpath(path.dirname(path.resolve(opts['private-dir'])));
    privateDir = path.join(privateParent, path.basename(opts['private-dir']));
    for (const candidate of [connectionPath, privateDir])
      check(![repo, checkout].some(root => candidate === root || candidate.startsWith(`${root}${path.sep}`)),
        'private_state_outside_checkout');
    await fsp.mkdir(privateDir, { mode: 0o700 }); // Existing directory is refused.
    await privateWrite('original-api-state.json', JSON.stringify(originals));
    prefArchive = await adb(['exec-out', 'run-as', PKG, 'tar', '-cf', '-', 'shared_prefs'], { binary: true });
    check(prefArchive.length >= 1024, 'preference_backup_required');
    await privateWrite('original-shared-prefs.tar', prefArchive);

    const fixtureDir = await fsp.realpath(opts['fixtures-dir']);
    const names = (await fsp.readdir(fixtureDir)).filter(name => /\.jpe?g$/i.test(name)).sort();
    check(names.every(name => /^[A-Za-z0-9_.-]+$/.test(name) && name !== 'zz-invalid.jpg'),
      'fixture_filename_required');
    const cameraNames = names.filter(name => name !== 'small.jpg');
    check(cameraNames.length === 6, 'six_synthetic_camera_jpegs_required');
    let landscape = false, portrait = false;
    for (const name of names) {
      const file = path.join(fixtureDir, name), info = await fsp.lstat(file);
      check(info.isFile() && !info.isSymbolicLink() && info.size >= 8 && info.size <= 12 * 1024 * 1024,
        'synthetic_fixture_required');
      const bytes = await fsp.readFile(file);
      fixtureBytes.set(name, bytes.length);
      const dimensions = jpegSize(bytes);
      if (name === 'small.jpg') {
        check(same(dimensions, [720, 540]), 'small_fixture_dimensions');
        smallName = name;
      } else {
        check(same(dimensions, [6400, 4800]) || same(dimensions, [4800, 6400]), 'fixture_dimensions');
        landscape ||= dimensions[0] === 6400;
        portrait ||= dimensions[0] === 4800;
      }
      fixtures.push([name, file]);
    }
    check(landscape && portrait, 'both_camera_orientations_required');
    coldName = cameraNames[1];
    await privateWrite('zz-invalid.jpg', Buffer.from('not-a-valid-jpeg!\n'));
    fixtureBytes.set('zz-invalid.jpg', Buffer.byteLength('not-a-valid-jpeg!\n'));
    fixtures.push(['zz-invalid.jpg', path.join(privateDir, 'zz-invalid.jpg')]);
    // Unique paths make the starting identities cold; never clear any app cache.
    const nonce = crypto.randomBytes(8).toString('hex');
    remoteRoot = `/sdcard/Android/data/${PKG}/files/bounded-native-${nonce}`;
    pushed = true; // Register rollback before attempting a command.
    await adb(['shell', 'mkdir', '-p', remoteRoot]);
    function rememberCacheKeys(name, mtime) {
      for (const edge of [600, 1200, 1800, 2400]) {
        const identity = `${remoteRoot}/${name}|${fixtureBytes.get(name)}|${mtime}` +
          (edge === 2400 ? '' : `|edge=${edge}`);
        ownedCacheKeys.add(crypto.createHash('sha1').update(identity).digest('hex') + '.jpg');
      }
    }
    for (const [name, file] of fixtures) {
      await adb(['push', file, `${remoteRoot}/${name}`]);
      // Pin a second-exact identity so cleanup knows ONLY our derivative names.
      await adb(['shell', 'touch', '-t', '202901010000.00', `${remoteRoot}/${name}`]);
      const stat = await adb(['shell', 'stat', '-c', "'%s:%Y'", `${remoteRoot}/${name}`]);
      check(/^\d+:\d+$/.test(stat) && Number(stat.split(':')[0]) === fixtureBytes.get(name), 'fixture_push_verify');
      rememberCacheKeys(name, Number(stat.split(':')[1]) * 1000);
    }

    const saved = originals.screensaver.screensaver;
    const patch = { folderPath: remoteRoot, mode: 'kenBurns', shuffle: false,
      includeVideo: false, intervalSec: 600 };
    // Disable only active night policies that could prevent the preview from showing.
    for (const key of ['overnightEnabled', 'darkRoomClock']) if (saved[key] === true) patch[key] = false;
    check(same((await api('/remote/sources')).sources, originals.sources.sources) &&
      same((await api('/screensaver')).screensaver, saved), 'source_group_changed_before_mutation');
    mutated = true;
    await api('/screensaver', 'POST', patch, 'test');
    const applied = (await api('/screensaver')).screensaver;
    check(applied.source === 'folder' && applied.folderPath === remoteRoot &&
      applied.mode === 'kenBurns', 'fixture_source_apply');
    launched = true;
    // The parent supplies a freshly installed Home-only process. Do not force-stop
    // immediately after SharedPreferences.apply(), which may still be writing.
    await adb(['shell', 'am', 'start', '-W', '-n', COMPONENT], { cap: 10_000 });
    const pid = await adb(['shell', 'pidof', PKG]);
    check(/^\d+$/.test(pid), 'single_owned_pid_required');

    // Quickly fail if this exact app process exposes no WebView CDP socket.
    let supported = false;
    for (let attempt = 0; attempt < 30; attempt++) {
      const sockets = await adb(['shell', 'cat', '/proc/net/unix']);
      supported = sockets.split(/\r?\n/).some(line =>
        line.trim().endsWith(`@webview_devtools_remote_${pid}`));
      if (supported) break;
      await delay(350);
    }
    check(supported, 'cdp_unsupported');
    const destination = `localabstract:webview_devtools_remote_${pid}`;
    const port = await adb(['forward', '--no-rebind', 'tcp:0', destination]);
    check(/^\d+$/.test(port), 'cdp_forward_failed');
    forward = { local: `tcp:${port}`, destination };
    report.ownedForwardRemoved = false;
    const cdpBase = `http://127.0.0.1:${port}`;
    let targets;
    report.cdpStage = 'page-list';
    try {
      const response = await fetch(`${cdpBase}/json/list`,
        { signal: AbortSignal.timeout(3000), redirect: 'error' });
      report.cdpHttpStatus = response.status;
      check(response.ok, 'cdp_http_rejected');
      targets = await response.json();
    } catch (_) { throw new CheckError('cdp_unsupported'); }
    const target = targets.filter(t => t.type === 'page' &&
      /^file:\/\/\/android_asset\/screensaver\/index\.html(?:\?|$)/.test(t.url || ''));
    check(target.length === 1, 'owned_asset_target_required');
    const socketUrl = new URL(target[0].webSocketDebuggerUrl);
    check(socketUrl.protocol === 'ws:' && /^\/devtools\/page\/[A-Za-z0-9_.-]+$/.test(socketUrl.pathname) &&
      !socketUrl.search && !socketUrl.hash, 'owned_page_socket_required');
    report.cdpStage = 'page-socket';
    const nativePage = await connectNativePage(`ws://127.0.0.1:${port}${socketUrl.pathname}`, bounded);
    const page = nativePage.page;
    session = nativePage.session;
    report.cdpStage = 'native-checks';
    await bounded(page.waitForFunction(() => {
      const loading = document.getElementById('loading');
      return loading?.classList.contains('hide') &&
        [...document.querySelectorAll('#stage *')].some(node =>
          getComputedStyle(node).backgroundImage.startsWith('url('));
    }, null, { timeout: remaining('test', 25_000) }), remaining('test', 26_000), 'first_scene_timeout');
    const scene = await evaluate(page, async () => {
      const urls = [...new Set([...document.querySelectorAll('#stage *')]
        .map(node => getComputedStyle(node).backgroundImage)
        .filter(value => value.startsWith('url('))
        .map(value => value.slice(4, -1).replace(/^["']|["']$/g, '')))];
      const dimensions = await Promise.all(urls.map(url => new Promise(resolve => {
        const im = new Image(), timer = setTimeout(() => done(), 4000);
        function done() {
          clearTimeout(timer); im.onload = im.onerror = null;
          resolve({ url, width: im.naturalWidth, height: im.naturalHeight }); im.src = '';
        }
        im.onload = im.onerror = done; im.src = url;
      })));
      return { bridge: !!window.Android?.scaledUrlForSize, urls: dimensions,
        originals: JSON.parse(window.Android.getPhotos()) };
    });
    check(scene.bridge && scene.originals.length === fixtures.length &&
      scene.originals.every(p => p.url.startsWith(`file://${remoteRoot}/`)), 'owned_synthetic_feed_required');
    function kind(url) {
      if (/^https:\/\/immortal-photo\.invalid\/\d+\/\d+\/(?:600|1200|1800|2400)$/.test(url)) return 'guarded';
      if (/^file:\/\/\/data\/(?:user\/0|data)\/com\.immortal\.launcher\.debug\/cache\/ss_scaled\/[a-f0-9]{40}\.jpg$/.test(url))
        return 'derivative';
      return 'raw';
    }
    const decoded = scene.urls.filter(im => im.width > 0 && im.height > 0);
    check(decoded.length > 0 && decoded.every(im => kind(im.url) !== 'raw' &&
      Math.max(im.width, im.height) <= 2400), 'first_scene_bounded');
    report.firstScene = true;
    report.sceneDimensions = decoded.map(im => [im.width, im.height]);
    // Freeze the visible composition so it cannot consume the invalid fixture later.
    await evaluate(page, () => window.immortalSlideshow.pause());

    // Change only our synthetic file's identity after warm-up; this must be cold.
    await adb(['shell', 'touch', '-t', '203001010000.00', `${remoteRoot}/${coldName}`]);
    const coldStat = await adb(['shell', 'stat', '-c', '%Y', `${remoteRoot}/${coldName}`]);
    check(/^\d+$/.test(coldStat), 'cold_identity_verify');
    rememberCacheKeys(coldName, Number(coldStat) * 1000);
    const probe = await evaluate(page, async ({ root, coldName, smallName }) => {
      const find = name => JSON.parse(Android.getPhotos()).find(p => p.url === `file://${root}/${name}`).url;
      const load = (url, timeout = 8000) => new Promise(resolve => {
        const im = new Image(), timer = setTimeout(() => done(false), timeout);
        function done(ok) {
          clearTimeout(timer); im.onload = im.onerror = null;
          resolve({ ok, url: im.currentSrc || url, width: im.naturalWidth, height: im.naturalHeight });
          im.src = '';
        }
        im.onload = async () => { try { await im.decode(); done(true); } catch (_) { done(false); } };
        im.onerror = () => done(false);
        im.src = url;
      });
      const coldUrl = Android.scaledUrlForSize(find(coldName), 2400);
      if (!/^https:\/\/immortal-photo\.invalid\/\d+\/\d+\/2400$/.test(coldUrl))
        return { coldUrl }; // Never make our own probe load an unchecked large original.
      const cold = await load(coldUrl);
      const readyUrl = Android.scaledUrlForSize(find(coldName), 2400);
      let smallUrl = null, small = null;
      if (smallName) {
        smallUrl = Android.scaledUrlForSize(find(smallName), 600);
        if (!(smallUrl === find(smallName) || /^https:\/\/immortal-photo\.invalid\//.test(smallUrl) ||
          /^file:\/\/\/data\/(?:user\/0|data)\/com\.immortal\.launcher\.debug\/cache\/ss_scaled\//.test(smallUrl)))
          return { coldUrl, cold, smallUrl, small: { ok: false } };
        small = await load(smallUrl);
      }
      const invalidUrl = Android.scaledUrlForSize(find('zz-invalid.jpg'), 600);
      // Cross-origin fetch may be disallowed for file:// origins; Network CDP
      // observes WebView's actual status instead of relying on CORS fetch.
      return { coldUrl, cold, readyUrl, smallUrl, small, invalidUrl };
    }, { root: remoteRoot, coldName, smallName }, 19_000);
    check(kind(probe.coldUrl) === 'guarded', 'cold_url_must_be_opaque');
    check(probe.cold?.ok && Math.max(probe.cold.width, probe.cold.height) === 2400 &&
      kind(probe.readyUrl || '') === 'derivative', 'cold_decode_bounded');
    if (smallName) {
      check(probe.small?.ok && Math.max(probe.small.width, probe.small.height) <= 750 &&
        (kind(probe.smallUrl) !== 'raw' || probe.smallUrl === `file://${remoteRoot}/${smallName}`),
        'header_verified_small_original');
      report.smallDecoded = true;
      report.smallDimensions = [probe.small.width, probe.small.height];
    } else report.smallCheckSkipped = true;
    report.coldGuarded = report.coldDecoded = true;
    report.coldDimensions = [probe.cold.width, probe.cold.height];
    check(kind(probe.invalidUrl) === 'guarded', 'failure_url_must_be_guarded');
    await session.send('Network.enable');
    let invalidStatus = null;
    const response = event => {
      if (event.response.url === probe.invalidUrl) invalidStatus = event.response.status;
    };
    session.on('Network.responseReceived', response);
    try {
      const invalid = await evaluate(page, url => new Promise(resolve => {
        const im = new Image(), timer = setTimeout(() => finish(false), 5000);
        function finish(ok) { clearTimeout(timer); im.onload = im.onerror = null; im.src = ''; resolve(ok); }
        im.onload = () => finish(true); im.onerror = () => finish(false); im.src = url;
      }), probe.invalidUrl, 6000);
      check(!invalid && invalidStatus === 404, 'failure_must_return_404');
      report.invalid404 = true;
    } finally { session.off('Network.responseReceived', response); }
    report.status = 'passed';
  }

  async function cleanup() {
    // Continue independently after individual cleanup failures within the one deadline.
    const failures = [];
    async function attempt(code, work) {
      try { await work(); } catch (_) { failures.push(code); }
    }
    if (launched || mutated || pushed || forward) {
      // Refuse cleanup against a replaced emulator.
      await identity('cleanup');
    }
    if (session) await attempt('cdp_detach', () => bounded(session.detach(), remaining('cleanup', 1500), 'cdp_detach'));
    // Release our CDP binding first, leaving the parent's API forward intact.
    if (forward) await attempt('owned_forward_remove', async () => {
      const list = await command(['forward', '--list'], { cap: 1500 });
      const binding = `${SERIAL} ${forward.local} ${forward.destination}`;
      check(list.split(/\r?\n/).some(line => line.trim() === binding), 'forward_ownership_changed');
      await command(['forward', '--remove', forward.local], { cap: 1500 });
      report.ownedForwardRemoved = true;
    });
    if (launched) await attempt('preview_stop', () => command(['shell', 'am', 'force-stop', PKG]));
    if (mutated) {
      // Reopen only the owned APK to run the same registry side-effect hooks.
      await attempt('api_restore', async () => {
        await command(['shell', 'am', 'start', '-n', `${PKG}/com.immortal.launcher.HomeActivity`]);
        let ready = false;
        for (let n = 0; n < 10; n++) {
          try { await api('/remote/settings/profile', 'GET', undefined, 'cleanup'); ready = true; break; }
          catch (_) { await delay(500); }
        }
        check(ready, 'restore_readiness');
        const keys = ['mode', 'shuffle', 'includeVideo', 'intervalSec', 'overnightEnabled', 'darkRoomClock'];
        const restore = { source: 'default' };
        for (const key of keys) if (key in originals.screensaver.screensaver) restore[key] = originals.screensaver.screensaver[key];
        await api('/screensaver', 'POST', restore, 'cleanup');
      });
      // Exact original prefs remove the temporary folder path, including an
      // originally absent preference that the API cannot represent.
      await attempt('exact_preferences_restore', async () => {
        await command(['shell', 'am', 'force-stop', PKG]);
        const restoreDir = `cache/${path.basename(remoteRoot)}-restore`;
        // Extract completely before replacing prefs; a broken pipe cannot destroy
        // the existing directory. The private host archive is the recovery source.
        await command(['shell', '-T', 'run-as', PKG, 'sh', '-c',
          `'mkdir -p ${restoreDir} && tar -xf - -C ${restoreDir} && ` +
          `mv shared_prefs ${restoreDir}/changed-prefs && ` +
          `mv ${restoreDir}/shared_prefs shared_prefs'`], { input: prefArchive });
        const restored = await command(['exec-out', 'run-as', PKG, 'tar', '-cf', '-', 'shared_prefs'], { binary: true });
        check(same(preferencePayloads(restored), preferencePayloads(prefArchive)), 'exact_preferences_restore');
        await command(['shell', 'run-as', PKG, 'rm', '-rf', restoreDir]);
      });
      await attempt('restore_readback', async () => {
        await command(['shell', 'am', 'start', '-n', `${PKG}/com.immortal.launcher.HomeActivity`]);
        let restored;
        for (let n = 0; n < 10; n++) {
          try {
            restored = { screensaver: await api('/screensaver', 'GET', undefined, 'cleanup'),
              sources: await api('/remote/sources', 'GET', undefined, 'cleanup'),
              profile: await api('/remote/settings/profile', 'GET', undefined, 'cleanup') };
            break;
          } catch (_) { await delay(500); }
        }
        check(restored && same(restored.screensaver.screensaver, originals.screensaver.screensaver) &&
          same(restored.sources.sources, originals.sources.sources) &&
          same(restored.sources.calendar, originals.sources.calendar) &&
          same(restored.profile.settings, originals.profile.settings), 'restoration_readback');
      });
    }
    if (pushed) await attempt('synthetic_folder_remove',
      () => command(['shell', 'rm', '-rf', remoteRoot]));
    if (ownedCacheKeys.size) await attempt('synthetic_derivatives_remove', async () => {
      // Exact known SHA-1 names only: no cache wipe, directory scan, or wildcard.
      const files = [...ownedCacheKeys].flatMap(key =>
        [`cache/ss_scaled/${key}`, `cache/ss_scaled/${key}.part`]);
      await command(['shell', 'run-as', PKG, 'rm', '-f', ...files]);
    });
    report.restored = failures.length === 0;
    if (failures.length) {
      report.cleanupFailures = failures;
      report.status = 'failed';
      report.privateRecoveryRetained = !!prefArchive;
    } else if (privateDir) {
      // Parent-selected private recovery directory is kept; private originals are
      // deliberately retained for manual recovery/audit, never exported.
      report.privateRecoveryRetained = true;
    }
  }

  let testTimer, rejectSignal;
  const onSignal = () => {
    if (phase !== 'test') return;
    testAbort.abort(); stopChildren();
    rejectSignal?.(new CheckError('interrupted'));
  };
  process.on('SIGINT', onSignal);
  process.on('SIGTERM', onSignal);
  try {
    await Promise.race([test(), new Promise((_, reject) => { rejectSignal = reject; }), new Promise((_, reject) => {
      testTimer = setTimeout(() => { testAbort.abort(); stopChildren(); reject(new CheckError('test_deadline')); }, TEST_MS);
    })]);
  } catch (error) {
    report.failure = error instanceof CheckError ? error.code : 'harness_internal_failure';
    report.status = report.failure === 'cdp_unsupported' ? 'unsupported' : 'failed';
  } finally {
    clearTimeout(testTimer); testAbort.abort(); stopChildren();
    phase = 'cleanup'; deadline = Date.now() + CLEANUP_MS;
    try { await bounded(cleanup(), CLEANUP_MS, 'cleanup_deadline'); }
    catch (error) {
      stopChildren(); report.status = 'failed'; report.restored = false;
      report.cleanupFailures = [error instanceof CheckError ? error.code : 'cleanup_failed'];
      report.privateRecoveryRetained = !!prefArchive;
    }
    phase = 'done';
    process.off('SIGINT', onSignal);
    process.off('SIGTERM', onSignal);
  }
  report.elapsedMs = Date.now() - started;
  process.stdout.write(`${JSON.stringify(report)}\n`);
  // Tear down remaining CDP client sockets locally after native state restoration.
  process.exit(report.status === 'passed' ? 0 : report.status === 'unsupported' ? 2 : 1);
}
if (require.main === module) main().catch(() => {
  process.stdout.write('{"status":"failed","failure":"harness_initialization","native":true}\n');
  process.exit(1);
});
