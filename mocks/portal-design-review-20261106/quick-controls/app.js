// Quick controls redesign mock. All device state is simulated from the URL:
// ?view=setup|home&jarvis=1&dash=1&ha=1&check=0&tiles=rec|current&theme=dark|light&capture=1
(() => {
  const $ = id => document.getElementById(id);
  const params = new URLSearchParams(location.search);
  const flag = (k, d) => (params.has(k) ? params.get(k) === '1' : d);
  let s = {
    view: params.get('view') === 'home' ? 'home' : 'setup',
    jarvis: flag('jarvis', true), dash: flag('dash', false), ha: flag('ha', true),
    check: flag('check', false), tiles: params.get('tiles') === 'current' ? 'current' : 'rec',
    theme: params.get('theme') === 'light' ? 'light' : 'dark',
  };
  if (params.get('capture') === '1') document.body.classList.add('capture');

  const ICON = {
    mic: '<path d="M12 3a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM6 11a6 6 0 0 0 12 0M12 17v4"/>',
    house: '<path d="m3.5 11 8.5-7.5 8.5 7.5M6 9.5V20h12V9.5"/><path d="M10 20v-5h4v5"/>',
    ha: '<path d="M12 3 3 11h2.5v9h13v-9H21z"/><circle cx="12" cy="14" r="2.2"/>',
    bulb: '<path d="M9 17h6M10 20h4M8.5 14C7 12.8 6 11 6 9a6 6 0 0 1 12 0c0 2-1 3.8-2.5 5z"/>',
    film: '<rect x="4" y="5" width="16" height="14" rx="2"/><path d="M4 9h16M9 5v4M15 5v4"/>',
    therm: '<path d="M10 5a2 2 0 0 1 4 0v9a4 4 0 1 1-4 0z"/>',
    out: '<path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/>',
    chev: '<path d="m9 5 7 7-7 7"/>',
  };
  const svg = (k, cls) => `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true">${ICON[k]}</svg>`;

  // Device truth after discovery. null = still checking.
  const inv = () => ({
    jarvis: s.check ? null : s.jarvis,
    ha: s.check ? null : s.ha,
    dash: s.dash, // a saved address is known immediately
  });

  function tiles() {
    const d = inv(), out = [];
    if (d.jarvis) out.push({ id: 'jarvis', icon: 'mic', label: 'Hey Jarvis', sub: 'Ask your assistant' });
    if (d.dash) out.push({ id: 'dash', icon: 'house', label: 'Home dashboard', sub: 'Opens in the browser', ext: true });
    else if (d.ha && s.tiles === 'rec') out.push({ id: 'ha', icon: 'ha', label: 'Home Assistant', sub: 'Opens the app', ext: true });
    else if (d.ha) [['bulb', 'Lights'], ['film', 'Scenes'], ['therm', 'Climate'], ['ha', 'Home']]
      .forEach(([icon, label]) => out.push({ id: 'ha', icon, label, sub: 'Opens Home Assistant', ext: true }));
    if (s.check) out.push({ placeholder: true });
    return out;
  }

  function widgetBody() {
    const t = tiles();
    if (!t.length) {
      return `<div class="empty"><p>Add your home dashboard, or install Jarvis or Home Assistant.</p>
        <button class="pill" type="button" data-go="setup">Set up Quick controls</button></div>`;
    }
    return `<div class="tiles">${t.map((x, i) => {
      const wide = t.length % 2 === 1 && i === t.length - 1 ? ' wide' : '';
      if (x.placeholder) return `<div class="tile placeholder${wide}" aria-label="Checking apps"><small>Checking apps…</small></div>`;
      return `<button class="tile${wide}" type="button" data-launch="${x.id}">${svg(x.icon, 'glyph')}${x.ext ? svg('out', 'out') : ''}<strong>${x.label}</strong><small>${x.sub}</small></button>`;
    }).join('')}</div>`;
  }

  function summary() {
    const d = inv(), t = tiles().filter(x => !x.placeholder);
    if (s.check) return d.dash ? 'Checking which apps can open. <b>Your dashboard</b> is ready now.' : 'Checking which apps can open on this Portal…';
    if (!t.length) return 'Nothing to show on Home yet. Save a dashboard address, or install Jarvis or Home Assistant.';
    if (!d.dash && d.ha && s.tiles === 'current') {
      return `Home shows ${d.jarvis ? '<b>Hey Jarvis</b> and ' : ''}<b>four Home Assistant tiles</b>. All four open the same app.`;
    }
    const names = t.map(x => x.id === 'dash' ? '<b>your Home dashboard</b>' : `<b>${x.label}</b>`);
    return `Home shows ${names.join(' and ')}.`;
  }

  function status(kind, text, chevron) {
    return `<span class="status ${kind}"><span class="dot"></span>${text}${chevron ? svg('chev', '') : ''}</span>`;
  }

  function sources() {
    const d = inv();
    const dashRow = `<li><button class="source" type="button" data-open="dashboard">
        <strong>Home dashboard</strong>
        ${d.dash ? status('on', 'In use', true) : status('', 'Set up', true)}
        <span class="why">${d.dash ? 'Saved as home.example. Sign-in happens in the browser.' : 'Your Home Assistant dashboard, in the browser. No app needed.'}</span>
      </button></li>`;
    const jarvisStatus = d.jarvis === null ? status('wait', 'Checking') : d.jarvis ? status('on', 'In use') : status('', 'Not installed');
    const jarvisRow = `<li class="source"><strong>Jarvis</strong>${jarvisStatus}<span class="why">Adds Hey Jarvis.</span></li>`;
    let haStatus, haWhy;
    if (d.ha === null) { haStatus = status('wait', 'Checking'); haWhy = 'Used only when no dashboard is saved.'; }
    else if (d.dash) { haStatus = status('idle', 'Not needed'); haWhy = 'Your dashboard takes its place.'; }
    else if (d.ha) {
      haStatus = status('on', 'In use');
      haWhy = s.tiles === 'rec' ? 'Adds one tile that opens the app.' : 'Adds Lights, Scenes, Climate and Home. Each opens the app.';
    } else { haStatus = status('', 'Not installed'); haWhy = 'Or save a dashboard address instead.'; }
    const haRow = `<li class="source"><strong>Home Assistant app</strong>${haStatus}<span class="why">${haWhy}</span></li>`;
    return dashRow + jarvisRow + haRow;
  }

  function appsCta() {
    const d = inv();
    const missing = d.jarvis === false || (d.ha === false && !d.dash);
    if (!missing) return '';
    return `<div class="apps-cta"><p>Apps are installed from the Apps tab.</p>
      <button class="ghost" type="button" data-open="apps">Open Apps</button></div>`;
  }

  let toastTimer;
  function toast(html) {
    const el = $('toast'); el.innerHTML = html; el.hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => { el.hidden = true; }, 3200);
  }
  const SIM = {
    jarvis: '<b>Simulated.</b> Opens Jarvis and sends its wake handoff.',
    dash: '<b>Simulated.</b> Opens the saved dashboard in the browser.',
    ha: '<b>Simulated.</b> Opens the Home Assistant app. It does not switch anything.',
    dashboard: '<b>Simulated.</b> Opens the existing Home Assistant dashboard page to edit the address.',
    apps: '<b>Simulated.</b> Leaves Settings for the Apps tab.',
  };

  function render() {
    document.documentElement.dataset.theme = s.theme;
    $('device').dataset.view = s.view;
    const body = widgetBody();
    $('widget-body').innerHTML = body;
    $('preview-stage').innerHTML = `<div class="widget-head"><span>Quick controls</span></div>${body}`;
    $('summary').innerHTML = summary();
    $('sources').innerHTML = sources();
    $('apps-cta').innerHTML = appsCta();
    syncReview();
  }

  document.addEventListener('click', e => {
    const b = e.target.closest('button'); if (!b) return;
    if (b.dataset.go) { s.view = b.dataset.go; render(); }
    else if (b.dataset.launch) toast(SIM[b.dataset.launch]);
    else if (b.dataset.open) toast(SIM[b.dataset.open]);
  });

  // ---- review chrome ----
  const form = $('review-panel');
  $('review-toggle').addEventListener('click', () => {
    form.hidden = !form.hidden; $('review-toggle').setAttribute('aria-expanded', String(!form.hidden));
  });
  function syncReview() {
    for (const el of form.elements) {
      if (el.type === 'radio') el.checked = s[el.name] === el.value;
      if (el.type === 'checkbox') el.checked = s[el.name];
    }
    const q = new URLSearchParams({ view: s.view, jarvis: +s.jarvis, dash: +s.dash, ha: +s.ha, check: +s.check, tiles: s.tiles, theme: s.theme });
    history.replaceState(null, '', `?${q}`);
  }
  form.addEventListener('change', e => {
    const el = e.target;
    s = { ...s, [el.name]: el.type === 'checkbox' ? el.checked : el.value };
    render();
  });

  render();
})();
