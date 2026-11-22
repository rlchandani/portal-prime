"""The scenario registry: each benchmark as a small declarative entry.

An entry:
  summary        one line for `bench.py scenarios`
  screenshots    False = the screenshot helper refuses for this scenario, whatever the flags
  start          activity each rep starts from (we bring Home up; anything else aborts)
  session        actions once after every install (e.g. Mini emulation)
  setup          actions at the start of every rep (not measured)
  warmup         unmeasured passes of [steps] at the start of every rep
  inner          measured passes of [steps] per rep
  steps          measured steps; each: name, do (actions), and either
                   wait (s) -> one framestats window, or
                   poll (s) -> framestats polled back to back, de-duplicated; with beats=True each
                               photo_beat (0.5 s before .. 3 s after) is one sample, the first skipped
                 expect: the short activity name that must be resumed afterwards (else abort)
  teardown       actions at the end of every rep
  audit          screens for the uiautomator overflow audit (replaces steps)

Actions are (verb, arg) tuples, run by engine.ACTIONS: tap_label, tap_desc, fleet, key, swipe_left,
sleep, home, dream, test_source, orientation, mini, screenshot.
"""

CC_OPEN = {"action": "open_control_center", "screen": "map"}
DOOR_ROW = "door is open"
HOME = "HomeActivity"
CC = "ControlCenterActivity"
SETTINGS = "ImmortalSettingsActivity"
MINI_SIZES = {"portrait": "800x1280", "landscape": "1280x800"}
PIN_INTERVAL_S = 15
HOUSEHOLD_WINDOW_S = 300
AUDIT_SCREENS = ("home", "apps", "tools", "control_center", "settings", "calls", "store")


def _tab(label, name):
    return {"name": name, "do": [("tap_label", label)], "wait": 2.5, "expect": HOME}


BASE = {
    "home-tabs": {
        "summary": "Home tab bar switches Home -> Apps -> Tools -> Home (taps located from uiautomator dump)",
        "screenshots": False,
        "start": HOME,
        "setup": [("tap_label", "Home"), ("sleep", 2)],
        "warmup": 1,
        "inner": 4,
        "steps": [_tab("Apps", "to-apps"), _tab("Tools", "to-tools"), _tab("Home", "to-home")],
        "teardown": [],
    },
    "cc-open": {
        "summary": "Control Center open (fleet open_control_center, Map) and BACK to Home",
        "screenshots": False,
        "start": HOME,
        "setup": [("sleep", 1)],
        "warmup": 0,
        "inner": 1,
        "steps": [
            {"name": "open", "do": [("fleet", CC_OPEN)], "wait": 3, "expect": CC},
            {"name": "back", "do": [("key", "BACK")], "wait": 3, "expect": HOME},
        ],
        "teardown": [("sleep", 2)],
    },
    "cc-switch": {
        "summary": "Control Center Map -> Alerts via the read-only 'door is open' row, and BACK to Map",
        "screenshots": False,
        "start": HOME,
        "setup": [("fleet", CC_OPEN), ("sleep", 5), ("expect", CC), ("locate", DOOR_ROW)],
        "warmup": 1,
        "inner": 4,
        "steps": [
            {"name": "to-alerts", "do": [("tap_desc", DOOR_ROW)], "wait": 3, "expect": CC},
            {"name": "back", "do": [("key", "BACK")], "wait": 3, "expect": CC},
        ],
        "teardown": [("key", "BACK"), ("sleep", 4), ("expect", HOME)],
    },
    "settings-open": {
        "summary": "Settings open from Home's header button (located from uiautomator dump), BACK to Home",
        "screenshots": True,
        "start": HOME,
        "setup": [("sleep", 1)],
        "warmup": 0,
        "inner": 1,
        "steps": [
            {"name": "open", "do": [("tap_label", "Settings")], "wait": 2.5, "expect": SETTINGS,
             "after": [("screenshot", "settings")]},
            {"name": "back", "do": [("key", "BACK")], "wait": 4, "expect": HOME},
        ],
        "teardown": [],
    },
    "screensaver-household": {
        "summary": "Real dream on household photos (Somnambulator), long window, photo_beat attribution",
        "screenshots": False,
        "start": HOME,
        "setup": [],
        "warmup": 0,
        "inner": 1,
        "steps": [{"name": "change", "do": [("dream", None)], "poll": HOUSEHOLD_WINDOW_S, "beats": True,
                   "warmup_poll": 40}],
        "teardown": [("home", None), ("sleep", 5)],
    },
    "rotation": {
        "summary": "Mini emulation (1280x800 @160, auto-rotate off): fleet orientation portrait <-> landscape",
        "screenshots": False,
        "start": HOME,
        "session": [("mini", "landscape")],
        "setup": [("sleep", 1)],
        "warmup": 1,
        "inner": 3,
        "steps": [
            {"name": "to-portrait", "do": [("orientation", "portrait")], "wait": 3.5, "expect": HOME},
            {"name": "to-landscape", "do": [("orientation", "landscape")], "wait": 3.5, "expect": HOME},
        ],
        "teardown": [("orientation", "forget"), ("sleep", 2)],
    },
    "uiautomator-overflow": {
        "summary": "uiautomator dump of each listed screen; flags nodes wider than the screen / h-scrollable",
        "screenshots": False,
        "start": HOME,
        "setup": [],
        "warmup": 0,
        "inner": 1,
        "steps": [],
        "audit": AUDIT_SCREENS,
        "teardown": [("home", None), ("sleep", 2)],
    },
}


