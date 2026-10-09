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
