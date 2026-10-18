/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { browserHarness } from './remote-browser-harness.mjs';
const saved = [
  { base: 'http://127.0.0.1:8877', name: 'Preview A', token: 'paired-fixture-a' },
  { base: 'http://127.0.0.1:8878', name: 'Preview B', token: 'paired-fixture-b' },
];

test('opening Home fetches only status, media and roster; hidden pages stop reads', async () => {
  const h = await browserHarness({ saved });
  assert.deepEqual([...new Set(h.calls.map((c) => c.path))].sort(), [
    '/remote/companion',
    '/remote/nowplaying',
    '/remote/roster',
  ]);
  h.hide();
  h.eval('loadVisible();status();mediaRead();');
  await h.settle();
  assert.equal(h.calls.length, 3);
  assert.equal(h.document.body.textContent.includes('paired-fixture-a'), false);
});

test('changing settings target scope does not load photos, apps, catalog or other management pages', async () => {
  const h = await browserHarness({ saved });
  h.eval('go("settings")');
  await h.settle();
  h.eval('scope="all";loadScopeSchemas()');
  await h.settle();
  assert.equal(
    h.calls.filter((c) =>
      [
        '/remote/photos',
        '/remote/apps',
        '/remote/catalog',
        '/remote/layout',
        '/remote/jarvis',
        '/remote/scenes',
      ].includes(c.path),
    ).length,
    0,
  );
});

test('editing an app folder enables Save layout without losing typed draft', async () => {
  const h = await browserHarness({ saved });
  h.eval('go("home","layout")');
  await h.settle();
  assert.equal(
    h.document.body.querySelectorAll('button').find((b) => b.textContent === 'Save layout')
      .disabled,
    true,
  );
  await h.input('com.test.app', 'Bedroom');
  assert.equal(
    h.document.body.querySelectorAll('button').find((b) => b.textContent === 'Save layout')
      .disabled,
    false,
  );
  assert.equal(h.eval('layoutDraft.assignments["com.test.app"]'), 'Bedroom');
});

test('a lost transport marks the room offline and offers Retry rather than staying Connecting', async () => {
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/companion': () => {
        throw new Error('offline');
      },
      '/remote/nowplaying': () => {
        throw new Error('offline');
      },
    },
  });
  assert.equal(h.eval('connectionState'), 'offline');
  assert.match(h.document.getElementById('connection').textContent, /Offline/);
});

test('expired pairing exposes pair-again UI and does not poll automatically', async () => {
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/companion': ({ response }) => response({ ok: false, error: 'unauthorized' }, 401),
    },
  });
  assert.equal(h.eval('connectionState'), 'expired');
  assert.match(h.document.getElementById('page').textContent, /Pair again/);
  assert.equal(h.eval('pollTimer'), null);
});

test('capability-disabled workflows do not call management APIs when reached directly', async () => {
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/companion': ({ response }) =>
        response({
          ok: true,
          name: 'Preview',
          capabilities: {
            layout: false,
            scenes: false,
            photos: false,
            photoLibrary: false,
            jarvis: false,
            snapshot: false,
            catalog: false,
            operations: false,
          },
        }),
    },
  });
  h.eval('go("home","layout")');
  await h.settle();
  assert.equal(
    h.calls.some((c) => c.path === '/remote/layout'),
    false,
  );
  assert.match(h.document.getElementById('page').textContent, /unavailable/i);
});

test('opening a late photo listing in another room cannot replace the active gallery', async () => {
  let release;
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/photos': ({ url, response }) =>
        url.includes('8877')
          ? new Promise((resolve) => {
              release = () =>
                resolve(
                  response({
                    ok: true,
                    path: '/sdcard',
                    recursive: true,
                    files: [{ path: '/sdcard/a.jpg', name: 'Old room' }],
                  }),
                );
            })
          : response({ ok: true, path: '/sdcard', recursive: true, files: [] }),
    },
  });
  h.eval('go("photos")');
  await h.settle();
  h.eval(`selectRoom('${saved[1].base}')`);
  await h.settle();
  release();
  await h.settle();
  assert.equal(h.document.body.textContent.includes('Old room'), false);
});

test('a late mutation response never overwrites another room’s source or preset state', async () => {
  let release;
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/presets': ({ opts, response }) =>
        opts.method === 'POST'
          ? new Promise((resolve) => {
              release = () =>
                resolve(
                  response({
                    ok: true,
                    presets: [{ id: 'old-room', name: 'Old room only', steps: [] }],
                  }),
                );
            })
          : response({ ok: true, presets: [] }),
    },
  });
  h.eval('go("home","presets")');
  await h.settle();
  h.eval('savePresets([{id:"new",name:"Saved",steps:[]}])');
  await h.settle();
  h.eval(`selectRoom('${saved[1].base}')`);
  await h.settle();
  release();
  await h.settle();
  assert.equal(h.eval('JSON.stringify(data.presets || [])').includes('old-room'), false);
});

test('folder source validation fails visibly without sending an invalid path', async () => {
  const h = await browserHarness({ saved });
  h.eval(
    'data.sources={source:"folder",folderPath:"/sdcard/Pictures"};setDraft("source:folderPath","/sdcard/../secret")',
  );
  await assert.doesNotReject(h.eval('saveSource("folder",data.sources)'));
  assert.equal(
    h.calls.some((c) => c.path === '/remote/sources' && c.opts.method === 'POST'),
    false,
  );
  assert.match(h.document.getElementById('notice').textContent, /public folder/i);
});

test('group setting partial rejection remains visible and renaming never broadcasts', async () => {
  const schema = {
    domains: [
      {
        id: 'screensaver',
        title: 'Slideshow',
        controls: [{ key: 'shuffle', title: 'Shuffle', type: 'bool', value: false }],
      },
      {
        id: 'fleet',
        title: 'Device',
        controls: [{ key: 'name', title: 'Name', type: 'string', value: 'Preview A' }],
      },
    ],
  };
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/settings': ({ url, opts, response }) => {
        if (opts.method !== 'POST') return response({ ok: true, settings: schema });
        const request = JSON.parse(opts.body);
        return response({
          ok: true,
          applied: url.includes('8878') ? [] : Object.keys(request.values),
          domain: {
            id: request.domain,
            controls: Object.entries(request.values).map(([key, value]) => ({
              key,
              value,
              type: typeof value === 'boolean' ? 'bool' : 'string',
            })),
          },
        });
      },
    },
  });
  h.eval('go("settings")');
  await h.settle();
  h.eval('scope="all";loadScopeSchemas()');
  await h.settle();
  await h.eval('applyDomain("screensaver",{shuffle:true})');
  await h.settle();
  assert.match(h.document.getElementById('page').textContent, /Results by room/);
  assert.match(h.document.getElementById('page').textContent, /Rejected: shuffle/);
  await h.eval('applyDomain("fleet",{name:"Study"})');
  await h.settle();
  const renameCalls = h.calls.filter(
    (c) =>
      c.opts.method === 'POST' &&
      c.path === '/remote/settings' &&
      JSON.parse(c.opts.body).domain === 'fleet',
  );
  assert.equal(renameCalls.length, 1);
  assert.match(renameCalls[0].url, /8877/);
});

test('weekday and time edits accumulate without reverting prior schedule choices', async () => {
  const h = await browserHarness({ saved });
  h.eval('go("home","scenes");openScene(null)');
  await h.settle();
  for (const day of ['Mon', 'Tue']) {
    h.eval(
      `{const control=document.body.querySelector('[aria-label="${day}"]');control.checked=false;for(const handler of control.listeners.change||[])handler({target:control});}`,
    );
  }
  await h.input('Time on Portal', '09:30');
  assert.deepEqual(JSON.parse(h.eval('JSON.stringify(sceneDraft.schedule.days)')), [3, 4, 5]);
  assert.equal(h.eval('sceneDraft.schedule.hour'), 9);
  assert.equal(h.eval('sceneDraft.schedule.minute'), 30);
});

test('activating a stored folder preserves its exact whitespace identity', async () => {
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/sources': ({ opts, response }) => {
        const body = JSON.parse(opts.body);
        return response({
          ok: true,
          applied: ['folderPath'],
          sources: { source: 'folder', folderPath: body.folderPath },
        });
      },
    },
  });
  h.eval('data.sources={source:"folder",folderPath:"/sdcard/Pictures/Family "}');
  await h.eval('saveSource("folder",data.sources)');
  await h.settle();
  const posted = h.calls.find((c) => c.path === '/remote/sources' && c.opts.method === 'POST');
  assert.equal(JSON.parse(posted.opts.body).folderPath, '/sdcard/Pictures/Family ');
});

test('snapshot restore confirmation is dismissed by room switching and cannot post to the new room', async () => {
  const current = {
    schemaVersion: 1,
    deviceId: 'fixture-device',
    revision: 'reviewed-revision',
    domains: {},
    layout: { assignments: {}, homeOrder: [], gridOrder: [], gridSlots: [], widgets: [] },
  };
  const h = await browserHarness({
    saved,
    routes: { '/remote/snapshot': ({ response }) => response({ ok: true, snapshot: current }) },
  });
  h.eval('go("home","snapshots")');
  await h.settle();
  const input = h.document.body
    .querySelectorAll('input')
    .find((n) => n.attrs['aria-label'] === 'Choose snapshot JSON');
  const file = new Blob([JSON.stringify(current)], { type: 'application/json' });
  file.name = 'fixture-snapshot.json';
  input.files = [file];
  for (const listener of input.listeners.change || []) listener({ target: input });
  h.click('Review restore');
  await h.settle();
  assert.equal(h.document.getElementById('sheet').open, true);
  h.eval(`selectRoom('${saved[1].base}')`);
  await h.settle();
  assert.equal(h.document.getElementById('sheet').open, false);
  assert.equal(
    h.calls.some((c) => c.path === '/remote/snapshot' && c.opts.method === 'POST'),
    false,
  );
});

test('an in-flight source write captures the original room and drops its result after selection changes', async () => {
  let release;
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/sources': ({ opts, response }) =>
        opts.method === 'POST'
          ? new Promise((resolve) => {
              release = () => resolve(response({ ok: true, sources: { source: 'default' } }));
            })
          : response({ ok: true, sources: { source: 'default' } }),
    },
  });
  h.eval('go("settings","sources")');
  await h.settle();
  h.eval('saveSource("default",data.sources)');
  await h.settle();
  const sourcePost = h.calls.find((c) => c.path === '/remote/sources' && c.opts.method === 'POST');
  assert.match(sourcePost.url, /8877/);
  h.eval(`selectRoom('${saved[1].base}')`);
  await h.settle();
  release();
  await h.settle();
  assert.equal(h.eval('data.sources'), undefined);
  assert.equal(
    h.calls.filter((c) => c.path === '/remote/sources' && c.opts.method === 'POST').length,
    1,
  );
});

test('phone sheet keeps confirm actions outside its scrollable content', async () => {
  const h = await browserHarness({ saved });
  h.eval('pairDialog()');
  await h.settle();
  assert.equal(
    !!h.document.getElementById('sheet-content').querySelector('button.button.primary'),
    false,
  );
  assert.equal(
    h.document.getElementById('sheet-actions').querySelector('button.button.primary').textContent,
    'Pair Portal',
  );
});

test('phone media transport uses compact accessible icon buttons', async () => {
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/nowplaying': ({ response }) =>
        response({
          ok: true,
          np: {
            active: true,
            title: 'Synthetic track',
            playing: true,
            volume: { level: 4, max: 15, canSet: true },
          },
        }),
    },
  });
  const transport = h.document.getElementById('page').querySelector('.media-controls');
  assert.equal(transport.children.length, 3);
  for (const button of transport.children) {
    assert.match(button.className, /icon-button/);
    assert.ok(button.attrs['aria-label']);
    assert.equal(button.textContent, '');
  }
});

test('confirmed installer result refreshes only the visible app list', async () => {
  let state = 'running';
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/operations': ({ response }) =>
        response({
          ok: true,
          operations: [
            {
              id: 'fixture-op',
              type: 'install',
              target: { packageName: 'com.test.app' },
              state,
              results: state === 'succeeded' ? [{ ok: true, result: 'installed' }] : [],
            },
          ],
        }),
    },
  });
  h.eval('appMode="catalog";go("apps")');
  await h.settle();
  const before = h.calls.filter((c) => c.path === '/remote/catalog').length;
  state = 'succeeded';
  await h.tick(4000);
  assert.equal(h.calls.filter((c) => c.path === '/remote/catalog').length, before + 1);
  assert.equal(
    h.calls.filter((c) => c.path === '/remote/photos' || c.path === '/remote/settings').length,
    0,
  );
});

test('phone Settings puts search, category and registry before room options and management', async () => {
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/settings': ({ response }) =>
        response({
          ok: true,
          settings: {
            domains: [
              {
                id: 'immortal',
                title: 'Immortal',
                controls: [
                  {
                    key: 'clock',
                    title: 'Clock format',
                    type: 'bool',
                    value: true,
                    tier: 'basic',
                  },
                ],
              },
            ],
          },
        }),
    },
  });
  h.eval('go("settings")');
  await h.settle();
  const page = h.document.getElementById('page');
  const labels = page.textContent;
  assert.ok(labels.indexOf('Search settings') < labels.indexOf('Category'));
  assert.ok(labels.indexOf('Category') < labels.indexOf('Clock format'));
  assert.ok(labels.indexOf('Clock format') < labels.indexOf('Photo sources'));
  const options = page.querySelector('details.settings-options');
  assert.ok(options, 'management belongs in a closed disclosure');
  assert.equal(options.open, false);
  assert.equal(labels.includes('Controls, visibility and limits'), false);
});

test('discovered folder choices survive opening a shallow folder and retain exact identities', async () => {
  const folder = '/sdcard/Pictures/Family ';
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/photos': ({ url, response }) =>
        new URL(url).searchParams.has('recursive')
          ? response({
              ok: true,
              path: '/sdcard',
              recursive: true,
              files: [],
              folders: [{ path: folder, count: 2 }],
            })
          : response({ ok: true, path: folder, files: [], folders: [] }),
    },
  });
  h.eval('go("photos")');
  await h.settle();
  await h.click('Folders');
  await h.settle();
  const picker = h.document
    .getElementById('page')
    .querySelector('[aria-label="Choose a photo folder"]');
  assert.ok(picker);
  assert.equal(picker.value, folder);
});

test('older photo capability uses an explicit shallow folder view rather than claiming all photos', async () => {
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/companion': ({ response }) =>
        response({
          ok: true,
          name: 'Preview',
          capabilities: { photos: true, photoLibrary: false },
        }),
      '/remote/photos': ({ response }) =>
        response({ ok: true, path: '/sdcard', files: [], folders: [] }),
    },
  });
  h.eval('go("photos")');
  await h.settle();
  assert.equal(h.eval('photosAll'), false);
  assert.ok(h.document.body.querySelector('[data-focus="photo-folder"]'));
});

const guardedSchema = {
  domains: [
    {
      id: 'mqtt',
      title: 'MQTT',
      controls: [
        { key: 'enabled', title: 'Publish to MQTT', type: 'bool', value: true, default: false },
        { key: 'host', title: 'Broker host', type: 'string', value: 'broker-a', default: '' },
        { key: 'username', title: 'Broker user', type: 'string', value: 'user-a' },
        { key: 'password', title: 'Broker password', type: 'string', secret: true, value: '' },
      ],
    },
    {
      id: 'immortal',
      title: 'Immortal',
      controls: [
        { key: 'shuffle', title: 'Shuffle', type: 'bool', value: false },
        {
          key: 'maPassword',
          title: 'Music Assistant password',
          type: 'string',
          secret: true,
          value: '',
        },
      ],
    },
    {
      id: 'fleet',
      title: 'Device',
      controls: [{ key: 'name', title: 'Name', type: 'string', value: 'Preview A' }],
    },
  ],
};
const guardedSettings = ({ opts, response }) => {
  if (opts.method !== 'POST') return response({ ok: true, settings: guardedSchema });
  const { domain, values } = JSON.parse(opts.body);
  return response({
    ok: true,
    applied: Object.keys(values),
    domain: {
      ...guardedSchema.domains.find((d) => d.id === domain),
      controls: guardedSchema.domains
        .find((d) => d.id === domain)
        .controls.map((c) => ({
          ...c,
          value: Object.hasOwn(values, c.key) ? values[c.key] : c.value,
        })),
    },
  });
};
const settingsPosts = (h) =>
  h.calls.filter((c) => c.path === '/remote/settings' && c.opts.method === 'POST');

async function allSettings(h) {
  h.eval('go("settings");scope="all"');
  await h.settle();
  h.eval('loadScopeSchemas()');
  await h.settle();
}

test('credential and connection transport patches use only captured active Portal, ordinary values still fan out', async () => {
  const h = await browserHarness({ saved, routes: { '/remote/settings': guardedSettings } });
  await allSettings(h);
  await h.eval(
    'applyDomain("mqtt",{enabled:false,host:"broker-new",username:"new-user",password:"typed-private"},settingTargets())',
  );
  await h.eval(
    'applyDomain("immortal",{shuffle:true,maPassword:"typed-private"},settingTargets())',
  );
  const posts = settingsPosts(h);
  assert.equal(posts.length, 4);
  for (const post of posts.filter((c) => c.url.includes('8878'))) {
    assert.deepEqual(Object.keys(JSON.parse(post.opts.body).values), [
      post.opts.body.includes('mqtt') ? 'enabled' : 'shuffle',
    ]);
  }
  assert.match(h.document.getElementById('page').textContent, /Active Portal only/);
});

test('an active-only edit targets active Portal even when selected scope excludes it', async () => {
  const h = await browserHarness({ saved, routes: { '/remote/settings': guardedSettings } });
  await allSettings(h);
  h.eval(`scope="selected";selectedTargets=new Set(['${saved[1].base}'])`);
  await h.eval('applyDomain("mqtt",{password:"typed-private"},settingTargets())');
  const posts = settingsPosts(h);
  assert.equal(posts.length, 1);
  assert.match(posts[0].url, /8877/);
});

test('reset partitions connection defaults at transport while preserving ordinary group resets', async () => {
  const h = await browserHarness({ saved, routes: { '/remote/settings': guardedSettings } });
  await allSettings(h);
  h.eval('category="mqtt";render()');
  h.click('Reset');
  h.document
    .getElementById('sheet-actions')
    .querySelectorAll('button')
    .find((b) => b.textContent === 'Reset')
    .click();
  await h.settle();
  const posts = settingsPosts(h);
  assert.equal(posts.length, 2);
  assert.deepEqual(JSON.parse(posts.find((c) => c.url.includes('8878')).opts.body).values, {
    enabled: false,
  });
  assert.deepEqual(JSON.parse(posts.find((c) => c.url.includes('8877')).opts.body).values, {
    enabled: false,
    host: '',
  });
});

test('legacy profile apply cannot broadcast connection/name values or stored credentials', async () => {
  const h = await browserHarness({ saved, routes: { '/remote/settings': guardedSettings } });
  await allSettings(h);
  await h.eval(
    'applyProfile("legacy",{mqtt:{enabled:false,host:"broker-new",password:"old-secret"},immortal:{shuffle:true,maPassword:"old-secret"},fleet:{name:"Study"}},settingTargets(),session.capture())',
  );
  const posts = settingsPosts(h);
  assert.equal(posts.length, 5);
  for (const post of posts) {
    assert.equal(post.opts.body.includes('old-secret'), false);
    if (post.url.includes('8878')) assert.equal(/host|name|assword/.test(post.opts.body), false);
  }
});

const startTwoPhotos = (h) =>
  h.eval(`{
  const files=['first.png','second.png'].map(name=>Object.assign(new Blob(['abc'],{type:'image/png'}),{name}));
  globalThis.importResult=startUpload(files,'/sdcard/Pictures',session.capture());
}`);

test('photo imports continue in captured room across tab navigation and finish with a usable ledger', async () => {
  let release;
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/photo-upload': ({ url, opts, response }) =>
        new URL(url).searchParams.get('name') === 'first.png'
          ? new Promise((resolve) => {
              release = () =>
                resolve(
                  response({ ok: true, path: '/sdcard/Pictures/first.png', bytes: opts.body.size }),
                );
            })
          : response({ ok: true, path: '/sdcard/Pictures/second.png', bytes: opts.body.size }),
    },
  });
  h.eval('go("photos")');
  await h.settle();
  startTwoPhotos(h);
  await h.settle();
  h.eval('go("home")');
  release();
  await h.settle();
  assert.equal(h.calls.filter((c) => c.path === '/remote/photo-upload').length, 2);
  assert.equal(h.eval('upload.running'), false);
  assert.equal(h.eval('upload.completed'), 2);
  assert.equal(h.eval('upload.current'), '');
  h.eval('go("photos")');
  await h.settle();
  assert.equal(
    h.document.body.querySelectorAll('button').find((b) => b.textContent === 'Import photos')
      .disabled,
    false,
  );
  assert.match(h.document.getElementById('upload-status').textContent, /2 of 2/);
});

test('room switching cancels queued imports and retains an honest in-flight ledger on return', async () => {
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/photo-upload': ({ opts }) =>
        new Promise((resolve, reject) => {
          opts.signal.addEventListener('abort', () =>
            reject(new DOMException('Aborted', 'AbortError')),
          );
        }),
    },
  });
  h.eval('go("photos")');
  await h.settle();
  const capturedName = h.eval('room().name');
  startTwoPhotos(h);
  await h.settle();
  h.eval(`selectRoom('${saved[1].base}')`);
  await h.settle();
  assert.equal(h.calls.filter((c) => c.path === '/remote/photo-upload').length, 1);
  h.eval(`selectRoom('${saved[0].base}')`);
  await h.settle();
  assert.equal(h.eval('upload?.running'), false);
  assert.equal(h.eval('upload?.cancelled'), true);
  assert.equal(h.eval('upload?.completed'), 0);
  assert.equal(h.eval('upload?.items[0].state'), 'unconfirmed');
  assert.equal(h.eval('upload?.items[1].state'), 'cancelled');
  assert.ok(h.document.getElementById('upload-status').textContent.includes(capturedName));
  assert.match(
    h.document.getElementById('upload-status').textContent,
    /unconfirmed|check whether/i,
  );
});

