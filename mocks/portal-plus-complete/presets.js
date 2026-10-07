/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
(() => {
  const Q = new URLSearchParams(location.search);
  window.__applyPortalReference = entry => {
    const a = window.__portalMock;
    a.navigate(entry.screen, entry.category);
    if (a.getState().theme !== entry.theme && entry.theme) a.showAction('theme');
    a.configure({ advanced: entry.advanced !== false, scenario: entry.scenario || 'expanded', values: entry.values || {},source:entry.source,connectionResult:entry.connectionResult });
    if (entry.values?.['digitalclock.face'])
      a.showAction('choose', { id: 'digitalclock.face', value: entry.values['digitalclock.face'] });
    for (const step of entry.steps || []) a.showAction(step.action, step.data || {});
    if (entry.control) { a.revealControl(entry.control); a.showControl(entry.control); }
    else if (entry.action) a.showAction(entry.action, entry.data || {});
  };
  const key = Q.get('reference') || Q.get('flow');
  if (!key) return;
  fetch(Q.has('reference') ? 'reference-plan.json' : 'coverage.json')
    .then(r => r.json())
    .then(data => {
      const entry = (Array.isArray(data) ? data : data.popups).find(p => p.id === key);
      if (entry) window.__applyPortalReference(entry);
    });
})();
