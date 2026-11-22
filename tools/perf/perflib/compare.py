"""A/B verdicts. Lower is better for every metric.

Noise rule: B "wins" a metric only when B's median beats A's median by more than A's spread
(max - min over A's samples); it "loses" when it is worse by more than that spread; otherwise "noise".
"""

import statistics

METRICS = ("over32", "worst", "sum_over32", "p90", "janky_pct", "over60")


def _values(samples, build, step, metric):
    return [s["summary"][metric] for s in samples if s["build"] == build and s["step"] == step
            and s["summary"].get("frames", 0) > 0]


def verdict(a_values, b_values):
    """(verdict, a_median, a_spread, b_median). Pure: the rule under test."""
    if not a_values or not b_values:
        return "n/a", None, None, None
    a_med, b_med = statistics.median(a_values), statistics.median(b_values)
    spread = max(a_values) - min(a_values)
    if b_med < a_med - spread:
        v = "win"
    elif b_med > a_med + spread:
        v = "loss"
    else:
        v = "noise"
    return v, a_med, spread, b_med


def steps_of(samples):
    seen = []
    for s in samples:
        if s["step"] not in seen:
            seen.append(s["step"])
    return seen


def rows(result):
    """[(step, metric, A, B, a_med, a_spread, b_med, verdict)] for the baseline (first build) vs each other."""
    labels = [b["label"] for b in result.get("builds", [])]
    samples = result.get("samples", [])
    out = []
    if len(labels) < 2:
        return out
    base = labels[0]
    for other in labels[1:]:
        for step in steps_of(samples):
            for metric in METRICS:
                v, a_med, spread, b_med = verdict(_values(samples, base, step, metric),
                                                  _values(samples, other, step, metric))
                out.append((step, metric, base, other, a_med, spread, b_med, v))
    return out


def _fmt(x):
    if x is None:
        return "-"
    return f"{x:.0f}" if abs(x) >= 10 or float(x).is_integer() else f"{x:.1f}"


def table(result):
    r = rows(result)
    if not r:
        return "(compare needs at least two builds in the result)"
    lines = ["| Step | Metric | A | A median | A spread | B | B median | Verdict |",
             "|---|---|---|---|---|---|---|---|"]
    for step, metric, a, b, a_med, spread, b_med, v in r:
        lines.append(f"| {step} | {metric} | {a} | {_fmt(a_med)} | {_fmt(spread)} | {b} | {_fmt(b_med)} | "
                     f"{'**' + v + '**' if v in ('win', 'loss') else v} |")
    wins = sum(1 for x in r if x[-1] == "win")
    losses = sum(1 for x in r if x[-1] == "loss")
    lines.append("")
    lines.append(f"{wins} win(s), {losses} loss(es), {len(r) - wins - losses} noise/n-a "
                 f"(win = B median beats A median by more than A's spread; lower is better)")
    return "\n".join(lines)