test('completion after delayed gallery refresh cannot toast against a newly selected room', async () => {
  let uploaded = false,
    release;
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/photo-upload': ({ opts, response }) => {
        uploaded = true;
        return response({ ok: true, path: '/sdcard/Pictures/first.png', bytes: opts.body.size });
      },
      '/remote/photos': ({ url, response }) =>
        uploaded && url.includes('8877')
          ? new Promise((resolve) => {
              release = () =>
                resolve(
                  response({ ok: true, path: '/sdcard', recursive: true, files: [], folders: [] }),
                );
            })
          : response({ ok: true, path: '/sdcard', recursive: true, files: [], folders: [] }),
    },
  });
  h.eval('go("photos")');
  await h.settle();
  h.eval(
    `{ const file=Object.assign(new Blob(['abc'],{type:'image/png'}),{name:'first.png'});startUpload([file],'/sdcard/Pictures',session.capture()); }`,
  );
  await h.settle();
  assert.ok(release);
  h.eval(`selectRoom('${saved[1].base}');document.getElementById('notice').textContent=''`);
  await h.settle();
  release();
  await h.settle();
  assert.equal(h.document.getElementById('notice').textContent.includes('stored'), false);
  assert.equal(h.document.getElementById('notice').textContent.includes('Preview A'), false);
});

const sceneV1 = 'a'.repeat(64),
  sceneV2 = 'b'.repeat(64),
  sceneV3 = 'c'.repeat(64);
const scheduledScene = (hour) => ({
  id: 'routine',
  name: 'Morning',
  steps: [{ type: 'key', action: 'home' }],
  schedule: { enabled: true, hour, minute: 0, days: [1, 2, 3, 4, 5] },
});

test('stale scene editor cannot replace a concurrently changed schedule; refresh never rebases its revision', async () => {
  let revision = sceneV1,
    scenes = [scheduledScene(8)];
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/scenes': ({ opts, response }) => {
        if (opts.method !== 'POST') return response({ ok: true, scenes, revision });
        const patch = JSON.parse(opts.body);
        if (patch.expectedRevision !== revision)
          return response({ ok: false, error: 'stale_revision' }, 409);
        scenes = patch.scenes;
        revision = sceneV3;
        return response({ ok: true, scenes, revision });
      },
    },
  });
  h.eval('go("home","scenes")');
  await h.settle();
  h.eval('openScene(data.scenes[0])');
  await h.input('Scene name', 'My morning');
  await h.input('Time on Portal', '09:30');
  // Independent writer changes the server while the original editor remains open.
  revision = sceneV2;
  scenes = [scheduledScene(11)];
  h.eval('loadVisible(true)');
  await h.settle();
  assert.equal(h.eval('data.scenesRevision'), sceneV2);
  assert.equal(h.eval('sceneDraft.expectedRevision'), sceneV1);
  h.click('Save scene');
  await h.settle();
  const post = h.calls.find((c) => c.path === '/remote/scenes' && c.opts.method === 'POST');
  assert.equal(JSON.parse(post.opts.body).expectedRevision, sceneV1);
  assert.equal(scenes[0].schedule.hour, 11);
  assert.equal(h.eval('sceneDraft.name'), 'My morning');
  assert.equal(h.eval('sceneDraft.schedule.hour'), 9);
  assert.equal(h.eval('sceneDraft.schedule.minute'), 30);
  assert.match(h.document.getElementById('page').textContent, /conflict|changed/i);
  h.click('Refresh to review');
  await h.settle();
  assert.equal(h.eval('sceneDraft.expectedRevision'), sceneV1);
  assert.equal(h.eval('sceneDraft.name'), 'My morning');
  assert.equal(
    h.calls.filter((c) => c.path === '/remote/scenes' && c.opts.method === 'POST').length,
    1,
  );
});

test('scene save and schedule changes send captured revision and accept only confirmed updated list', async () => {
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/scenes': ({ opts, response }) =>
        opts.method === 'POST'
          ? response({ ok: true, scenes: JSON.parse(opts.body).scenes, revision: sceneV2 })
          : response({ ok: true, scenes: [scheduledScene(8)], revision: sceneV1 }),
    },
  });
  h.eval('go("home","scenes")');
  await h.settle();
  h.eval('openScene(data.scenes[0])');
  await h.input('Time on Portal', '09:30');
  h.click('Save scene');
  await h.settle();
  const post = h.calls.find((c) => c.path === '/remote/scenes' && c.opts.method === 'POST');
  assert.equal(JSON.parse(post.opts.body).expectedRevision, sceneV1);
  assert.equal(JSON.parse(post.opts.body).scenes[0].schedule.hour, 9);
  assert.equal(h.eval('sceneDraft'), null);
  assert.equal(h.eval('data.scenesRevision'), sceneV2);
});

test('scene delete confirmation retains its reviewed list/revision through a concurrent refresh', async () => {
  let revision = sceneV1;
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/scenes': ({ opts, response }) =>
        opts.method === 'POST'
          ? response({ ok: false, error: 'stale_revision' }, 409)
          : response({
              ok: true,
              scenes: [scheduledScene(revision === sceneV1 ? 8 : 11)],
              revision,
            }),
    },
  });
  h.eval('go("home","scenes")');
  await h.settle();
  h.click('Delete');
  revision = sceneV2;
  h.eval('loadVisible(true)');
  await h.settle();
  h.document
    .getElementById('sheet-actions')
    .querySelectorAll('button')
    .find((b) => b.textContent === 'Delete')
    .click();
  await h.settle();
  const post = h.calls.find((c) => c.path === '/remote/scenes' && c.opts.method === 'POST');
  assert.equal(JSON.parse(post.opts.body).expectedRevision, sceneV1);
  assert.deepEqual(JSON.parse(post.opts.body).scenes, []);
  assert.equal(h.eval('data.scenes[0].schedule.hour'), 11);
  assert.match(h.document.getElementById('page').textContent, /conflict|changed/i);
});

test('old scene server without revision keeps editor reachable but never writes an unguarded list', async () => {
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/scenes': ({ response }) => response({ ok: true, scenes: [scheduledScene(8)] }),
    },
  });
  h.eval('go("home","scenes")');
  await h.settle();
  h.eval('openScene(data.scenes[0]);saveSceneDraft()');
  await h.settle();
  assert.equal(
    h.calls.some((c) => c.path === '/remote/scenes' && c.opts.method === 'POST'),
    false,
  );
  assert.ok(h.eval('sceneDraft'));
  assert.match(
    h.document.getElementById('page').textContent,
    /conflict protection.*unavailable|update Immortal/i,
  );
});

test('transport preflight rejects peer-only secret metadata before any credential write to that peer', async () => {
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/settings': ({ url, opts, response }) => {
        if (opts.method !== 'POST')
          return response({
            ok: true,
            settings: {
              domains: [
                {
                  id: 'custom',
                  title: 'Custom',
                  controls: [
                    {
                      key: 'value',
                      title: 'Value',
                      type: 'string',
                      value: '',
                      secret: url.includes('8878'),
                    },
                  ],
                },
              ],
            },
          });
        return response({
          ok: true,
          applied: ['value'],
          domain: {
            id: 'custom',
            title: 'Custom',
            controls: [
              {
                key: 'value',
                title: 'Value',
                type: 'string',
                value: JSON.parse(opts.body).values.value,
              },
            ],
          },
        });
      },
    },
  });
  await allSettings(h);
  await h.eval('applyDomain("custom",{value:"typed-private"},settingTargets())');
  const posts = settingsPosts(h);
  assert.equal(posts.length, 1);
  assert.match(posts[0].url, /8877/);
  assert.equal(h.eval('scopeResults.find(r=>r.base.includes("8878")).error'), 'active_portal_only');
});

test('a late confirmed in-flight upload counts honestly after cancellation without sending queued files', async () => {
  let release;
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/photo-upload': ({ opts, response }) =>
        new Promise((resolve) => {
          // Model a response already in flight that wins the cancellation race.
          release = () =>
            resolve(
              response({ ok: true, path: '/sdcard/Pictures/first.png', bytes: opts.body.size }),
            );
        }),
    },
  });
  h.eval('go("photos")');
  await h.settle();
  startTwoPhotos(h);
  await h.settle();
  h.eval(`selectRoom('${saved[1].base}')`);
  await h.settle();
  release();
  await h.settle();
  h.eval(`selectRoom('${saved[0].base}')`);
  await h.settle();
  assert.equal(h.calls.filter((c) => c.path === '/remote/photo-upload').length, 1);
  assert.equal(h.eval('upload.running'), false);
  assert.equal(h.eval('upload.completed'), 1);
  assert.equal(h.eval('upload.items[0].state'), 'confirmed');
  assert.equal(h.eval('upload.items[1].state'), 'cancelled');
  assert.match(h.document.getElementById('upload-status').textContent, /Stored and confirmed/);
  assert.match(h.document.getElementById('upload-status').textContent, /second.png: Not sent/);
});

test('clicking switch label padding forwards one native toggle and one settings write', async () => {
  let value = false;
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/settings': ({ opts, response }) => {
        const control = { key: 'shuffle', title: 'Shuffle', type: 'bool', value };
        if (opts.method !== 'POST')
          return response({
            ok: true,
            settings: { domains: [{ id: 'screensaver', title: 'Photos', controls: [control] }] },
          });
        value = JSON.parse(opts.body).values.shuffle;
        return response({
          ok: true,
          applied: ['shuffle'],
          domain: { id: 'screensaver', title: 'Photos', controls: [{ ...control, value }] },
        });
      },
    },
  });
  h.eval('go("settings")');
  await h.settle();
  const label = h.document.getElementById('page').querySelector('label.switch-hit');
  assert.ok(label, 'the visual track must be inside a real label hit target');
  const input = label.querySelector('input.switch');
  let changes = 0;
  input.addEventListener('change', () => changes++);
  // Target the label itself, representing padding outside the 30px input track.
  label.click();
  await h.settle();
  assert.equal(changes, 1);
  assert.equal(input.checked, true);
  assert.equal(settingsPosts(h).length, 1);
  assert.equal(JSON.parse(settingsPosts(h)[0].opts.body).values.shuffle, true);
  const current = h.document
    .getElementById('page')
    .querySelector('label.switch-hit')
    .querySelector('input.switch');
  let directChanges = 0;
  current.addEventListener('change', () => directChanges++);
  current.click();
  await h.settle();
  assert.equal(directChanges, 1, 'direct track click must not be re-forwarded by the label');
  assert.equal(settingsPosts(h).length, 2);
  assert.equal(JSON.parse(settingsPosts(h)[1].opts.body).values.shuffle, false);
});

test('disabled switch ignores label-padding and track clicks without changing state or sending', async () => {
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/settings': ({ response }) =>
        response({
          ok: true,
          settings: {
            domains: [
              {
                id: 'screensaver',
                title: 'Photos',
                controls: [
                  { key: 'shuffle', title: 'Shuffle', type: 'bool', value: true, readOnly: true },
                ],
              },
            ],
          },
        }),
    },
  });
  h.eval('go("settings")');
  await h.settle();
  const label = h.document.getElementById('page').querySelector('label.switch-hit');
  assert.ok(label);
  const input = label.querySelector('input.switch');
  let changes = 0;
  input.addEventListener('change', () => changes++);
  label.click();
  input.click();
  await h.settle();
  assert.equal(input.checked, true);
  assert.equal(changes, 0);
  assert.equal(settingsPosts(h).length, 0);
});

test('actual All-rooms Save handlers serialize deferred 45 then 60 per endpoint across active-room changes', async () => {
  const state = new Map(saved.map((r) => [r.base, 30]));
  const releaseOld = [];
  const h = await browserHarness({
    saved,
    routes: {
      '/remote/settings': ({ url, opts, response }) => {
        const base = new URL(url).origin;
        const domain = (value) => ({
          id: 'screensaver',
          title: 'Slideshow',
          controls: [
            {
              key: 'intervalSec',
              title: 'Interval',
              type: 'int',
              asText: true,
              min: 5,
              max: 3600,
              value,
            },
          ],
        });
        if (opts.method !== 'POST')
          return response({ ok: true, settings: { domains: [domain(state.get(base))] } });
        const value = JSON.parse(opts.body).values.intervalSec;
        const confirm = () => {
          state.set(base, value);
          return response({ ok: true, applied: ['intervalSec'], domain: domain(value) });
        };
        if (value === 45)
          return new Promise((resolve) => {
            releaseOld.push(() => resolve(confirm()));
          });
        return confirm();
      },
    },
  });
  await allSettings(h);
  await h.input('Interval', '45');
  h.click('Save');
  await h.settle();
  assert.equal(releaseOld.length, 2);
  h.eval(`selectRoom('${saved[1].base}')`);
  await h.settle();
  h.eval('scope="all";loadScopeSchemas()');
  await h.settle();
  await h.input('Interval', '60');
  h.click('Save');
  await h.settle();
  for (const release of releaseOld) release();
  await h.settle();
  assert.deepEqual([...state.values()], [60, 60]);
  assert.equal(h.eval('data.settings.domains[0].controls[0].value'), 60);
  assert.equal(settingsPosts(h).length, 4);
  assert.match(h.document.getElementById('notice').textContent, /Confirmed in 2 of 2/);
});

const profileSunriseValues = { enabled: true, hour: 23, minute: 59, rampMinutes: 15, chime: false };
function profileSunriseRoute({ legacy = false } = {}) {
  const rooms = new Map();
  return ({ url, opts, response }) => {
    const target = new URL(url);
    const values = rooms.get(target.origin) || { enabled: false, hour: 7, minute: 0, rampMinutes: 20, chime: true };
    const controls = Object.entries(values).map(([key, value]) => ({
      key, title: key, value, type: typeof value === 'boolean' ? 'bool' : 'int',
      ...(key === 'hour' ? { min: 0, max: 23 } : key === 'minute' ? { min: 0, max: 59 } : key === 'rampMinutes' ? { min: 1, max: 60 } : {}),
    }));
    const domain = { id: 'sunrise', title: 'Sunrise', controls };
    if (opts.method === 'POST') {
      const patch = JSON.parse(opts.body);
      assert.equal(patch.domain, 'sunrise');
      const updated = { ...values, ...patch.values };
      rooms.set(target.origin, updated);
      return response({ ok: true, applied: Object.keys(patch.values), domain: { ...domain,
        controls: controls.filter(c => updated.enabled || c.key === 'enabled').map(c => ({ ...c, value: updated[c.key] })) } });
    }
    const scoped = !legacy && target.searchParams.get('domain') === 'sunrise' && target.searchParams.get('includeInactive') === '1';
    return response({ ok: true, settings: { domains: [{ ...domain,
      controls: controls.filter(c => scoped || values.enabled || c.key === 'enabled') }] } });
  };
}

test('profile preflight enables a disabled parent and its hidden children in one batch per room', async () => {
  const h = await browserHarness({ saved, routes: { '/remote/settings': profileSunriseRoute() } });
  await allSettings(h);
  const before = h.calls.length;
  await h.eval(`applyProfile("Wake light",{sunrise:${JSON.stringify(profileSunriseValues)}},settingTargets(),session.capture())`);
  const posts = settingsPosts(h);
  assert.equal(posts.length, 2);
  for (const post of posts) assert.deepEqual(JSON.parse(post.opts.body).values, profileSunriseValues);
  const preflights = h.calls.slice(before).filter(c => c.path === '/remote/settings' && c.opts.method !== 'POST');
  assert.equal(preflights.length, 2);
  for (const read of preflights) {
    assert.equal(new URL(read.url).searchParams.get('domain'), 'sunrise');
    assert.equal(new URL(read.url).searchParams.get('includeInactive'), '1');
  }
  assert.equal(h.eval('scopeResults.every(result => result.ok)'), true);
});

test('profile preflight rejects an out-of-range hidden child without partially enabling its parent', async () => {
  const h = await browserHarness({ saved, routes: { '/remote/settings': profileSunriseRoute() } });
  await allSettings(h);
  await h.eval('applyProfile("Invalid",{sunrise:{enabled:true,hour:24}},settingTargets(),session.capture())');
  assert.equal(settingsPosts(h).length, 0);
  assert.equal(h.eval('scopeResults.length'), 2);
  assert.equal(h.eval('scopeResults.every(result => !result.ok && result.error === "unavailable_setting")'), true);
});

test('legacy peers that omit hidden child metadata fail safely with no partial profile write', async () => {
  const h = await browserHarness({ saved, routes: { '/remote/settings': profileSunriseRoute({ legacy: true }) } });
  await allSettings(h);
  await h.eval('applyProfile("Legacy",{sunrise:{enabled:true,hour:23}},settingTargets(),session.capture())');
  assert.equal(settingsPosts(h).length, 0);
  assert.equal(h.eval('scopeResults.every(result => !result.ok)'), true);
});

const fullProfileFixture = {
  domains: [{ id: 'sunrise', title: 'Sunrise', controls: [
    { key: 'enabled', type: 'bool', value: false },
    { key: 'hour', type: 'int', value: 23, min: 0, max: 23 },
    { key: 'minute', type: 'int', value: 59, min: 0, max: 59 },
  ] }, { id: 'fixture', title: 'Fixture', controls: [
    { key: 'label', type: 'string', value: 'Fresh snapshot' },
    { key: 'password', type: 'string', secret: true, value: 'fixture-secret' },
    { key: 'status', type: 'string', readOnly: true, value: 'Connected' },
    { key: 'address', type: 'string', value: 'https://user:password@example.test/' },
  ] }],
};
const visibleProfileFixture = { domains: [{ ...fullProfileFixture.domains[0], controls: [fullProfileFixture.domains[0].controls[0]] }] };
async function profileCaptureHarness(profileRoute) {
  const h = await browserHarness({ saved, routes: {
    '/remote/settings': ({ response }) => response({ ok: true, settings: visibleProfileFixture }),
    '/remote/settings/profile': profileRoute || (({ response }) => response({ ok: true, profileComplete: true, settings: fullProfileFixture })),
  } });
  h.eval('go("settings","profiles");setDraft("profile-name","Wake")');
  await h.settle();
  return h;
}
const storedProfiles = h => JSON.parse(h.eval('storage.get(STORAGE.profiles,"{}")'));
const profileReads = h => h.calls.filter(c => c.path === '/remote/settings/profile');

test('saving a complete profile reads fresh hidden values once and supports literal prototype-like names', async () => {
  const h = await profileCaptureHarness();
  h.eval('setDraft("profile-name","__proto__");render()');
  h.click('Save current settings'); await h.settle();
  assert.equal(profileReads(h).length, 1);
  assert.deepEqual(storedProfiles(h).__proto__.sunrise, { enabled: false, hour: 23, minute: 59 });
  assert.equal(storedProfiles(h).__proto__.fixture.label, 'Fresh snapshot');
  assert.equal(settingsPosts(h).length, 0);
});

test('complete profile capture omits credentials, credential-bearing addresses and read-only values', async () => {
  const h = await profileCaptureHarness(); h.click('Save current settings'); await h.settle();
  assert.deepEqual(storedProfiles(h).Wake.fixture, { label: 'Fresh snapshot' });
  assert.equal(JSON.stringify(storedProfiles(h)).includes('fixture-secret'), false);
});

test('profile capture failure preserves existing profiles and never falls back to cached visible settings', async () => {
  const h = await profileCaptureHarness(({ response }) => response({ error: 'not_found' }, 404));
  h.eval('storage.set(STORAGE.profiles,JSON.stringify({Other:{fixture:{label:"Keep me"}}}));render()');
  h.click('Save current settings'); await h.settle();
  assert.deepEqual(storedProfiles(h), { Other: { fixture: { label: 'Keep me' } } });
  assert.doesNotMatch(h.document.getElementById('notice').textContent, /Profile saved/);
});

test('partial profile schemas without the complete marker cannot be saved', async () => {
  const h = await profileCaptureHarness(({ response }) => response({ ok: true, settings: visibleProfileFixture }));
  h.click('Save current settings'); await h.settle();
  assert.deepEqual(storedProfiles(h), {});
  assert.match(h.document.getElementById('notice').textContent, /unreadable|complete/i);
});

test('burst profile saves share one pending capture and restore the enabled Save control', async () => {
  let release;
  const h = await profileCaptureHarness(({ response }) => new Promise(resolve => { release = () => resolve(response({ ok: true, profileComplete: true, settings: fullProfileFixture })); }));
  const first = h.click('Save current settings'); first.click(); await h.settle();
  assert.equal(profileReads(h).length, 1);
  assert.deepEqual(storedProfiles(h), {});
  release(); await h.settle();
  assert.equal(Object.keys(storedProfiles(h)).length, 1);
  assert.equal(h.document.body.querySelectorAll('button').find(b => b.textContent === 'Save current settings').disabled, false);
});

test('a late profile capture cannot save after the user changes Portal', async () => {
  let release;
  const h = await profileCaptureHarness(({ response }) => new Promise(resolve => { release = () => resolve(response({ ok: true, profileComplete: true, settings: fullProfileFixture })); }));
  h.click('Save current settings'); await h.settle();
  h.eval(`selectRoom('${saved[1].base}')`); await h.settle();
  const page = h.document.getElementById('page').textContent;
  assert.equal(typeof release, 'function');
  release(); await h.settle();
  assert.deepEqual(storedProfiles(h), {});
  assert.equal(h.document.getElementById('page').textContent, page);
});

test('replacing a profile waits for confirmation and then captures current complete settings', async () => {
  const h = await profileCaptureHarness();
  h.eval('storage.set(STORAGE.profiles,JSON.stringify({Wake:{sunrise:{hour:7}}}));render()');
  h.click('Save current settings'); await h.settle();
  assert.equal(profileReads(h).length, 0);
  h.document.getElementById('sheet-actions').querySelectorAll('button').find(b => b.textContent === 'Cancel').click(); await h.settle();
  assert.deepEqual(storedProfiles(h).Wake, { sunrise: { hour: 7 } });
  h.click('Save current settings'); await h.settle();
  h.document.getElementById('sheet-actions').querySelectorAll('button').find(b => b.textContent === 'Replace').click(); await h.settle();
  assert.equal(profileReads(h).length, 1);
  assert.deepEqual(storedProfiles(h).Wake.sunrise, { enabled: false, hour: 23, minute: 59 });
});

test('browser storage rejection never announces that a complete profile was saved', async () => {
  const h = await profileCaptureHarness();
  h.eval('storage.set=()=>false');
  h.click('Save current settings'); await h.settle();
  assert.deepEqual(storedProfiles(h), {});
  assert.doesNotMatch(h.document.getElementById('notice').textContent, /Profile saved/);
  assert.match(h.document.getElementById('notice').textContent, /storage/i);
});


