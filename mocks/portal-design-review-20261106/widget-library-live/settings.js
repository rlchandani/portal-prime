// Settings shell content. Categories and page titles mirror ApprovedSettingsNavigation.kt; row values are sample.
'use strict';

const CATEGORIES = [
  ['appearance', 'Appearance', ['display', 'appearance', 'wallpaper']],
  ['photos', 'Photos & screensaver', ['screensaver', 'faces', 'clock', 'sources']],
  ['home', 'Home & widgets', ['widgets', 'weather', 'calendar', 'alarm', 'quickbar']],
  ['sound', 'Sound & wake', ['touch', 'chime', 'welcome', 'sunrise', 'alarm', 'lamp']],
  ['connections', 'Connections', ['ha', 'phone', 'intercom']],
  ['access', 'Input & accessibility', ['touch', 'system', 'quickbar']],
  ['device', 'Device', ['device', 'sleep', 'health', 'help', 'system']],
  ['advanced', 'Advanced', ['almanac', 'immich', 'mqtt', 'options', 'debug']],
];

const PAGE_TITLES = {
  display: 'Display', appearance: 'App appearance', wallpaper: 'Wallpaper', screensaver: 'Slideshow', faces: 'Photo clock faces',
  clock: 'Digital clock', sources: 'Photo sources', widgets: 'Home dashboard', weather: 'Weather', calendar: 'Calendar',
  alarm: 'Alarm', quickbar: 'Quick buttons', touch: 'Touch sounds', chime: 'Sounds', welcome: 'Welcome greeting',
  sunrise: 'Sunrise & wake', lamp: 'Lamp', ha: 'Home Assistant', phone: 'Phone', intercom: 'Intercom', system: 'System',
  device: 'Device name', sleep: 'Sleep', health: 'Health', help: 'Help', almanac: 'Almanac', immich: 'Connect Immich',
  mqtt: 'MQTT', options: 'Options scanner', debug: 'Debug', world: 'World clock', crypto: 'Crypto', todo: 'To-do',
  news: 'News', stocks: 'Stocks', countdowns: 'Countdowns',
};
// Page -> owning category (for the sidebar highlight), from ApprovedSettingsPages.
const PAGE_CATEGORY = { world: 'home', crypto: 'home', todo: 'home', news: 'home', stocks: 'home', countdowns: 'home', options: 'advanced', ha: 'connections', sources: 'photos', display: 'appearance' };
const categoryOf = (page) => PAGE_CATEGORY[page] || (CATEGORIES.find(([, , p]) => p.includes(page)) || ['home'])[0];

