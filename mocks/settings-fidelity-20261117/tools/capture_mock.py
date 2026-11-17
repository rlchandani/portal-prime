"""Capture each mock page's device frame (detail column fully expanded) as a PNG.

Usage: python3 -I capture_mock.py <mock index.html> <out dir> <page,page,...>  (needs Playwright + Chrome)
"""
import sys
from playwright.sync_api import sync_playwright

MOCK, OUT = sys.argv[1], sys.argv[2]
PAGES = sys.argv[3].split(",")
WIDTH = 1920

JS = """(pid) => {
  if (pid === 'search') { go('display'); S.q = 'clock'; document.getElementById('q').value = 'clock'; renderSidebar(); }
  else go(pid);
  const dev = document.getElementById('dev'), inner = document.getElementById('inner');
  const ds = document.getElementById('ds');
  document.body.appendChild(dev);
  dev.style.cssText = 'position:absolute;left:0;top:0;transform:none;border-radius:0';
  const h = Math.max(1280, 104 + ds.scrollHeight + 20);
  for (const el of [dev, inner]) { el.style.width = '1920px'; el.style.height = h + 'px'; el.style.transform = 'none'; }
  inner.style.setProperty('--sbw', '600px');
  document.querySelectorAll('.page.in').forEach(p => p.classList.remove('in'));
  for (const s of ['.top', '.panes', '.bridge', '.dwrap']) document.querySelector(s).style.display = 'none';
  return h;
}"""

def open_page(b, h):
    pg = b.new_page(viewport={"width": WIDTH, "height": h})
    pg.goto(f"file://{MOCK}#theme=dark")
    pg.wait_for_timeout(250)
    return pg

with sync_playwright() as p:
    b = p.chromium.launch(channel="chrome")
    for pid in PAGES:
        probe = open_page(b, 1280)
        h = probe.evaluate(JS, pid)
        probe.close()
        pg = open_page(b, h)
        pg.evaluate(JS, pid)
        pg.wait_for_timeout(300)
        pg.screenshot(path=f"{OUT}/{pid}.png")
        pg.close()
        print(pid, h)
    b.close()