test('complete profile capture waits for an earlier settings write in the existing room FIFO', async () => {
  let releaseWrite;
  let enabled = false;
  const h = await browserHarness({ saved, routes: {
    '/remote/settings': ({ opts, response }) => opts.method === 'POST'
      ? new Promise(resolve => { releaseWrite = () => {
          enabled = true;
          resolve(response({ ok: true, applied: ['enabled'], domain: { id: 'sunrise', controls: [{ key: 'enabled', value: true }] } }));
        }; })
      : response({ ok: true, settings: visibleProfileFixture }),
    '/remote/settings/profile': ({ response }) => response({ ok: true, profileComplete: true,
      settings: { domains: [{ id: 'sunrise', controls: [{ key: 'enabled', type: 'bool', value: enabled }] }] } }),
  } });
  h.eval('go("settings","profiles");setDraft("profile-name","Queued")'); await h.settle();
  const writing = h.eval('applyDomain("sunrise",{enabled:true},settingTargets(),session.capture())');
  await h.settle(); assert.equal(typeof releaseWrite, 'function');
  h.click('Save current settings'); await h.settle();
  assert.equal(profileReads(h).length, 0);
  releaseWrite(); await writing; await h.settle();
  assert.equal(profileReads(h).length, 1);
  assert.deepEqual(storedProfiles(h).Queued, { sunrise: { enabled: true } });
});


const profileStoreRaw = h => h.eval('localStorage.getItem(STORAGE.profiles)');
function putProfileStore(h, raw) {
  h.eval(`localStorage.setItem(STORAGE.profiles, ${JSON.stringify(raw)});render()`);
}
function confirmProfileDelete(h) {
  h.document.getElementById('sheet-actions').querySelectorAll('button').find(b => b.textContent === 'Delete').click();
}

test('profile store malformed JSON stays intact and offers Retry without Save or Apply', async () => {
  const h = await profileCaptureHarness();
  putProfileStore(h, '{broken');
  assert.match(h.document.getElementById('page').textContent, /profiles.*could not be read/i);
  assert.equal(h.document.getElementById('page').querySelectorAll('button').some(b => b.textContent === 'Save current settings'), false);
  assert.equal(profileStoreRaw(h), '{broken');
  assert.equal(profileReads(h).length, 0);
  assert.ok(h.document.getElementById('page').querySelectorAll('button').some(b => b.textContent === 'Retry'));
});

test('profile store blocked read offers a working Retry and performs no writes', async () => {
  const h = await profileCaptureHarness();
  putProfileStore(h, JSON.stringify({ Wake: { sunrise: { hour: 7 } } }));
  h.eval('globalThis.originalProfileGet = localStorage.getItem;globalThis.profileWrites=0;globalThis.originalProfileSet=localStorage.setItem;localStorage.setItem=(key,value)=>{profileWrites++;return originalProfileSet(key,value)};localStorage.getItem=()=>{throw new Error("blocked")};render()');
  assert.match(h.document.getElementById('page').textContent, /storage.*unavailable/i);
  assert.equal(h.eval('profileWrites'), 0);
  h.eval('localStorage.getItem=originalProfileGet');
  h.click('Retry'); await h.settle();
  assert.match(h.document.getElementById('page').textContent, /Wake/);
  assert.equal(h.eval('profileWrites'), 0);
});

test('profile store rejects wrong collection roots without normalizing or deleting them', async () => {
  const h = await profileCaptureHarness();
  for (const raw of ['null', '[]', 'true', '7', '"saved"']) {
    putProfileStore(h, raw);
    assert.match(h.document.getElementById('page').textContent, /profiles.*could not be read/i);
    assert.equal(profileStoreRaw(h), raw);
  }
});

test('profile store rejects nonobject saved profiles without changing the original collection', async () => {
  const h = await profileCaptureHarness();
  for (const value of [null, [], true, 7, 'bad']) {
    const raw = JSON.stringify({ Wake: { sunrise: { hour: 7 } }, Broken: value });
    putProfileStore(h, raw);
    assert.match(h.document.getElementById('page').textContent, /profiles.*could not be read/i);
    assert.equal(profileStoreRaw(h), raw);
  }
});

test('profile store Save refuses unreadable originals before any Portal capture request', async () => {
  const h = await profileCaptureHarness();
  putProfileStore(h, '{broken');
  await h.eval('saveCurrentProfile("New",session.capture())');
  assert.equal(profileReads(h).length, 0);
  assert.equal(profileStoreRaw(h), '{broken');
  assert.match(h.document.getElementById('notice').textContent, /not saved/i);
});

test('profile store read failure during deferred capture cannot overwrite previous profiles', async () => {
  let release;
  const h = await profileCaptureHarness(({ response }) => new Promise(resolve => { release = () => resolve(response({ ok: true, profileComplete: true, settings: fullProfileFixture })); }));
  putProfileStore(h, JSON.stringify({ Earlier: { sunrise: { hour: 7 } } }));
  h.click('Save current settings'); await h.settle();
  h.eval('globalThis.originalProfileGet=localStorage.getItem;globalThis.profileWrites=0;globalThis.originalProfileSet=localStorage.setItem;localStorage.setItem=(key,value)=>{profileWrites++;return originalProfileSet(key,value)};localStorage.getItem=()=>{throw new Error("blocked")}');
  release(); await h.settle();
  assert.equal(h.eval('profileWrites'), 0);
  assert.match(h.document.getElementById('notice').textContent, /not saved/i);
  h.eval('localStorage.getItem=originalProfileGet');
  assert.deepEqual(storedProfiles(h), { Earlier: { sunrise: { hour: 7 } } });
  assert.equal(h.eval('busy.has(profileCaptureKey())'), false);
});

test('profile store malformed originals arriving during capture remain byte exact', async () => {
  let release;
  const h = await profileCaptureHarness(({ response }) => new Promise(resolve => { release = () => resolve(response({ ok: true, profileComplete: true, settings: fullProfileFixture })); }));
  h.click('Save current settings'); await h.settle();
  putProfileStore(h, '{changed-and-broken');
  release(); await h.settle();
  assert.equal(profileStoreRaw(h), '{changed-and-broken');
  assert.match(h.document.getElementById('notice').textContent, /not saved/i);
});

test('profile store failed Delete keeps the profile and reports that it was not deleted', async () => {
  const h = await profileCaptureHarness();
  putProfileStore(h, JSON.stringify({ Wake: { sunrise: { hour: 7 } } }));
  h.eval('storage.set=()=>false');
  h.click('Delete'); confirmProfileDelete(h); await h.settle();
  assert.deepEqual(storedProfiles(h), { Wake: { sunrise: { hour: 7 } } });
  assert.match(h.document.getElementById('notice').textContent, /not deleted/i);
  assert.match(h.document.getElementById('page').textContent, /Wake/);
});

test('profile store confirmed Delete rereads and preserves newly added other profiles', async () => {
  const h = await profileCaptureHarness();
  putProfileStore(h, JSON.stringify({ Wake: { sunrise: { hour: 7 } } }));
  h.click('Delete');
  h.eval('localStorage.setItem(STORAGE.profiles,JSON.stringify({Wake:{sunrise:{hour:7}},Later:{sunrise:{hour:8}}}))');
  confirmProfileDelete(h); await h.settle();
  assert.deepEqual(storedProfiles(h), { Later: { sunrise: { hour: 8 } } });
  assert.match(h.document.getElementById('notice').textContent, /Profile deleted/i);
});

test('profile store Delete preserves malformed data discovered after confirmation opens', async () => {
  const h = await profileCaptureHarness();
  putProfileStore(h, JSON.stringify({ Wake: { sunrise: { hour: 7 } } }));
  h.click('Delete');
  h.eval('localStorage.setItem(STORAGE.profiles,"{broken")');
  confirmProfileDelete(h); await h.settle();
  assert.equal(profileStoreRaw(h), '{broken');
  assert.match(h.document.getElementById('notice').textContent, /not deleted/i);
});

test('profile store Cancel and stale-room Delete confirmation perform no writes', async () => {
  const h = await profileCaptureHarness();
  putProfileStore(h, JSON.stringify({ Wake: { sunrise: { hour: 7 } } }));
  h.eval('globalThis.profileWrites=0;globalThis.originalProfileSet=localStorage.setItem;localStorage.setItem=(key,value)=>{profileWrites++;return originalProfileSet(key,value)}');
  h.click('Delete'); h.click('Cancel'); await h.settle();
  assert.equal(h.eval('profileWrites'), 0);
  h.click('Delete'); h.eval('session.select(roster[1])'); confirmProfileDelete(h); await h.settle();
  assert.equal(h.eval('profileWrites'), 0);
});

test('profile store already-removed Delete skips a redundant write', async () => {
  const h = await profileCaptureHarness();
  putProfileStore(h, JSON.stringify({ Wake: { sunrise: { hour: 7 } } }));
  h.click('Delete');
  h.eval('localStorage.setItem(STORAGE.profiles,JSON.stringify({Later:{sunrise:{hour:8}}}));globalThis.profileWrites=0;globalThis.originalProfileSet=localStorage.setItem;localStorage.setItem=(key,value)=>{profileWrites++;return originalProfileSet(key,value)}');
  confirmProfileDelete(h); await h.settle();
  assert.equal(h.eval('profileWrites'), 0);
  assert.deepEqual(storedProfiles(h), { Later: { sunrise: { hour: 8 } } });
});

test('profile store literal prototype-like profile names delete only that own entry', async () => {
  const h = await profileCaptureHarness();
  putProfileStore(h, '{"__proto__":{"sunrise":{"hour":7}},"constructor":{"sunrise":{"hour":8}}}');
  h.click('Delete'); confirmProfileDelete(h); await h.settle();
  assert.deepEqual(storedProfiles(h), { constructor: { sunrise: { hour: 8 } } });
});


const applyProfileFixture = { domains: [
  { id: 'screensaver', title: 'Photos', controls: [{ key: 'intervalSec', type: 'int', value: 30, min: 5, max: 600 }, { key: 'pin', type: 'string', secret: true, value: '' }] },
  { id: 'calendar', title: 'Calendar', controls: [{ key: 'maxEvents', type: 'int', value: 3, min: 1, max: 20 }] },
] };
async function ownedProfileHarness({ failSecondRoom = false, holdCapture = false } = {}) {
  const releases = []; let captureRelease;
  const h = await browserHarness({ saved, routes: {
    '/remote/settings': ({ url, opts, response }) => {
      if (opts.method !== 'POST') return response({ ok: true, settings: applyProfileFixture });
      const body = JSON.parse(opts.body), domain = applyProfileFixture.domains.find(d => d.id === body.domain);
      const reply = () => failSecondRoom && body.domain === 'calendar' && url.includes('8878')
        ? response({ ok: false, error: 'unreachable' }, 503)
        : response({ ok: true, applied: Object.keys(body.values), domain: { ...domain, controls: domain.controls.map(c => ({ ...c, value: body.values[c.key] ?? c.value })) } });
      return body.domain === 'screensaver' ? new Promise(resolve => releases.push(() => resolve(reply()))) : reply();
    },
    '/remote/settings/profile': ({ response }) => holdCapture
      ? new Promise(resolve => { captureRelease = () => resolve(response({ ok: true, profileComplete: true, settings: applyProfileFixture })); })
      : response({ ok: true, profileComplete: true, settings: applyProfileFixture }),
  } });
  h.eval('go("settings","profiles");localStorage.setItem(STORAGE.profiles,JSON.stringify({Morning:{screensaver:{intervalSec:45},calendar:{maxEvents:5}},Evening:{screensaver:{intervalSec:60},calendar:{maxEvents:8}}}));render()');
  await h.settle();
  return { h, releases, release: async () => { releases.splice(0).forEach(f => f()); await h.settle(); }, capture: async () => { captureRelease(); await h.settle(); } };
}
const confirmOwnedApply = h => h.document.getElementById('sheet-actions').querySelectorAll('button').find(b => b.textContent === 'Apply').click();
const startOwnedProfile = h => h.eval('applyProfile("Morning",{screensaver:{intervalSec:45},calendar:{maxEvents:5}},settingTargets(),session.capture())');

test('profile operation duplicate admission cannot apply the remaining category early', async () => {
  const t = await ownedProfileHarness(), h = t.h; const first = startOwnedProfile(h); await h.settle();
  const duplicate = startOwnedProfile(h); await h.settle();
  try {
    assert.equal(settingsPosts(h).length, 1);
    assert.equal(settingsPosts(h).some(c => JSON.parse(c.opts.body).domain === 'calendar'), false);
  } finally { await t.release(); await first; await duplicate; }
  assert.equal(settingsPosts(h).length, 2);
});

test('profile operation a competing profile cannot mix categories into the running profile', async () => {
  const t = await ownedProfileHarness(), h = t.h; const first = startOwnedProfile(h); await h.settle();
  const competing = h.eval('applyProfile("Evening",{calendar:{maxEvents:8}},settingTargets(),session.capture())'); await h.settle();
  try {
    assert.equal(settingsPosts(h).length, 1);
  } finally { await t.release(); await first; await competing; }
  assert.deepEqual(settingsPosts(h).map(c => JSON.parse(c.opts.body).values), [{ intervalSec: 45 }, { maxEvents: 5 }]);
});

test('profile operation actual Apply confirmation locks mutation and scope controls with visible progress', async () => {
  const t = await ownedProfileHarness(), h = t.h;
  h.click('Apply'); confirmOwnedApply(h); await h.settle();
  try {
    const buttons = h.document.getElementById('page').querySelectorAll('button');
    for (const label of ['Apply', 'Delete', 'Save current settings', 'This Portal', 'Selected rooms', 'All rooms'])
      assert.equal(buttons.filter(b => b.textContent === label).every(b => b.disabled), true, label);
    assert.match(h.document.getElementById('page').textContent, /Applying.*Morning/);
    assert.match(h.document.getElementById('page').textContent, /0 of 2 categories/);
  } finally { await t.release(); }
  assert.equal(h.document.getElementById('page').querySelectorAll('button').find(b => b.textContent === 'Apply').disabled, false);
});

test('profile operation freezes all category values before the first asynchronous write', async () => {
  const t = await ownedProfileHarness(), h = t.h;
  const applying = h.eval('globalThis.changingProfile={screensaver:{intervalSec:45},calendar:{maxEvents:5}};applyProfile("Morning",changingProfile,settingTargets(),session.capture())'); await h.settle();
  h.eval('changingProfile.calendar.maxEvents=9'); await t.release(); await applying;
  assert.equal(JSON.parse(settingsPosts(h)[1].opts.body).values.maxEvents, 5);
});

test('profile operation All rooms retains every category result and reports partial failure accurately', async () => {
  const t = await ownedProfileHarness({ failSecondRoom: true }), h = t.h;
  h.eval('scope="all"'); const applying = startOwnedProfile(h); await h.settle(); await t.release(); await applying;
  assert.equal(settingsPosts(h).length, 4);
  assert.equal(h.eval('profileApplyState.results.length'), 4);
  assert.equal(h.eval('profileApplyState.results.filter(r=>r.ok).length'), 3);
  assert.match(h.document.getElementById('notice').textContent, /3 of 4/);
  assert.match(h.document.getElementById('page').textContent, /2 of 2 categories/);
  assert.equal(h.eval('profileApplyState.pending'), false);
});

test('profile operation empty and secret-only profiles never announce zero-of-zero success', async () => {
  const t = await ownedProfileHarness(), h = t.h;
  for (const profile of [{}, { screensaver: { pin: 'public-fixture-secret' } }]) {
    await h.eval(`applyProfile("Empty",${JSON.stringify(profile)},settingTargets(),session.capture())`);
    assert.equal(settingsPosts(h).length, 0);
    assert.match(h.document.getElementById('notice').textContent, /no applicable settings/i);
    assert.doesNotMatch(h.document.getElementById('notice').textContent, /0 of 0/);
  }
});

test('profile operation navigation stops future categories and retains confirmed partial outcome on return', async () => {
  const t = await ownedProfileHarness(), h = t.h; const applying = startOwnedProfile(h); await h.settle();
  h.eval('go("home")'); await h.settle(); await t.release(); await applying;
  assert.equal(settingsPosts(h).length, 1);
  assert.equal(h.eval('profileApplyState.stopped'), true);
  assert.equal(h.eval('profileApplyState.results.length'), 1);
  assert.equal(h.eval('tab'), 'home');
  h.eval('go("settings","profiles")'); await h.settle();
  assert.match(h.document.getElementById('page').textContent, /application stopped/i);
  assert.match(h.document.getElementById('page').textContent, /confirmed.*remain/i);
  assert.equal(h.document.getElementById('page').querySelectorAll('button').find(b => b.textContent === 'Apply').disabled, false);
});

test('profile operation switching rooms cannot dispatch remaining categories to the new room', async () => {
  const t = await ownedProfileHarness(), h = t.h; const applying = startOwnedProfile(h); await h.settle();
  h.eval(`selectRoom('${saved[1].base}')`); await h.settle(); await t.release(); await applying;
  assert.equal(settingsPosts(h).length, 1);
  assert.ok(settingsPosts(h)[0].url.includes('8877'));
  assert.equal(h.eval('profileApplyState.stopped'), true);
  assert.equal(h.eval('profileApplyState.targets[0].base'), saved[0].base);
});

test('profile operation outstanding snapshot capture blocks Apply and Delete until it completes', async () => {
  const t = await ownedProfileHarness({ holdCapture: true }), h = t.h;
  h.eval('setDraft("profile-name","New snapshot");render()'); h.click('Save current settings'); await h.settle();
  const applying = h.eval('applyProfile("Morning",{calendar:{maxEvents:8}},settingTargets(),session.capture())');
  try {
    h.eval('deleteProfile("Morning")');
    assert.equal(settingsPosts(h).length, 0);
    assert.ok(storedProfiles(h).Morning);
    assert.equal(h.document.getElementById('page').querySelectorAll('button').filter(b=>b.textContent==='Apply').every(b=>b.disabled), true);
  } finally { await t.capture(); await applying; }
  assert.equal(settingsPosts(h).length, 0);
});

test('profile operation failure clears its pending guard and offers a truthful result', async () => {
  const t = await ownedProfileHarness(), h = t.h;
  h.eval('applyDomain=async()=>{throw new Error("public-fixture-error")}');
  await startOwnedProfile(h);
  assert.equal(h.eval('profileApplyState.pending'), false);
  assert.match(h.document.getElementById('notice').textContent, /could not finish/i);
  assert.equal(h.document.getElementById('page').querySelectorAll('button').find(b=>b.textContent==='Apply').disabled, false);
});

test('profile operation cancelled confirmation makes no writes and starts no operation', async () => {
  const t = await ownedProfileHarness(), h = t.h;
  h.click('Apply'); h.click('Cancel'); await h.settle();
  assert.equal(settingsPosts(h).length, 0);
  assert.equal(h.eval('profileApplyState?.pending ?? false'), false);
});

test('profile operation no selected ordinary rooms reports an unconfirmed category instead of success', async () => {
  const t = await ownedProfileHarness(), h = t.h;
  h.eval('scope="selected";selectedTargets.clear()');
  await h.eval('applyProfile("Morning",{calendar:{maxEvents:5}},settingTargets(),session.capture())');
  assert.equal(settingsPosts(h).length, 0);
  assert.match(h.document.getElementById('notice').textContent, /could not finish/i);
  assert.doesNotMatch(h.document.getElementById('notice').textContent, /0 of 0.*confirmed/);
});


const collectionA = '/sdcard/Pictures/Room A', collectionB = '/sdcard/Pictures/Room B';
async function collectionContextHarness() {
  const holds = [], modes = new Map();
  const h = await browserHarness({ saved, routes: {
    '/remote/photos': ({ url, response }) => {
      const q = new URL(url).searchParams, path = q.get('path'), all = q.has('recursive');
      const reply = () => modes.get(path) === 'fail' ? response({ ok: false, error: 'folder_not_found' }, 404)
        : response({ ok: true, path: modes.get(path) === 'mismatch' ? collectionA : path, recursive: all,
          files: [{ path: `${path}/fixture.jpg`, name: path === collectionB ? 'New folder photo' : 'Original folder photo' }],
          folders: all ? [{ path: collectionA, count: 1 }, { path: collectionB, count: 1 }] : [] });
      if (modes.get(path) === 'hold') return new Promise(resolve => holds.push({ path, release: () => resolve(reply()) }));
      return reply();
    },
    '/remote/sources': ({ opts, response }) => response({ ok: true, sources: opts.body ? JSON.parse(opts.body) : {} }),
  } });
  h.eval('go("photos")'); await h.settle(); h.click('Folders'); await h.settle();
  return { h, modes, holds, release: async () => { holds.splice(0).forEach(job => job.release()); await h.settle(); } };
}
async function openCollection(h, path) { await h.input('Folder on Portal', path); h.click('Open folder'); await h.settle(); }
const collectionButton = h => h.document.getElementById('page').querySelectorAll('button').find(b => b.textContent === 'Use for slideshow');
const collectionWrites = h => h.calls.filter(c => c.path === '/remote/sources' && c.opts.method === 'POST');
const confirmCollection = h => h.document.getElementById('sheet-actions').querySelectorAll('button').find(b => b.textContent === 'Use collection').click();

test('photo collection switching hides the old gallery and disables slideshow activation while loading', async () => {
  const t = await collectionContextHarness(), h = t.h; t.modes.set(collectionB, 'hold'); await openCollection(h, collectionB);
  try {
    assert.equal(h.document.getElementById('page').textContent.includes('Original folder photo'), false);
    assert.match(h.document.getElementById('page').textContent, /Loading photo metadata/);
    assert.equal(collectionButton(h).disabled, true);
    collectionButton(h).click(); assert.equal(h.document.getElementById('sheet').open, false);
  } finally { await t.release(); }
});

test('photo collection import defaults to the newly selected folder while its listing is pending', async () => {
  const t = await collectionContextHarness(), h = t.h; t.modes.set(collectionB, 'hold'); await openCollection(h, collectionB);
  try {
    h.click('Import photos');
    assert.equal(h.document.getElementById('sheet-content').querySelector('[data-focus="upload-folder"]').value, collectionB);
  } finally { h.eval('closeSheet()'); await t.release(); }
});

