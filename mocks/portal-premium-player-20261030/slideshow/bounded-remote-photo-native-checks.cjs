/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
'use strict';

/**
 * Native acceptance SIDECAR ONLY; never imported or executed while authoring.
 *
 * Based on the corrected local harness at 1ce7590f: direct WebView page
 * WebSocket CDP, actual CSS backgrounds, exact preference-file payload recovery.
 * No browser fixture, install, build, real Immich host/key, or performance gate.
 *
 * Parent must install the current debug APK, leave a Home-only process on the
 * exclusively owned PortalMusic20261030/emulator-5556 (API 34), and enable CDP
 * only in debug startEngine. This harness requires Node >=22 / built-in
 * WebSocket and adb on PATH. All device commands specify emulator-5556.
 *
 * Invocation from the worktree (tokens never go on the command line):
 * node mocks/portal-premium-player-20261030/slideshow/bounded-remote-photo-native-checks.cjs \
 *   --connection-file "$PRIVATE_CONNECTION_JSON" \
 *   --expected-apk-sha256 "$CURRENT_DEBUG_APK_SHA256" \
 *   --fixtures-dir "$PUBLIC_SYNTHETIC_FIXTURES_DIR" \
 *   --private-dir "$NEW_PRIVATE_RECOVERY_DIR"
 *
 * Connection JSON: owner-only, outside checkouts, mode 0600:
 * {"baseUrl":"http://127.0.0.1:<parent-forward-port>","token":"<private>",
 *  "serial":"emulator-5556","avd":"PortalMusic20261030","apiLevel":34,
 *  "packageName":"com.immortal.launcher.debug","apkSha256":"<64 hex>"}
 * Parent's existing forward must be tcp:<port> -> tcp:8723 on this serial.
 *
 * Fixtures: SIX public synthetic *.jpg/*.jpeg files, simple filenames, each
 * 6400x4800 or 4800x6400; both orientations, <12 MiB each. Optional small.jpg
 * is ignored. Optional alpha.png: 3200x2400 RGBA PNG, <=32 MiB encoded.
 * When supplied, metadata also includes id "alpha" (type IMAGE).
 * No large image generation in Node. Invalid bodies are tiny and synthetic.
 *
 * The HTTP server binds ONLY 127.0.0.1 on an ephemeral port. A no-rebind,
 * serial-scoped adb reverse maps the same owned device port to that server.
 * Every fake Immich request must carry the PUBLIC dummy x-api-key. Metadata:
 * POST /<unique-round>/api/search/metadata -> {assets:{items:[{id,type:"IMAGE"}],
 * nextPage:null}}. Thumbnails intentionally serve camera-sized JPEGs.
 *
 * The valid round holds thumbnails until CDP captures a cold guarded URL,
 * then checks native first-composition CSS backgrounds and a decoded 2400px
 * derivative. Optional alpha coverage checks a bounded PNG decode and actual
 * PNG signature/alpha color type at the exact validated ready cache file.
 * Fresh namespaces/owners check invalid bytes and a single 32 MiB + 1 byte
 * encoded response returning native 404 if the remaining budget allows.
 * Oversize bytes stream from a reusable 64KiB buffer with backpressure; no
 * giant buffer, giant image generation, throughput sampling or benchmark.
 *
 * Test <=120s including guards; cleanup <=40s. Default source is required
 * before mutations. Full API state/prefs are saved privately. Only dummy
 * provider credentials are posted; real stored keys are never sent to a peer.
 * Cleanup restores all preference groups and the full profile, removes only
 * this invocation's CDP forward/reverse/cache additions, and closes its server.
 * Exact pref file bytes, not tar metadata, are verified while the app is stopped.
 * Recovery originals remain in the parent-selected private directory.
 *
 * Stdout: allowlisted scalars ONLY. No raw errors, URLs, paths, tokens, header
 * contents, source credentials, photo names, logcat, screenshots or API bodies.
 * Exit 0 pass, 1 failure, 2 unsupported native CDP. Never substitute browser proof.
 * Decoder thread-affinity/shared serial internals remain parent's source tests.
 */
const fs = require('node:fs');
const fsp = fs.promises;
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const { EventEmitter } = require('node:events');