def _settings_page(page):
    if not page.replace("-", "").replace("_", "").isalnum():
        raise KeyError(f"bad settings page id: {page}")
    open_page = {"action": "open_screen", "screen": "settings", "page": page}
    return {
        "summary": f"Settings page '{page}' via fleet open_screen, BACK to Home",
        "screenshots": True,
        "start": HOME,
        "setup": [("sleep", 1)],
        "warmup": 0,
        "inner": 1,
        "steps": [
            {"name": "open", "do": [("fleet", open_page)], "wait": 2.5, "expect": SETTINGS,
             "after": [("screenshot", f"settings-{page}")]},
            {"name": "back", "do": [("key", "BACK")], "wait": 4, "expect": HOME},
        ],
        "teardown": [],
    }


def _screensaver_pinned(mode):
    if not mode.isalnum():
        raise KeyError(f"bad screensaver mode: {mode}")
    return {
        "summary": f"Generated test source pinned to '{mode}' ({PIN_INTERVAL_S} s); changes forced by left swipes",
        "screenshots": True,
        "start": HOME,
        "setup": [("test_source", mode), ("sleep", 1), ("screenshot", f"pinned-{mode}")],
        "warmup": 0,
        "inner": 5,
        "steps": [{"name": "change", "do": [("swipe_left", None)], "poll": 12, "beats": True, "skip_first": False}],
        "teardown": [("test_source", None), ("sleep", 3), ("home", None), ("sleep", 3)],
    }


def _mini(orientation, inner_name):
    if orientation not in MINI_SIZES:
        raise KeyError(f"mini-size orientation must be one of {sorted(MINI_SIZES)}")
    inner = get(inner_name)
    return {**inner, "summary": f"{inner['summary']} at Mini size {MINI_SIZES[orientation]} @160",
            "session": [("mini", orientation), *inner.get("session", [])]}


def _audit(screens):
    names = tuple(s for s in screens.split(",") if s)
    unknown = [s for s in names if s not in AUDIT_SCREENS]
    if unknown or not names:
        raise KeyError(f"unknown audit screens {unknown}; known: {', '.join(AUDIT_SCREENS)}")
    return {**BASE["uiautomator-overflow"], "audit": names}


PARAMETRIC = {
    "settings-page": ("settings-page:<page id>", _settings_page),
    "screensaver-pinned": ("screensaver-pinned:<mode>", _screensaver_pinned),
    "mini-size": ("mini-size:<portrait|landscape>:<scenario>", lambda arg: _mini(*arg.split(":", 1))),
    "uiautomator-overflow": ("uiautomator-overflow:<screen,screen>", _audit),
}


def get(name):
    """The scenario dict for [name] (KeyError with a helpful message when unknown)."""
    head, sep, arg = name.partition(":")
    if not sep:
        if name in BASE:
            return BASE[name]
        raise KeyError(f"unknown scenario '{name}'; see `bench.py scenarios`")
    if head not in PARAMETRIC or not arg:
        raise KeyError(f"unknown scenario '{name}'; see `bench.py scenarios`")
    try:
        return PARAMETRIC[head][1](arg)
    except TypeError:
        raise KeyError(f"bad arguments for {PARAMETRIC[head][0]}") from None


def listing():
    rows = [(n, s["summary"], s["screenshots"]) for n, s in BASE.items()]
    rows += [(usage, "parametric", None) for usage, _ in PARAMETRIC.values()]
    return rows