test('photo collection failed switch cannot activate the old collection and Retry keeps the selected folder', async () => {
  const t = await collectionContextHarness(), h = t.h; t.modes.set(collectionB, 'fail'); await openCollection(h, collectionB);
  assert.match(h.document.getElementById('page').textContent, /Photos unavailable/);
  assert.equal(collectionButton(h).disabled, true); assert.equal(collectionWrites(h).length, 0);
  t.modes.delete(collectionB); h.click('Retry'); await h.settle();
  assert.match(h.document.getElementById('page').textContent, /New folder photo/);
  assert.equal(collectionButton(h).disabled, false);
  assert.equal(new URL(h.calls.filter(c => c.path === '/remote/photos').at(-1).url).searchParams.get('path'), collectionB);
});

test('photo collection confirmed switch activates only the displayed selected folder', async () => {
  const t = await collectionContextHarness(), h = t.h; await openCollection(h, collectionB);
  h.click('Use for slideshow'); confirmCollection(h); await h.settle();
  assert.deepEqual(collectionWrites(h).map(c => JSON.parse(c.opts.body)), [{ folderPath: collectionB }]);
  assert.match(h.document.getElementById('notice').textContent, /Collection activated/);
});

test('photo collection a refresh retains matching cached photos but waits for confirmation before activation', async () => {
  const t = await collectionContextHarness(), h = t.h; t.modes.set(collectionA, 'hold');
  const refreshing = h.eval('loadPhotos()'); await h.settle();
  try {
    assert.match(h.document.getElementById('page').textContent, /Original folder photo/);
    assert.equal(collectionButton(h).disabled, true);
  } finally { await t.release(); await refreshing; }
  assert.equal(collectionButton(h).disabled, false);
});

test('photo collection retry shows pending recovery rather than the previous failure', async () => {
  const t = await collectionContextHarness(), h = t.h; t.modes.set(collectionB, 'fail'); await openCollection(h, collectionB);
  t.modes.set(collectionB, 'hold'); h.click('Retry'); await h.settle();
  try {
    assert.match(h.document.getElementById('page').textContent, /Loading photo metadata/);
    assert.equal(h.document.getElementById('page').textContent.includes('Photos unavailable'), false);
    assert.equal(collectionButton(h).disabled, true);
  } finally { await t.release(); }
});

test('photo collection superseded listing cannot restore an old folder or finish the newer pending state', async () => {
  const t = await collectionContextHarness(), h = t.h; t.modes.set(collectionA, 'hold');
  const old = h.eval('loadPhotos()'); await h.settle(); await openCollection(h, collectionB);
  await t.release(); await old;
  assert.match(h.document.getElementById('page').textContent, /New folder photo/);
  assert.equal(h.document.getElementById('page').textContent.includes('Original folder photo'), false);
  assert.equal(collectionButton(h).disabled, false);
});

test('photo collection delayed confirmation cannot apply a collection after the selection changes', async () => {
  const t = await collectionContextHarness(), h = t.h; h.click('Use for slideshow');
  await openCollection(h, collectionB); confirmCollection(h); await h.settle();
  assert.equal(collectionWrites(h).length, 0);
  assert.match(h.document.getElementById('notice').textContent, /collection changed/i);
});

test('photo collection reselecting the current Folders tab does not reset or rescan the chosen folder', async () => {
  const t = await collectionContextHarness(), h = t.h; await openCollection(h, collectionB);
  const reads = h.calls.filter(c => c.path === '/remote/photos').length;
  h.click('Folders'); await h.settle();
  assert.equal(h.calls.filter(c => c.path === '/remote/photos').length, reads);
  assert.equal(h.eval('photoPath'), collectionB);
});

test('photo collection a listing for a different returned folder is rejected without enabling slideshow activation', async () => {
  const t = await collectionContextHarness(), h = t.h; t.modes.set(collectionB, 'mismatch'); await openCollection(h, collectionB);
  assert.match(h.document.getElementById('page').textContent, /Photos unavailable/);
  assert.equal(collectionButton(h).disabled, true); assert.equal(collectionWrites(h).length, 0);
});

async function pendingLayoutSave({ fail = false, canonical = false } = {}) {
  let release;
  const initial = { assignments: { 'com.test.app': 'Room' }, homeOrder: ['com.test.app/.Main'], gridOrder: [], gridSlots: [], widgets: [] };
  const h = await browserHarness({ saved, routes: {
    '/remote/layout': ({ opts, response }) => opts.method === 'POST'
      ? new Promise((resolve) => {
          const body = JSON.parse(opts.body);
          release = () => resolve(fail ? response({ ok: false, error: 'conflict' }, 409)
            : response({ ok: true, revision: 'confirmed', widgetKinds: ['weather'], layout: {
                ...body.layout, assignments: canonical ? { 'com.test.app': 'Canonical' } : body.layout.assignments,
              } }));
        })
      : response({ ok: true, revision: 'current', widgetKinds: ['weather'], layout: initial }),
  } });
  h.eval('go("home","layout")');
  await h.settle();
  await h.input('com.test.app', 'Submitted');
  h.click('Save layout');
  await h.settle();
  return { h, finish: async () => { release(); await h.settle(); } };
}

const layoutPosts = (h) => h.calls.filter((c) => c.path === '/remote/layout' && c.opts.method === 'POST');
const layoutButton = (h, label) => h.document.body.querySelectorAll('button').find((b) => b.textContent === label);

test('layout save retains a folder edit made after submission', async () => {
  const { h, finish } = await pendingLayoutSave();
  try { await h.input('com.test.app', 'Newer'); } finally { await finish(); }
  assert.equal(h.eval('layoutDraft.assignments["com.test.app"]'), 'Newer');
  assert.equal(h.eval('data.layout.layout.assignments["com.test.app"]'), 'Submitted');
  assert.equal(layoutButton(h, 'Save layout').disabled, false);
});

test('layout save retains widget additions made after submission', async () => {
  const { h, finish } = await pendingLayoutSave();
  try { h.eval('editLayout(l => addWidget(l,"weather","after-submit"))'); } finally { await finish(); }
  assert.equal(h.eval('layoutDraft.widgets.length'), 1);
  assert.equal(h.eval('data.layout.layout.widgets.length'), 0);
});

test('layout save shows pending status while keeping folder fields editable', async () => {
  const { h, finish } = await pendingLayoutSave();
  try {
    assert.match(h.document.getElementById('page').textContent, /Saving submitted layout/);
    assert.equal(layoutButton(h, 'Save layout').disabled, true);
    assert.equal(layoutButton(h, 'Reload layout').disabled, true);
    await h.input('com.test.app', 'Still editable');
    assert.equal(h.eval('layoutDraft.assignments["com.test.app"]'), 'Still editable');
  } finally { await finish(); }
});

test('layout save dispatches only one mutation while pending', async () => {
  const { h, finish } = await pendingLayoutSave();
  try {
    h.click('Save layout');
    await h.settle();
    assert.equal(layoutPosts(h).length, 1);
    assert.equal(layoutButton(h, 'Save layout').disabled, true);
  } finally { await finish(); }
});

test('next layout save uses the confirmed revision and preserved draft', async () => {
  const { h, finish } = await pendingLayoutSave();
  try { await h.input('com.test.app', 'Next save'); } finally { await finish(); }
  h.click('Save layout');
  await h.settle();
  try {
    const body = JSON.parse(layoutPosts(h).at(-1).opts.body);
    assert.equal(body.layout.assignments['com.test.app'], 'Next save');
    assert.equal(body.expectedRevision, 'confirmed');
  } finally { await finish(); }
});

test('layout save adopts canonical response when no later edits exist', async () => {
  const { h, finish } = await pendingLayoutSave({ canonical: true });
  await finish();
  assert.equal(h.eval('layoutDraft.assignments["com.test.app"]'), 'Canonical');
  assert.equal(layoutButton(h, 'Save layout').disabled, true);
});

test('failed layout save preserves newer draft and original revision', async () => {
  const { h, finish } = await pendingLayoutSave({ fail: true });
  try { await h.input('com.test.app', 'Retry me'); } finally { await finish(); }
  assert.equal(h.eval('layoutDraft.assignments["com.test.app"]'), 'Retry me');
  assert.equal(h.eval('layoutRevision'), 'current');
  assert.equal(layoutButton(h, 'Save layout').disabled, false);
  assert.equal(layoutButton(h, 'Reload layout').disabled, false);
});

test('layout reload cannot discard a draft during its pending save', async () => {
  const { h, finish } = await pendingLayoutSave();
  try {
    h.click('Reload layout');
    await h.settle();
    assert.equal(h.document.getElementById('sheet').open, false);
    assert.equal(h.eval('layoutDraft.assignments["com.test.app"]'), 'Submitted');
  } finally { await finish(); }
});

test('late layout save never overwrites another room editor', async () => {
  const { h, finish } = await pendingLayoutSave();
  try {
    h.eval(`selectRoom('${saved[1].base}')`);
    await h.settle();
    h.eval('go("home","layout")');
    await h.settle();
    await h.input('com.test.app', 'Other room');
  } finally { await finish(); }
  assert.equal(h.eval('layoutDraft.assignments["com.test.app"]'), 'Other room');
  assert.equal(h.eval('layoutRevision'), 'current');
});

test('layout acknowledgment explains that newer edits remain unsaved', async () => {
  const { h, finish } = await pendingLayoutSave();
  try { await h.input('com.test.app', 'Newer'); } finally { await finish(); }
  assert.match(h.document.body.textContent, /newer edits are still unsaved/i);
  assert.match(h.document.getElementById('page').textContent, /Unsaved changes/);
});

async function pendingPresetSave({ fail = false, origin = "home" } = {}) {
  let release;
  const h = await browserHarness({ saved, routes: {
    '/remote/presets': ({ opts, response }) => opts.method === 'POST'
      ? new Promise(resolve => {
          const body = JSON.parse(opts.body);
          release = () => resolve(fail ? response({ ok: false, error: 'invalid_value' }, 400)
            : response({ ok: true, presets: body.presets }));
        })
      : response({ ok: true, presets: [{ id: 'existing', name: 'Submitted', steps: [{ type: 'wait', ms: 100 }] }] }),
  } });
  h.eval(`go('${origin}',"presets")`); await h.settle(); h.click('Edit'); await h.settle();
  h.click('Save preset'); await h.settle();
  return { h, finish: async () => { release(); await h.settle(); } };
}
const presetPosts = h => h.calls.filter(c => c.path === '/remote/presets' && c.opts.method === 'POST');

test('preset save preserves a newer name instead of closing its editor', async () => {
  const { h, finish } = await pendingPresetSave();
  try { await h.input('Preset name', 'Newer name'); } finally { await finish(); }
  assert.equal(h.eval('presetDraft?.name'), 'Newer name');
  assert.equal(h.eval('data.presets[0].name'), 'Submitted');
});

test('preset save preserves rich step changes and an open step editor', async () => {
  const { h, finish } = await pendingPresetSave();
  try { h.eval('presetDraft.steps[0].step={type:"config",config:{nested:{kept:true}},futureField:"retained"};render()'); } finally { await finish(); }
  assert.equal(h.eval('presetDraft?.steps[0].step.futureField'), 'retained');
  assert.equal(h.eval('presetDraft?.steps[0].step.config.nested.kept'), true);
  const pending=await pendingPresetSave();pending.h.click('Add step');await pending.h.settle();await pending.finish();
  assert.notEqual(pending.h.eval('presetDraft'),null);
  assert.equal(pending.h.document.getElementById('sheet').open,true);
  pending.h.click('Save step');await pending.h.settle();
  assert.equal(pending.h.eval('presetDraft.steps.length'),2);
});

test('preset save shows progress and leaves the name editable', async () => {
  const { h, finish } = await pendingPresetSave();
  try {
    assert.match(h.document.getElementById('page').textContent, /Saving preset/);
    assert.equal(layoutButton(h, 'Save preset').disabled, true);
    assert.equal(layoutButton(h, 'Discard changes').disabled, true);
    await h.input('Preset name', 'Editable');
    assert.equal(h.eval('presetDraft.name'), 'Editable');
  } finally { await finish(); }
});

test('preset save from Remote clears pending status and confirms the list', async () => {
  const {h,finish}=await pendingPresetSave({origin:"remote"});
  try {assert.equal(layoutButton(h,'Save preset').disabled,true);h.click('Save preset');await h.settle();assert.equal(presetPosts(h).length,1);} finally {await finish();}
  assert.equal(h.eval('presetDraft'),null);assert.match(h.document.getElementById('page').textContent,/Saved presets/);assert.doesNotMatch(h.document.getElementById('page').textContent,/Saving preset/);
});

test('next preset save submits preserved edits under the same identity', async () => {
  const { h, finish } = await pendingPresetSave();
  try { await h.input('Preset name', 'Next'); } finally { await finish(); }
  h.click('Save preset'); await h.settle();
  try { const body=JSON.parse(presetPosts(h).at(-1).opts.body);assert.equal(body.presets.length,1);assert.equal(body.presets[0].id,'existing');assert.equal(body.presets[0].name,'Next'); }
  finally { await finish(); }
  assert.equal(h.eval('presetDraft'),null);
});

test('preset save cannot overwrite an unavailable saved-preset list', async () => {
  const h=await browserHarness({saved,routes:{'/remote/presets':({opts,response})=>opts.method==='POST'?response({ok:true,presets:[]}):response({ok:false,error:'unavailable'},503)}});
  h.eval('go("home","presets")');await h.settle();h.click('New preset');await h.input('Preset name','Keep me');h.eval('presetDraft.steps=[{id:"step",step:{type:"wait",ms:100}}];render()');
  assert.equal(layoutButton(h,'Save preset').disabled,true);h.click('Save preset');await h.settle();assert.equal(presetPosts(h).length,0);assert.equal(h.eval('presetDraft.name'),'Keep me');
});

test('failed preset save retains newer draft and reenables its controls', async () => {
  const { h, finish } = await pendingPresetSave({ fail:true });
  try { await h.input('Preset name','Retry me'); } finally { await finish(); }
  assert.equal(h.eval('presetDraft?.name'),'Retry me');assert.equal(layoutButton(h,'Save preset').disabled,false);assert.equal(layoutButton(h,'Discard changes').disabled,false);
});

test('preset discard cannot clear the draft during pending save', async () => {
  const { h, finish } = await pendingPresetSave();
  try { h.click('Discard changes'); await h.settle();assert.equal(h.eval('presetDraft?.name'),'Submitted');assert.equal(h.document.getElementById('sheet').open,false); }
  finally { await finish(); }
});

test('preset discard requires confirmation and Cancel preserves the draft', async () => {
  const h=await browserHarness({saved});h.eval('go("home","presets")');await h.settle();h.click('New preset');await h.input('Preset name','Keep me');
  h.click('Discard changes');await h.settle();assert.equal(h.document.getElementById('sheet').open,true);h.click('Cancel');await h.settle();assert.equal(h.eval('presetDraft?.name'),'Keep me');
  h.click('Discard changes');await h.settle();h.click('Discard');await h.settle();assert.equal(h.eval('presetDraft'),null);
});

test('preset save acknowledgment explains that newer edits remain unsaved', async () => {
  const { h, finish }=await pendingPresetSave();try {await h.input('Preset name','Still working');} finally {await finish();}
  assert.match(h.document.body.textContent,/newer edits are still unsaved/i);
});


const reorderSteps = [
  { type: 'wait', ms: 100 },
  { type: 'config', target: 'screensaver', config: { nested: { retained: true } }, futureField: 'kept' },
  { type: 'media', action: 'next' },
];
async function reorderHarness(active = 'preset', origin = 'home') {
  const h = await browserHarness({ saved, routes: {
    '/remote/scenes': ({ response }) => response({ ok: true, revision: 'current', scenes: [{ id: 'scene', name: 'Scene', steps: reorderSteps, schedule: { enabled: true, hour: 8, minute: 30, days: [1, 3] } }] }),
    '/remote/presets': ({ response }) => response({ ok: true, presets: [{ id: 'preset', name: 'Preset', steps: reorderSteps }] }),
  } });
  const open = async (mode) => {
    h.eval(`go('${mode === 'scene' ? 'home' : origin}','${mode === 'scene' ? 'scenes' : 'presets'}')`);
    await h.settle(); h.click('Edit'); await h.settle();
  };
  await open(active === 'preset' ? 'scene' : 'preset'); await open(active);
  return h;
}
const draftJson = (h, mode) => h.eval(`JSON.stringify(${mode}Draft)`);
const stepTypes = (h, mode) => h.eval(`${mode}Draft.steps.map(s => s.step.type).join(',')`);
const reorderButtons = (h, arrow) => h.document.body.querySelectorAll('button').filter(b => b.textContent === arrow);

test('step reorder preset arrows work with a retained scene draft', async () => {
  const h = await reorderHarness(), inactive = draftJson(h, 'scene');
  reorderButtons(h, '↓')[0].click(); await h.settle();
  assert.equal(stepTypes(h, 'preset'), 'config,wait,media'); assert.equal(draftJson(h, 'scene'), inactive);
});

test('step reorder colliding wrapper IDs cannot reorder the inactive scene', async () => {
  const h = await reorderHarness(); h.eval('presetDraft.steps[0].id=sceneDraft.steps[0].id;render()');
  const inactive = draftJson(h, 'scene'); reorderButtons(h, '↓')[0].click(); await h.settle();
  assert.equal(stepTypes(h, 'preset'), 'config,wait,media'); assert.equal(draftJson(h, 'scene'), inactive);
});

test('step reorder scene arrows leave a retained preset untouched', async () => {
  const h = await reorderHarness('scene'), inactive = draftJson(h, 'preset');
  reorderButtons(h, '↑')[2].click(); await h.settle();
  assert.equal(stepTypes(h, 'scene'), 'wait,media,config'); assert.equal(draftJson(h, 'preset'), inactive);
});

test('step reorder up and down retain wrapper identity rich fields and schedule', async () => {
  const h = await reorderHarness('scene'), original = draftJson(h, 'scene');
  reorderButtons(h, '↓')[0].click(); await h.settle(); reorderButtons(h, '↑')[1].click(); await h.settle();
  assert.equal(draftJson(h, 'scene'), original);
});

test('step reorder a middle preset row moves exactly its own step', async () => {
  const h = await reorderHarness(); reorderButtons(h, '↓')[1].click(); await h.settle();
  assert.equal(stepTypes(h, 'preset'), 'wait,media,config');
  assert.equal(h.eval('presetDraft.steps[2].step.config.nested.retained'), true);
});

test('step reorder first and last boundaries do not mutate either draft', async () => {
  const h = await reorderHarness(), scene = draftJson(h, 'scene'), preset = draftJson(h, 'preset');
  assert.equal(reorderButtons(h, '↑')[0].disabled, true); assert.equal(reorderButtons(h, '↓')[2].disabled, true);
  h.eval('moveDraftStep(presetDraft.steps[0].id,-1,"preset");moveDraftStep(presetDraft.steps[2].id,1,"preset")'); await h.settle();
  assert.equal(draftJson(h, 'scene'), scene); assert.equal(draftJson(h, 'preset'), preset);
});

test('step reorder an unknown step cannot fall back to the other editor', async () => {
  const h = await reorderHarness(), scene = draftJson(h, 'scene'), preset = draftJson(h, 'preset');
  h.eval('moveDraftStep(sceneDraft.steps[0].id,1,"preset")'); await h.settle();
  assert.equal(draftJson(h, 'scene'), scene); assert.equal(draftJson(h, 'preset'), preset);
  assert.equal(h.calls.some(c => c.opts.method === 'POST'), false);
});

test('step reorder unavailable drafts unknown mode and invalid movement are harmless', async () => {
  const h = await browserHarness({ saved });
  assert.doesNotThrow(() => h.eval('moveDraftStep("gone",1,"scene");moveDraftStep("gone",1,"preset");moveDraftStep("gone",1,"unknown")'));
  const ready = await reorderHarness(), original = draftJson(ready, 'preset');
  ready.eval('moveDraftStep(presetDraft.steps[0].id,2,"preset");moveDraftStep(presetDraft.steps[0].id,0.5,"preset");moveDraftStep(presetDraft.steps[0].id,NaN,"preset");moveDraftStep(presetDraft.steps[0].id,1,"unknown")');
  assert.equal(draftJson(ready, 'preset'), original);
});

test('step reorder stale editor callbacks cannot edit drafts on another page', async () => {
  const h = await reorderHarness(), scene = draftJson(h, 'scene'), preset = draftJson(h, 'preset');
  h.eval('moveDraftStep(sceneDraft.steps[0].id,1,"scene");go("home");moveDraftStep(presetDraft.steps[0].id,1,"preset")'); await h.settle();
  assert.equal(draftJson(h, 'scene'), scene); assert.equal(draftJson(h, 'preset'), preset);
});

test('step reorder preset arrows work from the Remote entry point', async () => {
  const h = await reorderHarness('preset', 'remote'), inactive = draftJson(h, 'scene');
  reorderButtons(h, '↑')[1].click(); await h.settle();
  assert.equal(stepTypes(h, 'preset'), 'config,wait,media'); assert.equal(draftJson(h, 'scene'), inactive);
});


async function pendingSceneSave({ fail = false, unconfirmed = false } = {}) {
  let release, revision = sceneV1;
  let scenes = [scheduledScene(8), { id: 'other', name: 'Other scene', steps: [{ type: 'wait', ms: 100 }], schedule: null }];
  const h = await browserHarness({ saved, routes: {
    '/remote/scenes': ({ opts, response }) => opts.method === 'POST'
      ? new Promise(resolve => {
          const body = JSON.parse(opts.body);
          release = () => {
            if (fail) return resolve(response({ ok: false, error: 'stale_revision' }, 409));
            if (unconfirmed) return resolve(response({ ok: true, scenes: body.scenes }));
            scenes = body.scenes; revision = revision === sceneV1 ? sceneV2 : sceneV3;
            resolve(response({ ok: true, scenes, revision }));
          };
        })
      : response({ ok: true, scenes, revision }),
  } });
  h.eval('go("home","scenes")'); await h.settle(); h.click('Edit'); await h.settle();
  h.click('Save scene'); await h.settle();
  return { h, finish: async () => { release(); await h.settle(); } };
}
const scenePosts = h => h.calls.filter(c => c.path === '/remote/scenes' && c.opts.method === 'POST');

