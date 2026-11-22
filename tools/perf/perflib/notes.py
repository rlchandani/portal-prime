"""One line per device session in /tmp/portal-lock-notes-<portal>.txt (the orchestrator's log)."""

import sys
import time

from . import notes_file


def line(result, tag, started_epoch=None, released=True):
    start = time.strftime("%H:%M", time.localtime(started_epoch)) if started_epoch else "?"
    end = time.strftime("%H:%M %Z")
    builds = ", ".join(f"{b['label']} {b['kind']} {b.get('versionName') or ''} (sha {b['sha256'][:8]}...)".replace("  ", " ")
                       for b in result.get("builds", []))
    shots = len([s for s in result.get("screenshots", []) if s.get("file")])
    r = result.get("restore") or {}
    fin = r.get("final") or {}
    pri = result.get("priors") or {}
    music = pri.get("music_volume")
    if r.get("ok"):
        final = (f"FINAL: OFFICIAL {fin.get('official', {}).get('versionName', '?')} (on-device sha "
                 f"{str(fin.get('device_sha256', ''))[:8]}... = release json), dev off, wm reset, rotation auto, "
                 f"test source off, cc_debug cleared, HomeActivity on top + default Home.")
    else:
        bad = [s["step"] for s in r.get("steps", []) if not s["ok"]] or [r.get("skipped", "not run")]
        final = f"RESTORE INCOMPLETE ({', '.join(bad)}) - check the Portal."
    ota = result.get("ota")
    if ota:
        total = next((t["seconds"] for t in ota.get("totals", []) if t["total"].startswith("whole")), None)
        final = (f"OTA {ota.get('mode')} via fleet /update/ota check+install (dev off for it): "
                 f"{ota.get('outcome')}, whole update {total} s. " + final)
    return (f"{time.strftime('%a %b %d %H:%M:%S %Z %Y')} {tag} {start}-{end} ({'released' if released else 'held'}, "
            f"{result.get('status')}): perf-bench {result.get('scenario')} x{result.get('reps')} "
            f"order {' '.join(result.get('order', []))}; dev on; plain adb install -r of {builds or 'nothing'}; "
            f"no force-stop; {'NO screenshots' if not shots else f'{shots} allowed screenshots (scratch)'}; "
            f"framestats{' + meminfo' if result.get('options', {}).get('meminfo') else ''}. "
            f"Music {music}->0->{music}. {final}")


def append(serial, text):
    try:
        with open(notes_file(serial), "a", encoding="utf-8") as f:
            f.write(text.rstrip("\n") + "\n")
    except OSError as e:
        print(f"warning: could not append the session note to {notes_file(serial)}: {e}", file=sys.stderr)
