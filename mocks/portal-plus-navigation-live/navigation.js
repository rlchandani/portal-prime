/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
(() => {
  'use strict';
  const renderer = window.__portalMock;
  const staging = document.getElementById('device');
  const canvas = document.getElementById('navigation-device');
  const viewport = document.getElementById('demo-viewport');
  const model = renderer.model;
  const pages = new Map(model.pages.map(page => [page.id, page]));
  const tabIds = ['home', 'apps', 'tools'];
  const hiddenAppPackages = new Set([
    'com.android.quicksearchbox', 'com.google.android.googlequicksearchbox',
  ]);
  const toolIds = new Set(model.tools.map(tool => tool[0]));
  // Current native boundary supplied by the implementation owner, not inferred from
  // the frozen mock's older Activity inventory.
  const internalActivities = new Set([
    'immich', 'albums', 'smb', 'dav', 'folder', 'dismiss', 'album', 'web',
    'calendar-url', 'phone', 'help', 'apk', 'ha-entities',
  ]);
  const fullscreen = new Set([
    'photos', 'digital-preview', 'welcome-preview', 'wake-light', 'lamp-preview',
    'alarm-ring', 'ha-dashboard', 'provider-widgets', 'folder-preview', 'music', 'camera-preview',
  ]);
  const appPages = new Set(['store', 'app-detail', 'app-folder', 'switcher', 'music']);
  const homePages = new Set(['edit', 'widgets']);
  const tabs = new Map();
  const settingsPanes = new Map();
  const scrollPositions = new Map();
  const settingsHistory = [];
  let host = 'launcher';
  let activeTab = 'home';
  let settingsReturn = 'home';
  let current = { screen: 'home', category: 'appearance' };
  let activeSettingsKey = null;
  let lastSourceMain = null;
  let tier = null;
  let localSimulation = false;
  let modalFocus = null;
  let transitionId = 0;
  let animations = [];
  let fixtureRevision = '';
  let ready = false;
  let activity = null;
  let activityReturn = null;
  let exitingActivity = null;
  const activityHistory = [];
  const appHoldDelay = 550;
  const appHoldSlop = 10; // Browser CSS pixels, independent of the scaled Portal canvas.
  let appHold = null;
  let suppressedAppClick = null;
  let homePage = 0;
  let homeProjection = null;

  const routeKey = route => `${route.category}/${route.screen}${route.reader ? `/reader/${route.reader}` : ''}`;
  const routeTitle = route => route.reader || (route.screen === 'settings'
    ? model.categories.find(category => category[0] === route.category)?.[1] || 'Settings'
    : pages.get(route.screen)?.title || ({
      home: 'Home', apps: 'Apps', tools: 'Tools', store: 'App Store',
      'app-detail': 'App details', 'app-folder': 'App folder', edit: 'Customize home',
      widgets: 'Home & widgets', switcher: 'Recent apps', music: 'Music',
      photos: 'Photo display', 'digital-preview': 'Digital clock preview',
      'welcome-preview': 'Welcome preview', 'wake-light': 'Wake light',
      'lamp-preview': 'Lamp', 'alarm-ring': 'Alarm ringing',
      'camera-preview': 'Camera display',
      'ha-dashboard': 'Home Assistant', 'provider-widgets': 'Android widget providers',
      'folder-preview': 'Folder preview',
    })[route.screen] || route.screen);
  const button = (label, action) => {
    const node = document.createElement('button');
    node.type = 'button';
    node.className = 'text-button live-local-back';
    node.dataset.liveAction = action;
    node.textContent = label;
    return node;
  };
  const sourceMain = () => staging.querySelector('main');
  const routeFromSource = () => {
    const state = renderer.getState();
    if (current.reader && state.screen === 'stories' && staging.querySelector('.story-text')) return { ...current };
    return { screen: state.screen, category: state.category };
  };
  const activePanel = () => host === 'activity' ? activity.panel : host === 'settings'
    ? settingsPanes.get(activeSettingsKey)
    : tabs.get(activeTab);
  const activeHistory = () => host === 'activity' ? activityHistory
    : host === 'settings' ? settingsHistory : tabs.get(activeTab).history;

  function sourceNavigate(route) {
    renderer.navigate(route.screen, route.category);
    if (route.reader) renderer.showAction('story', { name: route.reader });
    lastSourceMain = sourceMain();
  }

  // Only fixture data changes invalidate retained HTML. Navigation itself never does.
  function revision() {
    const state = renderer.getState();
    return JSON.stringify({
      values: state.values, widgets: state.widgets, source: state.source,
      collections: state.collections, widgetSizes: state.widgetSizes, advanced: state.advanced,
      installedApps: model.installed.map(({ packageName, name, artwork }) => ({ packageName, name, artwork })),
    });
  }

  function captureFocus() {
    const element = document.activeElement;
    if (!canvas.contains(element)) return null;
    return {
      selector: targetSelector(element),
      start: element.selectionStart,
      end: element.selectionEnd,
    };
  }

  function restoreFocus(saved) {
    if (!saved?.selector) return;
    const scope = overlays.querySelector('.modal') || activePanel()?.node;
    const element = scope?.querySelector(saved.selector);
    if (!element) return;
    element.focus({ preventScroll: true });
    if (saved.start != null && element.setSelectionRange) {
      try { element.setSelectionRange(saved.start, saved.end); } catch { /* Number inputs. */ }
    }
  }

  function saveScroll(panel) {
    // Hidden retained DOM has no scroll geometry; keep the offset saved before hiding it.
    if (!panel?.route || !panel.node.getClientRects().length) return;
    const positions = [...panel.node.querySelectorAll('.screen-body,.detail-body,.home-grid')]
      .filter(element => element.getClientRects().length)
      .map(element => ({ top: element.scrollTop, left: element.scrollLeft }));
    const homeGrid = panel.node.querySelector('.live-widget-grid');
    const key = homeGrid ? `home/widgets/${homeGrid.dataset.livePage}` : routeKey(panel.route);
    scrollPositions.set(key, positions);
  }

  function restoreScroll(panel) {
    const homeGrid = panel.node.querySelector('.live-widget-grid');
    const key = homeGrid ? `home/widgets/${homeGrid.dataset.livePage}` : routeKey(panel.route);
    const positions = scrollPositions.get(key);
    if (!positions) return;
    [...panel.node.querySelectorAll('.screen-body,.detail-body,.home-grid')]
      .filter(element => element.getClientRects().length).forEach((element, index) => {
        element.scrollTop = positions[index]?.top || 0;
        element.scrollLeft = positions[index]?.left || 0;
      });
  }

  function projectApps(content) {
    // Keep the frozen sample inventory, but show exported native icon pixels wherever
    // the identity matches. Explicit sample artwork remains available to regressions.
    const grid = content.querySelector('.app-grid');
    grid.dataset.liveAppsGrid = '';
    content.querySelector('.detail-heading p').textContent =
      'Sample installed-app fixtures · matching icons use artwork rendered on the Portal.';
    content.querySelectorAll('.app-with-menu').forEach(wrapper => {
      const tile = wrapper.querySelector('.app-tile[data-action="launch"]');
      const menu = wrapper.querySelector('[data-action="app-menu"]');
      if (!tile || menu?.dataset.pkg !== tile.dataset.pkg) return;
      tile.dataset.liveAppMenu = '';
      tile.setAttribute('aria-haspopup', 'dialog');
      tile.setAttribute('aria-description', 'Touch and hold or right-click for app actions. Keyboard: Shift+F10 or Menu key.');
      wrapper.replaceWith(tile);
    });
    grid.querySelector('.app-tile[data-screen="tools"]')?.remove();
    const storeLabel = grid.querySelector('.app-tile[data-screen="store"] strong');
    if (storeLabel) storeLabel.textContent = 'App Store';
    grid.querySelectorAll('.app-tile .app-icon').forEach(icon => {
      const tile = icon.closest('.app-tile');
      const fallback = icon.cloneNode(true);
      fallback.querySelectorAll('img').forEach(image => image.remove());
      fallback.dataset.liveArtwork = 'glyph';
      if (!fallback.childElementCount) fallback.textContent = '◇';
      let image = icon.querySelector('img');
      const artwork = model.installed.find(app => app.packageName === tile.dataset.pkg)?.artwork;
      const builtin = tile.dataset.screen === 'store' ? 'store'
        : tile.dataset.screen === 'music' ? 'music'
        : tile.dataset.screen === 'settings' ? 'settings'
        : tile.dataset.action === 'external' && tile.dataset.owner === 'Portal' ? 'calls' : null;
      const native = window.__portalNativeIcons?.[builtin ? `builtin:${builtin}` : tile.dataset.pkg];
      const imageSource = image?.getAttribute('src') || artwork || native;
      if (typeof imageSource === 'string') {
        let url;
        try { url = new URL(imageSource, document.baseURI); } catch { /* Unavailable fixture artwork uses the glyph. */ }
        if (url && (url.origin === location.origin || /^data:image\//.test(url.href))) {
          image ||= document.createElement('img');
          image.removeAttribute('srcset');
          image.src = url.href;
          icon.replaceChildren(image);
        } else {
          icon.replaceWith(fallback);
          return;
        }
      }
      icon.dataset.liveArtwork = image ? (!artwork && native ? 'native' : 'fixture') : 'glyph';
      if (image) {
        image.alt = ''; // The tile already supplies the app's accessible label.
        const failed = () => icon.replaceWith(fallback);
        image.addEventListener('error', failed, { once: true });
        if (image.complete && !image.naturalWidth) queueMicrotask(failed);
      }
    });
  }

  function filterAppEntries(content) {
    // Filter copied app identities, never the launcher's own search controls or glyphs.
    content.querySelectorAll('[data-pkg],[data-value]').forEach(control => {
      const pkg = control.dataset.pkg || (control.dataset.value?.startsWith('boot.')
        ? control.dataset.value.slice(5) : null);
      if (!hiddenAppPackages.has(pkg)) return;
      (control.closest('.app-with-menu,.app-tile,.settings-row,.modal-option,.store-card') || control).remove();
    });
    return content;
  }

  function populateTools(panel, route, content, signature) {
    if (!panel.workspace) {
      const workspace = document.createElement('header');
      workspace.className = 'live-tools-workspace';
      const backControl = button('← Back', 'back');
      const title = document.createElement('h1');
      title.id = 'live-tools-title';
      workspace.append(backControl, title);
      const stage = document.createElement('div');
      stage.className = 'live-tools-stage';
      stage.setAttribute('aria-labelledby', title.id);
      panel.node.append(workspace, stage);
      Object.assign(panel, { workspace, stage, backControl, title });
    }
    const list = route.screen === 'tools';
    const key = list ? 'toolsList' : 'toolsDetail';
    const previous = panel[key];
    if (!previous || previous.signature !== signature) {
      previous?.node.remove();
      panel.stage.append(content);
      panel[key] = { node: content, signature };
    }
    if (panel.toolsList) panel.toolsList.node.hidden = !list;
    if (panel.toolsDetail) panel.toolsDetail.node.hidden = list;
    const parent = panel.history.at(-2);
    panel.backControl.setAttribute('aria-label', `Back to ${list ? 'Home' : routeTitle(parent || { screen: 'tools' })}`);
    panel.title.textContent = routeTitle(route);
    panel.signature = signature;
  }

  function indexedWidgets() {
    const widgets = renderer.getState().widgets;
    const entries = [...sourceMain().querySelectorAll('.widget-edit')].map(editor => {
      const index = Number(editor.querySelector('[data-action="widget-size"]').dataset.index);
      const size = editor.querySelector('.small').textContent.match(/^([1-4]) × ([1-4])$/);
      return { index, kind: widgets[index], saved: size ? [Number(size[1]), Number(size[2])] : null,
        controls: editor.querySelector('.button-group').cloneNode(true) };
    });
    if (entries.length !== widgets.length || entries.some((entry, index) => entry.index !== index || !entry.kind)) {
      throw new Error('Frozen editor did not expose every widget by its original action index.');
    }
    return entries;
  }

  function sampleHome(route) {
    // Read indexed size labels, never guess private widgetKeys from array positions.
    // Borrow only dialog-free staging renders; visible chrome and panels stay mounted.
    renderer.navigate('edit', route.category);
    const entries = indexedWidgets();
    const toolbar = sourceMain().querySelector('.editing-toolbar .button-group').cloneNode(true);
    renderer.navigate('home', route.category);
    const sourcePage = () => sourceMain().querySelector('.widget-page > span').textContent.match(/(\d+) of (\d+)/).slice(1).map(Number);
    const [originalPage, count] = sourcePage();
    const body = sourceMain().querySelector('.home-body').cloneNode(true);
    const paint = { background: sourceMain().style.background, grain: sourceMain().classList.contains('grain-background') };
    const photo = entries.find(entry => entry.kind === 'photo_memory');
    const nonPhotos = entries.filter(entry => entry.kind !== 'photo_memory');
    const templates = new Map();
    for (let page = 0; page < count; page++) {
      const visible = [...(photo ? [photo] : []), ...nonPhotos.slice((sourcePage()[0] - 1) * 4, sourcePage()[0] * 4)];
      const cards = [...sourceMain().querySelector('.home-grid').children];
      if (cards.length !== visible.length) throw new Error('Frozen Home card inventory cannot be joined to editor indices.');
      visible.forEach((entry, index) => templates.set(entry.index, cards[index].cloneNode(true)));
      renderer.showAction('page', { dir: '1' });
    }
    // Extra photos use the fixture's photo card but keep their own indexed actions/sizes.
    entries.filter(entry => entry.kind === 'photo_memory').forEach(entry =>
      templates.set(entry.index, templates.get(photo.index).cloneNode(true)));
    const remaining = entries.filter(entry => entry !== photo);
    const pageCount = Math.max(1, Math.ceil(remaining.length / 4));
    homePage = Math.min(homePage, pageCount - 1);
    // The old fixture omits extra photos from its count. Synchronize every page it can
    // represent; projected paging also exposes the additional configured identities.
    const targetPage = Math.min(homePage, count - 1);
    if (targetPage !== originalPage - 1) renderer.showAction('page', { dir: String(targetPage - originalPage + 1) });
    if (route.screen === 'edit') renderer.navigate('edit', route.category);
    homeProjection = { entries, toolbar, body, templates, paint, photo, remaining, pageCount };
  }

  function packWidgets(entries, span) {
    const occupied = [];
    return entries.map(entry => {
      const [width, height] = span(entry);
      let row = 0;
      let column = 0;
      const fits = (x, y) => Array.from({ length: height }, (_, dy) =>
        Array.from({ length: width }, (_, dx) => !occupied[y + dy]?.[x + dx]).every(Boolean)).every(Boolean);
      search: for (;; row++) {
        for (column = 0; column <= 4 - width; column++) if (fits(column, row)) break search;
      }
      for (let dy = 0; dy < height; dy++) {
        occupied[row + dy] ||= [];
        for (let dx = 0; dx < width; dx++) occupied[row + dy][column + dx] = true;
      }
      return { entry, column, row, width, height };
    });
  }

  function projectedHome(route) {
    if (!staging.querySelector('.modal-layer')) sampleHome(route);
    const { body, photo, remaining, templates, pageCount } = homeProjection;
    // Share the first photo across pages, then honor saved order within each
    // visible subset. Sharing membership must not pin its rendered position.
    const selected = [...(photo ? [photo] : []), ...remaining.slice(homePage * 4, homePage * 4 + 4)]
      .sort((a, b) => a.index - b.index);
    // Frozen widgetSizes entries are explicit selections, including legacy-looking
    // 2 × 1 / 2 × 2 presets. A neighbor's size never changes this widget's default.
    const span = entry => entry.saved || (entry.kind === 'photo_memory' ? [2, 4] : [1, 2]);
    const content = body.cloneNode(true);
    const grid = content.querySelector('.home-grid');
    grid.className = 'home-grid live-widget-grid';
    grid.dataset.livePage = homePage;
    grid.replaceChildren();
    packWidgets(selected, span).forEach(({ entry, column, row, width, height }) => {
      const slot = document.createElement('div');
      slot.className = 'live-widget-slot';
      slot.dataset.liveWidgetIndex = entry.index;
      slot.dataset.liveWidgetKind = entry.kind;
      slot.style.gridColumn = `${column + 1} / span ${width}`;
      slot.style.gridRow = `${row + 1} / span ${height}`;
      const card = templates.get(entry.index).cloneNode(true);
      card.classList.add('live-widget-preview');
      card.style.removeProperty('grid-column');
      card.style.removeProperty('grid-row');
      card.style.minHeight = '0';
      slot.append(card);
      if (route.screen === 'edit') {
        card.inert = true;
        const controls = entry.controls.cloneNode(true);
        controls.className = 'live-widget-edit-controls';
        controls.querySelectorAll('button').forEach(control => { control.type = 'button'; });
        slot.append(controls);
      }
      grid.append(slot);
    });
    if (route.screen === 'edit') {
      const toolbar = document.createElement('span');
      toolbar.className = 'live-widget-toolbar';
      const add = homeProjection.toolbar.querySelector('[data-screen="widgets"]').cloneNode(true);
      add.className = 'text-button';
      toolbar.append(add);
      content.querySelector('.widget-page').prepend(toolbar);
    }
    content.querySelector('.widget-page > span:not(.live-widget-toolbar)').textContent = `${homePage + 1} of ${pageCount}`;
    return content;
  }

  function cloneContent(route) {
    if (route.screen === 'home' || route.screen === 'edit') return filterAppEntries(projectedHome(route));
    if (route.reader) {
      const content = document.createElement('section');
      content.className = 'detail-body live-story-reader';
      const sample = document.createElement('p');
      sample.className = 'live-story-label';
      sample.textContent = 'Sample story text · speech playback is simulated';
      content.append(sample, staging.querySelector('.story-text').cloneNode(true),
        staging.querySelector('[data-action="story-stop"]').cloneNode(true));
      return content;
    }
    const main = sourceMain();
    const source = main.querySelector('.home-body,.screen-body,.detail-body');
    if (!source) throw new Error(`No approved fixture content for ${route.screen}`);
    const content = filterAppEntries(source.cloneNode(true));
    if (route.screen === 'apps') {
      projectApps(content);
    }
    if (host === 'launcher' && activeTab === 'tools') {
      content.querySelector(':scope > [data-screen="settings"]')?.remove();
      content.querySelector(':scope > .detail-heading')?.remove();
      return content; // The mounted workspace owns the single Back/title row.
    }
    if (route.screen !== 'home') {
      const originalBack = content.querySelector(':scope > [data-screen="settings"]');
      originalBack?.remove();
      const history = activeHistory();
      const parent = history.length > 1 ? history[history.length - 2]
        : host === 'activity' ? activityReturn.route : null;
      if (parent || host === 'settings') {
        content.prepend(button(`← ${parent ? routeTitle(parent) : 'Launcher'}`, 'back'));
      }
    }
    return content;
  }

  function populate(panel, route, force = false) {
    const nextRevision = revision();
    const content = cloneContent(route);
    const signature = content.outerHTML;
    if (force || panel.signature !== signature) {
      const focus = captureFocus();
      if (routeKey(panel.route || route) === routeKey(route)) saveScroll(panel);
      if (appHold && panel.node.contains(appHold.tile)) cancelAppHold();
      if (panel === tabs.get('tools')) populateTools(panel, route, content, signature);
      else {
        panel.node.replaceChildren(content);
        panel.signature = signature;
      }
      panel.route = { ...route };
      restoreScroll(panel);
      restoreFocus(focus);
    }
    panel.route = { ...route };
    panel.revision = nextRevision;
    const isHome = route.screen === 'home' || route.screen === 'edit';
    panel.node.classList.toggle('home-screen', isHome);
    if (isHome) {
      panel.node.style.background = homeProjection.paint.background;
      panel.node.classList.toggle('grain-background', homeProjection.paint.grain);
    } else {
      panel.node.style.background = '';
      panel.node.classList.remove('grain-background');
    }
    fixtureRevision = nextRevision;
  }

  function newPanel(className, container) {
    const node = document.createElement('section');
    node.className = className;
    node.hidden = true;
    node.inert = true;
    node.setAttribute('aria-hidden', 'true');
    container.append(node);
    return { node, route: null, signature: null, revision: null };
  }

  function animatePanels(previous, next, kind, direction = 1) {
    const id = ++transitionId;
    animations.forEach(animation => animation.cancel());
    animations = [];
    const all = kind === 'tabs' ? [...tabs.values()] : [...settingsPanes.values()];
    all.forEach(panel => {
      panel.node.hidden = panel !== previous && panel !== next;
      panel.node.inert = panel !== next;
      panel.node.setAttribute('aria-hidden', String(panel !== next));
    });
    next.node.hidden = false;
    if (!previous || previous === next) return;
    document.activeElement?.blur();
    const duration = matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 160;
    const easing = 'cubic-bezier(0.4, 0, 0.2, 1)';
    const incoming = kind === 'tabs'
      ? [{ transform: `translateX(${direction * 100}%)` }, { transform: 'translateX(0)' }]
      : [{ opacity: 0, transform: `translateX(${direction * 24}px)` }, { opacity: 1, transform: 'translateX(0)' }];
    const outgoing = kind === 'tabs'
      ? [{ transform: 'translateX(0)' }, { transform: `translateX(${-direction * 100}%)` }]
      : [{ opacity: 1, transform: 'translateX(0)' }, { opacity: 0, transform: `translateX(${-direction * 24}px)` }];
    animations = [next.node.animate(incoming, { duration, easing }),
      previous.node.animate(outgoing, { duration, easing })];
    Promise.all(animations.map(animation => animation.finished.catch(() => {}))).then(() => {
      if (id !== transitionId) return;
      previous.node.hidden = true;
      animations = [];
    });
  }

  function applyPalette(element, theme) {
    Object.entries(PORTAL_SPEC[theme]).forEach(([key, value]) =>
      element.style.setProperty(`--${key}`, value));
    element.classList.toggle('dark', theme === 'dark');
  }

  function syncChrome() {
    const state = renderer.getState();
    // The launcher owns a permanently dark palette. The choice belongs to Settings
    // and its editors; launcher dialogs also inherit the launcher's dark colors.
    applyPalette(canvas, state.theme);
    applyPalette(launcher, 'dark');
    applyPalette(overlays, host === 'launcher' ? 'dark' : state.theme);
    document.getElementById('demo-theme').value = state.theme;
    const editing = host === 'launcher' && activeTab === 'home' && current.screen === 'edit';
    customizeControl.hidden = activeTab !== 'home';
    customizeControl.textContent = editing ? 'Done' : 'Customize';
    customizeControl.dataset.screen = editing ? 'home' : 'edit';
    customizeControl.toggleAttribute('data-live-widget-done', editing);
    launcherDock.querySelectorAll('[data-screen]').forEach(node =>
      node.classList.toggle('selected', node.dataset.screen === activeTab));
    const room = launcherHeader.querySelector('.room-name');
    room.textContent = state.values['fleet.name'];
    settingsSidebar.querySelector('.sidebar-device').firstChild.textContent = state.values['fleet.name'];
    const sourceSidebar = staging.querySelector('.settings-sidebar');
    if (tier !== state.advanced && sourceSidebar) {
      const search = settingsSidebar.querySelector('#settings-search').value;
      settingsSidebar.querySelector('.sidebar-nav').replaceChildren(
        ...[...sourceSidebar.querySelector('.sidebar-nav').children].map(node => node.cloneNode(true)));
      settingsSidebar.querySelector('#settings-search').value = search;
      tier = state.advanced;
    }
    settingsSidebar.querySelector('[data-advanced-toggle]').checked = state.advanced;
    settingsSidebar.querySelectorAll('[data-action="category"]').forEach(node =>
      node.classList.toggle('selected', node.dataset.id === current.category));
    const themeButton = settingsHeader.querySelector('[data-action="theme"]');
    themeButton.textContent = state.theme === 'light' ? 'Dark appearance' : 'Light appearance';
    document.getElementById('demo-route').textContent =
      `${host === 'activity' ? 'Internal Activity simulation' : host === 'settings' ? 'Settings' : routeTitle({ screen: activeTab })} · ${routeTitle(current)}`;
    if (activity) {
      activity.window.querySelector('[data-action="theme"]').textContent = themeButton.textContent;
      const sidebar = activity.window.querySelector('.settings-sidebar');
      if (activity.tier !== state.advanced && sourceSidebar) {
        sidebar.querySelector('.sidebar-nav').replaceChildren(
          ...[...sourceSidebar.querySelector('.sidebar-nav').children].map(node => node.cloneNode(true)));
        activity.tier = state.advanced;
      }
      sidebar.querySelector('[data-advanced-toggle]').checked = state.advanced;
      sidebar.querySelectorAll('[data-action="category"]').forEach(node =>
        node.classList.toggle('selected', node.dataset.id === current.category));
    }
    document.title = `Immortal · ${routeTitle(current)} · browser navigation demo`;
  }

  function syncOverlays() {
    if (localSimulation) return;
    // The frozen reader dialog is projected into the Tools body, below its fixed heading.
    const sourceModal = current.reader && staging.querySelector('.story-text') ? null : staging.querySelector('.modal-layer');
    if (sourceModal) cancelAppHold();
    const sourceToast = staging.querySelector('.toast');
    const hadModal = !!overlays.querySelector('.modal-layer');
    const focus = captureFocus();
    overlays.replaceChildren();
    if (sourceModal) {
      const modal = filterAppEntries(sourceModal.cloneNode(true));
      if (modal.querySelector('.external-note')) {
        const label = document.createElement('strong');
        label.className = 'live-handoff-label';
        label.textContent = 'Third-party / system handoff simulation · sample only';
        modal.querySelector('.modal').prepend(label);
      }
      overlays.append(modal);
      if (!hadModal) modalFocus = document.activeElement;
    }
    if (sourceToast) overlays.append(sourceToast.cloneNode(true));
    launcher.inert = host !== 'launcher' || !!sourceModal;
    settings.inert = host !== 'settings' || !!sourceModal;
    if (activity) activity.window.inert = !!sourceModal;
    if (sourceModal) {
      if (hadModal && focus) restoreFocus(focus);
      else overlays.querySelector('input,button')?.focus({ preventScroll: true });
    } else if (hadModal) {
      modalFocus?.isConnected && modalFocus.focus({ preventScroll: true });
    }
  }

  function presentSettings(route, push, alreadyRendered) {
    const previous = settingsPanes.get(activeSettingsKey);
    saveScroll(previous);
    if (push && routeKey(settingsHistory.at(-1) || {}) !== routeKey(route)) settingsHistory.push(route);
    const key = routeKey(route);
    let panel = settingsPanes.get(key);
    if (!panel) {
      panel = newPanel('settings-pane', settingsStage);
      settingsPanes.set(key, panel);
    }
    // LRU: only four actual detail DOM panels can remain retained.
    settingsPanes.delete(key);
    settingsPanes.set(key, panel);
    while (settingsPanes.size > 4) {
      const oldest = settingsPanes.keys().next().value;
      const evicted = settingsPanes.get(oldest);
      saveScroll(evicted);
      evicted.node.remove();
      settingsPanes.delete(oldest);
    }
    if (!alreadyRendered) sourceNavigate(route);
    if (panel.signature === null || panel.revision !== revision()) populate(panel, route);
    activeSettingsKey = key;
    animatePanels(previous?.node.isConnected ? previous : null, panel, 'settings', push ? 1 : -1);
    restoreScroll(panel);
  }

  function presentTab(route, push, alreadyRendered, switching) {
    const previous = tabs.get(activeTab);
    saveScroll(previous);
    const nextTab = tabIds.includes(route.screen) ? route.screen
      : appPages.has(route.screen) ? 'apps'
      : homePages.has(route.screen) ? 'home' : activeTab;
    const panel = tabs.get(nextTab);
    const changedTab = nextTab !== activeTab;
    const previousTab = activeTab;
    activeTab = nextTab;
    if (switching && panel.history.length) route = panel.history.at(-1);
    if (push && routeKey(panel.history.at(-1) || {}) !== routeKey(route)) panel.history.push(route);
    if (!alreadyRendered || switching) sourceNavigate(route);
    current = { ...route };
    if (routeKey(panel.route || {}) !== routeKey(route) || panel.revision !== revision()) {
      saveScroll(panel);
      populate(panel, route);
    }
    animatePanels(previous, panel, 'tabs', tabIds.indexOf(nextTab) > tabIds.indexOf(previousTab) ? 1 : -1);
    if (!changedTab) panel.node.hidden = false;
    restoreScroll(panel);
  }

  function presentActivity(route, push, alreadyRendered) {
    if (host !== 'activity') {
      saveScroll(activePanel());
      activityReturn = { host, route: { ...current }, tab: activeTab };
      activityHistory.length = 0;
    }
    host = 'activity';
    if (push && routeKey(activityHistory.at(-1) || {}) !== routeKey(route)) activityHistory.push(route);
    current = { ...route };
    if (!alreadyRendered) sourceNavigate(route);
    if (!activity) {
      exitingActivity?.getAnimations().forEach(animation => animation.cancel());
      exitingActivity?.remove();
      exitingActivity = null;
      const windowNode = document.createElement('main');
      windowNode.className = 'app-screen settings-screen live-activity-window';
      const header = sourceMain().querySelector('.app-header').cloneNode(true);
      const layoutNode = document.createElement('div');
      layoutNode.className = 'settings-layout';
      const sidebar = sourceMain().querySelector('.settings-sidebar').cloneNode(true);
      const detailStage = document.createElement('div');
      detailStage.className = 'live-detail-stage';
      const panel = newPanel('activity-pane', detailStage);
      const label = document.createElement('strong');
      label.className = 'activity-boundary';
      label.textContent = 'Separate internal Activity simulation · sample fixtures';
      layoutNode.append(sidebar, detailStage);
      windowNode.append(header, layoutNode, label);
      canvas.insertBefore(windowNode, overlays);
      activity = { window: windowNode, panel };
    }
    populate(activity.panel, route);
    activity.panel.node.hidden = false;
    activity.panel.node.inert = false;
    activity.panel.node.setAttribute('aria-hidden', 'false');
    restoreScroll(activity.panel);
    const duration = matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 160;
    activity.window.getAnimations().forEach(animation => animation.cancel());
    activity.window.animate([{ opacity: 0 }, { opacity: 1 }], { duration });
    lastSourceMain = sourceMain();
    syncChrome();
    syncOverlays();
  }

  function dismissActivity(destination = null) {
    saveScroll(activity.panel);
    const returning = activityReturn;
    const outgoing = activity.window;
    activity = null;
    activityReturn = null;
    activityHistory.length = 0;
    host = returning.host;
    activeTab = returning.tab;
    const route = destination?.route || returning.route;
    navigate(route.screen, route.category, destination?.options || { push: false });
    // A solid, already-rendered parent stays beneath the outgoing simulated window.
    outgoing.inert = true;
    outgoing.setAttribute('aria-hidden', 'true');
    exitingActivity?.getAnimations().forEach(animation => animation.cancel());
    exitingActivity?.remove();
    exitingActivity = outgoing;
    const duration = matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 160;
    outgoing.getAnimations().forEach(animation => animation.cancel());
    const animation = outgoing.animate([{ opacity: 1 }, { opacity: 0 }], { duration });
    animation.finished.catch(() => {}).then(() => {
      outgoing.remove();
      if (exitingActivity === outgoing) exitingActivity = null;
    });
  }

  function navigate(screen, category, options = {}) {
    cancelAppHold();
    if (fullscreen.has(screen)) {
      return simulate(screen, 'Fullscreen handoff');
    }
    const route = { screen, category: category || pages.get(screen)?.category || current.category,
      ...(screen === 'stories' && options.reader ? { reader: options.reader } : {}) };
    const push = options.push !== false;
    if (internalActivities.has(screen)) return presentActivity(route, push, options.alreadyRendered);
    if (host === 'activity') {
      return dismissActivity({ route, options });
    }
    const enteringSettings = screen === 'settings' || (
      pages.has(screen) && !(host === 'launcher' && activeTab === 'tools' && toolIds.has(screen)));
    if (enteringSettings) {
      if (host !== 'settings') {
        saveScroll(tabs.get(activeTab));
        settingsReturn = activeTab;
        settingsHistory.length = 0;
        settingsHistory.push({ screen: 'settings', category: route.category });
      }
      host = 'settings';
      current = route;
      launcher.hidden = true;
      settings.hidden = false;
      presentSettings(route, push, options.alreadyRendered);
    } else {
      host = 'launcher';
      launcher.hidden = false;
      settings.hidden = true;
      presentTab(route, push, options.alreadyRendered, options.switching);
    }
    lastSourceMain = sourceMain();
    syncChrome();
    syncOverlays();
  }

  function back() {
    cancelAppHold();
    if (localSimulation) return closeSimulation();
    if (staging.querySelector('.modal-layer') && !current.reader) {
      renderer.showAction('close');
      lastSourceMain = sourceMain();
      syncOverlays();
      return;
    }
    const history = activeHistory();
    if (history.length > 1) {
      saveScroll(activePanel());
      history.pop();
      const route = history.at(-1);
      return navigate(route.screen, route.category, { push: false });
    }
    if (host === 'activity') return dismissActivity();
    if (host === 'settings') {
      host = 'launcher';
      return navigate(settingsReturn, null, { push: false, switching: true });
    }
    if (activeTab !== 'home') return navigate('home', null, { push: false, switching: true });
  }

  function simulate(screen, kind) {
    localSimulation = true;
    modalFocus = document.activeElement;
    const layer = document.createElement('div');
    layer.className = 'modal-layer';
    const modal = document.createElement('section');
    modal.className = 'modal';
    modal.setAttribute('role', 'dialog');
    modal.setAttribute('aria-modal', 'true');
    modal.setAttribute('aria-labelledby', 'handoff-title');
    const label = document.createElement('strong');
    label.className = 'live-handoff-label';
    label.textContent = `${kind} simulation`;
    const title = document.createElement('h2');
    title.id = 'handoff-title';
    title.textContent = routeTitle({ screen });
    const description = document.createElement('p');
    description.textContent = 'This destination leaves the retained native shell. This browser demo marks the handoff only; it does not open Android, another app, a camera, or an emulator.';
    const close = button(`Return to ${routeTitle(current)}`, 'close-simulation');
    modal.append(label, title, description, close);
    layer.append(modal);
    overlays.replaceChildren(layer);
    launcher.inert = true;
    settings.inert = true;
    if (activity) activity.window.inert = true;
    close.focus();
  }

  function closeSimulation() {
    localSimulation = false;
    syncOverlays();
    modalFocus?.isConnected && modalFocus.focus({ preventScroll: true });
  }

  function targetSelector(element) {
    if (element.id) return `#${CSS.escape(element.id)}`;
    const attributes = [...element.attributes].filter(attribute =>
      attribute.name.startsWith('data-') && !attribute.name.startsWith('data-live'));
    if (attributes.length) return element.tagName.toLowerCase() + attributes
      .map(attribute => `[${attribute.name}="${CSS.escape(attribute.value)}"]`).join('');
    if (element.getAttribute('aria-label')) {
      return `${element.tagName.toLowerCase()}[aria-label="${CSS.escape(element.getAttribute('aria-label'))}"]`;
    }
    if (element.type === 'submit' && element.form?.id) return `#${CSS.escape(element.form.id)} [type="submit"]`;
    return null;
  }

  function sourceTarget(element) {
    const selector = targetSelector(element);
    if (!selector) return null;
    const scope = element.closest('.modal') ? staging.querySelector('.modal') : staging;
    return scope?.querySelector(selector);
  }

  function mirrorInputs() {
    const scope = overlays.querySelector('.modal') || activePanel()?.node;
    scope?.querySelectorAll('input,textarea,select').forEach(element => {
      const target = sourceTarget(element);
      if (!target) return;
      target.value = element.value;
      if (element.type === 'checkbox') target.checked = element.checked;
    });
  }

  function refreshFromSource() {
    const route = routeFromSource();
    if (routeKey(route) !== routeKey(current)) {
      navigate(route.screen, route.category, { alreadyRendered: true });
      return;
    }
    const nextRevision = revision();
    const panel = activePanel();
    populate(panel, current);
    // Other retained panes refresh lazily only if shared fixture data changes.
    fixtureRevision = nextRevision;
    lastSourceMain = sourceMain();
    syncChrome();
    syncOverlays();
  }

  function forward(element, type) {
    mirrorInputs();
    if (type === 'click' && element.dataset.action === 'page' && activeTab === 'home' &&
        ['home', 'edit'].includes(current.screen)) {
      homePage = (homePage + Number(element.dataset.dir) + homeProjection.pageCount) % homeProjection.pageCount;
    }
    const target = sourceTarget(element);
    if (type === 'click') {
      if (target) {
        // The visible form forwards submission separately. Fixture action buttons
        // omit type="button"; do not forward their implicit submit activation.
        const click = new MouseEvent('click', { bubbles: true, cancelable: true });
        click.preventDefault();
        target.dispatchEvent(click);
      }
      else if (element.dataset.action) {
        const { action, ...data } = element.dataset;
        renderer.showAction(action, data);
      } else throw new Error('No approved fixture action for this control.');
    } else if (type === 'submit') {
      const form = staging.querySelector(`#${CSS.escape(element.id)}`);
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    } else if (target) {
      target.value = element.value;
      if (element.type === 'checkbox') target.checked = element.checked;
      target.dispatchEvent(new Event(type, { bubbles: true }));
    }
    refreshFromSource();
    if (type === 'input' && element.id === 'settings-search') {
      const sidebar = element.closest('.settings-sidebar') || settingsSidebar;
      sidebar.querySelector('.settings-results')?.remove();
      const results = staging.querySelector('.settings-results');
      if (results) sidebar.querySelector('.search').append(results.cloneNode(true));
    }
  }

  function appMenuTile(target) {
    const tile = target?.closest?.('.app-tile[data-live-app-menu]');
    return host === 'launcher' && activeTab === 'apps' && current.screen === 'apps' &&
      tile && canvas.contains(tile) && !tile.disabled && !tile.closest('[hidden],[inert]') ? tile : null;
  }

  function cancelAppHold() {
    if (appHold) clearTimeout(appHold.timer);
    appHold = null;
  }

  function openAppMenu(tile) {
    cancelAppHold();
    // Use the frozen renderer's app-menu action and overlay/focus handling.
    tile.focus({ preventScroll: true });
    renderer.showAction('app-menu', { pkg: tile.dataset.pkg });
    refreshFromSource();
  }

  function startAppHold(event) {
    cancelAppHold(); // A second contact cancels the gesture.
    if (!event.isPrimary || event.button !== 0) return;
    suppressedAppClick = null; // A new gesture may activate a menu action or launch normally.
    const tile = appMenuTile(event.target);
    if (!tile) return;
    const pending = { tile, pointerId: event.pointerId, x: event.clientX, y: event.clientY };
    appHold = pending;
    pending.timer = setTimeout(() => {
      if (appHold !== pending || !appMenuTile(tile)) return cancelAppHold();
      suppressedAppClick = tile;
      openAppMenu(tile);
    }, appHoldDelay);
    // No preventDefault or forced capture: native touch scrolling must remain available.
  }

  function moveAppHold(event) {
    if (appHold?.pointerId === event.pointerId &&
        Math.hypot(event.clientX - appHold.x, event.clientY - appHold.y) > appHoldSlop) cancelAppHold();
  }

  function onClick(event) {
    const control = event.target.closest('button,[data-action]');
    // A release can land on the newly opened overlay or on the tile after Escape.
    // Keep suppression until the next pointerdown; keyboard menu actions still work.
    if (suppressedAppClick && (event.detail > 0 || control === suppressedAppClick)) {
      event.preventDefault();
      event.stopImmediatePropagation();
      return;
    }
    if (!control || control.disabled) return;
    if (control.type === 'submit' && control.form && !control.dataset.action) return;
    if (control.dataset.liveAction) {
      event.preventDefault();
      return control.dataset.liveAction === 'back' ? back() : closeSimulation();
    }
    const data = control.dataset;
    if (data.action === 'story' && host === 'launcher' && activeTab === 'tools') {
      event.preventDefault();
      return navigate('stories', null, { reader: data.name });
    }
    if (data.action === 'camera-view') {
      event.preventDefault();
      return simulate('camera-preview', 'Fullscreen handoff');
    }
    if (data.action === 'header-pair') {
      event.preventDefault();
      return navigate('phone');
    }
    if (data.action === 'go') {
      event.preventDefault();
      if (control === customizeControl && control.hasAttribute('data-live-widget-done')) {
        const history = tabs.get('home').history;
        // Done leaves the editor flow, including any nested Add-widget library visits.
        while (homePages.has(history.at(-1)?.screen)) history.pop();
        return navigate('home', history.at(-1)?.category, { push: false });
      }
      if (settingsHeader.contains(control) && data.screen === 'home') {
        saveScroll(activePanel());
        host = 'launcher';
        return navigate('home', null, { push: false, switching: true });
      }
      return navigate(data.screen, null, { switching: launcherDock.contains(control) && tabIds.includes(data.screen) });
    }
    if (data.action === 'category') {
      event.preventDefault();
      saveScroll(activePanel());
      settingsHistory.length = 0;
      return navigate('settings', data.id);
    }
    if (data.action === 'back') { event.preventDefault(); return back(); }
    if (data.action === 'cancel-form') {
      event.preventDefault();
      // Let the original fixture restore its atomic form snapshot, then use our local Back.
      mirrorInputs();
      renderer.showAction('cancel-form');
      back();
      return;
    }
    if (data.action) {
      event.preventDefault();
      forward(control, 'click');
    }
  }

  function fit() {
    const scale = Math.min(viewport.clientWidth / 1920, viewport.clientHeight / 1280);
    canvas.style.transform = `scale(${scale})`;
    canvas.style.left = `${(viewport.clientWidth - 1920 * scale) / 2}px`;
    canvas.style.top = `${(viewport.clientHeight - 1280 * scale) / 2}px`;
  }

  // Construct each visible shell once. All future navigation only changes their content.
  sourceNavigate({ screen: 'settings', category: 'appearance' });
  const settings = document.createElement('main');
  settings.className = 'app-screen settings-screen live-settings';
  settings.hidden = true;
  const settingsHeader = sourceMain().querySelector('.app-header').cloneNode(true);
  const settingsSidebar = sourceMain().querySelector('.settings-sidebar').cloneNode(true);
  const layout = document.createElement('div');
  layout.className = 'settings-layout';
  const settingsStage = document.createElement('div');
  settingsStage.className = 'live-detail-stage';
  layout.append(settingsSidebar, settingsStage);
  settings.append(settingsHeader, layout);

  sourceNavigate({ screen: 'home', category: 'appearance' });
  const launcher = document.createElement('main');
  launcher.className = 'app-screen live-launcher';
  const launcherHeader = sourceMain().querySelector('.app-header').cloneNode(true);
  const launcherDock = sourceMain().querySelector('.dock').cloneNode(true);
  const customizeControl = launcherHeader.querySelector('[data-screen="edit"]');
  const tabStage = document.createElement('div');
  tabStage.className = 'live-tab-stage';
  launcher.append(launcherHeader, tabStage, launcherDock);
  for (const id of tabIds) {
    const panel = newPanel('native-tab-panel', tabStage);
    panel.history = [{ screen: id, category: 'appearance' }];
    panel.node.dataset.tab = id;
    tabs.set(id, panel);
  }
  const overlays = document.createElement('div');
  overlays.id = 'live-overlays';
  canvas.append(launcher, settings, overlays);
  document.addEventListener('pointerdown', startAppHold, true);
  canvas.addEventListener('contextmenu', event => {
    const tile = appMenuTile(event.target);
    if (!tile) return;
    event.preventDefault();
    suppressedAppClick = tile;
    openAppMenu(tile);
  });
  document.addEventListener('pointermove', moveAppHold, true);
  for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) {
    document.addEventListener(type, event => {
      if (appHold?.pointerId === event.pointerId) cancelAppHold();
    }, true);
  }
  document.addEventListener('scroll', cancelAppHold, true);
  window.addEventListener('blur', cancelAppHold);
  document.addEventListener('visibilitychange', cancelAppHold);
  canvas.addEventListener('click', onClick);
  canvas.addEventListener('input', event => {
    if (event.target.type !== 'checkbox') forward(event.target, 'input');
  });
  canvas.addEventListener('change', event => forward(event.target, 'change'));
  canvas.addEventListener('submit', event => {
    event.preventDefault();
    forward(event.target, 'submit');
  });
  document.getElementById('demo-back').addEventListener('click', back);
  document.getElementById('demo-theme').addEventListener('change', event => {
    if (event.target.value !== renderer.getState().theme) renderer.showAction('theme');
    refreshFromSource();
  });
  document.addEventListener('keydown', event => {
    const tile = appMenuTile(event.target);
    if (tile && (event.key === 'Enter' || event.key === ' ')) suppressedAppClick = null;
    if (tile && (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10'))) {
      event.preventDefault();
      event.stopImmediatePropagation();
      if (!event.repeat) openAppMenu(tile);
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopImmediatePropagation();
      return back();
    }
    // Keep the frozen renderer's spatial keyboard handler away from the hidden staging DOM.
    if (event.key === 'Tab' || event.key.startsWith('Arrow')) {
      event.stopImmediatePropagation();
      const modal = overlays.querySelector('.modal');
      const scope = modal || (host === 'activity' ? activity.window : host === 'settings' ? settings : launcher);
      const controls = [...scope.querySelectorAll('button,input,select,textarea')]
        .filter(element => !element.disabled && !element.closest('[inert],[hidden]') && element.getClientRects().length);
      if (event.key === 'Tab' && modal) {
        event.preventDefault();
        const index = controls.indexOf(document.activeElement);
        controls[(index + (event.shiftKey ? -1 : 1) + controls.length) % controls.length]?.focus();
      } else if (event.key.startsWith('Arrow') && !['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement.tagName)) {
        const rect = document.activeElement.getBoundingClientRect();
        const vertical = ['ArrowUp', 'ArrowDown'].includes(event.key);
        const direction = ['ArrowUp', 'ArrowLeft'].includes(event.key) ? -1 : 1;
        const candidates = controls.filter(element => element !== document.activeElement).map(element => {
          const next = element.getBoundingClientRect();
          const dx = next.x + next.width / 2 - rect.x - rect.width / 2;
          const dy = next.y + next.height / 2 - rect.y - rect.height / 2;
          return { element, main: vertical ? dy : dx, cross: vertical ? dx : dy };
        }).filter(item => item.main * direction > 2)
          .sort((a, b) => Math.abs(a.main) + 2 * Math.abs(a.cross) - Math.abs(b.main) - 2 * Math.abs(b.cross));
        if (candidates[0]) { event.preventDefault(); candidates[0].element.focus(); }
      }
    }
  }, true);
  new MutationObserver(() => {
    if (ready && sourceMain() !== lastSourceMain) refreshFromSource();
    else if (ready && !staging.querySelector('.toast')) overlays.querySelector('.toast')?.remove();
  }).observe(staging, { childList: true });
  // Existing fixture timer text is copied, not recomputed or represented as Android execution.
  setInterval(() => {
    canvas.querySelectorAll('[data-timer],#sleep-remaining').forEach(element => {
      const source = sourceTarget(element);
      if (source) element.textContent = source.textContent;
    });
  }, 1000);
  window.addEventListener('resize', fit);
  const requestedTab = new URLSearchParams(location.search).get('screen');
  navigate(tabIds.includes(requestedTab) ? requestedTab : 'home', 'appearance', { push: false });
  ready = true;
  fit();
  window.__portalNavigationDemo = {
    navigate, back,
    getState: () => ({
      host, tab: activeTab, route: { ...current },
      history: activeHistory().map(route => ({ ...route })),
      theme: renderer.getState().theme,
      retainedTabs: tabs.size, retainedSettings: settingsPanes.size,
      settingsKeys: [...settingsPanes.keys()],
      internalActivity: !!activity,
      homePage, homePageCount: homeProjection?.pageCount || 1,
      simulation: localSimulation || !!overlays.querySelector('.external-note'),
      fixtureRevisionChanged: revision() !== fixtureRevision,
    }),
  };
})();