test('scene editor save retains a name changed after submission', async () => {
  const { h, finish } = await pendingSceneSave();
  try { await h.input('Scene name', 'Newer name'); } finally { await finish(); }
  assert.equal(h.eval('sceneDraft?.name'), 'Newer name');
  assert.equal(h.eval('data.scenes.find(s=>s.id==="routine").name'), 'Morning');
});

test('scene editor save retains later schedule time and weekday edits', async () => {
  const { h, finish } = await pendingSceneSave();
  try { await h.input('Time on Portal', '10:45'); h.eval('sceneDraft.schedule.days=[2,6];render()'); } finally { await finish(); }
  assert.equal(h.eval('sceneDraft?.schedule.hour'), 10); assert.equal(h.eval('sceneDraft?.schedule.minute'), 45);
  assert.equal(h.eval('sceneDraft?.schedule.days.join(",")'), '2,6');
  assert.equal(h.eval('data.scenes.find(s=>s.id==="routine").schedule.hour'), 8);
});

test('scene editor save captures submitted rich steps and retains newer steps', async () => {
  const { h, finish } = await pendingSceneSave();
  try { h.eval('sceneDraft.steps.push({id:"newer-step",step:{type:"settings",domain:"immortal",values:{themeMode:"dark"},futureField:{kept:true}}});render()'); } finally { await finish(); }
  assert.equal(h.eval('sceneDraft?.steps[1].id'), 'newer-step');
  assert.equal(h.eval('sceneDraft?.steps[1].step.futureField.kept'), true);
  assert.equal(JSON.parse(scenePosts(h)[0].opts.body).scenes.find(s=>s.id==='routine').steps.length, 1);
});

test('scene editor save keeps an unfinished step popup and its parent until Save step', async () => {
  const { h, finish } = await pendingSceneSave();
  h.click('Add step'); await h.settle(); await finish();
  assert.notEqual(h.eval('sceneDraft'), null); assert.equal(h.document.getElementById('sheet').open, true);
  h.click('Save step'); await h.settle(); assert.equal(h.eval('sceneDraft.steps.length'), 2);
  assert.equal(h.eval('sceneDraft.expectedRevision'), sceneV2);
});

test('scene editor save shows progress and blocks duplicate Save and pending Discard', async () => {
  const { h, finish } = await pendingSceneSave();
  try {
    assert.match(h.document.getElementById('page').textContent, /Saving scene/);
    assert.equal(layoutButton(h, 'Save scene').disabled, true); assert.equal(layoutButton(h, 'Discard changes').disabled, true);
    h.click('Save scene'); h.click('Discard changes'); await h.settle();
    assert.equal(scenePosts(h).length, 1); assert.notEqual(h.eval('sceneDraft'), null);
    assert.equal(h.document.getElementById('sheet').open, false);
  } finally { await finish(); }
});

test('scene editor next Save uses the confirmed revision base list and preserved edits', async () => {
  const { h, finish } = await pendingSceneSave();
  try { await h.input('Scene name', 'Next version'); } finally { await finish(); }
  assert.equal(h.eval('sceneDraft?.expectedRevision'), sceneV2);
  h.click('Save scene'); await h.settle();
  try {
    const body=JSON.parse(scenePosts(h).at(-1).opts.body);
    assert.equal(body.expectedRevision, sceneV2); assert.equal(body.scenes.find(s=>s.id==='routine').name, 'Next version');
    assert.equal(body.scenes.find(s=>s.id==='other').name, 'Other scene');
  } finally { await finish(); }
  assert.equal(h.eval('sceneDraft'), null); assert.equal(h.eval('data.scenesRevision'), sceneV3);
});

test('scene editor unchanged confirmed submission closes the draft', async () => {
  const { h, finish } = await pendingSceneSave(); await finish();
  assert.equal(h.eval('sceneDraft'), null); assert.equal(h.eval('data.scenesRevision'), sceneV2);
  assert.match(h.document.getElementById('page').textContent, /Scenes/);
});

test('scene editor failed Save keeps newer edits and re-enables Discard', async () => {
  const { h, finish } = await pendingSceneSave({ fail:true });
  try { await h.input('Scene name', 'Keep this'); } finally { await finish(); }
  assert.equal(h.eval('sceneDraft?.name'), 'Keep this'); assert.equal(h.eval('sceneDraft?.expectedRevision'), sceneV1);
  assert.equal(h.eval('sceneDraft?.conflict'), true); assert.equal(layoutButton(h,'Discard changes').disabled, false);
  assert.equal(layoutButton(h,'Save scene').disabled, true);
});

test('scene editor unconfirmed Save cannot close or rebase the retained draft', async () => {
  const { h, finish } = await pendingSceneSave({ unconfirmed:true }); await finish();
  assert.notEqual(h.eval('sceneDraft'), null); assert.equal(h.eval('sceneDraft.expectedRevision'), sceneV1);
  assert.equal(h.eval('data.scenesRevision'), sceneV1); assert.match(h.document.body.textContent, /did not confirm/i);
});

test('scene editor malformed confirmation is rejected without changing the last confirmed list', async () => {
  const h=await browserHarness({saved,routes:{'/remote/scenes':({opts,response})=>opts.method==='POST'
    ? response({ok:true,scenes:{invalid:true},revision:sceneV2}) : response({ok:true,scenes:[scheduledScene(8)],revision:sceneV1})}});
  h.eval('go("home","scenes")');await h.settle();h.click('Edit');await h.settle();
  await h.eval('saveSceneDraft()');await h.settle();
  assert.notEqual(h.eval('sceneDraft'), null); assert.equal(h.eval('data.scenesRevision'), sceneV1);
  assert.equal(h.eval('Array.isArray(data.scenes)'), true);
});

test('scene editor Discard asks for confirmation and Cancel retains every edit', async () => {
  const h=await browserHarness({saved,routes:{'/remote/scenes':({response})=>response({ok:true,scenes:[scheduledScene(8)],revision:sceneV1})}});
  h.eval('go("home","scenes")');await h.settle();h.click('Edit');await h.input('Scene name','Keep edits');
  const before=h.eval('JSON.stringify(sceneDraft)');h.click('Discard changes');await h.settle();
  assert.equal(h.document.getElementById('sheet').open,true);h.click('Cancel');await h.settle();assert.equal(h.eval('JSON.stringify(sceneDraft)'),before);
  h.click('Discard changes');await h.settle();h.click('Discard');await h.settle();assert.equal(h.eval('sceneDraft'),null);assert.equal(scenePosts(h).length,0);
});

test('scene editor a late Save cannot change another Portal draft or confirmed revision', async () => {
  const {h,finish}=await pendingSceneSave();
  try {h.eval(`selectRoom('${saved[1].base}')`);await h.settle();h.eval('go("home","scenes")');await h.settle();h.click('Edit');await h.input('Scene name','Other Portal');}
  finally {await finish();}
  assert.equal(h.eval('sceneDraft?.name'),'Other Portal');assert.equal(h.eval('sceneDraft?.expectedRevision'),sceneV1);
  assert.equal(h.eval('data.scenesRevision'),sceneV1);
});


async function pendingSourceSave({ kind='immich', fail=false, wrong=false, blank=false, holdRefresh=false }={}) {
  let release, releaseRead, reads=0;
  let sources={source:kind,immichUrl:'https://old.example.invalid',immichKey:'public-server-key',immichAlbumId:'',immichAlbumName:'',smbHost:'public-server',smbShare:'Photos',smbPath:'',smbUser:'fixture',smbPass:'public-server-pass',davUrl:'https://old.example.invalid/photos/',davUser:'fixture',davPass:'public-server-pass'};
  const h=await browserHarness({saved,routes:{'/remote/sources':({opts,response})=>{
    if(opts.method==='POST')return new Promise(resolve=>{const body=JSON.parse(opts.body);release=()=>{
      if(fail)return resolve(response({ok:false,error:'invalid_value'},400));
      sources={...sources,...body,source:wrong?'default':kind};resolve(response({ok:true,sources,calendar:{url:'old-calendar'}}));
    };});
    reads++;const snapshot=JSON.parse(JSON.stringify(sources));
    if(holdRefresh&&reads===2)return new Promise(resolve=>releaseRead=()=>resolve(response({ok:true,sources:snapshot,calendar:{url:'old-calendar'}})));
    return response({ok:true,sources:snapshot,calendar:{url:'old-calendar'}});
  }}});
  h.eval('advanced=true;go("photos","sources")');await h.settle();
  if(kind==='immich')await h.input('Server address','https://submitted.example.invalid');
  await h.input(kind==='immich'?'API key':'Password',blank?'':'public-submitted-credential');h.click('Activate source');await h.settle();
  return {h,finish:async()=>{release();await h.settle();},finishRead:async()=>{releaseRead();await h.settle();}};
}
const sourcePosts=h=>h.calls.filter(c=>c.path==='/remote/sources'&&c.opts.method==='POST');

test('source save preserves a newer Immich key entered while pending',async()=>{
 const {h,finish}=await pendingSourceSave();try{await h.input('API key','public-newer-key');}finally{await finish();}
 assert.equal(h.eval('draft("source:immichKey")'),'public-newer-key');assert.equal(JSON.parse(sourcePosts(h)[0].opts.body).immichKey,'public-submitted-credential');
});
test('source save preserves a newer network-folder password entered while pending',async()=>{
 const {h,finish}=await pendingSourceSave({kind:'smb'});try{await h.input('Password','public-newer-password');}finally{await finish();}
 assert.equal(h.eval('draft("source:smbPass")'),'public-newer-password');
});
test('source save preserves a newer WebDAV password entered while pending',async()=>{
 const {h,finish}=await pendingSourceSave({kind:'dav'});try{await h.input('Password','public-newer-password');}finally{await finish();}
 assert.equal(h.eval('draft("source:davPass")'),'public-newer-password');
});
test('source save retains newer endpoint edits and explains that they are unsaved',async()=>{
 const {h,finish}=await pendingSourceSave();try{await h.input('Server address','https://newer.example.invalid');}finally{await finish();}
 assert.equal(h.eval('draft("source:immichUrl")'),'https://newer.example.invalid');assert.equal(h.eval('data.sources.immichUrl'),'https://submitted.example.invalid');assert.match(h.document.body.textContent,/newer edits.*unsaved/i);
});
test('source save retains a different selected provider and its credential draft',async()=>{
 const {h,finish}=await pendingSourceSave();try{h.eval('setDraft("source:selected","smb");render()');await h.input('Password','public-next-provider-password');}finally{await finish();}
 assert.equal(h.eval('draft("source:selected")'),'smb');assert.equal(h.eval('draft("source:smbPass")'),'public-next-provider-password');assert.equal(h.eval('data.sources.source'),'immich');assert.match(h.document.body.textContent,/Active on Portal: Immich/);
});
test('source save clears only confirmed unchanged submitted credentials and strips server secrets',async()=>{
 const {h,finish}=await pendingSourceSave();await finish();assert.equal(h.eval('draft("source:immichKey")'),'');
 assert.equal(h.eval('data.sources.immichKeyConfigured'),true);assert.equal(h.eval('["immichKey","smbPass","davPass"].some(k=>Object.hasOwn(data.sources,k))'),false);
});
test('source save shows progress and prevents duplicate activation and pending preview',async()=>{
 const {h,finish}=await pendingSourceSave();try{assert.match(h.document.getElementById('page').textContent,/Saving source/);assert.equal(layoutButton(h,'Activate source').disabled,true);assert.equal(layoutButton(h,'Preview active source').disabled,true);h.click('Activate source');await h.settle();assert.equal(sourcePosts(h).length,1);}finally{await finish();}
 assert.equal(layoutButton(h,'Activate source').disabled,false);
});
test('source save failures keep newer credentials and allow a retry',async()=>{
 const {h,finish}=await pendingSourceSave({fail:true});try{await h.input('API key','public-retry-key');}finally{await finish();}
 assert.equal(h.eval('draft("source:immichKey")'),'public-retry-key');assert.equal(h.eval('data.sources.immichUrl'),'https://old.example.invalid');assert.equal(layoutButton(h,'Activate source').disabled,false);
});
test('source save an unconfirmed activation keeps the credential draft',async()=>{
 const {h,finish}=await pendingSourceSave({wrong:true});await finish();assert.equal(h.eval('draft("source:immichKey")'),'public-submitted-credential');assert.match(h.document.body.textContent,/not activated/i);assert.equal(h.eval('data.sources.source'),'immich');
});
test('source save uses its confirmed snapshot without another GET and rejects an older refresh',async()=>{
 const {h,finish,finishRead}=await pendingSourceSave({holdRefresh:true});const refresh=h.eval('loadSources()');await h.settle();h.eval('data.calendar={url:"newer-calendar"}');
 await finish();await finishRead();await refresh;
 assert.equal(h.calls.filter(c=>c.path==='/remote/sources'&&c.opts.method!=='POST').length,2);assert.equal(h.eval('data.sources.immichUrl'),'https://submitted.example.invalid');assert.equal(h.eval('data.calendar.url'),'newer-calendar');
});
test('source save keeps blank-secret retention semantics and sends no saved credential',async()=>{
 const {h,finish}=await pendingSourceSave({blank:true});await finish();assert.equal(Object.hasOwn(JSON.parse(sourcePosts(h)[0].opts.body),'immichKey'),false);assert.equal(h.eval('data.sources.immichKeyConfigured'),true);
});
test('source save a late old-Portal response cannot clear another Portal credential',async()=>{
 const {h,finish}=await pendingSourceSave();try{h.eval(`selectRoom('${saved[1].base}')`);await h.settle();h.eval('advanced=true;go("photos","sources")');await h.settle();await h.input('API key','public-other-room-key');}finally{await finish();}
 assert.equal(h.eval('draft("source:immichKey")'),'public-other-room-key');assert.equal(h.eval('data.sources.immichUrl'),'https://old.example.invalid');
});


async function albumQueryHarness({ reply='normal', held=true }={}) {
 let release, mode=reply;
 const source={source:'immich',immichUrl:'https://albums.example.invalid',immichKey:'public-stored-key',immichAlbumId:'kept-id',immichAlbumName:'Kept album'};
 const h=await browserHarness({saved,routes:{
  '/remote/sources':({opts,response})=>response({ok:true,sources:{...source,...(opts.method==='POST'?JSON.parse(opts.body):{})},calendar:{url:''}}),
  '/remote/immich/albums':({response})=>{
   const send=()=>mode==='failure'?response({ok:false,error:'immich_unreachable'},502):response({ok:true,albums:mode==='empty'?[]:mode==='object'?{}:mode==='bad-row'?[{id:'first',name:'First album',count:2},{id:'first',name:'Duplicate',count:1}]:[{id:'first',name:'First album',count:2}]});
   return held?new Promise(resolve=>release=()=>resolve(send())):send();
  }
 }});
 h.eval('advanced=true;go("photos","sources")');await h.settle();await h.input('API key','public-query-key');
 return {h,setReply:value=>mode=value,start:async()=>{
   const pending=h.eval('loadAlbums()');pending.catch(()=>{});await h.settle();
   return {finish:async()=>{release?.();await pending.catch(()=>{});await h.settle();}};
 }};
}
const albumPicker=h=>h.document.getElementById('page').querySelectorAll('select').find(n=>n.attrs['aria-label']==='Immich album');
const albumQueryPosts=h=>h.calls.filter(c=>c.path==='/remote/immich/albums');
const chooseAlbum=async(h,picker,id)=>{assert.ok(picker,'Album selector missing');picker.value=id;for(const listener of picker.listeners.change||[])listener({target:picker});picker.onchange?.({target:picker});await h.settle();};

test('album query ignores results for an edited server address',async()=>{
 const {h,start}=await albumQueryHarness();const {finish}=await start();try{await h.input('Server address','https://new-server.example.invalid');}finally{await finish();}
 assert.doesNotMatch(h.document.getElementById('page').textContent,/First album/);assert.equal(h.eval('draft("source:immichAlbumId")'),'kept-id');
});
test('album query ignores results for a replacement API key',async()=>{
 const {h,start}=await albumQueryHarness();const {finish}=await start();try{await h.input('API key','public-new-key');}finally{await finish();}
 assert.doesNotMatch(h.document.getElementById('page').textContent,/First album/);assert.equal(h.eval('draft("source:immichKey")'),'public-new-key');
});
test('album query cannot return an old provider list after provider navigation',async()=>{
 const {h,start}=await albumQueryHarness();const {finish}=await start();try{h.eval('setDraft("source:selected","smb");render()');}finally{await finish();}
 h.eval('setDraft("source:selected","immich");render()');assert.doesNotMatch(h.document.getElementById('page').textContent,/First album/);
});
test('album query cannot populate another Portal after a late response',async()=>{
 const {h,start}=await albumQueryHarness();const {finish}=await start();try{h.eval(`selectRoom('${saved[1].base}')`);await h.settle();h.eval('advanced=true;go("photos","sources")');await h.settle();}finally{await finish();}
 assert.doesNotMatch(h.document.getElementById('page').textContent,/First album/);
});
test('album query shows loading and prevents duplicate reads and selection while pending',async()=>{
 const {h,start}=await albumQueryHarness();const {finish}=await start();let input;try{
  assert.match(h.document.getElementById('page').textContent,/Loading albums/);assert.equal(layoutButton(h,'Loading albums…').disabled,true);assert.equal(albumPicker(h).disabled,true);h.click('Loading albums…');await h.settle();assert.equal(albumQueryPosts(h).length,1);
  input=h.document.getElementById('page').querySelectorAll('input').find(n=>n.attrs['aria-label']==='Album name (optional)');input.focus();for(const f of input.listeners.compositionstart||[])f();await h.input('Album name (optional)','Composed album name');
 }finally{await finish();}
 assert.equal(h.document.getElementById('page').querySelectorAll('input').find(n=>n.attrs['aria-label']==='Album name (optional)')===input,true);for(const f of input.listeners.compositionend||[])f();await h.tick(0);assert.equal(h.eval('draft("source:immichAlbumName")'),'Composed album name');assert.equal(layoutButton(h,'Load albums').disabled,false);
});
test('album query preserves a selected album omitted from the returned list',async()=>{
 const {h,start}=await albumQueryHarness();await (await start()).finish();const picker=albumPicker(h);
 assert.ok(picker.children.some(o=>o.value==='kept-id'||o.attrs.value==='kept-id'));assert.match(picker.textContent,/Kept album/);assert.equal(h.eval('draft("source:immichAlbumId")'),'kept-id');
});
test('album query distinguishes an empty library and still offers Whole library',async()=>{
 const {h,start}=await albumQueryHarness({reply:'empty'});await (await start()).finish();assert.match(h.document.getElementById('page').textContent,/No albums found/);assert.match(albumPicker(h).textContent,/Whole library/);
 await chooseAlbum(h,albumPicker(h),'');assert.equal(h.eval('draft("source:immichAlbumId")'),'');assert.equal(h.eval('draft("source:immichAlbumName")'),'');
});
test('album query failure keeps the selection and offers Whole library and retry',async()=>{
 const {h,start}=await albumQueryHarness({reply:'failure'});await (await start()).finish();assert.equal(h.eval('draft("source:immichAlbumId")'),'kept-id');assert.match(albumPicker(h).textContent,/Whole library/);assert.equal(layoutButton(h,'Try albums again').disabled,false);
});
test('album query failed refresh retains matching previous albums with truthful feedback',async()=>{
 const {h,start,setReply}=await albumQueryHarness({held:false});await (await start()).finish();setReply('failure');await (await start()).finish();assert.match(albumPicker(h).textContent,/First album/);assert.match(h.document.getElementById('page').textContent,/previously loaded albums/);
});
test('album query rejects a non-array response without poisoning the editor',async()=>{
 const {h,start}=await albumQueryHarness({reply:'object'});await (await start()).finish();assert.equal(layoutButton(h,'Try albums again').disabled,false);assert.equal(h.eval('draft("source:immichAlbumId")'),'kept-id');assert.match(albumPicker(h).textContent,/Whole library/);
});
test('album query rejects duplicate album identities and preserves the draft',async()=>{
 const {h,start}=await albumQueryHarness({reply:'bad-row'});await (await start()).finish();assert.equal(layoutButton(h,'Try albums again').disabled,false);assert.doesNotMatch(albumPicker(h).textContent,/Duplicate/);assert.equal(h.eval('draft("source:immichAlbumId")'),'kept-id');
});
test('album query an obsolete selector cannot overwrite the current connection draft',async()=>{
 const {h,start}=await albumQueryHarness({held:false});await (await start()).finish();const old=albumPicker(h),input=h.document.getElementById('page').querySelectorAll('input').find(n=>n.attrs['aria-label']==='Server address');input.focus();for(const f of input.listeners.compositionstart||[])f();await h.input('Server address','https://changed.example.invalid');assert.equal(h.document.getElementById('page').querySelectorAll('input').find(n=>n.attrs['aria-label']==='Server address')===input,true);for(const f of input.listeners.compositionend||[])f();await h.tick(0);await chooseAlbum(h,old,'first');assert.equal(h.eval('draft("source:immichAlbumId")'),'kept-id');assert.doesNotMatch(h.document.getElementById('page').textContent,/First album/);
});
test('album query confirmed source Save retains the verified list after submitted key clearing',async()=>{
 const {h,start}=await albumQueryHarness({held:false});await (await start()).finish();await h.eval('saveSource("immich",data.sources)');await h.settle();assert.equal(h.eval('draft("source:immichKey")'),'');assert.match(albumPicker(h).textContent,/First album/);assert.equal(albumQueryPosts(h).length,1);
});
test('album query explicit source refresh invalidates stored-credential album context',async()=>{
 const {h,start}=await albumQueryHarness({held:false});await (await start()).finish();await h.eval('loadSources()');await h.settle();assert.doesNotMatch(h.document.getElementById('page').textContent,/First album/);assert.equal(albumQueryPosts(h).length,1);assert.equal(h.eval('draft("source:immichAlbumId")'),'kept-id');
});


