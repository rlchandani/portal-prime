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
  const toolIds = new Set(model.tools.map(tool => tool[0]));
  // Current native boundary supplied by the implementation owner, not inferred from
  // the frozen mock's older Activity inventory.
  const internalActivities = new Set([
    'immich', 'albums', 'smb', 'dav', 'folder', 'dismiss', 'album', 'web',
    'calendar-url', 'phone', 'help', 'apk', 'ha-entities',
  ]);
  const fullscreen = new Set([
    'photos', 'digital-preview', 'welcome-preview', 'wake-light', 'lamp-preview',
    'alarm-ring', 'ha-dashboard', 'provider-widgets', 'folder-preview', 'music', 'camera', 'lamp',
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

  const routeKey = route => `${route.category}/${route.screen}`;
  const routeTitle = route => route.screen === 'settings'
    ? model.categories.find(category => category[0] === route.category)?.[1] || 'Settings'
    : pages.get(route.screen)?.title || ({
      home: 'Home', apps: 'Apps', tools: 'Tools', store: 'App Store',
      'app-detail': 'App details', 'app-folder': 'App folder', edit: 'Customize home',
      widgets: 'Home & widgets', switcher: 'Recent apps', music: 'Music',
      photos: 'Photo display', 'digital-preview': 'Digital clock preview',
      'welcome-preview': 'Welcome preview', 'wake-light': 'Wake light',
      'lamp-preview': 'Lamp', 'alarm-ring': 'Alarm ringing',
      'ha-dashboard': 'Home Assistant', 'provider-widgets': 'Android widget providers',
      'folder-preview': 'Folder preview',
    })[route.screen] || route.screen;
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
    return { screen: state.screen, category: state.category };
  };
  const activePanel = () => host === 'activity' ? activity.panel : host === 'settings'
    ? settingsPanes.get(activeSettingsKey)
    : tabs.get(activeTab);
  const activeHistory = () => host === 'activity' ? activityHistory
    : host === 'settings' ? settingsHistory : tabs.get(activeTab).history;

  function sourceNavigate(route) {
    renderer.navigate(route.screen, route.category);
    lastSourceMain = sourceMain();
  }

  // Only fixture data changes invalidate retained HTML. Navigation itself never does.
  function revision() {
    const state = renderer.getState();
    return JSON.stringify({
      values: state.values, widgets: state.widgets, source: state.source,
      collections: state.collections, widgetSizes: state.widgetSizes, advanced: state.advanced,
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
      .map(element => ({ top: element.scrollTop, left: element.scrollLeft }));
    scrollPositions.set(routeKey(panel.route), positions);
  }

  function restoreScroll(panel) {
    const positions = scrollPositions.get(routeKey(panel.route));
    if (!positions) return;
    panel.node.querySelectorAll('.screen-body,.detail-body,.home-grid').forEach((element, index) => {
      element.scrollTop = positions[index]?.top || 0;
      element.scrollLeft = positions[index]?.left || 0;
    });
  }

  function cloneContent(route) {
    const main = sourceMain();
    const source = main.querySelector('.home-body,.screen-body,.detail-body');
    if (!source) throw new Error(`No approved fixture content for ${route.screen}`);
    const content = source.cloneNode(true);
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
      panel.node.replaceChildren(content);
      panel.signature = signature;
      panel.route = { ...route };
      restoreScroll(panel);
      restoreFocus(focus);
    }
    panel.route = { ...route };
    panel.revision = nextRevision;
    panel.node.classList.toggle('home-screen', route.screen === 'home');
    if (route.screen === 'home') {
      panel.node.style.background = sourceMain().style.background;
      panel.node.classList.toggle('grain-background', sourceMain().classList.contains('grain-background'));
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
    launcherHeader.querySelector('[data-screen="edit"]').hidden = activeTab !== 'home';
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
    const sourceModal = staging.querySelector('.modal-layer');
    const sourceToast = staging.querySelector('.toast');
    const hadModal = !!overlays.querySelector('.modal-layer');
    const focus = captureFocus();
    overlays.replaceChildren();
    if (sourceModal) {
      const modal = sourceModal.cloneNode(true);
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
    if (fullscreen.has(screen)) {
      return simulate(screen, 'Fullscreen handoff');
    }
    const route = { screen, category: category || pages.get(screen)?.category || current.category };
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
    if (localSimulation) return closeSimulation();
    if (staging.querySelector('.modal-layer')) {
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

  function onClick(event) {
    const control = event.target.closest('button,[data-action]');
    if (!control || control.disabled) return;
    if (control.type === 'submit' && control.form && !control.dataset.action) return;
    if (control.dataset.liveAction) {
      event.preventDefault();
      return control.dataset.liveAction === 'back' ? back() : closeSimulation();
    }
    const data = control.dataset;
    if (data.action === 'header-pair') {
      event.preventDefault();
      return navigate('phone');
    }
    if (data.action === 'go') {
      event.preventDefault();
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
  navigate('home', 'appearance', { push: false });
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
      simulation: localSimulation || !!overlays.querySelector('.external-note'),
      fixtureRevisionChanged: revision() !== fixtureRevision,
    }),
  };
})();
