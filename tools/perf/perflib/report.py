"""Result files: result.json (everything) and result.md (tables)."""

import json
import os
import statistics

from . import compare

SCHEMA = 1


def write(folder, result):
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, "result.json")
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, sort_keys=False)
    os.replace(tmp, path)
    with open(os.path.join(folder, "result.md"), "w", encoding="utf-8") as f:
        f.write(markdown(result))
    return path


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _med(xs):
    return statistics.median(xs) if xs else 0


def _n(x):
    return f"{x:.0f}" if float(x).is_integer() or abs(x) >= 10 else f"{x:.1f}"


def steps_table(result):
    samples = result.get("samples", [])
    labels = [b["label"] for b in result.get("builds", [])]
    lines = ["| Step | Build | Samples | >32 per sample | >32 total | >60 total | Worst median | Worst per sample "
             "| p90 median | p99 median | Janky % median |",
             "|---|---|---|---|---|---|---|---|---|---|---|"]
    for step in compare.steps_of(samples):
        for label in labels:
            ss = [s["summary"] for s in samples if s["step"] == step and s["build"] == label]
            if not ss:
                continue
            lines.append(
                f"| {step} | {label} | {len(ss)} | {', '.join(str(s['over32']) for s in ss)} | "
                f"{sum(s['over32'] for s in ss)} | {sum(s['over60'] for s in ss)} | {_n(_med([s['worst'] for s in ss]))} | "
                f"{', '.join(_n(s['worst']) for s in ss)} | {_n(_med([s['p90'] for s in ss]))} | "
                f"{_n(_med([s['p99'] for s in ss]))} | {_n(_med([s['janky_pct'] for s in ss]))} |")
    return "\n".join(lines)


def memory_table(result):
    mem = result.get("memory", [])
    if not mem:
        return ""
    lines = ["| Build | Rep | When | PSS MB | Graphics MB |", "|---|---|---|---|---|"]
    lines += [f"| {m['build']} | {m['rep']} | {m['when']} | {m['pss_mb']} | {m['graphics_mb']} |" for m in mem]
    return "\n".join(lines)


def audit_table(result):
    audits = result.get("audits", [])
    if not audits:
        return ""
    lines = ["| Build | Screen | Top | Nodes | Flagged | First findings |", "|---|---|---|---|---|---|"]
    for a in audits:
        first = "; ".join(f"{f['class']} '{f['label']}' {f['bounds']} ({', '.join(f['reasons'])})"
                          for f in a["findings"][:3])
        lines.append(f"| {a['build']} | {a['screen']} | {a['top']} | {a['nodes']} | {len(a['findings'])} | {first} |")
    return "\n".join(lines)


def markdown(result):
    builds = "; ".join(f"{b['label']} = {b['kind']} {b.get('versionName') or os.path.basename(b['apk'])} "
                       f"(sha {b['sha256'][:8]}...)" for b in result.get("builds", []))
    r = result.get("restore") or {}
    parts = [
        f"# {result['scenario']} on {result['device']} - {result['status'].upper()}",
        "",
        f"- {result.get('started', '?')} -> {result.get('ended', '?')}; reps {result.get('reps')}, "
        f"order {' '.join(result.get('order', []))}",
        f"- Builds: {builds}",
        f"- Screenshots: {len([s for s in result.get('screenshots', []) if s.get('file')]) or 'none'}",
        f"- Restore: {'OK' if r.get('ok') else 'INCOMPLETE'} "
        + "; ".join(f"{s['step']}: {'ok' if s['ok'] else 'FAILED ' + s['detail']}" for s in r.get("steps", [])),
    ]
    if result.get("error"):
        parts.append(f"- Error: {result['error']}")
    if result.get("ota"):
        from .otaphases import markdown as ota_markdown
        parts += ["", ota_markdown(result["ota"])]
        return "\n".join(parts) + "\n"
    parts += ["", "## Frames (framestats, flags 0; worst/p90/p99 in ms)", "", steps_table(result)]
    for title, body in (("Memory", memory_table(result)), ("uiautomator overflow", audit_table(result))):
        if body:
            parts += ["", f"## {title}", "", body]
    if len(result.get("builds", [])) > 1:
        parts += ["", "## A/B verdict", "", compare.table(result)]
    return "\n".join(parts) + "\n"