async function calendarEditor({ fail=false, wrong=false, holdRefresh=false, empty=false }={}) {
 let release,releaseRead,reads=0,failed=fail;
 const initial=empty?'':'https://calendar.example.invalid/old.ics';
 const h=await browserHarness({saved,routes:{'/remote/sources':({opts,response})=>{
  if(opts.method==='POST')return new Promise(resolve=>{const body=JSON.parse(opts.body);release=()=>{
   if(failed){failed=false;return resolve(response({ok:false,error:'invalid_value'},400));}
   resolve(response({ok:true,applied:['url'],calendar:{url:wrong?initial:body.calendarUrl,range:'week'},sources:{source:'default'}}));
  };});
  reads++;if(holdRefresh&&reads===2)return new Promise(resolve=>releaseRead=()=>resolve(response({ok:true,calendar:{url:initial},sources:{source:'default'}})));
  return response({ok:true,calendar:{url:initial,range:'week'},sources:{source:'default'}});
 }}});
 h.eval('go("photos","sources")');await h.settle();
 return {h,initial,start:async(value='https://calendar.example.invalid/submitted.ics')=>{await h.input('Calendar feed URL',value);h.click('Save calendar feed');await h.settle();},finish:async()=>{release?.();await h.settle();},finishRead:async()=>{releaseRead?.();await h.settle();}};
}
const calendarPosts=h=>sourcePosts(h).filter(c=>Object.hasOwn(JSON.parse(c.opts.body),'calendarUrl'));
const calendarInput=h=>h.document.body.querySelectorAll('input').find(n=>n.attrs['aria-label']==='Calendar feed URL');

test('calendar feed editor pending Save shows progress and blocks duplicate requests',async()=>{
 const t=await calendarEditor();await t.start();try{
  assert.match(t.h.document.getElementById('page').textContent,/Saving calendar feed/);
  assert.equal(layoutButton(t.h,'Save calendar feed').disabled,true);t.h.click('Save calendar feed');assert.equal(calendarPosts(t.h).length,1);
 }finally{await t.finish();}
});
test('calendar feed editor confirmed Save preserves newer URL and explains unsaved edits',async()=>{
 const t=await calendarEditor();await t.start();try{await t.h.input('Calendar feed URL','https://calendar.example.invalid/newer.ics');}finally{await t.finish();}
 assert.equal(calendarInput(t.h).value,'https://calendar.example.invalid/newer.ics');assert.match(t.h.document.getElementById('notice').textContent,/newer edits.*unsaved/i);
 assert.equal(t.h.eval('data.calendar.url'),'https://calendar.example.invalid/submitted.ics');
});
test('calendar feed editor Save does not overwrite independent photo-source state',async()=>{
 const t=await calendarEditor();await t.start();t.h.eval('data.sources={source:"immich",immichUrl:"https://new.example.invalid"};data.sourcesError={code:"unreachable"}');await t.finish();
 assert.equal(t.h.eval('data.sources.source'),'immich');assert.equal(t.h.eval('data.sources.immichUrl'),'https://new.example.invalid');assert.equal(t.h.eval('data.sourcesError.code'),'unreachable');
});
test('calendar feed editor confirmed Save invalidates older source read without extra GET',async()=>{
 const t=await calendarEditor({holdRefresh:true});t.h.eval('loadSources()');await t.h.settle();await t.start();await t.finish();await t.finishRead();
 assert.equal(t.h.eval('data.calendar.url'),'https://calendar.example.invalid/submitted.ics');assert.equal(t.h.calls.filter(c=>c.path==='/remote/sources'&&c.opts.method!=='POST').length,2);
});
test('calendar feed editor applied flag alone cannot confirm a different returned address',async()=>{
 const t=await calendarEditor({wrong:true});await t.start();await t.finish();assert.match(t.h.document.getElementById('notice').textContent,/not confirmed/i);
 assert.equal(t.h.eval('data.calendar.url'),t.initial);assert.equal(calendarInput(t.h).value,'https://calendar.example.invalid/submitted.ics');
});
test('calendar feed editor failed Save keeps newer draft and makes retry available',async()=>{
 const t=await calendarEditor({fail:true});await t.start();await t.h.input('Calendar feed URL','https://calendar.example.invalid/retry.ics');await t.finish();
 assert.equal(calendarInput(t.h).value,'https://calendar.example.invalid/retry.ics');assert.equal(layoutButton(t.h,'Save calendar feed').disabled,false);
 t.h.click('Save calendar feed');await t.h.settle();await t.finish();assert.equal(t.h.eval('data.calendar.url'),'https://calendar.example.invalid/retry.ics');
});
test('calendar feed editor late old-room Save cannot change another room calendar',async()=>{
 const t=await calendarEditor();await t.start();try{t.h.eval(`selectRoom('${saved[1].base}')`);await t.h.settle();t.h.eval('go("photos","sources")');await t.h.settle();await t.h.input('Calendar feed URL','https://calendar.example.invalid/other.ics');}finally{await t.finish();}
 assert.equal(calendarInput(t.h).value,'https://calendar.example.invalid/other.ics');assert.equal(t.h.eval('data.calendar.url'),t.initial);
});
test('calendar feed editor clearing saved feed requires confirmation and Cancel preserves it',async()=>{
 const t=await calendarEditor();await t.start('');try{
  assert.equal(t.h.document.getElementById('sheet').open,true);assert.equal(calendarPosts(t.h).length,0);assert.equal(t.h.document.getElementById('sheet-content').textContent.includes(t.h.eval('room().name')),true);
  t.h.click('Cancel');await t.h.settle();assert.equal(t.h.eval('data.calendar.url'),t.initial);assert.equal(calendarInput(t.h).value,'');
 }finally{await t.finish();}
});
test('calendar feed editor removal confirmation cannot remove a newly edited address',async()=>{
 const t=await calendarEditor();await t.start('');try{
  await t.h.input('Calendar feed URL','https://calendar.example.invalid/keep.ics');t.h.click('Remove feed');await t.h.settle();assert.equal(calendarPosts(t.h).length,0);assert.match(t.h.document.getElementById('notice').textContent,/changed.*review/i);
 }finally{await t.finish();}
});
test('calendar feed editor confirmed removal posts one blank URL and confirms returned removal',async()=>{
 const t=await calendarEditor();await t.start('');try{t.h.click('Remove feed');await t.h.settle();assert.equal(calendarPosts(t.h).length,1);}finally{await t.finish();}
 assert.equal(JSON.parse(calendarPosts(t.h)[0].opts.body).calendarUrl,'');assert.equal(t.h.eval('data.calendar.url'),'');assert.match(t.h.document.getElementById('notice').textContent,/removed.*confirmed/i);
});
test('calendar feed editor unchanged saved URL and empty feed need no POST or confirmation',async()=>{
 for(const empty of [false,true]){const t=await calendarEditor({empty});await t.start(t.initial);try{assert.equal(calendarPosts(t.h).length,0);assert.equal(t.h.document.getElementById('sheet').open,false);assert.match(t.h.document.getElementById('notice').textContent,/already saved/i);}finally{await t.finish();}}
});
test('calendar feed editor async Save completion preserves focused composing URL',async()=>{
 const t=await calendarEditor();await t.start();const input=calendarInput(t.h);input.focus();input.listeners.compositionstart?.forEach(fn=>fn());input.value='https://calendar.example.invalid/composing.ics';
 await t.finish();assert.equal(calendarInput(t.h)===input,true);assert.equal(t.h.document.activeElement===input,true);input.listeners.compositionend?.forEach(fn=>fn());await t.h.tick(0);
 assert.equal(calendarInput(t.h).value,'https://calendar.example.invalid/composing.ics');
});

// Public inventory fixtures cover recovery without installing or launching private apps.
const refreshApps = Array.from({ length: 30 }, (_, i) => ({
  packageName: `com.fixture.app${i}`, name: `Fixture app ${i}`, compatible: true,
  installed: true, updateAvailable: true,
}));
async function appRefreshHarness(mode = 'installed', route) {
  const h = await browserHarness({ saved, routes: {
    '/remote/apps': route || (({ response }) => response({ ok: true, apps: refreshApps })),
    '/remote/catalog': route || (({ response }) => response({ ok: true, apps: refreshApps })),
  } });
  h.eval(`appMode=${JSON.stringify(mode)};go("apps")`);
  await h.settle();
  return h;
}
const inventoryCalls = (h, key = 'apps') => h.calls.filter(c => c.path === `/remote/${key}`).length;
const inventoryList = h => h.document.getElementById('page').querySelector('.apps-list');
const pageText = h => h.document.getElementById('page').textContent;

test('Apps refresh failure retains confirmed inventory DOM and usable launch controls', async () => {
  let fail = false;
  const h = await appRefreshHarness('installed', ({ response }) => {
    if (fail) throw new Error('offline');
    return response({ ok: true, apps: refreshApps });
  });
  const list = inventoryList(h); fail = true;
  h.eval('loadApps()'); await h.settle();
  assert.equal(inventoryList(h) === list, true);
  assert.match(pageText(h), /last confirmed/i);
  assert.equal(h.document.getElementById('page').querySelectorAll('button').find(b => b.textContent === 'Open').disabled, false);
});

test('Apps pending refresh retains inventory and coalesces duplicate retries', async () => {
  let release, pending = false;
  const h = await appRefreshHarness('installed', ({ response }) => pending
    ? new Promise(resolve => { release = () => resolve(response({ ok: true, apps: refreshApps })); })
    : response({ ok: true, apps: refreshApps }));
  const list = inventoryList(h); pending = true; const before = inventoryCalls(h);
  try {
    h.eval('loadApps();loadApps()'); await h.settle();
    assert.equal(inventoryCalls(h), before + 1);
    assert.equal(inventoryList(h) === list, true);
    assert.match(pageText(h), /Refreshing apps/);
  } finally { release?.(); await h.settle(); }
});

test('Catalog pending refresh blocks retained update actions at dispatch boundary', async () => {
  let release, pending = false;
  const h = await appRefreshHarness('catalog', ({ response }) => pending
    ? new Promise(resolve => { release = () => resolve(response({ ok: true, apps: refreshApps })); })
    : response({ ok: true, apps: refreshApps }));
  const update = h.document.getElementById('page').querySelectorAll('button').find(b => b.textContent === 'Update');
  pending = true;
  try {
    h.eval('loadApps()'); await h.settle();
    assert.equal(update.disabled, true);
    update.listeners.click[0]({}); await h.settle();
    assert.equal(h.calls.filter(c => c.path === '/remote/operation').length, 0);
  } finally { release?.(); await h.settle(); }
});

test('Catalog failed refresh keeps cards while blocking stale install or update', async () => {
  let fail = false;
  const h = await appRefreshHarness('catalog', ({ response }) => {
    if (fail) throw new Error('offline'); return response({ ok: true, apps: refreshApps });
  });
  const list = inventoryList(h); fail = true;
  h.eval('loadApps()'); await h.settle();
  assert.equal(inventoryList(h) === list, true);
  assert.equal(h.document.getElementById('page').querySelectorAll('button').find(b => b.textContent === 'Update').disabled, true);
  assert.match(pageText(h), /Refresh the catalog before installing or updating/);
});

test('Catalog successful Retry replaces confirmed inventory and restores valid actions', async () => {
  let fail = false;
  const h = await appRefreshHarness('catalog', ({ response }) => {
    if (fail) throw new Error('offline'); return response({ ok: true, apps: refreshApps });
  });
  fail = true; h.eval('loadApps()'); await h.settle(); fail = false;
  h.click('Retry'); await h.settle();
  assert.equal(h.document.getElementById('page').querySelectorAll('button').find(b => b.textContent === 'Update').disabled, false);
  assert.equal(/last confirmed/i.test(pageText(h)), false);
  // Quiet installer completion may report identical metadata; actions must recover too.
  h.eval('loadApps({changedOnly:true})'); await h.settle();
  assert.equal(h.document.getElementById('page').querySelectorAll('button').find(b => b.textContent === 'Update').disabled, false);
});

test('malformed Apps response cannot replace a previously confirmed inventory', async () => {
  let malformed = false;
  const h = await appRefreshHarness('installed', ({ response }) => response({ ok: true, apps: malformed ? {} : refreshApps }));
  const list = inventoryList(h); malformed = true;
  h.eval('loadApps()'); await h.settle();
  assert.equal(inventoryList(h) === list, true);
  assert.match(pageText(h), /last confirmed/i);
});

test('initial Apps failure offers Retry and never represents it as an empty inventory', async () => {
  let fail = true;
  const h = await appRefreshHarness('installed', ({ response }) => {
    if (fail) throw new Error('offline'); return response({ ok: true, apps: [] });
  });
  assert.match(pageText(h), /Apps unavailable/);
  assert.equal(/No apps to show/.test(pageText(h)), false);
  fail = false; h.click('Retry'); await h.settle();
  assert.match(pageText(h), /No apps to show/);
});

test('reselecting Installed preserves pagination and sends no inventory request', async () => {
  const h = await appRefreshHarness(); h.click('Next');
  const before = inventoryCalls(h); const list = inventoryList(h);
  h.click('Installed'); await h.settle();
  assert.equal(inventoryCalls(h), before);
  assert.equal(h.eval('appPage'), 1);
  assert.equal(inventoryList(h) === list, true);
});

test('reselecting Catalog preserves the search and sends no inventory request', async () => {
  const h = await appRefreshHarness('catalog'); await h.input('Find an app', 'Fixture');
  const before = inventoryCalls(h, 'catalog'); h.click('Catalog'); await h.settle();
  assert.equal(inventoryCalls(h, 'catalog'), before);
  assert.equal(h.eval('appQuery'), 'Fixture');
});

test('late inventory refresh from another room cannot replace current apps', async () => {
  let release, pending = false;
  const h = await appRefreshHarness('installed', ({ url, response }) => pending && url.includes('8877')
    ? new Promise(resolve => { release = () => resolve(response({ ok: true, apps: [{ name: 'Old room only' }] })); })
    : response({ ok: true, apps: refreshApps }));
  pending = true;
  try {
    h.eval('loadApps()'); await h.settle(); h.eval(`selectRoom('${saved[1].base}')`); await h.settle();
    release(); await h.settle(); assert.equal(pageText(h).includes('Old room only'), false);
    assert.equal(/Refreshing apps/.test(pageText(h)), false);
  } finally { release?.(); await h.settle(); }
});

test('late Installed refresh cannot change the active Catalog collection', async () => {
  let release, pending = false;
  const h = await appRefreshHarness('installed', ({ url, response }) => pending && url.includes('/apps')
    ? new Promise(resolve => { release = () => resolve(response({ ok: true, apps: [{ name: 'Installed late' }] })); })
    : response({ ok: true, apps: refreshApps }));
  pending = true;
  try {
    h.eval('loadApps()'); await h.settle(); h.click('Catalog'); await h.settle();
    release(); await h.settle(); assert.equal(pageText(h).includes('Installed late'), false);
    assert.equal(/Refreshing apps/.test(pageText(h)), false);
  } finally { release?.(); await h.settle(); }
});

test('cancelled older Apps read cannot clear a newer refresh progress owner', async () => {
  let releases = [], pending = false;
  const h = await appRefreshHarness('installed', ({ response }) => pending
    ? new Promise(resolve => {
        const older = releases.length === 0;
        releases.push(() => resolve(response({ ok: true,
          apps: older ? [{ ...refreshApps[0], name: 'Pre-completion inventory', versionName: 'old' }] : refreshApps,
        })));
      })
    : response({ ok: true, apps: refreshApps }));
  pending = true;
  try {
    h.eval('data.operations=[{id:"public-completion",type:"update",target:{packageName:"com.fixture.app0"},state:"running"}]');
    h.eval('loadApps()'); await h.settle();
    // A completed installer requires a new post-completion inventory, not the older read.
    h.eval('acceptOperations({ok:true,operations:[{id:"public-completion",type:"update",target:{packageName:"com.fixture.app0"},state:"succeeded",results:[{ok:true,result:"installed"}]}]})');
    await h.settle(); assert.equal(releases.length, 2);
    releases[0](); await h.settle(); assert.match(pageText(h), /Refreshing apps/);
    assert.equal(pageText(h).includes('Pre-completion inventory'), false);
    releases[1](); await h.settle(); assert.equal(/Refreshing apps/.test(pageText(h)), false);
    assert.equal(h.eval('data.apps[0].name'), 'Fixture app 0');
  } finally { releases.forEach(release => release()); await h.settle(); }
});

const jarvisFixture = () => ({ supported: true, installed: true, model: 'model-a',
  availableModels: ['model-a', 'model-b', 'model-c'], wakeSupported: true,
  wakeEnabled: false, microphoneGranted: true, wakeListening: false, status: 'idle' });
async function jarvisSaveHarness(route) {
  const status = jarvisFixture();
  const h = await browserHarness({ saved, routes: {
    '/remote/jarvis': route || (({ response }) => response({ ok: true, jarvis: status })),
  } });
  h.eval('go("home","jarvis")'); await h.settle(); return h;
}
function jarvisModel(h, value) {
  const select = h.document.body.querySelectorAll('select').find(n => n.attrs['aria-label'] === 'Conversation model');
  assert.ok(select); select.value = value;
  for (const handler of select.listeners.change || []) handler({ target: select });
}
const jarvisSaveButton = h => h.document.body.querySelectorAll('button').find(b => b.textContent === 'Save Jarvis settings');
const jarvisPosts = h => h.calls.filter(c => c.path === '/remote/jarvis' && c.opts.method === 'POST');
const jarvisWake = h => h.document.body.querySelectorAll('input').find(n => n.attrs['aria-label'] === 'Allow background wake');

test('Jarvis unchanged settings reject a stale Save activation with zero POSTs', async () => {
  const h = await jarvisSaveHarness(); jarvisSaveButton(h).listeners.click[0]({}); await h.settle();
  assert.equal(jarvisPosts(h).length, 0);
});

test('Jarvis confirmed model Save submits only the changed reported model', async () => {
  let status = jarvisFixture();
  const h = await jarvisSaveHarness(({ opts, response }) => {
    if (opts.method === 'POST') status = { ...status, ...JSON.parse(opts.body) };
    return response({ ok: true, jarvis: status });
  });
  jarvisModel(h, 'model-b'); h.click('Save Jarvis settings'); await h.settle();
  assert.deepEqual(JSON.parse(jarvisPosts(h)[0].opts.body), { model: 'model-b' });
  assert.equal(h.eval('data.jarvis.model'), 'model-b');
  assert.equal(jarvisSaveButton(h).disabled, true);
  assert.match(h.document.getElementById('notice').textContent, /confirmed/);
});

test('Jarvis pending Save shows progress and prevents duplicate submissions', async () => {
  let release;
  const h = await jarvisSaveHarness(({ opts, response }) => opts.method === 'POST'
    ? new Promise(resolve => { release = () => resolve(response({ ok: true, jarvis: { ...jarvisFixture(), model: 'model-b' } })); })
    : response({ ok: true, jarvis: jarvisFixture() }));
  try {
    jarvisModel(h, 'model-b'); h.click('Save Jarvis settings'); await h.settle();
    assert.match(pageText(h), /Saving submitted Jarvis settings/);
    assert.equal(jarvisSaveButton(h).disabled, true);
    jarvisSaveButton(h).listeners.click[0]({}); await h.settle(); assert.equal(jarvisPosts(h).length, 1);
  } finally { release?.(); await h.settle(); }
});

test('Jarvis model edits made during Save survive confirmed readback', async () => {
  let release;
  const h = await jarvisSaveHarness(({ opts, response }) => opts.method === 'POST'
    ? new Promise(resolve => { release = () => resolve(response({ ok: true, jarvis: { ...jarvisFixture(), model: 'model-b' } })); })
    : response({ ok: true, jarvis: jarvisFixture() }));
  try {
    jarvisModel(h, 'model-b'); h.click('Save Jarvis settings'); await h.settle();
    jarvisModel(h, 'model-c'); release(); await h.settle();
    assert.equal(h.eval('draft("jarvis:model",data.jarvis.model)'), 'model-c');
    assert.equal(h.eval('data.jarvis.model'), 'model-b'); assert.equal(jarvisSaveButton(h).disabled, false);
    assert.match(h.document.getElementById('notice').textContent, /newer edits.*unsaved/);
  } finally { release?.(); await h.settle(); }
});

test('Jarvis newer wake edit survives a model-only Save while confirmed model draft retires', async () => {
  let release;
  const h = await jarvisSaveHarness(({ opts, response }) => opts.method === 'POST'
    ? new Promise(resolve => { release = () => resolve(response({ ok: true, jarvis: { ...jarvisFixture(), model: 'model-b' } })); })
    : response({ ok: true, jarvis: jarvisFixture() }));
  try {
    jarvisModel(h, 'model-b'); h.click('Save Jarvis settings'); await h.settle(); jarvisWake(h).click();
    release(); await h.settle();
    assert.equal(h.eval('draft("jarvis:wake",data.jarvis.wakeEnabled)'), true);
    assert.equal(h.eval('draft("jarvis:model",data.jarvis.model)'), 'model-b');
    assert.equal(jarvisSaveButton(h).disabled, false);
  } finally { release?.(); await h.settle(); }
});

test('Jarvis mismatched model acknowledgement cannot claim confirmation or discard draft', async () => {
  const h = await jarvisSaveHarness(({ response }) => response({ ok: true, jarvis: jarvisFixture() }));
  jarvisModel(h, 'model-b'); h.click('Save Jarvis settings'); await h.settle();
  assert.equal(h.eval('draft("jarvis:model",data.jarvis.model)'), 'model-b');
  assert.equal(h.eval('data.jarvis.model'), 'model-a'); assert.equal(jarvisSaveButton(h).disabled, false);
  assert.match(pageText(h), /did not confirm/);
});

test('Jarvis unsupported or incomplete acknowledgement preserves all submitted edits', async () => {
  const h = await jarvisSaveHarness(({ opts, response }) => response({ ok: true,
    jarvis: opts.method === 'POST' ? { supported: false, model: 'model-b' } : jarvisFixture() }));
  jarvisModel(h, 'model-b'); jarvisWake(h).click(); h.click('Save Jarvis settings'); await h.settle();
  assert.equal(h.eval('data.jarvis.supported'), true);
  assert.equal(h.eval('draft("jarvis:model",data.jarvis.model)'), 'model-b');
  assert.equal(h.eval('draft("jarvis:wake",data.jarvis.wakeEnabled)'), true);
  assert.match(pageText(h), /did not confirm/);
});

