"""Reminders: add one shared, undated test reminder through a phone test session, watch it reach
the other Portals, delete it, watch the delete arrive, and revoke exactly the sessions we minted.
An undated reminder never announces (ReminderAlerts only fires for due ones), so nothing sounds.
"""

import re
import time
from dataclasses import dataclass

from ..fleet import SessionClient
from ..model import FAIL, SKIP, Result, result
from .common import body

POLL_S = 3
PATH = "/remote/reminders"
OURS = re.compile(r"^Smoke test \d\d:\d\d:\d\d \(auto-deleted\)$")


@dataclass(frozen=True)
class Minted:
    device: object
    client: SessionClient
    remote_enabled: bool
    undo: object


def _revoke(ctx, device, sid):
    r = ctx.fleet.action(device, {"action": "phone_test_session", "op": "revoke_id", "id": sid})
    return r.ok and body(r).get("removed") is True


def _mint(ctx, device):
    """A test session (only ever revoked by its own id), or None."""
    r = ctx.fleet.action(device, {"action": "phone_test_session", "op": "mint"})
    b = body(r)
    if not r.ok or not b.get("token"):
        return None
    client = SessionClient(device, b["token"], ctx.fleet.locks, ctx.redactor)
    sid = client.session_id
    undo = ctx.on_cleanup(f"{device.name}: revoke test session", lambda: _revoke(ctx, device, sid))
    return Minted(device, client, bool(b.get("remoteEnabled")), undo)


def _revoke_all(ctx, minted):
    failed = []
    for m in minted:
        if _revoke(ctx, m.device, m.client.session_id):
            ctx.done(m.undo)
        else:
            failed.append(m.device.name)
    return failed


def _find(client, title):
    r = client.call("GET", PATH)
    rows = body(r).get("reminders") or []
    return r.rc == 0, next((x for x in rows if x.get("title") == title), None)


def _wait(clients, title, present, timeout_s):
    """{device name: seconds until [title] is present/absent, or None on timeout}."""
    t0 = time.monotonic()
    pending = dict(clients)
    seen = {}
    while pending and time.monotonic() - t0 < timeout_s:
        for name, c in list(pending.items()):
            ok, row = _find(c, title)
            if ok and (row is not None) == present:
                seen = {**seen, name: round(time.monotonic() - t0, 1)}
                pending = {k: v for k, v in pending.items() if k != name}
        if pending:
            time.sleep(POLL_S)
    return {**seen, **{n: None for n in pending}}


def sync(ctx):
    minted = [m for m in (_mint(ctx, d) for d in ctx.devices) if m is not None]
    usable = {m.device.name: m.client for m in minted if m.remote_enabled}
    out = [Result(d.name, "reminders.sync", SKIP, "phone remote is off here (the suite doesn't turn it on)")
           for d in ctx.devices if d.name not in usable]
    try:
        out += _round_trip(ctx, usable) if usable else []
    finally:
        failed = _revoke_all(ctx, minted)
    if failed:
        out.append(Result(", ".join(failed), "reminders.sync", FAIL, "test session not revoked"))
    return out


def _round_trip(ctx, clients):
    origin_name, origin = next(iter(clients.items()))
    stale = _purge_stale(origin)
    if stale:
        ctx.log(f"{origin_name}: deleted {stale} stale smoke reminder(s) from an earlier run")
    title = f"Smoke test {time.strftime('%H:%M:%S')} (auto-deleted)"
    added = origin.call("POST", PATH, {"action": "add", "title": title})
    row = next((x for x in body(added).get("reminders") or [] if x.get("title") == title), None)
    if row is None:
        return [Result(origin_name, "reminders.sync", FAIL, f"add failed: {added.text[:100]}")]
    peers = {n: c for n, c in clients.items() if n != origin_name}
    clean = False
    try:
        arrived = _wait(peers, title, True, ctx.options.sync_wait_s)
        deleted = origin.call("POST", PATH, {"action": "delete", "id": row["id"], "base": row.get("seq", 0)})
        gone_here = deleted.rc == 0 and not any(x.get("id") == row["id"] for x in body(deleted).get("reminders") or [])
        removed = _wait(peers, title, False, ctx.options.sync_wait_s)
        clean = gone_here and all(v is not None for v in removed.values())
    finally:
        if not clean:  # before the sessions are revoked: they are the only way to delete it
            _delete_everywhere(clients, title)
    out = [result(origin_name, "reminders.sync", gone_here,
                  f"added + deleted here (origin); {len(peers)} peer(s) watched" if gone_here
                  else f"delete failed: {deleted.text[:100]}")]
    for n in peers:
        a, r = arrived[n], removed[n]
        ok = a is not None and r is not None
        out.append(result(n, "reminders.sync", ok, f"arrived in {a}s, delete in {r}s" if ok
                          else f"arrived={a} deleted={r} (waited {ctx.options.sync_wait_s}s)"))
    return out


def _delete_everywhere(clients, title):
    for c in clients.values():
        ok, row = _find(c, title)
        if ok and row is not None:
            c.call("POST", PATH, {"action": "delete", "id": row["id"], "base": row.get("seq", 0)})


def _purge_stale(client):
    """Deletes reminders only this suite creates (exact title pattern) left by an aborted run."""
    r = client.call("GET", PATH)
    rows = [x for x in body(r).get("reminders") or [] if OURS.match(str(x.get("title", "")))]
    for x in rows:
        client.call("POST", PATH, {"action": "delete", "id": x["id"], "base": x.get("seq", 0)})
    return len(rows)