const SERIAL = 'emulator-5556', AVD = 'PortalMusic20261030';
const PKG = 'com.immortal.launcher.debug';
const PREVIEW = `${PKG}/com.immortal.launcher.PhotoFramePreviewActivity`;
const HOME = `${PKG}/com.immortal.launcher.HomeActivity`;
const TEST_MS = 120_000, CLEANUP_MS = 40_000;
const DUMMY_KEY = 'public-synthetic-native-check-only';
const MAX_JPEG_BYTES = 12 * 1024 * 1024;
const ENCODED_CAP = 32 * 1024 * 1024;
const PNG_MAGIC = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const sha256 = data => crypto.createHash('sha256').update(data).digest('hex');
class CheckError extends Error {
  constructor(code) { super(code); this.code = code; }
}
function check(condition, code) { if (!condition) throw new CheckError(code); }
function canonical(value) {
  if (Array.isArray(value)) return JSON.stringify(value.map(v => JSON.parse(canonical(v))));
  if (value && typeof value === 'object') return JSON.stringify(Object.fromEntries(
    Object.keys(value).sort().map(key => [key, JSON.parse(canonical(value[key]))])));
  return JSON.stringify(value);
}
const same = (a, b) => canonical(a) === canonical(b);
function options() {
  const allowed = new Set(['connection-file', 'fixtures-dir', 'private-dir', 'expected-apk-sha256']);
  const result = {};
  for (let i = 2; i < process.argv.length; i += 2) {
    const key = process.argv[i].replace(/^--/, '');
    check(process.argv[i].startsWith('--') && allowed.has(key) &&
      process.argv[i + 1] && !result[key], 'usage');
    result[key] = process.argv[i + 1];
  }
  check([...allowed].every(key => result[key]), 'usage');
  check(/^[a-fA-F0-9]{64}$/.test(result['expected-apk-sha256']), 'expected_apk_hash_required');
  return result;
}
// Read JPEG headers only; fixture pixels are never decoded or generated by Node.
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
    const size = bytes.readUInt16BE(offset);
    check(size >= 2 && offset + size <= bytes.length, 'fixture_jpeg_segment');
    if ([0xc0, 0xc1, 0xc2].includes(marker)) {
      check(size >= 8, 'fixture_jpeg_sof');
      return [bytes.readUInt16BE(offset + 5), bytes.readUInt16BE(offset + 3)];
    }
    offset += size;
  }
  throw new CheckError('fixture_jpeg_dimensions');
}
function preferencePayloads(bytes) {
  const entries = {};
  for (let offset = 0; offset + 512 <= bytes.length;) {
    const header = bytes.subarray(offset, offset + 512);
    if (header.every(byte => byte === 0)) break;
    const name = header.subarray(0, 100).toString('utf8').split('\0')[0];
    const prefix = header.subarray(345, 500).toString('utf8').split('\0')[0];
    const sizeText = header.subarray(124, 136).toString('ascii').replace(/\0/g, '').trim();
    check(!prefix && /^[0-7]+$/.test(sizeText), 'preference_archive_header');
    const size = parseInt(sizeText, 8), type = header[156];
    check(offset + 512 + size <= bytes.length, 'preference_archive_bounds');
    if (type === 0 || type === 48) {
      check(/^shared_prefs\/[A-Za-z0-9_.-]+\.xml(?:\.bak)?$/.test(name) &&
        !(name in entries), 'preference_archive_path');
      entries[name] = sha256(bytes.subarray(offset + 512, offset + 512 + size));
    } else check(type === 53 && /^shared_prefs\/?$/.test(name), 'preference_archive_type');
    offset += 512 + Math.ceil(size / 512) * 512;
  }
  check(Object.keys(entries).length > 0, 'preference_archive_empty');
  return entries;
}
function imageKind(url) {
  if (/^https:\/\/immortal-remote-photo\.invalid\/\d+\/\d+\/(?:600|1200|1800|2400)$/.test(url))
    return 'guarded';
  if (/^file:\/\/\/data\/(?:user\/0|data)\/com\.immortal\.launcher\.debug\/cache\/ss_scaled\/(?:[a-f0-9]{40}|[a-f0-9]{64})\.jpg$/.test(url))
    return 'derivative';
  if (/^file:\/\/\/data\/(?:user\/0|data)\/com\.immortal\.launcher\.debug\/cache\/ss_remote\/[A-Za-z0-9_-]+\/(?:[a-f0-9]{40}|[a-f0-9]{64})\.source$/.test(url))
    return 'verified-source';
  return 'raw';
}
function reverseRows(text) {
  return text.split(/\r?\n/).filter(Boolean).map(line => line.trim().split(/\s+/));
}