test('Jarvis transport failure keeps input and a later Save can confirm it', async () => {
  let fail = true;
  const h = await jarvisSaveHarness(({ opts, response }) => {
    if (opts.method === 'POST' && fail) throw new Error('offline');
    return response({ ok: true, jarvis: { ...jarvisFixture(), ...(opts.method === 'POST' ? JSON.parse(opts.body) : {}) } });
  });
  jarvisModel(h, 'model-b'); h.click('Save Jarvis settings'); await h.settle();
  assert.equal(h.eval('draft("jarvis:model",data.jarvis.model)'), 'model-b'); assert.equal(jarvisSaveButton(h).disabled, false);
  assert.match(pageText(h), /edits are kept/);
  fail = false; h.click('Save Jarvis settings'); await h.settle(); assert.equal(h.eval('data.jarvis.model'), 'model-b');
});

test('Jarvis older status GET cannot overwrite a successful Save readback', async () => {
  let release, defer = false;
  const h = await jarvisSaveHarness(({ opts, response }) => opts.method === 'POST'
    ? response({ ok: true, jarvis: { ...jarvisFixture(), model: 'model-b' } })
    : defer ? new Promise(resolve => { release = () => resolve(response({ ok: true, jarvis: jarvisFixture() })); })
      : response({ ok: true, jarvis: jarvisFixture() }));
  try {
    jarvisModel(h, 'model-b'); defer = true; h.eval('loadVisible()'); await h.settle();
    h.click('Save Jarvis settings'); await h.settle(); release(); await h.settle();
    assert.equal(h.eval('data.jarvis.model'), 'model-b');
  } finally { release?.(); await h.settle(); }
});

test('Jarvis late Save cannot clear another room draft or replace its status', async () => {
  let release;
  const h = await jarvisSaveHarness(({ url, opts, response }) => opts.method === 'POST'
    ? new Promise(resolve => { release = () => resolve(response({ ok: true, jarvis: { ...jarvisFixture(), model: 'model-b' } })); })
    : response({ ok: true, jarvis: { ...jarvisFixture(), model: url.includes('8878') ? 'model-c' : 'model-a' } }));
  try {
    jarvisModel(h, 'model-b'); h.click('Save Jarvis settings'); await h.settle();
    h.eval(`selectRoom('${saved[1].base}')`); await h.settle(); h.eval('go("home","jarvis")'); await h.settle(); jarvisModel(h, 'model-a');
    release(); await h.settle(); assert.equal(h.eval('data.jarvis.model'), 'model-c');
    assert.equal(h.eval('draft("jarvis:model",data.jarvis.model)'), 'model-a');
  } finally { release?.(); await h.settle(); }
});

test('Jarvis unavailable edited model stays visible and cannot submit an empty request', async () => {
  let status = jarvisFixture();
  const h = await jarvisSaveHarness(({ response }) => response({ ok: true, jarvis: status }));
  jarvisModel(h, 'model-b'); status = { ...status, availableModels: ['model-a'] };
  h.eval('loadVisible()'); await h.settle();
  const select = h.document.body.querySelectorAll('select').find(n => n.attrs['aria-label'] === 'Conversation model');
  assert.equal(select.querySelectorAll('option').some(n => n.value === 'model-b'), true);
  assert.equal(jarvisSaveButton(h).disabled, true);
  jarvisSaveButton(h).listeners.click[0]({}); await h.settle(); assert.equal(jarvisPosts(h).length, 0);
});

test('Jarvis missing model list preserves current model and allows supported wake-only Save', async () => {
  let status = { ...jarvisFixture(), availableModels: {}, models: null };
  const h = await jarvisSaveHarness(({ opts, response }) => {
    if (opts.method === 'POST') status = { ...status, ...JSON.parse(opts.body) };
    return response({ ok: true, jarvis: status });
  });
  assert.match(pageText(h), /model-a/);
  jarvisWake(h).click(); h.click('Save Jarvis settings'); await h.settle();
  assert.deepEqual(JSON.parse(jarvisPosts(h)[0].opts.body), { wakeEnabled: true });
  assert.equal(h.eval('data.jarvis.wakeEnabled'), true);
});


const restoreSnapshotFixture = () => ({ schemaVersion: 1, deviceId: 'fixture-device',
  revision: 'exported-revision', domains: {},
  layout: { assignments: {}, homeOrder: [], gridOrder: [], gridSlots: [], widgets: [] } });
async function restoreReviewHarness() {
  const state = { read: null, post: null }, snapshot = restoreSnapshotFixture();
  const h = await browserHarness({ saved, routes: { '/remote/snapshot': context => {
    if (context.opts.method === 'POST') return state.post ? state.post(context)
      : context.response({ ok: true, snapshot });
    return state.read ? state.read(context) : context.response({ ok: true,
      snapshot: { ...snapshot, revision: 'fresh-revision' } });
  } } });
  h.eval('go("home","snapshots")'); await h.settle();
  return { h, state, snapshot };
}
const restoreReviewButton = h => h.document.body.querySelectorAll('button')
  .find(node => node.textContent === 'Review restore');
const restoreFileInput = h => h.document.body.querySelectorAll('input')
  .find(node => node.attrs['aria-label'] === 'Choose snapshot JSON');
function chooseRestoreFile(h, file) {
  const input = restoreFileInput(h); input.files = file ? [file] : [];
  for (const listener of input.listeners.change || []) listener({ target: input });
}
const restoreFile = (name = 'first.json', contents = restoreSnapshotFixture()) => ({
  name, size: 256, text: async () => JSON.stringify(contents),
});
const snapshotGets = h => h.calls.filter(c => c.path === '/remote/snapshot' && c.opts.method !== 'POST');
const snapshotPosts = h => h.calls.filter(c => c.path === '/remote/snapshot' && c.opts.method === 'POST');
const forceRestoreReview = h => restoreReviewButton(h).listeners.click[0]();

test('Snapshot review needs a file and sends no request for a stale raw activation', async () => {
  const { h } = await restoreReviewHarness(), before = snapshotGets(h).length;
  assert.equal(restoreReviewButton(h).disabled, true);
  forceRestoreReview(h); await h.settle();
  assert.equal(snapshotGets(h).length, before); assert.equal(snapshotPosts(h).length, 0);
});
test('Snapshot review file progress blocks duplicate file reads', async () => {
  const { h } = await restoreReviewHarness(); let reads = 0; const releases = [];
  chooseRestoreFile(h, { name: 'first.json', size: 256, text: () => { reads++;
    return new Promise(resolve => releases.push(resolve)); } });
  h.click('Review restore'); forceRestoreReview(h); await h.settle();
  const observed = { reads, disabled: restoreReviewButton(h).disabled,
    progress: h.document.body.textContent.includes('Reading snapshot file') };
  for (const resolve of releases) resolve(JSON.stringify(restoreSnapshotFixture())); await h.settle();
  assert.deepEqual(observed, { reads: 1, disabled: true, progress: true });
});
test('Snapshot review revision progress coalesces duplicate preflight requests', async () => {
  const { h, state } = await restoreReviewHarness(), releases = [], before = snapshotGets(h).length;
  state.read = c => new Promise(resolve => releases.push(() => resolve(c.response({ ok: true,
    snapshot: { revision: 'fresh-revision' } }))));
  chooseRestoreFile(h, restoreFile()); h.click('Review restore'); await h.settle(); forceRestoreReview(h);
  await h.settle(); const observed = { gets: snapshotGets(h).length - before,
    disabled: restoreReviewButton(h).disabled,
    progress: h.document.body.textContent.includes('Reading the current device revision') };
  for (const resolve of releases) resolve(); await h.settle();
  assert.deepEqual(observed, { gets: 1, disabled: true, progress: true });
});
test('Snapshot review changing file while parsing suppresses the old confirmation', async () => {
  const { h } = await restoreReviewHarness(); let release;
  chooseRestoreFile(h, { name: 'old.json', size: 256, text: () => new Promise(resolve => release = resolve) });
  const before = snapshotGets(h).length; h.click('Review restore'); await h.settle();
  chooseRestoreFile(h, restoreFile('new.json')); release(JSON.stringify(restoreSnapshotFixture())); await h.settle();
  assert.equal(snapshotGets(h).length, before); assert.equal(h.document.getElementById('sheet').open, false);
  h.click('Review restore'); await h.settle(); assert.match(h.document.getElementById('sheet-content').textContent, /new\.json/);
});
test('Snapshot review changing file aborts the old revision read and owns only the new confirmation', async () => {
  const { h, state } = await restoreReviewHarness(); let release, signal;
  state.read = c => { signal = c.opts.signal; return new Promise(resolve => release = () => resolve(c.response({ ok: true,
    snapshot: { revision: 'old-revision' } }))); };
  chooseRestoreFile(h, restoreFile('old.json')); h.click('Review restore'); await h.settle();
  chooseRestoreFile(h, restoreFile('new.json')); const aborted = signal.aborted; state.read = null;
  h.click('Review restore'); await h.settle(); release(); await h.settle();
  assert.equal(aborted, true); assert.match(h.document.getElementById('sheet-content').textContent, /new\.json/);
  assert.equal(h.document.getElementById('sheet-content').textContent.includes('old.json'), false);
});
test('Snapshot review an old parse error cannot replace a newer selected file status', async () => {
  const { h } = await restoreReviewHarness(); let reject;
  chooseRestoreFile(h, { name: 'old.json', size: 256, text: () => new Promise((_, r) => reject = r) });
  h.click('Review restore'); await h.settle(); chooseRestoreFile(h, restoreFile('new.json'));
  reject(new SyntaxError('fixture')); await h.settle();
  assert.equal(h.document.body.textContent.includes('not valid snapshot JSON'), false);
  assert.equal(h.document.body.textContent.includes('new.json'), true);
});
test('Snapshot review ordinary page redraw keeps the selected file and current progress', async () => {
  const { h } = await restoreReviewHarness(); let release;
  chooseRestoreFile(h, { name: 'selected.json', size: 256, text: () => new Promise(resolve => release = resolve) });
  h.click('Review restore'); await h.settle(); h.eval('render()');
  const observed = { name: h.document.body.textContent.includes('selected.json'),
    progress: h.document.body.textContent.includes('Reading snapshot file'), disabled: restoreReviewButton(h).disabled };
  release(JSON.stringify(restoreSnapshotFixture())); await h.settle();
  assert.deepEqual(observed, { name: true, progress: true, disabled: true });
});
test('Snapshot review malformed JSON keeps the selection and supports a clean retry', async () => {
  const { h } = await restoreReviewHarness(); let invalid = true;
  chooseRestoreFile(h, { name: 'retry.json', size: 256, text: async () => invalid ? '{bad' : JSON.stringify(restoreSnapshotFixture()) });
  h.click('Review restore'); await h.settle(); assert.match(h.document.body.textContent, /not valid snapshot JSON/);
  h.eval('render()'); assert.equal(h.document.body.textContent.includes('retry.json'), true);
  invalid = false; h.click('Review restore'); await h.settle();
  assert.equal(h.document.getElementById('sheet').open, true); assert.equal(h.document.body.textContent.includes('not valid snapshot JSON'), false);
});
test('Snapshot review invalid snapshot shapes are rejected before a device revision request', async () => {
  for (const candidate of [null, [], { ...restoreSnapshotFixture(), domains: [] }, { ...restoreSnapshotFixture(), layout: [] }]) {
    const { h } = await restoreReviewHarness(), before = snapshotGets(h).length;
    chooseRestoreFile(h, restoreFile('invalid.json', candidate)); h.click('Review restore'); await h.settle();
    assert.equal(snapshotGets(h).length, before); assert.equal(h.document.getElementById('sheet').open, false);
    assert.equal(restoreReviewButton(h).disabled, false);
    assert.equal(h.document.body.textContent.includes('invalid.json'), true);
    assert.equal(h.document.body.textContent.includes('supported Immortal snapshot'), true);
  }
  const { h } = await restoreReviewHarness(), before = snapshotGets(h).length;
  chooseRestoreFile(h, { ...restoreFile('large.json'), size: 240 * 1024 + 1 });
  h.click('Review restore'); await h.settle();
  assert.equal(snapshotGets(h).length, before);
  assert.equal(h.document.body.textContent.includes('240 KiB or smaller'), true);
});
test('Snapshot review failed revision read keeps the selected file for retry', async () => {
  const { h, state } = await restoreReviewHarness(); state.read = c => c.response({ ok: false, error: 'ipc_failed' }, 503);
  chooseRestoreFile(h, restoreFile('retry.json')); h.click('Review restore'); await h.settle(); h.eval('render()');
  assert.equal(h.document.body.textContent.includes('retry.json'), true); assert.equal(restoreReviewButton(h).disabled, false);
  state.read = null; h.click('Review restore'); await h.settle();
  assert.equal(h.document.getElementById('sheet').open, true); assert.equal(snapshotPosts(h).length, 0);
});
test('Snapshot review file changes invalidate its existing confirmation before any restore POST', async () => {
  const { h } = await restoreReviewHarness(); chooseRestoreFile(h, restoreFile('old.json'));
  h.click('Review restore'); await h.settle();
  const staleRestore = h.document.getElementById('sheet-actions').querySelectorAll('button')
    .find(node => node.textContent === 'Restore snapshot');
  chooseRestoreFile(h, restoreFile('new.json')); staleRestore.listeners.click[0](); await h.settle();
  assert.equal(snapshotPosts(h).length, 0); assert.equal(h.document.body.textContent.includes('new.json'), true);
});
test('Snapshot review navigation clears selection and drops late revision success and errors', async () => {
  for (const destination of ['go("home")', `selectRoom('${saved[1].base}')`]) {
    const { h, state } = await restoreReviewHarness(); let reject, signal;
    state.read = c => { signal = c.opts.signal; return new Promise((_, r) => reject = r); };
    chooseRestoreFile(h, restoreFile()); h.click('Review restore'); await h.settle(); h.eval(destination);
    reject(new Error('old revision failure')); await h.settle(); assert.equal(signal.aborted, true);
    state.read = null; h.eval('go("home","snapshots")'); await h.settle();
    assert.equal(restoreReviewButton(h).disabled, true); assert.equal(h.document.getElementById('sheet').open, false);
    assert.equal(h.document.body.textContent.includes('old revision failure'), false); assert.equal(snapshotPosts(h).length, 0);
  }
});


// Pair requests and discovery belong to the visible form, not merely an open shared sheet.
async function pairingFixture({ discovery = false, fail = false } = {}) {
  const pairs = [], discoveries = [];
  const h = await browserHarness({ saved, routes: {
    '/remote/pair': ({ opts, response }) => new Promise(resolve => pairs.push({ opts,
      finish: () => resolve(fail ? response({ ok: false, error: 'bad_pin' }, 400)
        : response({ ok: true, token: 'new-public-fixture-token', name: 'New fixture Portal' })) })),
    '/remote/devices': ({ opts, response }) => discovery
      ? new Promise(resolve => discoveries.push({ opts, finish: () => resolve(response({ ok: true,
        devices: [{ host: '127.0.0.1', port: 8899, name: 'Discovered fixture' }] })) }))
      : response({ ok: true, devices: [] }),
  } });
  h.eval('pairDialog("http://127.0.0.1:8890", "123456")'); await h.settle();
  return { h, pairs, discoveries };
}
const pairInputs = h => h.document.getElementById('sheet-content').querySelectorAll('input');
const pairSubmit = h => h.document.getElementById('sheet-actions').querySelectorAll('button')
  .find(b => /Pair Portal|Pairing/.test(b.textContent));

test('Pair dialog closing aborts its pending request and cannot pair after reopening', async () => {
  const { h, pairs } = await pairingFixture(); h.click('Pair Portal'); await h.settle();
  h.eval('closeSheet();pairDialog("http://127.0.0.1:8891", "654321")'); await h.settle();
  assert.equal(pairs[0].opts.signal.aborted, true); pairs[0].finish(); await h.settle();
  assert.equal(h.eval('roster.some(r=>r.base==="http://127.0.0.1:8890")'), false);
  assert.equal(h.document.getElementById('sheet').open, true);
  assert.equal(pairInputs(h)[0].value, 'http://127.0.0.1:8891');
});
test('Pair dialog replacement aborts its pending request without closing the replacement', async () => {
  const { h, pairs } = await pairingFixture(); h.click('Pair Portal'); await h.settle();
  h.eval('roomsDialog()'); await h.settle(); assert.equal(pairs[0].opts.signal.aborted, true);
  pairs[0].finish(); await h.settle(); assert.equal(h.document.getElementById('sheet-title').textContent, 'Your Portals');
  assert.equal(h.eval('roster.length'), 2);
});
test('Pair dialog progress disables submission and both inputs while retaining the address', async () => {
  const { h } = await pairingFixture(); h.click('Pair Portal'); await h.settle();
  assert.equal(pairSubmit(h).disabled, true); assert.match(pairSubmit(h).textContent, /Pairing/);
  assert.equal(pairInputs(h).every(input => input.disabled), true);
  assert.equal(pairInputs(h)[0].value, 'http://127.0.0.1:8890');
});
test('Pair dialog raw form resubmission shares one pending pairing request', async () => {
  const { h, pairs } = await pairingFixture(); h.click('Pair Portal'); await h.settle();
  const form = h.document.getElementById('sheet-content').querySelector('form');
  for (const fn of form.listeners.submit) fn({ preventDefault() {} }); await h.settle();
  assert.equal(pairs.length, 1); assert.equal(pairSubmit(h).disabled, true);
});
test('Pair dialog failure restores editable controls and retains address for retry', async () => {
  const { h, pairs } = await pairingFixture({ fail: true }); h.click('Pair Portal'); await h.settle();
  pairs[0].finish(); await h.settle(); assert.equal(pairSubmit(h).disabled, false);
  assert.equal(pairSubmit(h).textContent, 'Pair Portal'); assert.equal(pairInputs(h).every(input => !input.disabled), true);
  assert.equal(pairInputs(h)[0].value, 'http://127.0.0.1:8890'); assert.equal(pairInputs(h)[1].value, '');
  await h.input('Six-digit PIN', '654321'); h.click('Pair Portal'); await h.settle();
  assert.equal(pairs.length, 2); assert.equal(pairSubmit(h).disabled, true);
  assert.equal(JSON.parse(pairs[1].opts.body).pin, '654321');
});
test('Pair dialog discovery is aborted when the form closes', async () => {
  const { h, discoveries } = await pairingFixture({ discovery: true }); h.eval('closeSheet()');
  assert.equal(discoveries[0].opts.signal.aborted, true);
});
test('Pair dialog replacement drops old discovery and leaves only its own refresh timer', async () => {
  const { h, discoveries } = await pairingFixture({ discovery: true });
  h.eval('pairDialog("http://127.0.0.1:8891", "654321")'); await h.settle();
  assert.equal(discoveries[0].opts.signal.aborted, true); discoveries[0].finish(); await h.settle();
  assert.equal(h.eval('pairDiscoveryTimer'), null);
  discoveries[1].finish(); await h.settle(); assert.notEqual(h.eval('pairDiscoveryTimer'), null);
  const tick = h.tick(10000); await h.settle(); assert.equal(discoveries.length, 3);
  discoveries[2].finish(); await tick;
});
test('Pair dialog beginning Pair aborts discovery so late peers cannot edit the pending address', async () => {
  const { h, discoveries } = await pairingFixture({ discovery: true }); h.click('Pair Portal'); await h.settle();
  assert.equal(discoveries[0].opts.signal.aborted, true); discoveries[0].finish(); await h.settle();
  assert.equal(h.document.getElementById('sheet-content').textContent.includes('Discovered fixture'), false);
  assert.equal(pairInputs(h)[0].disabled, true);
});
test('Pair dialog already discovered peers are disabled while pairing is pending', async () => {
  const { h, discoveries } = await pairingFixture({ discovery: true }); discoveries[0].finish(); await h.settle();
  const peer = h.document.getElementById('sheet-content').querySelectorAll('button')
    .find(b => b.textContent === 'Discovered fixture');
  h.click('Pair Portal'); await h.settle(); assert.equal(peer.disabled, true);
  for (const handler of peer.listeners.click) handler({ target: peer });
  assert.equal(pairInputs(h)[0].value, 'http://127.0.0.1:8890');
});
test('Pair dialog page exit aborts pairing and a late success cannot change the roster', async () => {
  const { h, pairs } = await pairingFixture(); h.click('Pair Portal'); await h.settle();
  h.eval('go("apps")'); await h.settle(); assert.equal(pairs[0].opts.signal.aborted, true);
  pairs[0].finish(); await h.settle(); assert.equal(h.eval('roster.length'), 2);
});
test('Pair dialog a current successful request pairs only the captured address once', async () => {
  const { h, pairs } = await pairingFixture(); h.click('Pair Portal'); await h.settle();
  assert.equal(pairSubmit(h).disabled, true); pairs[0].finish(); await h.settle();
  assert.equal(h.eval('roster.filter(r=>r.base==="http://127.0.0.1:8890").length'), 1);
  assert.equal(h.eval('room().base'), 'http://127.0.0.1:8890');
  assert.equal(h.document.getElementById('sheet').open, false);
});
test('Pair dialog stale pairing failure cannot remove a new dialog PIN or restart old discovery', async () => {
  const { h, pairs } = await pairingFixture({ fail: true }); h.click('Pair Portal'); await h.settle();
  h.eval('pairDialog("http://127.0.0.1:8891", "654321")'); await h.settle();
  assert.equal(pairs[0].opts.signal.aborted, true); pairs[0].finish(); await h.settle();
  assert.equal(pairInputs(h)[1].value, '654321'); assert.equal(pairSubmit(h).disabled, false);
  assert.equal(h.document.getElementById('sheet-content').textContent.includes('PIN'), true);
});


// Shared confirmation callers must finish when their specific prompt goes away.
const confirmationButton = (h, label) => h.document.getElementById('sheet-actions')
  .querySelectorAll('button').find(button => button.textContent === label);
function startConfirmation(h) {
  h.eval('globalThis.confirmedCount=0;globalThis.confirmationSettled=false;globalThis.confirmationResult=confirmAction("Old action?","Public fixture",()=>{confirmedCount++},"Do it").then(()=>{confirmationSettled=true})');
}
const rawClick = button => { for (const handler of button.listeners.click || []) handler({target:button}); };

