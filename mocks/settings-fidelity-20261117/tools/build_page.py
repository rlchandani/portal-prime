"""Builds pages.json and index.html of the fidelity comparison from the analysis doc.

Usage: python3 -I build_page.py <repo root> <template.html>
"""
import json, re, sys, html
ROOT = sys.argv[1]
doc = open(f"{ROOT}/docs/plans/settings-mock-vs-build-2026-11-17.md").read()

DENSITY = 1.125            # 2160×1440 px at 180 dpi → 1920×1280 dp
SIDEBAR_X_DP = 297         # centre of sidebar rows (28..567 dp)
DETAIL_X_DP = 1258         # centre of the detail cards (754..1762 dp)
SCROLL_END_DP = 844        # sidebar list scroll range (content 2124 dp − viewport bottom 1280 dp)
VIEWPORT_BOTTOM_DP = 1280
ROW_TOPS = {  # sidebar row tops (dp) before scrolling, 80 dp rows
  "display": 442, "sounds": 522, "frame": 602, "alarms": 682, "night": 762,
  "wallpaper": 902, "home": 982, "widgets": 1062, "clock": 1142, "sources": 1222, "greeting": 1302,
  "ha": 1442, "phone": 1522, "intercom": 1602, "music": 1682,
  "about": 1822, "system": 1902, "advanced": 1982,
}
PAGES = [  # id, sidebar label, doc heading, sub-page link (label, row centre y dp on the parent page)
  ("sidebar", "Display & Brightness", "Sidebar", None), ("search", "Search field", "Search", None),
  ("display", "Display & Brightness", "1. Display & Brightness", None),
  ("sounds", "Sounds", "2. Sounds", None), ("frame", "Photo Frame", "3. Photo Frame", None),
  ("alarms", "Alarms & Wake", "4. Alarms & Wake", None), ("night", "Night & Sleep", "5. Night & Sleep", None),
  ("wallpaper", "Wallpaper", "6. Wallpaper", None), ("home", "Home Screen", "7. Home Screen", None),
  ("widgets", "Widgets & Calendar", "8. Widgets & Calendar (with Weather and Calendar sub-pages)", None),
  ("w-weather", "Widgets & Calendar", None, ("Weather", 500)),
  ("w-calendar", "Widgets & Calendar", None, ("Calendar", 328)),
  ("clock", "Clock Faces", "9. Clock Faces", None), ("sources", "Photo Sources", "10. Photo Sources", None),
  ("greeting", "Welcome Greeting", "11. Welcome Greeting", None), ("ha", "Home Assistant", "12. Home Assistant", None),
  ("phone", "Phone Remote", "13. Phone Remote", None), ("intercom", "Intercom", "14. Intercom", None),
  ("music", "Music", "15. Music", None), ("about", "About & Health", "16. About & Health", None),
  ("system", "Portal & Android", "17. Portal & Android", None), ("advanced", "Advanced", "18. Advanced", None),
]

def tables():
  out, cur = {}, None
  for line in doc.split("\n"):
    m = re.match(r"^## (.+)$", line)
    if m: cur = m.group(1).strip(); out.setdefault(cur, []); continue
    if cur and line.startswith("|") and not line.startswith("|---") and "| Verdict |" not in line:
      cells = [c.strip() for c in line.strip().strip("|").split("|")]
      if len(cells) == 5: out[cur].append(dict(zip(["element", "mock", "built", "verdict", "reason"], cells)))
  return out
T = tables()
sub_rows = {"w-weather": "Weather ›", "w-calendar": "Calendar ›"}

def px(dp): return round(dp * DENSITY)
def tap(pid, label, sub):
  if pid == "search":
    return [{"searchField": "Search", "x": px(SIDEBAR_X_DP), "y": px(222), "type": "clock", "approximate": True}]
  root = {"w-weather": "widgets", "w-calendar": "widgets", "sidebar": "display", "search": "display"}.get(pid, pid)
  top = ROW_TOPS[root]
  centre = top + 40
  scroll = centre > VIEWPORT_BOTTOM_DP - 40
  y = centre - SCROLL_END_DP if scroll else centre
  t = {"sidebarRow": label,
       "scrollSidebar": "toEnd" if scroll else "none",
       "x": px(SIDEBAR_X_DP), "y": px(y), "approximate": True}
  steps = [t]
  if sub: steps.append({"detailRow": sub[0], "x": px(DETAIL_X_DP), "y": px(sub[1]), "approximate": True})
  return steps

pages = []
for pid, label, heading, sub in PAGES:
  if heading == "Sidebar":
    rows = T["Sidebar"] + T["Profile card"]
  elif heading:
    rows = T[heading]
    if pid == "widgets": rows = [r for r in rows if not r["element"].startswith(("Weather ›", "Calendar ›"))]
  else:
    rows = [r for r in T["8. Widgets & Calendar (with Weather and Calendar sub-pages)"] if r["element"].startswith(sub_rows[pid])]
  title = {"sidebar": "Sidebar & profile card", "search": "Search"}.get(pid, label if not sub else f"{label} › {sub[0]}")
  pages.append({"id": pid, "sidebarLabel": label, "title": title,
                "tap": tap(pid, label, sub), "rows": rows})

counts = {v: sum(1 for p in pages for r in p["rows"] if r["verdict"].startswith(v)) for v in ("MOCK", "BUILD", "NEW")}
manifest = {
  "screen": {"px": [2160, 1440], "density": DENSITY, "dp": [1920, 1280]},
  "notes": ["Settings reopens on the last page used; open each page by tapping its sidebar row.",
            "Coordinates are px on a 2160×1440 Portal, derived from layout constants (rows 80 dp, "
            "sidebar 31 % wide); treat them as approximate and prefer the row label.",
            "scrollSidebar=toEnd: fling the sidebar list to its end first (rows below Photo Sources are off screen).",
            "Capture the whole screen to ./built/<id>.png (before, from main) and ./final/<id>.png "
            "(settings-fidelity build). Use the dark theme and Advanced settings ON to match the mock captures."],
  "pages": [{k: p[k] for k in ("id", "sidebarLabel", "title", "tap")} for p in pages],
}
open(f"{ROOT}/mocks/settings-fidelity-20261117/pages.json", "w").write(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
data = json.dumps({"pages": [{k: p[k] for k in ("id", "title", "rows")} for p in pages], "counts": counts}, ensure_ascii=False)
tpl = open(sys.argv[2]).read()
open(f"{ROOT}/mocks/settings-fidelity-20261117/index.html", "w").write(tpl.replace("__DATA__", data.replace("</", "<\\/")))
print(counts, len(pages))