async function main() {
  const started = Date.now(), testAbort = new AbortController();
  let phase = 'test', deadline = started + TEST_MS;
  let opts, connection, privateDir, originals, prefArchive, originalPayloads, installedPath;
  let cacheBaseline, provider, reverse, forward, session, page;
  let mutated = false, launched = false, privateCreated = false;
  const children = new Set(), sockets = new Set(), rounds = new Map();
  const nonce = crypto.randomBytes(12).toString('hex');
  const counters = { authenticated: 0, rejectedAuth: 0, metadata: 0,
    images: 0, invalidImages: 0, oversizeImages: 0, sentBytes: 0, malformedSearch: 0 };
  const report = {
    status: 'failed', native: true, serial: SERIAL, avd: AVD, apiLevel: 34,
    firstScene: false, coldGuarded: false, coldDecoded: false, readyDerivative: false,
    authRespected: false, invalid404: false, oversize404: false,
    invalidCheckSkipped: false, oversizeCheckSkipped: false, restored: false,
    alphaProvided: false, alphaCheckSkipped: true, alphaDecoded: false, alphaPngMagic: false,
    ownedForwardRemoved: true, ownedReverseRemoved: true, serverClosed: true,
  };

  function remaining(requestPhase = phase, cap = 8_000) {
    check(requestPhase === phase && Date.now() < deadline, `${requestPhase}_deadline`);
    return Math.max(1, Math.min(cap, deadline - Date.now()));
  }
  function stopChildren() { for (const child of children) child.kill('SIGKILL'); }
  async function bounded(promise, ms, code) {
    let timer;
    try { return await Promise.race([promise, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new CheckError(code)), ms);
    })]); } finally { clearTimeout(timer); }
  }
  async function command(args, { input, binary = false, cap = 4_000, requestPhase = phase } = {}) {
    const timeout = remaining(requestPhase, cap);
    return new Promise((resolve, reject) => {
      const child = spawn('adb', ['-s', SERIAL, ...args], { stdio: ['pipe', 'pipe', 'pipe'] });
      children.add(child);
      let settled = false, length = 0;
      const chunks = [];
      const finish = (error, result) => {
        if (settled) return;
        settled = true; clearTimeout(timer); children.delete(child);
        error ? reject(error) : resolve(result);
      };
      const timer = setTimeout(() => {
        child.kill('SIGKILL'); finish(new CheckError('adb_timeout'));
      }, timeout);
      child.stdout.on('data', bytes => {
        length += bytes.length;
        if (length > 32 * 1024 * 1024) {
          child.kill('SIGKILL'); finish(new CheckError('adb_output_limit'));
        } else chunks.push(bytes);
      });
      child.stderr.resume(); // Never export native stderr/private data.
      child.stdin.on('error', () => {});
      child.on('error', () => finish(new CheckError('adb_unavailable')));
      child.on('close', code => {
        if (code !== 0) return finish(new CheckError('adb_failed'));
        const bytes = Buffer.concat(chunks);
        finish(null, binary ? bytes : bytes.toString('utf8').trim());
      });
      child.stdin.end(input);
    });
  }
  const adb = (args, settings) => command(args, { ...settings, requestPhase: 'test' });
  async function api(route, method = 'GET', body, requestPhase = phase, cap = 3500) {
    const timeout = remaining(requestPhase, cap);
    try {
      const response = await fetch(new URL(route, connection.baseUrl), {
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
    check((await command(['emu', 'avd', 'name'], { requestPhase, cap: 1500 }))
      .split(/\r?\n/)[0] === AVD, 'avd_guard');
    check(await command(['shell', 'getprop', 'ro.build.version.sdk'],
      { requestPhase, cap: 1500 }) === '34', 'api_level_guard');
  }
  async function apkGuard(requestPhase = phase) {
    const paths = (await command(['shell', 'pm', 'path', PKG], { requestPhase })).split(/\r?\n/);
    check(paths.length === 1 && /^package:\/data\/app\/[A-Za-z0-9_+=/~.-]+\/base\.apk$/.test(paths[0]),
      'single_apk_guard');
    const currentPath = paths[0].slice('package:'.length);
    check(!installedPath || currentPath === installedPath, 'installed_apk_changed');
    const digest = await command(['shell', 'sha256sum', currentPath], { requestPhase });
    check(digest.split(/\s/)[0] === opts['expected-apk-sha256'].toLowerCase(), 'installed_apk_hash_guard');
    installedPath = currentPath;
  }
  async function privateWrite(name, value) {
    remaining('test');
    await fsp.writeFile(path.join(privateDir, name), value, { mode: 0o600, flag: 'wx' });
  }
  async function cacheSnapshot(requestPhase = phase) {
    const output = await command(['shell', 'run-as', PKG, 'sh', '-c',
      "'for d in cache/ss_scaled cache/ss_remote; do if [ -d \"$d\" ]; then find \"$d\"; fi; done'"],
    { requestPhase, cap: 2000 });
    return new Set(output.split(/\r?\n/).filter(Boolean));
  }

  async function connectPage(url) {
    const socket = new WebSocket(url), pending = new Map(), events = new EventEmitter();
    let nextId = 0;
    const disconnected = () => {
      for (const request of pending.values()) request.reject(new CheckError('cdp_disconnected'));
      pending.clear();
    };
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
    socket.addEventListener('close', disconnected);
    socket.addEventListener('error', disconnected);
    // Register ownership before awaiting connection, including timeout/error cases.
    session = {
      send(method, params = {}) {
        const timeout = remaining('test', 10_000);
        check(socket.readyState === WebSocket.OPEN, 'cdp_disconnected');
        const id = ++nextId;
        return bounded(new Promise((resolve, reject) => {
          pending.set(id, { resolve, reject });
          socket.send(JSON.stringify({ id, method, params }));
        }), timeout, 'cdp_command_timeout').finally(() => pending.delete(id));
      },
      on: (event, fn) => events.on(event, fn),
      off: (event, fn) => events.off(event, fn),
      detach: async () => { socket.close(); disconnected(); },
    };
    await bounded(new Promise((resolve, reject) => {
      socket.addEventListener('open', resolve, { once: true });
      socket.addEventListener('error', () => reject(new CheckError('cdp_socket_failed')), { once: true });
    }), remaining('test', 3000), 'cdp_socket_timeout');
    page = {
      async evaluate(fn, argument) {
        const result = await session.send('Runtime.evaluate', {
          expression: `(${fn.toString()})(${JSON.stringify(argument) ?? 'undefined'})`,
          awaitPromise: true, returnByValue: true,
        });
        check(!result.exceptionDetails, 'native_evaluation_exception');
        return result.result?.value;
      },
    };
  }
  async function evaluate(fn, argument, cap = 10_000) {
    const result = await bounded(page.evaluate(fn, argument), remaining('test', cap), 'evaluation_timeout');
    remaining('test');
    return result;
  }
  async function detachPage() {
    if (session) { await session.detach(); session = null; page = null; }
    if (!forward) return;
    const list = await command(['forward', '--list'], { cap: 1500 });
    const exact = `${SERIAL} ${forward.local} ${forward.destination}`;
    check(list.split(/\r?\n/).some(line => line.trim() === exact), 'forward_ownership_changed');
    await command(['forward', '--remove', forward.local], { cap: 1500 });
    forward = null; report.ownedForwardRemoved = true;
  }
  async function attachPreview() {
    const pid = await adb(['shell', 'pidof', PKG]);
    check(/^\d+$/.test(pid), 'single_owned_pid_required');
    let available = false;
    const socketDeadline = Date.now() + remaining('test', 8000);
    while (Date.now() < socketDeadline) {
      const list = await adb(['shell', 'cat', '/proc/net/unix'], { cap: 1200 });
      available = list.split(/\r?\n/).some(line => line.trim().endsWith(`@webview_devtools_remote_${pid}`));
      if (available) break;
      await delay(150);
    }
    check(available, 'cdp_unsupported');
    const destination = `localabstract:webview_devtools_remote_${pid}`;
    const port = await adb(['forward', '--no-rebind', 'tcp:0', destination]);
    check(/^\d+$/.test(port), 'cdp_forward_failed');
    forward = { local: `tcp:${port}`, destination };
    report.ownedForwardRemoved = false;
    const listDeadline = Date.now() + remaining('test', 3000);
    let target;
    while (Date.now() < listDeadline) {
      try {
        const response = await fetch(`http://127.0.0.1:${port}/json/list`, {
          redirect: 'error', signal: AbortSignal.any([testAbort.signal, AbortSignal.timeout(1000)]),
        });
        check(response.ok, 'cdp_unsupported');
        const targets = (await response.json()).filter(value => value.type === 'page' &&
          /^file:\/\/\/android_asset\/screensaver\/index\.html(?:\?|$)/.test(value.url || ''));
        check(targets.length <= 1, 'ambiguous_native_page');
        if (targets.length === 1) { target = targets[0]; break; }
      } catch (error) {
        if (error instanceof CheckError && error.code === 'ambiguous_native_page') throw error;
      }
      await delay(100);
    }
    check(target, 'cdp_unsupported');
    const url = new URL(target.webSocketDebuggerUrl);
    check(url.protocol === 'ws:' && /^\/devtools\/page\/[A-Za-z0-9_.-]+$/.test(url.pathname) &&
      !url.search && !url.hash, 'owned_page_socket_required');
    // Ignore discovery's host: connect only to our validated owned local binding.
    try { await connectPage(`ws://127.0.0.1:${port}${url.pathname}`); }
    catch (error) {
      if (error instanceof CheckError && error.code.startsWith('cdp_')) throw new CheckError('cdp_unsupported');
      throw error;
    }
    await session.send('Network.enable');
  }

  function jsonResponse(response, status, body) {
    response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify(body));
  }
  function serveAsset(response, round, asset) {
    if (response.destroyed || response.writableEnded) return;
    counters.images++;
    let bytes;
    if (round.mode === 'invalid') {
      counters.invalidImages++; bytes = Buffer.from('not-a-valid-jpeg!\n');
    } else if (round.mode === 'oversize') {
      // Only index zero exercises the encoded cap; all other assets fail cheaply.
      // This preserves the six-asset provider schema without six large transfers.
      if (asset === round.assets[0]) {
        counters.oversizeImages++;
        response.writeHead(200, {
          'Content-Type': 'image/jpeg', 'Cache-Control': 'no-store', Connection: 'close',
          'Content-Length': ENCODED_CAP + 1,
        });
        let written = 0;
        const scratch = Buffer.alloc(64 * 1024);
        const pump = () => {
          while (phase === 'test' && !response.destroyed && written < ENCODED_CAP + 1) {
            // Start with an actual synthetic JPEG, followed by inert encoded padding.
            const chunk = written < asset.bytes.length
              ? asset.bytes.subarray(written, Math.min(asset.bytes.length, written + scratch.length))
              : scratch.subarray(0, Math.min(scratch.length, ENCODED_CAP + 1 - written));
            written += chunk.length; counters.sentBytes += chunk.length;
            if (!response.write(chunk)) { response.once('drain', pump); return; }
          }
          if (!response.destroyed) response.end();
        };
        pump();
        return;
      }
      bytes = Buffer.from('not-a-valid-jpeg!\n');
    } else bytes = asset.bytes;
    counters.sentBytes += bytes.length;
    response.writeHead(200, {
      'Content-Type': asset.mime, 'Cache-Control': 'no-store', Connection: 'close',
      'Content-Length': bytes.length,
    });
    response.end(bytes);
  }
  function releaseRound(round) {
    round.released = true;
    for (const item of round.held) {
      clearTimeout(item.timer); serveAsset(item.response, round, item.asset);
    }
    round.held.clear();
  }
  async function startProvider(fixtures) {
    remaining('test');
    provider = http.createServer((request, response) => {
      response.on('error', () => {});
      request.on('error', () => {});
      if (phase !== 'test') return jsonResponse(response, 503, { error: 'test_finished' });
      if (request.headers['x-api-key'] !== DUMMY_KEY) {
        counters.rejectedAuth++; return jsonResponse(response, 401, { error: 'dummy_key_required' });
      }
      counters.authenticated++;
      let url;
      try { url = new URL(request.url, 'http://127.0.0.1'); }
      catch (_) { return jsonResponse(response, 400, { error: 'bad_request' }); }
      const parts = url.pathname.split('/'), round = rounds.get(parts[1]);
      if (!round || parts[2] !== 'api') return jsonResponse(response, 404, { error: 'not_found' });
      if (request.method === 'GET' && parts.slice(3).join('/') === 'users/me')
        return jsonResponse(response, 200, { id: 'public-synthetic-user' });
      if (request.method === 'GET' && parts.slice(3).join('/') === 'albums')
        return jsonResponse(response, 200, []);
      if (request.method === 'POST' && parts.slice(3).join('/') === 'search/metadata') {
        const chunks = []; let length = 0, tooLarge = false;
        request.on('data', chunk => {
          length += chunk.length;
          if (length > 16 * 1024) {
            if (!tooLarge) jsonResponse(response, 413, { error: 'body_limit' });
            tooLarge = true;
          } else chunks.push(chunk);
        });
        request.on('end', () => {
          if (tooLarge || response.destroyed) return;
          let body;
          try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch (_) {}
          // Match ImmichSource.searchAssets exactly; never use any real album.
          if (!body || body.page !== 1 || body.size !== 1000 || body.type !== 'IMAGE' ||
            Object.prototype.hasOwnProperty.call(body, 'albumIds')) {
            counters.malformedSearch++;
            return jsonResponse(response, 400, { error: 'synthetic_search_schema' });
          }
          counters.metadata++;
          return jsonResponse(response, 200, {
            assets: { items: round.assets.map(asset => ({ id: asset.id, type: 'IMAGE' })), nextPage: null },
          });
        });
        return;
      }
      if (request.method === 'GET' && parts.length === 6 && parts[3] === 'assets' &&
        parts[5] === 'thumbnail' && url.search === '?size=preview') {
        const asset = round.assets.find(value => value.id === parts[4]);
        if (!asset) return jsonResponse(response, 404, { error: 'unknown_synthetic_asset' });
        if (round.released) return serveAsset(response, round, asset);
        if (round.held.size >= 32) return jsonResponse(response, 503, { error: 'held_limit' });
        const held = { response, asset, timer: null };
        held.timer = setTimeout(() => {
          round.held.delete(held);
          if (!response.destroyed && !response.writableEnded)
            jsonResponse(response, 503, { error: 'cold_capture_timeout' });
        }, 8000);
        round.held.add(held);
        response.once('close', () => { clearTimeout(held.timer); round.held.delete(held); });
        return;
      }
      jsonResponse(response, 404, { error: 'not_found' });
    });
    provider.on('connection', socket => {
      sockets.add(socket); socket.on('error', () => {}); socket.on('close', () => sockets.delete(socket));
    });
    provider.on('error', () => {}); // Never print native/network errors.
    provider.requestTimeout = 5000; provider.headersTimeout = 5000; provider.keepAliveTimeout = 500;
    report.serverClosed = false;
    await bounded(new Promise((resolve, reject) => {
      provider.once('error', () => reject(new CheckError('provider_listen_failed')));
      provider.listen(0, '127.0.0.1', resolve);
    }), remaining('test', 2000), 'provider_listen_timeout');
    const address = provider.address();
    check(address && address.address === '127.0.0.1' && address.port > 1024, 'localhost_provider_guard');
    const mapping = `tcp:${address.port}`;
    const before = reverseRows(await adb(['reverse', '--list']));
    check(before.every(row => row[1] !== mapping), 'reverse_port_already_owned');
    reverse = { remote: mapping, local: mapping, attempted: true };
    report.ownedReverseRemoved = false;
    await adb(['reverse', '--no-rebind', mapping, mapping]);
    const after = reverseRows(await adb(['reverse', '--list']));
    check(after.some(row => row.length === 3 && row[1] === mapping && row[2] === mapping), 'owned_reverse_guard');
    for (const mode of ['valid', 'invalid', 'oversize']) {
      const namespace = `synthetic-${nonce}-${mode}`;
      rounds.set(namespace, {
        namespace, mode, released: mode !== 'valid', held: new Set(),
        baseUrl: `http://127.0.0.1:${address.port}/${namespace}`,
        assets: fixtures.map((fixture, index) => ({
          id: fixture.alpha ? 'alpha' : `${namespace}-${index}`, bytes: fixture.bytes, mime: fixture.mime,
        })),
      });
    }
  }
  function closeProvider() {
    for (const round of rounds.values()) for (const held of round.held) {
      clearTimeout(held.timer); held.response.destroy();
    }
    for (const socket of sockets) socket.destroy();
    if (provider) {
      provider.closeAllConnections();
      provider.close();
    }
    report.serverClosed = true;
  }
  async function removeReverse() {
    if (!reverse) return;
    const list = reverseRows(await command(['reverse', '--list'], { cap: 1500 }));
    const rows = list.filter(row => row[1] === reverse.remote);
    // An absent attempted mapping needs no removal; a reassigned mapping is refused.
    check(rows.length === 0 || (rows.length === 1 && rows[0][2] === reverse.local), 'reverse_ownership_changed');
    if (rows.length) await command(['reverse', '--remove', reverse.remote], { cap: 1500 });
    reverse = null; report.ownedReverseRemoved = true;
  }
  async function configureRound(round) {
    remaining('test');
    if (session || forward) await detachPage();
    await apkGuard('test');
    const saved = originals.screensaver.screensaver;
    const patch = {
      immichUrl: round.baseUrl, immichKey: DUMMY_KEY, source: 'immich',
      immichAlbumId: '', immichAlbumName: '', mode: 'kenBurns',
      includeVideo: false, shuffle: false, intervalSec: 600,
    };
    for (const key of ['overnightEnabled', 'darkRoomClock']) if (saved[key] === true) patch[key] = false;
    mutated = true; // Record rollback before POST, including partial/ambiguous failure.
    await api('/screensaver', 'POST', patch, 'test');
    const state = (await api('/screensaver')).screensaver;
    const source = (await api('/remote/sources')).sources;
    check(state.source === 'immich' && state.mode === 'kenBurns' && state.intervalSec === 600 &&
      state.includeVideo === false && state.shuffle === false && source.source === 'immich' &&
      source.immichUrl === round.baseUrl && source.immichKey === DUMMY_KEY &&
      source.immichAlbumId === '', 'synthetic_immich_apply');
    // Verify apply() reached disk before the -S fresh-owner launch.
    let persisted = false;
    for (let attempt = 0; attempt < 8; attempt++) {
      const xml = await adb(['exec-out', 'run-as', PKG, 'cat', 'shared_prefs/immortal_screensaver.xml']);
      persisted = xml.includes(`<string name="immich_url">${round.baseUrl}</string>`) &&
        xml.includes(`<string name="immich_key">${DUMMY_KEY}</string>`) &&
        xml.includes('<string name="source">immich</string>');
      if (persisted) break;
      await delay(100);
    }
    check(persisted, 'provider_preferences_not_persisted');
    launched = true;
    await adb(['shell', 'am', 'start', '-S', '-W', '-n', PREVIEW], { cap: 8000 });
    await attachPreview();
  }
  async function captureCold(round, edge) {
    const capture = await evaluate(({ baseUrl, ids, edge }) => {
      const photos = JSON.parse(Android.getPhotos());
      return { urls: photos.map(photo => photo.url), mode: Android.getMode(),
        guarded: Android.scaledUrlForSize(
          `${baseUrl}/api/assets/${ids[0]}/thumbnail?size=preview`, edge) };
    }, { baseUrl: round.baseUrl, ids: round.assets.map(asset => asset.id), edge });
    const expected = round.assets.map(asset => `${round.baseUrl}/api/assets/${asset.id}/thumbnail?size=preview`);
    check(capture.mode === 'kenBurns' && same(capture.urls, expected), 'owned_synthetic_immich_feed_required');
    check(imageKind(capture.guarded) === 'guarded' && capture.guarded.endsWith(`/${edge}`),
      'cold_remote_url_must_be_opaque');
    return capture.guarded;
  }
  async function loadNative(url) {
    check(imageKind(url) === 'guarded' || imageKind(url) === 'derivative', 'unchecked_remote_probe_refused');
    return evaluate(url => new Promise(resolve => {
      const image = new Image(), timer = setTimeout(() => finish(false), 8000);
      let settled = false;
      function finish(ok) {
        if (settled) return; settled = true; clearTimeout(timer);
        image.onload = image.onerror = null;
        resolve({ ok, width: image.naturalWidth, height: image.naturalHeight,
          currentUrl: image.currentSrc || url });
        image.src = '';
      }
      image.onload = async () => {
        try { await image.decode(); finish(true); } catch (_) { finish(false); }
      };
      image.onerror = () => finish(false); image.src = url;
    }), url, 9500);
  }
  async function checkFailure(round, label) {
    await configureRound(round);
    const url = await captureCold(round, 600);
    let status = null;
    const listener = event => { if (event.response.url === url) status = event.response.status; };
    session.on('Network.responseReceived', listener);
    try {
      const result = await loadNative(url);
      // Allow a CDP event queued immediately after the Image.onerror callback.
      if (status === null) await delay(100);
      check(!result.ok && status === 404, `${label}_must_return_native_404`);
      report[`${label}404`] = true;
    } finally { session.off('Network.responseReceived', listener); }
  }

  async function test() {
    opts = options();
    check(typeof WebSocket === 'function', 'node_websocket_required');
    const connectionPath = await fsp.realpath(opts['connection-file']);
    const connectionStat = await fsp.stat(connectionPath);
    check(connectionStat.isFile() && connectionStat.size <= 8192 && (connectionStat.mode & 0o077) === 0 &&
      (typeof process.getuid !== 'function' || connectionStat.uid === process.getuid()), 'private_connection_permissions');
    connection = JSON.parse(await fsp.readFile(connectionPath, 'utf8'));
    const base = new URL(connection.baseUrl);
    check(base.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(base.hostname) &&
      base.port && base.pathname === '/' && !base.search && !base.hash && !base.username && !base.password,
      'loopback_api_required');
    check(connection.serial === SERIAL && connection.avd === AVD && connection.apiLevel === 34 &&
      connection.packageName === PKG && typeof connection.token === 'string' && connection.token.length > 0,
      'connection_identity_guard');
    check(connection.apkSha256?.toLowerCase() === opts['expected-apk-sha256'].toLowerCase(),
      'connection_apk_hash_guard');

    // Post-install readiness FIRST, before any ADB command/server/auth test/mutation.
    originals = {
      profile: await api('/remote/settings/profile'), screensaver: await api('/screensaver'),
      sources: await api('/remote/sources'), info: await api('/info'), diag: await api('/diag'),
    };
    check(originals.profile.profileComplete === true && Array.isArray(originals.profile.settings?.domains) &&
      originals.info.apiLevel === 34, 'post_install_readiness');
    check(originals.sources.sources?.source === 'default' &&
      originals.screensaver.screensaver?.source === 'default', 'default_source_group_required');
    await identity('test');
    const list = await adb(['forward', '--list']);
    check(list.split(/\r?\n/).some(line => line.trim() === `${SERIAL} tcp:${base.port} tcp:8723`),
      'owned_api_forward_guard');
    await apkGuard('test');
    check(originals.diag.hardware?.appApkPath === installedPath, 'api_owned_apk_guard');
    check(/\bDEBUGGABLE\b/.test(await adb(['shell', 'dumpsys', 'package', PKG])), 'debug_apk_required');
    check(/^\d+$/.test(await adb(['shell', 'run-as', PKG, 'id', '-u'])), 'run_as_required');
    const activity = await adb(['shell', 'dumpsys', 'activity', 'activities']);
    check(!activity.split(/\r?\n/).some(line => /(?:mResumedActivity|topResumedActivity)/.test(line) &&
      line.includes('com.immortal.launcher.PhotoFramePreviewActivity')), 'home_only_process_required');
    report.apkSha256 = opts['expected-apk-sha256'].toLowerCase();

    const repo = await fsp.realpath(path.resolve(__dirname, '../../..'));
    const primary = await fsp.realpath('~/portal-prime');
    check(path.isAbsolute(opts['private-dir']), 'absolute_private_directory_required');
    const parent = await fsp.realpath(path.dirname(opts['private-dir']));
    privateDir = path.join(parent, path.basename(opts['private-dir']));
    for (const candidate of [connectionPath, privateDir]) check(![repo, primary].some(root =>
      candidate === root || candidate.startsWith(`${root}${path.sep}`)), 'private_state_outside_checkout');
    await fsp.mkdir(privateDir, { mode: 0o700 }); privateCreated = true;
    await privateWrite('original-api-state.json', JSON.stringify(originals));
    prefArchive = await adb(['exec-out', 'run-as', PKG, 'tar', '-cf', '-', 'shared_prefs'], { binary: true });
    originalPayloads = preferencePayloads(prefArchive);
    await privateWrite('original-shared-prefs.tar', prefArchive);
    cacheBaseline = await cacheSnapshot('test');
    await privateWrite('original-cache-names.json', JSON.stringify([...cacheBaseline]));
    const fixtureDir = await fsp.realpath(opts['fixtures-dir']);
    const names = (await fsp.readdir(fixtureDir)).filter(name => /\.jpe?g$/i.test(name) && name !== 'small.jpg').sort();
    check(names.length === 6 && names.every(name => /^[A-Za-z0-9_.-]+$/.test(name)), 'six_synthetic_jpegs_required');
    const fixtures = []; let landscape = false, portrait = false;
    for (const name of names) {
      const file = path.join(fixtureDir, name), info = await fsp.lstat(file);
      check(info.isFile() && !info.isSymbolicLink() && info.size >= 8 && info.size <= MAX_JPEG_BYTES,
        'synthetic_fixture_required');
      const bytes = await fsp.readFile(file), dimensions = jpegSize(bytes);
      check(same(dimensions, [6400, 4800]) || same(dimensions, [4800, 6400]), 'fixture_dimensions');
      landscape ||= dimensions[0] === 6400; portrait ||= dimensions[0] === 4800;
      fixtures.push({ bytes, mime: 'image/jpeg', alpha: false });
    }
    check(landscape && portrait, 'both_camera_orientations_required');
    const alphaPath = path.join(fixtureDir, 'alpha.png');
    let alphaInfo;
    try { alphaInfo = await fsp.lstat(alphaPath); }
    catch (error) { check(error.code === 'ENOENT', 'alpha_fixture_read'); }
    if (alphaInfo) {
      check(alphaInfo.isFile() && !alphaInfo.isSymbolicLink() && alphaInfo.size >= 33 &&
        alphaInfo.size <= 32 * 1024 * 1024, 'alpha_fixture_required');
      const bytes = await fsp.readFile(alphaPath);
      check(bytes.subarray(0, 8).equals(PNG_MAGIC) && bytes.readUInt32BE(8) === 13 &&
        bytes.subarray(12, 16).toString('ascii') === 'IHDR' && bytes.readUInt32BE(16) === 3200 &&
        bytes.readUInt32BE(20) === 2400 && bytes[25] === 6, 'alpha_rgba_fixture_dimensions');
      fixtures.push({ bytes, mime: 'image/png', alpha: true });
      report.alphaProvided = true;
    }
    check(same((await api('/remote/sources')).sources, originals.sources.sources) &&
      same((await api('/screensaver')).screensaver, originals.screensaver.screensaver),
      'source_group_changed_before_mutation');
    await startProvider(fixtures);
    const valid = rounds.get(`synthetic-${nonce}-valid`);
    await configureRound(valid);
    const coldUrl = await captureCold(valid, 2400);
    report.coldGuarded = true;
    releaseRound(valid); // Only now may a synthetic camera response reach the owned app.
    const cold = await loadNative(coldUrl);
    check(cold.ok && Math.max(cold.width, cold.height) === 2400 &&
      imageKind(cold.currentUrl) === 'guarded', 'cold_remote_decode_bounded');
    const ready = await evaluate(url => Android.scaledUrlForSize(url, 2400),
      `${valid.baseUrl}/api/assets/${valid.assets[0].id}/thumbnail?size=preview`);
    check(imageKind(ready) === 'derivative', 'large_remote_ready_must_be_derivative');
    report.coldDecoded = report.readyDerivative = true;
    report.coldWidth = cold.width; report.coldHeight = cold.height;

    const sceneDeadline = Date.now() + remaining('test', 20_000);
    let sceneReady = false;
    while (Date.now() < sceneDeadline) {
      sceneReady = await evaluate(() => document.getElementById('loading')?.classList.contains('hide') &&
        [...document.querySelectorAll('#stage *')].some(node => getComputedStyle(node).backgroundImage.startsWith('url(')));
      if (sceneReady) break;
      await delay(100);
    }
    check(sceneReady, 'first_scene_timeout');
    const scene = await evaluate(async () => {
      const urls = [...new Set([...document.querySelectorAll('#stage *')]
        .map(node => getComputedStyle(node).backgroundImage).filter(value => value.startsWith('url('))
        .map(value => value.slice(4, -1).replace(/^["']|["']$/g, '')))];
      const safe = url => /^https:\/\/immortal-remote-photo\.invalid\/\d+\/\d+\/(?:600|1200|1800|2400)$/.test(url) ||
        /^file:\/\/\/data\/(?:user\/0|data)\/com\.immortal\.launcher\.debug\/cache\/ss_scaled\/(?:[a-f0-9]{40}|[a-f0-9]{64})\.jpg$/.test(url);
      if (!urls.length || !urls.every(safe)) return { safe: false, dimensions: [] };
      const dimensions = await Promise.all(urls.map(url => new Promise(resolve => {
        const image = new Image(), timer = setTimeout(() => done(false), 5000);
        let settled = false;
        function done(ok) {
          if (settled) return; settled = true; clearTimeout(timer);
          image.onload = image.onerror = null;
          resolve({ ok, width: image.naturalWidth, height: image.naturalHeight }); image.src = '';
        }
        image.onload = async () => { try { await image.decode(); done(true); } catch (_) { done(false); } };
        image.onerror = () => done(false); image.src = url;
      })));
      return { safe: true, dimensions };
    }, undefined, 6500);
    check(scene.safe && scene.dimensions.length > 0 && scene.dimensions.every(image =>
      image.ok && image.width > 0 && image.height > 0 && Math.max(image.width, image.height) <= 2400),
      'native_css_first_scene_bounded');
    report.firstScene = true; report.sceneImageCount = scene.dimensions.length;
    report.sceneMaxEdge = Math.max(...scene.dimensions.map(image => Math.max(image.width, image.height)));
    await evaluate(() => window.immortalSlideshow.pause());
    if (report.alphaProvided) {
      check(deadline - Date.now() > 15_000, 'alpha_budget_unavailable');
      const sourceUrl = `${valid.baseUrl}/api/assets/alpha/thumbnail?size=preview`;
      const boundedUrl = await evaluate(url => Android.scaledUrlForSize(url, 2400), sourceUrl);
      const decoded = await loadNative(boundedUrl);
      check(decoded.ok && decoded.width === 2400 && decoded.height === 1800, 'alpha_decode_bounded');
      const readyUrl = await evaluate(url => Android.scaledUrlForSize(url, 2400), sourceUrl);
      check(imageKind(readyUrl) === 'derivative', 'alpha_ready_derivative_required');
      const file = /^file:\/\/\/data\/(?:user\/0|data)\/com\.immortal\.launcher\.debug\/(cache\/ss_scaled\/(?:[a-f0-9]{40}|[a-f0-9]{64})\.jpg)$/.exec(readyUrl);
      check(file, 'alpha_exact_cache_path_guard');
      // The .jpg cache filename is historical; actual encoded bytes must be PNG.
      const header = await adb(['exec-out', 'run-as', PKG, 'head', '-c', '26', file[1]], { binary: true });
      check(header.length === 26 && header.subarray(0, 8).equals(PNG_MAGIC) &&
        header.subarray(12, 16).toString('ascii') === 'IHDR' &&
        [4, 6].includes(header[25]), 'alpha_png_encoding_required');
      report.alphaCheckSkipped = false; report.alphaDecoded = report.alphaPngMagic = true;
      report.alphaWidth = decoded.width; report.alphaHeight = decoded.height;
      report.alphaPngColorType = header[25];
    }
    if (deadline - Date.now() > 28_000) await checkFailure(rounds.get(`synthetic-${nonce}-invalid`), 'invalid');
    else report.invalidCheckSkipped = true;
    if (deadline - Date.now() > 28_000) await checkFailure(rounds.get(`synthetic-${nonce}-oversize`), 'oversize');
    else report.oversizeCheckSkipped = true;
    check(counters.metadata > 0 && counters.images > 0 && counters.authenticated > 0 &&
      counters.rejectedAuth === 0 && counters.malformedSearch === 0, 'provider_auth_or_schema_failure');
    report.authRespected = true; report.status = 'passed';
  }

  async function cleanup() {
    let failures = 0;
    const attempt = async (code, work) => {
      try { await work(); } catch (_) { failures++; report[code] = true; }
    };
    // Host server ownership is independent of any subsequent device guard failure.
    if (mutated || launched || reverse || forward) {
      await identity('cleanup'); await apkGuard('cleanup');
    }
    await attempt('cdpCleanupFailed', detachPage);
    if (launched || mutated) await attempt('previewStopFailed',
      () => command(['shell', 'am', 'force-stop', PKG], { cap: 2000 }));
    await attempt('reverseCleanupFailed', removeReverse);
    closeProvider();
    if (mutated) {
      // Reaffirm original scalar policies through the registry, using no stored
      // source URL or key. All source groups are recovered from the private archive.
      await attempt('apiRestoreFailed', async () => {
        await command(['shell', 'am', 'start', '-n', HOME], { cap: 2000 });
        let ready = false;
        const until = Date.now() + remaining('cleanup', 5500);
        while (Date.now() < until) {
          try { await api('/remote/settings/profile', 'GET', undefined, 'cleanup', 750); ready = true; break; }
          catch (_) { await delay(150); }
        }
        check(ready, 'restore_readiness');
        const restore = { source: 'default' };
        for (const key of ['mode', 'shuffle', 'includeVideo', 'intervalSec', 'overnightEnabled', 'darkRoomClock'])
          if (key in originals.screensaver.screensaver) restore[key] = originals.screensaver.screensaver[key];
        await api('/screensaver', 'POST', restore, 'cleanup', 1500);
      });
      await attempt('preferencesRestoreFailed', async () => {
        await command(['shell', 'am', 'force-stop', PKG], { cap: 2000 });
        const restoreDir = `cache/bounded-remote-${nonce}-restore`;
        await command(['shell', '-T', 'run-as', PKG, 'sh', '-c',
          `'mkdir -p ${restoreDir} && tar -xf - -C ${restoreDir} && ` +
          `mv shared_prefs ${restoreDir}/changed-prefs && mv ${restoreDir}/shared_prefs shared_prefs'`],
        { input: prefArchive, cap: 3000 });
        const restored = await command(['exec-out', 'run-as', PKG, 'tar', '-cf', '-', 'shared_prefs'],
          { binary: true, cap: 2500 });
        check(same(preferencePayloads(restored), originalPayloads), 'exact_preferences_restore');
        report.preferencePayloadsExact = true;
        await command(['shell', 'run-as', PKG, 'rm', '-rf', restoreDir], { cap: 1500 });
      });
    }
    if (cacheBaseline && mutated) await attempt('cacheCleanupFailed', async () => {
      // Only cache additions during this exclusively owned native invocation.
      // Existing entries are never removed, even if a ready URL names one.
      const current = await cacheSnapshot('cleanup');
      const additions = [...current].filter(name => !cacheBaseline.has(name));
      const filePattern = /^cache\/(?:ss_scaled\/(?:[a-f0-9]{40}|[a-f0-9]{64})\.jpg(?:\.part)?|ss_remote\/[A-Za-z0-9_-]+\/(?:[a-f0-9]{40}|[a-f0-9]{64})(?:\.source(?:\.[a-f0-9-]{36})?\.part|\.source|\.part))$/;
      const directoryPattern = /^cache\/(?:ss_scaled|ss_remote(?:\/[A-Za-z0-9_-]+)?)$/;
      check(additions.length <= 256 && additions.every(name => filePattern.test(name) || directoryPattern.test(name)),
        'owned_cache_additions_guard');
      const files = additions.filter(name => filePattern.test(name));
      if (files.length) await command(['shell', 'run-as', PKG, 'rm', '-f', ...files], { cap: 2000 });
      const directories = additions.filter(name => directoryPattern.test(name)).sort((a, b) => b.length - a.length);
      for (const directory of directories)
        await command(['shell', 'run-as', PKG, 'rmdir', directory], { cap: 1000 });
      report.syntheticCacheFilesRemoved = files.length;
    });
    if (mutated) await attempt('profileReadbackFailed', async () => {
      await command(['shell', 'am', 'start', '-n', HOME], { cap: 2000 });
      let restored;
      const until = Date.now() + remaining('cleanup', 6500);
      while (Date.now() < until) {
        try {
          restored = {
            screensaver: await api('/screensaver', 'GET', undefined, 'cleanup', 750),
            sources: await api('/remote/sources', 'GET', undefined, 'cleanup', 750),
            profile: await api('/remote/settings/profile', 'GET', undefined, 'cleanup', 750),
          };
          break;
        } catch (_) { await delay(150); }
      }
      check(restored && same(restored.screensaver.screensaver, originals.screensaver.screensaver) &&
        same(restored.sources.sources, originals.sources.sources) &&
        same(restored.sources.calendar, originals.sources.calendar) &&
        same(restored.profile.settings, originals.profile.settings), 'full_profile_restoration');
      report.fullProfileExact = true; report.allSourceGroupsExact = true;
    });
    report.restored = failures === 0;
    report.cleanupFailureCount = failures;
    if (failures) report.status = 'failed';
  }

  let timer, rejectSignal;
  const onSignal = () => {
    if (phase !== 'test') return;
    testAbort.abort(); stopChildren(); rejectSignal?.(new CheckError('interrupted'));
  };
  process.on('SIGINT', onSignal); process.on('SIGTERM', onSignal);
  try {
    await Promise.race([test(), new Promise((_, reject) => { rejectSignal = reject; }),
      new Promise((_, reject) => {
        timer = setTimeout(() => {
          testAbort.abort(); stopChildren(); reject(new CheckError('test_deadline'));
        }, TEST_MS);
      })]);
  } catch (error) {
    report.failure = error instanceof CheckError ? error.code : 'harness_internal_failure';
    report.status = report.failure === 'cdp_unsupported' ? 'unsupported' : 'failed';
  } finally {
    clearTimeout(timer); testAbort.abort(); stopChildren();
    phase = 'cleanup'; deadline = Date.now() + CLEANUP_MS;
    try { await bounded(cleanup(), CLEANUP_MS, 'cleanup_deadline'); }
    catch (error) {
      stopChildren(); report.status = 'failed'; report.restored = false;
      report.cleanupFailure = error instanceof CheckError ? error.code : 'cleanup_internal_failure';
    } finally {
      closeProvider(); // Always close our local listener/sockets, even if device identity changed.
      if (session) await session.detach().catch(() => {});
      phase = 'done';
      process.off('SIGINT', onSignal); process.off('SIGTERM', onSignal);
    }
  }
  report.privateRecoveryRetained = privateCreated && !!prefArchive;
  report.authenticatedRequestCount = counters.authenticated;
  report.rejectedAuthRequestCount = counters.rejectedAuth;
  report.metadataRequestCount = counters.metadata;
  report.imageRequestCount = counters.images;
  report.invalidImageRequestCount = counters.invalidImages;
  report.oversizeImageRequestCount = counters.oversizeImages;
  report.syntheticBytesSent = counters.sentBytes;
  report.elapsedMs = Date.now() - started;
  // Reports are constructed here from fixed fields and primitive values only.
  check(Object.values(report).every(value => ['string', 'number', 'boolean'].includes(typeof value)),
    'scalar_report_required');
  process.stdout.write(`${JSON.stringify(report)}\n`);
  process.exit(report.status === 'passed' ? 0 : report.status === 'unsupported' ? 2 : 1);
}
if (require.main === module) main().catch(() => {
  process.stdout.write('{"status":"failed","failure":"harness_initialization","native":true}\n');
  process.exit(1);
});