test('Confirmation replacement settles the old caller without dispatching its action', async () => {
  const h=await browserHarness({saved});startConfirmation(h);h.eval('roomsDialog()');await h.settle();
  assert.equal(h.eval('confirmationSettled'),true);assert.equal(h.eval('confirmedCount'),0);
  assert.equal(h.document.getElementById('sheet-title').textContent,'Your Portals');
});
test('Confirmation close settles before the asynchronous browser close event', async () => {
  const h=await browserHarness({saved});startConfirmation(h);
  h.eval('sheet.close=function(){this.open=false};closeSheet()');await h.settle();
  assert.equal(h.eval('confirmationSettled'),true);assert.equal(h.eval('confirmedCount'),0);
});
test('Confirmation stale confirm callback cannot dispatch or close a replacement', async () => {
  const h=await browserHarness({saved});startConfirmation(h);const old=confirmationButton(h,'Do it');
  h.eval('roomsDialog()');rawClick(old);await h.settle();
  assert.equal(h.eval('confirmedCount'),0);assert.equal(h.document.getElementById('sheet').open,true);
  assert.equal(h.document.getElementById('sheet-title').textContent,'Your Portals');
});
test('Confirmation stale cancel callback cannot dismiss another live prompt', async () => {
  const h=await browserHarness({saved});startConfirmation(h);const old=confirmationButton(h,'Cancel');
  h.eval('confirmAction("New action?","Public fixture",()=>{confirmedCount++},"New action")');rawClick(old);await h.settle();
  assert.equal(h.document.getElementById('sheet').open,true);assert.equal(h.eval('confirmedCount'),0);
  h.click('New action');await h.settle();assert.equal(h.eval('confirmedCount'),1);
});
test('Confirmation accepted action dispatches once and its replacement survives duplicate activation', async () => {
  const h=await browserHarness({saved});h.eval('globalThis.confirmedCount=0;confirmAction("Open rooms?","Public fixture",()=>{confirmedCount++;roomsDialog()},"Do it")');
  const old=confirmationButton(h,'Do it');h.click('Do it');await h.settle();rawClick(old);await h.settle();
  assert.equal(h.eval('confirmedCount'),1);assert.equal(h.document.getElementById('sheet').open,true);
  assert.equal(h.document.getElementById('sheet-title').textContent,'Your Portals');
});
test('Confirmation native dismissal settles and a retired callback cannot close a new dialog', async () => {
  const h=await browserHarness({saved});startConfirmation(h);const old=confirmationButton(h,'Do it');
  h.eval('sheet.close();roomsDialog()');rawClick(old);await h.settle();
  assert.equal(h.eval('confirmationSettled'),true);assert.equal(h.eval('confirmedCount'),0);
  assert.equal(h.document.getElementById('sheet').open,true);
});
test('Confirmation explicit Cancel settles without dispatch and permits a fresh confirmation', async () => {
  const h=await browserHarness({saved});startConfirmation(h);h.click('Cancel');await h.settle();
  assert.equal(h.eval('confirmationSettled'),true);assert.equal(h.eval('confirmedCount'),0);
  startConfirmation(h);h.click('Do it');await h.settle();assert.equal(h.eval('confirmedCount'),1);
});
test('Confirmation page change retires captured callbacks without affecting a new prompt', async () => {
  const h=await browserHarness({saved});startConfirmation(h);const old=confirmationButton(h,'Do it');
  h.eval('go("apps");roomsDialog()');rawClick(old);await h.settle();
  assert.equal(h.eval('confirmedCount'),0);assert.equal(h.eval('confirmationSettled'),true);
  assert.equal(h.document.getElementById('sheet').open,true);
});
test('Confirmation Portal change retires captured callbacks without affecting a new prompt', async () => {
  const h=await browserHarness({saved});startConfirmation(h);const old=confirmationButton(h,'Do it');
  h.eval(`selectRoom('${saved[1].base}');roomsDialog()`);rawClick(old);await h.settle();
  assert.equal(h.eval('confirmedCount'),0);assert.equal(h.eval('confirmationSettled'),true);
  assert.equal(h.document.getElementById('sheet').open,true);
});
test('Confirmation replacement clears Snapshot review progress and permits retry with its selected file', async () => {
  const {h}=await restoreReviewHarness();chooseRestoreFile(h,restoreFile());h.click('Review restore');await h.settle();
  assert.equal(restoreReviewButton(h).disabled,true);h.eval('roomsDialog()');await h.settle();
  assert.equal(restoreReviewButton(h).disabled,false);assert.match(h.document.getElementById('page').textContent,/first.json/);
  assert.equal(snapshotPosts(h).length,0);h.eval('closeSheet()');h.click('Review restore');await h.settle();
  assert.equal(h.document.getElementById('sheet-title').textContent,'Restore to Preview room?');
});


const historyOperation = (id='history-one', extras={}) => ({id,type:'scene',state:'failed',
 target:{name:'Fixture routine'},results:[{ok:false,result:'fixture_failure'}],retrySupported:true,cancelSupported:true,...extras});
async function activityRecoveryFixture({initialFailure=false}={}) {
 const state={mode:initialFailure?'fail':'ok',operations:[historyOperation()],reads:[],posts:[]};
 const h=await browserHarness({saved,routes:{
  '/remote/operations':c=>{state.reads.push(c);if(state.mode==='fail')throw new Error('fixture offline');
   if(state.mode==='hold')return new Promise((resolve,reject)=>state.finish=(fail=false)=>fail?reject(new Error('late fixture failure')):resolve(c.response({ok:true,operations:state.operations})));
   if(state.mode==='malformed')return c.response(state.malformed);
   return c.response({ok:true,operations:state.operations});},
  '/remote/operation':c=>{state.posts.push(JSON.parse(c.opts.body));return c.response({ok:true,
   operation:historyOperation('accepted-request',{state:'running',retrySupported:false,cancelSupported:false})});},
 }});
 h.eval('go("home","activity")');await h.settle();return{h,state,page:h.document.getElementById('page')};
}
const historyButton = (h,label) => h.document.getElementById('page').querySelector('.history-list')
 ?.querySelectorAll('button').find(button=>button.textContent===label);
const historyRefreshRetry = h=>h.document.getElementById('page').querySelectorAll('button')
 .find(button=>button.textContent==='Retry'&&!h.document.getElementById('page').querySelector('.history-list')?.contains(button));
const operationPosts = h=>h.calls.filter(c=>c.path==='/remote/operation'&&c.opts.method==='POST');

test('Activity refresh failure retains the confirmed list and card alongside recovery guidance',async()=>{
 const{h,state,page}=await activityRecoveryFixture(),list=page.querySelector('.history-list'),card=list.children[0];
 state.mode='fail';await h.tick(4000);
 assert.equal(page.querySelector('.history-list'),list);assert.equal(list.children[0],card);
 assert.match(page.textContent,/Last confirmed/);assert.match(page.textContent,/Activity unavailable/);
 h.document.getElementById('refresh-button').click();await h.settle();
 assert.equal(page.querySelector('.history-list'),list);assert.equal(list.children[0],card);
 assert.match(page.textContent,/Last confirmed/);
});
test('Activity refresh failure disables and guards the retained Retry and Cancel callbacks',async()=>{
 const{h,state}=await activityRecoveryFixture(),retry=historyButton(h,'Retry'),cancel=historyButton(h,'Cancel operation');
 state.mode='fail';await h.tick(4000);
 assert.equal(historyButton(h,'Retry').disabled,true);assert.equal(historyButton(h,'Cancel operation').disabled,true);
 rawClick(retry);rawClick(cancel);await h.settle();assert.equal(operationPosts(h).length,0);
 assert.equal(h.document.getElementById('sheet').open,false);
});
test('Activity refresh Retry keeps cards visible and reports one pending read without unrelated requests',async()=>{
 const{h,state,page}=await activityRecoveryFixture();state.mode='fail';await h.tick(4000);state.mode='hold';
 const before=h.calls.length;historyRefreshRetry(h).click();await h.settle();
 assert.match(page.textContent,/Refreshing activity/);assert.equal(historyButton(h,'Retry').disabled,true);
 assert.equal(page.querySelector('.history-list').children.length,1);
 assert.deepEqual(h.calls.slice(before).map(c=>c.path),['/remote/operations']);state.finish();await h.settle();
});
test('Activity refresh successful retry restores eligible current actions and removes failure guidance',async()=>{
 const{h,state,page}=await activityRecoveryFixture();state.mode='fail';await h.tick(4000);state.mode='ok';
 historyRefreshRetry(h).click();await h.settle();assert.equal(historyButton(h,'Retry').disabled,false);
 assert.equal(page.textContent.includes('Activity unavailable'),false);historyButton(h,'Retry').click();await h.settle();
 assert.deepEqual(state.posts,[{type:'retry',id:'history-one'}]);
});
test('Activity refresh initial failure remains unavailable rather than becoming successful empty history',async()=>{
 const{h,state,page}=await activityRecoveryFixture({initialFailure:true});
 assert.match(page.textContent,/Activity unavailable/);assert.equal(page.textContent.includes('No recent operations'),false);
 state.mode='hold';historyRefreshRetry(h).click();await h.settle();assert.match(page.textContent,/Loading operations/);
 state.finish();await h.settle();assert.equal(page.querySelector('.history-list').children.length,1);
});
test('Activity refresh successful empty history clears preceding confirmed cards',async()=>{
 const{h,state,page}=await activityRecoveryFixture();state.operations=[];await h.tick(4000);
 assert.match(page.textContent,/No recent operations/);assert.equal(page.textContent.includes('Fixture routine'),false);
});
test('Activity refresh missing and wrong typed arrays are rejected before replacing confirmed history',async()=>{
 for(const malformed of [{ok:true},{ok:true,operations:{}},{ok:true,operations:null}]){
  const{h,state,page}=await activityRecoveryFixture();state.mode='malformed';state.malformed=malformed;
  await h.tick(4000);assert.match(page.textContent,/Last confirmed/);assert.equal(page.querySelector('.history-list').children.length,1);
  assert.equal(historyButton(h,'Retry').disabled,true);
 }
});
test('Activity refresh invalid identities or result collections cannot replace a confirmed list',async()=>{
 for(const operations of [[null],[{id:''}],[historyOperation(),historyOperation()],[historyOperation('bad',{results:{}})],[historyOperation('bad',{results:[null]})]]){
  const{h,state,page}=await activityRecoveryFixture();state.mode='malformed';state.malformed={ok:true,operations};
  await h.tick(4000);assert.match(page.textContent,/Last confirmed/);assert.equal(h.eval('data.operations[0].id'),'history-one');
  assert.equal(historyButton(h,'Retry').disabled,true);
 }
});
test('Activity refresh page and Portal changes retire previously captured operation actions',async()=>{
 for(const next of ['go("home")',`selectRoom('${saved[1].base}')`]){
  const{h}=await activityRecoveryFixture(),retry=historyButton(h,'Retry'),cancel=historyButton(h,'Cancel operation');
  h.eval(next);await h.settle();rawClick(retry);rawClick(cancel);await h.settle();
  assert.equal(operationPosts(h).length,0);assert.equal(h.document.getElementById('sheet').open,false);
 }
});
test('Activity refresh Cancel confirmation rechecks current reported eligibility before dispatch',async()=>{
 const{h,state}=await activityRecoveryFixture();historyButton(h,'Cancel operation').click();await h.settle();
 state.operations=[historyOperation('history-one',{state:'succeeded',retrySupported:false,cancelSupported:false})];
 await h.tick(4000);h.document.getElementById('sheet-actions').querySelectorAll('button').find(b=>b.textContent==='Cancel operation').click();
 await h.settle();assert.equal(operationPosts(h).length,0);
});
test('Activity refresh stale read failure cannot hide or invalidate a newer Portal history',async()=>{
 const{h,state,page}=await activityRecoveryFixture();
 state.mode='hold';h.eval('loadVisible()');await h.settle();
 const old=state.reads.at(-1);state.mode='ok';h.eval(`selectRoom('${saved[1].base}')`);await h.settle();
 h.eval('go("home","activity")');await h.settle();
 state.finish(true);await h.settle();assert.equal(old.opts.signal.aborted,true);
 assert.equal(page.textContent.includes('Activity unavailable'),false);assert.equal(historyButton(h,'Retry').disabled,false);
});
test('Activity refresh repeated Retry activation coalesces its pending metadata read',async()=>{
 const{h,state,page}=await activityRecoveryFixture();state.mode='fail';await h.tick(4000);
 const retry=historyRefreshRetry(h);state.mode='hold';const before=state.reads.length;
 rawClick(retry);rawClick(retry);await h.settle();assert.equal(state.reads.length,before+1);
 const tick=h.tick(4000);await h.settle();assert.equal(state.reads.length,before+1);
 state.finish();await tick;await h.settle();assert.equal(page.textContent.includes('Refreshing activity'),false);
 assert.equal(historyButton(h,'Retry').disabled,false);
});


const recoveryScene = (id='scene-one', extras={}) => ({id,name:'Fixture evening',steps:[{type:'key',action:'home'}],schedule:null,...extras});
async function scenesRecoveryFixture({initialFailure=false,holdInitial=false}={}) {
 const state={mode:initialFailure?'fail':holdInitial?'hold':'ok',scenes:[recoveryScene()],revision:'a'.repeat(64),reads:[],posts:[]};
 const h=await browserHarness({saved,routes:{
  '/remote/scenes':c=>{if(c.opts.method==='POST'){state.posts.push(JSON.parse(c.opts.body));return c.response({ok:true,scenes:state.scenes,revision:state.revision});}
   state.reads.push(c);if(state.mode==='fail')throw new Error('fixture offline');
   if(state.mode==='hold')return new Promise((resolve,reject)=>state.finish=(fail=false)=>fail?reject(new Error('late fixture failure')):resolve(c.response({ok:true,scenes:state.scenes,revision:state.revision})));
   if(state.mode==='malformed')return c.response(state.malformed);
   return c.response({ok:true,scenes:state.scenes,revision:state.revision});},
  '/remote/scene':c=>{state.posts.push(JSON.parse(c.opts.body));return c.response({ok:true,operation:{id:'fixture-run',state:'running',results:[]}});},
 }});
 h.eval('go("home","scenes")');await h.settle();return{h,state,page:h.document.getElementById('page')};
}
const scenePageButton=(h,label)=>h.document.getElementById('page').querySelectorAll('button').find(b=>b.textContent===label);
const sceneRecoveryPosts=h=>h.calls.filter(c=>['/remote/scenes','/remote/scene'].includes(c.path)&&c.opts.method==='POST');

test('Scenes refresh initial pending state identifies loading and disables creation',async()=>{
 const{h,state,page}=await scenesRecoveryFixture({holdInitial:true});assert.match(page.textContent,/Loading.*routines/i);
 assert.equal(scenePageButton(h,'New scene').disabled,true);state.finish();await h.settle();assert.equal(scenePageButton(h,'New scene').disabled,false);
});
test('Scenes refresh failure retains confirmed routines with recovery guidance and unavailable actions',async()=>{
 const{h,state,page}=await scenesRecoveryFixture();state.mode='fail';h.eval('loadVisible()');await h.settle();
 assert.match(page.textContent,/Fixture evening/);assert.match(page.textContent,/Last confirmed/);assert.match(page.textContent,/Scenes unavailable/);
 for(const label of ['New scene','Run','Edit','Delete'])assert.equal(scenePageButton(h,label).disabled,true);
});
test('Scenes refresh pending Retry retains confirmed routines and coalesces duplicate activation',async()=>{
 const{h,state,page}=await scenesRecoveryFixture();state.mode='fail';h.eval('loadVisible()');await h.settle();
 const retry=scenePageButton(h,'Retry');state.mode='hold';const before=h.calls.length;rawClick(retry);rawClick(retry);await h.settle();
 assert.deepEqual(h.calls.slice(before).map(c=>c.path),['/remote/scenes']);assert.match(page.textContent,/Refreshing routines/);
 assert.match(page.textContent,/Fixture evening/);assert.equal(scenePageButton(h,'Run').disabled,true);state.finish();await h.settle();
});
test('Scenes refresh successful Retry restores current actions and runs exactly the confirmed identity',async()=>{
 const{h,state,page}=await scenesRecoveryFixture();state.mode='fail';h.eval('loadVisible()');await h.settle();state.mode='ok';
 scenePageButton(h,'Retry').click();await h.settle();assert.equal(page.textContent.includes('Scenes unavailable'),false);
 assert.equal(scenePageButton(h,'Run').disabled,false);scenePageButton(h,'Run').click();await h.settle();assert.deepEqual(state.posts,[{id:'scene-one'}]);
});
test('Scenes refresh initial failure is unavailable rather than a successful empty list',async()=>{
 const{h,state,page}=await scenesRecoveryFixture({initialFailure:true});assert.match(page.textContent,/Scenes unavailable/);
 assert.equal(page.textContent.includes('No scenes yet'),false);assert.equal(scenePageButton(h,'New scene').disabled,true);
 state.mode='ok';scenePageButton(h,'Retry').click();await h.settle();assert.match(page.textContent,/Fixture evening/);
});
test('Scenes refresh confirmed empty result clears preceding routines and enables creation',async()=>{
 const{h,state,page}=await scenesRecoveryFixture();state.scenes=[];h.eval('loadVisible()');await h.settle();
 assert.match(page.textContent,/No scenes yet/);assert.equal(page.textContent.includes('Fixture evening'),false);assert.equal(scenePageButton(h,'New scene').disabled,false);
});
test('Scenes refresh missing and wrong typed arrays cannot replace the confirmed list',async()=>{
 for(const malformed of [{ok:true},{ok:true,scenes:null},{ok:true,scenes:{}}]){
  const{h,state,page}=await scenesRecoveryFixture();state.mode='malformed';state.malformed=malformed;h.eval('loadVisible()');await h.settle();
  assert.match(page.textContent,/Last confirmed/);assert.match(page.textContent,/Fixture evening/);assert.equal(h.eval('data.scenes[0].id'),'scene-one');
 }
});
test('Scenes refresh rejects malformed scene identities names steps and schedules before admission',async()=>{
 for(const scenes of [[null],[recoveryScene('',{})],[recoveryScene(),recoveryScene()],[recoveryScene('bad',{name:null})],[recoveryScene('bad',{steps:{}})],[recoveryScene('bad',{steps:[null]})],[recoveryScene('bad',{schedule:{enabled:true,hour:8,minute:0,days:null}})]]){
  const{h,state,page}=await scenesRecoveryFixture();state.mode='malformed';state.malformed={ok:true,scenes,revision:state.revision};h.eval('loadVisible()');await h.settle();
  assert.match(page.textContent,/Last confirmed/);assert.equal(h.eval('data.scenes[0].id'),'scene-one');assert.equal(scenePageButton(h,'Run').disabled,true);
 }
});
test('Scenes refresh failed metadata retires captured Run Edit New and Delete actions',async()=>{
 const{h,state}=await scenesRecoveryFixture(),old=['Run','Edit','New scene','Delete'].map(label=>scenePageButton(h,label));
 state.mode='fail';h.eval('loadVisible()');await h.settle();for(const action of old)rawClick(action);await h.settle();
 assert.equal(sceneRecoveryPosts(h).length,0);assert.equal(h.eval('sceneDraft'),null);assert.equal(h.document.getElementById('sheet').open,false);
});
test('Scenes refresh Delete confirmation rejects unavailable metadata and preserves reviewed revision after a successful refresh',async()=>{
 {
  const{h,state}=await scenesRecoveryFixture();scenePageButton(h,'Delete').click();await h.settle();
  state.mode='fail';h.eval('loadVisible()');await h.settle();
  h.document.getElementById('sheet-actions').querySelectorAll('button').find(b=>b.textContent==='Delete').click();await h.settle();assert.equal(sceneRecoveryPosts(h).length,0);
 }
 {
  const{h,state}=await scenesRecoveryFixture();scenePageButton(h,'Delete').click();await h.settle();
  state.scenes=[recoveryScene('scene-other',{name:'Replacement evening'})];state.revision='b'.repeat(64);h.eval('loadVisible()');await h.settle();
  h.document.getElementById('sheet-actions').querySelectorAll('button').find(b=>b.textContent==='Delete').click();await h.settle();
  assert.deepEqual(state.posts,[{scenes:[],expectedRevision:'a'.repeat(64)}]);
 }
});
test('Scenes refresh page and Portal changes retire captured actions',async()=>{
 for(const next of ['go("home")',`selectRoom('${saved[1].base}')`]){
  const{h}=await scenesRecoveryFixture(),old=['Run','Edit','New scene','Delete'].map(label=>scenePageButton(h,label));
  h.eval(next);await h.settle();for(const action of old)rawClick(action);await h.settle();assert.equal(sceneRecoveryPosts(h).length,0);
  assert.equal(h.eval('sceneDraft'),null);assert.equal(h.document.getElementById('sheet').open,false);
 }
});
test('Scenes refresh late previous-Portal failure cannot invalidate current routines or replace an editor draft',async()=>{
 const{h,state,page}=await scenesRecoveryFixture();state.mode='hold';h.eval('loadVisible()');await h.settle();const old=state.reads.at(-1),finish=state.finish;
 state.mode='ok';h.eval(`selectRoom('${saved[1].base}')`);await h.settle();h.eval('go("home","scenes")');await h.settle();finish(true);await h.settle();
 assert.equal(old.opts.signal.aborted,true);assert.equal(page.textContent.includes('Scenes unavailable'),false);assert.equal(scenePageButton(h,'Run').disabled,false);
 scenePageButton(h,'Edit').click();await h.settle();await h.input('Scene name','Draft evening');state.mode='fail';h.eval('loadScenes()');await h.settle();
 assert.equal(h.eval('sceneDraft.name'),'Draft evening');
});


test('Scenes refresh global Refresh supersedes an aborted pending read without enabling stale actions',async()=>{
 const{h,state,page}=await scenesRecoveryFixture();state.mode='hold';h.eval('loadScenes()');await h.settle();
 const old=state.reads.at(-1),finishOld=state.finish,before=state.reads.length;
 h.document.getElementById('refresh-button').click();await h.settle();const finishNew=state.finish;
 assert.equal(old.opts.signal.aborted,true);assert.equal(state.reads.length,before+1);assert.notEqual(finishOld,finishNew);
 finishOld();await h.settle();assert.match(page.textContent,/Refreshing routines/);assert.equal(scenePageButton(h,'Run').disabled,true);
 state.scenes=[recoveryScene('scene-latest',{name:'Fresh evening'})];finishNew();await h.settle();
 assert.match(page.textContent,/Fresh evening/);assert.equal(page.textContent.includes('Refreshing routines'),false);assert.equal(scenePageButton(h,'Run').disabled,false);
});