// Sections: [label, rows]; a row is [title, value?, kind?]. kind: 'nav' | 'toggle-on' | 'toggle-off' | 'seg:a|b|c'
const PAGES = {
  widgets: [['Home dashboard', [['Customize home', 'Touch and hold a widget on Home to move, resize or remove it.', 'nav:customize']]],
    ['Widget settings', [['Weather', '', 'nav:weather'], ['Home Assistant', '', 'nav:ha'], ['Calendar', '', 'nav:calendar'], ['Alarm', '', 'nav:alarm'],
      ['World clock', '', 'nav:world'], ['Crypto', '', 'nav:crypto'], ['Countdowns', '', 'nav:countdowns'], ['To-do', '', 'nav:todo'],
      ['News', '', 'nav:news'], ['Stocks', '', 'nav:stocks'], ['Photo sources', '', 'nav:sources']]]],
  weather: [['Location', [['City', 'Edmonds, WA', 'nav'], ['Units', '', 'seg:°F|°C']]], ['Home widget', [['Forecast', '', 'seg:Hourly|Daily|Off']]]],
  calendar: [['Calendar feed', [['Family calendar', 'Not connected', 'nav'], ['Add calendar link', 'Paste an iCal link from your phone', 'nav']]],
    ['On Home', [['Show on Today', '', 'toggle-on'], ['Days ahead', '7 days', 'nav']]]],
  world: [['Cities', [['London', '8 hours ahead', 'nav'], ['Mumbai', '13½ hours ahead', 'nav'], ['Tokyo', 'Tomorrow', 'nav'], ['Add a city', '', 'nav']]]],
  alarm: [['Alarm', [['6:45 AM', 'Weekdays', 'toggle-on'], ['Sound', 'Morning birds', 'nav'], ['Snooze', '9 minutes', 'nav']]]],
  todo: [['Tasks', [['Oat milk', '', 'toggle-off'], ['Call the plumber', '', 'toggle-off'], ['Bins out', 'Done', 'toggle-on'], ['Add a task', '', 'nav']]]],
  news: [['Sources', [['Local news', '', 'toggle-on'], ['Weather alerts', '', 'toggle-on'], ['Headlines on Home', '5', 'nav']]]],
  stocks: [['Watchlist', [['AAPL', '', 'nav'], ['MSFT', '', 'nav'], ['VOO', '', 'nav'], ['Add a ticker', '', 'nav']]], ['Account', [['Alpaca account', 'Not connected', 'nav']]]],
  crypto: [['Coins', [['Bitcoin', 'BTC', 'nav'], ['Ethereum', 'ETH', 'nav'], ['Solana', 'SOL', 'nav']]], ['Display', [['Currency', 'USD', 'nav']]]],
  options: [['Scanner', [['Watchlist', 'SPY, QQQ, AAPL', 'nav'], ['Delta range', '0.25 – 0.45', 'nav']]]],
  ha: [['Server', [['Home Assistant', 'homeassistant.local · Connected', 'nav'], ['Pinned devices', '4 pinned', 'nav'], ['Open dashboard', '', 'nav']]]],
  sources: [['Photo source', [['Folder', '/Pictures/Family', 'nav'], ['Immich', 'Not connected', 'nav'], ['SMB / NAS', '', 'nav'], ['Shared album link', '', 'nav'], ['Web page', '', 'nav']]]],
  quickbar: [['Quick buttons', [['Hey Jarvis', '', 'toggle-on'], ['Home dashboard', '', 'toggle-on'], ['Lights', '', 'toggle-on'], ['Scenes', '', 'toggle-off']]]],
  display: [['Display', [['Brightness', 'Auto', 'nav'], ['Night mode', 'After sunset', 'nav'], ['Theme', '', 'seg:Dark|Light|Auto']]]],
  countdowns: [['Countdowns', [['Ava’s birthday', 'In 23 days', 'nav'], ['Add a countdown', '', 'nav']]]],
};

function settingsRow([title, value, kind = 'nav']) {
  let right = '';
  if (kind.startsWith('seg:')) right = `<span class="sseg">${kind.slice(4).split('|').map((o, i) => `<span class="${i === 0 ? 'on' : ''}">${o}</span>`).join('')}</span>`;
  else if (kind.startsWith('toggle')) right = `<span class="tog ${kind === 'toggle-on' ? 'on' : ''}"></span>`;
  else right = `<span class="chev">${ICONS.chev}</span>`;
  const target = kind.startsWith('nav:') ? kind.slice(4) : '';
  const tag = target ? `button type="button" data-act="settings-nav" data-target="${target}"` : 'div';
  return `<${tag} class="srow"><span class="st"><b>${title}</b>${value ? `<small>${value}</small>` : ''}</span>${right}</${tag.split(' ')[0]}>`;
}

function settingsContent(page, category) {
  if (!page) {
    const [, title, pages] = CATEGORIES.find(([id]) => id === category);
    return `<h2 class="stitle">${title}</h2><div class="scard">${pages.map((p) => settingsRow([PAGE_TITLES[p] || p, '', `nav:${p}`])).join('')}</div>`;
  }
  const sections = PAGES[page] || [[PAGE_TITLES[page] || page, [['Sample setting', 'Not part of this mock', 'nav']]]];
  return `<h2 class="stitle">${PAGE_TITLES[page] || page}</h2>${sections.map(([label, rows]) =>
    `<div class="slabel">${label}</div><div class="scard">${rows.map(settingsRow).join('')}</div>`).join('')}`;
}
