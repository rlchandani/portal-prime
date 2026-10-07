/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
(() => {
  'use strict';
  const params = new URLSearchParams(location.search);
  if (!params.has('screen')) {
    params.set('screen', 'tools');
    history.replaceState(null, '', `${location.pathname}?${params}`);
  }
  const tools = [
    ['alarm', 'Alarm', 'Wake up on your schedule.', 'Everyday', 'bell', 'var(--tools-glyph-warm)'],
    ['timers', 'Timers', 'A few minutes, or a few hours.', 'Everyday', 'timer', 'var(--tools-glyph-warm)'],
    ['countdowns', 'Countdowns', 'Keep a date worth looking forward to.', 'Everyday', 'cal', 'var(--tools-glyph-blue)'],
    ['converter', 'Converter', 'Measurements and currencies.', 'Everyday', 'calc', 'var(--tools-glyph-blue)'],
    ['lamp', 'Lamp', 'A little light for your room.', 'Everyday', 'bulb', 'var(--tools-glyph-amber)'],
    ['notes', 'Leave a note', 'A message for whoever comes next.', 'Everyday', 'notes', 'var(--tools-glyph-amber)'],
    ['camera', 'Cameras', 'Your saved home camera views.', 'Home & downtime', 'cameraLens', 'var(--tools-glyph-green)'],
    ['intercom', 'Intercom', 'Broadcast or listen across the house.', 'Home & downtime', 'megaphone', 'var(--tools-glyph-green)'],
    ['stories', 'Bedtime story', 'Something gentle before sleep.', 'Home & downtime', 'book', 'var(--tools-glyph-purple)'],
    ['iss', 'ISS passes', 'Know when to look up.', 'Discover', 'globe', 'var(--tools-glyph-blue)'],
    ['aurora', 'Aurora outlook', 'Check the northern lights forecast.', 'Discover', 'weather', 'var(--tools-glyph-green)'],
    ['speed', 'Speed test', 'Check your connection.', 'Advanced', 'wifi', 'var(--tools-glyph-blue)'],
  ].map(([id, name, description, group, glyph, color]) => ({ id, name, description, group, glyph, color }));
  const byId = new Map(tools.map(t => [t.id, t]));
  const state = {
    tool: byId.has(params.get('tool')) ? params.get('tool') : null,
    advanced: params.get('advanced') === '1', scenario: params.get('scenario') || 'normal',
    group: 'All tools', query: '', sheet: null, picker: null, full: null, story: null, lampControls: true,
    alarm: { enabled: true, hour: 7, minute: 30, days: [1, 2, 3, 4, 5] },
    timers: [], countdowns: [{ id: 1, name: 'Birthday', icon: '🎂', date: '2026-11-12', yearly: true }],
    cameras: [{ id: 1, name: 'Living room', url: 'rtsp://sample-camera.local/live' }],
    lamp: { brightness: 65, warmth: 70, red: false },
    note: 'Dinner is in the fridge. Enjoy your evening!', noteDraft: null, memo: 'none',
    intercom: 'idle', address: '192.168.0.71', reading: false,
    converter: { kind: 'Length', value: '1', from: 'm', to: 'ft' },
    speed: 'idle', speedStep: 0, networkFresh: false,
  };
  if (state.tool === 'speed') state.advanced = true;
  let root, active = true, timerTick, speedTimeout, toastTimeout;
  let libraryScroll = 0, returnFocus = null, listInitialized = false;
  const workScroll = new Map();
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  const icon = name => {
    const extra = {
      cameraLens: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M8 5 9.5 3h5L16 5h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z"/><circle cx="12" cy="13" r="4"/></svg>',
      book: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5C8 2 4 3 2 4v15c4-2 7-1 10 1 3-2 6-3 10-1V4c-2-1-6-2-10 1ZM12 5v15M5 7l4 1M15 8l4-1"/></svg>',
    };
    const svg = extra[name] || typeof I !== 'undefined' && I[name] || (typeof I !== 'undefined' && I.notes) || '';
    return svg.replace(/stroke="#fff"/g, 'stroke="currentColor"').replace(/fill="#fff"/g, 'fill="currentColor"')
      .replace('<svg ', '<svg aria-hidden="true" ');
  };
  const btn = (label, action, extra = '', cls = 'secondary-button') =>
    `<button type="button" class="${cls}" data-tool-action="${action}" ${extra}>${label}</button>`;
  const field = (label, name, value, type = 'text', extra = '') =>
    `<label class="tool-field">${label}<input name="${name}" type="${type}" value="${esc(value)}" ${extra}></label>`;
  const select = (label, name, options, value) =>
    `<label class="tool-field">${label}<select name="${name}">${options.map(o => `<option ${o === value ? 'selected' : ''}>${esc(o)}</option>`).join('')}</select></label>`;
  const panel = (title, body, extra = '') =>
    `<section class="tool-panel"><div class="panel-heading"><h2>${title}</h2>${extra}</div>${body}</section>`;
  const row = (title, detail, action = '') =>
    `<div class="panel-row"><div><strong>${esc(title)}</strong><p class="muted">${esc(detail)}</p></div>${action}</div>`;
  const empty = (glyph, title, message, action = '') =>
    `<div class="empty-state">${icon(glyph)}<h2>${title}</h2><p class="muted">${message}</p>${action}</div>`;
  const status = (text, on = false) => `<p class="status-line"><span class="status-dot ${on ? 'on' : ''}"></span>${text}</p>`;
  const stamp = '<span class="demo-badge">Sample data</span>';
  const visibleTools = () => tools.filter(t => t.id !== 'speed' || state.advanced);
  const offline = () => state.scenario === 'offline';
  const nothing = () => state.scenario === 'empty';
  const denied = () => state.scenario === 'permission';
  const time = () => `${String(state.alarm.hour).padStart(2, '0')}:${String(state.alarm.minute).padStart(2, '0')}`;
  const timerRemaining = t => {
    const sec = Math.max(0, Math.ceil((t.end - Date.now()) / 1000));
    return `${Math.floor(sec / 3600) ? Math.floor(sec / 3600) + ':' : ''}${String(Math.floor(sec / 60) % 60).padStart(2, '0')}:${String(sec % 60).padStart(2, '0')}`;
  };
  const storyTitles = ['The Tortoise and the Hare', 'The Lion and the Mouse', 'The Little Red Hen', 'The North Wind and the Sun', 'The Ant and the Grasshopper'];
  // Bundled public-domain text matches the native Stories library.
  const storyText = [
    ['A speedy hare once made fun of a slow-moving tortoise. Tired of the teasing, the tortoise said, “Let us have a race.” The hare laughed, but agreed.',
      'The fox set them off. The hare dashed almost out of sight at once, then stopped. To show how little he thought of the tortoise, he lay down beside the path to nap.',
      'The tortoise walked, and walked, and walked. He never stopped, not once, until he reached the finish line.',
      'The hare woke with a start and ran his hardest — but the tortoise had already won. “Slow and steady,” the tortoise smiled, “wins the race.”'],
    ['A great lion lay asleep in the sun. A little mouse ran across his nose and woke him. The lion caught the mouse in his huge paw.',
      '“Please let me go,” squeaked the mouse, “and one day I may help you.” The lion laughed at the thought, but he was kind, and let the little mouse go.',
      'Not long after, hunters caught the lion in a strong net. He roared and roared, but he could not get free.',
      'The little mouse heard him. She came and gnawed the ropes, one by one, until the lion was free. “You see,” said the mouse, “even a little friend can be a great friend.”'],
    ['A little red hen found some grains of wheat. “Who will help me plant the wheat?” she asked. “Not I,” said the cat, the dog, and the pig. “Then I will,” said the hen. And she did.',
      'When the wheat was tall, she asked, “Who will help me cut it?” “Not I,” they all said. “Then I will,” said the hen. And she did.',
      'She asked who would help carry it to the mill, and bake the bread. Each time they said, “Not I.” Each time the hen said, “Then I will.” And she did.',
      'At last the warm bread was ready. “Who will help me eat it?” “I will!” they all cried. “No,” said the little red hen. “I planted, and cut, and baked it myself. And I will eat it myself.” And she did.'],
    ['The North Wind and the Sun argued about who was stronger. They saw a traveler walking along, and agreed: whoever could make him take off his coat was the stronger.',
      'The North Wind blew first. He blew cold and hard. But the harder he blew, the tighter the traveler wrapped his coat around himself.',
      'Then it was the Sun’s turn. The Sun shone gently, warmer and warmer. Soon the traveler grew so warm that he took his coat right off.',
      '“Gentleness,” said the Sun, “can do what force cannot.”'],
    ['All summer long the grasshopper sang and played, while the ants worked hard, carrying food to store away.',
      '“Why work so hard?” laughed the grasshopper. “Come and sing with me!” But the ants kept working, gathering grain for the winter.',
      'When the cold winter came, the grasshopper had nothing to eat. The ants, warm in their nest, had plenty.',
      'The grasshopper learned that there is a time to play and a time to work — and it is wise to make ready while you can.'],
  ];
  const units = {
    Length: { m: 1, km: 1000, cm: .01, mm: .001, mi: 1609.344, yd: .9144, ft: .3048, in: .0254 },
    Mass: { kg: 1, g: .001, lb: .45359237, oz: .028349523125, st: 6.35029318 },
    Volume: { L: 1, mL: .001, 'gal (US)': 3.785411784, 'qt (US)': .946352946, 'cup (US)': .2365882365, 'fl oz (US)': .0295735295625 },
    Speed: { 'km/h': 1, 'm/s': 3.6, mph: 1.609344, kn: 1.852 },
    Temperature: { '°C': 1, '°F': 1, K: 1 },
    // Explicit sample rates, never presented as a live exchange-rate request.
    Currency: { EUR: 1.10, USD: 1, GBP: 1.29, RON: .22, PLN: .26, CHF: 1.17, SEK: .096, NOK: .094, DKK: .147, CAD: .74, AUD: .67, JPY: .0068, CZK: .044, HUF: .0028 },
  };
  function converted() {
    const c = state.converter;
    if (c.value.trim() === '' || !Number.isFinite(Number(c.value))) return 'Enter a valid number';
    let result;
    if (c.kind === 'Temperature') {
      const v = Number(c.value), base = c.from === '°F' ? (v - 32) * 5 / 9 : c.from === 'K' ? v - 273.15 : v;
      result = c.to === '°F' ? base * 9 / 5 + 32 : c.to === 'K' ? base + 273.15 : base;
    } else result = Number(c.value) * units[c.kind][c.from] / units[c.kind][c.to];
    return `${Number(result.toFixed(6)).toLocaleString('en-US', { maximumFractionDigits: 6 })} ${c.to}`;
  }
  function toolStatus(id) {
    return ({
      alarm: state.alarm.enabled ? `${time()} · ${state.alarm.days.length ? 'Repeating' : 'Once'}` : 'Off',
      timers: state.timers.length ? `${state.timers.length} timer${state.timers.length > 1 ? 's' : ''} running` : 'Ready when you are',
      countdowns: nothing() ? 'Add your first date' : `${state.countdowns.length} saved`,
      camera: nothing() ? 'Add a camera' : `${state.cameras.length} saved`,
      notes: state.note ? 'A note is waiting' : 'Leave a message',
      intercom: state.intercom === 'idle' ? 'Broadcast or listen' : state.intercom,
      stories: '5 bundled stories', converter: 'Works offline', lamp: 'Brightness and warmth',
      iss: 'Passes over your location', aurora: 'Planetary K-index', speed: 'Run only when needed',
    })[id];
  }
  function library() {
    const all = visibleTools().filter(t => (state.group === 'All tools' || t.group === state.group) &&
      `${t.name} ${t.description}`.toLowerCase().includes(state.query.toLowerCase()));
    return `<div class="tools-library">
      <header class="library-header"><div><h1 tabindex="-1">Tools</h1><p class="muted">Small things that make the day easier.</p></div>
        <label class="library-search">${icon('search')}<input aria-label="Find a tool" name="tool-search" placeholder="Find a tool" value="${esc(state.query)}"></label></header>
      <nav class="category-tabs" aria-label="Tool categories">${['All tools', 'Everyday', 'Home & downtime', 'Discover', ...(state.advanced ? ['Advanced'] : [])]
        .map(g => btn(g, 'group', `data-group="${esc(g)}" aria-pressed="${state.group === g}"`, state.group === g ? 'active' : '')).join('')}</nav>
      ${!all.length ? empty('search', 'No tools found', 'Try a different name or choose All tools.', btn('Show all tools', 'clear-search')) : ''}
      ${['Everyday', 'Home & downtime', 'Discover', 'Advanced'].map(group => {
        const entries = all.filter(t => t.group === group);
        return entries.length ? `<section class="tool-section"><h2>${group}</h2><div class="tool-grid-new">${entries.map(t =>
          `<button type="button" class="tool-entry" data-tool-action="open" data-tool="${t.id}"><span class="tool-icon" style="color:${t.color}">${icon(t.glyph)}</span>
          <span class="tool-copy"><strong>${t.name}</strong><span>${t.description}</span><small>${toolStatus(t.id)}</small></span><span class="entry-arrow" aria-hidden="true">›</span></button>`).join('')}</div></section>` : '';
      }).join('')}</div>`;
  }
  function alarm() {
    const days = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];
    return `<div class="work-columns">${panel('Your next alarm', `<div class="time-display">${time()}</div>
      <p class="muted">${state.alarm.enabled ? 'Next occurrence of your selected time' : 'Your alarm is turned off'}</p>
      <div class="action-row">${btn('Change time', 'alarm-time', '', 'primary-button')}${btn(state.alarm.enabled ? 'Turn off' : 'Turn on', 'alarm-toggle')}</div>
      ${status(state.alarm.enabled ? 'Alarm enabled' : 'Alarm off', state.alarm.enabled)}`)}
      ${panel('Repeat', `<p class="muted">Choose days, or leave all off for a one-time alarm.</p><div class="preset-grid alarm-days">${days.map((day, i) => btn(day, 'alarm-day', `data-day="${i}" aria-label="${['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'][i]}" aria-pressed="${state.alarm.days.includes(i)}"`, state.alarm.days.includes(i) ? 'primary-button' : 'secondary-button')).join('')}</div>
      ${btn('Preview ringing screen', 'alarm-preview')}`)}</div>`;
  }
  function timers() {
    const items = nothing() ? [] : state.timers;
    return `<div class="work-columns">${panel('Start a timer', `<p class="muted">Tap a duration to start.</p><div class="preset-grid">${[1, 3, 5, 10, 15, 30].map(m => btn(`${m} min`, 'timer-start', `data-minutes="${m}"`)).join('')}</div><div class="action-row">${btn('Custom timer', 'timer-custom', '', 'primary-button')}</div><p class="muted">Timers keep their place when you switch tools.</p>`)}
      ${panel('Running timers', items.length ? items.map(t => row(t.name, '', `<span class="metric" data-timer-id="${t.id}">${timerRemaining(t)}</span>${btn('Cancel', 'timer-cancel', `data-id="${t.id}"`, 'danger-button')}`)).join('') : empty('timer', 'A moment to yourself', 'Your active timers will appear here.'))}</div>`;
  }
  function countdowns() {
    const items = nothing() ? [] : state.countdowns;
    const phrase = item => {
      const today = new Date(); today.setHours(0, 0, 0, 0);
      const [year, month, day] = item.date.split('-').map(Number);
      const target = new Date(item.yearly ? today.getFullYear() : year, month - 1, day);
      if (item.yearly && target < today) target.setFullYear(target.getFullYear() + 1);
      const days = Math.round((target - today) / 86400000);
      return days === 0 ? 'Today' : days === 1 ? 'Tomorrow' : days > 0 ? `${days} days to go` : `${-days} days ago`;
    };
    return `<div class="work-columns">${panel('Dates to look forward to', items.length ? items.map(item =>
      row(`${item.icon} ${item.name}`, `${phrase(item)} · ${item.date}${item.yearly ? ' · Every year' : ' · Once'}`, btn('Remove', 'countdown-remove', `data-id="${item.id}"`, 'icon-button'))).join('') :
      empty('cal', 'Make a date', 'Birthdays, holidays, and the things that matter.'))}
      ${panel('Add a countdown', `<p class="muted">Choose a name, date, and icon. It will also be available to the Home countdown widget.</p>${btn('Add countdown', 'countdown-add', '', 'primary-button')}`)}</div>`;
  }
  const cameraImage = () => `<svg class="room-illustration" viewBox="0 0 1000 560" role="img" aria-label="Illustrated sample living room, not a live camera">
    <rect width="1000" height="560" fill="#9da79f"/><path d="M0 400H1000V560H0Z" fill="#736c60"/><rect x="90" y="58" width="225" height="260" rx="3" fill="#d5e0df"/>
    <path d="M202 58V318M90 188H315" stroke="#718283" stroke-width="8"/><path d="M500 140H750V300H500Z" fill="#c4b995"/>
    <path d="M520 280 580 198 630 240 710 182 735 280" fill="#657f70"/><rect x="333" y="348" width="540" height="114" rx="24" fill="#455f59"/>
    <rect x="360" y="292" width="225" height="125" rx="22" fill="#557269"/><rect x="594" y="292" width="250" height="125" rx="22" fill="#557269"/>
    <rect x="437" y="331" width="83" height="60" rx="9" fill="#c0aa87"/><path d="M125 410v-124M125 315Q40 255 125 236Q210 255 125 315M125 350Q60 303 70 276M125 350Q190 303 180 276" fill="#4c6d55" stroke="#355a46" stroke-width="8"/>
    <path d="M92 391H157L149 466H101Z" fill="#b9a283"/><ellipse cx="605" cy="506" rx="233" ry="32" fill="#b2a793"/></svg>`;
  function cameras() {
    const items = nothing() ? [] : state.cameras;
    return `<div class="work-columns">${panel('Your cameras', items.length ? items.map(item =>
      `<div class="camera-preview">${cameraImage()}${stamp}</div>${row(item.name, 'Saved network camera', `${btn('View camera', 'camera-view', `data-id="${item.id}"`, 'primary-button')}${btn('Remove', 'camera-remove', `data-id="${item.id}"`, 'icon-button')}`)}`).join('') :
      empty('film', 'A view of home', 'Add the stream address of a camera you already own.'))}
      ${panel('Camera setup', `<p class="muted">Saved cameras open here first. View camera expands the stream; Back returns to this list.</p>${btn('Add camera', 'camera-add', '', 'primary-button')}${offline() ? status('Camera unreachable. Check the camera and local network.') : status('External camera streams', true)}`)}</div>`;
  }
  function lamp() {
    const { brightness, warmth, red } = state.lamp;
    return `<div class="work-columns">${panel('A softer light', `<div class="lamp-swatch" style="background:${red ? '#cf2929' : `rgb(255,${255 - warmth * .5},${255 - warmth * 1.7})`};opacity:${.4 + brightness / 166}">${icon('bulb')}</div>
      <div class="action-row">${btn('Turn on lamp', 'lamp-preview', '', 'primary-button')}</div><p class="muted">The lamp fills the display. Tap Back to return to these controls.</p>`)}
      ${panel('Light', `<div class="tool-field">Brightness ${btn(`<output>${brightness}%</output>`, 'lamp-number', 'data-field="brightness" aria-label="Set brightness"', 'icon-button')}<input type="range" name="lamp-brightness" aria-label="Lamp brightness" min="5" max="100" value="${brightness}"></div>
      ${!red ? `<div class="tool-field">Warmth ${btn(`<output>${warmth}%</output>`, 'lamp-number', 'data-field="warmth" aria-label="Set warmth"', 'icon-button')}<input type="range" name="lamp-warmth" aria-label="Lamp warmth" min="0" max="100" value="${warmth}"></div>` : ''}
      ${row('Red night light', 'A dimmer color for after dark.', btn(red ? 'On' : 'Off', 'lamp-red', `aria-pressed="${red}"`, red ? 'primary-button' : 'secondary-button'))}`)}</div>`;
  }
  function notes() {
    return `<div class="work-columns">${panel('Your message', `<form data-tool-form="note"><label class="tool-field">Note<textarea name="note" rows="5" placeholder="Leave a little message…">${esc(nothing() ? '' : state.noteDraft ?? state.note)}</textarea></label>
      <div class="action-row"><button type="submit" class="primary-button">Save note</button>${btn('Clear note', 'note-clear', '', 'danger-button')}</div></form>`)}
      ${panel('Voice memo', denied() ? empty('mic', 'Microphone access needed', 'Allow microphone access before recording a voice memo.', btn('Allow microphone', 'permission', '', 'primary-button')) :
      `<div class="note-paper"><p>${state.memo === 'recording' ? 'Recording a sample memo…' : state.memo === 'saved' || state.memo === 'playing' ? 'A voice memo is waiting.' : 'Some things are easier to say.'}</p></div><div class="action-row">
      ${btn(state.memo === 'recording' ? 'Stop recording' : 'Record a memo', 'memo-record', '', 'primary-button')}
      ${state.memo === 'saved' || state.memo === 'playing' ? btn(state.memo === 'playing' ? 'Stop playback' : 'Play memo', 'memo-play') + btn('Delete memo', 'memo-remove', '', 'danger-button') : ''}</div><p class="muted">Recording and playback are simulated in this mock.</p>`)}</div>`;
  }
  function intercom() {
    return `<div class="work-columns">${panel('Broadcast this room', `<div class="empty-state">${icon('megaphone')}<h2>${state.intercom === 'broadcasting' ? 'Broadcasting' : 'Let the house hear you'}</h2><p class="muted">Send this Portal’s microphone to another Portal on your local network.</p></div>
      ${denied() ? btn('Allow microphone', 'permission', '', 'primary-button') : btn(state.intercom === 'broadcasting' ? 'Stop broadcasting' : 'Start broadcasting', 'broadcast', '', 'primary-button')}
      ${status(state.intercom === 'broadcasting' ? 'Sample broadcast active' : 'Microphone idle', state.intercom === 'broadcasting')}`)}
      ${panel('Listen to another Portal', `<form data-tool-form="intercom">${field('Other Portal’s address', 'address', state.address, 'text', 'required')}
      <div class="action-row"><button class="primary-button" type="submit">${state.intercom === 'listening' ? 'Stop listening' : 'Listen'}</button></div></form>
      <p class="muted">This Portal’s sample address: 192.168.0.70</p>${offline() ? status('Couldn’t connect. Check the other Portal and local network.') : status(state.intercom === 'listening' ? 'Listening · sample audio session' : 'Same-network connection', state.intercom === 'listening')}`)}</div>`;
  }
  function stories() {
    if (state.story !== null) return `<div class="story-reader"><div class="read-controls">${btn('‹ All stories', 'story-back')}${btn(state.reading ? 'Stop reading' : 'Read aloud', 'story-read', '', 'primary-button')}</div>
      <p class="muted">Aesop & traditional tales · bundled on your Portal</p><h2>${storyTitles[state.story]}</h2>
      ${storyText[state.story].map(text => `<p>${esc(text)}</p>`).join('')}<p class="muted">Read-aloud controls are simulated. No browser speech is started.</p></div>`;
    return panel('Pick a story', `<p class="muted">Read together, or let the Portal read aloud. These stories work without internet.</p>
      ${storyTitles.map((title, i) => row(title, 'Bundled short story', btn('Read', 'story-open', `data-story="${i}"`, 'primary-button'))).join('')}`);
  }
  function converter() {
    const c = state.converter, options = Object.keys(units[c.kind]);
    return `<div class="work-columns">${panel('Convert', `<form data-tool-form="converter">${select('Category', 'kind', Object.keys(units), c.kind)}
      ${field('Value', 'value', c.value, 'text', 'inputmode="decimal"')}<div class="field-grid">${select('From', 'from', options, c.from)}${select('To', 'to', options, c.to)}</div>
      <div class="action-row">${btn('Swap units', 'convert-swap')}</div></form>`)}
      ${panel('Result', `<div class="big-value" id="conversion-result">${esc(converted())}</div><p class="muted">${c.kind === 'Currency' ? 'Illustrative exchange rates · not current market data. The device can reuse its last saved rates offline.' : 'Calculated on the device. No internet needed.'}</p>`)}</div>`;
  }
  function iss() {
    if (offline() && !state.networkFresh) return panel('Passes above you', empty('globe', 'Passes aren’t available yet', 'Connect to the internet to get the orbit and location used to predict passes.', btn('Try again', 'refresh', '', 'primary-button')));
    if (nothing()) return panel('Passes above you', empty('globe', 'No passes found', 'Check your connection and the location used by Weather.', btn('Refresh passes', 'refresh', '', 'primary-button')));
    return `<div class="work-columns">${panel('Next visible pass', `<p class="muted">Illustrative pass · your Weather location</p><div class="big-value">7:42 PM</div><p class="muted">Look northwest. Peak elevation 48°.</p>
      <svg class="pass-track" viewBox="0 0 600 240" aria-label="Illustrative pass from northwest to southeast"><path d="M35 210Q300-145 565 210" fill="none" stroke="var(--tools-track)" stroke-width="4" stroke-dasharray="9 9"/><circle cx="300" cy="36" r="12" fill="var(--tools-accent)"/><path d="M30 213H570" stroke="var(--tools-divider)" stroke-width="2"/><text x="30" y="237" fill="var(--tools-secondary)">NW</text><text x="537" y="237" fill="var(--tools-secondary)">SE</text></svg>
      ${btn('Refresh passes', 'refresh')}`)}${panel('Upcoming passes', row('Tonight · 7:42 PM', 'NW → SE · Peak 48° · Visible') + row('Tomorrow · 6:55 PM', 'W → E · Peak 32° · Visible') + row('Tomorrow · 8:31 PM', 'NW → S · Peak 18°') + `<p class="muted">Times are local. Visibility depends on sunlight and your horizon.</p>${stamp}`)}</div>`;
  }
  function aurora() {
    if (offline() && !state.networkFresh) return panel('Northern lights', empty('weather', 'Forecast unavailable', 'Connect to the internet to check the latest planetary K-index.', btn('Try again', 'refresh', '', 'primary-button')));
    if (nothing()) return panel('Northern lights', empty('weather', 'No readings available', 'Try refreshing the forecast in a moment.', btn('Refresh outlook', 'refresh', '', 'primary-button')));
    return `<div class="work-columns">${panel('Planetary activity', `<p class="muted">Sample reading</p><div class="big-value">Kp 3.7</div><h3>Quiet to unsettled</h3><div class="forecast-bar"><div class="forecast-fill" style="width:41%"></div></div><p class="muted">A global activity reading, rather than a guarantee of visibility from your home.</p>${btn('Refresh outlook', 'refresh')}`)}
      ${panel('At a glance', row('Kp now', '3.7 · Sample') + row('Next 24-hour peak', '4.3 · Sample') + row('Viewing direction', 'North · Sample') + `<p class="muted">Source: NOAA. Local visibility also depends on latitude, cloud cover, and darkness.</p>`)}</div>`;
  }
  function speed() {
    const busy = state.speed === 'running', done = state.speed === 'done';
    return `<div class="work-columns">${panel('Your connection', `<p class="muted">${busy ? ['Measuring latency…', 'Testing download…', 'Testing upload…'][state.speedStep] : done ? 'Sample test complete' : 'Ready to test'}</p>
      <div class="big-value">${done ? '124.8' : '—'}</div><p class="muted">Download · Mbps</p><div class="action-row">${btn(busy ? 'Cancel test' : done ? 'Run again' : 'Start test', busy ? 'speed-cancel' : 'speed-start', '', 'primary-button')}</div>
      ${offline() ? status('The server couldn’t be reached. Check your internet connection.') : '<p class="muted">Runs only when requested. This mock simulates the result.</p>'}`)}
      ${panel('Details', row('Server', 'Cloudflare · sample') + row('Ping', done ? '18.4 ms' : '—') + row('Jitter', done ? '2.1 ms' : '—') + row('Upload', done ? '22.6 Mbps' : '—'))}</div>`;
  }
  const bodies = { alarm, timers, countdowns, camera: cameras, lamp, notes, intercom, stories, converter, iss, aurora, speed };
  function rememberScroll() {
    if (!root) return;
    if (state.tool) workScroll.set(state.tool, root.querySelector('.workspace-body')?.scrollTop || 0);
    else libraryScroll = root.querySelector('.tools-library')?.scrollTop || 0;
  }
  function render(focus = false) {
    if (!root) return;
    if (root.dataset.tool === (state.tool || '')) rememberScroll();
    root.dataset.tool = state.tool || '';
    root.dataset.view = state.tool ? 'workspace' : 'library';
    if (!state.tool) root.innerHTML = library();
    else {
      const tool = byId.get(state.tool);
      root.innerHTML = `<div class="tool-layout"><aside class="tool-rail">
        <nav aria-label="Tools">${visibleTools().map(t => `<button class="rail-tool ${t.id === state.tool ? 'active' : ''}" type="button" data-tool-action="open" data-tool="${t.id}" aria-current="${t.id === state.tool ? 'page' : 'false'}"><span style="color:${t.color}">${icon(t.glyph)}</span>${t.name}</button>`).join('')}</nav></aside>
        <section class="tool-workspace" aria-labelledby="tool-title"><header class="workspace-heading"><div><h1 id="tool-title" tabindex="-1">${state.story !== null && state.tool === 'stories' ? 'Bedtime story' : tool.name}</h1><p class="muted">${tool.description}</p></div><div class="workspace-actions">${stamp}</div></header>
        <div class="workspace-body">${bodies[state.tool]()}</div></section></div>`;
    }
    const scroller = root.querySelector(state.tool ? '.workspace-body' : '.tools-library');
    scroller.scrollTop = state.tool ? workScroll.get(state.tool) || 0 : libraryScroll;
    syncURL();
    if (focus) (root.querySelector('#tool-title') || root.querySelector('.library-header h1')).focus({ preventScroll: true });
    if (state.full) showFull();
    if (state.sheet) showSheet();
    enhancePickers(root);
    startTick();
  }
  function syncURL() {
    const url = new URL(location.href);
    if (state.tool) url.searchParams.set('tool', state.tool); else url.searchParams.delete('tool');
    url.searchParams.set('scenario', state.scenario);
    if (state.advanced) url.searchParams.set('advanced', '1'); else url.searchParams.delete('advanced');
    history.replaceState(null, '', url);
  }
  function openTool(id) {
    if (!byId.has(id) || (id === 'speed' && !state.advanced)) return;
    rememberScroll(); stopSessions();
    state.sheet = state.full = null;
    state.tool = id; state.story = null;
    render(true);
  }
  function stopSessions() {
    state.intercom = 'idle'; state.reading = false;
    if (state.memo === 'recording' || state.memo === 'playing') state.memo = 'saved';
    if (state.speed === 'running') state.speed = 'idle';
    clearTimeout(speedTimeout);
  }
  function toast(message) {
    root.querySelector('.toast-tool')?.remove();
    const t = document.createElement('div');
    t.className = 'toast-tool'; t.role = 'status'; t.textContent = message;
    root.append(t);
    clearTimeout(toastTimeout);
    toastTimeout = setTimeout(() => t.remove(), 3000);
  }
  function openSheet(kind, id = null) {
    returnFocus = document.activeElement;
    state.sheet = { kind, id }; showSheet();
  }
  function closeLayer() {
    if (!root) return;
    if (state.picker) { closePicker(); return; }
    if (state.sheet && state.full) {
      document.querySelector('.fullscreen-tool .tool-overlay')?.remove();
      state.sheet = null;
      document.querySelectorAll('.fullscreen-tool > [inert]').forEach(e => { e.inert = false; });
      if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true });
      return;
    }
    const lampPreview = state.full === 'lamp';
    state.sheet = state.full = null;
    root.querySelector('.tool-overlay')?.remove();
    document.querySelector('#navigation-device > .fullscreen-tool')?.remove();
    document.querySelector('.live-launcher').inert = false;
    const layout = root.querySelector('.tool-layout');
    if (layout) layout.inert = false;
    if (lampPreview) render();
    if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true });
    else root.querySelector('#tool-title')?.focus({ preventScroll: true });
  }
  function showSheet() {
    root.querySelector('.tool-overlay')?.remove();
    const { kind, id } = state.sheet;
    let title = '', body = '', submit = 'Save', form = kind;
    if (kind === 'lamp-brightness' || kind === 'lamp-warmth') {
      const fieldName = kind.slice(5);
      title = fieldName === 'brightness' ? 'Brightness' : 'Warmth';
      body = field('Percent', 'percent', state.lamp[fieldName], 'number', `min="${fieldName === 'brightness' ? 5 : 0}" max="100" required`);
    } else if (kind === 'alarm-time') {
      title = 'Change alarm time';
      body = `<div class="field-grid">${field('Hour', 'hour', state.alarm.hour, 'number', 'min="0" max="23" required')}${select('Minute', 'minute', Array.from({ length: 12 }, (_, i) => String(i * 5).padStart(2, '0')), String(state.alarm.minute).padStart(2, '0'))}</div>`;
    } else if (kind === 'timer-custom') {
      title = 'Custom timer'; submit = 'Start timer';
      body = `<div class="field-grid">${field('Hours', 'hours', 0, 'number', 'min="0" max="23" required')}${field('Minutes', 'minutes', 5, 'number', 'min="0" max="59" required')}${field('Seconds', 'seconds', 0, 'number', 'min="0" max="59" required')}</div>`;
    } else if (kind === 'countdown-add') {
      title = 'Add countdown'; submit = 'Add countdown';
      body = `${field('Name', 'name', '', 'text', 'required maxlength="80" placeholder="A date that matters"')}
        <div class="field-grid">${select('Month', 'month', Array.from({ length: 12 }, (_, i) => String(i + 1)), '11')}${select('Day', 'day', Array.from({ length: 31 }, (_, i) => String(i + 1)), '12')}</div>
        <label class="tool-field checkbox-field"><input type="checkbox" name="yearly" checked> Repeat every year</label>
        <div class="countdown-year" hidden>${field('Year', 'year', 2026, 'number', 'min="2000" max="2100"')}</div>
        ${select('Icon', 'icon', ['📅', '🎂', '🎉', '✈️', '❤️', '🎄', '🏖️', '🎓', '💍', '🍼', '⭐', '🏠'], '🎂')}`;
    } else if (kind === 'camera-add') {
      title = 'Add camera'; submit = 'Add camera';
      body = `${field('Camera name', 'name', '', 'text', 'placeholder="Living room"')}${field('Stream address', 'url', '', 'text', 'required placeholder="rtsp://camera-address/live"')}<p class="muted">Use a compatible RTSP stream from your camera. This mock saves the example without connecting.</p>`;
    } else if (kind === 'permission') {
      title = 'Allow microphone access'; submit = 'Allow in preview';
      body = '<p>Microphone access is needed for recording a voice memo or broadcasting this room.</p><p class="muted">On the Portal, Android supplies the permission dialog. This preview represents the flow and does not request access to your computer’s microphone.</p>';
    } else {
      title = kind === 'note-clear' ? 'Clear this note?' : kind === 'memo-remove' ? 'Delete voice memo?' : kind === 'timer-cancel' ? 'Cancel this timer?' : kind === 'camera-remove' ? 'Remove camera?' : 'Remove countdown?';
      submit = kind === 'note-clear' ? 'Clear note' : kind === 'timer-cancel' ? 'Cancel timer' : 'Remove';
      body = '<p>This removes the selected item from the preview.</p>';
    }
    const overlay = document.createElement('div');
    overlay.className = 'tool-overlay';
    overlay.innerHTML = `<section class="tool-sheet" role="dialog" aria-modal="true" aria-labelledby="sheet-title"><form data-tool-form="${form}" data-id="${id ?? ''}">
      <header class="sheet-header"><h2 id="sheet-title">${title}</h2>${btn('✕', 'close-layer', 'aria-label="Close dialog"', 'icon-button')}</header>
      <div class="sheet-body">${body}<p class="form-error" role="alert" hidden></p></div><footer class="sheet-footer">${btn('Cancel', 'close-layer')}<button type="submit" class="${submit === 'Remove' || kind === 'note-clear' || kind === 'timer-cancel' ? 'danger-button' : 'primary-button'}">${submit}</button></footer></form></section>`;
    const full = document.querySelector('#navigation-device > .fullscreen-tool');
    if (full) {
      [...full.children].forEach(e => { e.inert = true; });
      full.append(overlay);
    } else {
      root.append(overlay); root.querySelector('.tool-layout').inert = true;
    }
    enhancePickers(overlay);
    overlay.querySelector('input,select,button').focus({ preventScroll: true });
  }
  function showFull() {
    document.querySelector('#navigation-device > .fullscreen-tool')?.remove();
    const full = document.createElement('section');
    full.className = 'fullscreen-tool'; full.setAttribute('role', 'dialog'); full.setAttribute('aria-modal', 'true');
    const id = state.full;
    full.dataset.display = id;
    full.setAttribute('aria-label', id === 'camera' ? 'Camera display preview' : id === 'lamp' ? 'Lamp display preview' : 'Alarm ringing preview');
    const lampColor = state.lamp.red ? '#b32121' : `rgb(255,${255 - state.lamp.warmth * .5},${255 - state.lamp.warmth * 1.7})`;
    full.innerHTML = `<header class="fullscreen-header">${btn(`‹ ${byId.get(state.tool).name}`, 'close-layer')}<span>Display preview · sample</span>${id === 'lamp' ? btn(state.lampControls ? 'Hide controls' : 'Show controls', 'lamp-controls') : ''}</header>
      <div class="fullscreen-body ${id === 'lamp' ? 'lamp-display' : ''}" ${id === 'lamp' ? `style="background:${lampColor};opacity:${.35 + state.lamp.brightness / 154}"` : ''}>
      ${id === 'camera' ? offline() ? empty('film', 'Camera unavailable', 'Check the saved stream address and your local network.', btn('Try again', 'camera-retry', '', 'primary-button')) : `${cameraImage()}<span class="demo-badge">Illustrated sample camera</span>` :
        id === 'alarm' ? `<div class="time-display">${time()}</div><h2>Your alarm is ringing</h2><p class="muted">Preview only · no sound or real alarm scheduled</p><div class="action-row">${btn('Dismiss', 'close-layer', '', 'primary-button')}${btn('Snooze 9 minutes', 'alarm-snooze')}</div>` : ''}</div>
        ${id === 'lamp' && state.lampControls ? `<div class="fullscreen-controls tool-panel"><div class="tool-field">Brightness ${btn(`<output>${state.lamp.brightness}%</output>`, 'lamp-number', 'data-field="brightness" aria-label="Set brightness"', 'icon-button')}<input name="lamp-brightness" aria-label="Lamp brightness" type="range" min="5" max="100" value="${state.lamp.brightness}"></div>
          ${!state.lamp.red ? `<div class="tool-field">Warmth ${btn(`<output>${state.lamp.warmth}%</output>`, 'lamp-number', 'data-field="warmth" aria-label="Set warmth"', 'icon-button')}<input name="lamp-warmth" aria-label="Lamp warmth" type="range" min="0" max="100" value="${state.lamp.warmth}"></div>` : ''}
          <div class="action-row">${btn(state.lamp.red ? 'Red light: On' : 'Red light: Off', 'lamp-red')}${btn('Sleep', 'lamp-sleep')}</div></div>` : ''}`;
    document.getElementById('navigation-device').append(full);
    document.querySelector('.live-launcher').inert = true;
    full.addEventListener('click', click); full.addEventListener('input', input); full.addEventListener('submit', submit);
    full.addEventListener('change', event => event.stopPropagation());
    full.querySelector('button').focus({ preventScroll: true });
  }
  function addTimer(seconds) {
    state.timers.push({ id: Date.now(), name: `${seconds >= 60 ? Math.floor(seconds / 60) + ' min' : seconds + ' sec'}`, end: Date.now() + seconds * 1000 });
    state.scenario = 'normal'; document.getElementById('tools-scenario').value = 'normal';
    render(); toast('Timer started in preview');
  }
  function click(event) {
    const control = event.target.closest('[data-tool-action]');
    if (!control || !(root.contains(control) || control.closest('.fullscreen-tool'))) return;
    event.stopPropagation();
    const a = control.dataset.toolAction;
    if (a === 'open') return openTool(control.dataset.tool);
    if (a === 'group') { state.group = control.dataset.group; return render(); }
    if (a === 'clear-search') { state.group = 'All tools'; state.query = ''; return render(); }
    if (a === 'close-layer') return closeLayer();
    if (a === 'lamp-number') return openSheet(`lamp-${control.dataset.field}`);
    if (a === 'picker') return showPicker(control);
    if (a === 'picker-option') {
      const selectElement = state.picker.select;
      selectElement.value = control.dataset.option;
      closePicker();
      selectElement.dispatchEvent(new Event('input', { bubbles: true }));
      enhancePickers(root);
      return;
    }
    if (a === 'lamp-controls') { state.lampControls = !state.lampControls; return showFull(); }
    if (a === 'lamp-sleep') { closeLayer(); return toast('Sleep preview · device-admin permission is required on the Portal'); }
    if (a === 'alarm-snooze') { closeLayer(); return toast('Snooze preview · 9 minutes'); }
    if (['alarm-time', 'timer-custom', 'countdown-add', 'camera-add', 'note-clear', 'memo-remove', 'permission', 'timer-cancel', 'countdown-remove', 'camera-remove'].includes(a)) return openSheet(a, Number(control.dataset.id));
    if (a === 'alarm-toggle') { state.alarm.enabled = !state.alarm.enabled; return render(); }
    if (a === 'alarm-day') {
      const d = Number(control.dataset.day);
      state.alarm.days = state.alarm.days.includes(d) ? state.alarm.days.filter(day => day !== d) : [...state.alarm.days, d]; return render();
    }
    if (a === 'alarm-preview' || a === 'lamp-preview' || a === 'camera-view') {
      returnFocus = control; state.full = a === 'alarm-preview' ? 'alarm' : a === 'lamp-preview' ? 'lamp' : 'camera'; return showFull();
    }
    if (a === 'camera-retry') { toast('Still unavailable in the Offline preview'); return; }
    if (a === 'timer-start') return addTimer(Number(control.dataset.minutes) * 60);
    if (a === 'lamp-red') { state.lamp.red = !state.lamp.red; return state.full ? showFull() : render(); }
    if (a === 'memo-record') { state.memo = state.memo === 'recording' ? 'saved' : 'recording'; return render(); }
    if (a === 'memo-play') { state.memo = state.memo === 'playing' ? 'saved' : 'playing'; return render(); }
    if (a === 'broadcast') {
      if (offline()) return toast('Check the local network before broadcasting');
      state.intercom = state.intercom === 'broadcasting' ? 'idle' : 'broadcasting'; return render();
    }
    if (a === 'story-open') { state.story = Number(control.dataset.story); workScroll.set('stories', 0); return render(); }
    if (a === 'story-back') { state.story = null; state.reading = false; return render(); }
    if (a === 'story-read') { state.reading = !state.reading; render(); if (state.reading) toast('Sample read-aloud started'); return; }
    if (a === 'convert-swap') { [state.converter.from, state.converter.to] = [state.converter.to, state.converter.from]; return render(); }
    if (a === 'refresh') { if (offline()) return toast('Still offline. Your tool stays open.'); state.networkFresh = true; render(); toast('Sample data refreshed'); return; }
    if (a === 'speed-cancel') { clearTimeout(speedTimeout); state.speed = 'idle'; render(); toast('Test cancelled'); return; }
    if (a === 'speed-start') {
      if (offline()) return toast('Couldn’t reach the test server');
      state.speed = 'running'; state.speedStep = 0; render();
      const step = () => { if (!active || state.tool !== 'speed' || state.speed !== 'running') return; state.speedStep++;
        if (state.speedStep >= 3) state.speed = 'done'; render();
        if (state.speed === 'running') speedTimeout = setTimeout(step, 650); };
      speedTimeout = setTimeout(step, 650);
    }
  }
  function submit(event) {
    const form = event.target.closest('[data-tool-form]');
    if (!form) return;
    event.preventDefault(); event.stopPropagation();
    const data = Object.fromEntries(new FormData(form)), kind = form.dataset.toolForm;
    const error = message => { const e = form.querySelector('.form-error'); e.hidden = false; e.textContent = message; };
    if (kind === 'note') { state.note = data.note.trim(); state.noteDraft = state.note; return toast('Note saved in preview'); }
    if (kind === 'intercom') {
      state.address = data.address.trim();
      if (offline()) return toast('Couldn’t connect to the other Portal');
      state.intercom = state.intercom === 'listening' ? 'idle' : 'listening'; return render();
    }
    if (kind === 'converter') return;
    if (kind.startsWith('lamp-')) {
      state.lamp[kind.slice(5)] = Number(data.percent);
      closeLayer();
      if (state.full) showFull(); else render();
      return;
    } else if (kind === 'alarm-time') { state.alarm.hour = Number(data.hour); state.alarm.minute = Number(data.minute); }
    else if (kind === 'timer-custom') {
      const sec = Number(data.hours) * 3600 + Number(data.minutes) * 60 + Number(data.seconds);
      if (sec <= 0) return error('Set a duration greater than zero.');
      closeLayer(); return addTimer(sec);
    } else if (kind === 'countdown-add') {
      if (!data.name.trim()) return error('Enter a name.');
      const year = data.yearly === 'on' ? 2026 : Number(data.year);
      const candidate = new Date(year, Number(data.month) - 1, Number(data.day));
      if (candidate.getMonth() !== Number(data.month) - 1) return error('Choose a valid date for this month.');
      const date = `${year}-${data.month.padStart(2, '0')}-${data.day.padStart(2, '0')}`;
      state.countdowns.push({ id: Date.now(), name: data.name.trim(), date, icon: data.icon, yearly: data.yearly === 'on' });
      state.scenario = 'normal';
    } else if (kind === 'camera-add') {
      if (!/^rtsp:\/\/.+/i.test(data.url.trim())) return error('Use a camera stream address beginning with rtsp://.');
      state.cameras.push({ id: Date.now(), name: data.name.trim() || 'Camera', url: data.url.trim() });
      state.scenario = 'normal';
    } else if (kind === 'permission') { state.scenario = 'normal'; }
    else if (kind === 'note-clear') { state.note = ''; state.noteDraft = ''; }
    else if (kind === 'memo-remove') state.memo = 'none';
    else if (kind === 'timer-cancel') state.timers = state.timers.filter(t => t.id !== Number(form.dataset.id));
    else if (kind === 'camera-remove') state.cameras = state.cameras.filter(t => t.id !== Number(form.dataset.id));
    else if (kind === 'countdown-remove') state.countdowns = state.countdowns.filter(t => t.id !== Number(form.dataset.id));
    closeLayer(); document.getElementById('tools-scenario').value = state.scenario; render();
    toast(kind === 'permission' ? 'Permission allowed in preview' : 'Saved in preview');
  }
  function input(event) {
    // The retained launcher projects frozen forms; these controls have their own owner.
    event.stopPropagation();
    const target = event.target;
    if (target.name === 'note') state.noteDraft = target.value;
    if (target.name === 'yearly') root.querySelector('.countdown-year').hidden = target.checked;
    if (target.name === 'tool-search') {
      const start = target.selectionStart; state.query = target.value; render();
      const field = root.querySelector('[name=tool-search]'); field.focus(); field.setSelectionRange(start, start); return;
    }
    if (target.name === 'lamp-brightness' || target.name === 'lamp-warmth') {
      state.lamp[target.name === 'lamp-brightness' ? 'brightness' : 'warmth'] = Number(target.value);
      target.parentElement.querySelector('output').textContent = `${target.value}%`;
      const swatch = root.querySelector('.lamp-swatch');
      swatch.style.background = state.lamp.red ? '#cf2929' : `rgb(255,${255 - state.lamp.warmth * .5},${255 - state.lamp.warmth * 1.7})`;
      swatch.style.opacity = .4 + state.lamp.brightness / 166;
      const light = document.querySelector('.lamp-display');
      if (light) {
        light.style.background = swatch.style.background;
        light.style.opacity = .35 + state.lamp.brightness / 154;
      }
    }
    if (target.closest('[data-tool-form=converter]')) {
      if (target.name === 'kind') {
        state.converter.kind = target.value; const keys = Object.keys(units[target.value]);
        state.converter.from = keys[0]; state.converter.to = keys[1]; render();
      } else {
        state.converter[target.name] = target.value; root.querySelector('#conversion-result').textContent = converted();
      }
    }
  }
  function startTick() {
    clearInterval(timerTick);
    if (!active || state.tool !== 'timers' || !state.timers.some(t => t.end > Date.now())) return;
    timerTick = setInterval(() => {
      root.querySelectorAll('[data-timer-id]').forEach(el => {
        const timer = state.timers.find(t => t.id === Number(el.dataset.timerId));
        if (timer) el.textContent = timerRemaining(timer);
      });
      if (!state.timers.some(t => t.end > Date.now())) clearInterval(timerTick);
    }, 1000);
  }
  function back() {
    if (!root || !active) return false;
    if (state.picker) { closePicker(); return true; }
    if (state.sheet || state.full) { closeLayer(); return true; }
    if (state.story !== null) { state.story = null; state.reading = false; render(); return true; }
    if (!state.tool) return false;
    rememberScroll(); stopSessions(); state.tool = null; render(); return true;
  }
  function returnToLibrary() {
    if (!root || !active) return;
    rememberScroll();
    stopSessions();
    state.tool = null;
    state.story = null;
    state.sheet = null;
    state.picker = null;
    render();
    const heading = root.querySelector('.library-header h1');
    heading.tabIndex = -1;
    heading.focus({ preventScroll: true });
  }
  // Runs before the older demo's capturing keyboard listener.
  document.addEventListener('keydown', event => {
    if (!root || !active) return;
    if (event.key === 'Escape' && back()) { event.preventDefault(); event.stopImmediatePropagation(); return; }
    const dialog = root.querySelector('.tool-picker') || root.querySelector('.tool-overlay') ||
      document.querySelector('.fullscreen-tool .tool-overlay') || document.querySelector('#navigation-device > .fullscreen-tool');
    if (dialog && event.key === 'Tab') {
      const controls = [...dialog.querySelectorAll('button,input,select,textarea')].filter(e => !e.disabled && e.getClientRects().length);
      const index = controls.indexOf(document.activeElement);
      controls[(index + (event.shiftKey ? -1 : 1) + controls.length) % controls.length]?.focus();
      event.preventDefault(); event.stopImmediatePropagation();
    }
  }, true);
  function enhancePickers(scope) {
    scope.querySelectorAll('.tool-field select').forEach(selectElement => {
      let button = selectElement.parentElement.querySelector('.select-tool');
      if (!button) {
        button = document.createElement('button'); button.type = 'button'; button.className = 'select-tool';
        button.dataset.toolAction = 'picker'; button.setAttribute('aria-haspopup', 'dialog');
        selectElement.hidden = true; selectElement.after(button);
      }
      const label = selectElement.parentElement.firstChild.textContent.trim();
      button.setAttribute('aria-label', `${label}: ${selectElement.value}`);
      button.textContent = selectElement.value + '  ▾';
    });
  }
  function showPicker(button) {
    const selectElement = button.parentElement.querySelector('select');
    const title = selectElement.parentElement.firstChild.textContent.trim();
    state.picker = { select: selectElement, focus: button };
    const layer = document.createElement('div'); layer.className = 'tool-overlay tool-picker';
    layer.innerHTML = `<section class="tool-sheet" role="dialog" aria-modal="true" aria-label="${esc(title)}"><header class="sheet-header"><h2>${esc(title)}</h2>${btn('✕', 'picker-close', 'aria-label="Close picker"', 'icon-button')}</header>
      <div class="sheet-body picker-options">${[...selectElement.options].map(o => btn(`${esc(o.value)}${o.selected ? ' ✓' : ''}`, 'picker-option', `data-option="${esc(o.value)}" aria-pressed="${o.selected}"`)).join('')}</div></section>`;
    const underlying = root.querySelector('.tool-overlay') || root.querySelector('.tool-layout');
    underlying.inert = true; root.append(layer);
    layer.addEventListener('click', e => { if (e.target.closest('[data-tool-action=picker-close]')) { e.stopPropagation(); closePicker(); } });
    layer.querySelector('button').focus();
  }
  function closePicker() {
    const focus = state.picker?.focus;
    root.querySelector('.tool-picker')?.remove(); state.picker = null;
    const underlying = root.querySelector('.tool-overlay') || root.querySelector('.tool-layout');
    if (underlying) underlying.inert = false;
    if (focus?.isConnected) focus.focus({ preventScroll: true });
  }
  document.getElementById('tools-scenario').value = state.scenario;
  document.getElementById('tools-advanced').checked = state.advanced;
  document.getElementById('tools-scenario').addEventListener('change', e => {
    closeLayer(); stopSessions(); state.scenario = e.target.value; state.networkFresh = false; render();
  });
  document.getElementById('tools-advanced').addEventListener('change', e => {
    state.advanced = e.target.checked;
    if (!state.advanced && state.tool === 'speed') { stopSessions(); state.tool = null; }
    if (!state.advanced && state.group === 'Advanced') state.group = 'All tools';
    render();
  });
  window.__portalToolsRedesign = {
    mount(node, route) {
      if (!root) {
        root = document.createElement('div'); root.id = 'tools-overhaul'; root.className = 'tools-overhaul';
        node.replaceChildren(root);
        root.addEventListener('click', click); root.addEventListener('submit', submit); root.addEventListener('input', input);
        root.addEventListener('change', event => event.stopPropagation());
      }
      if (byId.has(route.screen)) state.tool = route.screen;
      if (!listInitialized) { listInitialized = true; render(); }
    },
    activate(value) {
      if (active === value) return;
      active = value;
      if (!active) { closeLayer(); stopSessions(); clearInterval(timerTick); }
      else if (root) render();
    },
    back, open: openTool, returnToLibrary,
    getState: () => JSON.parse(JSON.stringify({ ...state, active, libraryScroll })),
  };
})();
